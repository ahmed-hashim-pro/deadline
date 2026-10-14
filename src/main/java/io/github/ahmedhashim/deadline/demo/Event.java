package io.github.ahmedhashim.deadline.demo;

/**
 * Something a demo service did, with wall-clock times so events from separate
 * processes on one host line up. Services print these on stdout, one per line.
 *
 * @param kind "work" (holding a worker), "call" (one attempt to call downstream)
 *             or "done" (the hop finished handling a request)
 */
public record Event(String kind, String svc, String to, String req, int attempt, long t0, long t1, String out) {
    static final String PREFIX = "EV\t";

    public String line() {
        return PREFIX + String.join("\t", kind, svc, to, req, Integer.toString(attempt), Long.toString(t0),
                Long.toString(t1), out);
    }

    public static Event parse(String line) {
        if (!line.startsWith(PREFIX)) {
            return null;
        }
        String[] f = line.substring(PREFIX.length()).split("\t", -1);
        if (f.length != 8) {
            return null;
        }
        return new Event(f[0], f[1], f[2], f[3], Integer.parseInt(f[4]), Long.parseLong(f[5]), Long.parseLong(f[6]),
                f[7]);
    }

    public long ms() {
        return t1 - t0;
    }
}
