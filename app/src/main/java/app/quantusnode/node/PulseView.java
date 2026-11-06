package app.quantusnode.node;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * The node's pulse (versionCode 26 redesign): a small state-coloured
 * core in a wide quiet field — a filled dot of 8 dp inside a ring of
 * radius 22 dp — and, only while the node is SYNCED, one ripple per
 * poll that first observed a higher best block (the service records
 * the observation; this view only renders the event it is handed).
 *
 * A ripple runs 1,400 ms, radius 22 → 68 dp with ease-out cubic
 * easing, alpha 0.42 → 0 following (1 − t)². At most four run at
 * once; a fifth ends the oldest. Each ripple keeps the core colour
 * of the moment it was fired: a state change mid-flight never
 * recolours a ripple already on screen (audit finding L-4).
 * Nothing else moves: no breathing, no rotation, no glow. When the
 * system animator scale is 0 (animations disabled) ripples are
 * omitted entirely. The stopped state draws a grey ring with a
 * HOLLOW dot; every other state a filled dot. Decorative: not a
 * TalkBack target of its own.
 */
public class PulseView extends View {

    /** Ripple lifetime, ms. */
    private static final long RIPPLE_MS = 1400L;
    /** At most this many ripples are on screen at once. */
    private static final int MAX_RIPPLES = 4;

    /** One live ripple: when it was fired, and in which colour. */
    private static final class Ripple {
        final long startMs;
        final int color;

        Ripple(long startMs, int color) {
            this.startMs = startMs;
            this.color = color;
        }
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** The live ripples, oldest first. */
    private final List<Ripple> ripples = new ArrayList<>();
    /** Core colour of the current state. */
    private int coreColor = 0xFF656462;
    /** True for the stopped state: hollow dot instead of filled. */
    private boolean hollowCore = true;

    public PulseView(Context context) {
        super(context);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /**
     * Set the core for a state: its colour, and whether the dot is
     * hollow (stopped) or filled. Leaving the filled states for the
     * hollow (stopped) one ends any live ripples — a ripple never
     * outlives the state that made it meaningful, and a stop in
     * flight kills them at once.
     */
    public void setState(int color, boolean hollow) {
        if (color != coreColor || hollow != hollowCore) {
            coreColor = color;
            hollowCore = hollow;
            if (hollow) {
                ripples.clear();
            }
            invalidate();
        }
    }

    /** End all live ripples now (e.g. a stop was just requested). */
    public void clearRipples() {
        if (!ripples.isEmpty()) {
            ripples.clear();
            invalidate();
        }
    }

    /**
     * Fire one ripple — called by the activity when the service's
     * observation record advances while SYNCED. A no-op when the
     * system animator scale is 0 (animations off) or the core is the
     * stopped one.
     */
    public void pulse() {
        if (hollowCore || !animationsEnabled()) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        ripples.add(new Ripple(now, coreColor));
        while (ripples.size() > MAX_RIPPLES) {
            ripples.remove(0);
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

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        float d = getResources().getDisplayMetrics().density;
        int want = (int) (160 * d);
        int w = resolveSize(want, widthMeasureSpec);
        int h = resolveSize(want, heightMeasureSpec);
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float d = getResources().getDisplayMetrics().density;
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        long now = SystemClock.uptimeMillis();

        // Ripples first (they expand around the core; all strokes
        // are thin, so nothing visually covers anything else).
        boolean live = false;
        Iterator<Ripple> it = ripples.iterator();
        while (it.hasNext()) {
            Ripple ripple = it.next();
            float t = (now - ripple.startMs) / (float) RIPPLE_MS;
            if (t >= 1f) {
                it.remove();
                continue;
            }
            if (t < 0f) {
                t = 0f;
            }
            live = true;
            float eased = 1f - (1f - t) * (1f - t) * (1f - t);
            float radius = (22f + (68f - 22f) * eased) * d;
            int alpha = (int) (0.42f * (1f - t) * (1f - t) * 255f);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.5f * d);
            paint.setColor(ripple.color);
            paint.setAlpha(alpha);
            canvas.drawCircle(cx, cy, radius, paint);
        }

        // Centre ring: state colour at 50%.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.5f * d);
        paint.setColor(coreColor);
        paint.setAlpha(128);
        canvas.drawCircle(cx, cy, 22f * d, paint);

        // Centre dot: 8 dp across. Filled, or hollow when stopped.
        if (hollowCore) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.5f * d);
            paint.setColor(coreColor);
            paint.setAlpha(255);
            canvas.drawCircle(cx, cy, 4f * d, paint);
        } else {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(coreColor);
            paint.setAlpha(255);
            canvas.drawCircle(cx, cy, 4f * d, paint);
        }
        paint.setAlpha(255);

        if (live) {
            // ~60 fps while a ripple is alive; the view is otherwise
            // completely still and costs nothing.
            postInvalidateDelayed(16);
        }
    }
}
