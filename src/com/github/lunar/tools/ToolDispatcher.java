package com.github.lunar.tools;

import com.github.lunar.io.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.eclipse.core.runtime.jobs.Job;

/**
 * Routes a JSON-RPC method to a tool and turns its outcome into the wire response.
 *
 * <p>Session state is keyed on the {@code Mcp-Session-Id} header. A missing header is not a
 * client error worth failing on, so it maps to one implicit shared session rather than a rejection.
 */
public final class ToolDispatcher {

    private static final Map<String, Session> SESSIONS = new ConcurrentHashMap<>();
    private static final Map<String, CallBudget> ACTIVE = new ConcurrentHashMap<>();
    private static final java.util.concurrent.Semaphore MUTATIONS = new java.util.concurrent.Semaphore(1,true);

    private ToolDispatcher() {
    }

    /** Per-session bookkeeping. Deliberately tiny: P2 has nothing to store yet, and inventing a
     * slot for future state would be scaffolding for a caller that does not exist. */
    public static final class Session {
        final String id;
        final AtomicLong toolCalls = new AtomicLong();
        final AtomicLong emittedBytes = new AtomicLong();
        final Set<String> loaded = ConcurrentHashMap.newKeySet();
        final Map<String, String> cursors = new LinkedHashMap<>();
        final Map<String, CoreTools.Baseline> baselines = new LinkedHashMap<>();
        volatile boolean closed;
        Session(String id) { this.id = id; }
        public String id() { return id; }
        public long toolCalls() { return toolCalls.get(); }
    }

    public static Session sessionFor(String header) {
        String id = header == null || header.isBlank() ? "implicit" : header.trim();
        if (id.equals("implicit")) return SESSIONS.computeIfAbsent(id, Session::new);
        Session existing = SESSIONS.get(id);
        if (existing == null || existing.closed) throw new RequestError("session_unavailable","unknown MCP session");
        return existing;
    }


    public static void exposeAllTools(String session) {
        sessionFor(session).loaded.add("all");
    }

    public static String createSession() {
        synchronized (SESSIONS) {
            if (SESSIONS.size() >= 64) {
                throw new RequestError("session_capacity_reached","session capacity reached; DELETE an unused session");
            }
            String id = java.util.UUID.randomUUID().toString();
            SESSIONS.put(id, new Session(id));
            return id;
        }
    }

    public static boolean hasSession(String id) {
        return id == null || SESSIONS.containsKey(id);
    }

    public static void deleteSession(String id) {
        if (id != null) {
            Session removed = SESSIONS.remove(id);
            if (removed != null) removed.closed = true;
            ACTIVE.forEach((key, budget) -> {
                if (key.startsWith(id + "\n")) budget.cancel();
            });
        }
    }

    public static void cancel(String session, String requestId) {
        CallBudget budget = ACTIVE.get((session == null ? "implicit" : session) + "\n"
                + canonicalId(requestId));
        if (budget != null) budget.cancel();
    }

    private static String canonicalId(String raw) { return Json.write(Json.parse(raw)); }

    public static void shutdown() {
        ACTIVE.values().forEach(CallBudget::cancel);
        SESSIONS.values().forEach(s -> s.closed = true);
        SESSIONS.clear();
        ACTIVE.entrySet().removeIf(e -> !e.getValue().isRunning());
        ToolRegistry.close();
    }

    public static List<Object> activeRequests(String session) {
        ACTIVE.entrySet().removeIf(e -> e.getValue().isCancelRequested() && !e.getValue().isRunning());
        List<Object> result = new ArrayList<>();
        ACTIVE.forEach((key,budget) -> {
            if (key.startsWith(session + "\n")) result.add(Map.of("requestId",key.substring(session.length()+1),
                    "running",budget.isRunning(),"cancelRequested",budget.isCancelRequested()));
        });
        return result;
    }

    /** @return the JSON-RPC response body for a method this dispatcher owns. */
    public static String dispatch(String method, String id, String paramsRaw, String sessionHeader) {
        return switch (method) {
            case "tools/list" -> ok(id, Json.write(Map.of("tools", visibleTools(sessionFor(sessionHeader)))));
            case "tools/call" -> toolCall(id, paramsRaw, sessionFor(sessionHeader));
            default -> null;
        };
    }

