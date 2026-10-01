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

    private static final String TOKEN_ENV = "ECLIPSE_MCP_TOKEN";
    private static final String HOST_ENV = "LUNAR_MCP_HOST";
    private static final String PORT_ENV = "LUNAR_MCP_PORT";

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
        String host = host();
        int port = port();
        String token = System.getenv(TOKEN_ENV);
        if (token == null || token.isBlank()) {
            // Refuse to bind rather than serve unauthenticated. A blank token is not a usable token.
            String reason = TOKEN_ENV + " is unset or blank; refusing to bind "
                    + EndpointFile.url(host, port, CONTEXT_PATH)
                    + ". Set the user-scope environment variable and restart Eclipse.";
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
                    + " (token length " + token.length() + ")", null);
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
                        + " Stop whatever holds it, or set " + PORT_ENV + " to a free port and restart"
                        + " Eclipse.";
            } else if (e instanceof java.net.UnknownHostException) {
                // The likeliest failure this phase introduces: a typo in the new host override.
                hint = " " + HOST_ENV + "=\"" + host + "\" does not resolve to an address.";
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

    /** {@code LUNAR_MCP_HOST} when set, else the documented default. */
    private static String host() {
        return hostFrom(System.getenv(HOST_ENV));
    }

    /**
     * {@code LUNAR_MCP_PORT} when it parses to a usable port, else the documented default. An
     * unusable value falls back rather than throwing, because a typo should not be the reason the
     * endpoint is missing at all -- but it is never silent, unlike a bind failure. A discarded
     * value is logged by name, because otherwise a user who set the variable and mistyped their
     * client URL has no way to learn the setting was ignored.
     */
    private static int port() {
        String configured = System.getenv(PORT_ENV);
        if (configured == null || configured.isBlank())
            return DEFAULT_PORT;
        // Parse once here rather than re-deriving the outcome from the raw text, so the warning can
        // never drift from what portFrom actually accepts.
        try {
            int value = Integer.parseInt(configured.trim());
            if (value >= 1 && value <= 65535)
                return value;
        } catch (NumberFormatException ignored) {
            // Falls through to the warning below.
        }
        log(IStatus.WARNING, "Ignoring " + PORT_ENV + "=\"" + configured + "\": not a port in 1-65535. "
                + "Using the default " + DEFAULT_PORT + " instead.", null);
        return DEFAULT_PORT;
    }

    // Package-private so SelfCheck exercises this logic rather than a copy of it.
    static String hostFrom(String configured) {
        return configured == null || configured.isBlank() ? DEFAULT_HOST : configured.trim();
    }

    static int portFrom(String configured) {
        if (configured == null || configured.isBlank())
            return DEFAULT_PORT;
        try {
            int value = Integer.parseInt(configured.trim());
            return value >= 1 && value <= 65535 ? value : DEFAULT_PORT;
        } catch (NumberFormatException e) {
            return DEFAULT_PORT;
        }
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

    private static void log(int severity, String message, Throwable t) {
        Platform.getLog(LunarServer.class).log(new Status(severity, "com.github.lunar.core", message, t));
    }
}
