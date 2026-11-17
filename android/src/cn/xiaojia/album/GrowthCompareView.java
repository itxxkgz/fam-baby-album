package cn.xiaojia.album;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

/** Two real photographs, with a draggable reveal and a separate export canvas. */
final class GrowthCompareView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private Bitmap left, right;
    private boolean overlay;
    private float divider = .5f;

    GrowthCompareView(Context context) {
        super(context);
        setFocusable(true);
        updateDescription();
    }

    void setPhotos(Bitmap first, Bitmap second) {
        left = first;
        right = second;
        invalidate();
    }

    boolean ready() {
        return left != null && right != null && !left.isRecycled() && !right.isRecycled();
    }

    void setOverlay(boolean value) {
        overlay = value;
        updateDescription();
        invalidate();
    }

    boolean isOverlay() {
        return overlay;
    }

    float divider() {
        return divider;
    }

    void setDivider(float value) {
        divider = Math.max(.05f, Math.min(.95f, value));
        invalidate();
    }

    private void updateDescription() {
        setContentDescription(overlay ? "叠着看，左右拖动分界线" : "两张不同年龄的照片并排对比");
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        drawPhotos(canvas, new RectF(0, 0, getWidth(), getHeight()), true);
    }

    private void drawPhotos(Canvas canvas, RectF area, boolean handle) {
        paint.setColor(Ui.SOFT);
        canvas.drawRect(area, paint);
        if (!ready()) return;
        if (!overlay) {
            float gap = area.width() * .012f;
            float center = area.centerX();
            drawCrop(canvas, left, new RectF(area.left, area.top, center - gap / 2, area.bottom));
            drawCrop(canvas, right, new RectF(center + gap / 2, area.top, area.right, area.bottom));
        } else {
            drawCrop(canvas, right, area);
            float edge = area.left + area.width() * divider;
            int saved = canvas.save();
            canvas.clipRect(area.left, area.top, edge, area.bottom);
            drawCrop(canvas, left, area);
            canvas.restoreToCount(saved);
            paint.setColor(Color.WHITE);
            paint.setStrokeWidth(Math.max(2, area.width() / 280));
            canvas.drawLine(edge, area.top, edge, area.bottom, paint);
            if (handle) {
                float radius = Ui.dp(getContext(), 18);
                canvas.drawCircle(edge, area.centerY(), radius, paint);
                paint.setColor(Ui.ACCENT_DARK);
                paint.setStrokeWidth(Ui.dp(getContext(), 1.5f));
                float a = Ui.dp(getContext(), 4), b = Ui.dp(getContext(), 7);
                canvas.drawLine(edge - a, area.centerY() - a, edge - b, area.centerY(), paint);
                canvas.drawLine(edge - b, area.centerY(), edge - a, area.centerY() + a, paint);
                canvas.drawLine(edge + a, area.centerY() - a, edge + b, area.centerY(), paint);
                canvas.drawLine(edge + b, area.centerY(), edge + a, area.centerY() + a, paint);
            }
        }
    }

    private void drawCrop(Canvas canvas, Bitmap bitmap, RectF target) {
        float scale =
                Math.max(target.width() / bitmap.getWidth(), target.height() / bitmap.getHeight());
        float width = bitmap.getWidth() * scale, height = bitmap.getHeight() * scale;
        RectF drawing =
                new RectF(
                        target.centerX() - width / 2,
                        target.centerY() - height / 2,
                        target.centerX() + width / 2,
                        target.centerY() + height / 2);
        int saved = canvas.save();
        canvas.clipRect(target);
        paint.setColor(Color.WHITE);
        canvas.drawBitmap(bitmap, null, drawing, paint);
        canvas.restoreToCount(saved);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!overlay || !ready() || getWidth() <= 0) return super.onTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                setDivider(event.getX() / getWidth());
                return true;
            case MotionEvent.ACTION_MOVE:
                setDivider(event.getX() / getWidth());
                return true;
            case MotionEvent.ACTION_UP:
                getParent().requestDisallowInterceptTouchEvent(false);
                performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            default:
                return true;
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        if (overlay) {
            info.setClassName("android.widget.SeekBar");
            info.setRangeInfo(
                    AccessibilityNodeInfo.RangeInfo.obtain(
                            AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT,
                            5,
                            95,
                            divider * 100));
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
        }
    }

    @Override
    public boolean performAccessibilityAction(int action, Bundle args) {
        if (overlay && action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) {
            setDivider(divider + .1f);
            return true;
        }
        if (overlay && action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
            setDivider(divider - .1f);
            return true;
        }
        return super.performAccessibilityAction(action, args);
    }

    Bitmap export(
            String child, String leftAge, String rightAge, String leftDate, String rightDate) {
        if (!ready()) return null;
        Bitmap result = Bitmap.createBitmap(1200, 1500, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(result);
        canvas.drawColor(Ui.BG);
        Paint title = new Paint(Paint.ANTI_ALIAS_FLAG);
        title.setColor(Ui.INK);
        title.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        title.setTextSize(46);
        title.setTextAlign(Paint.Align.CENTER);
        String name = child == null || child.trim().isEmpty() ? "成长对比" : child.trim() + "的成长对比";
        while (title.measureText(name) > 1080 && title.getTextSize() > 24)
            title.setTextSize(title.getTextSize() - 2);
        canvas.drawText(name, 600, 78, title);
        title.setTextSize(36);
        canvas.drawText(leftAge, 310, 150, title);
        canvas.drawText(rightAge, 890, 150, title);
        drawPhotos(canvas, new RectF(40, 184, 1160, 1304), false);
        title.setTypeface(Typeface.DEFAULT);
        title.setTextSize(28);
        title.setColor(Ui.MUTED);
        canvas.drawText(leftDate, 310, 1364, title);
        canvas.drawText(rightDate, 890, 1364, title);
        title.setTextSize(32);
        title.setColor(Ui.INK);
        canvas.drawText("原来，你已经长这么大了", 600, 1440, title);
        return result;
    }
}
