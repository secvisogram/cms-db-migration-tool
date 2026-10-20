package de.bsi.secvisogram.csafmigrator;

import java.io.PrintStream;
import java.util.Locale;
import java.util.function.LongSupplier;

/**
 * Prints a progress line now and then during a long step, so a user watching the run can see that it
 * is working, how far it is and roughly how long it will take: documents done, the share of the
 * total, the rate so far and an estimate of the time left.
 *
 * <p>Nothing is printed more often than the interval, however often {@link #advance} is called, and
 * nothing at all when progress output is switched off ({@code --quiet}). The clock starts with the
 * first {@link #advance}, not when the object is created, so work done before the first document (a
 * preparatory query, say) does not distort the rate and the estimate.</p>
 */
final class Progress {

    private static final long DEFAULT_INTERVAL_NANOS = 15_000_000_000L;

    private final String label;
    private final long total;
    private final boolean enabled;
    private final long intervalNanos;
    private final PrintStream out;
    private final LongSupplier nanoClock;
    private boolean started;
    private long startedAt;
    private long lastPrintedAt;
    /** What was done by the first advance, which is when the clock started and so not part of the rate. */
    private long doneAtStart;
    private long done;

    /** @param total the number of documents expected, or 0 if not known (then no percentage is shown) */
    Progress(String label, long total, boolean enabled) {
        this(label, total, enabled, DEFAULT_INTERVAL_NANOS, System.out, System::nanoTime);
    }

    Progress(String label, long total, boolean enabled, long intervalNanos, PrintStream out, LongSupplier nanoClock) {
        this.label = label;
        this.total = total;
        this.enabled = enabled;
        this.intervalNanos = intervalNanos;
        this.out = out;
        this.nanoClock = nanoClock;
    }

    void advance(long count) {
        done += count;
        if (!enabled) {
            return;
        }
        long now = nanoClock.getAsLong();
        if (!started) {
            started = true;
            startedAt = now;
            lastPrintedAt = now;
            doneAtStart = done;
            return;
        }
        if (now - lastPrintedAt >= intervalNanos) {
            lastPrintedAt = now;
            out.println(line(now));
        }
    }

    private String line(long now) {
        double seconds = (now - startedAt) / 1e9;
        double rate = seconds > 0 ? (done - doneAtStart) / seconds : 0;
        StringBuilder line = new StringBuilder("  ").append(label).append(": ").append(count(done));
        if (total > 0) {
            line.append(" of ").append(count(total))
                    .append(String.format(Locale.ROOT, " (%.1f%%)", 100.0 * done / total));
        }
        line.append(", ").append(rate >= 10 ? String.format(Locale.ROOT, "%,.0f", rate)
                : String.format(Locale.ROOT, "%.1f", rate)).append("/s");
        if (total > done && rate > 0) {
            line.append(", about ").append(duration((total - done) / rate)).append(" left");
        }
        return line.toString();
    }

    /** A number with thousands separators, the same in every locale. */
    static String count(long number) {
        return String.format(Locale.ROOT, "%,d", number);
    }

    /** "45 s", "1 min 30 s" below ten minutes, "25 min" below an hour, then "1 h 5 min". */
    static String duration(double seconds) {
        long rounded = Math.round(seconds);
        if (rounded < 60) {
            return rounded + " s";
        }
        if (rounded < 600) {
            long minutes = rounded / 60;
            long rest = rounded % 60;
            return rest == 0 ? minutes + " min" : minutes + " min " + rest + " s";
        }
        long minutes = Math.round(seconds / 60);
        if (minutes < 60) {
            return minutes + " min";
        }
        return (minutes / 60) + " h " + (minutes % 60) + " min";
    }
}
