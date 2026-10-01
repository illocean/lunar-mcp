package com.github.lunar.tools;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.jobs.Job;

/** A per-request deadline and cancellation handle backed by the actual Eclipse Job monitor. */
public final class CallBudget {
    private static final long DEFAULT_BUDGET_MS = 120_000L;
    private final long deadlineNanos;
    private final long budgetMs;
    private final String sessionId;
    private final AtomicBoolean cancelRequested = new AtomicBoolean();
    private final CountDownLatch wake = new CountDownLatch(1);
    private volatile IProgressMonitor monitor = new NullProgressMonitor();
    private volatile Job job;
    private volatile boolean cancellationHonoured = true;
    private int maxOutputBytes = 16_384;
    private volatile boolean stopped;
    private final java.util.concurrent.atomic.AtomicReference<Runnable> afterStop = new java.util.concurrent.atomic.AtomicReference<>();

    public CallBudget() { this(DEFAULT_BUDGET_MS, "implicit"); }
    public CallBudget(long budgetMs) { this(budgetMs, "implicit"); }
    public CallBudget(long budgetMs, String sessionId) {
        if (budgetMs <= 0 || budgetMs > Long.MAX_VALUE / 1_000_000L) {
            throw new IllegalArgumentException("budgetMs must be positive and fit nanoseconds");
        }
        this.budgetMs = budgetMs;
        this.deadlineNanos = System.nanoTime() + budgetMs * 1_000_000L;
        this.sessionId = sessionId == null || sessionId.isBlank() ? "implicit" : sessionId;
    }

    void attach(Job job) {
        this.job = job;
        if (isCancelRequested()) job.cancel();
    }

    void bindMonitor(IProgressMonitor monitor) {
        boolean cancelled = isCancelRequested();
        this.monitor = monitor;
        if (cancelled || cancelRequested.get()) monitor.setCanceled(true);
    }

    /** Job.cancel marks the Job monitor; it does not interrupt its worker thread. */
    public void cancel() {
        cancelRequested.set(true);
        monitor.setCanceled(true);
        Job running = job;
        if (running != null) running.cancel();
        wake.countDown();
    }

    public boolean isCancelRequested() {
        return cancelRequested.get() || monitor.isCanceled();
    }
    public IProgressMonitor monitor() { return monitor; }
    public String sessionId() { return sessionId; }
    public int maxOutputBytes() { return maxOutputBytes; }
    public void setMaxOutputBytes(int bytes) { maxOutputBytes = bytes; }
    public long budgetMs() { return budgetMs; }
    public boolean cancellationHonoured() { return cancellationHonoured; }
    public boolean isRunning() { return job != null && job.getState() != Job.NONE; }
    public long remainingMs() {
        return Math.max(0L, (deadlineNanos - System.nanoTime()) / 1_000_000L);
    }
    public boolean isExpired() { return System.nanoTime() - deadlineNanos >= 0; }
    public void checkCancelled() {
        if (isCancelRequested()) throw new OperationCanceledException("call cancelled");
        if (isExpired()) throw new OperationCanceledException("call exceeded its deadline");
    }
    void signalCompletion() {
        stopped = true; wake.countDown();
        Runnable action = afterStop.getAndSet(null); if(action != null) action.run();
    }
    public void afterStop(Runnable action) {
        afterStop.set(action);
        if(stopped){Runnable pending = afterStop.getAndSet(null);if(pending != null)pending.run();}
    }
    void awaitCompletionOrCancellation() throws InterruptedException {
        wake.await(remainingMs(), TimeUnit.MILLISECONDS);
    }

    public boolean awaitStopped(CountDownLatch stopped, long graceMs) {
        try {
            cancellationHonoured = stopped.await(graceMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cancellationHonoured = stopped.getCount() == 0;
        }
        return cancellationHonoured;
    }
}
