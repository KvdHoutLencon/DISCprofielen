package nl.spheredetect;

/** Measured evidence for one circle hypothesis. */
public final class CircleScore {

    public double cx, cy, r;

    /** Longest contiguous stretch of valid circular contour, in degrees (360 = full circle). */
    public double arcDeg;
    /** Fraction of angular bins carrying a valid contour sample. */
    public double coverage;
    /** RMS deviation of the measured contour radius, in pixels. */
    public double radiusRmse;
    /** Mean |cos| between the contour gradient and the radial direction (1 = perfectly radial). */
    public double orientation;
    /** Fraction of the change blob that lies inside 1.25 r of the centre. */
    public double containment;
    /** Measured change amplitude divided by the amplitude learned during calibration. */
    public double contrastRatio;
    /** Sign of the contour step: +1 object brighter than surroundings, -1 darker. */
    public int polarity;
    /**
     * How sharply the outline concentrates at the fitted radius compared with the best
     * competing radius. High for a circle, near 1 for an angular or irregular outline.
     */
    public double consistency;
    /**
     * Largest relative amplitude of the 3rd..6th harmonic of the contour radius: how
     * ANGULAR the outline is. ~0.01 for a real sphere, ~0.15 for a square or triangle.
     * 0 means "not measurable" (too little of the outline visible).
     */
    public double shapeHarmonic;
    /**
     * Relative amplitude of the 2nd harmonic: how OVAL the outline is. A real sphere
     * shows a little of this because its own shading shifts the apparent edge, so it is
     * judged more leniently than {@link #shapeHarmonic}.
     */
    public double ellipticity;

    public double sArc, sRadius, sOrientation, sContainment, sContrast, sConsistency, sShape;
    /** Fused confidence in 0..1. */
    public double score;

    public boolean gatesPassed;
    public String rejectReason = "";

    @Override public String toString() {
        return String.format(java.util.Locale.US,
                "c=(%.1f,%.1f) r=%.2f arc=%.0fdeg cov=%.2f rmse=%.2fpx orient=%.2f contain=%.2f " +
                "contrast=%.2f dom=%.2f angular=%.3f oval=%.3f pol=%+d score=%.3f%s",
                cx, cy, r, arcDeg, coverage, radiusRmse, orientation, containment,
                contrastRatio, consistency, shapeHarmonic, ellipticity, polarity, score,
                gatesPassed ? "" : " REJECT:" + rejectReason);
    }
}
