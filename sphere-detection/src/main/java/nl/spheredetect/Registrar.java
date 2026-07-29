package nl.spheredetect;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Aligns the sampling image onto the (slightly larger) reference image.
 *
 * Model: uniform scale + rotation + translation (4 DOF). That covers the three
 * disturbances named in the requirements: the sample may be shifted, zoomed and
 * rotated relative to the reference.
 *
 * Two design choices make this robust for our use case:
 *
 *  1. Both images are LOCALLY CONTRAST NORMALISED first ((I-mean)/(std)) over a
 *     small window. Any local gain/offset change in the illumination therefore
 *     disappears before matching, so a brighter/darker scene does not break the
 *     alignment.
 *
 *  2. The matching cost is a REDESCENDING (Lorentzian) error, not SSD. The ball
 *     itself is present in the sample and absent in the reference, so it is a
 *     block of outliers. With a Lorentzian its influence saturates and the
 *     alignment is driven by the background, exactly what we want.
 *
 * Search strategy: a coarse exhaustive grid over (scale, angle, tx, ty) on a
 * heavily downsampled pyramid level, then the best few hypotheses are refined
 * with a shrinking pattern search down to full resolution. When a previous frame
 * already produced a good transform it can be passed in as a seed, which skips
 * the grid entirely (the normal case for a tripod-mounted phone).
 */
public final class Registrar {

    /** Outlier scale of the Lorentzian, in units of locally normalised intensity. */
    private static final double LORENTZ_SIGMA = 0.55;
    private static final double HUGE_COST = 1e9;

    public static final class Result {
        public SimilarityTransform transform;
        public double cost;        // lower is better; ~0.05-0.35 for a good match
        public double coverage;    // fraction of sampled points that fell inside the reference
        public boolean ok;
        public int evaluations;

        @Override public String toString() {
            return String.format("%s cost=%.4f coverage=%.2f ok=%s", transform, cost, coverage, ok);
        }
    }

    private static final class Level {
        int w, h;
        float[] v;          // locally normalised image
        int[] px, py;       // sample points (sample image only)
        int nPts;
    }

    private final DetectorConfig cfg;
    private final int refW, refH;
    private final Level[] refLevels;
    private final int levels;

    public Registrar(GrayImage reference, DetectorConfig cfg) {
        this.cfg = cfg;
        this.refW = reference.width;
        this.refH = reference.height;
        this.levels = pyramidLevels(Math.min(reference.width, reference.height), cfg);
        this.refLevels = buildPyramid(reference, false);
    }

    private static int pyramidLevels(int minDim, DetectorConfig cfg) {
        int n = 1;
        int d = minDim;
        while (n < cfg.maxPyramidLevels + 1 && d / 2 >= 24) {
            d /= 2;
            n++;
        }
        return n;
    }

    private Level[] buildPyramid(GrayImage img, boolean withPoints) {
        Level[] out = new Level[levels];
        GrayImage cur = img;
        for (int l = 0; l < levels; l++) {
            if (l > 0) cur = ImageOps.downsample2(cur);
            Level lv = new Level();
            lv.w = cur.width;
            lv.h = cur.height;
            int radius = Math.max(2, cfg.normalizeRadius >> l);
            lv.v = ImageOps.localNormalize(cur, radius);
            if (withPoints) {
                // The coarse levels only generate hypotheses, which are then refined at
                // full resolution, so they can afford to sample sparsely.
                int step = (l == 0) ? 3 : (l == 1 ? 2 : (l == levels - 1 ? 2 : 1));
                int margin = radius + 1;
                int cap = ((lv.w) / step + 1) * ((lv.h) / step + 1);
                lv.px = new int[cap];
                lv.py = new int[cap];
                int n = 0;
                for (int y = margin; y < lv.h - margin; y += step) {
                    for (int x = margin; x < lv.w - margin; x += step) {
                        lv.px[n] = x;
                        lv.py[n] = y;
                        n++;
                    }
                }
                lv.nPts = n;
            }
            out[l] = lv;
        }
        return out;
    }

