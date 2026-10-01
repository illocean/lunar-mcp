package com.github.lunar.tools;

import com.github.lunar.io.Json;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The one result shape, always: {@code {ok, data, error, meta}}.
 *
 * <p>{@code meta} carries the fields the P7 algorithm layers need — byte counts either side of
 * shaping, the truncation flag, the resume cursor, elapsed ms. P2 does not implement shaping, delta
 * or ranking, so every call reports {@code truncated=false} and a null cursor. The fields exist
 * now because retrofitting them onto 40-odd results is exactly the churn this shape exists to
 * prevent, and a client that learns one envelope learns one parser instead of one per tool.
 */
public record ToolResult(
        boolean ok,
        Object data,
        Map<String, Object> error,
        Meta meta) {

    /**
     * @param bytesBeforeShaping  size of the tool's raw output, before any shaping
     * @param bytesAfterShaping   size as actually emitted. Equal in P2, since nothing shapes.
     * @param truncated           true if the emitted data was cut short
     * @param resumeCursor        opaque token to continue from, or null when there is nothing more
     * @param elapsedMs           wall time inside the tool, measured not estimated
     * @param deadlineMs          the budget the call ran under
     * @param cancellationHonoured false when the tool ignored cancellation and was still running
     *                             after the grace window. The result then says so instead of
     *                             claiming a clean stop.
     */
    public record Meta(
            long bytesBeforeShaping,
            long bytesAfterShaping,
            boolean truncated,
            String resumeCursor,
            long elapsedMs,
            long deadlineMs,
            boolean cancellationHonoured) {

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("bytesBeforeShaping", bytesBeforeShaping);
            m.put("bytesAfterShaping", bytesAfterShaping);
            m.put("truncated", truncated);
            m.put("resumeCursor", resumeCursor);
            m.put("elapsedMs", elapsedMs);
            m.put("deadlineMs", deadlineMs);
            m.put("cancellationHonoured", cancellationHonoured);
            return m;
        }
    }

    public static ToolResult ok(Object data, Meta meta) {
        return new ToolResult(true, data, null, meta);
    }

    /** Schema-shaped error. {@code details} carries whatever helps the caller fix the call. */
    public static ToolResult error(String code, String message, Map<String, Object> details,
            Meta meta) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("code", code);
        e.put("message", message);
        if (details != null && !details.isEmpty()) {
            e.put("details", details);
        }
        return new ToolResult(false, null, e, meta);
    }

    public static ToolResult error(String code, String message, Meta meta) {
        return error(code, message, null, meta);
    }

    public String toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", ok);
        m.put("data", data);
        m.put("error", error);
        m.put("meta", meta == null ? null : meta.toMap());
        return Json.write(m);
    }
}
