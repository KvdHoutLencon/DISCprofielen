package nl.spheredetect;

import java.util.Properties;

/**
 * Everything the detector LEARNS during the calibration procedure. Persist this
 * (SharedPreferences, a file, ...) and hand it back to the detector on startup.
 *
 * <ul>
 *   <li>{@link #rMin}/{@link #rMax} - expected ball radius window (user given size + margin)</li>
 *   <li>{@link #residualFloor} - how big an intensity difference must be before it counts
 *       as "something changed"; derived from the sensor noise of the calibration frames</li>
 *   <li>{@link #edgeThreshold} - how strong a radial edge must be to count as ball contour;
 *       derived from the actual contour strength measured on the calibration ball</li>
 *   <li>{@link #learnedContrast} - typical intensity difference the ball produces</li>
 *   <li>{@link #polarity} - +1 when the ball reads brighter than the background, -1 darker</li>
 *   <li>{@link #scoreThreshold} - decision threshold, set between the score of the
 *       positive calibration frame and the score of the ball-free frames</li>
 * </ul>
 */
public final class Calibration {

    public double rMin = 8;
    public double rMax = 18;
    public double rNominal = 12.5;

    public double noiseSigma = 2.0;
    public double residualFloor = 8.0;

    public double edgeThreshold = 6.0;
    public double learnedContrast = 30.0;

    public int polarity = -1;
    /** Contiguous contour arc measured on the calibration ball (degrees). */
    public double calibratedArcDeg = 360;
    /** Minimum arc required at detection time (relaxed when the calibration ball was a crescent). */
    public double minArcDeg = 95;

    public double scoreThreshold = 0.55;

    /** Score obtained on the positive calibration frame (diagnostics). */
    public double positiveScore = 0;
    /** Best score obtained on the ball-free frame(s) (diagnostics). */
    public double negativeScore = 0;

    public boolean valid = false;
    public String notes = "";

    public double radiusMargin() {
        return (rMax - rMin) / 2.0;
    }

    // ------------------------------------------------------------- persistence

    public Properties toProperties() {
        Properties p = new Properties();
        p.setProperty("rMin", Double.toString(rMin));
        p.setProperty("rMax", Double.toString(rMax));
        p.setProperty("rNominal", Double.toString(rNominal));
        p.setProperty("noiseSigma", Double.toString(noiseSigma));
        p.setProperty("residualFloor", Double.toString(residualFloor));
        p.setProperty("edgeThreshold", Double.toString(edgeThreshold));
        p.setProperty("learnedContrast", Double.toString(learnedContrast));
        p.setProperty("polarity", Integer.toString(polarity));
        p.setProperty("calibratedArcDeg", Double.toString(calibratedArcDeg));
        p.setProperty("minArcDeg", Double.toString(minArcDeg));
        p.setProperty("scoreThreshold", Double.toString(scoreThreshold));
        p.setProperty("positiveScore", Double.toString(positiveScore));
        p.setProperty("negativeScore", Double.toString(negativeScore));
        p.setProperty("valid", Boolean.toString(valid));
        p.setProperty("notes", notes == null ? "" : notes);
        return p;
    }

    public static Calibration fromProperties(Properties p) {
        Calibration c = new Calibration();
        c.rMin = d(p, "rMin", c.rMin);
        c.rMax = d(p, "rMax", c.rMax);
        c.rNominal = d(p, "rNominal", c.rNominal);
        c.noiseSigma = d(p, "noiseSigma", c.noiseSigma);
        c.residualFloor = d(p, "residualFloor", c.residualFloor);
        c.edgeThreshold = d(p, "edgeThreshold", c.edgeThreshold);
        c.learnedContrast = d(p, "learnedContrast", c.learnedContrast);
        c.polarity = (int) d(p, "polarity", c.polarity);
        c.calibratedArcDeg = d(p, "calibratedArcDeg", c.calibratedArcDeg);
        c.minArcDeg = d(p, "minArcDeg", c.minArcDeg);
        c.scoreThreshold = d(p, "scoreThreshold", c.scoreThreshold);
        c.positiveScore = d(p, "positiveScore", c.positiveScore);
        c.negativeScore = d(p, "negativeScore", c.negativeScore);
        c.valid = Boolean.parseBoolean(p.getProperty("valid", "false"));
        c.notes = p.getProperty("notes", "");
        return c;
    }

    private static double d(Properties p, String k, double def) {
        String s = p.getProperty(k);
        if (s == null) return def;
        try { return Double.parseDouble(s); } catch (NumberFormatException e) { return def; }
    }

    /** Flat JSON, handy for logging or for storing as a single string. */
    public String toJson() {
        StringBuilder sb = new StringBuilder(384);
        sb.append('{');
        sb.append("\"rMin\":").append(fmt(rMin)).append(',');
        sb.append("\"rMax\":").append(fmt(rMax)).append(',');
        sb.append("\"rNominal\":").append(fmt(rNominal)).append(',');
        sb.append("\"noiseSigma\":").append(fmt(noiseSigma)).append(',');
        sb.append("\"residualFloor\":").append(fmt(residualFloor)).append(',');
        sb.append("\"edgeThreshold\":").append(fmt(edgeThreshold)).append(',');
        sb.append("\"learnedContrast\":").append(fmt(learnedContrast)).append(',');
        sb.append("\"polarity\":").append(polarity).append(',');
        sb.append("\"calibratedArcDeg\":").append(fmt(calibratedArcDeg)).append(',');
        sb.append("\"minArcDeg\":").append(fmt(minArcDeg)).append(',');
        sb.append("\"scoreThreshold\":").append(fmt(scoreThreshold)).append(',');
        sb.append("\"positiveScore\":").append(fmt(positiveScore)).append(',');
        sb.append("\"negativeScore\":").append(fmt(negativeScore)).append(',');
        sb.append("\"valid\":").append(valid).append(',');
        sb.append("\"notes\":\"").append(notes == null ? "" : notes.replace("\"", "'")).append('"');
        sb.append('}');
        return sb.toString();
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.US, "%.4f", v);
    }

    @Override public String toString() {
        return toJson();
    }
}
