package nl.spheredetect;

import java.util.List;

/**
 * The calibration procedure.
 *
 * The app asks the user to do three things:
 *   1. aim the camera at the empty scene and shoot the REFERENCE image (a bit larger
 *      than the sampling window, so there is room for shift/zoom/rotation);
 *   2. put the ball in place and shoot a SAMPLING image;
 *   3. drag a circle over the ball in that sampling image (centre + radius) and set the
 *      size margin.
 * Optionally a ball-free sampling image is captured as well; that sharpens the decision
 * threshold considerably and is well worth one extra tap.
 *
 * From this the calibrator learns:
 *   - the exact ball radius (the user's circle is only a starting point; the contour is
 *     re-fitted sub-pixel) and the allowed radius window;
 *   - the noise floor of these frames, hence how big an intensity difference counts as
 *     "changed";
 *   - the strength of the ball's contour, hence the edge threshold (this is the
 *     "illumination learning": with a back-lit crescent the contour is strong on one
 *     side only, and the arc requirement is relaxed accordingly);
 *   - the contrast the ball produces and its polarity (brighter or darker);
 *   - the decision threshold, placed between the score of the ball frame and the score
 *     of the ball-free frame.
 */
public final class Calibrator {

    public static final class Input {
        /** Reference image of the empty scene, slightly larger than the sampling window. */
        public GrayImage reference;
        /** Sampling image WITH the ball in place. */
        public GrayImage positive;
        /** Optional sampling image WITHOUT the ball (strongly recommended). */
        public GrayImage negative;
        /** Ball centre as marked by the user, in positive-image pixel coordinates. */
        public double userCx, userCy;
        /** Ball radius as marked by the user, in pixels. */
        public double userRadius;
        /** Allowed size deviation at detection time, e.g. 0.35 for +-35%. */
        public double radiusMarginFraction = 0.35;
        /**
         * Set this when the object may be lit from behind or from the side at detection
         * time, so that only a crescent of it is visible. It relaxes how much of the
         * outline has to be found: a strongly back-lit sphere can show as little as 70-80
         * degrees of contour, because near the tips of the crescent its brightness passes
         * through the background level and the edge disappears there.
         *
         * Leave it off for a scene that is always lit the same way as during calibration:
         * demanding a nearly complete outline is the single most effective way to keep
         * stray edges from being mistaken for a ball.
         */
        public boolean expectPartialIllumination = true;
        /**
         * Optional colour planes of the POSITIVE frame. When supplied, the ball and
         * background colours are learned and later used to corroborate the brightness
         * verdict - most usefully to tell a launched ball (grass is back) from a covered
         * one (a club is there), which brightness alone confuses with a passing shadow.
         */
        public ChromaPlanes positiveChroma;
    }

    public static final class Output {
        public Calibration calibration;
        public boolean ok;
        /** Dutch, ready to show in the calibration screen. */
        public String message = "";
        public double fittedRadius;
        public double fittedCx, fittedCy;
        public double positiveScore, negativeScore;
        public Registrar.Result registration;
        public CircleScore positiveEvidence;

        @Override public String toString() {
            return (ok ? "OK" : "MISLUKT") + ": " + message + "\n  " +
                    (calibration != null ? calibration.toJson() : "-");
        }
    }

    private Calibrator() {}

