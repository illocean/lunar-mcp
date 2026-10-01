package com.github.lunar.tools;

import java.util.Map;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.jobs.ISchedulingRule;

/**
 * A contributed tool. Implementations must have a public no-arg constructor: the registry
 * instantiates them reflectively from the extension point, once, and keeps the instance.
 */
public interface Tool {

    ToolSpec spec();

    /** Writes share Eclipse's workspace rule; narrower tools can override it. */
    default ISchedulingRule schedulingRule() {
        return switch (spec().riskTier()) {
            case READ -> null;
            default -> ResourcesPlugin.getWorkspace().getRoot();
        };
    }

    /**
     * Runs the tool. Called on a Job worker thread, never on the HTTP thread and never on a UI
     * thread.
     *
     * @param args     already validated and coerced against {@link ToolSpec#inputSchema()}
     * @param budget   deadline and cancellation; a tool that ignores it is a bug, and the budget
     *                 reports when one did rather than letting it pass as a clean finish
     */
    ToolResult call(Map<String, Object> args, CallBudget budget) throws Exception;
}
