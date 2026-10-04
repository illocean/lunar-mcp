package com.github.lunar.io;

import com.github.lunar.tools.CallBudget;
import com.github.lunar.tools.CoreTools;
import com.github.lunar.tools.Tool;
import com.github.lunar.tools.ToolRegistry;
import com.github.lunar.tools.ToolResult;
import com.github.lunar.tools.ToolSpec;
import com.github.lunar.tools.Tools;
import java.net.URI;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/** Runnable transport regression check; no Eclipse launch or testing dependency. */
public final class ProtocolCheck {
    public static void main(String[] args) throws Exception {
        if (!ProtocolCheck.class.desiredAssertionStatus()) throw new IllegalStateException("Run with -ea");
        for (String bad : new String[]{"+1", "01", "1.", "1e999", "{\"x\":1,\"x\":2}"}) {
            try { Json.parse(bad); throw new AssertionError("accepted " + bad); }
            catch (IllegalArgumentException expected) { }
        }
        int port;
        try (ServerSocket socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        McpHttpServer server = new McpHttpServer("check-token");
        server.start("127.0.0.1", port, "/mcp");
        try {
            URI uri = URI.create("http://127.0.0.1:" + port + "/mcp");
            HttpClient client = HttpClient.newHttpClient();
            String ping = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}";
            assert send(client, uri, ping, "Origin", "https://example.com").statusCode() == 403;
            assert send(client, uri, ping, "MCP-Protocol-Version", "invalid").statusCode() == 400;
            assert send(client, uri, ping, "MCP-Protocol-Version", "2025-11-25").statusCode() == 400;
            assert send(client, uri, ping, "Mcp-Session-Id", "invalid").statusCode() == 404;
            assert send(client, uri, ping, "Origin", "http://127.0.0.1:" + port).statusCode() == 200;
            HttpResponse<String> badInitialize = send(client, uri,
                    "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"initialize\",\"params\":{}}");
            assert errorCode(badInitialize) == -32602;
            assert badInitialize.headers().firstValue("Mcp-Session-Id").isEmpty();
            HttpResponse<String> initialized = send(client, uri,
                    "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"initialize\",\"params\":{"
                            + "\"protocolVersion\":\"unsupported-client-version\",\"capabilities\":{},"
                            + "\"clientInfo\":{\"name\":\"ProtocolCheck\",\"version\":\"1\"}}}");
            assert initialized.statusCode() == 200;
            assert ((Map<?, ?>)((Map<?, ?>)Json.parse(initialized.body())).get("result"))
                    .get("protocolVersion").equals(McpHttpServer.PROTOCOL_VERSION);
            // serverInfo.version is the only value in the initialize result that is a typed
            // literal rather than a protocol constant, so it is the one that can silently
            // disagree with the bundle that was actually built. Asserted over the wire
            // because that is the value a client actually reads. check-env.ps1 separately
            // proves the literal matches the manifests; this proves the literal is served.
            Map<?, ?> result = (Map<?, ?>) ((Map<?, ?>) Json.parse(initialized.body())).get("result");
            assert ((String) ((Map<?, ?>) result.get("serverInfo")).get("version"))
                    .equals(McpHttpServer.SERVER_VERSION);
            String session = initialized.headers().firstValue("Mcp-Session-Id").orElseThrow();
            assert send(client, uri, ping, "Mcp-Session-Id", session).body().contains("\"result\":{}");
            HttpResponse<String> notification = send(client, uri,
                    "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}",
                    "Mcp-Session-Id", session);
            assert notification.statusCode() == 202 && notification.body().isEmpty();
            HttpRequest badUtf8 = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer check-token")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(new byte[]{'{', '"', (byte)0xc3, '"', ':', '1', '}'})).build();
            HttpResponse<String> utf8Response = client.send(badUtf8, HttpResponse.BodyHandlers.ofString());
            assert utf8Response.statusCode() == 400 && errorCode(utf8Response) == -32700;
            HttpResponse<String> response = send(client, uri,
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}");
            assert response.statusCode() == 202 && response.body().isEmpty();
            assert errorCode(send(client, uri,
                    "{\"jsonrpc\":\"2.0\",\"id\":true,\"method\":\"ping\"}")) == -32600;
            assert errorCode(send(client, uri, "{malformed")) == -32700;
            HttpResponse<String> failedLoad = send(client, uri,
                    "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"tools/call\",\"params\":{"
                            + "\"name\":\"load_toolset\",\"arguments\":{\"name\":\"invalid\"}}}",
                    "Mcp-Session-Id", session);
            assert failedLoad.headers().firstValue("Content-Type").orElse("").equals("application/json");
            withCoreTools(() -> {
                checkRefusalCarriesAStableCode(client, uri, session);
                checkWindowBillsEmittedBytes(client, uri, session);
                checkToolListIsBilled(client, uri, session);
                return null;
            });
            checkCanonicalCancellation(client, uri, session);
            HttpRequest deleted = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer check-token").header("Mcp-Session-Id", session)
                    .DELETE().build();
            assert client.send(deleted, HttpResponse.BodyHandlers.ofString()).statusCode() == 204;
            assert send(client, uri, ping, "Mcp-Session-Id", session).statusCode() == 404;
            Map<?, ?> malformed = (Map<?, ?>) Json.parse(send(client, uri,
                    "{\"id\":4,\"method\":\"ping\"}").body());
            assert malformed.containsKey("error");
        } finally { server.stop(); }
        System.out.println("PROTOCOL CHECK PASS");
    }