    /**
     * @param seed optional transform from a previous frame; when non-null only a local
     *             refinement is done first, and the full grid search is used as fallback
     *             if that refinement does not reach a good cost.
     */
    public Result register(GrayImage sample, SimilarityTransform seed) {
        Level[] sam = buildPyramid(sample, true);
        int sw = sample.width, sh = sample.height;
        Counter counter = new Counter();

        double[] best = null;
        if (seed != null) {
            // 5 slots: the last one carries the cost back out of patternSearch.
            double[] p = new double[]{seed.scale, seed.angle, seed.tx, seed.ty, 0};
            for (int l = Math.min(levels - 1, 1); l >= 0; l--) {
                patternSearch(sam, l, p, counter);
            }
            double c = cost(sam, 0, p[0], p[1], p[2], p[3], counter);
            if (c <= cfg.maxRegistrationCost * 0.9) {
                best = new double[]{p[0], p[1], p[2], p[3], c};
            }
        }

        if (best == null) {
            best = fullSearch(sam, counter);
        }

        Result r = new Result();
        r.transform = new SimilarityTransform(best[0], best[1], best[2], best[3], sw, sh, refW, refH);
        r.cost = best[4];
        r.coverage = coverage(sam, 0, best[0], best[1], best[2], best[3]);
        r.ok = r.cost <= cfg.maxRegistrationCost && r.coverage >= cfg.minRegistrationCoverage;
        r.evaluations = counter.n;
        return r;
    }

    // ------------------------------------------------------------------ search

    private double[] fullSearch(Level[] sam, Counter counter) {
        int coarse = levels - 1;

        double sMin = 1.0 / cfg.maxZoomFactor, sMax = cfg.maxZoomFactor;
        double aMax = Math.toRadians(cfg.maxRotationDeg);
        double tMax = cfg.maxShiftPx;

        double sStep = Math.max(0.02, (sMax - sMin) / 6.0);
        double aStep = Math.toRadians(Math.max(2.0, cfg.maxRotationDeg / 3.0));
        double tStep = Math.max(4.0, tMax / 4.0);

        List<double[]> hypotheses = new ArrayList<double[]>();
        for (double s = sMin; s <= sMax + 1e-9; s += sStep) {
            for (double a = -aMax; a <= aMax + 1e-9; a += aStep) {
                for (double tx = -tMax; tx <= tMax + 1e-9; tx += tStep) {
                    for (double ty = -tMax; ty <= tMax + 1e-9; ty += tStep) {
                        double c = cost(sam, coarse, s, a, tx, ty, counter);
                        hypotheses.add(new double[]{s, a, tx, ty, c});
                    }
                }
            }
        }
        Collections.sort(hypotheses, new java.util.Comparator<double[]>() {
            @Override public int compare(double[] x, double[] y) { return Double.compare(x[4], y[4]); }
        });

        int keep = Math.min(cfg.hypothesesToRefine, hypotheses.size());
        List<double[]> alive = new ArrayList<double[]>(hypotheses.subList(0, keep));

        for (int l = coarse; l >= 0; l--) {
            for (double[] p : alive) {
                patternSearch(sam, l, p, counter);
                p[4] = cost(sam, l, p[0], p[1], p[2], p[3], counter);
            }
            Collections.sort(alive, new java.util.Comparator<double[]>() {
                @Override public int compare(double[] x, double[] y) { return Double.compare(x[4], y[4]); }
            });
            // prune: at finer (more expensive) levels only the leaders survive
            int survivors = (l >= 2) ? Math.min(alive.size(), 4) : Math.min(alive.size(), 2);
            alive = new ArrayList<double[]>(alive.subList(0, survivors));
        }
        return alive.get(0);
    }