    public static Output calibrate(Input in, DetectorConfig cfg) {
        if (cfg == null) cfg = new DetectorConfig();
        Output out = new Output();

        if (in.reference == null || in.positive == null) {
            out.ok = false;
            out.message = "referentie- of samplingbeeld ontbreekt";
            return out;
        }
        if (in.userRadius < 4) {
            out.ok = false;
            out.message = "aangegeven bolradius is te klein (minimaal 4 pixels)";
            return out;
        }

        double margin = Stats.clamp(in.radiusMarginFraction, 0.10, 0.60);

        // ---- 1. bootstrap calibration -----------------------------------------
        Calibration cal = new Calibration();
        cal.rNominal = in.userRadius;
        cal.rMin = in.userRadius * (1 - margin);
        cal.rMax = in.userRadius * (1 + margin);
        cal.residualFloor = 2.0;
        cal.edgeThreshold = 0.8;
        cal.learnedContrast = 1.0;
        cal.minArcDeg = 60;
        cal.scoreThreshold = 0.0;
        cal.valid = true;

        // ---- 2. align the positive sample onto the reference -------------------
        Registrar registrar = new Registrar(in.reference, cfg);
        Registrar.Result reg = registrar.register(in.positive, null);
        out.registration = reg;
        if (!reg.ok) {
            out.ok = false;
            out.message = "kalibratie mislukt: samplingbeeld kon niet op de referentie uitgelijnd " +
                    "worden (kost " + String.format(java.util.Locale.US, "%.2f", reg.cost) +
                    "). Maak beide beelden opnieuw met dezelfde camerapositie.";
            out.calibration = cal;
            cal.valid = false;
            return out;
        }

        ResidualMap rm = ResidualMap.compute(in.positive, in.reference, reg.transform, cfg, cal);

        // ---- 3. noise floor, measured OUTSIDE the marked ball ------------------
        double sigmaOut = residualSigmaOutside(rm, in.userCx, in.userCy, 1.5 * in.userRadius);
        cal.noiseSigma = Math.max(0.3, sigmaOut);

        // ---- 4. contour strength on the marked circle -------------------------
        double[] strength = contourStrength(rm, in.userCx, in.userCy, in.userRadius);
        double median = strength[0];
        double p75 = strength[1];
        if (p75 < 3 * cal.noiseSigma * 0.5) {
            out.ok = false;
            cal.valid = false;
            out.calibration = cal;
            out.message = "kalibratie mislukt: op de aangegeven cirkel is geen duidelijke rand te " +
                    "vinden. Controleer positie/grootte van de cirkel, of zorg voor meer contrast.";
            return out;
        }
        cal.edgeThreshold = Math.max(0.32 * Math.max(median, 0.5 * p75), 1.2 * cal.noiseSigma * 0.5);

        // ---- 5. re-fit the contour to get the true radius ---------------------
        // The user's circle is the starting point - that is exactly why we asked for it -
        // so the contour is traced straight from the mark. No circle search is needed
        // here; that is only required at detection time, when nobody says where to look.
        double searchMin = Math.max(3.0, in.userRadius * 0.60);
        double searchMax = in.userRadius * 1.50;
        double fcx = in.userCx, fcy = in.userCy, fr = in.userRadius;
        // An object may present more than one circular contour (a shaded ball shows its
        // silhouette and, just inside it, the ridge where its brightness drops fastest).
        // The size the user marked is what tells us which one they mean.
        ContourTracer.Trace tr0 = null;
        double bestGap = Double.MAX_VALUE;
        for (ContourTracer.Trace t : ContourTracer.traceCandidates(rm, in.userCx, in.userCy,
                searchMin, searchMax, cal.edgeThreshold, 3)) {
            if (Math.hypot(t.cx - in.userCx, t.cy - in.userCy) > 0.6 * in.userRadius) continue;
            if (t.r < 0.6 * in.userRadius || t.r > 1.5 * in.userRadius) continue;
            double gap = Math.abs(t.r - in.userRadius);
            if (gap < bestGap) { bestGap = gap; tr0 = t; }
        }
        if (tr0 != null) {
            fcx = tr0.cx; fcy = tr0.cy; fr = tr0.r;
        } else {
            out.ok = false;
            cal.valid = false;
            out.calibration = cal;
            out.message = "kalibratie mislukt: rond de aangegeven cirkel kon geen sluitende " +
                    "contour gevolgd worden. Controleer positie en grootte van de cirkel.";
            return out;
        }
        out.fittedCx = fcx;
        out.fittedCy = fcy;
        out.fittedRadius = fr;

        cal.rNominal = fr;
        cal.rMin = Math.max(3.0, fr * (1 - margin));
        cal.rMax = fr * (1 + margin);

        // ---- 6. contour strength again, now on the properly fitted circle -----
        double[] strength2 = contourStrength(rm, fcx, fcy, fr);
        cal.edgeThreshold = Math.max(0.32 * Math.max(strength2[0], 0.5 * strength2[1]),
                1.2 * cal.noiseSigma * 0.5);

        // ---- 7. contrast + polarity ------------------------------------------
        double[] cp = contrastAndPolarity(rm, fcx, fcy, fr);
        cal.learnedContrast = Math.max(2.0, cp[0]);
        cal.polarity = cp[1] >= 0 ? 1 : -1;
        cal.residualFloor = Stats.clamp(2.5 * cal.noiseSigma, 2.5, 0.35 * cal.learnedContrast);

        // ---- 8. how complete is the contour on the calibration ball? ---------
        // Re-derive the change mask with the final thresholds before measuring.
        rm = ResidualMap.compute(in.positive, in.reference, reg.transform, cfg, cal);
        Blobs.Group around = groupAround(rm, fcx, fcy, fr, cfg);
        ContourTracer.Trace tr = ContourTracer.trace(rm, fcx, fcy, fr,
                cal.rMin, cal.rMax, cal.edgeThreshold);
        if (Math.abs(tr.r - fr) > 0.25 * fr) tr = tr0;   // keep the contour the user meant
        CircleScore ev = RoundnessScorer.evaluate(rm, around, tr, cfg, cal);
        out.positiveEvidence = ev;
        cal.calibratedArcDeg = ev.arcDeg;
        // Never demand more contour than calibration itself could produce, and relax
        // further when the user says the lighting may leave only a crescent visible.
        double fromCalibration = Stats.clamp(0.75 * ev.arcDeg, 75, 200);
        cal.minArcDeg = in.expectPartialIllumination
                ? Math.max(cfg.minArcDeg, Math.min(80, fromCalibration))
                : fromCalibration;

        // ---- 8b. colour of ball and background --------------------------------
        if (in.positiveChroma != null) {
            cal.color = ColorModel.learn(in.positiveChroma, fcx, fcy, fr);
        }

        // ---- 9. decision threshold -------------------------------------------
        cal.scoreThreshold = 0.0;
        SphereDetector det = new SphereDetector(in.reference, cal, cfg);
        DetectionResult pos = det.detect(in.positive);
        out.positiveScore = pos.score;
        cal.positiveScore = pos.score;

        GrayImage neg = in.negative;
        boolean syntheticNegative = false;
        if (neg == null) {
            // Fall back to a synthetic ball-free sample: the middle of the reference.
            int w = in.positive.width, h = in.positive.height;
            int x0 = (in.reference.width - w) / 2;
            int y0 = (in.reference.height - h) / 2;
            if (x0 >= 0 && y0 >= 0) {
                neg = in.reference.crop(x0, y0, w, h);
                syntheticNegative = true;
            }
        }
        double negScore = 0;
        if (neg != null) {
            det.resetTracking();
            DetectionResult nr = det.detect(neg);
            negScore = nr.score;
        }
        out.negativeScore = negScore;
        cal.negativeScore = negScore;

        double thr = Stats.clamp(0.62 * pos.score, 0.40, 0.78);
        if (negScore + 0.08 > thr) thr = Math.min(0.85, negScore + 0.08);
        cal.scoreThreshold = thr;

        StringBuilder msg = new StringBuilder();
        boolean ok = true;
        if (!(pos.score > 0) || (out.positiveEvidence != null && !out.positiveEvidence.gatesPassed)) {
            ok = false;
            msg.append("de gemarkeerde bol werd zelf niet als rond object geaccepteerd (")
               .append(out.positiveEvidence != null ? out.positiveEvidence.rejectReason : "geen score")
               .append("). ");
        }
        if (pos.score <= thr) {
            ok = false;
            msg.append("te weinig marge tussen bol en achtergrond. ");
        }
        if (ok) {
            msg.append("kalibratie gelukt. Radius ")
               .append(String.format(java.util.Locale.US, "%.1f", fr))
               .append(" px (toegestaan ")
               .append(String.format(java.util.Locale.US, "%.1f-%.1f", cal.rMin, cal.rMax))
               .append(" px), contour ")
               .append(String.format(java.util.Locale.US, "%.0f", ev.arcDeg))
               .append(" graden, bol is ")
               .append(cal.polarity > 0 ? "lichter" : "donkerder")
               .append(" dan de achtergrond, score bol ")
               .append(String.format(java.util.Locale.US, "%.2f", pos.score))
               .append(" vs leeg ")
               .append(String.format(java.util.Locale.US, "%.2f", negScore))
               .append(syntheticNegative ? " (leeg beeld afgeleid uit de referentie; " +
                       "maak liever een echt leeg samplingbeeld)" : "")
               .append(", drempel ")
               .append(String.format(java.util.Locale.US, "%.2f", thr))
               .append('.');
            if (in.positiveChroma != null) msg.append(' ').append(cal.color.describe()).append('.');
        }
        cal.notes = (ev.arcDeg < 300 ? "contour deels zichtbaar (tegenlicht/maanvorm)" : "volledige contour")
                + (in.expectPartialIllumination ? ", maanvorm toegestaan" : ", volledige omtrek vereist");
        cal.valid = ok;
        out.calibration = cal;
        out.ok = ok;
        out.message = msg.toString();
        return out;
    }

