package io.github.ahmedhashim.deadline;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Where a request's budget went, hop by hop. Each hop returns its trace in a
 * response header; the caller shifts it onto its own timeline and adds it to
 * its own, so the root ends up with the whole tree without any clock
 * synchronisation between hosts. Thread-safe.
 */
public final class Trace {
    /** Header carrying the encoded trace on responses. */
    public static final String HEADER = "Deadline-Trace";
    /** Beyond this many spans the rest are counted, not kept, so a header stays small. */
    public static final int MAX_SPANS = 64;

    private final List<Span> spans = new ArrayList<>();
    private int dropped;

    public synchronized void add(Span s) {
        if (spans.size() < MAX_SPANS) {
            spans.add(s);
        } else {
            dropped++;
        }
    }

    /**
     * Adds a callee's trace. Its times are relative to when the callee received
     * the request, which on our clock is {@code offsetMs}.
     */
    public synchronized void merge(Trace child, long offsetMs, String prefix, int attempt) {
        for (Span s : child.spans()) {
            add(s.shifted(offsetMs, prefix, attempt));
        }
        dropped += child.dropped();
    }

    public synchronized List<Span> spans() {
        return List.copyOf(spans);
    }

    public synchronized int dropped() {
        return dropped;
    }

    public String encode() {
        StringBuilder b = new StringBuilder();
        for (Span s : spans()) {
            if (!b.isEmpty()) {
                b.append(',');
            }
            b.append(enc(s.path())).append(';').append(s.attempt()).append(';').append(s.startMs()).append(';')
                    .append(s.endMs()).append(';').append(s.queueMs()).append(';').append(enc(s.outcome()))
                    .append(';').append(enc(s.detail()));
        }
        int d = dropped();
        if (d > 0) {
            b.append(b.isEmpty() ? "" : ",").append("+").append(d);
        }
        return b.toString();
    }

    /** Parses a header value. Malformed input gives an empty trace, never an exception. */
    public static Trace decode(String header) {
        Trace t = new Trace();
        if (header == null || header.isBlank()) {
            return t;
        }
        try {
            for (String item : header.split(",")) {
                if (item.startsWith("+")) {
                    t.dropped += Integer.parseInt(item.substring(1));
                    continue;
                }
                String[] f = item.split(";", -1);
                if (f.length != 7) {
                    return new Trace();
                }
                t.add(new Span(dec(f[0]), Integer.parseInt(f[1]), Long.parseLong(f[2]), Long.parseLong(f[3]),
                        Long.parseLong(f[4]), dec(f[5]), dec(f[6])));
            }
        } catch (IllegalArgumentException e) {
            return new Trace();
        }
        return t;
    }

    /** A table for people: one row per span, in tree order. */
    public String render(long budgetMs) {
        List<Span> rows = treeOrder(spans());
        StringBuilder b = new StringBuilder();
        b.append(String.format("%-28s %3s %7s %7s %6s  %s%n", "hop", "try", "start", "end", "queue", "outcome"));
        for (Span s : rows) {
            String name = "  ".repeat(s.depth()) + s.hop();
            String tri = s.attempt() == 0 ? "-" : Integer.toString(s.attempt());
            boolean started = !s.outcome().equals("not_started");
            b.append(String.format("%-28s %3s %7s %7s %6s  %s%s%n", name, tri, s.startMs(),
                    started ? Long.toString(s.endMs()) : "-", started ? Long.toString(s.queueMs()) : "-",
                    s.outcome(), s.detail().isEmpty() ? "" : " (" + s.detail() + ")"));
        }
        if (dropped() > 0) {
            b.append(dropped()).append(" more spans not kept\n");
        }
        if (budgetMs >= 0) {
            b.append("budget ").append(budgetMs).append(" ms\n");
        }
        return b.toString();
    }

    /** Parents before children; siblings by start time. */
    static List<Span> treeOrder(List<Span> all) {
        List<Span> out = new ArrayList<>();
        List<Span> roots = all.stream().filter(s -> s.depth() == 0).sorted(Comparator.comparingLong(Span::startMs))
                .toList();
        for (Span r : roots) {
            walk(r, all, out);
        }
        // Anything whose parent span is missing still gets shown.
        for (Span s : all) {
            if (!out.contains(s)) {
                out.add(s);
            }
        }
        return out;
    }

    private static void walk(Span parent, List<Span> all, List<Span> out) {
        out.add(parent);
        all.stream()
                .filter(s -> s.depth() == parent.depth() + 1 && s.path().startsWith(parent.path() + ">")
                        && s.startMs() >= parent.startMs() && s.startMs() <= parent.endMs())
                .sorted(Comparator.comparingLong(Span::startMs).thenComparingInt(Span::attempt))
                .forEach(c -> {
                    if (!out.contains(c)) {
                        walk(c, all, out);
                    }
                });
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String dec(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }
}
