package io.github.ahmedhashim.deadline;

/**
 * What to do about the next attempt of a call. Pure: given the policy and the
 * deadline it decides, so it can be tested on a {@link VirtualClock}.
 */
public sealed interface Plan {

    /**
     * Send the attempt.
     *
     * @param waitMs   how long we wait for the reply
     * @param budgetMs the budget to hand the callee, or -1 to send none
     */
    record Send(long waitMs, long budgetMs) implements Plan {
    }

    /** Don't send: the attempt could not finish before nobody is waiting. */
    record Skip(long remainingMs, long neededMs) implements Plan {
    }

    static Plan next(CallPolicy policy, Deadline deadline) {
        long timeout = policy.attemptTimeout().toMillis();
        if (!policy.propagate() || deadline == null) {
            return new Send(timeout, -1);
        }
        long remaining = deadline.remainingMillis();
        long margin = policy.margin().toMillis();
        long needed = policy.minUseful().toMillis() + margin;
        if (remaining < needed) {
            return new Skip(remaining, needed);
        }
        // We wait as long as the timeout or our own deadline allows; the callee is
        // told that, less the margin it needs to get its reply back to us in time.
        long wait = Math.min(timeout, remaining);
        return new Send(wait, wait - margin);
    }
}
