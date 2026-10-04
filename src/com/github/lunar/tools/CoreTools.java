package com.github.lunar.tools;

import com.github.lunar.io.Json;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.jobs.ISchedulingRule;

/** Bounded session discovery, snapshots and result shaping shared by every domain. */
public final class CoreTools implements ToolProvider {
    static final Set<String> INTERNAL = Set.of("find_tools", "load_toolset", "get_session_info",
            "resume_result", "batch", "capture_baseline", "get_delta");
    /** The tools a client can see before it loads anything. {@code launch} is deliberately absent: it
     *  is one of only two EXECUTE tools, and the only one that would otherwise be visible at
     *  session start, so a first tools/list offered arbitrary code execution to every client
     *  before it had chosen to ask for it. {@code run_tests} is EXECUTE too and is already
     *  gated behind its toolset. */
    private static final Set<String> CORE = Set.of("find_tools", "load_toolset", "get_session_info",
            "resume_result", "batch", "capture_baseline", "get_delta", "list_projects",
            "project_info", "list_files", "read_file", "search_text", "get_problems",
            "build_project", "wait_until_quiet", "list_launch_configs", "get_console_output");
    private static final int RETAIN_BYTES = 2 * 1024 * 1024;
    private static final AtomicLong generation = new AtomicLong();
    private static final IResourceChangeListener listener = event -> generation.incrementAndGet();
    public record Baseline(String tool, Map<String, Object> arguments, Object data, String hash) { }

