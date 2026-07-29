package nl.spheredetect;

/**
 * Tunables that describe the CAMERA SETUP (how much the view may move) and the
 * decision thresholds. Everything that describes the SCENE (illumination, noise,
 * ball size) lives in {@link Calibration} instead, because that part is learned.
 *
 * The defaults are tuned for a ~250x250 sampling window on a fairly stable mount.
 */
public final class DetectorConfig {

    // ---- registration search space -----------------------------------------

    /** Maximum zoom factor in either direction (1.15 means 0.87x .. 1.15x). */
    public double maxZoomFactor = 1.15;
    /** Maximum rotation of the sample relative to the reference, in degrees. */
    public double maxRotationDeg = 12.0;
    /** Maximum translation in reference pixels. Keep this near (refSize - sampleSize)/2. */
    public double maxShiftPx = 28.0;

    /** Window radius (px) for local contrast normalisation used during matching. */
    public int normalizeRadius = 10;
    /** Extra pyramid levels above full resolution (3 -> up to 1/8 scale). */
    public int maxPyramidLevels = 3;
    /** How many coarse hypotheses get refined. More = slower but more robust. */
    public int hypothesesToRefine = 8;

    /** Registration is rejected above this robust cost. */
    public double maxRegistrationCost = 0.62;
    /** Registration is rejected when less than this fraction of the sample lands inside the reference. */
    public double minRegistrationCoverage = 0.85;
    /** Reuse the previous frame's transform as a seed (fast path for a fixed camera). */
    public boolean reuseLastTransform = true;

    // ---- difference map -----------------------------------------------------

    /** Residual threshold = max(calibration floor, kSigma * robust sigma of the residual). */
    public double kSigma = 4.5;
    /** Illumination is fitted per tile; tile size is max(this, 4 * rMax) pixels. */
    public int minIlluminationTile = 48;
    /** Discard change blobs smaller than this many pixels (sensor noise). */
    public int minBlobArea = 6;

    // ---- candidate geometry -------------------------------------------------

    /** Blobs closer than this fraction of rMax are merged (a crescent can split up). */
    public double blobMergeGapFactor = 0.6;
    /** Reject a blob group whose longest bbox side exceeds this multiple of rMax. */
    public double maxBBoxFactor = 2.9;
    /** A blob group may not contain more change pixels than this multiple of its disc area. */
    public double maxAreaFactor = 1.8;

    // ---- circle verification ------------------------------------------------

