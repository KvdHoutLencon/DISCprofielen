package nl.spheredetect;

import java.util.Random;

/**
 * Self contained validation of the detector on synthetic scenes. No test framework
 * needed: {@code java nl.spheredetect.SyntheticSelfTest}.
 *
 * It builds a textured reference image, then renders sampling images out of it with a
 * known shift/zoom/rotation, added sensor noise and an illumination change, and places
 * objects in them:
 *
 *   - a normally lit sphere            -> must be detected
 *   - a back-lit sphere (crescent, the rest of the object indistinguishable from the
 *     background)                      -> must be detected
 *   - a small sphere at the size limit -> must be detected
 *   - nothing at all                   -> must NOT be detected
 *   - a square, a bar, a triangle      -> must NOT be detected (not round)
 *   - a sphere far outside the
 *     calibrated size window           -> must NOT be detected
 */
public final class SyntheticSelfTest {

    private static final int REF = 320;
    private static final int SAMP = 250;

    static boolean DEBUG = Boolean.getBoolean("disc.debug");

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        GrayImage reference = buildReference(REF, REF, 1234);

        // ---------------- calibration -------------------------------------------
        // The user places the ball, shoots a sample and marks it. The mark is
        // deliberately sloppy (2 px off centre, radius 13 instead of 12).
        Scene calScene = new Scene(1.01, 2.0, 4, -3, 1.0, 0.0, 1.4, 4242);
        GrayImage calPos = calScene.render(reference, new Sphere(126, 118, 12, LIT, false));
        GrayImage calNeg = calScene.render(reference, null);

        DetectorConfig cfg = new DetectorConfig();
        cfg.maxRotationDeg = 8;
        cfg.maxZoomFactor = 1.08;
        cfg.maxShiftPx = 20;
        if (DEBUG) cfg.reportedCandidates = 12;

        Calibrator.Input ci = new Calibrator.Input();
        ci.reference = reference;
        ci.positive = calPos;
        ci.negative = calNeg;
        ci.userCx = 128;
        ci.userCy = 116;
        ci.userRadius = 13;
        ci.radiusMarginFraction = 0.35;

        long t0 = System.currentTimeMillis();
        Calibrator.Output cal = Calibrator.calibrate(ci, cfg);
        long calMs = System.currentTimeMillis() - t0;

        System.out.println("=== KALIBRATIE (" + calMs + " ms) ===");
        System.out.println(cal.message);
        System.out.println("  gefit: c=(" + f(cal.fittedCx) + "," + f(cal.fittedCy) + ") r=" + f(cal.fittedRadius));
        System.out.println("  bewijs: " + cal.positiveEvidence);
        System.out.println("  " + cal.calibration.toJson());
        System.out.println("  registratie: " + cal.registration);
        System.out.println();
        if (!cal.ok) {
            System.out.println("KALIBRATIE MISLUKT - rest van de test wordt overgeslagen");
            System.exit(1);
        }

        SphereDetector det = new SphereDetector(reference, cal.calibration, cfg);

        // ---------------- detection cases ---------------------------------------
        // Each case uses a different camera pose, noise seed and illumination change.

        check(det, "bal, zelfde opstelling", true,
                new Scene(1.00, 0.0, 0, 0, 1.0, 0.0, 1.4, 11).render(reference,
                        new Sphere(120, 130, 12, LIT, false)));

        check(det, "bal, geschoven+geroteerd+gezoomd", true,
                new Scene(1.045, -5.5, -12, 9, 1.0, 0.0, 1.4, 12).render(reference,
                        new Sphere(150, 96, 12, LIT, false)));

        check(det, "bal, helderheid +25% en offset -18", true,
                new Scene(1.02, 3.0, 6, -7, 1.25, -18, 1.4, 13).render(reference,
                        new Sphere(96, 160, 12.5, LIT, false)));

        check(det, "bal, kleiner (r=9, binnen marge)", true,
                new Scene(1.0, 1.0, 3, 2, 1.0, 0.0, 1.4, 14).render(reference,
                        new Sphere(140, 140, 9, LIT, false)));

