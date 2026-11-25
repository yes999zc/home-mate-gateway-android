package homes.mymate.gateway;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.os.Build;
import android.view.Choreographer;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/**
 * Architectural 60-120fps Zero-GC 3D Spherical Particle Nebula Voice Orb ({@code ParticleOrbView} v2.1).
 *
 * v2.1 design & rhythm upgrade:
 * - Restrained gallery-grade monochrome & warm tungsten palette (eliminates cyberpunk neon AI clichés):
 *     idle:      Alabaster Pearl & Silver Mist (calm architectural breath)
 *     listening: Pure Crisp White & Cool Ice (25Hz real-time voice dispersion)
 *     thinking:  Frosted Platinum & Muted Slate-Blue twin-helix vortex
 *     speaking:  Warm Champagne Gold & Alabaster harmonic standing waves
 *     music:     Beat-Reactive Warm Amber & Platinum Ring (35Hz bass/kick transient impulse + undulating orbital spectrum)
 *     muted:     Damped Crimson & Charcoal core
 *     offline:   Quiet dim starfield
 * - 220 pre-allocated 3D Fibonacci-sphere particles across 3 shells + pre-baked ALPHA_8 HDR bloom.
 * - Even-strided 110-particle StandBy ambient mode (full 3D sphere, 30fps idle cap, 10-min OLED shift).
 */
public final class ParticleOrbView extends View {

    private static final int TOTAL_PARTICLES = 220;
    private static final int SHELL0_COUNT = 60;
    private static final int SHELL1_COUNT = 130;
    private static final int MAX_FILAMENT_NODES = 26;

    private static final int STATE_OFFLINE = 0;
    private static final int STATE_IDLE = 1;
    private static final int STATE_LISTENING = 2;
    private static final int STATE_THINKING = 3;
    private static final int STATE_SPEAKING = 4;
    private static final int STATE_MUTED = 5;
    private static final int STATE_MUSIC = 6;

    // Pre-allocated SoA particle buffers (ZERO allocations in onDraw)
    private final float[] baseX = new float[TOTAL_PARTICLES];
    private final float[] baseY = new float[TOTAL_PARTICLES];
    private final float[] baseZ = new float[TOTAL_PARTICLES];
    private final float[] baseR = new float[TOTAL_PARTICLES];
    private final float[] theta0 = new float[TOTAL_PARTICLES];
    private final float[] phi0 = new float[TOTAL_PARTICLES];
    private final float[] phase = new float[TOTAL_PARTICLES];
    private final float[] sizeMul = new float[TOTAL_PARTICLES];
    private final int[] shell = new int[TOTAL_PARTICLES];

    // Per-frame projected scratch arrays
    private final float[] projX = new float[TOTAL_PARTICLES];
    private final float[] projY = new float[TOTAL_PARTICLES];
    private final float[] projZ = new float[TOTAL_PARTICLES];
    private final float[] projSize = new float[TOTAL_PARTICLES];
    private final float[] projAlpha = new float[TOTAL_PARTICLES];
    private final int[] sortOrder = new int[TOTAL_PARTICLES];
    private final int[] frontNodes = new int[MAX_FILAMENT_NODES];

    private Bitmap haloSprite;
    private Bitmap glowSprite;
    private final RectF dstRect = new RectF();

    private final Paint spritePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint addSpritePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint filamentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shockwavePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private volatile int targetState = STATE_IDLE;
    private volatile float targetAudioLevel = 0.0f;
    private volatile float targetBassLevel = 0.0f;
    private boolean lightTheme = false;
    private boolean ambientMode = false;
    private boolean paused = false;
    private boolean frameCallbackScheduled = false;
    private final PorterDuffXfermode additiveXfer = new PorterDuffXfermode(PorterDuff.Mode.ADD);

    private float simTime = 0f;
    private long lastFrameNs = 0L;
    private float fastEnergy = 0f;
    private float slowEnergy = 0f;
    private float fastBass = 0f;
    private float slowBass = 0f;
    private float musicDynamics = 0f;
    private float beatImpulse = 0f;
    private float musicRippleProgress = 1.0f;

    // Smoothed RGB channels (start in Alabaster Pearl / Silver Mist idle)
    private float curR1 = 228f, curG1 = 228f, curB1 = 232f;
    private float curR2 = 161f, curG2 = 161f, curB2 = 170f;
    private float curRC = 250f, curGC = 250f, curBC = 250f;

    // Smoothed motion parameters
    private float curSpinSpeed = 0.26f;
    private float curTurbulence = 0.08f;
    private float curExpand = 1.0f;
    private float curVortex = 0.0f;
    private float curWaveAmp = 0.0f;
    private float curMusicRingAmp = 0.0f;
    private float curFilamentAlpha = 0.22f;

