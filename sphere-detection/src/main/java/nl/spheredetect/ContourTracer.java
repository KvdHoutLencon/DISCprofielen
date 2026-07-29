package nl.spheredetect;

/**
 * Traces the outline of a candidate object and fits a circle to it.
 *
 * A fan of rays is cast from the current centre estimate. Along each ray every local
 * maximum of the radial intensity derivative is collected - each one is a candidate
 * edge crossing, located sub-pixel by a parabola through its three samples. Sub-pixel
 * localisation matters: taking the outermost point that merely exceeds a threshold sits
 * a blur-width too far out by an amount that varies with the local edge strength, which
 * smears away exactly the radius differences that separate a circle from a square.
 *
 * The radius is then chosen by SUPPORT: for every candidate radius, count how many rays
 * have an edge crossing there, and take the best supported one. A round contour
 * concentrates the rays at a single radius; an angular outline, a shadow terminator (an
 * ellipse, not a circle) or random structure spreads its crossings over many radii and
 * cannot win. That is what makes the awkward cases work:
 *  - a fully lit sphere whose limb blends into the background over part of its
 *    circumference: the rays that miss the limb report interior structure instead, but
 *    they scatter and cannot outvote the part of the contour that is visible;
 *  - a back-lit sphere showing only a crescent: the circular arc of its silhouette
 *    outvotes the elliptical terminator just inside it.
 *
 * Note that a strongly shaded sphere has a second, concentric circular feature: its own
 * brightness gradient steepens towards the limb and forms a ridge slightly inside the
 * silhouette. Either feature is a valid round contour and the detector is content with
 * whichever is stronger - but it must be the SAME one every time, which is why the
 * radius is chosen by maximum support and not by preferring the outermost. Calibration
 * measures the same feature, so the learned radius window stays consistent with it. The
 * reported radius may therefore sit a little inside the geometric silhouette of a
 * strongly shaded ball.
 *
 * Edges are accepted whatever their SIGN is. A crescent runs from brighter than the
 * background to darker than it along its own length, so the silhouette step genuinely
 * changes sign partway round; insisting on one sign would discard part of a perfectly
 * circular contour.
 *
 * Finally the selected contour points are fitted with a robust circle fit, the fan is
 * re-cast from the improved centre, and so on. Two or three passes pull a starting centre
 * that is several pixels off right onto the object.
 */
public final class ContourTracer {

    public static final class Trace {
        public double cx, cy, r;
        public int bins;
        /** Radius of the contour crossing per angular bin (0 when the ray found none). */
        public double[] rEdge;
        /** Signed radial derivative of the contour step per bin. */
        public double[] vEdge;
        /** |radial derivative| / |gradient| per bin: 1 = gradient exactly radial. */
        public double[] orient;
        public boolean[] have;
        /** Dominant sign of the contour step: +1 object darker, -1 object brighter. */
        public int polarity;
        /** Number of rays that contributed a contour point. */
        public int contourPoints;
        /** Number of rays that saw any edge at all inside the search window. */
        public int raysWithEdge;
        /**
         * How much the chosen radius stands out against the best competing radius.
         * A circle concentrates its contour at one radius and scores high; an angular or
         * irregular outline spreads its edges over many radii and scores near 1.
         */
        public double radiusDominance;
        public boolean fitOk;
    }

    private ContourTracer() {}

    private static final double RAY_STEP = 0.25;
    /** Per ray only the strongest few crossings are kept; the rest is interior clutter. */
    private static final int MAX_EDGES_PER_RAY = 4;

    /**
     * Traces one circle hypothesis, starting from the given centre and radius.
     *
     * @param rGuess     starting radius; pass &lt;=0 to use the middle of the search window
     * @param rSearchMin lower end of the plausible radius window (calibrated)
     * @param rSearchMax upper end of the plausible radius window (calibrated)
     */
    public static Trace trace(ResidualMap rm, double cx0, double cy0, double rGuess,
                              double rSearchMin, double rSearchMax, double edgeThreshold) {
        double r0 = (rGuess > 0) ? rGuess : 0.5 * (rSearchMin + rSearchMax);
        return refine(rm, cx0, cy0, r0, rSearchMin, rSearchMax, edgeThreshold, true);
    }

