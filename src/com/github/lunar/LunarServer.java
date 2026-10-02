package com.github.lunar;

import com.github.lunar.io.McpHttpServer;
import com.github.lunar.tools.CoreTools;
import java.io.IOException;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;

/**
 * Starts the MCP endpoint as a DS immediate component.
 *
 * <p>Bound in the constructor rather than in {@code @PostConstruct}: this bundle is built with plain
 * {@code javac} + {@code jar}, so Equinox's SCR reads the hand-written descriptor in
 * {@code OSGI-INF/}, and the component is instantiated and activated the moment the bundle resolves.
 * The annotation is the source of truth for intent; the descriptor is what makes it real.
 */
@Component(immediate = true, service = LunarServer.class)
public class LunarServer {

    /**
     * Defaults, not fixed values. {@code LUNAR_MCP_HOST} and {@code LUNAR_MCP_PORT} override them so a
     * second workspace, or a machine where 8124 is already taken, can run without a rebuild. The
     * defaults are unchanged, so every documented client URL stays valid when the variables are unset.
     *
     * <p>The host override is still constrained: {@link McpHttpServer#start} rejects anything that is
     * not a loopback address. Widening the bind surface is a security decision, not a config one.
     */
    public static final int DEFAULT_PORT = 8124;
    public static final String CONTEXT_PATH = "/mcp";
    public static final String DEFAULT_HOST = "127.0.0.1";

    private McpHttpServer server;
    private EndpointFile endpoint;
    private boolean listenerStarted;

    /**
     * Throws rather than logging and returning. A DS component whose constructor returns normally
     * is a constructed and activated component, so swallowing a bind failure or a missing token
     * left SCR reporting success for a component serving nothing -- indistinguishable from health
     * to everything downstream. Letting the throw escape makes SCR record the failure, and the
     * logged reason below still names the cause.
     */
    public LunarServer() {
        LunarConfig config = LunarConfig.resolve();
        String host = config.host();
        int port = config.port();
        String token = config.token();
        if (token == null) {
            // Refuse to bind rather than serve unauthenticated. A blank token is not a usable token.
            String reason = LunarConfig.TOKEN_ENV + " is unset and no token is in the lunar config file"
                    + " (" + LunarConfig.defaultPath() + "); refusing to bind "
                    + EndpointFile.url(host, port, CONTEXT_PATH)
                    + ". Run lunar.ps1 setup, set the user-scope environment variable, and restart"
                    + " Eclipse.";
            log(IStatus.ERROR, reason, null);
            throw new IllegalStateException(reason);
        }
        try {
            CoreTools.start();
            listenerStarted = true;
            server = new McpHttpServer(token);
            server.start(host, port, CONTEXT_PATH);
            endpoint = EndpointFile.create();
            endpoint.write(host, port, CONTEXT_PATH, McpHttpServer.PROTOCOL_VERSION);
            // Length only. The value never reaches the log.
            log(IStatus.INFO, "LUNAR MCP endpoint listening on "
                    + EndpointFile.url(host, port, CONTEXT_PATH) + " -> " + endpoint.path()
                    + " (" + config.describe() + ")", null);
        } catch (Exception e) {
            // A bind failure used to surface only in .metadata/.log, so Eclipse started normally and
            // lunar was silently absent. Name the cause and the way out in the console too.
            // No leading space in any arm; the join adds exactly one.
            String hint;
            if (e instanceof java.net.BindException) {
                // BindException covers EADDRINUSE but also EACCES and EADDRNOTAVAIL, so the message
                // must not assert "in use" -- that would send the user hunting a process that is not
                // the problem.
                hint = " Could not bind port " + port + ": already in use, or not permitted to bind."
                        + " Stop whatever holds it, or set " + LunarConfig.PORT_ENV
                        + " to a free port and restart Eclipse.";
            } else if (e instanceof java.net.UnknownHostException) {
                // The likeliest failure this phase introduces: a typo in the new host override.
                hint = " " + LunarConfig.HOST_ENV + "=\"" + host + "\" does not resolve to an address.";
            } else {
                // The loopback refusal from McpHttpServer lands here, and its own message already
                // names the address and the reason, so it needs nothing added.
                hint = "";
            }
            String where = EndpointFile.url(host, port, CONTEXT_PATH);
            log(IStatus.ERROR, "LUNAR MCP endpoint failed to start on " + where + ": " + e + hint, e);
            System.err.println("[LUNAR] MCP endpoint did not start on " + where + ": " + e + hint);
            // Release a half-open port before the failure propagates, so a retry can bind it.
            shutdown();
            throw new IllegalStateException("LUNAR MCP endpoint failed to start: " + e, e);
        }
    }

    // Package-private so SelfCheck exercises this logic rather than a copy of it.
    static String hostFrom(String configured) {
        return configured == null || configured.isBlank() ? DEFAULT_HOST : configured.trim();
    }

    /**
     * Whether the text is a port this server could actually bind. Split out from {@link #portFrom}
     * because the caller has to know both things: which value to use, and whether the value it
     * was given was discarded. A caller that only asked {@link #portFrom} could not tell a
     * deliberate 8124 from a typo that quietly became one.
     */
    static boolean isUsablePort(String configured) {
        if (configured == null || configured.isBlank())
            return false;
        try {
            int value = Integer.parseInt(configured.trim());
            return value >= 1 && value <= 65535;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    static int portFrom(String configured) {
        return isUsablePort(configured) ? Integer.parseInt(configured.trim()) : DEFAULT_PORT;
    }

    @Deactivate
    public void deactivate() {
        shutdown();
    }

    private void shutdown() {
        if (server != null) {
            server.stop();
            server = null;
        }
        if (endpoint != null) {
            try {
                endpoint.delete();
            } catch (IOException e) {
                log(IStatus.ERROR, "Could not remove " + endpoint.path(), e);
            }
            endpoint = null;
        }
        if (listenerStarted) {
            CoreTools.stop();
            listenerStarted = false;
        }
    }

    static void log(int severity, String message, Throwable t) {
        try {
            Platform.getLog(LunarServer.class)
                    .log(new Status(severity, "com.github.lunar.core", message, t));
        } catch (RuntimeException noPlatform) {
            // Platform.getLog throws outside OSGi rather than returning a no-op log, so anything
            // that reports a configuration problem before the platform is up would replace the
            // warning with an exception -- and the self-check runs in a plain JVM. Falling back to
            // the console keeps the message, which is the entire reason it was logged.
            System.err.println("[LUNAR] " + message);
            if (t != null) {
                t.printStackTrace();
            }
        }
    }
}