    public static void start() {
        ResourcesPlugin.getWorkspace().addResourceChangeListener(listener, IResourceChangeEvent.POST_CHANGE);
    }
    public static void stop() {
        ResourcesPlugin.getWorkspace().removeResourceChangeListener(listener);
    }
    /** Would {@code bytes} of output push this session past its trailing window? A check, not a
     *  charge: the caller bills what the call actually emitted once it knows, so a 200-byte response
     *  costs 200 bytes and a refused call costs nothing at all. Charging the reservation instead
     *  capped every session at WINDOW_BYTES/maxBytes calls an hour -- 512 by default -- however
     *  small the responses were, and a client retrying on the refusal billed its own lockout. */
    static boolean outputWindowAllows(ToolDispatcher.Session s,int bytes){
        return s.windowedBytes()+bytes<=ToolDispatcher.WINDOW_BYTES;
    }
    static String group(Tool tool) {
        String name = tool.getClass().getName();
        if (name.contains(".workspace.") || tool instanceof ListProjects) return "workspace";
        if (name.contains(".run.")) return "run";
        if (name.contains(".debug.")) return "debug";
        return "core";
    }
    static boolean visible(String name, ToolDispatcher.Session session) {
        Tool tool = ToolRegistry.all().get(name);
        return CORE.contains(name) || tool != null && (session.loaded.contains("all")
                || session.loaded.contains(group(tool)));
    }
    private static Map<String, Object> anyObject() {
        return Map.of("type", "object", "additionalProperties", true, "default", Map.of());
    }
    private interface Action { Object call(Map<String, Object> args, CallBudget budget) throws Exception; }
    private static Tool tool(String name, String description, Map<String, Object> properties,
            Action action, String... required) {
        ToolSpec spec = new ToolSpec(name, name.replace('_', ' '), description,
                Tools.object(properties, required), name.equals("batch")
                        ? ToolSpec.RiskTier.DESTRUCTIVE : ToolSpec.RiskTier.READ);
        return new Tool() {
            public ToolSpec spec() { return spec; }
            public ISchedulingRule schedulingRule() { return null; }
            public ToolResult call(Map<String, Object> args, CallBudget budget) throws Exception {
                Object result = action.call(args,budget);
                return result instanceof ToolResult r ? r : ToolResult.ok(result,null);
            }
        };
    }
    @Override public List<Tool> tools() {
        return List.of(
            tool("find_tools", "Rank tools by query; returns toolset names for load_toolset. Empty query lists every tool up to limit, which defaults to 8; pass limit 200 for the full roster.",
                Map.of("query", Tools.string(), "limit", Tools.integer(8, 1, 200)),
                (a,b) -> find((String)a.get("query"), ((Number)a.get("limit")).intValue()), "query"),
            tool("load_toolset", "Load workspace, run, debug or all tools for this session.",
                Map.of("name", Tools.enumString("workspace", "run", "debug", "all")),
                (a,b) -> {
                    var s = ToolDispatcher.sessionFor(b.sessionId()); s.loaded.add((String)a.get("name"));
                    return Map.of("loaded", s.loaded.stream().sorted().toList(),
                            "availableTools", ToolDispatcher.visibleTools(s).size());
                }, "name"),
            tool("get_session_info", "Report this session's loaded tools and remaining output budget.",
                Map.of(), (a,b) -> {
                    var s = ToolDispatcher.sessionFor(b.sessionId());
                    return Map.of("sessionId",s.id(),"toolCalls",s.toolCalls(),"loaded",s.loaded.stream().sorted().toList(),
                            "remainingOutputBytes", Math.max(0, ToolDispatcher.WINDOW_BYTES-s.windowedBytes()),
                            "workspaceGeneration",generation.get(),"activeRequests",ToolDispatcher.activeRequests(b.sessionId()));
                }),
            tool("resume_result", "Read a retained JSON result by character offset; cursors belong to this session.",
                Map.of("cursor",Tools.string(),"offset",Tools.integer(0,0,RETAIN_BYTES),
                        "maxBytes",Tools.integer(8192,256,32768)), (a,b) -> {
                    var s=ToolDispatcher.sessionFor(b.sessionId()); String text;
                    synchronized(s){text=s.cursors.get((String)a.get("cursor"));}
                    if(text==null)throw new RequestError("cursor_invalid","unknown, expired or foreign cursor");
                    int offset=((Number)a.get("offset")).intValue();
                    if(offset>text.length() || offset>0 && offset<text.length() && Character.isLowSurrogate(text.charAt(offset)))
                        throw new RequestError("invalid_range","offset is outside the retained text or splits a character");
                    // This page carries nextOffset, so shape() exempts it and the declared maxBytes
                    // is the contract; pre-clamping to the session budget only starved the caller.
                    int end=cut(text,offset,((Number)a.get("maxBytes")).intValue());
                    return Map.of("text",text.substring(offset,end),"offset",offset,"nextOffset",end,
                            "offsetUnit","UTF-16 character","done",end==text.length());
                },"cursor"),
            tool("batch", "Execute up to 16 loaded tools sequentially; stops on failure, without rollback.",
                Map.of("calls",Map.of("type","array","items",Tools.object(
                        Map.of("name",Tools.string(),"arguments",anyObject()),"name"))), (a,b) -> {
                    List<?> calls=(List<?>)a.get("calls");
                    if(calls.isEmpty()||calls.size()>16)throw new RequestError("invalid_range","batch needs 1 to 16 calls");
                    // Validate the complete batch before its first side effect.
                    for(Object value:calls){Map<?,?> c=(Map<?,?>)value; String name=(String)c.get("name");
                        Tool t=ToolRegistry.all().get(name);
                        if(INTERNAL.contains(name)||t==null||!visible(name,ToolDispatcher.sessionFor(b.sessionId())))
                            throw new RequestError("tool_unavailable","unknown, unloaded or nested core tool: "+name);
                        SchemaValidator.validate(t.spec().inputSchema(),c.get("arguments"));
                    }
                    List<Object> results=new ArrayList<>();
                    for(Object value:calls){Map<?,?> c=(Map<?,?>)value;ToolResult r;
                        try{b.checkCancelled();r=ToolDispatcher.runNested((String)c.get("name"),c.get("arguments"),b,false);}
                        catch(Exception failure){r=ToolResult.error("child_failed",String.valueOf(failure.getMessage()),
                                Map.of("name",c.get("name"),"exception",failure.getClass().getName()),null);}
                        r=shape(ToolDispatcher.sessionFor(b.sessionId()),r,
                                Math.max(1024,b.maxOutputBytes()/calls.size()),false);
                        @SuppressWarnings("unchecked") Map<String,Object> outcome=new LinkedHashMap<>((Map<String,Object>)Json.parse(r.toJson()));
                        outcome.put("name",c.get("name"));results.add(outcome); if(!r.ok())break;
                    }
                    Map<String,Object> data=Map.of("results",results,"completed",results.size(),"requested",calls.size());
                    if(results.size()<calls.size() || !Boolean.TRUE.equals(((Map<?,?>)results.get(results.size()-1)).get("ok")))
                        return ToolResult.error("batch_failed","Batch stopped on a failed call; no rollback",data,null);
                    return data;
                },"calls"),
            tool("capture_baseline", "Capture a loaded read tool's data for a later explicit delta.",
                Map.of("tool",Tools.string(),"arguments",anyObject()),(a,b)->{
                    String name=(String)a.get("tool");
                    @SuppressWarnings("unchecked") Map<String,Object> arguments=(Map<String,Object>)a.get("arguments");
                    ToolResult r=ToolDispatcher.runNested(name,arguments,b,true);
                    if(!r.ok())throw new RequestError("baseline_failed",Json.write(r.error()));
                    String raw=Json.write(r.data()); if(bytes(raw)+bytes(Json.write(arguments))>RETAIN_BYTES)
                        throw new RequestError("baseline_too_large","baseline data and arguments exceed 2 MiB");
                    String id=UUID.randomUUID().toString(), hash=hash(raw);
                    var s=ToolDispatcher.sessionFor(b.sessionId());
                    synchronized(s){s.baselines.put(id,new Baseline(name,arguments,r.data(),hash));trimBaselines(s);}
                    return Map.of("baselineId",id,"hash",hash,"bytes",bytes(raw));
                },"tool"),
            tool("get_delta", "Compare a retained baseline with a fresh read; unchanged data returns only its hash.",
                Map.of("baselineId",Tools.string()),(a,b)->{
                    var s=ToolDispatcher.sessionFor(b.sessionId()); Baseline baseline;
                    synchronized(s){baseline=s.baselines.get((String)a.get("baselineId"));}
                    if(baseline==null)throw new RequestError("baseline_invalid","unknown, expired or foreign baseline");
                    ToolResult r=ToolDispatcher.runNested(baseline.tool(),baseline.arguments(),b,true);
                    if(!r.ok())throw new RequestError("baseline_failed",Json.write(r.error()));
                    String next=hash(Json.write(r.data()));
                    return Map.of("baselineId",a.get("baselineId"),"beforeHash",baseline.hash(),"afterHash",next,
                            "changed",!next.equals(baseline.hash()),"changes",delta(baseline.data(),r.data()));
                },"baselineId")
        );
    }
    private static List<Object> find(String query,int limit){
        String[] words=query.toLowerCase(Locale.ROOT).trim().split("\\s+");
        List<Map<String,Object>> ranked=new ArrayList<>();
        for(Tool t:ToolRegistry.all().values()){ToolSpec s=t.spec();int score=0;
            String name=s.name().replace('_',' '), description=s.description().toLowerCase(Locale.ROOT);
            String raw=s.name();
            for(String word:words){if(!word.isEmpty()){
                if(name.contains(word))score+=3;
                // Match the literal tool name too: "create_project" must find create_project.
                if(raw.contains(word))score+=2;
                if(description.contains(word))score++;}}
            // An empty query is the enumeration case: every tool scores equally and the limit orders them.
            if(score>0||words.length==0||(words.length==1&&words[0].isEmpty()))
                ranked.add(Map.of("name",s.name(),"toolset",group(t),"riskTier",s.riskTier().wire(),"score",score));
        }
        ranked.sort(Comparator.<Map<String,Object>>comparingInt(m->-((Number)m.get("score")).intValue())
                .thenComparing(m->(String)m.get("name")));
        return new ArrayList<>(ranked.subList(0,Math.min(limit,ranked.size())));
    }
    static ToolResult shape(ToolDispatcher.Session s,ToolResult r,int maxBytes,boolean resume){
        Map<String,Object> retained=new LinkedHashMap<>();retained.put("data",r.data());retained.put("error",r.error());
        String raw=Json.write(retained);int before=bytes(ToolDispatcher.encodeResult(r,s.id));
        Object data=r.data();Map<String,Object> error=r.error();String cursor=null;
        var m=r.meta()==null?new ToolResult.Meta(0,0,false,null,0,0,true):r.meta();
        // A tool that already pages its own output owns the budget: replacing it with a cursor
        // stub would hide nextOffset/hasMore and strand the documented read loop.
        boolean selfPaging=data instanceof Map<?,?> pd&&pd.containsKey("nextOffset");
        if(!selfPaging&&before>maxBytes-100){
            if(bytes(raw)>RETAIN_BYTES)return ToolResult.error("output_too_large",
                    "The tool executed; its data exceeds 2 MiB. Page the source read tool before retrying; do not repeat writes",r.meta());
            cursor=UUID.randomUUID().toString();
            synchronized(s){s.cursors.put(cursor,raw);
                while(s.cursors.size()>8||s.cursors.values().stream().mapToInt(CoreTools::bytes).sum()>RETAIN_BYTES)
                    s.cursors.remove(s.cursors.keySet().iterator().next());}
            error=r.error()==null?null:Map.of("code",r.error().getOrDefault("code","tool_failed"),"message","Resume the result for error details");
            int previewBytes=maxBytes/8;
            do{
                int end=cut(raw,0,previewBytes);
                data=Map.of("preview",raw.substring(0,end)+" ... elided ..."+raw.substring(tailCut(raw,previewBytes/2)), "cursor",cursor,
                        "resumeTool","resume_result","offset",0);
                previewBytes/=2;
            }while(bytes(ToolDispatcher.encodeResult(new ToolResult(r.ok(),data,error,
                    new ToolResult.Meta(before,maxBytes,true,cursor,m.elapsedMs(),m.deadlineMs(),m.cancellationHonoured())),s.id))>maxBytes&&previewBytes>0);
        }
        ToolResult shaped=new ToolResult(r.ok(),data,error,new ToolResult.Meta(before,0,cursor!=null,cursor,
                m.elapsedMs(),m.deadlineMs(),m.cancellationHonoured()));
        int after=0;
        for(int i=0;i<4;i++){
            after=bytes(ToolDispatcher.encodeResult(shaped,s.id));
            shaped=new ToolResult(r.ok(),data,error,new ToolResult.Meta(before,after,cursor!=null,cursor,
                    m.elapsedMs(),m.deadlineMs(),m.cancellationHonoured()));
        }
        if(!selfPaging&&after>maxBytes)return ToolResult.error("output_budget_too_small","Increase lunar/maxBytes",null);
        return shaped;
    }
    private static void trimBaselines(ToolDispatcher.Session s){
        while(s.baselines.size()>8||s.baselines.values().stream().mapToInt(b->bytes(Json.write(b.data()))+bytes(Json.write(b.arguments()))).sum()>RETAIN_BYTES)
            s.baselines.remove(s.baselines.keySet().iterator().next());
    }
    private static Object delta(Object before,Object after){
        if(java.util.Objects.equals(before,after))return Map.of();
        if(before instanceof Map<?,?> old&&after instanceof Map<?,?> next){Map<String,Object> changes=new LinkedHashMap<>();
            for(var e:next.entrySet())if(!old.containsKey(e.getKey())){Map<String,Object> added=new LinkedHashMap<>();added.put("added",e.getValue());changes.put(String.valueOf(e.getKey()),added);}
                else if(!java.util.Objects.equals(old.get(e.getKey()),e.getValue()))changes.put(String.valueOf(e.getKey()),delta(old.get(e.getKey()),e.getValue()));
            for(Object key:old.keySet())if(!next.containsKey(key))changes.put(String.valueOf(key),Map.of("removed",true));
            return changes;
        }
        if(before instanceof String old&&after instanceof String next){int prefix=0,suffix=0;
            while(prefix<old.length()&&prefix<next.length()&&old.charAt(prefix)==next.charAt(prefix))prefix++;
            if(prefix>0&&prefix<next.length()&&Character.isLowSurrogate(next.charAt(prefix)))prefix--;
            while(suffix<old.length()-prefix&&suffix<next.length()-prefix&&old.charAt(old.length()-1-suffix)==next.charAt(next.length()-1-suffix))suffix++;
            if(suffix>0&&Character.isLowSurrogate(next.charAt(next.length()-suffix)))suffix--;
            return Map.of("offset",prefix,"deleteCharacters",old.length()-prefix-suffix,"insert",next.substring(prefix,next.length()-suffix));
        }
        Map<String,Object> replacement=new LinkedHashMap<>();replacement.put("value",after);return replacement;
    }
    private static int bytes(String text){return text.getBytes(StandardCharsets.UTF_8).length;}
    private static String hash(String text){try{return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
    private static int cut(String text,int start,int maxBytes){int end=start,total=0;
        while(end<text.length()){int cp=text.codePointAt(end),n=cp<=0x7f?1:cp<=0x7ff?2:cp<=0xffff?3:4;
            if(total+n>maxBytes)break;total+=n;end+=Character.charCount(cp);}return end;}
    private static int tailCut(String text,int maxBytes){int start=text.length(),total=0;
        while(start>0){int cp=text.codePointBefore(start),n=cp<=0x7f?1:cp<=0x7ff?2:cp<=0xffff?3:4;
            if(total+n>maxBytes)break;total+=n;start-=Character.charCount(cp);}return start;}
    public static void main(String[] args){
        String text="a😀éz";assert cut(text,0,5)==3;assert cut(text,3,2)==4;
        // A resume page at the declared max must be reachable, not silently reduced to the budget.
        assert cut("a".repeat(40000),0,32768)==32768;
        @SuppressWarnings("unchecked") Map<String,Object> d=(Map<String,Object>)delta("a😀oldz","a😀newz");
        assert ((Number)d.get("offset")).intValue()==3;assert d.get("insert").equals("new");
        assert delta(Map.of("x",1),Map.of("x",1)).equals(Map.of());
        var s=new ToolDispatcher.Session("check");String large="x".repeat(5000);
        ToolResult shaped=shape(s,ToolResult.ok(large,null),1024,false);
        assert shaped.meta().truncated()&&s.cursors.containsKey(shaped.meta().resumeCursor());
        assert shaped.meta().bytesAfterShaping()<=1024;
        for(String tricky:List.of("\\\"".repeat(5000),"漢😀".repeat(2000))) {
            ToolResult bounded=shape(s,ToolResult.ok(tricky,null),1024,false);
            assert bytes(ToolDispatcher.encodeResult(bounded,s.id))<=1024;
        }
        ToolResult failed=shape(s,ToolResult.error("batch_failed","Failed",Map.of("results",large),null),1024,false);
        assert failed.meta().truncated()&&bytes(ToolDispatcher.encodeResult(failed,s.id))<=1024;
        Map<String,Object> nullValue=new LinkedHashMap<>();nullValue.put("added",null);
        assert !delta(Map.of(),nullValue).equals(Map.of());
        // The window bills what a call emitted, not what it reserved. Charging the reservation
        // capped every session at 512 calls an hour however small the responses were, and
        // charging a refused call made a client that retried on session_budget_exhausted extend
        // its own lockout. A thousand 200-byte calls have to fit in an 8 MiB window.
        var windowed=new ToolDispatcher.Session("window-check");
        for(int i=0;i<1000;i++){
            if(!outputWindowAllows(windowed,16384))
                throw new AssertionError("1000 small calls exhausted the window at call "+i
                        +": a refused call was billed, or the reservation was charged instead of the bytes");
            windowed.charge(200);
        }
        assert windowed.windowedBytes()==200_000L:"charged the reservation, not the emitted bytes";
        // A refusal costs the caller nothing, so a retry loop on it can still clear.
        assert !outputWindowAllows(windowed,(int)ToolDispatcher.WINDOW_BYTES);
        assert windowed.windowedBytes()==200_000L:"a refused call was charged";
        // A full window still stops a live burst, and only ageing the clock decays it: the expiry
        // is the fix, so drive the same decay a real hour would and then restore the clock, or
        // every later charge runs against a frozen time source.
        var full=new ToolDispatcher.Session("window-full");
        while(full.windowedBytes()<ToolDispatcher.WINDOW_BYTES) full.charge(ToolDispatcher.WINDOW_BYTES/1024);
        assert !outputWindowAllows(full,1024);
        long t0=ToolDispatcher.now();
        try {
            ToolDispatcher.ageWindow(()->t0+61*60_000L);
            assert full.windowedBytes()==0&&outputWindowAllows(full,1024);
        } finally {
            ToolDispatcher.ageWindow(System::currentTimeMillis);
        }
        // An expired session must be answered with 404, not a 200-wrapped JSON-RPC error. The
        // transport decides that on hasSession, and hasSession was the one entry point that
        // never evicted, so it agreed the session was live while sessionFor evicted it and
        // threw -- the client saw 200 then 404 for the same id and could not recover. lastSeen
        // is package-private, so the check ages the clock without adding a seam for it.
        String staleId=ToolDispatcher.createSession();
        ToolDispatcher.Session stale=ToolDispatcher.sessionFor(staleId);
        stale.lastSeen=System.nanoTime()-java.util.concurrent.TimeUnit.MINUTES.toNanos(31);
        if(ToolDispatcher.hasSession(staleId))
            throw new AssertionError("hasSession reports an idle-expired session as live, so the transport will not 404 it");
        if(ToolDispatcher.evictIdleSessions()!=0)
            throw new AssertionError("hasSession did not evict the expired session itself");
        ToolDispatcher.deleteSession(staleId);
        // And the ceiling itself must not have been silently raised along the way.
        assert ToolDispatcher.WINDOW_BYTES==8L*1024*1024;
        System.out.println("CORE CHECK PASS");
    }
}