    private static long errorCode(HttpResponse<String> response) {
        Map<?, ?> error = (Map<?, ?>)((Map<?, ?>)Json.parse(response.body())).get("error");
        return ((Number)error.get("code")).longValue();
    }

    /**
     * Run {@code body} with the real core tools registered.
     *
     * <p>The registry is empty outside OSgi, so the cancellation check installs a stub instead.
     * These two need the real ones: the refusal check has to raise a genuine {@code RequestError}
     * and the window check has to read the dispatcher's own accounting. Only tools that read no
     * Eclipse state are called, so no workspace is needed.
     */
    private static void withCoreTools(java.util.concurrent.Callable<Void> body) throws Exception {
        Map<String, Tool> previous = ToolRegistry.all();
        var install = ToolRegistry.class.getDeclaredMethod("installForTest", Map.class);
        install.setAccessible(true);
        Map<String, Tool> core = new java.util.LinkedHashMap<>();
        for (Tool tool : new CoreTools().tools()) core.put(tool.spec().name(), tool);
        install.invoke(null, core);
        try {
            body.call();
        } finally {
            install.invoke(null, previous);
        }
    }

    /**
     * A transport refusal has to carry a code a client can branch on.
     *
     * <p>The capacity refusal was packed into the message string under the generic -32600, so a
     * client could only grep prose to learn why it had been refused. JSON-RPC gives an error a
     * {@code data} member for exactly that. This drives the real 64-session ceiling the way a
     * leaking client would, because {@code createSession} throws outside {@code ToolRunner} --
     * the only refusals that reach the transport's own catch are ones no tool raised.
     */
    private static void checkRefusalCarriesAStableCode(HttpClient client, URI uri, String session)
            throws Exception {
        Map<?, ?> error = null;
        // Every initialize the server accepted is a real session in the dispatcher's table, and
        // eviction is 30 minutes away. They are deleted here, because leaving the table pinned at
        // its ceiling makes the next initialize check fail with a capacity refusal that has
        // nothing to do with what it is checking.
        List<String> created = new ArrayList<>();
        try {
            for (int i = 0; i < 200 && error == null; i++) {
                HttpResponse<String> reply = send(client, uri, initialize(40 + i));
                reply.headers().firstValue("Mcp-Session-Id").ifPresent(created::add);
                Map<?, ?> replyBody = (Map<?, ?>) Json.parse(reply.body());
                error = (Map<?, ?>) replyBody.get("error");
            }
            assert error != null : "200 initialize calls never reached the session ceiling";
            assert error.containsKey("data") : "refusal carries no error.data: " + error;
            assert "session_capacity_reached".equals(((Map<?, ?>) error.get("data")).get("code"));
            assert !error.get("message").toString().contains("session_capacity_reached")
                    : "the code is still packed into the message as well as into the data";
        } finally {
            for (String id : created) {
                client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10))
                        .header("Authorization", "Bearer check-token")
                        .header("Mcp-Session-Id", id).DELETE().build(),
                        HttpResponse.BodyHandlers.ofString());
            }
        }
        // The table has to be usable again, or the next initialize check in this file fails with
        // a capacity refusal that says nothing about what it is checking.
        Map<?, ?> after = (Map<?, ?>) Json.parse(send(client, uri, initialize(9)).body());
        assert after.get("error") == null
                : "the capacity check left the session table full: " + after.get("error");
    }

    private static String initialize(int id) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"initialize\",\"params\":{"
                + "\"protocolVersion\":\"unsupported-client-version\",\"capabilities\":{},"
                + "\"clientInfo\":{\"name\":\"ProtocolCheck\",\"version\":\"1\"}}}";
    }

    /**
     * The window must bill the bytes a call emitted, not the {@code maxBytes} it reserved.
     *
     * <p>Twenty {@code get_session_info} calls answer in a few hundred bytes each. Charging the
     * 16 KiB reservation instead spent 320 KiB of an 8 MiB window on about six kilobytes of real
     * output, which capped every session at 512 calls an hour however small the responses were.
     * Asserted over the wire because the charge happens in the dispatcher's {@code finally}
     * block, so a unit check of the helper would never have reached the bug.
     */
    private static void checkWindowBillsEmittedBytes(HttpClient client, URI uri, String session)
            throws Exception {
        long before = remainingOutputBytes(sessionInfo(client, uri, session, 20));
        for (int i = 0; i < 20; i++) sessionInfo(client, uri, session, 100 + i);
        long spent = before - remainingOutputBytes(sessionInfo(client, uri, session, 21));
        assert spent < 64 * 1024
                : "20 small calls spent " + spent
                + " bytes of the output window, so the window is billing reservations";
    }

    /**
     * The catalogue is the largest body this server emits, so it has to be billed too.
     *
     * <p>{@code tools/list} sits outside the {@code tools/call} path that charges, so a client
     * could ask for every schema and description as often as the transport allowed and never
     * move its output window -- and {@code get_session_info} reported the window as though
     * nothing had been spent. Asserted over the wire because the charge is in the dispatcher.
     */
    private static void checkToolListIsBilled(HttpClient client, URI uri, String session)
            throws Exception {
        long before = remainingOutputBytes(sessionInfo(client, uri, session, 30));
        long catalogue = 0;
        for (int i = 0; i < 10; i++) {
            HttpResponse<String> listed = send(client, uri,
                    "{\"jsonrpc\":\"2.0\",\"id\":" + (300 + i) + ",\"method\":\"tools/list\"}",
                    "Mcp-Session-Id", session);
            assert listed.statusCode() == 200 && listed.body().contains("\"tools\"");
            catalogue = Math.max(catalogue,
                    listed.body().getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        }
        long spent = before - remainingOutputBytes(sessionInfo(client, uri, session, 31));
        assert spent >= 10L * catalogue
                : "10 tools/list calls emitted at least " + (10L * catalogue)
                + " bytes but spent only " + spent
                + " of the output window, so the catalogue is free";
    }

    private static HttpResponse<String> sessionInfo(HttpClient client, URI uri, String session, int id)
            throws Exception {
        HttpResponse<String> response = send(client, uri,
                "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"tools/call\",\"params\":{"
                        + "\"name\":\"get_session_info\",\"arguments\":{}}}",
                "Mcp-Session-Id", session);
        assert response.statusCode() == 200;
        return response;
    }

    /** {@code remainingOutputBytes} out of a tools/call result, through the JSON path a client reads. */
    private static long remainingOutputBytes(HttpResponse<String> response) {
        Map<?, ?> content = (Map<?, ?>) ((Map<?, ?>) ((Map<?, ?>) Json.parse(response.body()))
                .get("result")).get("structuredContent");
        assert Boolean.TRUE.equals(content.get("ok"));
        return ((Number) ((Map<?, ?>) content.get("data")).get("remainingOutputBytes")).longValue();
    }

    private static void checkCanonicalCancellation(HttpClient client, URI uri, String session)
            throws Exception {
        Map<String, Tool> previous = ToolRegistry.all();
        var install = ToolRegistry.class.getDeclaredMethod("installForTest", Map.class);
        install.setAccessible(true);
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean observed = new AtomicBoolean();
        Tool cooperative = new Tool() {
            public ToolSpec spec() {
                return new ToolSpec("list_projects", "Cancellation check", "Check", Tools.object(Map.of()),
                        ToolSpec.RiskTier.READ);
            }
            public ToolResult call(Map<String, Object> args, CallBudget budget) {
                started.countDown();
                while (!budget.monitor().isCanceled()) LockSupport.parkNanos(1_000_000L);
                observed.set(true);
                budget.checkCancelled();
                return ToolResult.ok(Map.of(), null);
            }
        };
        install.invoke(null, Map.of("list_projects", cooperative));
        try {
            var running = client.sendAsync(request(uri,
                    "{\"jsonrpc\":\"2.0\",\"id\":\"a\\u0062\",\"method\":\"tools/call\",\"params\":{"
                            + "\"name\":\"list_projects\",\"arguments\":{}}}", "Mcp-Session-Id", session),
                    HttpResponse.BodyHandlers.ofString());
            assert started.await(3, TimeUnit.SECONDS);
            HttpResponse<String> duplicate = send(client, uri,
                    "{\"jsonrpc\":\"2.0\",\"id\":\"ab\",\"method\":\"tools/call\",\"params\":{"
                            + "\"name\":\"list_projects\",\"arguments\":{}}}", "Mcp-Session-Id", session);
            assert errorCode(duplicate) == -32600;
            HttpResponse<String> notification = send(client, uri,
                    "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\",\"params\":{"
                            + "\"requestId\":\"ab\"}}", "Mcp-Session-Id", session);
            assert notification.statusCode() == 202;
            Map<?, ?> reply = (Map<?, ?>)Json.parse(running.get(3, TimeUnit.SECONDS).body());
            assert reply.get("id").equals("ab");
            Map<?, ?> content = (Map<?, ?>)((Map<?, ?>)reply.get("result")).get("structuredContent");
            assert observed.get() && Boolean.FALSE.equals(content.get("ok"));
            assert "cancelled".equals(((Map<?, ?>)content.get("error")).get("code"));
            assert Boolean.TRUE.equals(((Map<?, ?>)content.get("meta")).get("cancellationHonoured"));
        } finally {
            send(client, uri,
                    "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\",\"params\":{"
                            + "\"requestId\":\"ab\"}}", "Mcp-Session-Id", session);
            install.invoke(null, previous);
        }
    }

    private static HttpResponse<String> send(HttpClient client, URI uri, String body,
            String... headers) throws Exception {
        return client.send(request(uri, body, headers), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpRequest request(URI uri, String body, String... headers) {
        var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer check-token").header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream");
        for (int i = 0; i < headers.length; i += 2) request.header(headers[i], headers[i + 1]);
        return request.POST(HttpRequest.BodyPublishers.ofString(body)).build();
    }
}
