package nl.spheredetect;

/**
 * Similarity transform (uniform scale + rotation + translation) that maps a point
 * of the SAMPLING image into REFERENCE image coordinates.
 *
 * It is parameterised around the two image centres so that the identity
 * (scale=1, angle=0, tx=ty=0) means "the sampling window sits exactly in the
 * middle of the reference image at nominal zoom". That keeps the search space
 * small and physically meaningful:
 *   scale &gt; 1  -> the camera zoomed out / moved away (sample covers more of the scene)
 *   angle      -> device rotation in radians
 *   tx, ty     -> pan in reference pixels
 */
public final class SimilarityTransform {

    public final double scale;
    public final double angle;
    public final double tx;
    public final double ty;

    private final double sampleCx, sampleCy, refCx, refCy;
    private final double m00, m01, m10, m11;

    public SimilarityTransform(double scale, double angle, double tx, double ty,
                              int sampleW, int sampleH, int refW, int refH) {
        this.scale = scale;
        this.angle = angle;
        this.tx = tx;
        this.ty = ty;
        this.sampleCx = (sampleW - 1) / 2.0;
        this.sampleCy = (sampleH - 1) / 2.0;
        this.refCx = (refW - 1) / 2.0;
        this.refCy = (refH - 1) / 2.0;
        double c = scale * Math.cos(angle);
        double s = scale * Math.sin(angle);
        this.m00 = c; this.m01 = -s;
        this.m10 = s; this.m11 = c;
    }

    public double mapX(double xs, double ys) {
        return refCx + m00 * (xs - sampleCx) + m01 * (ys - sampleCy) + tx;
    }

    public double mapY(double xs, double ys) {
        return refCy + m10 * (xs - sampleCx) + m11 * (ys - sampleCy) + ty;
    }

    public double angleDegrees() {
        return Math.toDegrees(angle);
    }

    @Override
    public String toString() {
        return String.format("scale=%.4f angle=%.2fdeg t=(%.2f,%.2f)", scale, angleDegrees(), tx, ty);
    }
}
