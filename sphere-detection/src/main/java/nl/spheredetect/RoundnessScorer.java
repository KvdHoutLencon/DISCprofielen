package nl.spheredetect;

/**
 * Turns a traced contour into a decision about roundness.
 *
 * Metrics, and why each one is there:
 *
 *   arcDeg      - longest contiguous stretch of valid contour. A fully visible ball
 *                 gives ~360 degrees, a back-lit crescent 100..250, an accidental edge
 *                 much less. This is the ONE requirement that has to be relaxed for
 *                 the moon-shaped case, so it is a separate metric rather than being
 *                 folded into a single "circularity" number.
 *   radiusRmse  - scatter of the contour radius around the fitted circle. This is the
 *                 real roundness test: it stays sharp no matter how much of the contour
 *                 is missing, so a square, a triangle or an irregular smudge is rejected
 *                 even when only part of its outline is visible.
 *   orientation - how radial the contour gradient is. A circle's intensity step is
 *                 normal to its contour, hence purely radial.
 *   containment - the changed area must sit inside the fitted disc instead of sprawling
 *                 beyond it (rejects large illumination changes that happen to contain
 *                 a round-ish piece of edge).
 *   contrast    - the change must have the amplitude that was learned during calibration.
 */
public final class RoundnessScorer {

    private RoundnessScorer() {}

    public static CircleScore evaluate(ResidualMap rm, Blobs.Group group, ContourTracer.Trace tr,
                                       DetectorConfig cfg, Calibration cal) {
        CircleScore s = new CircleScore();
        s.cx = tr.cx;
        s.cy = tr.cy;
        s.r = tr.r;
        s.polarity = tr.polarity;

        int w = rm.width, h = rm.height;
        int nb = tr.bins;

        boolean[] good = new boolean[nb];
        int nGood = 0;
        double sumOrient = 0, sumSq = 0;
        double band = Math.max(1.0, 0.14 * tr.r);
        for (int b = 0; b < nb; b++) {
            if (!tr.have[b]) continue;
            if (tr.orient[b] < 0.45) continue;
            if (Math.abs(tr.rEdge[b] - tr.r) > band) continue;
            good[b] = true;
            nGood++;
            sumOrient += tr.orient[b];
            double d = tr.rEdge[b] - tr.r;
            sumSq += d * d;
        }
        // How sharply the outline concentrates at this radius rather than being spread
        // over many. This is what stops a wrong circle from validating itself on the
        // subset of contour that happens to agree with it while ignoring the rest.
        s.consistency = tr.radiusDominance;

        // ---- harmonic shape test -----------------------------------------------
        double[] harm = harmonicDeviation(tr, cfg);
        s.ellipticity = harm[0];
        s.shapeHarmonic = harm[1];

        s.coverage = nb > 0 ? nGood / (double) nb : 0;
        s.orientation = nGood > 0 ? sumOrient / nGood : 0;
        s.radiusRmse = nGood > 0 ? Math.sqrt(sumSq / nGood) : Double.MAX_VALUE;
        s.arcDeg = nb > 0 ? 360.0 * longestRun(good) / nb : 0;

        // ---- containment of the change blob ------------------------------------
        int inside = 0;
        double lim = 1.25 * tr.r;
        for (int p : group.pixels) {
            double dx = (p % w) - tr.cx, dy = (p / w) - tr.cy;
            if (Math.sqrt(dx * dx + dy * dy) <= lim) inside++;
        }
        s.containment = group.area > 0 ? inside / (double) group.area : 0;

        // ---- change amplitude inside the disc ----------------------------------
        double measured = amplitude(rm, tr.cx, tr.cy, 1.15 * tr.r);
        s.contrastRatio = cal.learnedContrast > 1e-6 ? measured / cal.learnedContrast : 0;

        // ---- gates -------------------------------------------------------------
        double rmseGate = Math.max(cfg.maxRadiusRmseAbsPx, cfg.maxRadiusRmseRel * tr.r);
        double harmonicGate = Math.max(cfg.maxShapeHarmonic,
                cfg.shapeHarmonicRadiusSlack / Math.max(1, tr.r));
        double minArc = Math.max(cfg.minArcDeg, cal.minArcDeg);
        StringBuilder why = new StringBuilder();
        if (tr.r < cal.rMin || tr.r > cal.rMax) why.append("radius buiten gekalibreerd bereik; ");
        if (s.arcDeg < minArc) why.append("contour te kort; ");
        if (s.radiusRmse > rmseGate) why.append("contour niet rond genoeg; ");
        if (s.orientation < cfg.minOrientation) why.append("gradienten niet radiaal; ");
        if (s.containment < cfg.minContainment) why.append("verandering loopt buiten de cirkel; ");
        if (s.contrastRatio < cfg.minContrastRatio) why.append("verandering te zwak; ");
        if (s.consistency < cfg.minRadiusDominance) why.append("contour past niet op een cirkel; ");
        if (s.shapeHarmonic > harmonicGate) why.append("omtrek is hoekig, niet rond; ");
        if (s.ellipticity > cfg.maxEllipticity) why.append("omtrek is ovaal, niet rond; ");
        if (group.area > cfg.maxAreaFactor * Math.PI * tr.r * tr.r) why.append("vlek veel groter dan de cirkel; ");
        s.gatesPassed = why.length() == 0;
        s.rejectReason = why.toString();

        // ---- fused confidence --------------------------------------------------
        // Each sub-score maps its own GATE value to FLOOR and its "clearly good" value to
        // 1. Admission is the gates' job; the score only expresses how convincing an
        // admitted candidate is. Without that floor a candidate sitting right at a gate we
        // deliberately chose to accept - an 80 degree crescent, say - would be scored near
        // zero and end up indistinguishable from something that was rejected outright.
        s.sArc = ramp(s.arcDeg, minArc, Math.min(300, Math.max(240, minArc * 3)));
        s.sRadius = rampDown(s.radiusRmse, 0.3 * rmseGate, rmseGate);
        s.sOrientation = ramp(s.orientation, cfg.minOrientation, 0.90);
        s.sContainment = ramp(s.containment, cfg.minContainment, 0.95);
        s.sContrast = ramp(s.contrastRatio, cfg.minContrastRatio, 0.85);
        s.sConsistency = ramp(s.consistency, cfg.minRadiusDominance, cfg.goodRadiusDominance);
        if (s.shapeHarmonic <= 0 && s.ellipticity <= 0) {
            // Not measurable: too little of the outline visible. Neither confirmed round
            // nor shown to be angular, so this contributes neutrally.
            s.sShape = 0.80;
        } else {
            s.sShape = rampDown(s.shapeHarmonic, 0.3 * harmonicGate, harmonicGate)
                    * (1.0 - 0.35 * Stats.smooth01(s.ellipticity, 0.5 * cfg.maxEllipticity,
                    cfg.maxEllipticity));
        }

        double[] v = {s.sArc, s.sRadius, s.sOrientation, s.sContainment, s.sContrast,
                s.sConsistency, s.sShape};
        double[] wt = {1.2, 1.6, 1.0, 0.8, 0.6, 0.5, 1.5};
        double logSum = 0, wSum = 0;
        for (int i = 0; i < v.length; i++) {
            logSum += wt[i] * Math.log(Math.max(1e-4, v[i]));
            wSum += wt[i];
        }
        s.score = Math.exp(logSum / wSum);
        if (!s.gatesPassed) s.score = Math.min(s.score, GATED_OUT_SCORE);
        return s;
    }