    // Touch physics
    private float shockwaveProgress = 1.0f;
    private boolean touching = false;
    private long touchDownMs = 0L;
    private float chargePull = 0f;

    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            frameCallbackScheduled = false;
            if (paused || !isAttachedToWindow() || getWindowVisibility() != VISIBLE || getVisibility() != VISIBLE) {
                lastFrameNs = 0L;
                return;
            }
            if (lastFrameNs == 0L) {
                lastFrameNs = frameTimeNanos;
            }
            long deltaNs = frameTimeNanos - lastFrameNs;
            boolean lowPowerCap = ambientMode
                    && (targetState == STATE_IDLE || targetState == STATE_OFFLINE || targetState == STATE_MUTED)
                    && shockwaveProgress >= 1.0f && chargePull < 0.01f;
            if (lowPowerCap && deltaNs < 31_000_000L) {
                scheduleNextFrame();
                return;
            }
            float dt = Math.min(0.05f, Math.max(0.001f, deltaNs * 1e-9f));
            lastFrameNs = frameTimeNanos;
            stepSimulation(dt);
            invalidate();
            scheduleNextFrame();
        }
    };

    public ParticleOrbView(Context context) {
        super(context);
        initParticles();
        initSpritesAndPaints();
        setClickable(true);
        setFocusable(true);
    }

    private void initParticles() {
        final double goldenAngle = Math.PI * (3.0 - Math.sqrt(5.0));
        for (int i = 0; i < TOTAL_PARTICLES; i++) {
            sortOrder[i] = i;
            if (i < SHELL0_COUNT) {
                shell[i] = 0;
                double t = (i + 0.5) / SHELL0_COUNT;
                double y = 1.0 - 2.0 * t;
                double r = Math.sqrt(Math.max(0.0, 1.0 - y * y));
                double a = goldenAngle * i;
                baseX[i] = (float) (Math.cos(a) * r);
                baseY[i] = (float) y;
                baseZ[i] = (float) (Math.sin(a) * r);
                baseR[i] = 0.30f + 0.26f * ((i * 37 % 100) / 100f);
                theta0[i] = (float) a;
                phi0[i] = (float) Math.acos(Math.max(-1.0, Math.min(1.0, y)));
                phase[i] = (i * 0.71f) % 6.28318f;
                sizeMul[i] = 0.80f + 0.50f * ((i * 53 % 100) / 100f);
            } else if (i < SHELL0_COUNT + SHELL1_COUNT) {
                shell[i] = 1;
                int idx = i - SHELL0_COUNT;
                double t = (idx + 0.5) / SHELL1_COUNT;
                double y = 1.0 - 2.0 * t;
                double r = Math.sqrt(Math.max(0.0, 1.0 - y * y));
                double a = goldenAngle * idx;
                baseX[i] = (float) (Math.cos(a) * r);
                baseY[i] = (float) y;
                baseZ[i] = (float) (Math.sin(a) * r);
                baseR[i] = 0.78f + 0.24f * ((idx * 29 % 100) / 100f);
                theta0[i] = (float) a;
                phi0[i] = (float) Math.acos(Math.max(-1.0, Math.min(1.0, y)));
                phase[i] = (idx * 0.43f) % 6.28318f;
                sizeMul[i] = 0.68f + 0.72f * ((idx * 61 % 100) / 100f);
            } else {
                shell[i] = 2;
                int idx = i - SHELL0_COUNT - SHELL1_COUNT;
                int ringCount = TOTAL_PARTICLES - SHELL0_COUNT - SHELL1_COUNT;
                double a = (2.0 * Math.PI * idx) / ringCount;
                double tilt = Math.toRadians(24.0);
                double bandY = (((idx * 17) % 11) - 5) * 0.020;
                double rx = Math.cos(a);
                double rz = Math.sin(a);
                double ry = bandY;
                double ty = ry * Math.cos(tilt) - rz * Math.sin(tilt);
                double tz = ry * Math.sin(tilt) + rz * Math.cos(tilt);
                baseX[i] = (float) rx;
                baseY[i] = (float) ty;
                baseZ[i] = (float) tz;
                baseR[i] = 1.15f + 0.25f * ((idx * 41 % 100) / 100f);
                theta0[i] = (float) a;
                phi0[i] = (float) (Math.PI * 0.5);
                phase[i] = (idx * 0.89f) % 6.28318f;
                sizeMul[i] = 0.48f + 0.46f * ((idx * 73 % 100) / 100f);
            }
        }
    }

    private void initSpritesAndPaints() {
        haloSprite = buildGaussianAlphaSprite(128, 2.45f, 0.0f);
        glowSprite = buildGaussianAlphaSprite(48, 2.15f, 0.34f);

        addSpritePaint.setXfermode(additiveXfer);

        filamentPaint.setStyle(Paint.Style.STROKE);
        filamentPaint.setStrokeCap(Paint.Cap.ROUND);
        filamentPaint.setXfermode(additiveXfer);

        shockwavePaint.setStyle(Paint.Style.STROKE);
        shockwavePaint.setXfermode(additiveXfer);
    }

    /**
     * Switches orb rendering physics between Daytime Light Mode (rich SRC_OVER luminous pigment sphere
     * that stays crisp on #F2F2F7/#FFFFFF) and Nighttime Dark Mode (additive HDR bloom on OLED black).
     */
    public void setLightTheme(boolean light) {
        if (this.lightTheme == light) {
            return;
        }
        this.lightTheme = light;
        if (light) {
            addSpritePaint.setXfermode(null);
            filamentPaint.setXfermode(null);
            shockwavePaint.setXfermode(null);
            // Snap initial daylight palette immediately so there is no white flash on theme toggle
            curR1 = 0f; curG1 = 113f; curB1 = 227f;
            curR2 = 88f; curG2 = 86f; curB2 = 214f;
            curRC = 100f; curGC = 210f; curBC = 255f;
        } else {
            addSpritePaint.setXfermode(additiveXfer);
            filamentPaint.setXfermode(additiveXfer);
            shockwavePaint.setXfermode(additiveXfer);
            curR1 = 228f; curG1 = 228f; curB1 = 232f;
            curR2 = 161f; curG2 = 161f; curB2 = 170f;
            curRC = 250f; curGC = 250f; curBC = 250f;
        }
        invalidate();
    }

    private static Bitmap buildGaussianAlphaSprite(int size, float falloffPow, float hotCoreRadius) {
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8);
        byte[] pixels = new byte[size * size];
        float half = (size - 1) * 0.5f;
        for (int y = 0; y < size; y++) {
            float dy = (y - half) / half;
            for (int x = 0; x < size; x++) {
                float dx = (x - half) / half;
                float dist = (float) Math.sqrt(dx * dx + dy * dy);
                float alpha;
                if (dist >= 1.0f) {
                    alpha = 0f;
                } else {
                    float glow = (float) Math.pow(1.0f - dist, falloffPow);
                    if (hotCoreRadius > 0f && dist < hotCoreRadius) {
                        float core = 1.0f - (dist / hotCoreRadius);
                        alpha = Math.min(1.0f, glow * 0.68f + core * core * 0.88f);
                    } else {
                        alpha = glow * 0.80f;
                    }
                }
                int a = Math.max(0, Math.min(255, Math.round(alpha * 255f)));
                pixels[y * size + x] = (byte) a;
            }
        }
        bmp.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(pixels));
        return bmp;
    }

    public void setAmbientMode(boolean ambient) {
        this.ambientMode = ambient;
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
        if (paused) {
            cancelScheduledFrame();
        } else {
            lastFrameNs = 0L;
            scheduleNextFrame();
        }
    }

    /** Thread-safe 25Hz mic / 33Hz TTS / 35Hz music-beat live energy hook (zero allocations). */
    public void setLiveAudioEnergy(double rmsDbfs, double bassDbfs, boolean isMusic) {
        // Mastered music averages -14..-10 dBFS; keep wide headroom (-40..-4 dBFS) so it never pegs at 1.0
        float normFull = (float) Math.max(0.0, Math.min(1.0, (rmsDbfs + (isMusic ? 40.0 : 58.0)) / (isMusic ? 36.0 : 36.0)));
        float normBass = (float) Math.max(0.0, Math.min(1.0, (bassDbfs + 38.0) / 34.0));
        this.targetAudioLevel = normFull;
        this.targetBassLevel = isMusic ? normBass : normFull * 0.5f;
    }

    public void updateState(boolean connected, boolean muted, String assistantState, boolean musicActive, double rmsDbfs) {
        if (!musicActive) {
            setLiveAudioEnergy(rmsDbfs, rmsDbfs, false);
        }
        if (!connected) {
            targetState = STATE_OFFLINE;
        } else if (muted) {
            targetState = STATE_MUTED;
        } else if ("listening".equalsIgnoreCase(assistantState) || "hearing".equalsIgnoreCase(assistantState)) {
            targetState = STATE_LISTENING;
        } else if ("thinking".equalsIgnoreCase(assistantState)) {
            targetState = STATE_THINKING;
        } else if ("speaking".equalsIgnoreCase(assistantState)) {
            targetState = STATE_SPEAKING;
        } else if (musicActive || "music".equalsIgnoreCase(assistantState)) {
            targetState = STATE_MUSIC;
        } else {
            targetState = STATE_IDLE;
        }
    }

    public void triggerShockwave() {
        shockwaveProgress = 0.0f;
        if (!paused) {
            scheduleNextFrame();
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            touching = true;
            touchDownMs = System.currentTimeMillis();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            }
        } else if (action == MotionEvent.ACTION_UP) {
            long heldMs = System.currentTimeMillis() - touchDownMs;
            touching = false;
            if (heldMs < 350L) {
                triggerShockwave();
            } else {
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                triggerShockwave();
            }
        } else if (action == MotionEvent.ACTION_CANCEL) {
            touching = false;
        }
        return super.onTouchEvent(event);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        lastFrameNs = 0L;
        scheduleNextFrame();
    }

    @Override
    protected void onDetachedFromWindow() {
        cancelScheduledFrame();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE && !paused) {
            lastFrameNs = 0L;
            scheduleNextFrame();
        } else {
            cancelScheduledFrame();
        }
    }

    private void scheduleNextFrame() {
        if (!frameCallbackScheduled && !paused && isAttachedToWindow()) {
            frameCallbackScheduled = true;
            Choreographer.getInstance().postFrameCallback(frameCallback);
        }
    }

    private void cancelScheduledFrame() {
        if (frameCallbackScheduled) {
            Choreographer.getInstance().removeFrameCallback(frameCallback);
            frameCallbackScheduled = false;
        }
    }

    private void stepSimulation(float dt) {
        simTime += dt;

        // Dual-envelope audio tracking + relative music dynamics & silky bass kick detector
        float targetE = targetAudioLevel;
        float fastAlpha = targetE > fastEnergy ? Math.min(1f, dt * 12f) : Math.min(1f, dt * 5.0f);
        fastEnergy += (targetE - fastEnergy) * fastAlpha;
        slowEnergy += (targetE - slowEnergy) * Math.min(1f, dt * 2.2f);

        float relDyn = Math.max(0f, Math.min(1.0f, (fastEnergy - slowEnergy * 0.90f) * 2.8f));
        float dynAlpha = relDyn > musicDynamics ? Math.min(1f, dt * 10f) : Math.min(1f, dt * 3.5f);
        musicDynamics += (relDyn - musicDynamics) * dynAlpha;

        float targetB = targetBassLevel;
        float bassFastAlpha = targetB > fastBass ? Math.min(1f, dt * 16f) : Math.min(1f, dt * 6.0f);
        fastBass += (targetB - fastBass) * bassFastAlpha;
        slowBass += (targetB - slowBass) * Math.min(1f, dt * 2.0f);

        float transientKick = Math.max(0f, Math.min(1.0f, (fastBass - slowBass * 1.06f) * 2.2f));
        if (transientKick > beatImpulse) {
            beatImpulse += (transientKick - beatImpulse) * Math.min(1f, dt * 18f);
        } else {
            beatImpulse = Math.max(0f, beatImpulse - dt * 2.6f);
        }

        if (targetState == STATE_MUSIC && beatImpulse > 0.30f && musicRippleProgress >= 0.88f) {
            musicRippleProgress = 0.0f;
        }
        if (musicRippleProgress < 1.0f) {
            musicRippleProgress = Math.min(1.0f, musicRippleProgress + dt / 0.85f);
        }

        if (shockwaveProgress < 1.0f) {
            shockwaveProgress = Math.min(1.0f, shockwaveProgress + dt / 0.55f);
        }

        float targetPull = (touching && (System.currentTimeMillis() - touchDownMs) > 280L) ? 1.0f : 0.0f;
        chargePull += (targetPull - chargePull) * Math.min(1f, dt * 8.0f);

        // Apple Light / Dark adaptive spherical palettes (zero invisible white-on-white in Light Mode!)
        float tr1, tg1, tb1;
        float tr2, tg2, tb2;
        float trc, tgc, tbc;
        float tSpin, tTurb, tExp, tVortex, tWave, tMusicRing, tFil;

        int st = targetState;
        if (lightTheme) {
            if (st == STATE_LISTENING) {
                tr1 = 0f; tg1 = 102f; tb1 = 255f;    // Vibrant Apple Azure
                tr2 = 0f; tg2 = 168f; tb2 = 232f;    // Crisp Sky Cyan
                trc = 88f; tgc = 86f; tbc = 214f;    // Deep Sapphire Core
                tSpin = 0.68f; tTurb = 0.22f; tExp = 1.06f; tVortex = 0.0f; tWave = 0.14f; tMusicRing = 0.0f; tFil = 0.34f;
            } else if (st == STATE_THINKING) {
                tr1 = 88f; tg1 = 86f; tb1 = 214f;    // Apple Indigo #5856D6
                tr2 = 122f; tg2 = 90f; tb2 = 248f;   // Luminous Iris
                trc = 48f; tgc = 68f; tbc = 200f;
                tSpin = 1.45f; tTurb = 0.15f; tExp = 0.96f; tVortex = 1.0f; tWave = 0.06f; tMusicRing = 0.0f; tFil = 0.30f;
            } else if (st == STATE_SPEAKING) {
                tr1 = 255f; tg1 = 136f; tb1 = 0f;    // Warm Apple Tangerine
                tr2 = 255f; tg2 = 79f; tb2 = 94f;    // Soft Apple Coral
                trc = 217f; tgc = 88f; tbc = 16f;
                tSpin = 0.50f; tTurb = 0.18f; tExp = 1.04f; tVortex = 0.0f; tWave = 0.26f; tMusicRing = 0.0f; tFil = 0.30f;
            } else if (st == STATE_MUSIC) {
                tr1 = 250f; tg1 = 68f; tb1 = 92f;    // Apple Music Rose Coral
                tr2 = 255f; tg2 = 149f; tb2 = 0f;    // Apple Warm Amber
                trc = 94f; tgc = 92f; tbc = 230f;    // Deep Harmonic Indigo Core
                tSpin = 0.32f + beatImpulse * 0.12f;
                tTurb = 0.04f;
                tExp = 1.02f;
                tVortex = 0.0f;
                tWave = 0.07f;
                tMusicRing = 1.0f;
                tFil = 0.24f;
            } else if (st == STATE_MUTED) {
                tr1 = 224f; tg1 = 49f; tb1 = 49f;    // Apple Red
                tr2 = 142f; tg2 = 142f; tb2 = 147f;  // System Gray
                trc = 192f; tgc = 36f; tbc = 36f;
                tSpin = 0.12f; tTurb = 0.03f; tExp = 0.74f; tVortex = 0.0f; tWave = 0.0f; tMusicRing = 0.0f; tFil = 0.10f;
            } else if (st == STATE_OFFLINE) {
                tr1 = 134f; tg1 = 134f; tb1 = 139f;  // Apple Titanium Gray
                tr2 = 174f; tg2 = 174f; tb2 = 178f;  // System Gray 2
                trc = 99f; tgc = 99f; tbc = 102f;
                tSpin = 0.10f; tTurb = 0.04f; tExp = 0.86f; tVortex = 0.0f; tWave = 0.0f; tMusicRing = 0.0f; tFil = 0.08f;
            } else {
                // STATE_IDLE (Light): Iconic Apple Blue & Indigo Liquid Sphere
                tr1 = 0f; tg1 = 113f; tb1 = 227f;    // Apple HIG Blue #0071E3
                tr2 = 88f; tg2 = 86f; tb2 = 214f;    // Apple Indigo #5856D6
                trc = 45f; tgc = 156f; tbc = 240f;   // Luminous Sky Core
                tSpin = 0.26f; tTurb = 0.08f; tExp = 1.00f; tVortex = 0.0f; tWave = 0.03f; tMusicRing = 0.0f; tFil = 0.20f;
            }
        } else {
            if (st == STATE_LISTENING) {
                tr1 = 250f; tg1 = 250f; tb1 = 255f;  // Pure Crisp White
                tr2 = 100f; tg2 = 186f; tb2 = 255f;  // Apple Ice Blue
                trc = 255f; tgc = 255f; tbc = 255f;
                tSpin = 0.68f; tTurb = 0.22f; tExp = 1.06f; tVortex = 0.0f; tWave = 0.14f; tMusicRing = 0.0f; tFil = 0.44f;
            } else if (st == STATE_THINKING) {
                tr1 = 196f; tg1 = 204f; tb1 = 255f;  // Frosted Periwinkle
                tr2 = 106f; tg2 = 128f; tb2 = 248f;  // Apple Dark Indigo
                trc = 250f; tgc = 250f; tbc = 255f;
                tSpin = 1.45f; tTurb = 0.15f; tExp = 0.96f; tVortex = 1.0f; tWave = 0.06f; tMusicRing = 0.0f; tFil = 0.36f;
            } else if (st == STATE_SPEAKING) {
                tr1 = 255f; tg1 = 179f; tb1 = 92f;   // Warm Apple Gold
                tr2 = 255f; tg2 = 228f; tb2 = 200f;  // Warm Pearl
                trc = 255f; tgc = 248f; tbc = 232f;
                tSpin = 0.50f; tTurb = 0.18f; tExp = 1.04f; tVortex = 0.0f; tWave = 0.26f; tMusicRing = 0.0f; tFil = 0.38f;
            } else if (st == STATE_MUSIC) {
                tr1 = 255f; tg1 = 159f; tb1 = 10f;   // Apple Dark Amber #FF9F0A
                tr2 = 255f; tg2 = 96f; tb2 = 120f;   // Apple Music Rose Glow
                trc = 255f; tgc = 244f; tbc = 224f;  // Warm Pearl Core
                tSpin = 0.32f + beatImpulse * 0.12f;
                tTurb = 0.04f;
                tExp = 1.02f;
                tVortex = 0.0f;
                tWave = 0.07f;
                tMusicRing = 1.0f;
                tFil = 0.28f;
            } else if (st == STATE_MUTED) {
                tr1 = 255f; tg1 = 69f; tb1 = 58f;    // Apple Dark Red
                tr2 = 99f; tg2 = 99f; tb2 = 102f;
                trc = 254f; tgc = 215f; tbc = 215f;
                tSpin = 0.12f; tTurb = 0.03f; tExp = 0.72f; tVortex = 0.0f; tWave = 0.0f; tMusicRing = 0.0f; tFil = 0.10f;
            } else if (st == STATE_OFFLINE) {
                tr1 = 142f; tg1 = 142f; tb1 = 147f;
                tr2 = 72f; tg2 = 72f; tb2 = 74f;
                trc = 174f; tgc = 174f; tbc = 178f;
                tSpin = 0.10f; tTurb = 0.04f; tExp = 0.86f; tVortex = 0.0f; tWave = 0.0f; tMusicRing = 0.0f; tFil = 0.08f;
            } else {
                // STATE_IDLE (Dark): Calm Alabaster Pearl & Soft Apple Blue Mist
                tr1 = 232f; tg1 = 238f; tb1 = 248f;
                tr2 = 110f; tg2 = 162f; tb2 = 232f;
                trc = 252f; tgc = 252f; tbc = 255f;
                tSpin = 0.26f; tTurb = 0.08f; tExp = 1.00f; tVortex = 0.0f; tWave = 0.03f; tMusicRing = 0.0f; tFil = 0.22f;
            }
        }

        if (chargePull > 0.01f) {
            tr1 += (220f - tr1) * chargePull;
            tg1 += (76f - tg1) * chargePull;
            tb1 += (76f - tb1) * chargePull;
            tExp -= 0.28f * chargePull;
            tSpin += 1.4f * chargePull;
        }

        float m = Math.min(1f, dt * 4.5f);
        curR1 += (tr1 - curR1) * m; curG1 += (tg1 - curG1) * m; curB1 += (tb1 - curB1) * m;
        curR2 += (tr2 - curR2) * m; curG2 += (tg2 - curG2) * m; curB2 += (tb2 - curB2) * m;
        curRC += (trc - curRC) * m; curGC += (tgc - curGC) * m; curBC += (tbc - curBC) * m;

        curSpinSpeed += (tSpin - curSpinSpeed) * m;
        curTurbulence += (tTurb - curTurbulence) * m;
        curExpand += (tExp - curExpand) * m;
        curVortex += (tVortex - curVortex) * m;
        curWaveAmp += (tWave - curWaveAmp) * m;
        curMusicRingAmp += (tMusicRing - curMusicRingAmp) * m;
        curFilamentAlpha += (tFil - curFilamentAlpha) * m;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }

        float cx = w * 0.5f;
        float cy = h * 0.5f;

        if (ambientMode) {
            float maxShift = 6.0f * getResources().getDisplayMetrics().density;
            float slowT = simTime * (float) (2.0 * Math.PI / 600.0);
            cx += (float) Math.sin(slowT) * maxShift;
            cy += (float) Math.cos(slowT * 1.5f) * maxShift * 0.7f;
        }

        float baseRadius = Math.min(w, h) * 0.305f;
        float breath = (float) Math.sin(simTime * 1.65f) * 0.028f;
        float voiceBoost = fastEnergy * 0.20f + slowEnergy * 0.06f + beatImpulse * 0.10f;
        float musicBoost = slowEnergy * 0.025f + musicDynamics * 0.045f + beatImpulse * 0.085f;
        float audioBoost = voiceBoost * (1.0f - curMusicRingAmp) + musicBoost * curMusicRingAmp;
        float visualEnergy = fastEnergy * (1.0f - curMusicRingAmp) + musicDynamics * curMusicRingAmp;
        float shockKick = (shockwaveProgress < 1.0f)
                ? (float) Math.sin(shockwaveProgress * Math.PI) * 0.15f
                : 0f;
        float globeRadius = baseRadius * (curExpand + breath + audioBoost + shockKick);

        // Layer 1: Subtle ambient halo (delicate tinted cushion on Light Mode, luminous aura on Dark Mode)
        float haloR = globeRadius * (1.64f + slowEnergy * 0.12f + beatImpulse * 0.12f);
        int haloAlpha = lightTheme
                ? Math.max(12, Math.min(46, Math.round((0.10f + visualEnergy * 0.08f + beatImpulse * 0.06f) * 255f)))
                : Math.max(18, Math.min(78, Math.round((0.18f + visualEnergy * 0.12f + beatImpulse * 0.09f) * 255f)));
        spritePaint.setColor(Color.argb(haloAlpha, (int) curR2, (int) curG2, (int) curB2));
        dstRect.set(cx - haloR, cy - haloR, cx + haloR, cy + haloR);
        canvas.drawBitmap(haloSprite, null, dstRect, spritePaint);

        float coreR = globeRadius * (0.72f + visualEnergy * 0.10f + beatImpulse * 0.09f);
        int coreAlpha = lightTheme
                ? Math.max(16, Math.min(64, Math.round((0.14f + visualEnergy * 0.09f + beatImpulse * 0.07f) * 255f)))
                : Math.max(28, Math.min(126, Math.round((0.28f + visualEnergy * 0.16f + beatImpulse * 0.12f) * 255f)));
        addSpritePaint.setColor(Color.argb(coreAlpha, (int) curR1, (int) curG1, (int) curB1));
        dstRect.set(cx - coreR, cy - coreR, cx + coreR, cy + coreR);
        canvas.drawBitmap(haloSprite, null, dstRect, addSpritePaint);

        // Layer 2: Project 3D particle coordinates (110 evenly strided across ALL 3 shells in ambient mode)
        final int step = ambientMode ? 2 : 1;
        final int activeCount = ambientMode ? (TOTAL_PARTICLES / 2) : TOTAL_PARTICLES;
        float rotY = simTime * curSpinSpeed;
        float rotX = 0.24f + (float) Math.sin(simTime * 0.42f) * 0.14f;
        float cosY = (float) Math.cos(rotY);
        float sinY = (float) Math.sin(rotY);
        float cosX = (float) Math.cos(rotX);
        float sinX = (float) Math.sin(rotX);
        float cosY0 = (float) Math.cos(-rotY * 1.40f);
        float sinY0 = (float) Math.sin(-rotY * 1.40f);

        float focal = 3.2f;
        float density = getResources().getDisplayMetrics().density;

        int slot = 0;
        for (int i = 0; i < TOTAL_PARTICLES && slot < activeCount; i += step, slot++) {
            int sh = shell[i];
            float px = baseX[i];
            float py = baseY[i];
            float pz = baseZ[i];
            float rMul = baseR[i];
            float ph = phase[i];

            // Keep main 3D sphere cohesive during music (no chaotic random particle scatter)
            float curl = curTurbulence * (1.0f + visualEnergy * 1.4f * (1.0f - curMusicRingAmp * 0.80f));
            float w1 = (float) Math.sin(simTime * 1.8f + ph + py * 3.1f);
            float w2 = (float) Math.cos(simTime * 1.4f + ph * 1.3f + px * 2.7f);
            rMul += (w1 * 0.55f + w2 * 0.45f) * curl;

            // Voice / music harmonic latitude standing waves (slow silky surface ripple during music)
            if (curWaveAmp > 0.005f) {
                float freq = (sh == 2) ? 4.0f : (curMusicRingAmp > 0.5f ? 3.0f : 5.0f);
                float waveSpeed = 6.0f * (1.0f - curMusicRingAmp * 0.60f);
                float waveGain = (curMusicRingAmp > 0.5f)
                        ? (0.22f + musicDynamics * 0.55f + beatImpulse * 0.50f)
                        : (0.28f + fastEnergy * 1.05f + beatImpulse * 0.55f);
                float latWave = (float) Math.sin(phi0[i] * freq - simTime * waveSpeed + theta0[i] * 2.0f * curMusicRingAmp)
                        * curWaveAmp * waveGain;
                rMul += latWave;
            }

            // Music rhythm: smooth, coherent 3-lobe equatorial orbital soundwave ring
            if (curMusicRingAmp > 0.01f && sh == 2) {
                float eqWave = (float) Math.sin(theta0[i] * 3.0f - simTime * 2.4f);
                py += eqWave * 0.085f * curMusicRingAmp * (0.30f + musicDynamics * 0.55f + beatImpulse * 0.55f);
                rMul += (0.04f + beatImpulse * 0.10f) * curMusicRingAmp;
            }

            // Thinking twin-helix vortex morph
            if (curVortex > 0.01f && sh >= 1) {
                float strand = ((i & 1) == 0) ? 0.0f : (float) Math.PI;
                float spiralAngle = theta0[i] + simTime * 2.4f + py * 3.6f + strand;
                float bandR = 0.86f + 0.14f * (float) Math.cos(py * 2.5f);
                float vx = (float) Math.cos(spiralAngle) * bandR;
                float vz = (float) Math.sin(spiralAngle) * bandR;
                float vy = py * 0.72f;
                px += (vx - px) * curVortex;
                py += (vy - py) * curVortex;
                pz += (vz - pz) * curVortex;
            }

            px *= rMul;
            py *= rMul;
            pz *= rMul;

            float rx, ry, rz;
            if (sh == 0) {
                rx = px * cosY0 - pz * sinY0;
                rz = px * sinY0 + pz * cosY0;
                ry = py;
            } else {
                rx = px * cosY - pz * sinY;
                rz = px * sinY + pz * cosY;
                ry = py;
            }
            float fy = ry * cosX - rz * sinX;
            float fz = ry * sinX + rz * cosX;

            float persp = focal / (focal - fz * 0.85f);
            projX[i] = cx + rx * globeRadius * persp;
            projY[i] = cy + fy * globeRadius * persp;
            projZ[i] = fz;

            float depthNorm = Math.max(0.0f, Math.min(1.0f, (fz + 1.35f) / 2.7f));
            float basePt = (sh == 0 ? 4.4f : (sh == 1 ? 3.9f : 2.8f)) * density;
            float sizeBoost = 1.0f + visualEnergy * (0.24f - curMusicRingAmp * 0.14f) + beatImpulse * (0.18f - curMusicRingAmp * 0.06f);
            projSize[i] = basePt * sizeMul[i] * persp * (0.65f + 0.55f * depthNorm) * sizeBoost;
            projAlpha[i] = (0.16f + 0.84f * depthNorm) * (sh == 2 ? 0.75f : 1.0f);
            sortOrder[slot] = i;
        }

        // Zero-GC insertion sort back-to-front by Z
        for (int i = 1; i < activeCount; i++) {
            int key = sortOrder[i];
            float keyZ = projZ[key];
            int j = i - 1;
            while (j >= 0 && projZ[sortOrder[j]] > keyZ) {
                sortOrder[j + 1] = sortOrder[j];
                j--;
            }
            sortOrder[j + 1] = key;
        }

        // Layer 3: Subtle hairline constellation filaments on front Shell-1 nodes
        if (!ambientMode && curFilamentAlpha > 0.05f) {
            int fCount = 0;
            for (int k = activeCount - 1; k >= 0 && fCount < MAX_FILAMENT_NODES; k--) {
                int idx = sortOrder[k];
                if (shell[idx] == 1 && projZ[idx] > 0.08f) {
                    frontNodes[fCount++] = idx;
                }
            }
            float maxDist = globeRadius * 0.52f;
            float maxDistSq = maxDist * maxDist;
            filamentPaint.setStrokeWidth(Math.max(0.8f, 0.9f * density));
            for (int a = 0; a < fCount; a++) {
                int ia = frontNodes[a];
                float ax = projX[ia];
                float ay = projY[ia];
                for (int b = a + 1; b < fCount; b++) {
                    int ib = frontNodes[b];
                    float dx = ax - projX[ib];
                    float dy = ay - projY[ib];
                    float dSq = dx * dx + dy * dy;
                    if (dSq < maxDistSq) {
                        float strength = (1.0f - (float) Math.sqrt(dSq) / maxDist) * curFilamentAlpha * (0.65f + visualEnergy * 0.35f + beatImpulse * 0.25f);
                        int alpha = Math.max(6, Math.min(96, Math.round(strength * 255f)));
                        filamentPaint.setColor(Color.argb(alpha, (int) curR1, (int) curG1, (int) curB1));
                        canvas.drawLine(ax, ay, projX[ib], projY[ib], filamentPaint);
                    }
                }
            }
        }

        // Layer 4: Draw depth-sorted glowing 3D particles
        for (int k = 0; k < activeCount; k++) {
            int i = sortOrder[k];
            int sh = shell[i];
            float px = projX[i];
            float py = projY[i];
            float r = projSize[i];
            float a = projAlpha[i];

            int red, grn, blu;
            if (sh == 0) {
                float mix = 0.45f + 0.45f * a;
                red = Math.min(255, Math.round(curR1 + (curRC - curR1) * mix));
                grn = Math.min(255, Math.round(curG1 + (curGC - curG1) * mix));
                blu = Math.min(255, Math.round(curB1 + (curBC - curB1) * mix));
            } else if (sh == 1) {
                float t = 0.5f + 0.5f * (float) Math.sin(phase[i] + simTime * 0.85f);
                red = Math.round(curR1 + (curR2 - curR1) * t);
                grn = Math.round(curG1 + (curG2 - curG1) * t);
                blu = Math.round(curB1 + (curB2 - curB1) * t);
            } else {
                red = Math.min(255, Math.round(curR2 * 0.65f + curRC * 0.35f));
                grn = Math.min(255, Math.round(curG2 * 0.65f + curGC * 0.35f));
                blu = Math.min(255, Math.round(curB2 * 0.65f + curBC * 0.35f));
            }

            int alpha = lightTheme
                    ? Math.max(36, Math.min(242, Math.round((0.24f + a * 0.76f) * 238f)))
                    : Math.max(16, Math.min(240, Math.round(a * 228f)));
            addSpritePaint.setColor(Color.argb(alpha, red, grn, blu));
            dstRect.set(px - r, py - r, px + r, py + r);
            canvas.drawBitmap(glowSprite, null, dstRect, addSpritePaint);
        }

        // Layer 5: Subtle concentric music bass-kick pulse halo + Touch Shockwave Ripple
        if (curMusicRingAmp > 0.10f && musicRippleProgress < 1.0f) {
            float easeOut = 1.0f - (1.0f - musicRippleProgress) * (1.0f - musicRippleProgress);
            float pulseR = globeRadius * (0.98f + easeOut * 0.58f);
            float maxAlpha = lightTheme ? 44f : 68f;
            int pulseAlpha = Math.max(0, Math.min(90, Math.round((1.0f - musicRippleProgress) * maxAlpha * curMusicRingAmp)));
            shockwavePaint.setStrokeWidth((1.6f - musicRippleProgress * 0.8f) * density);
            shockwavePaint.setColor(Color.argb(pulseAlpha, (int) curR1, (int) curG1, (int) curB1));
            canvas.drawCircle(cx, cy, pulseR, shockwavePaint);
        }
        if (shockwaveProgress < 1.0f) {
            float easeOut = 1.0f - (1.0f - shockwaveProgress) * (1.0f - shockwaveProgress);
            float waveR = globeRadius * (0.48f + easeOut * 1.12f);
            int waveAlpha = Math.max(0, Math.min(180, Math.round((1.0f - shockwaveProgress) * 180f)));
            shockwavePaint.setStrokeWidth((1.0f - shockwaveProgress) * 3.2f * density + 1.0f * density);
            shockwavePaint.setColor(Color.argb(waveAlpha, (int) curRC, (int) curGC, (int) curBC));
            canvas.drawCircle(cx, cy, waveR, shockwavePaint);
        }
    }
}
