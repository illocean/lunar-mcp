package com.github.lunar.tools;

import com.github.lunar.io.Json;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.IJobChangeListener;
import org.eclipse.core.runtime.jobs.ISchedulingRule;
import org.eclipse.core.runtime.jobs.Job;

/** Eclipse Jobs provide workspace scheduling and completion in headless and UI launches. */
public final class ToolRunner {
    public static final long CANCEL_GRACE_MS = 2_000L;
    private ToolRunner() { }

    public static ToolResult run(Tool tool, Map<String, Object> args, CallBudget budget,
            ISchedulingRule rule) {
        long started = System.nanoTime();
        AtomicReference<ToolResult> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch stopped = new CountDownLatch(1);
        Job job = new Job("lunar: " + tool.spec().name()) {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                budget.bindMonitor(monitor);
                try {
                    budget.checkCancelled();
                    result.set(tool.call(args, budget));
                    return Status.OK_STATUS;
                } catch (OperationCanceledException cancelled) {
                    failure.set(cancelled);
                    return Status.CANCEL_STATUS;
                } catch (Exception exception) {
                    failure.set(exception);
                    // The error envelope owns reporting; avoid a second workbench error dialog.
                    return Status.OK_STATUS;
                }
            }
        };
        job.setRule(rule);
        job.setSystem(true);
        IJobChangeListener onDone = IJobChangeListener.onDone(event -> {
            stopped.countDown();
            budget.signalCompletion();
        });
        job.addJobChangeListener(onDone);
        budget.attach(job);
        boolean deadlineExceeded = false;
        try {
            job.schedule();
            budget.awaitCompletionOrCancellation();
            boolean finished = stopped.getCount() == 0;
            if (!finished) {
                deadlineExceeded = budget.isExpired();
                budget.cancel();
                finished = budget.awaitStopped(stopped, CANCEL_GRACE_MS);
            }
            long elapsed = (System.nanoTime() - started) / 1_000_000L;
            ToolResult.Meta meta = new ToolResult.Meta(0, 0, false, null, elapsed,
                    budget.budgetMs(), finished);
            if (!finished) {
                return ToolResult.error("cancellation_not_honoured", tool.spec().name()
                        + " is still running after cancellation; do not assume the workspace is idle",
                        Map.of("job", job.getName(), "elapsedMs", elapsed), meta);
            }
            if (deadlineExceeded || budget.isCancelRequested()) {
                ToolResult partial = result.get();
                return ToolResult.error(deadlineExceeded ? "deadline_exceeded" : "cancelled",
                        tool.spec().name() + (deadlineExceeded ? " exceeded its deadline"
                                : " was cancelled"), partial == null ? Map.of()
                                : Map.of("partialResult",Json.parse(partial.toJson())), meta);
            }
            Throwable error = failure.get();
            if (error != null) {
                // A RequestError already names a stable code; do not flatten it to tool_failed.
                String code = error instanceof OperationCanceledException ? "cancelled"
                        : error instanceof RequestError request ? request.code : "tool_failed";
                // Include where it came from: without a location a tool_failed is undiagnosable from the client side.
                Map<String,Object> details = new LinkedHashMap<>();
                details.put("exception", error.getClass().getName());
                StackTraceElement[] frames = error.getStackTrace();
                List<String> where = new ArrayList<>(Math.min(frames.length,6));
                for (int i = 0; i < frames.length && i < 6; i++) where.add(frames[i].toString());
                if (!where.isEmpty()) details.put("stack", where);
                return ToolResult.error(code, tool.spec().name() + ": " + error.getMessage(), details, meta);
            }
            ToolResult output = result.get();
            if (output == null) {
                return ToolResult.error("no_result", tool.spec().name() + " produced no result", meta);
            }
            long bytes = Json.write(output.data()).getBytes(StandardCharsets.UTF_8).length;
            return new ToolResult(output.ok(), output.data(), output.error(),
                    new ToolResult.Meta(bytes, bytes, false, null, elapsed, budget.budgetMs(), true));
        } catch (InterruptedException interrupted) {
            budget.cancel();
            // Clear the interrupted flag while allowing a cooperative job the same stop grace.
            boolean finished = budget.awaitStopped(stopped, CANCEL_GRACE_MS);
            Thread.currentThread().interrupt();
            return ToolResult.error(finished ? "interrupted" : "cancellation_not_honoured",
                    "Interrupted waiting for " + tool.spec().name()
                            + (finished ? "; job stopped" : "; job is still running"),
                    new ToolResult.Meta(0, 0, false, null,
                            (System.nanoTime() - started) / 1_000_000L, budget.budgetMs(), finished));
        } finally {
            if(job.getState()==Job.NONE)job.removeJobChangeListener(onDone);
        }
    }
}
