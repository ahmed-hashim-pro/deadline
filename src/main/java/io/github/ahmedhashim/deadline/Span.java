package io.github.ahmedhashim.deadline;

/**
 * One piece of a request's life: a hop handling it, or one attempt to call the
 * next hop. Times are milliseconds from when the root of the trace received the
 * request.
 *
 * @param path    hops from the root, joined by '>', e.g. {@code gateway>orders>inventory}
 * @param attempt which attempt of the caller this was (1-based); 0 for the root
 * @param startMs when it started, or when it was decided not to start
 * @param endMs   when it ended
 * @param queueMs time spent waiting for a worker before any work started
 * @param outcome a short token: ok, deadline_exceeded, timeout, not_started, error_503, ...
 * @param detail  free text for people, may be empty
 */
public record Span(String path, int attempt, long startMs, long endMs, long queueMs, String outcome,
                   String detail) {

    public Span {
        if (path.isEmpty()) {
            throw new IllegalArgumentException("empty path");
        }
        detail = detail == null ? "" : detail;
    }

    public int depth() {
        return (int) path.chars().filter(c -> c == '>').count();
    }

    public String hop() {
        return path.substring(path.lastIndexOf('>') + 1);
    }

    Span shifted(long offsetMs, String prefix, int asAttempt) {
        String p = prefix.isEmpty() ? path : prefix + ">" + path;
        int a = attempt == 0 ? asAttempt : attempt;
        return new Span(p, a, startMs + offsetMs, endMs + offsetMs, queueMs, outcome, detail);
    }
}
