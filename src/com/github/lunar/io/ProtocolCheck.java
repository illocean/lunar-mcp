package com.github.lunar.io;

import com.github.lunar.tools.CallBudget;
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
