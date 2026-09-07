package dev.quasar.engine;

import java.util.Arrays;

/**
 * Rolling tick timings for one region.
 *
 * <p>Only the region's own tick thread writes here, and one region is never ticked concurrently
 * with itself, so the writes need no synchronisation. Readers (the console, the metrics command)
 * race with the writer and may observe a half-updated window — acceptable for a statistic, and far
 * cheaper than locking a struct that is touched twenty times a second per region.
 */
public final class TickMetrics {

    private static final int WINDOW = 100;

    private final long[] durationsNanos = new long[WINDOW];
    private int index;
    private int filled;

    private long lastTickStartNanos;
    private long intervalSumNanos;
    private int intervalCount;

    /** Ticks skipped because the region could not keep up with its 50 ms budget. */
    private long droppedTicks;

    void recordTick(long startNanos, long durationNanos) {
        durationsNanos[index] = durationNanos;
        index = (index + 1) % WINDOW;
        if (filled < WINDOW) {
            filled++;
        }
        if (lastTickStartNanos != 0) {
            intervalSumNanos += startNanos - lastTickStartNanos;
            intervalCount++;
            if (intervalCount >= WINDOW) {
                // Halve the accumulator so the average tracks recent behaviour instead of all history.
                intervalSumNanos /= 2;
                intervalCount /= 2;
            }
        }
        lastTickStartNanos = startNanos;
    }

    void recordDroppedTicks(long count) {
        droppedTicks += count;
    }

    /** Mean milliseconds per tick over the window. */
    public double averageMspt() {
        if (filled == 0) {
            return 0;
        }
        long sum = 0;
        for (int i = 0; i < filled; i++) {
            sum += durationsNanos[i];
        }
        return sum / (double) filled / 1_000_000.0;
    }

    public double p95Mspt() {
        if (filled == 0) {
            return 0;
        }
        long[] copy = Arrays.copyOf(durationsNanos, filled);
        Arrays.sort(copy);
        int rank = Math.min(filled - 1, (int) Math.ceil(filled * 0.95) - 1);
        return copy[Math.max(rank, 0)] / 1_000_000.0;
    }

    public double maxMspt() {
        long max = 0;
        for (int i = 0; i < filled; i++) {
            max = Math.max(max, durationsNanos[i]);
        }
        return max / 1_000_000.0;
    }

    /** Observed ticks per second, derived from real tick start times rather than assumed. */
    public double tps() {
        if (intervalCount == 0) {
            return 20.0;
        }
        double averageIntervalNanos = intervalSumNanos / (double) intervalCount;
        if (averageIntervalNanos <= 0) {
            return 20.0;
        }
        return Math.min(20.0, 1_000_000_000.0 / averageIntervalNanos);
    }

    public long droppedTicks() {
        return droppedTicks;
    }

    public int samples() {
        return filled;
    }
}