    private static String toolCall(String id, String paramsRaw, Session session) {
        session.toolCalls.incrementAndGet();
        Map<String, Object> params = readParams(paramsRaw);
        if (params == null) {
            return err(id, -32602, "tools/call requires params with a name and arguments");
        }
        String name = params.get("name") instanceof String s ? s : null;
        if (name == null) {
            return err(id, -32602, "tools/call requires params.name to be a string");
        }
        Map<String, Tool> registry = ToolRegistry.all();
        Tool tool = registry.get(name);
        if (tool == null) {
            return err(id, -32602, "no such tool: " + name + "; available tools are "
                    + new ArrayList<>(registry.keySet()));
        }
        if (!CoreTools.visible(name, session)) {
            return err(id, -32602, "toolset not loaded; call load_toolset for " + CoreTools.group(tool));
        }
        Object rawArgs = params.get("arguments");
        long timeout = 120_000L;
        // _meta is the spec-defined home for these, but most clients cannot construct one, so the
        // same keys are accepted as ordinary arguments. They are stripped before validation
        // because they are transport options, not tool arguments.
        Object clientMeta = params.get("_meta");
        int maxBytes = 16_384;
        boolean full = false;
        if (rawArgs instanceof Map<?, ?> raw) {
            Object inline = raw.get("_meta");
            if (inline != null) {
                clientMeta = inline;
                Map<String, Object> stripped = new LinkedHashMap<>((Map<String, Object>) raw);
                stripped.remove("_meta"); rawArgs = stripped;
            }
        }
        Map<String, Object> validated;
        try {
            validated = SchemaValidator.validate(tool.spec().inputSchema(), rawArgs);
        } catch (SchemaValidator.SchemaException e) {
            // Schema-shaped, so a model can fix the call instead of retrying it blind.
            return err(id, -32602, "invalid arguments for " + name + ": " + e.getMessage());
        } catch (RuntimeException e) {
            return err(id, -32603, "schema failure for " + name + ": " + e);
        }
        if (clientMeta instanceof Map<?, ?> m) {
            if (m.containsKey("lunar/maxBytes")) {
                Object value = m.get("lunar/maxBytes");
                if (!(value instanceof Number n) || n.doubleValue() != n.longValue()
                        || n.longValue() < 1024 || n.longValue() > 65_536) {
                    return err(id, -32602, "lunar/maxBytes must be an integer from 1024 to 65536");
                }
                maxBytes = ((Number) value).intValue();
            }
            if (m.containsKey("lunar/full") && !(m.get("lunar/full") instanceof Boolean))
                return err(id, -32602, "lunar/full must be boolean");
            full = Boolean.TRUE.equals(m.get("lunar/full"));
        }
        if (clientMeta instanceof Map<?, ?> m && m.containsKey("lunar/timeoutMs")) {
            Object value = m.get("lunar/timeoutMs");
            if (!(value instanceof Number n) || n.longValue() < 1 || n.longValue() > 120_000
                    || n.doubleValue() != n.longValue()) {
                return err(id, -32602, "lunar/timeoutMs must be an integer from 1 to 120000");
            }
            timeout = ((Number) value).longValue();
        }
        CallBudget budget = new CallBudget(timeout, session.id());
        budget.setMaxOutputBytes(maxBytes);
        String key = session.id() + "\n" + canonicalId(id);
        if (ACTIVE.putIfAbsent(key, budget) != null) {
            return err(id, -32600, "request id already active in this session");
        }
        if (session.closed) budget.cancel();
        if(!CoreTools.reserveOutput(session,maxBytes)){
            ACTIVE.remove(key,budget);
            return ok(id,encodeResult(ToolResult.error("session_budget_exhausted",
                    "Initialize a new session; output budget is exhausted and no tool ran",null),session.id()));
        }
        ToolResult r;boolean mutationHeld=false;int emitted=0;
        try {
            if(tool.spec().riskTier()!=ToolSpec.RiskTier.READ){
                while(!MUTATIONS.tryAcquire(Math.min(100,Math.max(1,budget.remainingMs())),java.util.concurrent.TimeUnit.MILLISECONDS))
                    budget.checkCancelled();
                mutationHeld=true;
            }
            r = ToolRunner.run(tool, validated, budget, tool.schedulingRule());
            r = CoreTools.shape(session, r, maxBytes, name.equals("resume_result"));
            emitted=encodeResult(r,session.id()).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        } catch(org.eclipse.core.runtime.OperationCanceledException cancelled){
            r=ToolResult.error("cancelled",cancelled.getMessage(),null);
            emitted=encodeResult(r,session.id()).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        } catch(InterruptedException interrupted){
            Thread.currentThread().interrupt();budget.cancel();r=ToolResult.error("interrupted","Interrupted while waiting to mutate",null);
            emitted=encodeResult(r,session.id()).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        } finally {
            session.emittedBytes.addAndGet(emitted-maxBytes);
            if(mutationHeld){if(budget.isRunning())budget.afterStop(MUTATIONS::release);else MUTATIONS.release();}
            if (!budget.isRunning()) ACTIVE.remove(key, budget);
        }
        // MCP carries the tool's own envelope inside the JSON-RPC result, not as a JSON-RPC error:
        // a tool that ran and reported failure is a successful call, and a client must be able to
        // read the failure without also handling a protocol-level error for the same event.
        return ok(id, encodeResult(r, session.id()));
    }

