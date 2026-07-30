package nl.spheredetect;

/**
 * Follows a ball through a live camera stream and reports when it is struck away.
 *
 * Written for the golf case: a camera on a light tripod, a fixed detection area, a ball
 * that is placed and then lies still, a club that is brought to address and swung, and
 * one moment that matters - the ball leaving.
 *
 * <pre>
 *   SEARCHING ──ball found──► CONFIRMING ──stable for armAfterMs──► ARMED
 *       ▲                          │                                 │
 *       │                     ball gone                        ball no longer visible
 *       │                          ▼                                 ▼
 *       └──────────────────────────┴──────────────────────── INTERRUPTED
 *       ▲                                                      │        │
 *       │                                              ball back│        │background back
 *       │                                                      ▼        ▼
 *       └────────────── after launchHoldMs ─────────────── ARMED    LAUNCHED
 * </pre>
 *
 * <h3>Why a brief disappearance is not a launch</h3>
 *
 * A club passing over the ball hides it just as completely as a strike does, and it does
 * so at the same speed - a practice swing is not slower than a real one. Waiting a while
 * and seeing whether the ball comes back cannot be avoided, but time alone is a poor
 * test. What settles it is WHAT IS LEFT BEHIND at the spot:
 *
 *   - covered by a club  -> the ball is gone AND something foreign sits there;
 *   - actually launched  -> the ball is gone AND the grass the reference already knows
 *                           about is visible again.
 *
 * So the tracker waits in INTERRUPTED and watches both signals. As soon as the background
 * is confirmed for a few frames it fires; if the ball reappears first, the interruption
 * is written off as an occlusion and nothing is reported at all - not even a flicker in
 * the label, which stays "bal gedetecteerd" throughout. If neither happens - something
 * moved in and stayed, a shoe or a bag - it gives up and goes back to searching without
 * firing.
 *
 * <h3>Timing</h3>
 *
 * The trigger arrives {@link Config#launchConfirmFrames} frames after the ball actually
 * left, which at 60 fps is around 50 ms. That delay is the price of not firing on every
 * practice swing, and it is why the app should keep a rolling video buffer rather than
 * start recording on the trigger.
 *
 * <h3>Threading</h3>
 *
 * Not thread safe, and it holds the detector's state. One instance per analysis thread;
 * create it once, not per frame.
 */
public final class BallTracker {

    public enum State {
        /** No ball. Full search runs on each analysed frame. */
        SEARCHING,
        /** A ball is present but has not been lying still long enough to arm. */
        CONFIRMING,
        /** Ball present and stable; a launch can now be reported. */
        ARMED,
        /** The ball is momentarily not visible. Still reported as present. */
        INTERRUPTED,
        /** The ball has just been struck away. Reported briefly, then back to SEARCHING. */
        LAUNCHED
    }

    public static final class Config {
        /**
         * How long the ball must be continuously present before a launch can be reported.
         * Note the consequence: a strike within this window produces no trigger, so on a
         * driving range where balls are hit in rapid succession this may need lowering.
         */
        public long armAfterMs = 3000;
        /** How long LAUNCHED is reported before falling back to SEARCHING. */
        public long launchHoldMs = 1000;

        /** Presence score at which the ball counts as present (hysteresis, high side). */
        public double presentThreshold = 0.50;
        /** Presence score below which it counts as not visible (hysteresis, low side). */
        public double absentThreshold = 0.34;
        /**
         * Consecutive frames below {@link #absentThreshold} before the ball is treated as
         * out of sight. One frame is never enough: an individual check can fail while the
         * illumination correction is recovering from something that just passed through,
         * and a single dropout must not move the state machine at all.
         */
        public int absentFramesToInterrupt = 2;

        /** Consecutive frames of "gone AND background is back" needed to declare a launch. */
        public int launchConfirmFrames = 3;
        /** Background score above which the spot counts as empty again. */
        public double backgroundThreshold = 0.55;
        /**
         * The illumination correction must be this quiet before "the background is back"
         * is believed. While a shadow edge is crossing the window the correction is busy
         * chasing it and is partly explaining the spot away, so a launch declared then
         * would be a shadow, not a strike.
         */
        public double maxGainSlewForLaunch = 0.012;
        /**
         * A ball may be out of sight this long during CONFIRMING without the arming clock
         * being restarted. Covers the odd frame in which the check happens to fail.
         */
        public long confirmGraceMs = 350;

        /**
         * How long the ball may stay invisible before the tracker gives up and returns to
         * searching WITHOUT reporting a launch. Covers something moving in and staying.
         */
        public long maxInterruptionMs = 1200;

        /**
         * A ball that shifts further than this (times its radius) is treated as newly
         * placed rather than the same ball, and the arming clock restarts.
         */
        public double replaceDriftFactor = 1.2;