    /**
     * Traces every plausible circle around this centre, one per well supported radius.
     *
     * Returning several is not hedging. A shaded sphere really does present two
     * concentric circular contours - its silhouette, and the ridge where its own
     * brightness falls off most steeply just inside it - and which of the two collects
     * more support flips with the background and the lighting. Committing to one would
     * make the measured radius jump by 15..20% between frames, which would eat most of
     * the size margin the user specified. So both are offered as hypotheses and the
     * caller decides: at calibration time by which radius matches the size the user
     * marked, at detection time by accepting whichever hypothesis passes the checks.
     *
     * @param maxCandidates upper bound on the number of hypotheses returned (2 is plenty)
     */
    public static java.util.List<Trace> traceCandidates(ResidualMap rm, double cx0, double cy0,
                                                        double rSearchMin, double rSearchMax,
                                                        double edgeThreshold, int maxCandidates) {
        java.util.List<Trace> out = new java.util.ArrayList<Trace>();
        double lo = Math.max(1.5, 0.80 * rSearchMin);
        double hi = 1.20 * rSearchMax;
        Fan wide = castFan(rm, cx0, cy0, 0.5 * (rSearchMin + rSearchMax), lo, hi, edgeThreshold, true);
        if (wide.support == null || wide.support.length < 3) return out;

        double[] sm = wide.support;
        double max = 0;
        for (double v : sm) if (v > max) max = v;
        if (max <= 0) return out;

        java.util.List<double[]> peaks = new java.util.ArrayList<double[]>();
        for (int k = 1; k < sm.length - 1; k++) {
            if (sm[k] < 0.45 * max || sm[k] < sm[k - 1] || sm[k] < sm[k + 1]) continue;
            double den = sm[k - 1] - 2 * sm[k] + sm[k + 1];
            double delta = (Math.abs(den) > 1e-9) ? Stats.clamp(0.5 * (sm[k - 1] - sm[k + 1]) / den, -1, 1) : 0;
            peaks.add(new double[]{wide.supportLo + (k + delta) * 0.25, sm[k]});
        }
        java.util.Collections.sort(peaks, new java.util.Comparator<double[]>() {
            @Override public int compare(double[] a, double[] b) { return Double.compare(b[1], a[1]); }
        });

        java.util.List<Double> chosen = new java.util.ArrayList<Double>();
        for (double[] p : peaks) {
            if (chosen.size() >= maxCandidates) break;
            boolean tooClose = false;
            for (double c : chosen) {
                if (Math.abs(c - p[0]) < Math.max(1.5, 0.15 * c)) { tooClose = true; break; }
            }
            if (tooClose) continue;
            chosen.add(p[0]);
        }
        for (double r0 : chosen) {
            Trace t = refine(rm, cx0, cy0, r0, rSearchMin, rSearchMax, edgeThreshold, false);
            if (t.fitOk) out.add(t);
        }
        return out;
    }

    /**
     * @param sweepWide true to let the first pass search the whole calibrated window,
     *                  false to stay near the given radius (used per hypothesis)
     */
    private static Trace refine(ResidualMap rm, double cx0, double cy0, double r0,
                                double rSearchMin, double rSearchMax, double edgeThreshold,
                                boolean sweepWide) {
        double cx = cx0, cy = cy0, r = r0;
        for (int iter = 0; iter < 3; iter++) {
            double lo, hi;
            if (iter == 0 && sweepWide) {
                lo = Math.max(1.5, 0.80 * rSearchMin);
                hi = 1.20 * rSearchMax;
            } else {
                lo = Math.max(Math.max(1.5, 0.78 * rSearchMin), 0.72 * r);
                hi = Math.min(1.22 * rSearchMax, 1.28 * r);
            }
            Fan fan = castFan(rm, cx, cy, r, lo, hi, edgeThreshold, true);
            if (fan.count < 8) break;
            CircleFit fit = CircleFit.fit(fan.px, fan.py, fan.pw, fan.count, cx, cy, fan.rStar);
            if (fit.ok) {
                cx = fit.cx; cy = fit.cy; r = fit.r;
            } else {
                r = fan.rStar;
                break;
            }
        }

        // Final measurement pass, at the circle that was actually fitted.
        Fan fan = castFan(rm, cx, cy, r, 0.55 * r, 1.45 * r, edgeThreshold, false);
        Trace t = new Trace();
        t.cx = cx; t.cy = cy; t.r = r;
        t.bins = fan.bins;
        t.rEdge = fan.rEdge;
        t.vEdge = fan.vEdge;
        t.orient = fan.orient;
        t.have = fan.have;
        t.polarity = fan.polarity;
        t.contourPoints = fan.count;
        t.raysWithEdge = fan.raysWithEdge;
        t.radiusDominance = fan.dominance;
        t.fitOk = fan.count >= 8;
        return t;
    }

