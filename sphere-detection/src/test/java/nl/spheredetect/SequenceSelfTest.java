package nl.spheredetect;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import nl.spheredetect.SyntheticSelfTest.Shape;

/**
 * Validates {@link BallTracker} on simulated golf sequences at 60 fps.
 *
 * Each sequence renders frame by frame out of the same reference image, with the small
 * continuous wobble of a light tripod applied to every frame, and plays out a scenario:
 * a ball is placed, a club is brought to address, practice swings pass over the ball, and
 * eventually a strike takes the ball away.
 *
 * What is being checked is not "is the ball visible" - the frame level detector already
 * has its own tests - but the temporal behaviour:
 *
 *   - a launch is reported exactly once, and only after the ball has actually gone;
 *   - a club passing over the ball reports nothing at all, not even a flicker of the
 *     label, because a practice swing hides the ball exactly as thoroughly as a strike;
 *   - a shadow sweeping over the ball reports nothing;
 *   - after a launch the tracker returns to searching and re-arms on the next ball.
 *
 * Run: {@code java nl.spheredetect.SequenceSelfTest}
 */
public final class SequenceSelfTest {

    private static final int REF = 320;
    private static final int SAMP = 250;
    private static final long FRAME_MS = 16;      // ~60 fps

    private static final double BALL_X = 124, BALL_Y = 128, BALL_R = 11;

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        GrayImage reference = SyntheticSelfTest.buildReference(REF, REF, 20240730);

        DetectorConfig cfg = new DetectorConfig();
        cfg.maxRotationDeg = 6;
        cfg.maxZoomFactor = 1.05;
        cfg.maxShiftPx = 14;

        // Calibration: ball placed, user marks it roughly.
        GrayImage calPos = frame(reference, 0, ball());
        GrayImage calNeg = frame(reference, 0);
        Calibrator.Input ci = new Calibrator.Input();
        ci.reference = reference;
        ci.positive = calPos;
        ci.negative = calNeg;
        ci.userCx = BALL_X + 2;
        ci.userCy = BALL_Y - 1;
        ci.userRadius = 12;
        ci.radiusMarginFraction = 0.35;
        ci.expectPartialIllumination = false;      // a lit driving range, not backlight
        Calibrator.Output cal = Calibrator.calibrate(ci, cfg);

        System.out.println("=== KALIBRATIE ===");
        System.out.println(cal.message);
        if (!cal.ok) {
            System.out.println("kalibratie mislukt, test afgebroken");
            System.exit(1);
        }
        System.out.println();

        if (Boolean.getBoolean("disc.debug")) { debugDump(reference, cal.calibration, cfg); return; }
        if (Boolean.getBoolean("disc.realign")) {
            for (int n : new int[]{1, 8, 100000}) {
                BallTracker.Config tc = new BallTracker.Config();
                tc.realignEveryNFrames = n;
                Run r = new Run("realign=" + n, reference, cal.calibration, cfg, tc);
                r.play(0, 260, f -> new Shape[]{ball()});
                int low = 0;
                for (int i = 200; i < r.frames(); i++) if (r.log.get(i).presence < 0.5) low++;
                System.out.println("realignEveryNFrames=" + n + " -> " + low
                        + " van 60 frames met lage presence");
            }
            return;
        }
        scenarioNormalSwing(reference, cal.calibration, cfg);
        scenarioPracticeSwingOnly(reference, cal.calibration, cfg);
        scenarioShadowSweep(reference, cal.calibration, cfg);
        scenarioTooSoon(reference, cal.calibration, cfg);
        scenarioBlockedForever(reference, cal.calibration, cfg);
        benchmark(reference, cal.calibration, cfg);

