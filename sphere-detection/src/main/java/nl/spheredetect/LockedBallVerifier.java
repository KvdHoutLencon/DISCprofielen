package nl.spheredetect;

import java.util.List;

/**
 * Answers, per frame, the only question that matters once the ball has been found and
 * the camera is standing still: <em>is the ball still lying where it was?</em>
 *
 * This is a fundamentally easier question than "is there a ball somewhere", and treating
 * it as such buys three things at once:
 *
 *  1. <b>The club becomes irrelevant.</b> Only a small window around the known position
 *     is examined, so a club being brought to address, a shadow drifting across the mat,
 *     or the player's feet simply fall outside what is looked at. No amount of movement
 *     elsewhere can disturb the verdict.
 *  2. <b>It is roughly an order of magnitude cheaper</b> than a full search, which is
 *     what makes a live camera stream affordable.
 *  3. <b>Two separate verdicts instead of one.</b> Besides "is the ball there", it
 *     reports "does this spot look like the empty background again". That second signal
 *     is what separates a ball that has been STRUCK from a ball that is merely COVERED,
 *     and without it the two are genuinely indistinguishable - in both cases the ball
 *     stops being visible.
 *
 * That last point deserves emphasis, because it is the whole basis for the launch
 * trigger. When a club head passes over the ball, the ball's evidence disappears but
 * something foreign is sitting there instead. When the ball is actually launched, the
 * spot reverts to the grass that the reference image already knows about. So a launch is
 * not "the ball stopped being detected" - it is "the ball stopped being detected AND the
 * background came back".
 */
public final class LockedBallVerifier {

    public static final class Verdict {
        /** Could the check run at all (window inside the frame, registration usable)? */
        public boolean ok;

        /** 0..1 - how convincingly the calibrated ball is still at this spot. */
        public double ballScore;
        /** 0..1 - how much this spot looks like the empty reference again. */
        public double backgroundScore;
        /**
         * 0..1 - how much foreign material sits in the ring around the ball. High while a
         * club is being held at address or is passing through.
         */
        public double foreignFraction;

        /** Refined position; the ball may wobble by a fraction of a pixel between frames. */
        public double cx, cy, r;
        /** Distance from the locked position, in pixels. */
        public double drift;

        /** Full measurement detail of the best circle hypothesis, or null. */
        public CircleScore evidence;
        /** Chroma verdicts, or -1 when colour is unavailable or unusable. */
        public double ballColorScore = -1;
        public double backgroundColorScore = -1;

        public double residualSigma;
        /** Illumination correction used, carried into the next frame as a prior. */
        public double gain = 1, offset = 0;
        /**
         * How much the illumination correction had to move this frame. Steady light sits
         * near zero; a shadow edge sweeping through the window keeps this pinned at the
         * per-frame limit for as long as it takes to cross. That distinction matters,
         * because while the correction is chasing a shadow it is also partly explaining
         * the spot away, and a verdict of "the background is back" cannot be trusted.
         */
        public double gainSlew;

        @Override public String toString() {
            return String.format(java.util.Locale.US,
                    "bal=%.2f achtergrond=%.2f vreemd=%.2f drift=%.2fpx licht=%.3f kleur(bal=%.2f,acht=%.2f)",
                    ballScore, backgroundScore, foreignFraction, drift, gainSlew,
                    ballColorScore, backgroundColorScore);
        }
    }

    private final GrayImage reference;
    private final DetectorConfig cfg;
    private final Calibration cal;
    private double priorGain = 1, priorOffset = 0;
    private boolean haveIllumination;

    public LockedBallVerifier(GrayImage reference, Calibration cal, DetectorConfig cfg) {
        this.reference = reference;
        this.cal = cal;
        this.cfg = cfg != null ? cfg : new DetectorConfig();
    }

    public void resetIllumination() {
        priorGain = 1;
        priorOffset = 0;
        haveIllumination = false;
    }