    // ------------------------------------------------------------------ internals

    private static final class Fan {
        int bins;
        double[] rEdge, vEdge, orient;
        boolean[] have;
        double[] px, py, pw;
        int count;
        int raysWithEdge;
        int polarity;
        double rStar;
        double dominance;
        double[] support;      // smoothed support per radius bin
        double supportLo;      // radius of bin 0
    }

    /**
     * @param rHint     current radius estimate, used as the band centre when pickRadius is false
     * @param pickRadius true to choose the radius by support, false to keep rHint
     */
    private static Fan castFan(ResidualMap rm, double cx, double cy, double rHint,
                               double lo, double hi, double edgeThreshold, boolean pickRadius) {
        int w = rm.width, h = rm.height;
        int nb = (int) Stats.clamp(Math.round(2 * Math.PI * Math.max(3, rHint) / 0.9), 32, 160);

        Fan f = new Fan();
        f.bins = nb;
        f.rEdge = new double[nb];
        f.vEdge = new double[nb];
        f.orient = new double[nb];
        f.have = new boolean[nb];
        f.px = new double[nb];
        f.py = new double[nb];
        f.pw = new double[nb];

        int ns = (int) Math.floor((hi - lo) / RAY_STEP) + 1;
        if (ns < 5) {
            f.rStar = rHint;
            f.dominance = 0;
            f.support = new double[0];
            f.supportLo = lo;
            return f;
        }

        // ---- 1. collect every edge crossing on every ray -----------------------
        double[] prof = new double[ns];
        int[] nEdge = new int[nb];
        double[][] et = new double[nb][MAX_EDGES_PER_RAY];   // radius
        double[][] ev = new double[nb][MAX_EDGES_PER_RAY];   // signed radial derivative
        double[][] eo = new double[nb][MAX_EDGES_PER_RAY];   // radiality of the gradient

        for (int b = 0; b < nb; b++) {
            double ang = 2 * Math.PI * b / nb;
            double ux = Math.cos(ang), uy = Math.sin(ang);
            double maxAbs = 0;
            for (int k = 0; k < ns; k++) {
                double t = lo + k * RAY_STEP;
                double x = cx + ux * t, y = cy + uy * t;
                if (x < 1 || y < 1 || x > w - 2 || y > h - 2) { prof[k] = 0; continue; }
                prof[k] = rm.grad.gxAt(x, y) * ux + rm.grad.gyAt(x, y) * uy;
                double a = Math.abs(prof[k]);
                if (a > maxAbs) maxAbs = a;
            }
            if (maxAbs < edgeThreshold) continue;

            for (int k = 1; k < ns - 1; k++) {
                double a = Math.abs(prof[k - 1]), c0 = Math.abs(prof[k]), c = Math.abs(prof[k + 1]);
                if (c0 < edgeThreshold || c0 < a || c0 <= c) continue;
                double den = a - 2 * c0 + c;
                double delta = (Math.abs(den) > 1e-9) ? Stats.clamp(0.5 * (a - c) / den, -1, 1) : 0;
                double t = lo + (k + delta) * RAY_STEP;
                double x = cx + ux * t, y = cy + uy * t;
                if (x < 1 || y < 1 || x > w - 2 || y > h - 2) continue;
                double gx = rm.grad.gxAt(x, y), gy = rm.grad.gyAt(x, y);
                double rd = gx * ux + gy * uy;
                double mag = Math.sqrt(gx * gx + gy * gy);
                double orient = mag > 1e-6 ? Math.abs(rd) / mag : 0;
                double strength = Math.abs(rd) * orient;
                // Keep the strongest MAX_EDGES_PER_RAY crossings, ordered strongest first.
                int slot = nEdge[b];
                if (slot == MAX_EDGES_PER_RAY) {
                    int weakest = 0;
                    for (int e = 1; e < MAX_EDGES_PER_RAY; e++) {
                        if (Math.abs(ev[b][e]) * eo[b][e] < Math.abs(ev[b][weakest]) * eo[b][weakest]) {
                            weakest = e;
                        }
                    }
                    if (strength <= Math.abs(ev[b][weakest]) * eo[b][weakest]) continue;
                    slot = weakest;
                } else {
                    nEdge[b]++;
                }
                et[b][slot] = t;
                ev[b][slot] = rd;
                eo[b][slot] = orient;
            }
            if (nEdge[b] > 0) f.raysWithEdge++;
        }

        // ---- 2. radius support ------------------------------------------------
        // Each crossing votes for the radii it could belong to, weighted by how strong
        // and how radial it is. Both matter: inside an object the residual carries the
        // background texture inverted, which litters the interior with crossings, but
        // those are weaker than the object's own outline and point in random directions,
        // whereas a real contour is strong and its gradient is purely radial.
        int nbin = (int) Math.floor((hi - lo) / 0.25) + 1;
        double[] support = new double[nbin];
        double tol = 0.7;
        double cap = 6.0 * edgeThreshold;
        for (int b = 0; b < nb; b++) {
            for (int e = 0; e < nEdge[b]; e++) {
                if (eo[b][e] < 0.40) continue;
                double weight = Math.min(Math.abs(ev[b][e]), cap) * eo[b][e] * eo[b][e];
                int k0 = (int) Math.max(0, Math.ceil((et[b][e] - tol - lo) / 0.25));
                int k1 = (int) Math.min(nbin - 1, Math.floor((et[b][e] + tol - lo) / 0.25));
                for (int k = k0; k <= k1; k++) support[k] += weight;
            }
        }
        double[] sm = new double[nbin];
        for (int k = 0; k < nbin; k++) {
            double a = support[Math.max(0, k - 1)], b2 = support[k], c = support[Math.min(nbin - 1, k + 1)];
            sm[k] = 0.25 * a + 0.5 * b2 + 0.25 * c;
        }

        double rStar = rHint;
        double dominance = 0;
        if (pickRadius) {
            int best = 0;
            for (int k = 1; k < nbin; k++) if (sm[k] > sm[best]) best = k;
            if (sm[best] <= 0) {
                f.rStar = rHint;
                f.support = sm;
                f.supportLo = lo;
                return f;
            }
            double delta = 0;
            if (best > 0 && best < nbin - 1) {
                double a = sm[best - 1], b2 = sm[best], c = sm[best + 1];
                double den = a - 2 * b2 + c;
                if (Math.abs(den) > 1e-9) delta = Stats.clamp(0.5 * (a - c) / den, -1, 1);
            }
            rStar = lo + (best + delta) * 0.25;
            dominance = dominance(sm, lo, best, rStar);
        } else {
            int at = (int) Stats.clamp(Math.round((rHint - lo) / 0.25), 0, nbin - 1);
            dominance = dominance(sm, lo, at, rHint);
        }
        f.rStar = rStar;
        f.dominance = dominance;
        f.support = sm;
        f.supportLo = lo;

        // ---- 3. per ray, keep the crossing nearest the chosen radius -----------
        double band = Math.max(1.2, 0.16 * rStar);
        double pos = 0, neg = 0;
        int n = 0;
        for (int b = 0; b < nb; b++) {
            int bestE = -1;
            double bestW = 0;
            for (int e = 0; e < nEdge[b]; e++) {
                if (Math.abs(et[b][e] - rStar) > band) continue;
                double wgt = Math.min(Math.abs(ev[b][e]), cap) * eo[b][e] * eo[b][e];
                if (wgt <= bestW) continue;
                bestW = wgt;
                bestE = e;
            }
            if (bestE < 0) continue;
            f.rEdge[b] = et[b][bestE];
            f.vEdge[b] = ev[b][bestE];
            f.orient[b] = eo[b][bestE];
            f.have[b] = true;
            if (f.vEdge[b] > 0) pos += f.vEdge[b]; else neg -= f.vEdge[b];
            if (f.orient[b] >= 0.40) {
                double ang = 2 * Math.PI * b / nb;
                f.px[n] = cx + Math.cos(ang) * f.rEdge[b];
                f.py[n] = cy + Math.sin(ang) * f.rEdge[b];
                f.pw[n] = Math.abs(f.vEdge[b]) * f.orient[b];
                n++;
            }
        }
        f.count = n;
        f.polarity = (pos >= neg) ? 1 : -1;
        return f;
    }

    /** Support at the chosen radius versus the best support at a clearly different radius. */
    private static double dominance(double[] sm, double lo, int at, double rStar) {
        double here = sm[at];
        double gap = Math.max(1.5, 0.14 * Math.max(1, rStar));
        double other = 0;
        for (int k = 0; k < sm.length; k++) {
            double rk = lo + k * 0.25;
            if (Math.abs(rk - rStar) <= gap) continue;
            if (sm[k] > other) other = sm[k];
        }
        return (here + 0.5) / (other + 0.5);
    }
}