    // -------------------------------------------------------------------- helpers

    private static double residualSigmaOutside(ResidualMap rm, double cx, double cy, double rExclude) {
        int w = rm.width, h = rm.height;
        double[] v = new double[(w / 2 + 1) * (h / 2 + 1)];
        int n = 0;
        double r2 = rExclude * rExclude;
        for (int y = 1; y < h - 1; y += 2) {
            for (int x = 1; x < w - 1; x += 2) {
                int i = y * w + x;
                if (!rm.valid[i]) continue;
                double dx = x - cx, dy = y - cy;
                if (dx * dx + dy * dy <= r2) continue;
                v[n++] = rm.smooth.data[i];
            }
        }
        return Stats.sigmaFromMad(v, n);
    }

    /** @return {median, 75th percentile} of the strongest radial step per angular bin. */
    private static double[] contourStrength(ResidualMap rm, double cx, double cy, double r) {
        int w = rm.width, h = rm.height;
        int nb = (int) Stats.clamp(Math.round(2 * Math.PI * r / 0.9), 32, 128);
        double[] vals = new double[nb];
        int n = 0;
        for (int b = 0; b < nb; b++) {
            double ang = 2 * Math.PI * b / nb;
            double ux = Math.cos(ang), uy = Math.sin(ang);
            double best = 0;
            for (double t = 0.6 * r; t <= 1.4 * r; t += 0.25) {
                double px = cx + ux * t, py = cy + uy * t;
                if (px < 1 || py < 1 || px > w - 2 || py > h - 2) continue;
                double rd = Math.abs(rm.grad.gxAt(px, py) * ux + rm.grad.gyAt(px, py) * uy);
                if (rd > best) best = rd;
            }
            vals[n++] = best;
        }
        double[] copy = new double[n];
        System.arraycopy(vals, 0, copy, 0, n);
        double med = Stats.medianInPlace(copy, n);
        System.arraycopy(vals, 0, copy, 0, n);
        double p75 = Stats.percentileInPlace(copy, n, 0.75);
        return new double[]{med, p75};
    }

