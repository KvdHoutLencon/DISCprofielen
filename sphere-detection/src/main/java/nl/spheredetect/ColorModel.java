package nl.spheredetect;

import java.util.Properties;

/**
 * What colour the ball is and what colour the background is, learned during calibration.
 *
 * Deliberately small: two points in the (U,V) chroma plane and the distance between them.
 * That is all the resolution chroma can honestly support for a 25 px object in a 4:2:0
 * frame, and it is enough for the two questions worth asking:
 *
 *   "does this patch still look like the ball?"      -> ballScore
 *   "does this patch look like the empty background?" -> backgroundScore
 *
 * The second question is the important one. Telling a LAUNCHED ball apart from a
 * temporarily COVERED one comes down to what is left behind: after a launch the spot goes
 * back to looking like grass, while under a club head it looks like something else. In
 * brightness alone that distinction is unreliable, because a shadow also darkens the spot
 * and a shadowed patch of grass is not obviously "grass" any more. In chroma it stays
 * green, so the discrimination survives the moving shadows of a golf swing.
 *
 * {@link #separation} records how far apart the two colours actually were. When ball and
 * background are colour-wise alike - a white ball on pale concrete, or a monochrome scene
 * - it comes out low and {@link #usable()} turns false, after which the tracker quietly
 * falls back to brightness only. Colour is a corroborating signal here, never a
 * requirement.
 */
public final class ColorModel {

    /** Mean chroma of the ball, centred on neutral grey (0,0). */
    public double ballU, ballV;
    /** Mean chroma of the background right around the ball. */
    public double bgU, bgV;
    /** Distance between the two in the (U,V) plane. */
    public double separation;
    /** Spread of the background chroma; sets how strict the background match may be. */
    public double bgSpread = 6;

    public boolean valid;

    /** Below this separation colour cannot discriminate and is ignored altogether. */
    public static final double MIN_SEPARATION = 8.0;

    public boolean usable() {
        return valid && separation >= MIN_SEPARATION;
    }

    /** Scale on which a chroma difference is judged: a fraction of what was learned. */
    private double tolerance() {
        return Math.max(4.0, 0.45 * separation);
    }

    /** 1 when this chroma matches the ball, 0 when it is a tolerance or more away. */
    public double ballScore(double u, double v) {
        if (!usable()) return -1;
        double d = Math.hypot(u - ballU, v - ballV);
        return 1.0 - Stats.smooth01(d, 0.5 * tolerance(), 1.6 * tolerance());
    }

    /** 1 when this chroma matches the learned background, 0 when it is clearly something else. */
    public double backgroundScore(double u, double v) {
        if (!usable()) return -1;
        double d = Math.hypot(u - bgU, v - bgV);
        double tol = Math.max(bgSpread * 1.5, 0.45 * separation);
        return 1.0 - Stats.smooth01(d, 0.6 * tol, 1.7 * tol);
    }

    /**
     * Learns both colours from a calibration frame in which the ball sits at (cx, cy, r).
     * The background is sampled from a ring just outside the ball rather than from the
     * whole frame, so it describes the surface the ball is actually lying on.
     */
    public static ColorModel learn(ChromaPlanes chroma, double cx, double cy, double r) {
        ColorModel m = new ColorModel();
        if (chroma == null || r <= 0) return m;

        double[] ball = chroma.meanChromaInDisc(cx, cy, 0, 0.72 * r);
        double[] bg = chroma.meanChromaInDisc(cx, cy, 1.6 * r, 3.0 * r);
        if (ball[2] < 3 || bg[2] < 8) return m;

        m.ballU = ball[0];
        m.ballV = ball[1];
        m.bgU = bg[0];
        m.bgV = bg[1];
        m.separation = Math.hypot(m.ballU - m.bgU, m.ballV - m.bgV);
        m.bgSpread = 6;
        m.valid = true;
        return m;
    }

    /** Human readable, for the calibration screen. */
    public String describe() {
        if (!valid) return "geen kleurinformatie geleerd";
        String s = String.format(java.util.Locale.US,
                "bal chroma (%.0f,%.0f), achtergrond (%.0f,%.0f), afstand %.0f",
                ballU, ballV, bgU, bgV, separation);
        return usable()
                ? s + " - kleur wordt als extra bevestiging gebruikt"
                : s + " - te klein verschil, kleur wordt genegeerd";
    }

    public void save(Properties p) {
        p.setProperty("color.valid", Boolean.toString(valid));
        p.setProperty("color.ballU", Double.toString(ballU));
        p.setProperty("color.ballV", Double.toString(ballV));
        p.setProperty("color.bgU", Double.toString(bgU));
        p.setProperty("color.bgV", Double.toString(bgV));
        p.setProperty("color.separation", Double.toString(separation));
        p.setProperty("color.bgSpread", Double.toString(bgSpread));
    }

    public static ColorModel load(Properties p) {
        ColorModel m = new ColorModel();
        m.valid = Boolean.parseBoolean(p.getProperty("color.valid", "false"));
        m.ballU = d(p, "color.ballU", 0);
        m.ballV = d(p, "color.ballV", 0);
        m.bgU = d(p, "color.bgU", 0);
        m.bgV = d(p, "color.bgV", 0);
        m.separation = d(p, "color.separation", 0);
        m.bgSpread = d(p, "color.bgSpread", 6);
        return m;
    }

    private static double d(Properties p, String k, double def) {
        String s = p.getProperty(k);
        if (s == null) return def;
        try { return Double.parseDouble(s); } catch (NumberFormatException e) { return def; }
    }

    @Override public String toString() {
        return describe();
    }
}
