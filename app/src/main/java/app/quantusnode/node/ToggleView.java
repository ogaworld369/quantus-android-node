package app.quantusnode.node;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * The settings toggle (versionCode 26): a Canvas-drawn switch,
 * 52×32 dp of track with a 24 dp thumb and 4 dp of inner padding,
 * centred in a touch target at least 48 dp tall. Colours are the
 * Quantus brand tokens: on is the flare track with the content
 * thumb; off is the #2C2C2A track with the muted thumb; disabled
 * (the settings lock) dims the whole glyph to about 38%. State
 * changes are instant — no animation, like every other transition
 * in this UI.
 *
 * Why not the platform Switch: dressed with code-generated track
 * and thumb drawables it rendered as a giant clipped half-disc on
 * GrapheneOS (device finding, 2026-11-06) — the widget's layout
 * maths for the thumb/track pair did not survive the drawables'
 * intrinsic sizes on that platform. Drawing the glyph directly
 * leaves nothing to negotiate: the shapes on screen are exactly
 * the spec's.
 *
 * Accessibility: the view is focusable and clickable, reports
 * itself as a switch (checkable, with the current checked state)
 * in its accessibility node info, and posts a content-change
 * event on every state change, so a screen reader announces the
 * new state when the toggle is used — the semantics the platform
 * Switch provided, kept.
 */
public class ToggleView extends View {

    /** Mirrors CompoundButton's listener shape, for this view. */
    public interface OnCheckedChangeListener {
        void onCheckedChanged(ToggleView view, boolean isChecked);
    }

    private static final int TRACK_ON = 0xFFFF6B35;
    private static final int THUMB_ON = 0xFFE8E6E0;
    private static final int TRACK_OFF = 0xFF2C2C2A;
    private static final int THUMB_OFF = 0xFF91908C;
    private static final int FOCUS_RING = 0xFFFF6B35;
    /** Disabled alpha, about 38% — the palette's dim convention. */
    private static final int DISABLED_ALPHA = 0x61;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF trackRect = new RectF();

    private boolean checked;
    private OnCheckedChangeListener listener;

    public ToggleView(Context context) {
        super(context);
        setClickable(true);
        setFocusable(true);
    }

    public boolean isChecked() {
        return checked;
    }

    /**
     * Set the state. Like CompoundButton, a change fires the
     * listener; callers that sync the view from stored state guard
     * against re-entry themselves (the settings screen's
     * syncingSettings flag), exactly as they did with Switch.
     */
    public void setChecked(boolean value) {
        if (checked == value) {
            return;
        }
        checked = value;
        invalidate();
        sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
        if (listener != null) {
            listener.onCheckedChanged(this, checked);
        }
    }

    public void toggle() {
        setChecked(!checked);
    }

    public void setOnCheckedChangeListener(OnCheckedChangeListener l) {
        listener = l;
    }

    @Override
    public boolean performClick() {
        if (isEnabled()) {
            toggle();
        }
        return super.performClick();
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        invalidate();
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName("android.widget.Switch");
        info.setCheckable(true);
        info.setChecked(checked);
    }

    @Override
    protected void onFocusChanged(boolean gainFocus, int direction,
                                  android.graphics.Rect previouslyFocusedRect) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        float d = getResources().getDisplayMetrics().density;
        // The glyph is 52×32 dp; the view never goes below a
        // 52×48 dp target, so the toggle alone clears 48 dp.
        setMeasuredDimension(
                resolveSize((int) (52 * d), widthMeasureSpec),
                resolveSize((int) (48 * d), heightMeasureSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float d = getResources().getDisplayMetrics().density;
        float trackW = 52 * d;
        float trackH = 32 * d;
        float left = (getWidth() - trackW) / 2f;
        float top = (getHeight() - trackH) / 2f;
        trackRect.set(left, top, left + trackW, top + trackH);
        int alpha = isEnabled() ? 0xFF : DISABLED_ALPHA;

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(checked ? TRACK_ON : TRACK_OFF);
        paint.setAlpha(alpha);
        canvas.drawRoundRect(trackRect, 16 * d, 16 * d, paint);

        // The thumb rides 4 dp inside the track: its centre sits
        // 16 dp from the near edge (4 padding + 12 radius), and
        // mirrors with the layout direction, like the platform
        // switch — "on" is always the trailing side.
        boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        boolean onAtRight = checked != rtl;
        float cx = onAtRight
                ? trackRect.right - 16 * d
                : trackRect.left + 16 * d;
        float cy = (top + trackRect.bottom) / 2f;
        paint.setColor(checked ? THUMB_ON : THUMB_OFF);
        paint.setAlpha(alpha);
        canvas.drawCircle(cx, cy, 12 * d, paint);

        if (hasFocus()) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2 * d);
            paint.setColor(FOCUS_RING);
            paint.setAlpha(0xFF);
            float inset = 4 * d;
            trackRect.inset(-inset, -inset);
            canvas.drawRoundRect(trackRect, 20 * d, 20 * d, paint);
            trackRect.inset(inset, inset);
        }
        paint.setAlpha(0xFF);
    }
}
