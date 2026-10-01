package com.github.lunar;

import com.github.lunar.io.Json;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import org.eclipse.core.runtime.Platform;
import org.eclipse.osgi.service.datalocation.Location;

/**
 * Publishes where the server is listening so a client can find it. The instance location is
 * resolved at runtime; the install path is never compiled in.
 */
final class EndpointFile {

    private static final String RELATIVE_DIR = ".metadata/.plugins/com.github.lunar/server";
    private static final String FILE_NAME = "endpoint.json";

    private final Path file;

    private EndpointFile(Path file) {
        this.file = file;
    }

    /** Bypasses the instance location, for a self-check that runs outside OSGi. */
    static EndpointFile at(Path file) {
        return new EndpointFile(file);
    }

    static EndpointFile create() {
        Location instance = Platform.getInstanceLocation();
        URL url = instance == null ? null : instance.getURL();
        if (url == null) {
            throw new IllegalStateException(
                    "Eclipse instance location is unset; cannot locate " + FILE_NAME);
        }
        try {
            Path root = Path.of(url.toURI());
            return new EndpointFile(root.resolve(RELATIVE_DIR).resolve(FILE_NAME));
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Unusable Eclipse instance location: " + url, e);
        }
    }

    void write(String host, int port, String path, String protocolVersion) throws IOException {
        Files.createDirectories(file.getParent());
        // The token is deliberately absent: this file sits in the workspace and is readable by
        // anything that can read the workspace. Only the token's presence is implied, never its value.
        String json = Json.obj(
                "url", Json.quote("http://" + host + ":" + port + path),
                "port", Integer.toString(port),
                "path", Json.quote(path),
                "protocolVersion", Json.quote(protocolVersion),
                "pid", Long.toString(ProcessHandle.current().pid()),
                "startedAt", Json.quote(Instant.now().toString()),
                "owner", Json.quote(System.getProperty("user.name", "")));
        writeAtomically(json);
    }

    /**
     * Write-then-rename, never truncate-then-write. The documented health check is
     * {@code Test-Path ...\endpoint.json}, so a reader that arrives between truncate and write sees
     * an empty or half-written file and reports a live server that has no socket. ATOMIC_MOVE makes
     * the rename indivisible on NTFS; the plain REPLACE_EXISTING fallback keeps this working on a
     * filesystem that cannot promise it.
     */
    private void writeAtomically(String json) throws IOException {
        Path tmp = Files.createTempFile(file.getParent(), "endpoint", ".json.tmp");
        try {
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            moveOntoTarget(tmp);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /**
     * On Windows a rename onto a file that any process currently has open fails with
     * AccessDeniedException, because NTFS will not replace a file with a live handle. The
     * health-check reader does exactly that, so a bare move would fail the write outright rather
     * than merely race it. Bounded retry: the reader closes its handle and the rename then
     * succeeds. Ten attempts over roughly a second covers a reader mid-read without turning a
     * genuinely locked file into a hang.
     */
    private void moveOntoTarget(Path tmp) throws IOException {
        AccessDeniedException last = null;
        for (int attempt = 0; attempt < 100; attempt++) {
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (AtomicMoveNotSupportedException noAtomicityHere) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (AccessDeniedException stillOpen) {
                last = stillOpen;
                try {
                    Thread.sleep(10);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw last;
                }
            }
        }
        throw last;
    }

    void delete() throws IOException {
        Files.deleteIfExists(file);
    }

    Path path() {
        return file;
    }
}
