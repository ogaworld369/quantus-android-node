package app.quantusnode.node;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * The settings icon of the redesign (spec F): three horizontal
 * sliders drawn on Canvas — no image asset, no icon font. The glyph
 * occupies a 24 dp box centred in the view (the activity gives the
 * view a 48 dp touch target): lines at y = 6 / 12 / 18 dp from
 * x = 3 to 21 dp, 2 dp stroke with round caps; knobs centred at
 * (8, 6), (16, 12) and (10, 18), radius 2.5 dp with a 2 dp outline,
 * filled with the background colour. Each line is split around its
 * knob so it never crosses the knob's disc. The glyph does NOT
 * mirror in RTL. Lines and outlines are label-2 (#91908C) on the
 * home background (#0E0E0E); the press ripple and the keyboard focus
 * frame (2 dp accent, rounded 12 dp) are handled with the accent
 * colour (flare #FF6B35).
 */
public class SettingsIconView extends View {

    private static final int GLYPH = 0xFF91908C;
    private static final int KNOB_FILL = 0xFF0E0E0E;
    private static final int ACCENT = 0xFFFF6B35;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF focusRect = new RectF();

    public SettingsIconView(Context context) {
        super(context);
        setClickable(true);
        setFocusable(true);
    }

    @Override
    protected void onFocusChanged(boolean gainFocus, int direction,
                                  android.graphics.Rect previouslyFocusedRect) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float d = getResources().getDisplayMetrics().density;
        // The 24 dp glyph box, centred in whatever box we were given.
        float ox = (getWidth() - 24f * d) / 2f;
        float oy = (getHeight() - 24f * d) / 2f;

        paint.setColor(GLYPH);
        paint.setStrokeWidth(2f * d);
        paint.setStrokeCap(Paint.Cap.ROUND);

        float[] ys = {6f, 12f, 18f};
        float[] knobX = {8f, 16f, 10f};
        // The knob's outer edge: radius 2.5 dp + half its 2 dp
        // outline. Lines stop there, so they never cross the disc.
        float knobOuter = 3.5f;
        for (int i = 0; i < 3; i++) {
            float y = oy + ys[i] * d;
            float kx = ox + knobX[i] * d;
            paint.setStyle(Paint.Style.STROKE);
            canvas.drawLine(ox + 3f * d, y, kx - knobOuter * d, y, paint);
            canvas.drawLine(kx + knobOuter * d, y, ox + 21f * d, y, paint);
        }
        for (int i = 0; i < 3; i++) {
            float kx = ox + knobX[i] * d;
            float ky = oy + ys[i] * d;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(KNOB_FILL);
            canvas.drawCircle(kx, ky, 2.5f * d, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2f * d);
            paint.setColor(GLYPH);
            canvas.drawCircle(kx, ky, 2.5f * d, paint);
        }

        if (hasFocus()) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2f * d);
            paint.setColor(ACCENT);
            float inset = 1f * d;
            focusRect.set(inset, inset, getWidth() - inset, getHeight() - inset);
            canvas.drawRoundRect(focusRect, 12f * d, 12f * d, paint);
        }
        paint.setColor(GLYPH);
    }
}