    /** Sub-score floor: the value a metric sitting exactly at its gate is worth. */
    private static final double SCORE_FLOOR = 0.35;

    /**
     * Ceiling imposed on a candidate that failed one of the gates. Anything at or below
     * this is "not the calibrated ball", whatever the sub-scores said. Callers that turn
     * the score into a present/absent decision must keep their threshold clear of it -
     * see {@link LockedBallVerifier}, which scales failed candidates further down rather
     * than relying on a threshold happening to fall on the right side of this number.
     */
    public static final double GATED_OUT_SCORE = 0.35;

    /** Maps gate -> SCORE_FLOOR and good -> 1, for metrics where higher is better. */
    private static double ramp(double v, double gate, double good) {
        return SCORE_FLOOR + (1 - SCORE_FLOOR) * Stats.smooth01(v, gate, good);
    }

    /** Maps gate -> SCORE_FLOOR and good -> 1, for metrics where lower is better. */
    private static double rampDown(double v, double good, double gate) {
        return SCORE_FLOOR + (1 - SCORE_FLOOR) * (1 - Stats.smooth01(v, good, gate));
    }

    /**
     * Decomposes the contour radius into angular harmonics and returns
     * {@code {ellipticity, angularity}}:
     * <ul>
     *   <li>ellipticity = relative amplitude of the 2nd harmonic (oval-ness)</li>
     *   <li>angularity  = largest relative amplitude of the 3rd..6th harmonics</li>
     * </ul>
     * This is the sharpest roundness test available, and it works where plain radius
     * scatter does not: a square puts almost all of its deviation into ONE harmonic
     * (k=4; a triangle k=3), while a circle's deviation is unstructured measurement
     * noise spread thinly over all of them. At r = 9 px a square reaches about 0.15
     * angularity and a real sphere stays near 0.01, a separation that mere scatter
     * cannot offer at that size.
     *
     * The two are reported separately because they have different physical causes. A
     * REAL sphere does produce a low-order signal: its own shading is brightest on the
     * lit side, which shifts the apparent edge slightly in and out with a period of one
     * or two cycles per revolution. That is not a shape defect, so the 2nd harmonic gets
     * a looser limit, while the 3rd and higher - which only genuine corners produce -
     * are judged strictly. The constant and 1st harmonic are fitted but not judged at
     * all: they merely express a radius error and a centre offset that the circle fit
     * already accounts for.
     *
     * Estimating six harmonics needs most of the outline to be visible. With a partially
     * lit (crescent) object it is not, and a short arc cannot tell the harmonics apart,
     * so the test returns zeroes (= no objection) below
     * {@link DetectorConfig#harmonicMinArcDeg}; roundness then rests on the radius
     * scatter and consistency tests.
     */
    private static double[] harmonicDeviation(ContourTracer.Trace tr, DetectorConfig cfg) {
        int nb = tr.bins;
        int kMax = 6;
        int m = 1 + 2 * kMax;
        double[][] basis = new double[nb][];
        double[] y = new double[nb];
        double[] w = new double[nb];
        int n = 0;
        int span = 0;
        for (int b = 0; b < nb; b++) {
            if (!tr.have[b] || tr.orient[b] < 0.45) continue;
            if (Math.abs(tr.rEdge[b] - tr.r) > Math.max(1.5, 0.30 * tr.r)) continue;
            double ang = 2 * Math.PI * b / nb;
            double[] row = new double[m];
            row[0] = 1;
            for (int k = 1; k <= kMax; k++) {
                row[2 * k - 1] = Math.cos(k * ang);
                row[2 * k] = Math.sin(k * ang);
            }
            basis[n] = row;
            y[n] = tr.rEdge[b] - tr.r;
            w[n] = tr.orient[b];
            n++;
            span++;
        }
        // Needs enough of the circumference, and comfortably more points than unknowns.
        if (n < m + 8 || 360.0 * span / nb < cfg.harmonicMinArcDeg) return new double[]{0, 0};

        double[] coeff = LinAlg.leastSquares(basis, y, w, n, m);
        if (coeff == null) return new double[]{0, 0};
        double inv = 1.0 / Math.max(1e-6, tr.r);
        double ellip = amp(coeff, 2) * inv;
        double angular = 0;
        for (int k = 3; k <= kMax; k++) angular = Math.max(angular, amp(coeff, k) * inv);
        return new double[]{ellip, angular};
    }

