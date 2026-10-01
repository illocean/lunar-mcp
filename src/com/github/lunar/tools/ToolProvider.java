package com.github.lunar.tools;

import java.util.List;

/** One extension contribution for a domain's concrete tools. */
public interface ToolProvider extends AutoCloseable {
    List<Tool> tools();
    default void close() throws Exception { }
}
