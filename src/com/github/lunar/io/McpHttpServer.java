package com.github.lunar.io;

import com.github.lunar.tools.ToolDispatcher;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Semaphore;
import java.util.Map;
import java.net.URI;

/** Hand-rolled JSON-RPC over the JDK's built-in HTTP server. No third-party transport. */
public final class McpHttpServer {

    /** Session key header (Q20). Absent means one implicit shared session, not an error. */
    public static final String SESSION_HEADER = "Mcp-Session-Id";

    public static final String PROTOCOL_VERSION = "2025-06-18";
    public static final String SERVER_NAME = "lunar";
    public static final String SERVER_VERSION = "0.0.1";

    private static final int PARSE_ERROR = -32700;
    private static final int INVALID_REQUEST = -32600;
    private static final int METHOD_NOT_FOUND = -32601;
    private static final int INTERNAL_ERROR = -32603;
    private static final int UNAUTHORIZED = -32001;
    private static final int PAYLOAD_TOO_LARGE = -32000;

    /**
     * Bounded request size complements native request/response time and connection limits.
     */
    public static final int MAX_BODY_BYTES = 1024 * 1024;

    /**
     * How long a tools/call waits for one of the three tool slots. Kept under
     * {@code sun.net.httpserver.maxReqTime} so a queued burst still gets a real answer from the
     * transport rather than being cut off mid-wait.
     */
    static final int TOOL_QUEUE_TIMEOUT_MS = 10_000;

    private final String token;
    private HttpServer http;
    private ExecutorService pool;
    private final Semaphore toolSlots = new Semaphore(3);

    public McpHttpServer(String token) {
        this.token = token;
    }

