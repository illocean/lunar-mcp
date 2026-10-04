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

    /**
     * Sessions are reclaimed after this much idle time.
     *
     * <p>The cap of 64 was the only bound, so an abandoned session -- a client that crashed, or an
     * agent that opened one per task and never closed it -- held a slot until the cap was hit and
     * then every later session was refused. Each session is small, but "small times 64" is not a
     * policy: it makes the lifetime of a leaked session the problem of whoever leaked the 65th.
     * An idle TTL reclaims them without the client having to be well behaved, and anything
     * genuinely in flight is never touched because {@link #sessionFor} refreshes lastSeen.
     */
    private static final long SESSION_IDLE_NANOS = java.util.concurrent.TimeUnit.MINUTES.toNanos(30);

    /** The id used when a client sends no Mcp-Session-Id header; never evicted, always exactly one. */
    private static final String IMPLICIT = "implicit";

    /** Output a session may emit inside its trailing window. Same magnitude as the lifetime cap it
     *  replaces, but decaying, so an old busy hour stops counting against today's work. */
    static final long WINDOW_BYTES = 8L * 1024 * 1024;

    /** Window clock, a seam so a check can age a window without sleeping for an hour. */
    private static volatile java.util.function.LongSupplier windowClock = System::currentTimeMillis;
    static long now() { return windowClock.getAsLong(); }
    static void ageWindow(java.util.function.LongSupplier clock) { windowClock = clock; }

    private ToolDispatcher() {
    }

    /**
     * Drop sessions idle past the TTL. Called from every entry point that reads SESSIONS, so
     * reclamation costs no timer and no thread; a server nobody is talking to holds nothing stale
     * because nothing is running to hold it.
     *
     * @return how many sessions were evicted
     */
    static int evictIdleSessions() {
        long now = System.nanoTime();
        int evicted = 0;
        for (java.util.Map.Entry<String, Session> entry : SESSIONS.entrySet()) {
            Session s = entry.getValue();
            if (s.id.equals(IMPLICIT)) continue;
            if (now - s.lastSeen < SESSION_IDLE_NANOS) continue;
            // Compare-and-remove against the same instance: a session that was just refreshed by
            // a request in flight must not be deleted out from under it.
            if (!SESSIONS.remove(entry.getKey(), s)) continue;
            s.closed = true;
            ACTIVE.forEach((key, budget) -> { if (key.startsWith(s.id + "\n")) budget.cancel(); });
            evicted++;
        }
        return evicted;
    }

    /**
     * Per-session bookkeeping.
     *
     * <p>The output budget is a sliding window, not a lifetime total. A lifetime cap punishes a
     * long-lived session for work it did an hour ago and then refuses the session outright, so a
     * client is pushed to burn a fresh session per task and the sessions that keep living are the
     * idle ones. A window bounds the rate, which is the thing that can actually exhaust memory,
     * and lets a busy session keep working once its earlier output has aged out.
     */
    public static final class Session {
        /** One bucket per minute; the window is WINDOW_BUCKETS of them. */
        private static final int WINDOW_BUCKETS = 60;
        final String id;
        final AtomicLong toolCalls = new AtomicLong();
        final AtomicLong emittedBytes = new AtomicLong();
        final Set<String> loaded = ConcurrentHashMap.newKeySet();
        final Map<String, String> cursors = new LinkedHashMap<>();
        final Map<String, CoreTools.Baseline> baselines = new LinkedHashMap<>();
        final long[] windowMinutes = new long[WINDOW_BUCKETS];
        final AtomicLong[] windowBytes = new AtomicLong[WINDOW_BUCKETS];
        volatile long lastSeen = System.nanoTime();
        volatile boolean closed;
        Session(String id) {
            this.id = id;
            for (int i = 0; i < WINDOW_BUCKETS; i++) windowBytes[i] = new AtomicLong();
        }
        public String id() { return id; }
        public long toolCalls() { return toolCalls.get(); }

        /**
         * Charge {@code bytes} against the trailing one-hour window and report the total still
         * inside it, expiring buckets as they age past the window.
         */
        synchronized long charge(long bytes) {
            long bucket = now() / 60_000L;
            long total = 0;
            for (int i = 0; i < WINDOW_BUCKETS; i++) {
                // A bucket from an hour or more ago is out of the window; so is any bucket older
                // than the newest one we hold, which is the wraparound case within the hour.
                if (windowMinutes[i] < bucket - WINDOW_BUCKETS + 1 || windowMinutes[i] > bucket) {
                    windowMinutes[i] = 0;
                    windowBytes[i].set(0);
                }
                total += windowBytes[i].get();
            }
            int slot = (int) Math.floorMod(bucket, WINDOW_BUCKETS);
            if (windowMinutes[slot] != bucket) { windowMinutes[slot] = bucket; windowBytes[slot].set(0); }
            // Count what was just charged, or the cap is soft by exactly one response.
            total += windowBytes[slot].addAndGet(bytes);
            emittedBytes.addAndGet(bytes);
            return total;
        }

        /** Bytes charged inside the trailing window, without charging anything new. */
        synchronized long windowedBytes() {
            long bucket = now() / 60_000L;
            long total = 0;
            for (int i = 0; i < WINDOW_BUCKETS; i++) {
                if (windowMinutes[i] < bucket - WINDOW_BUCKETS + 1 || windowMinutes[i] > bucket) continue;
                total += windowBytes[i].get();
            }
            return total;
        }

        
    }

    public static Session sessionFor(String header) {
        evictIdleSessions();
        String id = header == null || header.isBlank() ? IMPLICIT : header.trim();
        Session s = id.equals(IMPLICIT) ? SESSIONS.computeIfAbsent(id, Session::new) : SESSIONS.get(id);
        if (s == null || s.closed)
            throw new RequestError("session_unavailable", "unknown or expired MCP session; initialize again");
        s.lastSeen = System.nanoTime();
        return s;
    }


    public static String createSession() {
        synchronized (SESSIONS) {
            // Reclaim before refusing: the cap is a backstop for a burst, not a way to make leaked
            // sessions permanent, and evicting here is what stops an idle-but-full server from
            // rejecting every initialize from then on.
            SESSIONS.values().removeIf(s -> {
                boolean idle = !s.id.equals(IMPLICIT)
                        && System.nanoTime() - s.lastSeen >= SESSION_IDLE_NANOS;
                if (idle) s.closed = true;
                return idle;
            });
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
        CallBudget budget = ACTIVE.get((session == null ? IMPLICIT : session) + "\n"
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
