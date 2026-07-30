package nl.spheredetect;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Detects whether a round object (ball / sphere) is present in a small sampling image,
 * by comparing it against a slightly larger reference image of the same scene.
 *
 * <pre>
 *   SphereDetector det = new SphereDetector(referenceImage, calibration, config);
 *   DetectionResult r = det.detect(samplingImage);
 *   textView.setText(r.label);          // "bal gedetecteerd" / "geen bal gedetecteerd"
 * </pre>
 *
 * Pipeline:
 *   1. align sample -> reference (scale + rotation + translation, robust to the ball
 *      being present in only one of the two images)
 *   2. warp the reference into the sampling frame and remove the illumination
 *      difference with a smooth, robustly fitted gain/offset field
 *   3. threshold the residual against the learned noise floor -> change mask
 *   4. group the change into candidates of plausible size
 *   5. per candidate: gradient-direction circle voting -> centre, radius histogram,
 *      sub-pixel circle fit, then measure how round and how complete the contour is
 *   6. gate + fuse into a single confidence and compare with the calibrated threshold
 *
 * The class is not thread safe (it caches the last transform); use one instance per
 * analysis thread.
 */
public final class SphereDetector {

    private final GrayImage reference;
    private final DetectorConfig cfg;
    private Calibration cal;
    private final Registrar registrar;
    private SimilarityTransform lastTransform;

    public SphereDetector(GrayImage reference, Calibration calibration, DetectorConfig config) {
        this.reference = reference;
        this.cal = calibration;
        this.cfg = config != null ? config : new DetectorConfig();
        this.registrar = new Registrar(reference, this.cfg);
    }

    public void setCalibration(Calibration c) {
        this.cal = c;
    }

    public Calibration getCalibration() {
        return cal;
    }

    /** Forget the cached transform, e.g. after the user re-aimed the camera. */
    public void resetTracking() {
        lastTransform = null;
    }

    /**
     * Aligns a frame without running the rest of the pipeline. The tracker uses this to
     * refresh the alignment now and then while it is only verifying a locked position.
     */
    public Registrar.Result register(GrayImage sample) {
        Registrar.Result reg = registrar.register(sample,
                cfg.reuseLastTransform ? lastTransform : null);
        lastTransform = reg.ok ? reg.transform : null;
        return reg;
    }

    public DetectionResult detect(GrayImage sample) {
        long t0 = System.nanoTime();
        DetectionResult res = new DetectionResult();

        if (cal == null || !cal.valid) {
            res.status = DetectionResult.Status.NOT_CALIBRATED;
            res.label = "geen bal gedetecteerd";
            res.message = "niet gekalibreerd";
            res.millis = (System.nanoTime() - t0) / 1000000L;
            return res;
        }

        Registrar.Result reg = registrar.register(sample,
                cfg.reuseLastTransform ? lastTransform : null);
        res.registration = reg;

        ResidualMap rm;
        if (reg.ok) {
            lastTransform = reg.transform;
            rm = ResidualMap.compute(sample, reference, reg.transform, cfg, cal);
        } else {
            lastTransform = null;
            if (!cfg.allowReferenceFreeFallback) {
                res.status = DetectionResult.Status.REGISTRATION_FAILED;
                res.label = "geen bal gedetecteerd";
                res.message = "referentie en sampling konden niet uitgelijnd worden";
                res.millis = (System.nanoTime() - t0) / 1000000L;
                return res;
            }
            res.message = "uitlijning mislukt: referentievrije terugvalmodus";
            rm = ResidualMap.withoutReference(sample, cfg, cal);
        }

        res.residualSigma = rm.sigma;
        res.residualThreshold = rm.threshold;
        res.illuminationGain = rm.medianGain;
        res.illuminationOffset = rm.medianOffset;
        res.changedPixels = rm.maskCount;
        if (cfg.keepDebugImages) res.debugResidual = rm;

        List<Blobs.Group> groups = candidateGroups(rm, sample.width, sample.height);
        res.candidateGroups = groups.size();

        // Edge gate: contour pixels must belong to the changed area (grown a little,
        // because the contour itself sits at the border of the change) and must carry
        // a gradient of the learned strength.
        boolean[] edge = ImageOps.dilate(rm.mask, rm.width, rm.height, 2);

        List<CircleScore> scores = new ArrayList<CircleScore>();
        for (Blobs.Group g : groups) {
            scores.addAll(scoreGroup(rm, g, edge));
        }
        Collections.sort(scores, new java.util.Comparator<CircleScore>() {
            @Override public int compare(CircleScore a, CircleScore b) {
                return Double.compare(b.score, a.score);
            }
        });
        for (int i = 0; i < Math.min(cfg.reportedCandidates, scores.size()); i++) res.candidates.add(scores.get(i));

        CircleScore best = null;
        for (CircleScore s : scores) {
            if (s.gatesPassed) { best = s; break; }
        }
        if (best == null && !scores.isEmpty()) best = scores.get(0);
        res.best = best;

        if (best != null && best.gatesPassed && best.score >= cal.scoreThreshold) {
            res.ballDetected = true;
            res.status = DetectionResult.Status.BALL;
            res.label = "bal gedetecteerd";
            res.score = best.score;
            res.cx = best.cx;
            res.cy = best.cy;
            res.r = best.r;
        } else {
            res.ballDetected = false;
            res.status = reg.ok ? DetectionResult.Status.NO_BALL : res.status;
            res.label = "geen bal gedetecteerd";
            res.score = best != null ? best.score : 0;
            if (best != null && !best.gatesPassed && res.message.isEmpty()) {
                res.message = best.rejectReason;
            }
        }
        res.millis = (System.nanoTime() - t0) / 1000000L;
        return res;
    }