    public void start(String host, int port, String contextPath) throws IOException {
        // Loopback only, never 0.0.0.0: an unauthenticated-by-default MCP endpoint must not be
        // reachable from off-box even if the token check below were ever weakened. The host is
        // therefore resolved by LunarServer and must already be a loopback address; the bind
        // below rejects anything else rather than trusting the caller.
        InetAddress bind = InetAddress.getByName(host);
        if (!bind.isLoopbackAddress())
            throw new IOException("refusing to bind non-loopback address " + host
                    + "; lunar serves an IDE workspace and must stay on this machine");
        // Native values are cached by the JDK's first HttpServer initialization. These defaults
        // do not claim to override an HttpServer another Eclipse component already initialized.
        defaultProperty("sun.net.httpserver.maxReqTime", "15");
        defaultProperty("sun.net.httpserver.maxRspTime", "125");
        defaultProperty("jdk.httpserver.maxConnections", "64");
        http = HttpServer.create(new InetSocketAddress(bind, port), 16);
        pool = new java.util.concurrent.ThreadPoolExecutor(8, 8, 0L,
                java.util.concurrent.TimeUnit.MILLISECONDS,
                new java.util.concurrent.ArrayBlockingQueue<>(32), workerThreads(),
                new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
        http.setExecutor(pool);
        http.createContext(contextPath, ex -> handle(ex, contextPath));
        http.start();
    }

    public void stop() {
        ToolDispatcher.shutdown();
        if (http != null) {
            http.stop(0);
            http = null;
        }
        if (pool != null) {
            pool.shutdownNow();
            pool = null;
        }
    }

    private static void defaultProperty(String name, String value) {
        if (System.getProperty(name) == null) System.setProperty(name, value);
    }

    /**
     * No loopback bypass: the token is required on every request even from 127.0.0.1. A short-circuit
     * for the local address is how "unprotected" ends up one config line away.
     */
    public static boolean bearerMatches(String header, String expectedToken) {
        if (header == null || expectedToken == null) {
            return false;
        }
        // RFC 6750: the auth-scheme token is case-insensitive.
        if (!header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return false;
        }
        String presented = header.substring(7).trim();
        if (presented.isEmpty()) {
            return false;
        }
        return MessageDigest.isEqual(expectedToken.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }

    private void handle(HttpExchange ex, String contextPath) {
        try {
            // Authentication precedes reading the body and reaching any method handler.
            if (!bearerMatches(ex.getRequestHeaders().getFirst("Authorization"), token)) {
                send(ex, 401, error("null", UNAUTHORIZED, "unauthorized"));
                return;
            }
            if (!contextPath.equals(ex.getRequestURI().getPath())) {
                send(ex, 404, error("null", METHOD_NOT_FOUND, "unknown context"));
                return;
            }
            String origin = ex.getRequestHeaders().getFirst("Origin");
            if (origin != null && !validOrigin(origin, ex.getLocalAddress().getPort())) {
                send(ex, 403, error("null", INVALID_REQUEST, "invalid Origin"));
                return;
            }
            String protocol = ex.getRequestHeaders().getFirst("MCP-Protocol-Version");
            if (protocol != null && !PROTOCOL_VERSION.equals(protocol)) {
                send(ex, 400, error("null", INVALID_REQUEST, "unsupported protocol version"));
                return;
            }
            String session = ex.getRequestHeaders().getFirst(SESSION_HEADER);
            if (!ToolDispatcher.hasSession(session)) {
                send(ex, 404, error("null", INVALID_REQUEST, "unknown MCP session"));
                return;
            }
            if ("DELETE".equalsIgnoreCase(ex.getRequestMethod())) {
                ToolDispatcher.deleteSession(session);
                send(ex, 204, "");
                return;
            }
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                send(ex, 405, error("null", METHOD_NOT_FOUND, "POST required"));
                return;
            }
            // Rejected on the declared length, before a single byte is read, so a lying header
            // cannot make a worker thread wait. Authorization has already returned by this point.
            String declared = ex.getRequestHeaders().getFirst("Content-Length");
            if (declared != null) {
                long length;
                try {
                    length = Long.parseLong(declared.trim());
                } catch (NumberFormatException notANumber) {
                    send(ex, 400, error("null", INVALID_REQUEST, "unparseable Content-Length"));
                    return;
                }
                if (length > MAX_BODY_BYTES) {
                    tooLarge(ex);
                    return;
                }
            }
            byte[] raw;
            try (var in = ex.getRequestBody()) {
                // readNBytes rather than readAllBytes: a chunked request declares no Content-Length
                // at all, and the read must be bounded regardless of what the headers claimed.
                raw = in.readNBytes(MAX_BODY_BYTES + 1);
            }
            if (raw.length > MAX_BODY_BYTES) {
                tooLarge(ex);
                return;
            }
            String body;
            try {
                body = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(raw)).toString();
            } catch (CharacterCodingException invalidUtf8) {
                send(ex, 400, error("null", PARSE_ERROR, "request body must be valid UTF-8"));
                return;
            }
            Json.Request req = Json.request(body);
            if (req == null) {
                int code = INVALID_REQUEST;
                try { Json.parse(body); } catch (RuntimeException malformed) { code = PARSE_ERROR; }
                send(ex, 200, error("null", code,
                        code == PARSE_ERROR ? "parse error" : "invalid JSON-RPC request"));
                return;
            }
            Map<?, ?> message = req.message;
            if ("2.0".equals(message.get("jsonrpc")) && req.id != null
                    && !message.containsKey("method")
                    && message.containsKey("result") != message.containsKey("error")) {
                // No server requests are outstanding; a valid unsolicited response is accepted
                // and discarded, with the transport status required for client responses.
                send(ex, 202, "");
                return;
            }
            if (!"2.0".equals(message.get("jsonrpc")) || req.method == null) {
                send(ex, 200, error(req.id == null ? "null" : req.id, INVALID_REQUEST,
                        "jsonrpc must be 2.0 and method must be a string"));
                return;
            }
            if (req.isNotification()) {
                if ("notifications/cancelled".equals(req.method)
                        && message.get("params") instanceof Map<?, ?> params
                        && params.containsKey("requestId")) {
                    ToolDispatcher.cancel(session, Json.write(params.get("requestId")));
                }
                send(ex, 202, "");
                return;
            }
            if ("initialize".equals(req.method)) {
                if (!(message.get("params") instanceof Map<?, ?> params)
                        || !(params.get("protocolVersion") instanceof String)
                        || !(params.get("capabilities") instanceof Map)
                        || !(params.get("clientInfo") instanceof Map<?, ?> client)
                        || !(client.get("name") instanceof String)
                        || !(client.get("version") instanceof String)) {
                    send(ex, 200, error(req.id, -32602,
                            "initialize requires protocolVersion, capabilities and clientInfo name/version"));
                    return;
                }
                session = ToolDispatcher.createSession();
                ex.getResponseHeaders().set(SESSION_HEADER, session);
            }
            boolean toolCall = "tools/call".equals(req.method);
            // Queue a burst instead of rejecting it. The three concurrent tool slots protect Eclipse's
            // single UI thread; refusing the 4th caller made legitimate parallel fan-out fail with a 429
            // the client cannot act on. Bounded by the same ceiling as the request itself, so a
            // genuinely stuck tool still surfaces as 429 rather than holding the slot forever.
            if (toolCall && !toolSlots.tryAcquire(TOOL_QUEUE_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                ex.getResponseHeaders().set("Retry-After", "1");
                send(ex, 429, error(req.id, -32000, "tool concurrency limit reached"));
                return;
            }
            try {
                Map<?, ?> parameters = message.get("params") instanceof Map<?, ?> map ? map : Map.of();
                boolean loading = toolCall && "load_toolset".equals(parameters.get("name"));
                int beforeTools = loading ? ToolDispatcher.visibleTools(
                        ToolDispatcher.sessionFor(session)).size() : 0;
                String response = dispatch(req, session);
                boolean changed = loading && toolSucceeded(response) && beforeTools !=
                        ToolDispatcher.visibleTools(ToolDispatcher.sessionFor(session)).size();
                if (changed
                        && ex.getRequestHeaders().getFirst("Accept") != null
                        && ex.getRequestHeaders().getFirst("Accept").contains("text/event-stream")) {
                    sendEvents(ex, response);
                } else {
                    send(ex, 200, response);
                }
            } finally {
                if (toolCall) toolSlots.release();
            }
        } catch (Exception e) {
            try {
                send(ex, 500, error("null", INTERNAL_ERROR, "internal error"));
            } catch (IOException ignored) {
                // Client already gone; nothing useful left to report.
            }
        } finally {
            ex.close();
        }
    }

    private static boolean toolSucceeded(String response) {
        Object parsed = Json.parse(response);
        return parsed instanceof Map<?, ?> message && message.get("result") instanceof Map<?, ?> result
                && result.get("structuredContent") instanceof Map<?, ?> content
                && Boolean.TRUE.equals(content.get("ok"));
    }

    private static boolean validOrigin(String origin, int port) {
        try {
            URI uri = URI.create(origin);
            return "http".equals(uri.getScheme()) && uri.getUserInfo() == null
                    && java.util.Set.of("127.0.0.1", "localhost", "[::1]").contains(uri.getHost())
                    && uri.getPort() == port && (uri.getPath().isEmpty() || uri.getPath().equals("/"))
                    && uri.getQuery() == null && uri.getFragment() == null;
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private static String dispatch(Json.Request req, String session) {
        String id = req.id;
        String method = req.method == null ? "" : req.method;
        // The dispatcher owns tools/* and returns null for anything else, so the transport keeps
        // its own cases and does not grow a branch per tool.
        String handled = ToolDispatcher.dispatch(method, id, req.params, session);
        if (handled != null) {
            return handled;
        }
        return switch (method) {
            case "initialize" -> result(id, Json.obj(
                    "protocolVersion", Json.quote(PROTOCOL_VERSION),
                    "serverInfo", Json.obj(
                            "name", Json.quote(SERVER_NAME),
                            "version", Json.quote(SERVER_VERSION)),
                    "capabilities", Json.obj("tools", "{\"listChanged\":true}")));
            case "ping" -> result(id, "{}");
            default -> error(id, METHOD_NOT_FOUND, "unknown method: " + method);
        };
    }

    private static void tooLarge(HttpExchange ex) throws IOException {
        send(ex, 413, error("null", PAYLOAD_TOO_LARGE, "request body exceeds " + MAX_BODY_BYTES
                + " bytes"));
    }

    private static String result(String id, String result) {
        return Json.obj("jsonrpc", Json.quote("2.0"), "id", id, "result", result);
    }

    private static String error(String id, int code, String message) {
        return Json.obj("jsonrpc", Json.quote("2.0"), "id", id, "error",
                Json.obj("code", Integer.toString(code), "message", Json.quote(message)));
    }

    /** Ordinary calls use JSON; successful catalog changes may use a finite POST SSE response. */
    private static void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        if (bytes.length == 0) {
            ex.sendResponseHeaders(status, -1);
            return;
        }
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void sendEvents(HttpExchange ex, String response) throws IOException {
        String events = "event: message\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/tools/list_changed\"}\n\n"
                + "event: message\ndata: " + response + "\n\n";
        byte[] bytes = events.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/event-stream");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = ex.getResponseBody()) { out.write(bytes); }
    }

    private static ThreadFactory workerThreads() {
        AtomicInteger n = new AtomicInteger();
        return runnable -> {
            Thread t = new Thread(runnable, "lunar-mcp-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }
}