    /**
     * @param lockedR radius as measured when the ball was locked on; the search stays
     *                close to it, because the ball cannot change size while lying still
     */
    public Verdict verify(GrayImage sample, ChromaPlanes chroma, SimilarityTransform t,
                          double lockedCx, double lockedCy, double lockedR) {
        Verdict v = new Verdict();
        v.cx = lockedCx;
        v.cy = lockedCy;
        v.r = lockedR;

        // Window: the ball, its contour search band, and enough surrounding background
        // to both estimate the illumination and notice foreign objects arriving.
        int half = (int) Math.ceil(cfg.verifyWindowFactor * lockedR) + 6;
        int ox = (int) Math.round(lockedCx) - half;
        int oy = (int) Math.round(lockedCy) - half;
        int w = 2 * half + 1, h = 2 * half + 1;
        if (ox < 0) { w += ox; ox = 0; }
        if (oy < 0) { h += oy; oy = 0; }
        if (ox + w > sample.width) w = sample.width - ox;
        if (oy + h > sample.height) h = sample.height - oy;
        if (w < 12 || h < 12) return v;

        // The very first frame after a reset has no history, so it fits freely; from then
        // on the illumination may only creep.
        double gainStep = haveIllumination ? cfg.maxGainStepPerFrame : 0;
        double offsetStep = haveIllumination ? cfg.maxOffsetStepPerFrame : 0;
        ResidualMap rm = ResidualMap.computeWindow(sample, reference, t, cfg, cal,
                ox, oy, w, h, priorGain, priorOffset, gainStep, offsetStep);
        v.gainSlew = haveIllumination
                ? Math.abs(rm.medianGain - priorGain) + Math.abs(rm.medianOffset - priorOffset) / 60.0
                : 0;
        priorGain = rm.medianGain;
        priorOffset = rm.medianOffset;
        haveIllumination = true;

        // Contour strength and contrast scale with the light that is actually falling on
        // the scene, so judge this frame against thresholds scaled to it.
        Calibration lit = cal.scaledForIllumination(rm.medianGain);
        v.residualSigma = rm.sigma;
        v.gain = rm.medianGain;
        v.offset = rm.medianOffset;
        v.ok = true;

        double lx = lockedCx - ox, ly = lockedCy - oy;

        // ---- is the ball still there? ------------------------------------------
        // The size is known, so the radius search is narrow. Only hypotheses near the
        // locked position count: a round thing appearing elsewhere in the window is not
        // this ball.
        double rLo = Math.max(cal.rMin, 0.80 * lockedR);
        double rHi = Math.min(cal.rMax, 1.20 * lockedR);
        List<Blobs.Group> groups = Blobs.merge(
                Blobs.label(rm.mask, w, h, cfg.minBlobArea), cfg.blobMergeGapFactor * lockedR);

        Blobs.Group near = null;
        double bestD = Double.MAX_VALUE;
        for (Blobs.Group g : groups) {
            double d = Math.hypot(g.centroidX - lx, g.centroidY - ly);
            if (d < cfg.maxDriftFactor * lockedR && d < bestD) { bestD = d; near = g; }
        }
        if (near == null) near = Blobs.disc(lx, ly, lockedR, w, h);

        CircleScore best = null;
        for (ContourTracer.Trace tr : ContourTracer.traceCandidates(rm, lx, ly, rLo, rHi,
                lit.edgeThreshold, cfg.radiiPerCentre)) {
            double d = Math.hypot(tr.cx - lx, tr.cy - ly);
            if (d > cfg.maxDriftFactor * lockedR) continue;
            CircleScore s = RoundnessScorer.evaluate(rm, near, tr, cfg, lit);
            if (best == null || s.score > best.score) best = s;
        }
        v.evidence = best;
        if (best != null) {
            // A candidate that failed a gate is not this ball - it is a club edge, a
            // shadow boundary, whatever. Push it well clear of any present/absent
            // threshold instead of leaving it just under the gated-out ceiling, where a
            // caller's threshold could land on the wrong side of it.
            v.ballScore = best.gatesPassed
                    ? best.score
                    : 0.25 * Math.min(best.score, RoundnessScorer.GATED_OUT_SCORE);
            v.drift = Math.hypot(best.cx - lx, best.cy - ly);
            if (best.gatesPassed) {
                // Follow the ball's small wobble, so slow tripod creep does not
                // accumulate into a lost lock.
                v.cx = ox + best.cx;
                v.cy = oy + best.cy;
                v.r = best.r;
            }
        }

        // ---- does the spot look like the empty background again? ----------------
        double amp = RoundnessScorer.amplitude(rm, lx, ly, 0.85 * lockedR);
        if (amp <= 0) {
            // Nothing was even flagged as changed inside the disc: as empty as it gets.
            v.backgroundScore = 1;
        } else {
            v.backgroundScore = 1.0 - Stats.smooth01(amp / Math.max(1e-6, lit.learnedContrast),
                    0.12, 0.45);
        }

        // ---- how much foreign material is around the ball? ----------------------
        int ring = 0, ringTotal = 0;
        double rIn = 1.35 * lockedR, rOut = Math.min(half - 1, 2.4 * lockedR);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double d = Math.hypot(x - lx, y - ly);
                if (d < rIn || d > rOut) continue;
                int i = y * w + x;
                if (!rm.valid[i]) continue;
                ringTotal++;
                if (rm.mask[i]) ring++;
            }
        }
        v.foreignFraction = ringTotal > 0 ? ring / (double) ringTotal : 0;

        // ---- colour corroboration ----------------------------------------------
        ColorModel cm = cal.color;
        if (chroma != null && cm != null && cm.usable()) {
            double[] c = chroma.meanChromaInDisc(lockedCx, lockedCy, 0, 0.72 * lockedR);
            if (c[2] >= 2) {
                v.ballColorScore = cm.ballScore(c[0], c[1]);
                v.backgroundColorScore = cm.backgroundScore(c[0], c[1]);
                // Colour only ever moderates the brightness verdict; it never overrules
                // it, because at 4:2:0 the chroma disc of a 25 px ball is barely 12 px.
                v.backgroundScore = 0.65 * v.backgroundScore + 0.35 * v.backgroundColorScore;
                if (v.ballColorScore < 0.25) v.ballScore *= 0.6;
            }
        }
        return v;
    }
}
