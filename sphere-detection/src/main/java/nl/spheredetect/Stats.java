package nl.spheredetect;

import java.util.Arrays;

/** Robust statistics helpers. */
public final class Stats {

    private Stats() {}

    /** Median of the first n entries. The array is sorted in place. */
    public static double medianInPlace(double[] a, int n) {
        if (n <= 0) return 0;
        Arrays.sort(a, 0, n);
        return (n % 2 == 1) ? a[n / 2] : 0.5 * (a[n / 2 - 1] + a[n / 2]);
    }

    /** Percentile in [0,1] of the first n entries. The array is sorted in place. */
    public static double percentileInPlace(double[] a, int n, double p) {
        if (n <= 0) return 0;
        Arrays.sort(a, 0, n);
        double idx = p * (n - 1);
        int lo = (int) Math.floor(idx);
        int hi = (int) Math.ceil(idx);
        if (lo < 0) lo = 0;
        if (hi > n - 1) hi = n - 1;
        double f = idx - lo;
        return a[lo] + (a[hi] - a[lo]) * f;
    }

    /** Median absolute deviation, scaled to be a consistent estimator of sigma for gaussian data. */
    public static double sigmaFromMad(double[] values, int n) {
        if (n <= 1) return 0;
        double[] tmp = Arrays.copyOf(values, n);
        double med = medianInPlace(tmp, n);
        for (int i = 0; i < n; i++) tmp[i] = Math.abs(values[i] - med);
        double mad = medianInPlace(tmp, n);
        return 1.4826 * mad;
    }

    public static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** Smooth 0..1 ramp: 0 below 'a', 1 above 'b', smoothstep in between. */
    public static double smooth01(double x, double a, double b) {
        if (b <= a) return x >= b ? 1 : 0;
        double t = clamp((x - a) / (b - a), 0, 1);
        return t * t * (3 - 2 * t);
    }
}
