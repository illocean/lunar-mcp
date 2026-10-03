package com.github.lunar;

import com.github.lunar.io.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.eclipse.core.runtime.IStatus;

/**
 * The endpoint settings, resolved once from the places they can come from.
 *
 * <p>Order is environment, then the config file, then the built-in default. The environment
 * wins because it is per-process: two Eclipse installations on one machine can be given
 * different ports without either having to edit a shared file, and a value exported in the
 * terminal that launched Eclipse beats whatever was written to disk last week. The file is
 * the fallback so a fresh install works without anyone having to know the variable names.
 *
 * <p>The server only ever reads the file. {@code lunar.ps1} is what writes it, and what
 * copies the token out to the user-scope environment variable that MCP clients expand.
 */
final class LunarConfig {

    static final String TOKEN_ENV = "ECLIPSE_MCP_TOKEN";
    static final String HOST_ENV = "LUNAR_MCP_HOST";
    static final String PORT_ENV = "LUNAR_MCP_PORT";

    /**
     * Shortest token this server will accept, in characters.
     *
     * <p>The floor is on the token rather than on the transport, because a bearer token is
     * the only thing standing between a local process and an endpoint that can delete
     * projects from disk. A short one is a guessable one, and the comparison is constant
     * time, so a guessable token gets exactly as many attempts as a good one.
     *
     * <p>32 characters is what OWASP gives as the floor for a generated secret (128 bits of
     * entropy), and it is also the length of the hex form of 16 CSPRNG bytes, which is what
     * the log line below tells the reader to make.
     */
    static final int MIN_TOKEN_LENGTH = 32;

    private final String host;
    private final int port;
    private final String token;
    /** Which of the three each value came from. Only ever logged, never returned to a client. */
    private final String hostSource;
    private final String portSource;
    private final String tokenSource;

    private LunarConfig(String host, int port, String token,
            String hostSource, String portSource, String tokenSource) {
        this.host = host;
        this.port = port;
        this.token = token;
        this.hostSource = hostSource;
        this.portSource = portSource;
        this.tokenSource = tokenSource;
    }

    static LunarConfig resolve() {
        return resolve(defaultPath());
    }

    /**
     * The same resolution against a named file, so the self-check can point at a temporary
     * one instead of reading whatever the developer happens to have on their own machine.
     */
    static LunarConfig resolve(Path configFile) {
        return fromFile(read(configFile), System.getenv(TOKEN_ENV), System.getenv(HOST_ENV),
                System.getenv(PORT_ENV));
    }

    /**
     * The resolution itself, with the environment passed in rather than read from it.
     *
     * <p>Split from {@link #resolve} so the self-check can state the environment instead of
     * inheriting whatever the developer's own machine happens to export -- otherwise every
     * assertion about the config file would silently be an assertion about the environment
     * whenever the machine had a token set, which is exactly the case that has to work.
     */
    static LunarConfig fromFile(Map<String, Object> file, String tokenEnv, String hostEnv,
            String portEnv) {
        String fileHost = text(file, "host");

        String host;
        String hostSource;
        if (present(hostEnv)) {
            host = LunarServer.hostFrom(hostEnv);
            hostSource = HOST_ENV;
        } else if (present(fileHost)) {
            host = LunarServer.hostFrom(fileHost);
            hostSource = "config file";
        } else {
            host = LunarServer.DEFAULT_HOST;
            hostSource = "default";
        }

        // A port that cannot be used falls back rather than throwing, because a typo should not
        // be the reason the endpoint is missing at all -- but it is never silent, unlike a bind
        // failure. The discarded value is named, or a user who set it and mistyped their client
        // URL has no way to learn the setting was ignored.
        int port;
        String portSource;
        if (present(portEnv)) {
            if (LunarServer.isUsablePort(portEnv)) {
                port = LunarServer.portFrom(portEnv);
                portSource = PORT_ENV;
            } else {
                LunarServer.log(IStatus.WARNING, "Ignoring " + PORT_ENV + "=\"" + portEnv
                        + "\": not a port in 1-65535. Using the default "
                        + LunarServer.DEFAULT_PORT + " instead.", null);
                port = LunarServer.DEFAULT_PORT;
                portSource = "default";
            }
        } else {
            // Read once: integer() logs why it rejected a value, and calling it twice would
            // put the same warning in the log twice.
            Integer filePort = integer(file, "port");
            port = filePort == null ? LunarServer.DEFAULT_PORT : filePort;
            portSource = filePort == null ? "default" : "config file";
        }

        String token;
        String tokenSource;
        String fileToken = text(file, "token");
        if (present(tokenEnv)) {
            token = tokenEnv.trim();
            tokenSource = TOKEN_ENV;
        } else if (present(fileToken)) {
            token = fileToken.trim();
            tokenSource = "config file";
        } else {
            token = null;
            tokenSource = "unset";
        }

        // Refused exactly the way a blank token is, and for the same reason: falling through
        // to "no token" makes the server decline to bind, which is the safe outcome. What is
        // not safe is serving a credential that can be guessed, so a short one is not served
        // with a warning -- it is not a token at all. Only the length is ever logged.
        if (token != null && token.length() < MIN_TOKEN_LENGTH) {
            LunarServer.log(IStatus.WARNING,
                    "Ignoring the " + tokenSource + " token: " + token.length()
                            + " characters is below the " + MIN_TOKEN_LENGTH
                            + "-character minimum for a bearer token, and a guessable token is"
                            + " not a token. Generate one with: powershell -NoProfile -Command"
                            + " \"$b = New-Object byte[] 32;"
                            + " [System.Security.Cryptography.RandomNumberGenerator]::Create()"
                            + ".GetBytes($b); -join ($b | ForEach-Object { '{0:x2}' -f $_ })\"",
                    null);
            token = null;
            tokenSource = "rejected (too short)";
        }

        return new LunarConfig(host, port, token, hostSource, portSource, tokenSource);
    }