        check(det, "bal, groter (r=16, binnen marge)", true,
                new Scene(1.0, -2.0, -4, 5, 1.0, 0.0, 1.4, 15).render(reference,
                        new Sphere(115, 125, 16, LIT, false)));

        check(det, "tegenlicht: alleen maanvormige rand zichtbaar", true,
                new Scene(1.01, 2.5, 5, -4, 1.0, 0.0, 1.4, 16).render(reference,
                        new Sphere(130, 120, 12, BACKLIT, true)));

        check(det, "tegenlicht: maanvorm, andere hoek", true,
                new Scene(1.0, -3.0, -6, 6, 0.9, 8, 1.4, 17).render(reference,
                        new Sphere(118, 140, 13, BACKLIT_2, true)));

        check(det, "bal, veel ruis (sigma 4)", true,
                new Scene(1.0, 1.5, 2, -2, 1.0, 0.0, 4.0, 18).render(reference,
                        new Sphere(122, 132, 12, LIT, false)));

        check(det, "leeg beeld", false,
                new Scene(1.0, 0.0, 0, 0, 1.0, 0.0, 1.4, 21).render(reference, null));

        check(det, "leeg beeld, verschoven+gezoomd+geroteerd", false,
                new Scene(1.05, -6.0, 14, -11, 1.0, 0.0, 1.4, 22).render(reference, null));

        check(det, "leeg beeld, andere belichting", false,
                new Scene(1.01, 2.0, 4, 4, 1.3, -25, 1.4, 23).render(reference, null));

        check(det, "leeg beeld, veel ruis (sigma 5)", false,
                new Scene(1.0, 0.5, 1, 1, 1.0, 0.0, 5.0, 24).render(reference, null));

        check(det, "vierkant 24x24 (niet rond)", false,
                new Scene(1.0, 1.0, 2, 2, 1.0, 0.0, 1.4, 25).render(reference,
                        new Rect(120, 130, 24, 24, -70)));

        check(det, "balk 40x12 (niet rond)", false,
                new Scene(1.0, 1.0, 2, 2, 1.0, 0.0, 1.4, 26).render(reference,
                        new Rect(120, 130, 40, 12, -70)));

        check(det, "driehoek (niet rond)", false,
                new Scene(1.0, 1.0, 2, 2, 1.0, 0.0, 1.4, 27).render(reference,
                        new Triangle(120, 130, 15, -70)));

        check(det, "bal veel te klein (r=4)", false,
                new Scene(1.0, 1.0, 2, 2, 1.0, 0.0, 1.4, 28).render(reference,
                        new Sphere(120, 130, 4, LIT, false)));

        check(det, "bal veel te groot (r=28)", false,
                new Scene(1.0, 1.0, 2, 2, 1.0, 0.0, 1.4, 29).render(reference,
                        new Sphere(120, 130, 28, LIT, false)));

        check(det, "sterke schaduw over de scene (niet rond)", false,
                shadow(new Scene(1.0, 1.0, 2, 2, 1.0, 0.0, 1.4, 30).render(reference, null)));

        System.out.println();
        System.out.println("=== RESULTAAT: " + passed + " geslaagd, " + failed + " mislukt ===");

