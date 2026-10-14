package io.github.ahmedhashim.deadline;

import java.util.OptionalLong;

/**
 * The request header that carries the budget: whole milliseconds the caller is
 * still prepared to wait, measured when it sent the request.
 */
public final class Budget {
    public static final String HEADER = "Deadline-Budget-Ms";
    /** Larger budgets are clamped; nobody waits an hour for an HTTP reply. */
    public static final long MAX_MS = 3_600_000L;

    private Budget() {
    }

    /**
     * @return the budget in ms, empty if the header is absent. Zero or negative
     *         means the caller had already run out when it sent the request.
     * @throws IllegalArgumentException if it is present but not a whole number
     */
    public static OptionalLong parse(String value) {
        if (value == null) {
            return OptionalLong.empty();
        }
        String v = value.strip();
        if (v.isEmpty() || v.length() > 12 || !v.matches("-?[0-9]+")) {
            throw new IllegalArgumentException("bad " + HEADER + ": '" + value + "'");
        }
        long ms = Long.parseLong(v);
        return OptionalLong.of(Math.min(ms, MAX_MS));
    }
}