    static String encodeResult(ToolResult r, String sessionId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", List.of(Map.of("type", "text",
                "text", r.toJson())));
        result.put("isError", !r.ok());
        result.put("structuredContent", Map.of("ok", r.ok(),
                "data", r.data() == null ? Map.of() : r.data(),
                "error", r.error() == null ? Map.of() : r.error(),
                "meta", r.meta() == null ? Map.of() : r.meta().toMap(),
                "session", sessionId));
        return Json.write(result);
    }

    public static List<Object> visibleTools(Session session) {
        List<Object> result = new ArrayList<>();
        for (Object item : ToolRegistry.toolList()) {
            if (CoreTools.visible((String) ((Map<?, ?>) item).get("name"), session)) result.add(item);
        }
        return result;
    }

    /** Nested batch/baseline calls share the parent Job monitor and deadline. */
    static ToolResult runNested(String name, Object arguments, CallBudget budget, boolean readsOnly)
            throws Exception {
        if (CoreTools.INTERNAL.contains(name)) throw new RequestError("nested_call_forbidden","nested core calls are not allowed");
        Tool tool = ToolRegistry.all().get(name);
        if (tool == null || !CoreTools.visible(name, sessionFor(budget.sessionId())))
            throw new RequestError("tool_unavailable","unknown or unloaded tool: " + name);
        if (readsOnly && tool.spec().riskTier() != ToolSpec.RiskTier.READ)
            throw new RequestError("baseline_failed","baseline calls require a read tool");
        Map<String, Object> args = SchemaValidator.validate(tool.spec().inputSchema(), arguments);
        budget.checkCancelled();
        var rule = tool.schedulingRule();
        if (rule != null) Job.getJobManager().beginRule(rule, budget.monitor());
        try { return tool.call(args, budget); }
        finally { if (rule != null) Job.getJobManager().endRule(rule); }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readParams(String paramsRaw) {
        if (paramsRaw == null) {
            return null;
        }
        try {
            Object parsed = Json.parse(paramsRaw);
            return parsed instanceof Map ? (Map<String, Object>) parsed : null;
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    private static String ok(String id, String result) {
        return Json.obj("jsonrpc", Json.quote("2.0"), "id", id == null ? "null" : id,
                "result", result);
    }

    private static String err(String id, int code, String message) {
        return Json.obj("jsonrpc", Json.quote("2.0"), "id", id == null ? "null" : id, "error",
                Json.obj("code", Integer.toString(code), "message", Json.quote(message)));
    }
}
