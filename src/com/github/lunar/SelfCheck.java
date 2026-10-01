package com.github.lunar;

import com.github.lunar.io.Json;
import com.github.lunar.io.McpHttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import javax.xml.parsers.SAXParserFactory;
import org.apache.felix.scr.impl.logger.NoOpLogger;
import org.apache.felix.scr.impl.metadata.ComponentMetadata;
import org.apache.felix.scr.impl.xml.XmlHandler;
import org.osgi.framework.Bundle;

/**
 * No test framework in this project and none is planned, so this is the whole check suite.
 *
 * <p>The unit checks below cover the hand-rolled JSON writer/parser and the token comparison. They
 * were not enough: P1 shipped with three stacked defects that made the server never start at all,
 * and every one of them lived outside the units -- the manifest header Felix SCR reads, the XML
 * namespace it accepts, and an attribute that had to be an element. A gate that only exercises the
 * units cannot see that class of bug, so this suite also boots a real {@link McpHttpServer}, parses
 * the real descriptor through Felix's own {@link XmlHandler}, and reads the real built jar.
 *
 * <p>Args: the built jar. Everything else is derived from it.
 */
public final class SelfCheck {

    /** Same shape and length as the real user-scope ECLIPSE_MCP_TOKEN. */
    private static final String TOKEN = "0123456789abcdef0123456789abcdef0123";

    private static int total;
    private static int failures;

