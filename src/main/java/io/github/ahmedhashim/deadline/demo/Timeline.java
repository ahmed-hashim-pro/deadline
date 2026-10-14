package io.github.ahmedhashim.deadline.demo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Draws one request's life in both modes: a lane per service, a bar per time a
 * service handled the request, red where nobody was waiting for the answer any
 * more.
 */
public final class Timeline {
    private static final String[] HOPS = {"gateway", "orders", "inventory"};
    private static final String INK = "#24292f";
    private static final String MUTED = "#57606a";
    private static final String GRID = "#d8dee4";
    private static final String WAITING = "#2a78d6";
    private static final String WASTED = "#cf222e";
    private static final String FONT = "-apple-system, 'Segoe UI', Helvetica, Arial, sans-serif";

    private Timeline() {
    }

    public static String svg(Cluster.Topology t, Main.Run naive, Main.Run dl) {
        double spanS = Math.ceil(Math.max(naive.stats().busyUntilMs(), dl.stats().busyUntilMs()) / 1000.0);
        long spanMs = (long) (Math.max(spanS, 3) * 1000);
        double left = 96;
        double width = 1000;
        double plotW = width - left - 30;
        StringBuilder b = new StringBuilder();
        List<String> body = new ArrayList<>();
        double y = 78;
        y = panel(body, "Without deadlines: every hop's timeouts and retries are its own", naive, spanMs, left, plotW, y);
        y = panel(body, "With deadlines: each hop is told what's left of the user's " + t.userWaitMs() / 1000
                + " s", dl, spanMs, left, plotW, y + 26);
        double height = y + 76;
        f(b, "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"%.0f\" height=\"%.0f\" viewBox=\"0 0 %.0f %.0f\" font-family=\"%s\">%n",
                width, height, width, height, FONT);
        f(b, "<rect width=\"100%%\" height=\"100%%\" fill=\"#ffffff\"/>%n");
        f(b, "<text x=\"24\" y=\"30\" font-size=\"17\" font-weight=\"600\" fill=\"%s\">One slow request, user → gateway → orders → inventory</text>%n", INK);
        f(b, "<text x=\"24\" y=\"50\" font-size=\"12.5\" fill=\"%s\">Same services, same timeouts and retries. Inventory takes %.1f s; orders waits %.1f s for it, three times; gateway waits %.1f s for orders, three times.</text>%n",
                MUTED, t.inventoryWorkMs() / 1000.0, t.ordersTimeoutMs() / 1000.0, t.gatewayTimeoutMs() / 1000.0);
        body.forEach(b::append);
        double ly = y + 26;
        f(b, "<rect x=\"%.1f\" y=\"%.1f\" width=\"22\" height=\"12\" fill=\"%s\"/>%n", left, ly - 10, WAITING);
        f(b, "<text x=\"%.1f\" y=\"%.1f\" font-size=\"12\" fill=\"%s\">working while the user is waiting</text>%n", left + 30, ly, MUTED);
        f(b, "<rect x=\"%.1f\" y=\"%.1f\" width=\"22\" height=\"12\" fill=\"%s\"/>%n", left + 260, ly - 10, WASTED);
        f(b, "<text x=\"%.1f\" y=\"%.1f\" font-size=\"12\" fill=\"%s\">working after the user stopped waiting</text>%n", left + 290, ly, MUTED);
        f(b, "<text x=\"%.1f\" y=\"%.1f\" font-size=\"12\" fill=\"%s\">One bar each time a service handled the request, drawn from the services' own logs.</text>%n", left, ly + 22, MUTED);
        b.append("</svg>\n");
        return b.toString();
    }