    /**
     * Coordinate pattern search: try +-step on each parameter, accept improvements,
     * shrink the step when a full sweep yields nothing. Derivative free, so it copes
     * with the non-smooth robust cost.
     */
    private void patternSearch(Level[] sam, int level, double[] p, Counter counter) {
        double f = 1 << level;
        double dt = 2.0 * f;
        double da = Math.toRadians(1.5);
        double ds = 0.02;
        double dtMin = (level == 0) ? 0.05 : 0.4 * f;
        double daMin = Math.toRadians(level == 0 ? 0.05 : 0.3);
        double dsMin = (level == 0) ? 0.0008 : 0.004;

        double best = cost(sam, level, p[0], p[1], p[2], p[3], counter);
        int guard = 0;
        while (guard++ < 200) {
            boolean improved = false;
            double[] steps = {ds, da, dt, dt};
            for (int k = 0; k < 4; k++) {
                for (int sign = -1; sign <= 1; sign += 2) {
                    double old = p[k];
                    p[k] = old + sign * steps[k];
                    if (k == 0 && (p[0] < 0.5 || p[0] > 2.0)) { p[0] = old; continue; }
                    double c = cost(sam, level, p[0], p[1], p[2], p[3], counter);
                    if (c < best - 1e-7) {
                        best = c;
                        improved = true;
                        break; // keep the new value, move to next parameter
                    }
                    p[k] = old;
                }
            }
            if (!improved) {
                if (dt <= dtMin && da <= daMin && ds <= dsMin) break;
                dt = Math.max(dtMin, dt * 0.5);
                da = Math.max(daMin, da * 0.5);
                ds = Math.max(dsMin, ds * 0.5);
            }
        }
        p[4] = best;
    }

    // -------------------------------------------------------------------- cost

    private double cost(Level[] sam, int level, double s, double a, double tx, double ty, Counter counter) {
        counter.n++;
        Level S = sam[level];
        Level R = refLevels[level];
        double f = 1 << level;
        double csx = (S.w - 1) / 2.0, csy = (S.h - 1) / 2.0;
        double crx = (R.w - 1) / 2.0, cry = (R.h - 1) / 2.0;
        double c = s * Math.cos(a), sn = s * Math.sin(a);
        double txl = tx / f, tyl = ty / f;
        double sig2 = LORENTZ_SIGMA * LORENTZ_SIGMA;

        double sum = 0;
        int used = 0;
        for (int i = 0; i < S.nPts; i++) {
            int x = S.px[i], y = S.py[i];
            double dx = x - csx, dy = y - csy;
            double rx = crx + c * dx + (-sn) * dy + txl;
            double ry = cry + sn * dx + c * dy + tyl;
            if (rx < 1 || ry < 1 || rx > R.w - 2 || ry > R.h - 2) continue;
            float rv = ImageOps.bilinear(R.v, R.w, R.h, rx, ry);
            double d = S.v[y * S.w + x] - rv;
            sum += Math.log1p(d * d / sig2);
            used++;
        }
        if (used < 0.5 * S.nPts) return HUGE_COST;
        double cov = used / (double) S.nPts;
        return sum / used + 0.6 * (1.0 - cov);
    }

    private double coverage(Level[] sam, int level, double s, double a, double tx, double ty) {
        Level S = sam[level];
        Level R = refLevels[level];
        double f = 1 << level;
        double csx = (S.w - 1) / 2.0, csy = (S.h - 1) / 2.0;
        double crx = (R.w - 1) / 2.0, cry = (R.h - 1) / 2.0;
        double c = s * Math.cos(a), sn = s * Math.sin(a);
        int used = 0;
        for (int i = 0; i < S.nPts; i++) {
            double dx = S.px[i] - csx, dy = S.py[i] - csy;
            double rx = crx + c * dx - sn * dy + tx / f;
            double ry = cry + sn * dx + c * dy + ty / f;
            if (rx >= 1 && ry >= 1 && rx <= R.w - 2 && ry <= R.h - 2) used++;
        }
        return S.nPts == 0 ? 0 : used / (double) S.nPts;
    }

    private static final class Counter { int n; }
}
