package app.quantusnode.node;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

/**
 * A label/value information row (versionCode 26): the label sits at
 * the start, the value at the end, at least 24 dp between them, the
 * row at least 56 dp tall with 12 dp of vertical padding.
 *
 * When the two sides cannot share one line at their natural widths
 * — a long label in a verbose language next to a long value — the
 * row falls back to stacking, label above and value below at the
 * start edge (redesign proposal §6; audit finding L-7). Text is
 * never shrunk and never ellipsized away: in the horizontal form
 * the label gets whatever width the value leaves, and wraps inside
 * it; in the stacked form both sides get the full width.
 *
 * Child 0 is the label, child 1 the value; both are plain
 * TextViews the activity fills in. The row itself is not a
 * separate accessibility target — TalkBack reads the two texts as
 * it did when the row was a LinearLayout.
 */
public class InfoRowView extends ViewGroup {

    private final int gapPx;
    private final int stackGapPx;
    private final int padVPx;
    private final int minHeightPx;
    /** Set by onMeasure: the arrangement onLayout must use. */
    private boolean stacked;

    public InfoRowView(Context context) {
        super(context);
        float d = getResources().getDisplayMetrics().density;
        gapPx = (int) (24 * d);
        stackGapPx = (int) (4 * d);
        padVPx = (int) (12 * d);
        minHeightPx = (int) (56 * d);
    }

    private TextView label() {
        return (TextView) getChildAt(0);
    }

    private TextView value() {
        return (TextView) getChildAt(1);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int avail = Math.max(0, width - getPaddingLeft() - getPaddingRight());
        TextView label = label();
        TextView value = value();

        // Natural widths first: each side measured against the
        // whole row, so the fit decision uses unwrapped desires.
        value.measure(
                MeasureSpec.makeMeasureSpec(avail, MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        label.measure(
                MeasureSpec.makeMeasureSpec(avail, MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        int valueW = value.getMeasuredWidth();
        int labelW = label.getMeasuredWidth();

        int contentH;
        if (labelW + gapPx + valueW <= avail) {
            stacked = false;
            // The value keeps its natural width; the label takes
            // the rest of the line and wraps inside it.
            int labelBox = Math.max(0, avail - gapPx - valueW);
            if (labelBox != labelW) {
                label.measure(
                        MeasureSpec.makeMeasureSpec(labelBox, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            }
            contentH = Math.max(label.getMeasuredHeight(),
                    value.getMeasuredHeight());
        } else {
            stacked = true;
            // Both sides get the full width, one above the other.
            if (labelW != avail) {
                label.measure(
                        MeasureSpec.makeMeasureSpec(avail, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            }
            contentH = label.getMeasuredHeight() + stackGapPx
                    + value.getMeasuredHeight();
        }
        int height = Math.max(minHeightPx,
                contentH + padVPx * 2 + getPaddingTop() + getPaddingBottom());
        // With an EXACTLY / AT_MOST parent this is the offered
        // width; an UNSPECIFIED parent gets the natural desire.
        int desiredW = (stacked
                ? Math.max(labelW, valueW)
                : labelW + gapPx + valueW)
                + getPaddingLeft() + getPaddingRight();
        setMeasuredDimension(
                resolveSize(desiredW, widthMeasureSpec),
                resolveSize(height, heightMeasureSpec));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        TextView label = label();
        TextView value = value();
        int left = getPaddingLeft();
        int right = getWidth() - getPaddingRight();
        int avail = right - left;
        int top = getPaddingTop() + padVPx;
        int bottom = getHeight() - getPaddingBottom() - padVPx;
        boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;

        if (!stacked) {
            int valueW = value.getMeasuredWidth();
            int labelBox = Math.max(0, avail - gapPx - valueW);
            int labelY = top + Math.max(0,
                    ((bottom - top) - label.getMeasuredHeight()) / 2);
            int valueY = top + Math.max(0,
                    ((bottom - top) - value.getMeasuredHeight()) / 2);
            if (rtl) {
                // Start is the right edge: label there, value at left.
                label.layout(right - labelBox, labelY, right,
                        labelY + label.getMeasuredHeight());
                value.layout(left, valueY, left + valueW,
                        valueY + value.getMeasuredHeight());
            } else {
                label.layout(left, labelY, left + labelBox,
                        labelY + label.getMeasuredHeight());
                value.layout(right - valueW, valueY, right,
                        valueY + value.getMeasuredHeight());
            }
        } else {
            int labelH = label.getMeasuredHeight();
            label.layout(left, top, right, top + labelH);
            int valueW = value.getMeasuredWidth();
            int valueY = top + labelH + stackGapPx;
            if (rtl) {
                value.layout(right - valueW, valueY, right,
                        valueY + value.getMeasuredHeight());
            } else {
                value.layout(left, valueY, left + valueW,
                        valueY + value.getMeasuredHeight());
            }
        }
    }
}