    private static double panel(List<String> out, String title, Main.Run run, long spanMs, double left, double plotW,
                                double top) {
        long t0 = run.user().sentEpochMs();
        long stop = run.user().stoppedEpochMs() - t0;
        StringBuilder b = new StringBuilder();
        f(b, "<text x=\"24\" y=\"%.1f\" font-size=\"13.5\" font-weight=\"600\" fill=\"%s\">%s</text>%n", top, INK, esc(title));
        double y = top + 30;
        double lane = 14;
        double gap = 5;
        double laneTop = y;
        List<double[]> lanes = new ArrayList<>();
        for (String hop : HOPS) {
            List<Event> evs = run.events().stream().filter(e -> e.kind().equals("done") && e.svc().equals(hop))
                    .sorted(Comparator.comparingLong(Event::t0)).toList();
            List<Long> rowEnds = new ArrayList<>();
            List<int[]> placed = new ArrayList<>();
            for (Event e : evs) {
                int row = 0;
                while (row < rowEnds.size() && rowEnds.get(row) > e.t0()) {
                    row++;
                }
                if (row == rowEnds.size()) {
                    rowEnds.add(e.t1());
                } else {
                    rowEnds.set(row, e.t1());
                }
                placed.add(new int[] {row});
            }
            int rows = Math.max(1, rowEnds.size());
            double h = rows * (lane + gap) - gap;
            lanes.add(new double[] {y, h});
            f(b, "<text x=\"%.1f\" y=\"%.1f\" font-size=\"12.5\" font-weight=\"600\" text-anchor=\"end\" fill=\"%s\">%s</text>%n",
                    left - 10, y + h / 2 + 4, INK, hop);
            for (int i = 0; i < evs.size(); i++) {
                Event e = evs.get(i);
                double ry = y + placed.get(i)[0] * (lane + gap);
                long a = e.t0() - t0;
                long z = e.t1() - t0;
                long split = Math.max(a, Math.min(z, stop));
                if (split > a) {
                    bar(b, a, split, ry, lane, WAITING, spanMs, left, plotW);
                }
                if (z > split) {
                    bar(b, split, z, ry, lane, WASTED, spanMs, left, plotW);
                }
            }
            y += h + 12;
        }
        double bottom = y - 12;
        // Time grid behind: insert before bars by prefixing.
        StringBuilder grid = new StringBuilder();
        for (long s = 0; s <= spanMs; s += 1000) {
            double x = left + s * plotW / spanMs;
            f(grid, "<line x1=\"%.1f\" y1=\"%.1f\" x2=\"%.1f\" y2=\"%.1f\" stroke=\"%s\"/>%n", x, laneTop - 6, x, bottom + 4, GRID);
            f(grid, "<text x=\"%.1f\" y=\"%.1f\" font-size=\"11\" text-anchor=\"middle\" fill=\"%s\">%d s</text>%n", x, bottom + 18, MUTED, s / 1000);
        }
        double sx = left + stop * plotW / spanMs;
        f(grid, "<rect x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" fill=\"#f6f8fa\"/>%n", sx, laneTop - 6, Math.max(0, left + plotW - sx), bottom - laneTop + 10);
        String what = run.user().ok() ? "user got the answer" : run.user().outcome().equals("gave up") ? "user gave up"
                : "user got " + run.user().outcome().replace("error ", "");
        StringBuilder mark = new StringBuilder();
        f(mark, "<line x1=\"%.1f\" y1=\"%.1f\" x2=\"%.1f\" y2=\"%.1f\" stroke=\"%s\" stroke-width=\"1.5\" stroke-dasharray=\"5 3\"/>%n", sx, laneTop - 16, sx, bottom + 4, INK);
        f(mark, "<text x=\"%.1f\" y=\"%.1f\" font-size=\"11.5\" fill=\"%s\">%s at %s s</text>%n", sx + 5, laneTop - 9, INK, esc(what), num(stop / 1000.0));
        long busy = run.stats().busyUntilMs();
        f(mark, "<text x=\"%.1f\" y=\"%.1f\" font-size=\"11.5\" text-anchor=\"end\" fill=\"%s\">%s</text>%n",
                left + plotW, bottom + 34, run.stats().workAfterMs() > 0 ? WASTED : MUTED,
                esc(String.format(Locale.ROOT, "services busy until %.2f s · %.1f s of worker time after the user stopped waiting",
                        busy / 1000.0, run.stats().workAfterMs() / 1000.0)));
        out.add(grid.toString());
        out.add(b.toString());
        out.add(mark.toString());
        return bottom + 40;
    }

    private static void bar(StringBuilder b, long a, long z, double y, double h, String fill, long spanMs, double left,
                            double plotW) {
        double x0 = left + a * plotW / spanMs;
        double x1 = left + z * plotW / spanMs;
        f(b, "<rect x=\"%.1f\" y=\"%.1f\" width=\"%.2f\" height=\"%.1f\" fill=\"%s\"/>%n", x0, y, Math.max(1, x1 - x0), h, fill);
    }

    private static String num(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    private static void f(StringBuilder b, String fmt, Object... args) {
        b.append(String.format(Locale.ROOT, fmt, args));
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