        // --------- second calibration: very small ball (25x25 px object) ---------
        System.out.println();
        System.out.println("=== TWEEDE KALIBRATIE: kleine bol, r=6.5 ===");
        Scene s2 = new Scene(1.0, 1.0, 3, -2, 1.0, 0.0, 1.4, 777);
        Calibrator.Input ci2 = new Calibrator.Input();
        ci2.reference = reference;
        ci2.positive = s2.render(reference, new Sphere(130, 125, 6.5, LIT, false));
        ci2.negative = s2.render(reference, null);
        ci2.userCx = 130;
        ci2.userCy = 125;
        ci2.userRadius = 7;
        ci2.radiusMarginFraction = 0.35;
        Calibrator.Output cal2 = Calibrator.calibrate(ci2, cfg);
        System.out.println(cal2.message);
        System.out.println("  " + cal2.calibration.toJson());
        if (cal2.ok) {
            SphereDetector det2 = new SphereDetector(reference, cal2.calibration, cfg);
            check(det2, "kleine bol r=6.5 elders", true,
                    new Scene(1.02, -2.0, -5, 4, 1.0, 0.0, 1.4, 31).render(reference,
                            new Sphere(112, 148, 6.5, LIT, false)));
            check(det2, "kleine bol r=8", true,
                    new Scene(1.0, 2.0, 3, 3, 1.1, -8, 1.4, 32).render(reference,
                            new Sphere(140, 110, 8, LIT, false)));
            check(det2, "klein vierkant 13x13", false,
                    new Scene(1.0, 1.0, 2, 2, 1.0, 0.0, 1.4, 33).render(reference,
                            new Rect(120, 130, 13, 13, -70)));
            check(det2, "leeg beeld (kleine kalibratie)", false,
                    new Scene(1.0, 1.0, 2, 2, 1.0, 0.0, 1.4, 34).render(reference, null));
        } else {
            failed++;
        }

        if (DEBUG) {
            // What does the tracer see on the small calibration ball itself?
            GrayImage smallPos = s2.render(reference, new Sphere(130, 125, 6.5, LIT, false));
            Calibration boot = new Calibration();
            boot.rMin = 4.5; boot.rMax = 10.2; boot.residualFloor = 2.0;
            boot.edgeThreshold = 9.0; boot.learnedContrast = 80; boot.valid = true;
            dumpFan(reference, boot, cfg, smallPos, 130, 125, 7);
        }
        if (DEBUG && cal2.ok) {
            GrayImage sq = new Scene(1.0, 1.0, 2, 2, 1.0, 0.0, 1.4, 33).render(reference,
                    new Rect(120, 130, 13, 13, -70));
            dumpFan(reference, cal2.calibration, cfg, sq, 119.5, 129.5, 8.97);
        }

        benchmark(reference, cal.calibration, cfg);

