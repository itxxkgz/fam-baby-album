package cn.xiaojia.album;

import android.content.Context;
import android.graphics.*;
import android.os.SystemClock;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Keeps the touch stream with the content until a vertical pull wins at its top edge. */
final class PullRefreshLayout extends FrameLayout {
    interface Action {
        void run(Consumer<Boolean> finished);
    }

    // All page callbacks arrive on the UI thread.
    static Consumer<Boolean> together(int count, Consumer<Boolean> finished) {
        return new Consumer<Boolean>() {
            int remaining = count;
            boolean success = true;

            public void accept(Boolean ok) {
                success &= ok;
                if (--remaining == 0) finished.accept(success);
            }
        };
    }

    private final View scroll;
    private final Action action;
    private final PullRefreshGesture gesture;
    private final Ring ring;
    private final int distance, circle;
    private float origin;
    private boolean ownTouch, refreshing;
    private int revision;
    private BooleanSupplier available = () -> true;

    PullRefreshLayout(Context context, View body, View scroll, Action action) {
        super(context);
        this.scroll = scroll;
        this.action = action;
        distance = Ui.dp(context, 72);
        circle = Ui.dp(context, 40);
        gesture =
                new PullRefreshGesture(
                        ViewConfiguration.get(context).getScaledTouchSlop(), distance);
        addView(body, new LayoutParams(-1, -1));
        ring = new Ring(context);
        ring.setBackground(Ui.round(0xFFFEFCFA, 24, context));
        ring.setElevation(Ui.dp(context, 3));
        ring.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        ring.setVisibility(INVISIBLE);
        addView(ring, new LayoutParams(circle, circle, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    void allowWhen(BooleanSupplier test) {
        available = test;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        int type = e.getActionMasked();
        switch (type) {
            case MotionEvent.ACTION_DOWN:
                ownTouch = false;
                origin = e.getY();
                gesture.start(
                        e.getX(),
                        origin,
                        !refreshing && available.getAsBoolean() && !scroll.canScrollVertically(-1));
                // Empty lists must also receive the rest of the gesture.
                super.dispatchTouchEvent(e);
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_CANCEL:
                gesture.cancel();
                if (ownTouch && !refreshing) retract();
                if (ownTouch) return true;
                break;
            case MotionEvent.ACTION_MOVE:
                if (e.getPointerCount() == 1
                        && !refreshing
                        && available.getAsBoolean()
                        && gesture.move(e.getX(), e.getY(), !scroll.canScrollVertically(-1))) {
                    if (!ownTouch) {
                        MotionEvent cancel = MotionEvent.obtain(e);
                        cancel.setAction(MotionEvent.ACTION_CANCEL);
                        super.dispatchTouchEvent(cancel);
                        cancel.recycle();
                        getParent().requestDisallowInterceptTouchEvent(true);
                        ownTouch = true;
                    }
                    float amount = Math.min(1.35f, Math.max(0, (e.getY() - origin) / distance));
                    ring.animate().cancel();
                    ring.fraction = amount;
                    ring.setAlpha(gesture.armed() ? 1f : .45f + .45f * Math.min(1f, amount));
                    ring.setVisibility(VISIBLE);
                    ring.setTranslationY(
                            -circle
                                    + (circle + Ui.dp(getContext(), 12)) * Math.min(1f, amount)
                                    + Ui.dp(getContext(), 15) * Math.max(0, amount - 1));
                    ring.invalidate();
                    return true;
                }
                if (ownTouch) return true;
                break;
            case MotionEvent.ACTION_UP:
                boolean released = gesture.release(e.getX(), e.getY());
                if (ownTouch) {
                    ownTouch = false;
                    if (released && available.getAsBoolean()) startRefresh();
                    else if (!refreshing) retract();
                    return true;
                }
                break;
        }
        return super.dispatchTouchEvent(e);
    }

    void startRefresh() {
        if (refreshing || !available.getAsBoolean()) return;
        refreshing = true;
        ring.setAlpha(1f);
        int issued = ++revision;
        long started = SystemClock.uptimeMillis();
        ring.animate().cancel();
        ring.setVisibility(VISIBLE);
        ring.animate().translationY(Ui.dp(getContext(), 12)).setDuration(130).start();
        ring.invalidate();
        announceForAccessibility("正在刷新");
        action.run(
                ok -> {
                    if (issued != revision) return;
                    postDelayed(
                            () -> {
                                if (issued != revision) return;
                                refreshing = false;
                                if (!ok)
                                    Toast.makeText(
                                                    getContext(),
                                                    "刷新未完成，可以再下拉试试",
                                                    Toast.LENGTH_SHORT)
                                            .show();
                                else announceForAccessibility("已刷新");
                                retract();
                            },
                            Math.max(0, 300 - (SystemClock.uptimeMillis() - started)));
                });
    }

    private void retract() {
        ring.animate().cancel();
        ring.animate()
                .translationY(-circle - Ui.dp(getContext(), 4))
                .setDuration(160)
                .withEndAction(() -> ring.setVisibility(INVISIBLE))
                .start();
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        if (!refreshing && available.getAsBoolean())
            info.addAction(
                    new AccessibilityNodeInfo.AccessibilityAction(R.id.refresh_content, "刷新"));
    }

    @Override
    public boolean performAccessibilityAction(int id, android.os.Bundle args) {
        if (id == R.id.refresh_content && !refreshing && available.getAsBoolean()) {
            startRefresh();
            return true;
        }
        return super.performAccessibilityAction(id, args);
    }

    @Override
    protected void onDetachedFromWindow() {
        revision++;
        gesture.cancel();
        ownTouch = refreshing = false;
        ring.animate().cancel();
        ring.setTranslationY(-circle);
        ring.setVisibility(INVISIBLE);
        super.onDetachedFromWindow();
    }

    private final class Ring extends View {
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF arc = new RectF();
        private final Path arrow = new Path();
        float fraction;

        Ring(Context context) {
            super(context);
            stroke.setColor(Ui.ACCENT);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float center = getWidth() / 2f, r = center * .43f;
            float phase =
                    refreshing
                            ? (SystemClock.uptimeMillis() % 950L) / 950f
                            : Math.min(fraction, 1f);
            float sweep = refreshing ? 220f : Math.min(285f, 55f + 230f * phase);
            canvas.save();
            canvas.rotate(refreshing ? phase * 360f : phase * 140f, center, center);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            stroke.setStrokeWidth(Ui.dp(getContext(), 2));
            arc.set(center - r, center - r, center + r, center + r);
            canvas.drawArc(arc, -90, sweep, false, stroke);
            canvas.rotate(sweep, center, center);
            stroke.setStyle(Paint.Style.FILL);
            arrow.rewind();
            arrow.moveTo(center + r * .42f, center - r);
            arrow.lineTo(center - r * .12f, center - r * 1.38f);
            arrow.lineTo(center - r * .12f, center - r * .62f);
            arrow.close();
            canvas.drawPath(arrow, stroke);
            canvas.restore();
            if (refreshing) postInvalidateOnAnimation();
        }
    }
}