    private static double amp(double[] coeff, int k) {
        double a = coeff[2 * k - 1], b = coeff[2 * k];
        return Math.sqrt(a * a + b * b);
    }

    /**
     * Amplitude of the change inside a disc: 93rd percentile of |residual| over the
     * pixels that were actually flagged as changed. Restricting it to the changed pixels
     * makes the number comparable between a fully visible ball and a thin crescent, which
     * matters because calibration and detection may see different amounts of the object.
     */
    public static double amplitude(ResidualMap rm, double cx, double cy, double radius) {
        int w = rm.width, h = rm.height;
        int cap = (int) (Math.PI * (radius + 2) * (radius + 2)) + 16;
        double[] amp = new double[cap];
        int n = 0;
        int x0 = (int) Math.max(0, Math.floor(cx - radius));
        int x1 = (int) Math.min(w - 1, Math.ceil(cx + radius));
        int y0 = (int) Math.max(0, Math.floor(cy - radius));
        int y1 = (int) Math.min(h - 1, Math.ceil(cy + radius));
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                double dx = x - cx, dy = y - cy;
                if (dx * dx + dy * dy > radius * radius) continue;
                int i = y * w + x;
                if (!rm.valid[i] || !rm.mask[i]) continue;
                if (n < cap) amp[n++] = Math.abs(rm.smooth.data[i]);
            }
        }
        if (n < 4) return 0;
        return Stats.percentileInPlace(amp, n, 0.93);
    }

    /** Longest circular run of true, tolerating single-bin gaps. */
    private static int longestRun(boolean[] ok) {
        int n = ok.length;
        if (n == 0) return 0;
        boolean[] filled = new boolean[n];
        for (int i = 0; i < n; i++) {
            filled[i] = ok[i] || (ok[(i - 1 + n) % n] && ok[(i + 1) % n]);
        }
        boolean all = true;
        for (boolean b : filled) if (!b) { all = false; break; }
        if (all) return n;
        int best = 0, cur = 0;
        for (int i = 0; i < 2 * n; i++) {
            if (filled[i % n]) {
                cur++;
                if (cur > best) best = cur;
            } else {
                cur = 0;
            }
        }
        return Math.min(best, n);
    }
}
