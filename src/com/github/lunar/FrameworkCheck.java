package com.github.lunar;

import com.github.lunar.tools.CallBudget;
import com.github.lunar.tools.SchemaValidator;
import com.github.lunar.tools.Tool;
import com.github.lunar.tools.ToolResult;
import com.github.lunar.tools.ToolRunner;
import com.github.lunar.tools.ToolSpec;
import com.github.lunar.tools.Tools;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Function;

/** One runnable check for P2's argument trust boundary and real Eclipse Jobs. */
public final class FrameworkCheck {
    private static int checks;
    public static void main(String[] args) throws Exception {
        Map<String, Object> required = Tools.object(Map.of("path", Tools.string()), "path");
        SchemaValidator.validateDefinition(required);
        check("required schema registers", true);
        rejects(() -> SchemaValidator.validate(required, Map.of()));
        rejects(() -> SchemaValidator.validate(required, Map.of("path", "x", "extra", true)));
        Map<String, Object> defaulted = Tools.object(Map.of("n", Map.of("type", "integer",
                "default", 10, "minimum", 0, "maximum", 20)));
        SchemaValidator.validateDefinition(defaulted);
        check("Integer default becomes Long", SchemaValidator.validate(defaulted, Map.of())
                .get("n").equals(10L));
        check("integral Double becomes Long", SchemaValidator.validate(defaulted, Map.of("n", 1.0))
                .get("n").equals(1L));
        rejects(() -> SchemaValidator.validate(defaulted, Map.of("n", 1.5)));
        rejects(() -> SchemaValidator.validate(defaulted, Map.of("n", Double.NaN)));
        rejects(() -> SchemaValidator.validate(defaulted, Map.of("n", 21L)));
        rejects(() -> SchemaValidator.validate(Tools.object(Map.of("b", Tools.bool(true))),
                Map.of("b", "true")));
        rejects(() -> SchemaValidator.validateDefinition(Tools.object(Map.of("nested",
                Map.of("type", "object", "properties", Map.of("x", Map.of("type", "string",
                        "pattern", "ignored")))))));
        Map<String, Object> exact = Tools.object(Map.of("n", Map.of("type", "integer",
                "maximum", Long.MAX_VALUE - 1)));
        rejects(() -> SchemaValidator.validate(exact, Map.of("n", Long.MAX_VALUE)));
        Map<String, Object> nullable = Tools.object(Map.of("n", Map.of("type", "null", "enum",
                java.util.Collections.singletonList(null))));
        check("null enum", SchemaValidator.validate(nullable,
                java.util.Collections.singletonMap("n", null)).containsKey("n"));
        Map<String, Object> arrays = Tools.object(Map.of("n", Map.of("type", "array", "items",
                Map.of("type", "integer"))));
        check("array coerces items", SchemaValidator.validate(arrays, Map.of("n", List.of(1, 2)))
                .get("n").equals(List.of(1L, 2L)));
        Map<String, Object> generic = Tools.object(Map.of("arguments",
                Map.of("type", "object", "additionalProperties", true)), "arguments");
        SchemaValidator.validateDefinition(generic);
        Map<String, Object> copied = SchemaValidator.validate(generic, Map.of("arguments",
                Map.of("path", "x", "extra", Map.of("nested", List.of(1L, true)))));
        check("generic arguments preserved", copied.get("arguments").equals(
                Map.of("path", "x", "extra", Map.of("nested", List.of(1L, true)))));
        rejects(() -> SchemaValidator.validate(required, copied.get("arguments")));
        ToolResult success = ToolRunner.run(tool(b -> ToolResult.ok(Map.of("name", "月"), null)),
                Map.of(), new CallBudget(5_000), null);
        check("real Job result", success.ok() && success.meta().bytesAfterShaping() == 14);
        // Worker.run's exception table routes Error and Exception to the same handler, so an Error
        // used to complete the job with result and failure both null. That reached the client as
        // no_result, carrying no exception class and no stack -- the one failure it cannot act on.
        ToolResult errored = ToolRunner.run(tool(b -> { throw new StackOverflowError("deep"); }),
                Map.of(), new CallBudget(5_000), null);
        check("an Error is a tool failure, not no_result", !errored.ok()
                && "tool_failed".equals(errored.error().get("code"))
                && String.valueOf(((Map<?, ?>) errored.error().get("details")).get("exception"))
                        .endsWith("StackOverflowError"));
        AtomicBoolean called = new AtomicBoolean();
        CallBudget alreadyCancelled = new CallBudget(5_000);
        alreadyCancelled.cancel();
        ToolResult cancelled = ToolRunner.run(tool(b -> {
            called.set(true); return ToolResult.ok(Map.of(), null);
        }), Map.of(), alreadyCancelled, null);
        check("cancel before attach", !called.get() && !cancelled.ok()
                && cancelled.meta().cancellationHonoured());
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean observed = new AtomicBoolean();
        CallBudget budget = new CallBudget(10_000);
        CompletableFuture<ToolResult> future = CompletableFuture.supplyAsync(() -> ToolRunner.run(
                tool(b -> {
                    started.countDown();
                    while (!b.monitor().isCanceled()) LockSupport.parkNanos(1_000_000L);
                    observed.set(true);
                    b.checkCancelled();
                    return ToolResult.ok(Map.of(), null);
                }), Map.of(), budget, null));
        check("Job started", started.await(3, TimeUnit.SECONDS));
        budget.cancel();
        ToolResult stopped = future.get(3, TimeUnit.SECONDS);
        check("actual monitor receives cancel", observed.get() && !stopped.ok()
                && stopped.meta().cancellationHonoured());
        ToolResult expired = ToolRunner.run(tool(b -> {
            while (true) { b.checkCancelled(); LockSupport.parkNanos(1_000_000L); }
        }), Map.of(), new CallBudget(50), null);
        check("deadline Job stopped", !expired.ok() && expired.meta().cancellationHonoured());
        CountDownLatch stubbornStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch stubbornStopped = new CountDownLatch(1);
        CountDownLatch deferredRelease = new CountDownLatch(1);
        CallBudget stubbornBudget = new CallBudget(10_000);
        CompletableFuture<ToolResult> stubborn = CompletableFuture.supplyAsync(() -> ToolRunner.run(
                tool(b -> {
                    stubbornStarted.countDown();
                    try { release.await(); } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    } finally { stubbornStopped.countDown(); }
                    return ToolResult.ok(Map.of(), null);
                }), Map.of(), stubbornBudget, null));
        try {
            check("stubborn Job started", stubbornStarted.await(3, TimeUnit.SECONDS));
            stubbornBudget.cancel();
            ToolResult dishonest = stubborn.get(3, TimeUnit.SECONDS);
            check("ignored cancellation reported", !dishonest.ok()
                    && "cancellation_not_honoured".equals(dishonest.error().get("code"))
                    && !dishonest.meta().cancellationHonoured());
            stubbornBudget.afterStop(deferredRelease::countDown);
            check("release waits for actual stop", deferredRelease.getCount() == 1);
        } finally {
            release.countDown();
            check("stubborn Job released", stubbornStopped.await(3, TimeUnit.SECONDS));
            check("completion listener releases orphan", deferredRelease.await(3, TimeUnit.SECONDS));
        }
        System.out.println("FRAMEWORK CHECK PASS (" + checks + " checks)");
    }

    private static Tool tool(Function<CallBudget, ToolResult> body) {
        return new Tool() {
            private final ToolSpec spec = new ToolSpec("framework_check", "Framework check", "Check",
                    Tools.object(Map.of()), ToolSpec.RiskTier.READ);
            public ToolSpec spec() { return spec; }
            public ToolResult call(Map<String, Object> args, CallBudget budget) { return body.apply(budget); }
        };
    }
    private static void rejects(Runnable action) {
        try { action.run(); } catch (SchemaValidator.SchemaException expected) { checks++; return; }
        throw new AssertionError("invalid schema or arguments were accepted");
    }
    private static void check(String message, boolean condition) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