        /**
         * Re-run the full alignment at most this often while armed. Between times the
         * previous transform is reused, which is what a tripod allows and what keeps the
         * per-frame cost down. Set to 1 to re-align every frame.
         */
        public int realignEveryNFrames = 8;
    }

    /** Result of one frame. */
    public static final class Update {
        public State state;
        /** Dutch label, ready to display. */
        public String label;
        /**
         * True on the single frame the launch is reported. Use this edge to trigger,
         * not the state, so a held LAUNCHED cannot fire twice.
         */
        public boolean launched;

        /** 0..1 confidence that the ball is at the locked position. */
        public double presence;
        /** 0..1 confidence that the spot looks like the empty background. */
        public double background;
        /** Locked ball position; only meaningful from CONFIRMING onwards. */
        public double cx, cy, r;
        /** How long the ball has been continuously present. */
        public long presentForMs;
        /** How long the current interruption has lasted, 0 when not interrupted. */
        public long interruptedForMs;

        public long analysisMillis;
        public boolean fullSearchRan;
        public String detail = "";

        @Override public String toString() {
            return String.format(java.util.Locale.US,
                    "%s [%s] aanwezig=%.2f achtergrond=%.2f pos=(%.1f,%.1f) r=%.1f " +
                    "aanwezig_sinds=%dms onderbroken=%dms %dms%s%s",
                    label, state, presence, background, cx, cy, r, presentForMs,
                    interruptedForMs, analysisMillis, launched ? " GELANCEERD" : "",
                    detail.isEmpty() ? "" : " | " + detail);
        }
    }

    public static final String LABEL_NONE = "geen bal gedetecteerd";
    public static final String LABEL_BALL = "bal gedetecteerd";
    public static final String LABEL_LAUNCHED = "gelanceerd";

    private final SphereDetector detector;
    private final LockedBallVerifier verifier;
    private final Config tcfg;

    private State state = State.SEARCHING;
    private double cx, cy, r;
    private long presentSinceMs;
    private long lastSeenMs;
    private long interruptedSinceMs;
    private long launchedAtMs;
    private int goneWithBackgroundFrames;
    private int absentFrames;
    private int framesSinceRealign = Integer.MAX_VALUE;
    private SimilarityTransform transform;

    public BallTracker(GrayImage reference, Calibration calibration,
                       DetectorConfig config, Config trackerConfig) {
        DetectorConfig dc = config != null ? config : new DetectorConfig();
        this.detector = new SphereDetector(reference, calibration, dc);
        this.verifier = new LockedBallVerifier(reference, calibration, dc);
        this.tcfg = trackerConfig != null ? trackerConfig : new Config();
    }

    public State state() {
        return state;
    }

    /** Forget everything: call after the camera has been re-aimed or re-calibrated. */
    public void reset() {
        state = State.SEARCHING;
        transform = null;
        framesSinceRealign = Integer.MAX_VALUE;
        goneWithBackgroundFrames = 0;
        absentFrames = 0;
        detector.resetTracking();
        verifier.resetIllumination();
    }

    /** Luma only. */
    public Update update(GrayImage sample, long timestampMs) {
        return update(sample, null, timestampMs);
    }

    /**
     * @param chroma optional colour planes for the same window; pass null to run on
     *               brightness alone. Colour is used only to corroborate.
     */
    public Update update(GrayImage sample, ChromaPlanes chroma, long timestampMs) {
        long t0 = System.nanoTime();
        Update u = new Update();

        switch (state) {
            case SEARCHING:   searching(sample, timestampMs, u);   break;
            case CONFIRMING:  // same handling as ARMED, only the reporting differs
            case ARMED:       tracking(sample, chroma, timestampMs, u);  break;
            case INTERRUPTED: interrupted(sample, chroma, timestampMs, u); break;
            case LAUNCHED:    launched(timestampMs, u);            break;
        }

        u.state = state;
        u.cx = cx; u.cy = cy; u.r = r;
        u.presentForMs = (state == State.SEARCHING || state == State.LAUNCHED)
                ? 0 : Math.max(0, timestampMs - presentSinceMs);
        u.interruptedForMs = (state == State.INTERRUPTED)
                ? Math.max(0, timestampMs - interruptedSinceMs) : 0;
        u.label = labelFor(state);
        u.analysisMillis = (System.nanoTime() - t0) / 1000000L;
        return u;
    }

    private static String labelFor(State s) {
        switch (s) {
            case LAUNCHED:  return LABEL_LAUNCHED;
            case SEARCHING: return LABEL_NONE;
            default:        return LABEL_BALL;   // CONFIRMING, ARMED and INTERRUPTED alike
        }
    }

    // ------------------------------------------------------------------ states

