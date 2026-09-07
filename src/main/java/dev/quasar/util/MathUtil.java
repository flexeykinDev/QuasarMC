package dev.quasar.util;

/** Small numeric helpers used across the chunk/region code. */
public final class MathUtil {

    private MathUtil() {}

    /** Number of bits needed to represent values in {@code [0, size)}, minimum 1. */
    public static int ceilLog2(int size) {
        if (size <= 1) {
            return 1;
        }
        return 32 - Integer.numberOfLeadingZeros(size - 1);
    }

    public static int floorDiv16(int v) {
        return v >> 4;
    }

    public static int clamp(int v, int min, int max) {
        return v < min ? min : Math.min(v, max);
    }

    public static double clamp(double v, double min, double max) {
        return v < min ? min : Math.min(v, max);
    }

    /** Chebyshev (chessboard) distance — the metric the region graph links chunks with. */
    public static int chebyshev(int x1, int z1, int x2, int z2) {
        return Math.max(Math.abs(x1 - x2), Math.abs(z1 - z2));
    }
}
