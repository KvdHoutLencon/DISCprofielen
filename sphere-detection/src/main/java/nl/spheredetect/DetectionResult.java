package nl.spheredetect;

import java.util.ArrayList;
import java.util.List;

/** Outcome of one detection run. */
public final class DetectionResult {

    public enum Status {
        /** A round object of the expected size was found. */
        BALL,
        /** Everything worked, nothing round of the right size is present. */
        NO_BALL,
        /** The sampling image could not be aligned with the reference (camera moved too far,
         *  scene changed completely, out of focus). Treated as "no ball" unless you decide
         *  otherwise; the app should normally ask the user to re-aim or re-calibrate. */
        REGISTRATION_FAILED,
        /** No calibration loaded. */
        NOT_CALIBRATED
    }

    public Status status = Status.NO_BALL;

    public boolean ballDetected;
    /** Dutch label, ready to display. */
    public String label = "geen bal gedetecteerd";

    /** Confidence of the winning hypothesis, 0..1. */
    public double score;
    /** Circle in SAMPLING image coordinates (sub-pixel). Only meaningful when ballDetected. */
    public double cx, cy, r;

    public CircleScore best;
    public final List<CircleScore> candidates = new ArrayList<CircleScore>();

    public Registrar.Result registration;
    /** Robust sigma of the residual: the effective noise floor of this frame. */
    public double residualSigma;
    /** Threshold that was applied to the residual. */
    public double residualThreshold;
    /** Illumination change that had to be compensated (1.0 / 0.0 = none). */
    public double illuminationGain = 1;
    public double illuminationOffset = 0;
    public int changedPixels;
    public int candidateGroups;

    public long millis;
    public String message = "";

    /** Set for debugging/overlay drawing when {@link DetectorConfig#keepDebugImages} is on. */
    public ResidualMap debugResidual;

    @Override public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(label).append(" [").append(status).append("]");
        sb.append(String.format(java.util.Locale.US, " score=%.3f", score));
        if (ballDetected) sb.append(String.format(java.util.Locale.US, " at (%.1f,%.1f) r=%.2f", cx, cy, r));
        sb.append(String.format(java.util.Locale.US, " sigma=%.2f thr=%.1f gain=%.3f off=%.1f changed=%d groups=%d %dms",
                residualSigma, residualThreshold, illuminationGain, illuminationOffset,
                changedPixels, candidateGroups, millis));
        if (registration != null) sb.append(" | reg: ").append(registration);
        if (!message.isEmpty()) sb.append(" | ").append(message);
        return sb.toString();
    }
}