    private void searching(GrayImage sample, long now, Update u) {
        DetectionResult d = detector.detect(sample);
        u.fullSearchRan = true;
        u.presence = d.score;
        u.detail = d.message;
        if (d.registration != null && d.registration.ok) {
            transform = d.registration.transform;
            framesSinceRealign = 0;
        }
        if (d.ballDetected) {
            cx = d.cx; cy = d.cy; r = d.r;
            presentSinceMs = now;
            lastSeenMs = now;
            state = State.CONFIRMING;
            verifier.resetIllumination();
        }
    }

    /** Shared by CONFIRMING and ARMED: verify at the locked spot, arm when stable enough. */
    private void tracking(GrayImage sample, ChromaPlanes chroma, long now, Update u) {
        if (!ensureAlignment(sample, u)) return;

        LockedBallVerifier.Verdict v = verifier.verify(sample, chroma, transform, cx, cy, r);
        u.presence = v.ballScore;
        u.background = v.backgroundScore;
        u.detail = v.toString();

        if (!v.ok) return;

        if (v.ballScore >= tcfg.absentThreshold) {
            absentFrames = 0;
            if (v.drift > tcfg.replaceDriftFactor * r) {
                // Moved far enough to be a different placement: start the clock again.
                presentSinceMs = now;
                state = State.CONFIRMING;
            }
            cx = v.cx; cy = v.cy; r = v.r;
            lastSeenMs = now;
            if (state == State.CONFIRMING && now - presentSinceMs >= tcfg.armAfterMs) {
                state = State.ARMED;
            }
            return;
        }

        // Lost sight of it. Wait for confirmation before acting on it.
        if (++absentFrames < tcfg.absentFramesToInterrupt) return;

        // From ARMED this may become a launch; from CONFIRMING the ball was never
        // established, so it is simply gone.
        if (state == State.ARMED) {
            state = State.INTERRUPTED;
            interruptedSinceMs = now;
            goneWithBackgroundFrames = 0;
        } else if (now - lastSeenMs > tcfg.confirmGraceMs) {
            // Only give up on a ball that is being confirmed once it has really been gone
            // for a while; a single failed check must not restart the arming clock.
            state = State.SEARCHING;
        }
    }

    private void interrupted(GrayImage sample, ChromaPlanes chroma, long now, Update u) {
        if (!ensureAlignment(sample, u)) return;

        LockedBallVerifier.Verdict v = verifier.verify(sample, chroma, transform, cx, cy, r);
        u.presence = v.ballScore;
        u.background = v.backgroundScore;
        u.detail = v.toString();

        if (!v.ok) return;

        // Back again: it was something passing in front of it. Say nothing about it.
        if (v.ballScore >= tcfg.presentThreshold) {
            cx = v.cx; cy = v.cy; r = v.r;
            state = State.ARMED;
            goneWithBackgroundFrames = 0;
            absentFrames = 0;
            return;
        }

        if (v.gainSlew > tcfg.maxGainSlewForLaunch) {
            // The light is moving. Do not read anything into the spot looking empty.
            goneWithBackgroundFrames = 0;
            u.detail = "licht verandert, oordeel uitgesteld";
        } else if (v.backgroundScore >= tcfg.backgroundThreshold) {
            goneWithBackgroundFrames++;
            if (goneWithBackgroundFrames >= tcfg.launchConfirmFrames) {
                state = State.LAUNCHED;
                launchedAtMs = now;
                u.launched = true;
                u.detail = "bal weg en achtergrond terug";
                detector.resetTracking();
                return;
            }
        } else {
            // Something is sitting on the spot: keep waiting, this is an occlusion.
            goneWithBackgroundFrames = 0;
        }

        if (now - interruptedSinceMs > tcfg.maxInterruptionMs) {
            // Blocked for too long to call it either way. No trigger.
            state = State.SEARCHING;
            u.detail = "te lang onderbroken, terug naar zoeken";
        }
    }

    private void launched(long now, Update u) {
        u.presence = 0;
        if (now - launchedAtMs >= tcfg.launchHoldMs) {
            state = State.SEARCHING;
            transform = null;
            framesSinceRealign = Integer.MAX_VALUE;
        }
    }

    /**
     * Keeps the alignment fresh without paying for it every frame. A tripod drifts slowly,
     * so re-aligning every few frames is enough; in between, the stored transform is used.
     */
    private boolean ensureAlignment(GrayImage sample, Update u) {
        if (transform != null && framesSinceRealign < tcfg.realignEveryNFrames) {
            framesSinceRealign++;
            return true;
        }
        Registrar.Result reg = detector.register(sample);
        u.fullSearchRan = true;
        if (!reg.ok) {
            // Cannot trust anything measured against a bad alignment.
            transform = null;
            framesSinceRealign = Integer.MAX_VALUE;
            state = State.SEARCHING;
            u.detail = "uitlijning mislukt";
            return false;
        }
        transform = reg.transform;
        framesSinceRealign = 0;
        return true;
    }
}
