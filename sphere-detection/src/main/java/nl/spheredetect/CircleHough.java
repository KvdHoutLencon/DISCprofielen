package nl.spheredetect;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Gradient-direction circle voting over the full (centreX, centreY, radius) space,
 * restricted to the radius window that calibration established.
 *
 * Every edge pixel votes along its own gradient normal at every radius in the window.
 * Both directions are tried (so it does not matter whether the object is brighter or
 * darker than its surroundings) but they go into SEPARATE accumulators. That separation
 * is essential: a partially lit sphere has two edges, the silhouette and the shadow
 * terminator, and they run in opposite directions. Mixed into one accumulator the
 * terminator's votes combine with the silhouette's into a false circle of the wrong
 * radius; kept apart, the silhouette builds a clean peak in one accumulator and the
 * terminator is confined to the other, where its non-circular shape scores badly.
 *
 * Why the full 3D accumulator and not the cheaper "sum over radius" projection: with a
 * back-lit sphere only an ARC of the contour is visible, and a short arc can be fitted
 * by many different (centre, radius) combinations. Keeping radius as a separate
 * dimension resolves that: at the true radius the arc's votes pile up in one cell, at a
 * wrong radius the very same votes smear out along a curve.
 *
 * Votes are divided by the radius they vote for. Without that the larger radii win by
 * default: a bigger circle has a proportionally longer contour, and it also draws votes
 * from a proportionally larger neighbourhood of unrelated structure. Both effects scale
 * with r, so dividing by r leaves the comparison between radii to concentration alone,
 * which is what actually indicates a circle.
 *
 * This is also where the "expected size within a margin" input pays off: it bounds the
 * accumulator and removes most of the ambiguity that makes partial contours hard.
 */
public final class CircleHough {

    public static final class Peak {
        public double cx, cy, r;
        /** Accumulated vote weight: how much contour evidence sits on this circle. */
        public double votes;
        /**
         * Sign of the radial intensity derivative this hypothesis expects on its contour:
         * -1 when the object is brighter than what surrounds it, +1 when it is darker.
         * The contour tracer uses it so that one hypothesis never mixes both kinds of edge.
         */
        public int stepSign;

        @Override public String toString() {
            return String.format(java.util.Locale.US, "c=(%.1f,%.1f) r=%.2f votes=%.1f step=%+d",
                    cx, cy, r, votes, stepSign);
        }
    }

    private CircleHough() {}

    /** Radius resolution of the accumulator, in pixels. */
    private static final double R_STEP = 0.5;
    private static final int MAX_R_PLANES = 72;