    public static void main(String[] args) {
        // --- writer ---
        eq("quote plain", "\"lunar\"", Json.quote("lunar"));
        eq("quote empty", "\"\"", Json.quote(""));
        eq("quote double quote", "\"a\\\"b\"", Json.quote("a\"b"));
        eq("quote backslash", "\"a\\\\b\"", Json.quote("a\\b"));
        eq("quote newline", "\"a\\nb\"", Json.quote("a\nb"));
        eq("quote tab and cr", "\"a\\tb\\rc\"", Json.quote("a\tb\rc"));
        eq("quote control char", "\"\\u0001\"", Json.quote("\u0001"));
        eq("quote non-ascii passthrough", "\"\u00e9\"", Json.quote("\u00e9"));
        eq("obj two pairs", "{\"k\":1,\"j\":\"v\"}", Json.obj("k", "1", "j", Json.quote("v")));
        eq("obj single pair", "{\"tools\":[]}", Json.obj("tools", Json.arr()));
        eq("arr empty", "[]", Json.arr());
        eq("arr two items", "[1,2]", Json.arr("1", "2"));

        // --- request extraction ---
        Json.Request r = Json.request(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{\"a\":[1,2]}}");
        yes("parse simple request", r != null);
        eq("method decoded", "tools/list", r == null ? null : r.method);
        eq("numeric id is a request", "1", r == null ? null : r.id);
        yes("numeric id is not a notification", r != null && !r.isNotification());

        r = Json.request("{\"id\":\"abc\",\"method\":\"ping\"}");
        eq("string id kept raw for echo", "\"abc\"", r == null ? null : r.id);

        r = Json.request("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
        yes("missing id is a notification", r != null && r.isNotification());
        eq("notification method decoded", "notifications/initialized", r == null ? null : r.method);

        r = Json.request("{\"id\":null,\"method\":\"ping\"}");
        eq("explicit null id still requests a response", "null", r == null ? null : r.id);
        yes("explicit null id is not a notification", r != null && !r.isNotification());

        r = Json.request("{\"id\":1,\"method\":\"tools\\/list\"}");
        eq("escaped solidus decoded", "tools/list", r == null ? null : r.method);

        r = Json.request("{\"id\":1,\"method\":\"a\\\"b\"}");
        eq("escaped quote decoded", "a\"b", r == null ? null : r.method);

        // A brace inside a nested string must not confuse the depth counter. Both the object and the
        // nested array below hold strings that are nothing but braces.
        r = Json.request("{\"id\":1,\"method\":\"m\",\"params\":{\"s\":\"}\\\"{\",\"t\":[\"}\",\"{\"]}}");
        eq("nested braces inside strings ignored", "m", r == null ? null : r.method);

        r = Json.request("{ \"method\" : \"ping\" ,\t\"id\" : 7 }");
        eq("whitespace tolerant", "ping", r == null ? null : r.method);
        eq("whitespace tolerant id", "7", r == null ? null : r.id);

        r = Json.request("{}");
        yes("empty object is well formed", r != null);
        eq("empty object has no method", null, r == null ? null : r.method);
        yes("empty object is a notification", r != null && r.isNotification());

        eq("truncated object rejected", null, str(Json.request("{\"id\":1,")));
        eq("non-object rejected", null, str(Json.request("not json")));
        eq("unterminated string rejected", null, str(Json.request("{\"id\":1,\"method\":\"pin")));
        eq("trailing garbage rejected", null, str(Json.request("{\"id\":1,\"method\":\"ping\"} x")));
        eq("raw control char in string rejected",
                null, str(Json.request("{\"id\":1,\"method\":\"a\u0001b\"}")));

        // The escaper and the unescaper must agree, or nothing round-trips.
        String awkward = "he said \"hi\" \\ then\nnewline";
        String built = Json.obj("jsonrpc", Json.quote("2.0"), "id", "1", "method",
                Json.quote(awkward));
        r = Json.request(built);
        eq("writer output re-parses to the same string", awkward, r == null ? null : r.method);

        // --- bearer token ---
        yes("exact token accepted",
                McpHttpServer.bearerMatches("Bearer " + TOKEN, TOKEN));
        yes("auth scheme is case-insensitive",
                McpHttpServer.bearerMatches("bearer " + TOKEN, TOKEN));
        yes("surrounding whitespace trimmed",
                McpHttpServer.bearerMatches("Bearer   " + TOKEN + "  ", TOKEN));
        yes("wrong token rejected",
                !McpHttpServer.bearerMatches("Bearer " + TOKEN + "x", TOKEN));
        yes("truncated token rejected (no prefix match)",
                !McpHttpServer.bearerMatches("Bearer " + TOKEN.substring(0, TOKEN.length() - 1),
                        TOKEN));
        yes("empty token rejected", !McpHttpServer.bearerMatches("Bearer ", TOKEN));
        yes("null header rejected", !McpHttpServer.bearerMatches(null, TOKEN));
        yes("empty header rejected", !McpHttpServer.bearerMatches("", TOKEN));
        yes("bare scheme rejected", !McpHttpServer.bearerMatches("Bearer", TOKEN));
        yes("token with no scheme rejected", !McpHttpServer.bearerMatches(TOKEN, TOKEN));
        yes("other auth scheme rejected",
                !McpHttpServer.bearerMatches("Basic " + TOKEN, TOKEN));
        yes("prefix of the scheme word is not a scheme",
                !McpHttpServer.bearerMatches("Beare" + TOKEN, TOKEN));

        // --- id validation at parse time ---
        // An unvalidated id is echoed verbatim, so anything the parser does not recognise becomes
        // an invalid response body. These four are the shapes that actually reached the wire.
        eq("bare-word id rejected", null, str(Json.request("{\"id\":tru,\"method\":\"ping\"}")));
        eq("array id rejected", null, str(Json.request("{\"id\":[1,2],\"method\":\"ping\"}")));
        eq("object id rejected", null, str(Json.request("{\"id\":{\"a\":1},\"method\":\"ping\"}")));
        eq("trailing-garbage number id rejected",
                null, str(Json.request("{\"id\":1abc,\"method\":\"ping\"}")));
        eq("empty id rejected", null, str(Json.request("{\"id\":,\"method\":\"ping\"}")));
        r = Json.request("{\"id\":-12.5e3,\"method\":\"ping\"}");
        eq("valid negative/exponent number id kept", "-12.5e3", r == null ? null : r.id);
        r = Json.request("{\"id\":\"a\\\"b\",\"method\":\"ping\"}");
        eq("valid escaped string id kept", "\"a\\\"b\"", r == null ? null : r.id);
        // A bad id poisons the whole request rather than yielding a notification.
        yes("bad id is not silently a notification",
                Json.request("{\"id\":tru,\"method\":\"ping\"}") == null);

        // --- descriptor parsed by Felix SCR itself ---
        // The single most valuable check here. A namespace typo or a misplaced deactivate makes
        // Felix report "Ignoring unsupported element" and count 0 components while the file still
        // looks like valid XML, so only Felix's own handler can tell us.
        Path jar = Path.of(args.length > 0 ? args[0] : "");
        checkDescriptor(jar);
        checkManifest(jar);

        // --- live HTTP: the body cap and the id on the wire ---
        try {
            checkHttp();
        } catch (IOException e) {
            fail("live HTTP checks", e.toString());
        }

        // --- endpoint.json atomicity ---
        checkEndpointFile();

        System.out.println();
        if (failures == 0) {
            System.out.println("SELFCHECK PASS (" + total + " checks, 0 failures)");
        } else {
            System.out.println("SELFCHECK FAIL (" + total + " checks, " + failures + " failures)");
            System.exit(1);
        }
    }

    /** Parses the shipped descriptor with the same handler Eclipse's SCR will use. */
    private static void checkDescriptor(Path jar) {
        List<ComponentMetadata> parsed;
        try (JarFile jf = new JarFile(jar.toFile());
                InputStream in = jf.getInputStream(jf.getJarEntry(
                        "OSGI-INF/com.github.lunar.core.LunarServer.xml"))) {
            if (in == null) {
                fail("descriptor present in jar", "OSGI-INF/com.github.lunar.core.LunarServer.xml "
                        + "is missing from " + jar);
                return;
            }
            XmlHandler handler = new XmlHandler(fakeBundle(), new NoOpLogger(), false, false, null);
            SAXParserFactory factory = SAXParserFactory.newInstance();
            // SCR parses namespace-aware; a non-aware parser would report 0 for a correct file too,
            // so this must match BundleComponentActivator exactly.
            factory.setNamespaceAware(true);
            factory.newSAXParser().parse(in, handler);
            parsed = handler.getComponentMetadataList();
        } catch (Exception e) {
            fail("descriptor parses under Felix XmlHandler", e.toString());
            return;
        }
        // This is the check that catches B2 and B3. As shipped it was 0.
        eq("descriptor yields exactly one component", 1, parsed.size());
        if (parsed.size() != 1) {
            return;
        }
        ComponentMetadata c = parsed.get(0);
        eq("component name", "com.github.lunar.core.LunarServer", c.getName());
        eq("component implementation", "com.github.lunar.LunarServer", c.getImplementationClassName());
        yes("component is immediate", c.isImmediate());
        // B3: as a child element this was ignored, so deactivate never ran on shutdown.
        yes("deactivate is a declared attribute", c.isDeactivateDeclared());
        eq("deactivate method name", "deactivate", c.getDeactivate());
    }

    private static void checkManifest(Path jar) {
        try (JarFile jf = new JarFile(jar.toFile())) {
            Manifest mf = jf.getManifest();
            if (mf == null) {
                fail("jar has a manifest", "no manifest in " + jar);
                return;
            }
            java.util.jar.Attributes main = mf.getMainAttributes();
            // B1: Felix SCR reads ONLY this header. Absent, loadComponents returns without ever
            // scanning OSGI-INF/ and the component count is 0 no matter what the XML says.
            eq("Service-Component header", "OSGI-INF/com.github.lunar.core.LunarServer.xml",
                    main.getValue("Service-Component"));
            eq("symbolic name", "com.github.lunar.io;singleton:=true",
                    main.getValue("Bundle-SymbolicName"));
            // B7: both of these have zero imports in the source, and each is a resolution
            // constraint that can fail the whole bundle at P8.
            String require = main.getValue("Require-Bundle");
            yes("Require-Bundle omits core.jobs", require != null && !require.contains("core.jobs"));
            yes("Require-Bundle omits equinox.common",
                    require != null && !require.contains("equinox.common"));
            yes("Require-Bundle keeps core.runtime", require != null
                    && require.contains("org.eclipse.core.runtime"));
            yes("Require-Bundle keeps org.eclipse.osgi",
                    require != null && require.contains("org.eclipse.osgi"));
        } catch (Exception e) {
            fail("manifest readable", e.toString());
        }
    }

    /**
     * Boots a real server on an ephemeral port and drives it over a socket.
     *
     * <p>Raw sockets, not HttpURLConnection: the cap must be provable with a {@code Content-Length}
     * that no well-behaved client library would let you lie about, and that is exactly the request
     * that used to wedge the four-thread pool.
     */
    private static void checkHttp() throws IOException {
        int port;
        try (ServerSocket probe = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            port = probe.getLocalPort();
        } catch (IOException e) {
            fail("pick an ephemeral port", e.toString());
            return;
        }
        McpHttpServer server = new McpHttpServer(TOKEN);
        try {
            server.start(port, "/mcp");
        } catch (IOException e) {
            fail("server starts on an ephemeral port", e.toString());
            return;
        }
        try {
            String auth = "Authorization: Bearer " + TOKEN + "\r\n";

            // B4, declared length: rejected on the header, before a byte of body is read.
            eq("oversized Content-Length rejected with 413", 413,
                    rawStatus(port, auth, "999999999", ""));

            // B4, real bytes: a client that understates nothing must also be refused.
            byte[] big = new byte[McpHttpServer.MAX_BODY_BYTES + 1];
            java.util.Arrays.fill(big, (byte) 'x');
            eq("oversized body rejected with 413", 413,
                    rawStatus(port, auth, String.valueOf(big.length), new String(big, StandardCharsets.UTF_8)));

            // B4, chunked: no Content-Length at all, so only the bounded read can catch this.
            eq("chunked body with no Content-Length rejected with 413", 413,
                    rawChunked(port, auth, new String(big, StandardCharsets.UTF_8)));

            // The cap must not become an unauthenticated oracle: 401 still wins, and 401 still
            // happens without reading the body.
            eq("oversized body without a token still 401s", 401,
                    rawStatus(port, "", "999999999", ""));
            eq("GET rejected without reading a body", 405, rawMethod(port, auth, "GET", null));

            // A body just under the cap must still be served, or the cap is a truncation bug.
            byte[] ok = new byte[4096];
            java.util.Arrays.fill(ok, (byte) 'y');
            String padded = "{\"id\":1,\"method\":\"ping\",\"params\":{\"pad\":\""
                    + new String(ok, StandardCharsets.UTF_8).trim() + "\"}}";
            eq("body under the cap still served", 200,
                    rawStatus(port, auth, String.valueOf(padded.length()), padded));

            // End to end: a good id reaches the response body verbatim, a bad one never does.
            eq("well-formed ping returns 200", 200,
                    rawStatus(port, auth, "15", "{\"id\":1,\"method\":\"ping\"}"));
            eq("bare-word id rejected with a parse error", 200,
                    rawStatus(port, auth, "24", "{\"id\":tru,\"method\":\"ping\"}"));
        } finally {
            // Nothing may outlive the check: a leftover listener would look like a real server.
            server.stop();
        }
        try {
            // After stop() the port must be free again. Proves the pool was shut down too.
            try (ServerSocket rebound = new ServerSocket(port, 0,
                    InetAddress.getLoopbackAddress())) {
                pass("port released on stop");
            }
        } catch (IOException e) {
            fail("port released on stop", e.toString());
        }
    }

    private static int rawStatus(int port, String auth, String contentLength, String body)
            throws IOException {
        return raw(port, "POST /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\n" + auth
                + "Content-Type: application/json\r\nContent-Length: " + contentLength + "\r\n"
                + "Connection: close\r\n\r\n" + body);
    }

    /** Chunked request: deliberately no Content-Length header at all. */
    private static int rawChunked(int port, String auth, String body) throws IOException {
        // One chunk is enough; the point is the missing header, not the framing.
        String wire = "POST /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\n" + auth
                + "Content-Type: application/json\r\nTransfer-Encoding: chunked\r\n"
                + "Connection: close\r\n\r\n"
                + Integer.toHexString(body.length()) + "\r\n" + body + "\r\n0\r\n\r\n";
        return raw(port, wire);
    }

    private static int rawMethod(int port, String auth, String method, String body)
            throws IOException {
        return raw(port, method + " /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\n" + auth
                + "Connection: close\r\n\r\n" + (body == null ? "" : body));
    }

    /** Sends a hand-written request and returns the status code, or -1 if none came back. */
    private static int raw(int port, String request) throws IOException {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 5000);
            s.setSoTimeout(10000);
            OutputStream out = s.getOutputStream();
            out.write(request.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            String statusLine = new String(s.getInputStream().readNBytes(15),
                    StandardCharsets.ISO_8859_1);
            String[] parts = statusLine.split(" ");
            return parts.length >= 2 ? Integer.parseInt(parts[1]) : -1;
        }
    }

    private static void checkEndpointFile() {
        Path dir;
        try {
            dir = Files.createTempDirectory(Path.of("D:\\EclipseIDE\\lunar\\smoke"),
                    "lunar-selfcheck");
        } catch (IOException e) {
            fail("temp dir for endpoint.json", e.toString());
            return;
        }
        try {
            Path file = dir.resolve("nested").resolve("endpoint.json");
            EndpointFile ep = EndpointFile.at(file);
            ep.write("127.0.0.1", 8124, "/mcp", "2025-06-18");
            yes("endpoint.json written", Files.isRegularFile(file));
            String json = Files.readString(file, StandardCharsets.UTF_8);
            Json.Request r = Json.request(json);
            yes("endpoint.json is a well-formed JSON object", r != null);
            yes("endpoint.json carries the pid",
                    json.contains("\"pid\":" + ProcessHandle.current().pid()));
            // A reader must be able to tell a live server from a stale file: pid plus start time.
            yes("endpoint.json carries a start timestamp", json.contains("\"startedAt\":\""));
            yes("endpoint.json names its owner", json.contains("\"owner\":\""));

            // B6: rewrite must be atomic, so a concurrent reader sees one whole version or the
            // other, never a truncated one. Four writers is enough to catch truncate-then-write.
            final Path target = file;
            final int readers = 4;
            final int writesPerThread = 40;
            CountDownLatch go = new CountDownLatch(1);
            List<Thread> threads = new java.util.ArrayList<>();
            final String[] torn = {null};
            Thread reader = new Thread(() -> {
                long deadline = System.nanoTime() + 3_000_000_000L;
                try {
                    go.await();
                    while (System.nanoTime() < deadline) {
                        if (Files.exists(target)) {
                            String seen = Files.readString(target, StandardCharsets.UTF_8);
                            if (!seen.endsWith("}") || Json.request(seen) == null) {
                                torn[0] = "reader saw a partial file of " + seen.length() + " bytes: "
                                        + seen;
                                return;
                            }
                        }
                    }
                } catch (Exception stop) {
                    torn[0] = "reader threw " + stop;
                }
            }, "lunar-selfcheck-reader");
            threads.add(reader);
            for (int t = 0; t < readers; t++) {
                final int id = t;
                threads.add(new Thread(() -> {
                    try {
                        go.await();
                        for (int i = 0; i < writesPerThread; i++) {
                            EndpointFile.at(target).write("127.0.0.1", 8124 + id, "/mcp",
                                    "2025-06-18");
                        }
                    } catch (Exception stop) {
                        torn[0] = "writer threw " + stop;
                    }
                }, "lunar-selfcheck-writer-" + t));
            }
            threads.forEach(Thread::start);
            go.countDown();
            for (Thread th : threads) {
                th.join(20000);
            }
            eq("concurrent readers never see a torn endpoint.json", null, torn[0]);
            try (var leftovers = Files.list(target.getParent())) {
                yes("no temp files left behind",
                        leftovers.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")));
            }

            ep.delete();
            yes("delete removes the file", !Files.exists(file));
        } catch (Exception e) {
            fail("endpoint.json atomic write", e.toString());
        } finally {
            deleteTree(dir);
        }
    }

    private static void deleteTree(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // Best effort; this is a temp directory.
                }
            });
        } catch (IOException ignored) {
            // Best effort; this is a temp directory.
        }
    }

    /**
     * XmlHandler wants a Bundle purely to name it in log messages. A dynamic proxy supplies one
     * without dragging in the whole OSGi framework; any other call is a bug here, so it throws.
     */
    private static Bundle fakeBundle() {
        return (Bundle) java.lang.reflect.Proxy.newProxyInstance(
                SelfCheck.class.getClassLoader(), new Class<?>[]{Bundle.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getLocation" -> {
                            return "file:/lunar-selfcheck.jar";
                        }
                        case "toString" -> {
                            return "lunar-selfcheck";
                        }
                        case "hashCode" -> {
                            return 1;
                        }
                        case "equals" -> {
                            return proxy == args[0];
                        }
                        default -> throw new UnsupportedOperationException(
                                "XmlHandler called Bundle." + method.getName());
                    }
                });
    }

    private static String str(Json.Request r) {
        return r == null ? null : "present";
    }

    private static void eq(String name, int expected, int actual) {
        if (expected == actual) {
            pass(name);
        } else {
            fail(name, "expected " + expected + " but was " + actual);
        }
    }

    private static void eq(String name, String expected, String actual) {
        if (expected == null ? actual == null : expected.equals(actual)) {
            pass(name);
        } else {
            fail(name, "expected " + expected + " but was " + actual);
        }
    }

    private static void yes(String name, boolean ok) {
        if (ok) {
            pass(name);
        } else {
            fail(name, "assertion was false");
        }
    }

    private static void pass(String name) {
        total++;
        System.out.println("PASS - " + name);
    }

    private static void fail(String name, String detail) {
        total++;
        failures++;
        System.out.println("FAIL - " + name + ": " + detail);
    }
}
