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

    public static final int PORT = 8124;
    public static final String CONTEXT_PATH = "/mcp";
    public static final String HOST = "127.0.0.1";

    private static final String TOKEN_ENV = "ECLIPSE_MCP_TOKEN";

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
        String token = System.getenv(TOKEN_ENV);
        if (token == null || token.isBlank()) {
            // Refuse to bind rather than serve unauthenticated. A blank token is not a usable token.
            String reason = TOKEN_ENV + " is unset or blank; refusing to bind " + HOST + ":"
                    + PORT + CONTEXT_PATH + ". Set the user-scope environment variable and restart "
                    + "Eclipse.";
            log(IStatus.ERROR, reason, null);
            throw new IllegalStateException(reason);
        }
        try {
            CoreTools.start();
            listenerStarted = true;
            server = new McpHttpServer(token);
            server.start(PORT, CONTEXT_PATH);
            endpoint = EndpointFile.create();
            endpoint.write(HOST, PORT, CONTEXT_PATH, McpHttpServer.PROTOCOL_VERSION);
            // Length only. The value never reaches the log.
            log(IStatus.INFO, "LUNAR MCP endpoint listening on http://" + HOST + ":" + PORT
                    + CONTEXT_PATH + " -> " + endpoint.path() + " (token length " + token.length()
                    + ")", null);
        } catch (Exception e) {
            log(IStatus.ERROR, "LUNAR MCP endpoint failed to start: " + e, e);
            // Release a half-open port before the failure propagates, so a retry can bind it.
            shutdown();
            throw new IllegalStateException("LUNAR MCP endpoint failed to start: " + e, e);
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