    /**
     * @param grad          gradient field of the (smoothed) residual image
     * @param edge          per-pixel gate: only these pixels may vote
     * @param x0,y0,x1,y1   inclusive ROI in which centres are searched
     * @param rMin,rMax     calibrated radius window
     * @param edgeThreshold minimum gradient magnitude for an edge pixel
     * @param topK          how many peaks to return
     */
    public static List<Peak> findPeaks(GradField grad, boolean[] edge,
                                       int x0, int y0, int x1, int y1,
                                       double rMin, double rMax,
                                       double edgeThreshold, int topK) {
        int w = grad.width, h = grad.height;
        x0 = Math.max(1, x0); y0 = Math.max(1, y0);
        x1 = Math.min(w - 2, x1); y1 = Math.min(h - 2, y1);
        int aw = x1 - x0 + 1, ah = y1 - y0 + 1;
        List<Peak> peaks = new ArrayList<Peak>();
        if (aw < 3 || ah < 3 || rMax < rMin) return peaks;

        int nr = (int) Stats.clamp(Math.ceil((rMax - rMin) / R_STEP) + 1, 1, MAX_R_PLANES);
        double rStep = (nr > 1) ? (rMax - rMin) / (nr - 1) : 0;

        double magCap = Math.max(1.0, 8.0 * edgeThreshold);

        // Edge pixels up to rMax outside the ROI can still vote for a centre inside it.
        int vx0 = (int) Math.max(1, x0 - rMax - 1), vy0 = (int) Math.max(1, y0 - rMax - 1);
        int vx1 = (int) Math.min(w - 2, x1 + rMax + 1), vy1 = (int) Math.min(h - 2, y1 + rMax + 1);

        // bank 0: centre at p + r*n (gradient points inward -> object brighter, step -1)
        // bank 1: centre at p - r*n (gradient points outward -> object darker, step +1)
        float[][][] banks = new float[2][nr][aw * ah];
        for (int y = vy0; y <= vy1; y++) {
            for (int x = vx0; x <= vx1; x++) {
                int i = y * w + x;
                if (!edge[i]) continue;
                double m = grad.mag[i];
                if (m < edgeThreshold) continue;
                double weight = Math.min(m, magCap);
                double nx = grad.gx[i] / m, ny = grad.gy[i] / m;
                for (int k = 0; k < nr; k++) {
                    double r = rMin + k * rStep;
                    double wr = weight / r;
                    vote(banks[0][k], aw, ah, x0, y0, x + nx * r, y + ny * r, wr);
                    vote(banks[1][k], aw, ah, x0, y0, x - nx * r, y - ny * r, wr);
                }
            }
        }

        // A one pixel blur per plane absorbs the discretisation of the vote positions.
        double max = 0;
        for (int bank = 0; bank < 2; bank++) {
            for (int k = 0; k < nr; k++) {
                banks[bank][k] = ImageOps.blur121(new GrayImage(aw, ah, banks[bank][k])).data;
                for (float v : banks[bank][k]) if (v > max) max = v;
            }
        }
        if (max <= 0) return peaks;

        // 3D local maxima, per bank
        List<double[]> cand = new ArrayList<double[]>();
        double floor = 0.35 * max;
        for (int bank = 0; bank < 2; bank++) {
        float[][] acc = banks[bank];
        int stepSign = (bank == 0) ? -1 : 1;
        for (int k = 0; k < nr; k++) {
            for (int y = 1; y < ah - 1; y++) {
                for (int x = 1; x < aw - 1; x++) {
                    float v = acc[k][y * aw + x];
                    if (v < floor) continue;
                    boolean best = true;
                    for (int dk = -1; dk <= 1 && best; dk++) {
                        int kk = k + dk;
                        if (kk < 0 || kk >= nr) continue;
                        for (int dy = -1; dy <= 1 && best; dy++) {
                            for (int dx = -1; dx <= 1; dx++) {
                                if (dk == 0 && dx == 0 && dy == 0) continue;
                                if (acc[kk][(y + dy) * aw + x + dx] > v) { best = false; break; }
                            }
                        }
                    }
                    if (!best) continue;
                    // sub-bin radius by parabolic interpolation across planes
                    double dr = 0;
                    if (k > 0 && k < nr - 1) {
                        double a = acc[k - 1][y * aw + x], b = v, c = acc[k + 1][y * aw + x];
                        double den = a - 2 * b + c;
                        if (Math.abs(den) > 1e-9) dr = Stats.clamp(0.5 * (a - c) / den, -1, 1);
                    }
                    cand.add(new double[]{x + x0, y + y0, rMin + (k + dr) * rStep, v, stepSign});
                }
            }
        }
        }
        Collections.sort(cand, new java.util.Comparator<double[]>() {
            @Override public int compare(double[] a, double[] b) { return Double.compare(b[3], a[3]); }
        });

        double minSep = Math.max(1.5, 0.5 * rMin);
        for (double[] c : cand) {
            if (peaks.size() >= topK) break;
            boolean dup = false;
            for (Peak p : peaks) {
                double dx = p.cx - c[0], dy = p.cy - c[1];
                if (p.stepSign == (int) c[4] && Math.sqrt(dx * dx + dy * dy) < minSep
                        && Math.abs(p.r - c[2]) < 0.25 * p.r) {
                    dup = true;
                    break;
                }
            }
            if (dup) continue;
            Peak p = new Peak();
            p.cx = c[0];
            p.cy = c[1];
            p.r = c[2];
            p.votes = c[3];
            p.stepSign = (int) c[4];
            peaks.add(p);
        }
        return peaks;
    }

    private static void vote(float[] acc, int aw, int ah, int x0, int y0,
                             double cx, double cy, double weight) {
        int ix = (int) Math.round(cx) - x0;
        int iy = (int) Math.round(cy) - y0;
        if (ix < 0 || iy < 0 || ix >= aw || iy >= ah) return;
        acc[iy * aw + ix] += (float) weight;
    }
}