    static Path defaultPath() {
        return Path.of(System.getProperty("user.home"), ".lunar", "config.json");
    }

    /**
     * The config file as a map, or empty. Every failure mode lands here rather than throwing:
     * a file that does not exist is the normal case for anyone who set the environment
     * variables by hand, and a malformed one must not be the reason the endpoint is missing.
     * A file that exists but cannot be read is worth a line in the log, though -- otherwise
     * a typo in the path looks exactly like having no configuration at all.
     */
    static Map<String, Object> read(Path configFile) {
        if (configFile == null || !Files.isRegularFile(configFile)) {
            return Map.of();
        }
        String text;
        try {
            text = Files.readString(configFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LunarServer.log(IStatus.WARNING,
                    "Could not read " + configFile + "; falling back to the environment and defaults.",
                    e);
            return Map.of();
        }
        try {
            if (Json.parse(text) instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) map;
                return typed;
            }
            // Parsed, and it is not an object -- an array, a number, a bare string. Every key
            // lookup on one of those misses silently, so without this the file reads as
            // "nothing configured" and the reason for it never appears anywhere. README.md
            // promises a log line for a config file it cannot use; this is that line.
            LunarServer.log(IStatus.WARNING,
                    "The lunar config file " + configFile
                            + " is valid JSON but not an object, so none of it was used."
                            + " It has to be an object with \"token\", \"host\" and \"port\" keys."
                            + " Falling back to the environment and defaults.",
                    null);
        } catch (RuntimeException malformed) {
            LunarServer.log(IStatus.WARNING,
                    "Could not parse " + configFile + ": " + malformed.getMessage()
                            + ". Falling back to the environment and defaults.",
                    null);
        }
        return Map.of();
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private static String text(Map<String, Object> file, String key) {
        Object value = file.get(key);
        return value instanceof String s ? s : null;
    }

    /**
     * A port may arrive as a JSON number or as a quoted string, because both are things a
     * person types. A number out of range is rejected here rather than at bind time, so the
     * message can name the file instead of surfacing as an unexplained bind failure.
     */
    private static Integer integer(Map<String, Object> file, String key) {
        Object value = file.get(key);
        if (value == null) {
            return null; // No key at all is not a mistake, and is what an absent file gives.
        }
        int parsed;
        if (value instanceof Long l) {
            parsed = l.intValue();
        } else if (value instanceof String s) {
            try {
                parsed = Integer.parseInt(s.trim());
            } catch (NumberFormatException notANumber) {
                LunarServer.log(IStatus.WARNING,
                        "Ignoring the port in the lunar config file: \"" + s + "\" is not a number.",
                        null);
                return null;
            }
        } else {
            // A port of true, or of 8124.5. Present, so wrong, so it says so -- the same
            // reason a number out of range does rather than quietly falling back.
            LunarServer.log(IStatus.WARNING,
                    "Ignoring the port in the lunar config file: " + value
                            + " is neither a number nor a quoted number.",
                    null);
            return null;
        }
        if (parsed < 1 || parsed > 65535) {
            LunarServer.log(IStatus.WARNING,
                    "Ignoring the port in the lunar config file: " + parsed + " is not in 1-65535.",
                    null);
            return null;
        }
        return parsed;
    }

    String host() {
        return host;
    }

    int port() {
        return port;
    }

    /** Null when neither the environment nor the file carries one; the caller refuses to bind. */
    String token() {
        return token;
    }

    /** One line naming where each value came from. The values themselves are never in it. */
    String describe() {
        return "host " + host + " from " + hostSource + ", port " + port + " from " + portSource
                + ", token " + (token == null ? "unset" : "set (" + token.length() + " chars) from "
                        + tokenSource);
    }
}
