package io.github.ahmedhashim.deadline;

import java.util.Map;

/**
 * One request as a hop sees it: when it arrived, its deadline if the caller
 * sent one, and the trace of what this hop did with it.
 */
public final class RequestContext {
    private final String name;
    private final Clock clock;
    private final long startNanos;
    private final long startEpochMs;
    private final Deadline deadline;
    private final long budgetMs;
    private final Trace trace = new Trace();
    private final Map<String, String> forward;

    public RequestContext(String name, Clock clock, Deadline deadline, long budgetMs, Map<String, String> forward) {
        this.name = name;
        this.clock = clock;
        this.startNanos = clock.nanos();
        this.startEpochMs = System.currentTimeMillis();
        this.deadline = deadline;
        this.budgetMs = budgetMs;
        this.forward = Map.copyOf(forward);
    }

    /** A context for the first hop, which makes the budget up rather than receiving it. */
    public static RequestContext root(String name, Clock clock, long budgetMs) {
        Deadline d = budgetMs < 0 ? null : Deadline.afterMillis(budgetMs, clock);
        return new RequestContext(name, clock, d, budgetMs, Map.of());
    }

    public String name() {
        return name;
    }

    public Clock clock() {
        return clock;
    }

    /** May be null: the caller sent no budget, or this hop doesn't enforce one. */
    public Deadline deadline() {
        return deadline;
    }

    /** The budget as received, or -1. */
    public long budgetMs() {
        return budgetMs;
    }

    public long startNanos() {
        return startNanos;
    }

    public long startEpochMs() {
        return startEpochMs;
    }

    public long elapsedMs() {
        return (clock.nanos() - startNanos) / 1_000_000L;
    }

    /** Throws {@link DeadlineExceededException} if nobody is waiting any more. */
    public void check(String what) {
        if (deadline != null) {
            deadline.check(what);
        }
    }

    public Trace trace() {
        return trace;
    }

    /** Headers to pass on to downstream calls unchanged (request ids and the like). */
    public Map<String, String> forward() {
        return forward;
    }
}