    /** Number of Hough centre peaks that get fully scored. */
    public int houghPeaks = 4;
    /**
     * How many concentric radii per centre are tried. At least 2 is needed to cover the
     * silhouette plus the brightness ridge that a strongly shaded sphere shows just
     * inside it; a third also covers a shadow terminator sitting between them.
     */
    public int radiiPerCentre = 3;
    /**
     * Absolute floor on the contiguous arc (degrees) of circular contour. Far below 360
     * because a partially lit sphere only reveals part of its outline; how far below is
     * decided per installation by {@link Calibration#minArcDeg}, which calibration sets
     * from what the user says about the lighting.
     */
    public double minArcDeg = 70.0;
    /**
     * Radius scatter gate: rmse of the measured contour radius, relative to r. Contour
     * points are localised sub-pixel, so a real circle stays far below this even at
     * r = 6 px, while a square of the same size lands around 0.13.
     */
    public double maxRadiusRmseRel = 0.115;
    /** Absolute floor for the radius scatter gate, in pixels (sampling noise). */
    public double maxRadiusRmseAbsPx = 0.75;
    /**
     * Roundness gate on ANGULARITY: maximum relative amplitude of the 3rd..6th angular
     * harmonic of the contour radius. A square lands near 0.15, a triangle higher still,
     * a real sphere near 0.01.
     */
    public double maxShapeHarmonic = 0.055;
    /**
     * Small objects get a proportionally looser angularity gate: the effective limit is
     * max(maxShapeHarmonic, this / r). Edge positions carry roughly the same absolute
     * uncertainty at any size, so relative to the radius they get noisier as the object
     * shrinks - at r = 6 px one is simply not entitled to judge angularity as finely as
     * at r = 15 px. Consequence worth knowing: telling a small ball from a small square
     * is inherently less certain, so aim for at least 20 px across.
     */
    public double shapeHarmonicRadiusSlack = 0.58;
    /**
     * Roundness gate on OVALNESS (2nd harmonic). Looser than the angularity gate,
     * because a real sphere's own shading shifts its apparent edge a little.
     */
    public double maxEllipticity = 0.115;
    /** The harmonic test needs this much visible outline (degrees) to be meaningful. */
    public double harmonicMinArcDeg = 260;
    /** Minimum mean agreement between edge gradient direction and the radial direction. */
    public double minOrientation = 0.50;
    /** Minimum fraction of the change blob that lies inside 1.25*r of the fitted centre. */
    public double minContainment = 0.60;
    /** Minimum measured/learned contrast ratio. */
    public double minContrastRatio = 0.30;
    /**
     * Sanity gate on radius dominance: how much more contour support the fitted radius
     * must have than the best clearly different radius. Deliberately weak, because a
     * shaded sphere legitimately shows TWO concentric circular features - its silhouette
     * and the ridge where its own brightness falls off steepest - so a high value here
     * would reject real balls. Dominance mostly contributes to the confidence score.
     */
    public double minRadiusDominance = 1.05;
    /** Dominance at which the confidence contribution saturates. */
    public double goodRadiusDominance = 1.8;

    /** Fall back to a reference-free search when registration fails (riskier). */
    public boolean allowReferenceFreeFallback = false;

    /** How many scored candidates are reported back in the result (diagnostics). */
    public int reportedCandidates = 5;

    /** Keep intermediate images in the result for debugging/overlay drawing. */
    public boolean keepDebugImages = false;

    public DetectorConfig copy() {
        DetectorConfig c = new DetectorConfig();
        c.maxZoomFactor = maxZoomFactor;
        c.maxRotationDeg = maxRotationDeg;
        c.maxShiftPx = maxShiftPx;
        c.normalizeRadius = normalizeRadius;
        c.maxPyramidLevels = maxPyramidLevels;
        c.hypothesesToRefine = hypothesesToRefine;
        c.maxRegistrationCost = maxRegistrationCost;
        c.minRegistrationCoverage = minRegistrationCoverage;
        c.reuseLastTransform = reuseLastTransform;
        c.kSigma = kSigma;
        c.minIlluminationTile = minIlluminationTile;
        c.minBlobArea = minBlobArea;
        c.blobMergeGapFactor = blobMergeGapFactor;
        c.maxBBoxFactor = maxBBoxFactor;
        c.maxAreaFactor = maxAreaFactor;
        c.houghPeaks = houghPeaks;
        c.radiiPerCentre = radiiPerCentre;
        c.minArcDeg = minArcDeg;
        c.maxRadiusRmseRel = maxRadiusRmseRel;
        c.maxRadiusRmseAbsPx = maxRadiusRmseAbsPx;
        c.maxShapeHarmonic = maxShapeHarmonic;
        c.shapeHarmonicRadiusSlack = shapeHarmonicRadiusSlack;
        c.maxEllipticity = maxEllipticity;
        c.harmonicMinArcDeg = harmonicMinArcDeg;
        c.minOrientation = minOrientation;
        c.minContainment = minContainment;
        c.minContrastRatio = minContrastRatio;
        c.minRadiusDominance = minRadiusDominance;
        c.goodRadiusDominance = goodRadiusDominance;
        c.allowReferenceFreeFallback = allowReferenceFreeFallback;
        c.reportedCandidates = reportedCandidates;
        c.keepDebugImages = keepDebugImages;
        return c;
    }
}
