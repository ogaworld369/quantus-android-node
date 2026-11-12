package app.quantusnode.node;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * The network field of the vision UI's Network tab (design
 * sections 2.2 / 2.3, spec section 4.2; stage 1+2 spec S1-01 /
 * S2-01 / S2-02): this node at the centre, one orb per
 * connected peer on a single ring, a quiet flare core, the
 * record ring of the last 48 checkpoint slots, and — in the
 * 24 h segment — the ghost layer of the last day's own
 * checkpoints. It follows the {@link PulseView} discipline
 * — a custom Canvas view whose data-bound motion is driven
 * ONLY by real events the service recorded (a newly observed
 * best block while SYNCED arrives via {@link #onBlockArrived()},
 * a newly recorded checkpoint via
 * {@link #onCheckpointRecorded()}), while the ambient motion
 * (core breathing, orb drift) carries no claim about the data.
 *
 * Truthfulness (design section 3): the orb count is exactly
 * the current peer count (capped at 64 for drawing only; the
 * count text beside the field always shows the real number).
 * Every orb sits on the SAME ring (stage 1 spec S1-01): a
 * dot's position and its distance from the centre carry no
 * per-peer meaning — nothing per-peer is knowable — and the
 * how-to-read sheet says so in words. Lines and packets are
 * drawn to ALL orbs at once: which peer a block came through
 * is not knowable either. The number on the ring is this
 * node's own latest block, a fact of the node itself. The
 * record ring's ticks are the journal's own checkpoint slots
 * (newest at the top, older clockwise, no rotation — it is
 * data, not decoration), and a ghost's angle is the local
 * time its checkpoint was recorded, its brightness the peer
 * count observed then. The radar sweep of the first build is
 * gone: it implied a direction the node cannot know.
 *
 * When the system animator scale is 0, or the view is not on
 * the visible tab, everything stops and the still frame
 * stands on its own. While the node is stopped the whole
 * field sinks dim and no event motion runs.
 *
 * The telemetry starfield of the full design is V3 and is
 * NOT drawn here: no feed has been verified, so there are no
 * dots whose count could be honest.
 */
public class NetworkFieldView extends View {

    private static final int FLARE = Color.rgb(0xFF, 0x6B, 0x35);
    private static final int CONTENT = Color.rgb(0xE8, 0xE6, 0xE0);
    private static final int STOPPED = Color.rgb(0x65, 0x64, 0x62);

    /** Drawing cap for orbs (design 2.2); counts above it still
     *  show as their real number in the text beside the field. */
    private static final int MAX_ORBS = 64;
    private static final double GOLDEN_ANGLE = 2.399963229728653;

    private static final long PACKET_MS = 900L;
    private static final long SONAR_MS = 3600L;
    private static final long FADE_MS = 600L;
    private static final long BREATHE_MS = 2600L;
    private static final long SPIN_MS = 10_000L;
    /** How long the record ring's newest tick stays lit after
     *  a checkpoint lands (stage 2 spec S2-02). */
    private static final long FLASH_MS = 600L;
    /** Every orb sits on this one ring (stage 1 spec S1-01). */
    private static final float RING_FR = 0.78f;
    /** The record ring's radius, as a field-radius fraction. */
    private static final float RECORD_FR = 0.97f;

    /** One block-arrival event: packets first, sonar on landing. */
    private static final class Arrival {
        final long startMs;
        boolean sonarFired;

        Arrival(long startMs) {
            this.startMs = startMs;
        }
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final float[] orbAlpha = new float[MAX_ORBS];
    private final List<Arrival> arrivals = new ArrayList<>();

    // Frame-drawing work set, allocated once and reused (audit
    // F3-L10: onDraw used to mint gradients, effects and per-orb
    // arrays at ~30 fps). The dashed ring's effect is cached
    // alongside and rebuilt only when the density changes.
    private final float[][] orbPos = new float[MAX_ORBS][2];
    private final float[][] orbCtl = new float[MAX_ORBS][2];
    private final float[] pktHead = new float[2];
    private final float[] pktTail = new float[2];
    private DashPathEffect dashEffect;
    private float dashDensity = -1f;

    private boolean fieldActive;
    private boolean running;
    private boolean synced;
    private int peers = -1;
    private long lastFrameMs = -1;
    /** This node's own latest block; -1 while unknown. Drawn
     *  as the tip ring's label (stage 1 spec S1-01). */
    private long bestBlock = -1;
    /** The last 48 checkpoint slots' peer counts (oldest
     *  first, -1 = no checkpoint in the slot); null while
     *  unknown. Drawn as the record ring (S2-02). */
    private int[] recordRing;
    /** Ghost layer data (S2-01): the last 24 h of own
     *  checkpoints — times (unix seconds) and the peer counts
     *  observed at each. Drawn only in the 24 h segment. */
    private long[] ghostTs;
    private int[] ghostPeers;
    private int ghostMaxPeers;
    private boolean showGhosts;
    /** Uptime millis of the last checkpoint-recorded flash. */
    private long flashStartMs = -1;
    /** The aggregate TalkBack readout (S2-04): ONE virtual
     *  node describes the whole field — there is no per-peer
     *  data to give any dot a node of its own. (The design
     *  consultation named ExploreByTouchHelper; this app has
     *  no androidx dependency, so the framework's
     *  AccessibilityNodeProvider serves the same shape.) */
    private String a11yText;

    private final AccessibilityNodeProvider a11yProvider =
            new AccessibilityNodeProvider() {
                @Override
                public AccessibilityNodeInfo createAccessibilityNodeInfo(
                        int virtualViewId) {
                    if (virtualViewId == HOST_VIEW_ID) {
                        AccessibilityNodeInfo info =
                                AccessibilityNodeInfo.obtain(
                                        NetworkFieldView.this);
                        onInitializeAccessibilityNodeInfo(info);
                        info.addChild(NetworkFieldView.this, 0);
                        return info;
                    }
                    if (virtualViewId == 0) {
                        AccessibilityNodeInfo info =
                                AccessibilityNodeInfo.obtain(
                                        NetworkFieldView.this, 0);
                        info.setContentDescription(a11yText);
                        info.setClassName("android.widget.Button");
                        info.setClickable(true);
                        info.addAction(AccessibilityNodeInfo
                                .AccessibilityAction.ACTION_CLICK);
                        Rect bounds = new Rect(0, 0,
                                getWidth(), getHeight());
                        info.setBoundsInParent(bounds);
                        int[] loc = new int[2];
                        getLocationOnScreen(loc);
                        Rect screen = new Rect(bounds);
                        screen.offset(loc[0], loc[1]);
                        info.setBoundsInScreen(screen);
                        return info;
                    }
                    return null;
                }

                @Override
                public boolean performAction(int virtualViewId,
                                             int action,
                                             Bundle arguments) {
                    if (virtualViewId == 0
                            && action == AccessibilityNodeInfo
                                    .ACTION_CLICK) {
                        return performClick();
                    }
                    return false;
                }
            };

    @Override
    public AccessibilityNodeProvider getAccessibilityNodeProvider() {
        return a11yProvider;
    }

    public NetworkFieldView(Context context) {
        super(context);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        textPaint.setTypeface(android.graphics.Typeface.MONOSPACE);
        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    /** The tab became visible / hidden: the frame loop follows. */
    public void setFieldActive(boolean active) {
        fieldActive = active;
        if (active) {
            lastFrameMs = -1;
            invalidate();
        }
    }

    /** Peers right now; -1 while unknown (drawn as none). */
    public void setPeers(int peers) {
        if (this.peers != peers) {
            this.peers = peers;
            invalidate();
        }
    }

    public void setNodeState(boolean running, boolean synced) {
        if (this.running != running || this.synced != synced) {
            this.running = running;
            this.synced = synced;
            if (!running) {
                arrivals.clear();
            }
            invalidate();
        }
    }

    /** This node's own latest block, for the tip-ring label;
     *  -1 hides the label. */
    public void setBestBlock(long best) {
        if (this.bestBlock != best) {
            this.bestBlock = best;
            invalidate();
        }
    }

    /** The record ring's slot window (oldest first, -1 = no
     *  checkpoint in that slot); null hides the data ticks. */
    public void setRecordRing(int[] slotPeers) {
        this.recordRing = slotPeers != null ? slotPeers.clone() : null;
        invalidate();
    }

    /** The ghost layer's data and whether the 24 h segment is
     *  showing. Times are unix seconds, oldest first. */
    public void setGhostData(long[] ts, int[] peers, boolean show) {
        this.ghostTs = ts != null ? ts.clone() : null;
        this.ghostPeers = peers != null ? peers.clone() : null;
        this.showGhosts = show;
        int max = 0;
        if (this.ghostPeers != null) {
            for (int p : this.ghostPeers) {
                if (p > max) {
                    max = p;
                }
            }
        }
        this.ghostMaxPeers = max;
        invalidate();
    }

    /**
     * A checkpoint was just recorded: the record ring's newest
     * tick lights up briefly (stage 2 spec S2-02). Event-bound
     * only — nothing here runs on a timer of its own.
     */
    public void onCheckpointRecorded() {
        flashStartMs = SystemClock.uptimeMillis();
        invalidate();
    }

    /** The aggregate TalkBack readout text (S2-04), refreshed
     *  by the activity on the same cadence as the numbers. */
    public void setA11yText(String text) {
        if (text == null ? a11yText != null : !text.equals(a11yText)) {
            a11yText = text;
            setContentDescription(text);
        }
    }

    /**
     * A block arrival the service actually observed (SYNCED, a
     * higher best than the display baseline). Fires the packet
     * run and, when the packets land, the sonar ring. A no-op
     * when animations are disabled or the node is not synced:
     * events are never manufactured for a hidden or still UI.
     */
    public void onBlockArrived() {
        if (!animationsEnabled() || !running || !synced) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        arrivals.add(new Arrival(now));
        while (arrivals.size() > 4) {
            arrivals.remove(0);
        }
        invalidate();
    }

    private boolean animationsEnabled() {
        try {
            float scale = Settings.Global.getFloat(
                    getContext().getContentResolver(),
                    Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
            return scale != 0f;
        } catch (RuntimeException e) {
            return true;
        }
    }

    // ---- deterministic orb layout (a stage set, not geography) ----

    private float orbAngle(int i) {
        return (float) (i * GOLDEN_ANGLE);
    }

    private float orbRadius(int i, float fieldR) {
        // Stage 1 spec S1-01: every orb on ONE ring. The old
        // sqrt-spread layout gave each dot a different distance,
        // which read as if distance meant something per peer;
        // it cannot — nothing per-peer is knowable.
        return fieldR * RING_FR;
    }

    /** Quadratic-curve control point for orb i's arc. */
    private void controlPoint(float cx, float cy,
                              float ox, float oy, int i, float[] out) {
        float mx = (cx + ox) / 2f;
        float my = (cy + oy) / 2f;
        float dx = ox - cx;
        float dy = oy - cy;
        float dist = (float) Math.hypot(dx, dy);
        if (dist < 1f) {
            out[0] = mx;
            out[1] = my;
            return;
        }
        float bend = (((i * 37) % 11) - 5) * 0.045f;
        float px = -dy / dist;
        float py = dx / dist;
        out[0] = mx + px * bend * dist;
        out[1] = my + py * bend * dist;
    }

    private void quadPoint(float[] p0, float[] c, float[] p1,
                           float t, float[] out) {
        float u = 1f - t;
        out[0] = u * u * p0[0] + 2 * u * t * c[0] + t * t * p1[0];
        out[1] = u * u * p0[1] + 2 * u * t * c[1] + t * t * p1[1];
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float d = getResources().getDisplayMetrics().density;
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        float cx = w / 2f;
        float cy = h * 0.56f;
        // The width factor leaves headroom for the ghost
        // layer, whose oldest dots rest at 1.15 x fieldR.
        float fieldR = Math.min(w * 0.42f, h * 0.30f);
        long now = SystemClock.uptimeMillis();
        boolean anim = animationsEnabled();
        if (!anim && !arrivals.isEmpty()) {
            // Audit F3-L10: a still frame must not freeze a
            // packet mid-flight. When animation turns off, the
            // in-flight events are discarded, not held.
            arrivals.clear();
        }
        float dtMs = lastFrameMs < 0 ? 16f
                : Math.min(100f, now - lastFrameMs);
        lastFrameMs = now;

        int shown = running && peers > 0 ? Math.min(peers, MAX_ORBS) : 0;
        float dim = running ? 1f : 0.38f;

        // Orb fade toward the current count (600 ms, design 2.3).
        for (int i = 0; i < MAX_ORBS; i++) {
            float target = i < shown ? 1f : 0f;
            if (anim && fieldActive) {
                float step = dtMs / (float) FADE_MS;
                if (orbAlpha[i] < target) {
                    orbAlpha[i] = Math.min(target, orbAlpha[i] + step);
                } else if (orbAlpha[i] > target) {
                    orbAlpha[i] = Math.max(target, orbAlpha[i] - step);
                }
            } else {
                orbAlpha[i] = target;
            }
        }

        float[] core = {cx, cy};
        for (int i = 0; i < MAX_ORBS; i++) {
            if (orbAlpha[i] <= 0f) {
                continue;
            }
            float ang = orbAngle(i);
            float rad = orbRadius(i, fieldR);
            float ox = cx + rad * (float) Math.cos(ang);
            float oy = cy + rad * (float) Math.sin(ang);
            if (anim) {
                // Slow drift, amplitude at most 2 dp; phase and
                // period are per-orb constants, so the layout's
                // determinism is untouched (design 2.3).
                long period = 7000L + (i % 5) * 1000L;
                double ph = (now % period) / (double) period
                        * Math.PI * 2 + i;
                ox += (float) (Math.cos(ph) * 2f * d);
                oy += (float) (Math.sin(ph * 0.9) * 2f * d);
            }
            orbPos[i][0] = ox;
            orbPos[i][1] = oy;
            controlPoint(cx, cy, ox, oy, i, orbCtl[i]);
        }

        // Ghost layer (stage 2 spec S2-01), drawn under
        // everything else: the last 24 hours of this node's
        // OWN checkpoints. A ghost's angle is the local time
        // of day its checkpoint was recorded, its radius grows
        // with age, and its brightness is the peer count
        // observed at that checkpoint. Shown only in the 24 h
        // segment. Checkpoints are this node's own records, so
        // the ghosts wear the flare (self) colour.
        if (showGhosts && ghostTs != null && ghostPeers != null) {
            long nowSec = System.currentTimeMillis() / 1000L;
            java.time.ZoneId zone = java.time.ZoneId.systemDefault();
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(FLARE);
            int count = Math.min(ghostTs.length, ghostPeers.length);
            for (int i = 0; i < count; i++) {
                long age = nowSec - ghostTs[i];
                if (age < 0 || age > 86_400L) {
                    continue;
                }
                int secOfDay = java.time.LocalDateTime.ofInstant(
                        java.time.Instant.ofEpochSecond(ghostTs[i]),
                        zone).toLocalTime().toSecondOfDay();
                double ang = UiLogic.ghostAngleRad(secOfDay);
                float rad = fieldR * UiLogic.ghostRadiusFactor(age);
                float gx = cx + rad * (float) Math.cos(ang);
                float gy = cy + rad * (float) Math.sin(ang);
                paint.setAlpha((int) (UiLogic.ghostAlpha(
                        ghostPeers[i], ghostMaxPeers) * dim));
                canvas.drawCircle(gx, gy,
                        UiLogic.ghostRadiusDp(
                                ghostPeers[i], ghostMaxPeers) * d,
                        paint);
            }
            paint.setAlpha(255);
        }

        // The tip ring (stage 1 spec S1-01): one quiet circle
        // the orbs sit on, flare at 22%, with this node's own
        // latest block labelled at the top — a fact of the
        // node itself, in monospace like every observed value.
        {
            float ringR = fieldR * RING_FR;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1f, 0.75f * d));
            paint.setColor(FLARE);
            paint.setAlpha((int) (0.22f * dim * 255f));
            canvas.drawCircle(cx, cy, ringR, paint);
            paint.setAlpha(255);
            if (running && bestBlock >= 0) {
                textPaint.setColor(CONTENT);
                textPaint.setTextSize(11f * d);
                textPaint.setAlpha((int) (dim * 255f));
                canvas.drawText("#" + UiLogic.grouped(bestBlock),
                        cx, cy - ringR - 6f * d, textPaint);
                textPaint.setAlpha(255);
            }
        }

        // Arcs: core to every visible orb, one quiet line each —
        // the total of the connections, never a per-peer claim.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1f * d);
        paint.setColor(CONTENT);
        for (int i = 0; i < MAX_ORBS; i++) {
            if (orbAlpha[i] <= 0f) {
                continue;
            }
            paint.setAlpha((int) (0.28f * orbAlpha[i] * dim * 255f));
            path.reset();
            path.moveTo(cx, cy);
            path.quadTo(orbCtl[i][0], orbCtl[i][1],
                    orbPos[i][0], orbPos[i][1]);
            canvas.drawPath(path, paint);
        }
        paint.setAlpha(255);

        // Packets of live arrivals, along every arc at once.
        boolean arrivalLive = false;
        Iterator<Arrival> it = arrivals.iterator();
        while (it.hasNext()) {
            Arrival a = it.next();
            float p = (now - a.startMs) / (float) PACKET_MS;
            if (p >= 1f) {
                if (!a.sonarFired) {
                    a.sonarFired = true;
                }
                continue;
            }
            if (p < 0f) {
                p = 0f;
            }
            arrivalLive = true;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.6f * d);
            paint.setColor(FLARE);
            for (int i = 0; i < MAX_ORBS; i++) {
                if (orbAlpha[i] <= 0f) {
                    continue;
                }
                float tail = Math.max(0f, p - 0.14f);
                quadPoint(orbPos[i], orbCtl[i], core, p, pktHead);
                quadPoint(orbPos[i], orbCtl[i], core, tail, pktTail);
                paint.setAlpha((int) (0.9f * orbAlpha[i] * 255f));
                canvas.drawLine(pktTail[0], pktTail[1],
                        pktHead[0], pktHead[1], paint);
                canvas.drawCircle(pktHead[0], pktHead[1],
                        1.8f * d, paint);
            }
        }
        paint.setAlpha(255);

        // Sonar rings of landed arrivals (3.6 s, (1-t)^2 decay —
        // the PulseView ripple language, widened to the field).
        it = arrivals.iterator();
        while (it.hasNext()) {
            Arrival a = it.next();
            if (!a.sonarFired) {
                continue;
            }
            float t = (now - a.startMs - PACKET_MS) / (float) SONAR_MS;
            if (t >= 1f) {
                it.remove();
                continue;
            }
            if (t < 0f) {
                t = 0f;
            }
            arrivalLive = true;
            float eased = 1f - (1f - t) * (1f - t) * (1f - t);
            float radius = (22f * d) + (fieldR * 1.05f - 22f * d) * eased;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.5f * d);
            paint.setColor(FLARE);
            paint.setAlpha((int) (0.42f * (1f - t) * (1f - t) * 255f));
            canvas.drawCircle(cx, cy, radius, paint);
        }
        paint.setAlpha(255);

        // Orbs: one halo layer, one body, all identical.
        for (int i = 0; i < MAX_ORBS; i++) {
            if (orbAlpha[i] <= 0f) {
                continue;
            }
            float a = orbAlpha[i] * dim;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(CONTENT);
            paint.setAlpha((int) (0.16f * a * 255f));
            canvas.drawCircle(orbPos[i][0], orbPos[i][1],
                    3.5f * 2.2f * d, paint);
            paint.setAlpha((int) (0.85f * a * 255f));
            canvas.drawCircle(orbPos[i][0], orbPos[i][1],
                    3.5f * d, paint);
        }
        paint.setAlpha(255);

        // The record ring (stage 2 spec S2-02): 48 ticks, one
        // per checkpoint slot of the last 8 hours — DATA, so
        // it does not rotate. The newest slot sits at the top
        // and older slots run clockwise. A tick is lit (flare:
        // this node's own record) when its slot holds a
        // checkpoint, dark grey when it does not; until the
        // window is known at all, every tick stays dark. For
        // FLASH_MS after a checkpoint lands, the newest tick
        // burns brighter and longer.
        float spin = anim
                ? (float) (((now % SPIN_MS) / (double) SPIN_MS)
                        * Math.PI * 2)
                : 0f;
        {
            float recR = fieldR * RECORD_FR;
            float flash = 0f;
            if (flashStartMs > 0) {
                float p = (now - flashStartMs) / (float) FLASH_MS;
                if (p < 1f) {
                    flash = 1f - p;
                } else {
                    flashStartMs = -1;
                }
            }
            paint.setStyle(Paint.Style.STROKE);
            // Residual F-22: with no ring data yet (null),
            // the unlit ticks draw at a fraction of the
            // alpha, so "not measured yet" is visibly
            // distinct from a measured all-empty window
            // (every slot -1) — the same distinction the
            // numbers make ("—" versus "0").
            boolean ringKnown = recordRing != null;
            for (int slot = 0; slot < 48; slot++) {
                double ang = -Math.PI / 2
                        + (47 - slot) * (Math.PI * 2 / 48.0);
                boolean lit = ringKnown
                        && slot < recordRing.length
                        && recordRing[slot] >= 0;
                float len = 4f * d;
                if (lit) {
                    paint.setColor(FLARE);
                    paint.setStrokeWidth(1.4f * d);
                    paint.setAlpha((int) (0.85f * dim * 255f));
                } else {
                    paint.setColor(Color.rgb(0x2A, 0x2A, 0x33));
                    paint.setStrokeWidth(1.2f * d);
                    paint.setAlpha((int) (dim
                            * (ringKnown ? 1f : 0.35f) * 255f));
                }
                if (slot == 47 && flash > 0f) {
                    len += 4f * d * flash;
                    paint.setColor(FLARE);
                    paint.setStrokeWidth(1.4f * d + 1f * d * flash);
                    paint.setAlpha(255);
                }
                canvas.drawLine(
                        cx + (recR - len) * (float) Math.cos(ang),
                        cy + (recR - len) * (float) Math.sin(ang),
                        cx + recR * (float) Math.cos(ang),
                        cy + recR * (float) Math.sin(ang), paint);
            }
            paint.setAlpha(255);
        }
        // The dashed ring (ambient decoration, no data claim)
        // turns by rotating the canvas under a cached,
        // phase-free effect, instead of minting a new
        // DashPathEffect every frame (audit F3-L10).
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(running ? FLARE : STOPPED);
        paint.setStrokeWidth(1.2f * d);
        if (dashEffect == null || dashDensity != d) {
            dashEffect = new DashPathEffect(
                    new float[]{6f * d, 8f * d}, 0f);
            dashDensity = d;
        }
        paint.setPathEffect(dashEffect);
        paint.setAlpha((int) (0.35f * dim * 255f));
        canvas.save();
        canvas.rotate((float) Math.toDegrees(spin), cx, cy);
        canvas.drawCircle(cx, cy, 46f * d, paint);
        canvas.restore();
        paint.setPathEffect(null);
        paint.setAlpha(255);

        // Core: a quiet glowing orb (breathe 2.6 s). Stopped, it
        // is a hollow grey ring and nothing around it moves.
        float breathe = 1f;
        if (anim) {
            double ph = (now % BREATHE_MS) / (double) BREATHE_MS
                    * Math.PI * 2;
            breathe = 1f + 0.06f * (float) Math.sin(ph);
        }
        if (running) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(FLARE);
            paint.setAlpha((int) (0.10f * 255f));
            canvas.drawCircle(cx, cy, 30f * d * breathe, paint);
            paint.setAlpha((int) (0.18f * 255f));
            canvas.drawCircle(cx, cy, 19f * d * breathe, paint);
            paint.setAlpha(255);
            canvas.drawCircle(cx, cy, 10f * d * breathe, paint);
        } else {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.5f * d);
            paint.setColor(STOPPED);
            paint.setAlpha(255);
            canvas.drawCircle(cx, cy, 10f * d, paint);
        }
        paint.setAlpha(255);

        if (fieldActive && anim) {
            // ~30 fps while the tab is in front of the user; the
            // loop dies with the tab, and a reduced-motion device
            // gets the still frame only.
            postInvalidateDelayed(33);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        fieldActive = false;
    }
}
