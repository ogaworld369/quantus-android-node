package app.quantusnode.node;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

/**
 * The hero sparkline of the network page (stage 2 spec
 * S2-02): the peer counts of the last 48 checkpoint slots,
 * one point per slot, oldest at the left. A slot with no
 * checkpoint BREAKS the line — a gap in the record is drawn
 * as a gap, never bridged by interpolation (design section
 * 3: a pretty chart must not manufacture continuity the
 * journal does not have). The last point is emphasised; a
 * window whose maximum is 0 stays flat on the baseline (0 is
 * a real observation, drawn as one). No axes, no labels: the
 * numbers live in the hero text beside it.
 */
public class SparklineView extends View {

    private static final int LINE = Color.rgb(0xE8, 0xE6, 0xE0);

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** Slot peer counts, oldest first; -1 = no checkpoint. */
    private int[] slots;

    public SparklineView(Context context) {
        super(context);
        setImportantForAccessibility(
                IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    public void setSlots(int[] slotPeers) {
        this.slots = slotPeers != null ? slotPeers.clone() : null;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (slots == null || slots.length == 0) {
            return;
        }
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        float d = getResources().getDisplayMetrics().density;
        int max = 0;
        int lastIdx = -1;
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] > max) {
                max = slots[i];
            }
            if (slots[i] >= 0) {
                lastIdx = i;
            }
        }
        float pad = 3f * d;
        float baseY = h - pad;
        float topY = pad;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.5f * d);
        paint.setColor(LINE);
        paint.setAlpha(230);
        float prevX = 0;
        float prevY = 0;
        boolean pen = false;
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] < 0) {
                pen = false;
                continue;
            }
            float x = slots.length == 1 ? w / 2f
                    : pad + (w - 2 * pad) * i / (slots.length - 1);
            float y = max <= 0 ? baseY
                    : baseY - (baseY - topY) * slots[i] / max;
            if (pen) {
                canvas.drawLine(prevX, prevY, x, y, paint);
            }
            prevX = x;
            prevY = y;
            pen = true;
        }
        paint.setAlpha(255);
        if (lastIdx >= 0) {
            float x = slots.length == 1 ? w / 2f
                    : pad + (w - 2 * pad) * lastIdx / (slots.length - 1);
            float y = max <= 0 ? baseY
                    : baseY - (baseY - topY) * slots[lastIdx] / max;
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(x, y, 2.5f * d, paint);
        }
    }
}
