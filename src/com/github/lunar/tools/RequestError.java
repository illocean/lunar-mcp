package com.github.lunar.tools;

/**
 * A tool failure that carries a stable machine-readable code. ToolRunner maps it to that code, so a
 * caller can branch on {@code thread_not_suspended} instead of the generic {@code tool_failed}.
 * Extends IllegalArgumentException so it stays unchecked at every throw site.
 */
public class RequestError extends IllegalArgumentException {
    public final String code;

    public RequestError(String code,String message) {
        super(message);
        this.code = code;
    }
}
