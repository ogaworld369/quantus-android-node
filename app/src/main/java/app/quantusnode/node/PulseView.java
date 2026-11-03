package app.quantusnode.node;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.View;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * "気配" — the pulse. Almost nothing moves here.
 *
 * A faint still ring and a small dim centre dot are always present.
 * When (and only when) a new best block is observed, one quiet ripple
 * expands from the centre and fades out over ~2.6 s. No looping
 * animation, no colours shouting: if the chain is quiet, so is this view.
 */
public class PulseView extends View {

    private static final long RIPPLE_MS = 2600;
    private static final int MAX_RIPPLES = 4;

    private final List<Long> rippleStarts = new ArrayList<>();
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ripplePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public PulseView(Context context) {
        super(context);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(dp(1));
        ringPaint.setColor(Color.argb(36, 150, 180, 170));
        dotPaint.setStyle(Paint.Style.FILL);
        dotPaint.setColor(Color.argb(110, 150, 180, 170));
        ripplePaint.setStyle(Paint.Style.STROKE);
        ripplePaint.setStrokeWidth(dp(1.2f));
    }

    /** One new block was observed: emit exactly one ripple. */
    public void pulse() {
        long now = SystemClock.uptimeMillis();
        synchronized (rippleStarts) {
            rippleStarts.add(now);
            while (rippleStarts.size() > MAX_RIPPLES) {
                rippleStarts.remove(0);
            }
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float maxR = Math.min(cx, cy) * 0.92f;
        if (maxR <= 0) {
            return;
        }

        // Still elements: one faint ring, one dim dot. That is the idle state.
        canvas.drawCircle(cx, cy, maxR * 0.30f, ringPaint);
        canvas.drawCircle(cx, cy, dp(3), dotPaint);

        long now = SystemClock.uptimeMillis();
        boolean animating = false;
        synchronized (rippleStarts) {
            Iterator<Long> it = rippleStarts.iterator();
            while (it.hasNext()) {
                long t = now - it.next();
                if (t < 0) {
                    animating = true;
                    continue;
                }
                if (t >= RIPPLE_MS) {
                    it.remove();
                    continue;
                }
                animating = true;
                float f = t / (float) RIPPLE_MS;
                // Ease-out: quick to leave the centre, slow to disappear.
                float eased = 1f - (1f - f) * (1f - f);
                float r = maxR * (0.08f + 0.92f * eased);
                int alpha = (int) (80 * (1f - f));
                ripplePaint.setColor(Color.argb(alpha, 150, 180, 170));
                canvas.drawCircle(cx, cy, r, ripplePaint);
            }
        }
        if (animating) {
            // Only while a ripple is alive; otherwise the view is static
            // and costs nothing.
            postInvalidateDelayed(50);
        }
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