    /** @return {contrast amplitude, signed polarity} inside the ball. */
    private static double[] contrastAndPolarity(ResidualMap rm, double cx, double cy, double r) {
        int w = rm.width, h = rm.height;
        int cap = (int) (Math.PI * (1.05 * r + 2) * (1.05 * r + 2)) + 16;
        double[] amp = new double[cap];
        int n = 0;
        double signed = 0;
        double lim = 1.05 * r;
        for (int y = (int) Math.max(0, cy - lim); y <= Math.min(h - 1, cy + lim); y++) {
            for (int x = (int) Math.max(0, cx - lim); x <= Math.min(w - 1, cx + lim); x++) {
                double dx = x - cx, dy = y - cy;
                if (dx * dx + dy * dy > lim * lim) continue;
                int i = y * w + x;
                if (!rm.valid[i]) continue;
                if (n < cap) {
                    amp[n++] = Math.abs(rm.smooth.data[i]);
                    signed += rm.smooth.data[i];
                }
            }
        }
        double contrast = n > 0 ? Stats.percentileInPlace(amp, n, 0.90) : 0;
        return new double[]{contrast, signed};
    }

    /** Change blob near the marked ball; falls back to a synthetic disc when nothing was flagged. */
    private static Blobs.Group groupAround(ResidualMap rm, double cx, double cy, double r,
                                           DetectorConfig cfg) {
        List<Blobs.Group> raw = Blobs.label(rm.mask, rm.width, rm.height, cfg.minBlobArea);
        List<Blobs.Group> merged = Blobs.merge(raw, cfg.blobMergeGapFactor * r * 1.4);
        Blobs.Group best = null;
        double bestD = Double.MAX_VALUE;
        for (Blobs.Group g : merged) {
            double d = Math.hypot(g.centroidX - cx, g.centroidY - cy);
            if (d < 1.4 * r && d < bestD) { bestD = d; best = g; }
        }
        if (best != null) return best;

        Blobs.Group g = new Blobs.Group();
        int w = rm.width;
        int cnt = 0;
        int[] tmp = new int[(int) (Math.PI * (r + 2) * (r + 2)) + 16];
        g.minX = Integer.MAX_VALUE; g.minY = Integer.MAX_VALUE; g.maxX = -1; g.maxY = -1;
        for (int y = (int) Math.max(0, cy - r); y <= Math.min(rm.height - 1, cy + r); y++) {
            for (int x = (int) Math.max(0, cx - r); x <= Math.min(w - 1, cx + r); x++) {
                double dx = x - cx, dy = y - cy;
                if (dx * dx + dy * dy > r * r) continue;
                if (cnt < tmp.length) tmp[cnt++] = y * w + x;
                g.minX = Math.min(g.minX, x); g.maxX = Math.max(g.maxX, x);
                g.minY = Math.min(g.minY, y); g.maxY = Math.max(g.maxY, y);
            }
        }
        g.area = cnt;
        g.pixels = new int[cnt];
        System.arraycopy(tmp, 0, g.pixels, 0, cnt);
        g.centroidX = cx;
        g.centroidY = cy;
        return g;
    }

}