        System.out.println();
        System.out.println("=== TOTAAL: " + passed + " geslaagd, " + failed + " mislukt ===");
        if (failed > 0) System.exit(1);
    }

    /** Prints per-frame verdicts so the stability of the locked check can be inspected. */
    private static void debugDump(GrayImage ref, Calibration cal, DetectorConfig cfg) {
        Run run = new Run("debug", ref, cal, cfg);
        run.play(0, 220, f -> new Shape[]{ball()});
        System.out.println("-- stabiele bal, laatste 15 frames --");
        for (int i = run.frames() - 15; i < run.frames(); i++) {
            BallTracker.Update u = run.log.get(i);
            System.out.println("  " + i + " " + u);
        }
        run.playShadowed(0, 40, f -> new Shape[]{ball()}, f -> 40.0 + f * 4.0);
        System.out.println("-- schaduw trekt over --");
        for (int i = run.frames() - 40; i < run.frames(); i += 3) {
            BallTracker.Update u = run.log.get(i);
            System.out.println("  " + i + " " + u);
        }
    }

    // ------------------------------------------------------------------ scenarios

    /**
     * The full story: ball placed, address, a practice swing that passes over the ball,
     * address again, then the real strike. Exactly one launch, and only at the strike.
     */
    private static void scenarioNormalSwing(GrayImage ref, Calibration cal, DetectorConfig cfg) {
        Run run = new Run("normale swing", ref, cal, cfg);

        run.play(0, 10, f -> new Shape[]{});                                  // empty
        run.play(10, 200, f -> new Shape[]{ball()});                          // ball placed, waiting
        int armedAt = run.firstFrameInState(BallTracker.State.ARMED);

        run.play(200, 236, f -> new Shape[]{ball(), club(52, 132, -20)});     // address
        run.play(236, 239, f -> new Shape[]{club(BALL_X, BALL_Y, 70)});       // practice swing: ball hidden
        int practiceEnd = run.frames();
        run.play(239, 280, f -> new Shape[]{ball(), club(52, 132, -20)});     // ball back, address

        run.play(280, 281, f -> new Shape[]{club(BALL_X, BALL_Y, 70)});       // impact: club over ball
        run.play(281, 400, f -> new Shape[]{});                               // ball gone, grass

        List<Integer> launches = run.launchFrames();
        expect(run, "armt na ~3 s", armedAt > 0 && Math.abs(armedAt - (10 + 3000 / FRAME_MS)) < 12,
                "armed op frame " + armedAt + " (verwacht ~" + (10 + 3000 / FRAME_MS) + ")");
        expect(run, "geen trigger bij oefenswing",
                launches.isEmpty() || launches.get(0) > practiceEnd,
                "eerste trigger op frame " + (launches.isEmpty() ? -1 : launches.get(0))
                        + ", oefenswing eindigde op " + practiceEnd);
        expect(run, "label blijft staan tijdens oefenswing",
                run.labelDuring(236, 239, BallTracker.LABEL_BALL),
                "labels " + run.labelsBetween(236, 240));
        expect(run, "precies een trigger", launches.size() == 1, "aantal triggers " + launches.size());
        expect(run, "trigger kort na de slag",
                launches.size() == 1 && launches.get(0) >= 281 && launches.get(0) <= 292,
                "trigger op frame " + (launches.isEmpty() ? -1 : launches.get(0)) + " (slag op 280)");
        expect(run, "daarna terug naar zoeken",
                run.stateAt(run.frames() - 1) == BallTracker.State.SEARCHING,
                "eindtoestand " + run.stateAt(run.frames() - 1));

        // A new ball is placed and must arm again.
        run.play(400, 600, f -> new Shape[]{ball()});
        expect(run, "nieuwe bal armt opnieuw",
                run.stateAt(run.frames() - 1) == BallTracker.State.ARMED,
                "eindtoestand " + run.stateAt(run.frames() - 1));
        run.report();
    }

    /** Practice swings only, no strike. Nothing may ever be reported. */
    private static void scenarioPracticeSwingOnly(GrayImage ref, Calibration cal, DetectorConfig cfg) {
        Run run = new Run("alleen oefenswings", ref, cal, cfg);
        run.play(0, 200, f -> new Shape[]{ball()});
        for (int k = 0; k < 4; k++) {
            run.play(0, 40, f -> new Shape[]{ball(), club(52, 132, -20)});
            run.play(0, 3, f -> new Shape[]{club(BALL_X, BALL_Y, 70)});    // hidden briefly
            run.play(0, 40, f -> new Shape[]{ball()});
        }
        expect(run, "nooit een trigger", run.launchFrames().isEmpty(),
                "triggers " + run.launchFrames());
        // What matters is what the app shows and does: the ball stays reported as present
        // and nothing is triggered. ARMED and INTERRUPTED both mean "bal gedetecteerd".
        expect(run, "bal blijft als aanwezig gemeld",
                run.labelDuring(run.frames() - 30, run.frames(), BallTracker.LABEL_BALL),
                "labels " + run.labelsBetween(run.frames() - 6, run.frames()));
        expect(run, "blijft gearmeerd (niet terug naar zoeken)",
                run.stateAt(run.frames() - 1) == BallTracker.State.ARMED
                        || run.stateAt(run.frames() - 1) == BallTracker.State.INTERRUPTED,
                "eindtoestand " + run.stateAt(run.frames() - 1));
        if (Boolean.getBoolean("disc.tail")) {
            for (int i = run.frames() - 46; i < run.frames(); i += 3) {
                System.out.println("   tail " + i + " " + run.log.get(i));
            }
        }
        run.report();
    }

    /** A hard shadow sweeping across the ball must not read as a departure. */
    private static void scenarioShadowSweep(GrayImage ref, Calibration cal, DetectorConfig cfg) {
        Run run = new Run("schaduw over de bal", ref, cal, cfg);
        run.play(0, 200, f -> new Shape[]{ball()});
        run.playShadowed(0, 60, f -> new Shape[]{ball()}, f -> 40.0 + f * 4.0);
        run.play(0, 60, f -> new Shape[]{ball()});
        expect(run, "geen trigger door schaduw", run.launchFrames().isEmpty(),
                "triggers " + run.launchFrames());
        run.report();
    }

    /** Struck before the arming time has elapsed: no trigger, by design. */
    private static void scenarioTooSoon(GrayImage ref, Calibration cal, DetectorConfig cfg) {
        Run run = new Run("slag voor het armen", ref, cal, cfg);
        run.play(0, 60, f -> new Shape[]{ball()});          // only 1 s, arming needs 3 s
        run.play(0, 2, f -> new Shape[]{club(BALL_X, BALL_Y, 70)});
        run.play(0, 120, f -> new Shape[]{});
        expect(run, "geen trigger voor het armen (bewust)", run.launchFrames().isEmpty(),
                "triggers " + run.launchFrames());
        expect(run, "valt terug op zoeken",
                run.stateAt(run.frames() - 1) == BallTracker.State.SEARCHING,
                "eindtoestand " + run.stateAt(run.frames() - 1));
        run.report();
    }

    /** Something moves in and stays there: give up quietly, do not report a launch. */
    private static void scenarioBlockedForever(GrayImage ref, Calibration cal, DetectorConfig cfg) {
        Run run = new Run("bal blijvend afgedekt", ref, cal, cfg);
        run.play(0, 200, f -> new Shape[]{ball()});
        run.play(0, 150, f -> new Shape[]{club(BALL_X, BALL_Y, 70)});   // stays on the ball
        expect(run, "geen trigger bij blijvende afdekking", run.launchFrames().isEmpty(),
                "triggers " + run.launchFrames());
        expect(run, "geeft het op en gaat zoeken",
                run.stateAt(run.frames() - 1) == BallTracker.State.SEARCHING,
                "eindtoestand " + run.stateAt(run.frames() - 1));
        run.report();
    }

    private static void benchmark(GrayImage ref, Calibration cal, DetectorConfig cfg) {
        Run warm = new Run("benchmark", ref, cal, cfg);
        warm.silent = true;
        warm.play(0, 260, f -> new Shape[]{ball()});     // get to ARMED and stay there

        long sum = 0;
        int n = 0;
        for (int i = 0; i < 120; i++) {
            GrayImage img = frame(ref, warm.frames(), ball());
            long t = System.nanoTime();
            warm.tracker.update(img, warm.frames() * FRAME_MS);
            sum += System.nanoTime() - t;
            n++;
            warm.frameCount++;
        }
        System.out.println();
        System.out.println(String.format(java.util.Locale.US,
                "=== SNELHEID stream (ARMED, alleen verifieren rond de vaste positie) ===%n" +
                "  %.1f ms per frame", sum / 1e6 / n));
    }

    // ------------------------------------------------------------------ harness

    private interface Frame {
        Shape[] shapes(int f);
    }

    private interface Shadow {
        double at(int f);
    }

    /** Plays frames through a tracker and records what came out. */
    private static final class Run {
        final String name;
        final GrayImage ref;
        final BallTracker tracker;
        final List<BallTracker.Update> log = new ArrayList<BallTracker.Update>();
        int frameCount;
        boolean silent;
        int localFailures;

        Run(String name, GrayImage ref, Calibration cal, DetectorConfig cfg) {
            this(name, ref, cal, cfg, new BallTracker.Config());
        }

        Run(String name, GrayImage ref, Calibration cal, DetectorConfig cfg, BallTracker.Config tc) {
            this.name = name;
            this.ref = ref;
            this.tracker = new BallTracker(ref, cal, cfg, tc);
        }

        int frames() { return frameCount; }

        void play(int from, int to, Frame f) {
            for (int i = from; i < to; i++) {
                feed(frame(ref, frameCount, f.shapes(i)));
            }
        }

        void playShadowed(int from, int to, Frame f, Shadow s) {
            for (int i = from; i < to; i++) {
                GrayImage img = frame(ref, frameCount, f.shapes(i));
                shadowBand(img, s.at(i - from));
                feed(img);
            }
        }

        private void feed(GrayImage img) {
            log.add(tracker.update(img, frameCount * FRAME_MS));
            frameCount++;
        }

        List<Integer> launchFrames() {
            List<Integer> out = new ArrayList<Integer>();
            for (int i = 0; i < log.size(); i++) if (log.get(i).launched) out.add(i);
            return out;
        }

        int firstFrameInState(BallTracker.State s) {
            for (int i = 0; i < log.size(); i++) if (log.get(i).state == s) return i;
            return -1;
        }

        BallTracker.State stateAt(int i) {
            return (i >= 0 && i < log.size()) ? log.get(i).state : null;
        }

        boolean labelDuring(int from, int to, String label) {
            for (int i = from; i < Math.min(to, log.size()); i++) {
                if (!label.equals(log.get(i).label)) return false;
            }
            return true;
        }

        String labelsBetween(int from, int to) {
            StringBuilder sb = new StringBuilder();
            for (int i = from; i < Math.min(to, log.size()); i++) {
                sb.append(i).append('=').append(log.get(i).label).append(' ');
            }
            return sb.toString();
        }

        void report() {
            System.out.println((localFailures == 0 ? "[OK]   " : "[FOUT] ") + name
                    + "  (" + frameCount + " frames, triggers " + launchFrames() + ")");
        }
    }

    private static void expect(Run run, String what, boolean ok, String detail) {
        if (ok) {
            passed++;
        } else {
            failed++;
            run.localFailures++;
            System.out.println("   [FOUT] " + run.name + ": " + what + " -- " + detail);
        }
    }

    // ------------------------------------------------------------------ rendering

    private static SyntheticSelfTest.Shape ball() {
        return new SyntheticSelfTest.Sphere(BALL_X, BALL_Y, BALL_R, SyntheticSelfTest.LIT, false);
    }

    /** The club: a dark, slightly angled bar. */
    private static SyntheticSelfTest.Shape club(final double cx, final double cy, final double deg) {
        return new SyntheticSelfTest.Shape() {
            @Override public void draw(GrayImage img) {
                double a = Math.toRadians(deg);
                double ca = Math.cos(a), sa = Math.sin(a);
                double halfL = 30, halfW = 9;
                for (int y = (int) (cy - 40); y <= cy + 40; y++) {
                    for (int x = (int) (cx - 40); x <= cx + 40; x++) {
                        if (x < 0 || y < 0 || x >= img.width || y >= img.height) continue;
                        double dx = x - cx, dy = y - cy;
                        double u = dx * ca + dy * sa;
                        double v = -dx * sa + dy * ca;
                        if (Math.abs(u) > halfL || Math.abs(v) > halfW) continue;
                        int i = y * img.width + x;
                        img.data[i] = (float) Stats.clamp(img.data[i] * 0.35 - 12, 0, 255);
                    }
                }
            }
        };
    }

    /** A hard shadow edge at horizontal position x, darkening everything to its left. */
    private static void shadowBand(GrayImage img, double x) {
        for (int y = 0; y < img.height; y++) {
            for (int i = y * img.width, e = i + img.width, px = 0; i < e; i++, px++) {
                double f = 1.0 - 0.45 * Stats.smooth01(x - px, -3, 3);
                img.data[i] = (float) Stats.clamp(img.data[i] * f, 0, 255);
            }
        }
    }

    /**
     * One camera frame: the reference seen through a slowly wobbling tripod, with the
     * given objects drawn in and sensor noise added.
     */
    private static GrayImage frame(GrayImage ref, int n, SyntheticSelfTest.Shape... shapes) {
        // Slow drift plus a little jitter: what a light tripod actually does.
        double t = n * 0.017;
        double scale = 1.0 + 0.004 * Math.sin(t * 0.21);
        double angle = 0.9 * Math.sin(t * 0.13) + 0.15 * Math.sin(t * 2.7);
        double tx = 3.0 * Math.sin(t * 0.11) + 0.4 * Math.sin(t * 3.1);
        double ty = 2.4 * Math.cos(t * 0.09) + 0.4 * Math.cos(t * 2.3);

        SimilarityTransform tr = new SimilarityTransform(scale, Math.toRadians(angle), tx, ty,
                SAMP, SAMP, ref.width, ref.height);
        GrayImage out = new GrayImage(SAMP, SAMP);
        for (int y = 0; y < SAMP; y++) {
            for (int x = 0; x < SAMP; x++) {
                out.data[y * SAMP + x] = ref.bilinear(tr.mapX(x, y), tr.mapY(x, y));
            }
        }
        for (SyntheticSelfTest.Shape s : shapes) s.draw(out);

        Random rnd = new Random(n * 7919L + 13);
        for (int i = 0; i < SAMP * SAMP; i++) {
            out.data[i] = (float) Stats.clamp(out.data[i] + rnd.nextGaussian() * 1.6, 0, 255);
        }
        return out;
    }
}