        System.out.println();
        System.out.println("=== TOTAAL: " + passed + " geslaagd, " + failed + " mislukt ===");
        if (failed > 0) System.exit(1);
    }

    /**
     * Timing. Two regimes matter: the first frame after aiming the camera, which has to
     * search the whole pose space, and every frame after it, which starts from the
     * previous frame's alignment - the normal case for a camera that stays put.
     */
    private static void benchmark(GrayImage reference, Calibration cal, DetectorConfig cfg) {
        GrayImage withBall = new Scene(1.01, 2.0, 4, -3, 1.0, 0.0, 1.4, 99)
                .render(reference, new Sphere(124, 126, 12, LIT, false));
        SphereDetector det = new SphereDetector(reference, cal, cfg);
        for (int i = 0; i < 12; i++) { det.resetTracking(); det.detect(withBall); }   // warm up

        long coldSum = 0;
        int coldN = 10;
        for (int i = 0; i < coldN; i++) {
            det.resetTracking();
            long t = System.nanoTime();
            det.detect(withBall);
            coldSum += System.nanoTime() - t;
        }
        det.resetTracking();
        det.detect(withBall);
        long warmSum = 0;
        int warmN = 40;
        for (int i = 0; i < warmN; i++) {
            long t = System.nanoTime();
            det.detect(withBall);
            warmSum += System.nanoTime() - t;
        }
        System.out.println();
        System.out.println(String.format(java.util.Locale.US,
                "=== SNELHEID (250x250 sampling, 320x320 referentie) ===%n" +
                "  eerste beeld (volledige zoektocht): %.1f ms%n" +
                "  daarna (uitlijning hergebruikt):    %.1f ms",
                coldSum / 1e6 / coldN, warmSum / 1e6 / warmN));
    }

    // ------------------------------------------------------------------ checking

    private static void check(SphereDetector det, String name, boolean expectBall, GrayImage sample) {
        det.resetTracking();
        DetectionResult r = det.detect(sample);
        boolean ok = (r.ballDetected == expectBall);
        if (ok) passed++; else failed++;
        CircleScore bs = r.best;
        System.out.println(String.format(java.util.Locale.US,
                "%-6s %-42s verwacht=%-4s -> %-4s r=%5s rmse=%5s hoek=%5s ovaal=%5s arc=%4s cons=%4s contr=%4s score=%5s",
                ok ? "[OK]" : "[FOUT]", name, expectBall ? "BAL" : "leeg",
                r.ballDetected ? "BAL" : "leeg",
                bs == null ? "-" : String.format(java.util.Locale.US, "%.2f", bs.r),
                bs == null ? "-" : String.format(java.util.Locale.US, "%.2f", bs.radiusRmse),
                bs == null ? "-" : String.format(java.util.Locale.US, "%.3f", bs.shapeHarmonic),
                bs == null ? "-" : String.format(java.util.Locale.US, "%.3f", bs.ellipticity),
                bs == null ? "-" : String.format(java.util.Locale.US, "%.0f", bs.arcDeg),
                bs == null ? "-" : String.format(java.util.Locale.US, "%.2f", bs.consistency),
                bs == null ? "-" : String.format(java.util.Locale.US, "%.2f", bs.contrastRatio),
                String.format(java.util.Locale.US, "%.3f", r.score)));
        if (false) System.out.println((ok ? "[OK]   " : "[FOUT] ") + name);
        if (DEBUG) System.out.println("         -> " + r);
        if (!ok || DEBUG) {
            for (CircleScore c : r.candidates) System.out.println("         kand: " + c);
        }
    }

    /** Prints the traced outline radius per angle, to see what the tracer actually locks onto. */
    private static void dumpFan(GrayImage reference, Calibration cal, DetectorConfig cfg,
                                GrayImage sample, double cx, double cy, double r) {
        Registrar reg = new Registrar(reference, cfg);
        Registrar.Result rr = reg.register(sample, null);
        ResidualMap rm = ResidualMap.compute(sample, reference, rr.transform, cfg, cal);
        System.out.println("  fan dump: edgeThr=" + f(cal.edgeThreshold) + " sigma=" + f(rm.sigma)
                + " thr=" + f(rm.threshold) + " rMin=" + f(cal.rMin) + " rMax=" + f(cal.rMax));
        ContourTracer.Trace tr = ContourTracer.trace(rm, cx, cy, r, cal.rMin, cal.rMax, cal.edgeThreshold);
        System.out.println("  trace c=(" + f(tr.cx) + "," + f(tr.cy) + ") r=" + f(tr.r)
                + " bins=" + tr.bins + " pts=" + tr.contourPoints);
        StringBuilder sb = new StringBuilder("  rEdge per 15deg: ");
        for (int b = 0; b < tr.bins; b++) {
            double ang = 360.0 * b / tr.bins;
            if (ang % 15 > 360.0 / tr.bins) continue;
            sb.append((int) ang).append("=").append(tr.have[b] ? f(tr.rEdge[b]) : "-").append(" ");
        }
        System.out.println(sb);
    }

    // ------------------------------------------------------------------ rendering

    /** Camera pose + illumination + noise for one synthetic sampling frame. */
    static final class Scene {
        final double scale, angleDeg, tx, ty, gain, offset, noise;
        final long seed;

        Scene(double scale, double angleDeg, double tx, double ty,
              double gain, double offset, double noise, long seed) {
            this.scale = scale; this.angleDeg = angleDeg; this.tx = tx; this.ty = ty;
            this.gain = gain; this.offset = offset; this.noise = noise; this.seed = seed;
        }

        GrayImage render(GrayImage ref, Shape shape) {
            SimilarityTransform t = new SimilarityTransform(scale, Math.toRadians(angleDeg),
                    tx, ty, SAMP, SAMP, ref.width, ref.height);
            GrayImage out = new GrayImage(SAMP, SAMP);
            for (int y = 0; y < SAMP; y++) {
                for (int x = 0; x < SAMP; x++) {
                    double rx = t.mapX(x, y), ry = t.mapY(x, y);
                    out.data[y * SAMP + x] = ref.bilinear(rx, ry);
                }
            }
            if (shape != null) shape.draw(out);
            Random rnd = new Random(seed);
            for (int i = 0; i < SAMP * SAMP; i++) {
                double v = out.data[i] * gain + offset + rnd.nextGaussian() * noise;
                out.data[i] = (float) Stats.clamp(v, 0, 255);
            }
            return out;
        }
    }

    interface Shape {
        void draw(GrayImage img);
    }

    static final double[] LIT = norm(0.35, -0.35, 0.87);
    private static final double[] BACKLIT = norm(0.92, -0.22, -0.32);
    private static final double[] BACKLIT_2 = norm(-0.55, 0.72, -0.42);

    static double[] norm(double x, double y, double z) {
        double n = Math.sqrt(x * x + y * y + z * z);
        return new double[]{x / n, y / n, z / n};
    }

    /**
     * Lambertian sphere with 3x3 supersampled antialiasing.
     *
     * @param rimOnly when true only the lit part is drawn and the unlit part keeps the
     *                background value: this is the hard back-lit case where the object's
     *                silhouette is invisible and only a crescent betrays it.
     */
    static final class Sphere implements Shape {
        final double cx, cy, r;
        final double[] light;
        final boolean rimOnly;

        Sphere(double cx, double cy, double r, double[] light, boolean rimOnly) {
            this.cx = cx; this.cy = cy; this.r = r; this.light = light; this.rimOnly = rimOnly;
        }

        @Override public void draw(GrayImage img) {
            int x0 = (int) Math.floor(cx - r - 2), x1 = (int) Math.ceil(cx + r + 2);
            int y0 = (int) Math.floor(cy - r - 2), y1 = (int) Math.ceil(cy + r + 2);
            double ambient = 12;
            double albedo = 205;
            for (int y = Math.max(0, y0); y <= Math.min(img.height - 1, y1); y++) {
                for (int x = Math.max(0, x0); x <= Math.min(img.width - 1, x1); x++) {
                    double acc = 0, cov = 0;
                    for (int sy = 0; sy < 3; sy++) {
                        for (int sx = 0; sx < 3; sx++) {
                            double px = x + (sx + 0.5) / 3.0 - 0.5;
                            double py = y + (sy + 0.5) / 3.0 - 0.5;
                            double dx = (px - cx) / r, dy = (py - cy) / r;
                            double d2 = dx * dx + dy * dy;
                            if (d2 > 1) continue;
                            double nz = Math.sqrt(Math.max(0, 1 - d2));
                            double dot = dx * light[0] + dy * light[1] + nz * light[2];
                            double v = ambient + albedo * Math.max(0, dot);
                            if (rimOnly && dot <= 0.10) continue; // unlit part stays invisible
                            acc += v;
                            cov += 1;
                        }
                    }
                    if (cov == 0) continue;
                    double a = cov / 9.0;
                    int i = y * img.width + x;
                    img.data[i] = (float) (img.data[i] * (1 - a) + (acc / cov) * a);
                }
            }
        }
    }

    static final class Rect implements Shape {
        final double cx, cy, w, h, delta;

        Rect(double cx, double cy, double w, double h, double delta) {
            this.cx = cx; this.cy = cy; this.w = w; this.h = h; this.delta = delta;
        }

        @Override public void draw(GrayImage img) {
            for (int y = (int) (cy - h / 2); y <= cy + h / 2; y++) {
                for (int x = (int) (cx - w / 2); x <= cx + w / 2; x++) {
                    if (x < 0 || y < 0 || x >= img.width || y >= img.height) continue;
                    int i = y * img.width + x;
                    img.data[i] = (float) Stats.clamp(img.data[i] + delta, 0, 255);
                }
            }
        }
    }

    private static final class Triangle implements Shape {
        final double cx, cy, r, delta;

        Triangle(double cx, double cy, double r, double delta) {
            this.cx = cx; this.cy = cy; this.r = r; this.delta = delta;
        }

        @Override public void draw(GrayImage img) {
            double[] px = new double[3], py = new double[3];
            for (int k = 0; k < 3; k++) {
                double a = -Math.PI / 2 + k * 2 * Math.PI / 3;
                px[k] = cx + r * Math.cos(a);
                py[k] = cy + r * Math.sin(a);
            }
            for (int y = (int) (cy - r - 1); y <= cy + r + 1; y++) {
                for (int x = (int) (cx - r - 1); x <= cx + r + 1; x++) {
                    if (x < 0 || y < 0 || x >= img.width || y >= img.height) continue;
                    if (!inTriangle(x, y, px, py)) continue;
                    int i = y * img.width + x;
                    img.data[i] = (float) Stats.clamp(img.data[i] + delta, 0, 255);
                }
            }
        }

        private static boolean inTriangle(double x, double y, double[] px, double[] py) {
            double d1 = side(x, y, px[0], py[0], px[1], py[1]);
            double d2 = side(x, y, px[1], py[1], px[2], py[2]);
            double d3 = side(x, y, px[2], py[2], px[0], py[0]);
            boolean neg = d1 < 0 || d2 < 0 || d3 < 0;
            boolean pos = d1 > 0 || d2 > 0 || d3 > 0;
            return !(neg && pos);
        }

        private static double side(double x, double y, double ax, double ay, double bx, double by) {
            return (bx - ax) * (y - ay) - (by - ay) * (x - ax);
        }
    }

    /** A soft diagonal shadow: a big non-round illumination change that must not fool us. */
    private static GrayImage shadow(GrayImage img) {
        GrayImage out = img.copy();
        for (int y = 0; y < out.height; y++) {
            for (int x = 0; x < out.width; x++) {
                double t = (x + y) / (double) (out.width + out.height);
                double f = 1.0 - 0.45 * Stats.smooth01(t, 0.35, 0.55);
                int i = y * out.width + x;
                out.data[i] = (float) Stats.clamp(out.data[i] * f, 0, 255);
            }
        }
        return out;
    }

    /** Textured background: multi-scale structure plus a few hard edges. */
    static GrayImage buildReference(int w, int h, long seed) {
        GrayImage img = new GrayImage(w, h);
        Random rnd = new Random(seed);
        double[] phase = new double[12];
        for (int i = 0; i < phase.length; i++) phase[i] = rnd.nextDouble() * Math.PI * 2;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double v = 118;
                v += 26 * Math.sin(x / 17.0 + phase[0]) * Math.cos(y / 13.0 + phase[1]);
                v += 18 * Math.sin((x + y) / 9.0 + phase[2]);
                v += 11 * Math.sin(x / 5.0 + phase[3]) * Math.sin(y / 6.5 + phase[4]);
                v += 14 * Math.cos((x - 2 * y) / 23.0 + phase[5]);
                v += 22 * Math.sin(x / 47.0 + phase[6]) * Math.cos(y / 39.0 + phase[7]); // vignette-ish
                img.data[y * w + x] = (float) Stats.clamp(v, 5, 250);
            }
        }
        // a few blocks and stripes so there is real structure to lock onto
        for (int k = 0; k < 9; k++) {
            int bx = 10 + rnd.nextInt(w - 60);
            int by = 10 + rnd.nextInt(h - 60);
            int bw = 12 + rnd.nextInt(40);
            int bh = 12 + rnd.nextInt(40);
            double d = (rnd.nextBoolean() ? 1 : -1) * (18 + rnd.nextInt(30));
            for (int y = by; y < Math.min(h, by + bh); y++) {
                for (int x = bx; x < Math.min(w, bx + bw); x++) {
                    img.data[y * w + x] = (float) Stats.clamp(img.data[y * w + x] + d, 5, 250);
                }
            }
        }
        return ImageOps.blur121(img);
    }

    private static String f(double v) {
        return String.format(java.util.Locale.US, "%.2f", v);
    }
}
