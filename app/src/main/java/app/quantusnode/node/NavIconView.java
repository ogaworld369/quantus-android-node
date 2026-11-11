package app.quantusnode.node;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/**
 * The bottom-navigation glyphs of the vision UI (mock v5's three
 * outline icons — home, network, member card — drawn on Canvas
 * in the same 24-unit grid, 1.7 stroke). Colour is set from
 * outside: the active tab is content-coloured, the rest faint.
 * Decorative beside its text label, so not a TalkBack target.
 */
public class NavIconView extends View {

    public static final int ICON_HOME = 0;
    public static final int ICON_NETWORK = 1;
    public static final int ICON_MEMBER = 2;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final int icon;
    private int color;

    public NavIconView(Context context, int icon, int color) {
        super(context);
        this.icon = icon;
        this.color = color;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }

    public void setIconColor(int color) {
        if (this.color != color) {
            this.color = color;
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float scale = Math.min(getWidth(), getHeight()) / 24f;
        float ox = (getWidth() - 24f * scale) / 2f;
        float oy = (getHeight() - 24f * scale) / 2f;
        canvas.save();
        canvas.translate(ox, oy);
        canvas.scale(scale, scale);
        paint.setColor(color);
        paint.setStrokeWidth(1.7f);
        path.reset();
        switch (icon) {
            case ICON_HOME:
                // M4 10.5 12 4l8 6.5V19a1 1 0 0 1-1 1h-4.5v-5.5h-5V20H5
                // a1 1 0 0 1-1-1z (mock path, simplified joins)
                path.moveTo(4f, 10.5f);
                path.lineTo(12f, 4f);
                path.lineTo(20f, 10.5f);
                path.lineTo(20f, 19f);
                path.lineTo(14.5f, 20f);
                path.lineTo(14.5f, 14.5f);
                path.lineTo(9.5f, 14.5f);
                path.lineTo(9.5f, 20f);
                path.lineTo(4f, 20f);
                path.close();
                canvas.drawPath(path, paint);
                break;
            case ICON_NETWORK:
                canvas.drawCircle(12f, 12f, 2.6f, paint);
                canvas.drawCircle(4.5f, 6f, 1.6f, paint);
                canvas.drawCircle(19.5f, 7f, 1.6f, paint);
                canvas.drawCircle(6f, 19f, 1.6f, paint);
                canvas.drawCircle(18.5f, 18f, 1.6f, paint);
                path.moveTo(10f, 10.4f);
                path.lineTo(5.8f, 7f);
                path.moveTo(14f, 10.8f);
                path.lineTo(18.2f, 8f);
                path.moveTo(10.2f, 14f);
                path.lineTo(7.2f, 17.8f);
                path.moveTo(14f, 13.8f);
                path.lineTo(17.4f, 17f);
                canvas.drawPath(path, paint);
                break;
            case ICON_MEMBER:
            default:
                canvas.drawRoundRect(new RectF(3.5f, 5f, 20.5f, 19f),
                        3f, 3f, paint);
                canvas.drawCircle(9f, 11f, 2f, paint);
                path.moveTo(6f, 16f);
                path.cubicTo(6.6f, 14.6f, 7.7f, 14f, 9f, 14f);
                path.cubicTo(10.3f, 14f, 11.4f, 14.6f, 12f, 16f);
                path.moveTo(14.5f, 10f);
                path.lineTo(17.5f, 10f);
                path.moveTo(14.5f, 13f);
                path.lineTo(16.5f, 13f);
                canvas.drawPath(path, paint);
                break;
        }
        canvas.restore();
    }
}