    // ------------------------------------------------------------------ internals

    private List<Blobs.Group> candidateGroups(ResidualMap rm, int w, int h) {
        List<Blobs.Group> raw = Blobs.label(rm.mask, w, h, cfg.minBlobArea);
        List<Blobs.Group> merged = Blobs.merge(raw, cfg.blobMergeGapFactor * cal.rMax);
        List<Blobs.Group> keep = new ArrayList<Blobs.Group>();

        double discMin = Math.PI * cal.rMin * cal.rMin;
        double minArea = Math.max(cfg.minBlobArea, 0.07 * discMin);   // a thin crescent is small
        double maxArea = cfg.maxAreaFactor * Math.PI * cal.rMax * cal.rMax;
        double maxSide = cfg.maxBBoxFactor * cal.rMax;
        double minSide = 1.1 * cal.rMin;

        for (Blobs.Group g : merged) {
            if (g.area < minArea || g.area > maxArea) continue;
            if (g.longSide() > maxSide || g.longSide() < minSide) continue;
            keep.add(g);
        }
        // Deterministic order, biggest first: helps when several candidates tie.
        Collections.sort(keep, new java.util.Comparator<Blobs.Group>() {
            @Override public int compare(Blobs.Group a, Blobs.Group b) {
                return Integer.compare(b.area, a.area);
            }
        });
        if (keep.size() > 8) keep = new ArrayList<Blobs.Group>(keep.subList(0, 8));
        return keep;
    }

    private List<CircleScore> scoreGroup(ResidualMap rm, Blobs.Group g, boolean[] edge) {
        List<CircleScore> out = new ArrayList<CircleScore>();
        int pad = (int) Math.ceil(0.8 * cal.rMax);
        int x0 = g.minX - pad, y0 = g.minY - pad;
        int x1 = g.maxX + pad, y1 = g.maxY + pad;

        List<CircleHough.Peak> peaks = CircleHough.findPeaks(rm.grad, edge, x0, y0, x1, y1,
                cal.rMin, cal.rMax, cal.edgeThreshold, cfg.houghPeaks);

        for (CircleHough.Peak p : peaks) {
            for (ContourTracer.Trace tr : ContourTracer.traceCandidates(rm, p.cx, p.cy,
                    cal.rMin, cal.rMax, cal.edgeThreshold, cfg.radiiPerCentre)) {
                out.add(RoundnessScorer.evaluate(rm, g, tr, cfg, cal));
            }
        }
        // The blob centroid is always worth a try as well. For a small ball the contour
        // is only a few dozen pixels and may not build a Hough peak at all, and for a
        // crescent the strongest peaks can cluster around the shadow terminator instead
        // of the silhouette. The centroid is a cheap, independent starting point.
        for (ContourTracer.Trace tr : ContourTracer.traceCandidates(rm, g.centroidX,
                g.centroidY, cal.rMin, cal.rMax, cal.edgeThreshold, cfg.radiiPerCentre)) {
            out.add(RoundnessScorer.evaluate(rm, g, tr, cfg, cal));
        }
        return out;
    }
}
