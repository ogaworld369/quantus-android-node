package app.quantusnode.node;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;

/**
 * The member card's body (design 2.7, spec 4.3): a floating
 * card on the surface gradient with a glowing flare edge. The
 * edge is a sweep gradient — a mostly quiet flare at low alpha
 * with one bright segment — turning once per 10 seconds, plus a
 * soft gloss drifting across the top. Both are pure surface
 * motion (section 7.6 UI-layer motion): they claim nothing
 * about any data, and both stop dead when the system animator
 * scale is 0, leaving the card's still form complete.
 *
 * Drawn as the background layer of the card's frame; the card's
 * text content sits in a sibling view on top. No blur filters
 * (design 2.2's low-end rule): the outer glow is layered
 * strokes at low alpha instead.
 */
public class GlowCardView extends View {

    private static final int FLARE = Color.rgb(0xFF, 0x6B, 0x35);
    private static final int SURFACE2 = Color.rgb(0x20, 0x20, 0x20);
    private static final int VOID = Color.rgb(0x0E, 0x0E, 0x0E);
    private static final long SPIN_MS = 10_000L;
    private static final long GLOSS_MS = 9_000L;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private boolean cardActive;

    public GlowCardView(Context context) {
        super(context);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /**
     * A background layer claims no size of its own. The card's
     * frame sizes itself from the text content (the frame's other
     * child), and this view — a MATCH_PARENT child — is then
     * re-measured to exactly that size. Without this, the default
     * View measurement takes the full offered height under an
     * AT_MOST spec, the card balloons to the screen's height, and
     * its top slides over the member tab's title (device finding,
     * 2026-11-09).
     */
    @Override
    protected void onMeasure(int widthMeasureSpec,
                             int heightMeasureSpec) {
        setMeasuredDimension(resolveSize(0, widthMeasureSpec),
                resolveSize(0, heightMeasureSpec));
    }

    /** The member tab became visible / hidden. */
    public void setCardActive(boolean active) {
        cardActive = active;
        if (active) {
            invalidate();
        }
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
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float d = getResources().getDisplayMetrics().density;
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        float radius = 27f * d;
        boolean anim = animationsEnabled();
        long now = SystemClock.uptimeMillis();

        // Body: the 165-degree surface gradient of the mock.
        rect.set(0.5f * d, 0.5f * d, w - 0.5f * d, h - 0.5f * d);
        LinearGradient body = new LinearGradient(
                0, 0, w * 0.3f, h,
                SURFACE2, VOID, Shader.TileMode.CLAMP);
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(body);
        canvas.drawRoundRect(rect, radius, radius, paint);
        paint.setShader(null);

        // Outer glow: two wide, faint strokes under the edge.
        float angle = anim
                ? (float) (((now % SPIN_MS) / (double) SPIN_MS) * 360.0)
                : 120f;
        SweepGradient edge = new SweepGradient(w / 2f, h / 2f,
                new int[]{Color.argb(26, 0xFF, 0x6B, 0x35),
                        Color.argb(26, 0xFF, 0x6B, 0x35),
                        FLARE,
                        Color.argb(26, 0xFF, 0x6B, 0x35)},
                new float[]{0f, 0.58f, 0.79f, 0.94f});
        Matrix m = new Matrix();
        m.setRotate(angle, w / 2f, h / 2f);
        edge.setLocalMatrix(m);
        paint.setStyle(Paint.Style.STROKE);
        paint.setShader(edge);
        paint.setStrokeWidth(9f * d);
        paint.setAlpha(70);
        rect.inset(2f * d, 2f * d);
        canvas.drawRoundRect(rect, radius, radius, paint);
        paint.setAlpha(255);
        paint.setStrokeWidth(1f * d);
        rect.inset(-2f * d, -2f * d);
        canvas.drawRoundRect(rect, radius, radius, paint);
        paint.setShader(null);

        // Gloss: a soft light wandering the top of the card.
        float gx = w * 0.2f;
        float gy = h * 0.1f;
        if (anim) {
            double ph = (now % GLOSS_MS) / (double) GLOSS_MS;
            gx = (float) (w * (0.15 + 0.7 * ph));
            gy = (float) (h * (0.08 + 0.1 * Math.sin(ph * Math.PI)));
        }
        RadialGradient gloss = new RadialGradient(
                gx, gy, w * 0.55f,
                Color.argb(18, 0xE8, 0xE6, 0xE0),
                Color.argb(0, 0xE8, 0xE6, 0xE0),
                Shader.TileMode.CLAMP);
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(gloss);
        canvas.drawRoundRect(rect, radius, radius, paint);
        paint.setShader(null);

        if (cardActive && anim) {
            postInvalidateDelayed(33);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        cardActive = false;
    }
}
