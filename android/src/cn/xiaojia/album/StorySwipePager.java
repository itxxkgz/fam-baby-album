package cn.xiaojia.album;

import android.content.Context;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import java.util.function.IntConsumer;

/** A horizontal book pager: vertical gestures remain available for reading long stories. */
final class StorySwipePager extends FrameLayout {
    private View previous, current, next;
    private final int slop;
    private final float density;
    private float startX, startY, distance;
    private int axis, revision;
    private boolean settling;
    private VelocityTracker velocity;
    private IntConsumer selected;

    StorySwipePager(Context context) {
        super(context);
        slop = ViewConfiguration.get(context).getScaledTouchSlop();
        density = getResources().getDisplayMetrics().density;
        setClipChildren(true);
    }

    void onSelected(IntConsumer callback) {
        selected = callback;
    }

    void pages(View before, View value, View after) {
        stop();
        removeAllViews();
        previous = before;
        current = value;
        next = after;
        attach(previous);
        attach(next);
        attach(current);
        position(0);
    }

    private void attach(View view) {
        if (view == null) return;
        if (view.getParent() instanceof ViewGroup) ((ViewGroup) view.getParent()).removeView(view);
        addView(view, new FrameLayout.LayoutParams(-1, -1));
    }

    private void begin(MotionEvent event) {
        if (velocity != null) velocity.recycle();
        velocity = VelocityTracker.obtain();
        velocity.addMovement(event);
        startX = event.getX();
        startY = event.getY();
        distance = 0;
        axis = 0;
    }

    private boolean horizontal(MotionEvent event) {
        float dx = event.getX() - startX, dy = event.getY() - startY;
        if (axis == 0) {
            if (Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy) * 1.3f) axis = 1;
            else if (Math.abs(dy) > slop) axis = 2;
        }
        if (axis == 1) {
            distance = dx;
            getParent().requestDisallowInterceptTouchEvent(true);
        }
        return axis == 1;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        if (settling) return true;
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) begin(event);
        if (event.getPointerCount() > 1) {
            axis = 2;
            return false;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE) return horizontal(event);
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (settling) return true;
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) begin(event);
        if (velocity != null) velocity.addMovement(event);
        if (event.getPointerCount() > 1) {
            axis = 2;
            finish(0);
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE && horizontal(event)) {
            boolean missing = distance < 0 ? next == null : previous == null;
            position(
                    Math.max(
                            -getWidth(),
                            Math.min(getWidth(), missing ? distance * .22f : distance)));
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            float speed = 0;
            if (velocity != null) {
                velocity.computeCurrentVelocity(1000);
                speed = velocity.getXVelocity();
            }
            int direction =
                    axis == 1
                            ? MediaPagerView.pageDirection(
                                    distance, speed, getWidth(), 24 * density, 500 * density)
                            : 0;
            if ((direction > 0 && next == null) || (direction < 0 && previous == null))
                direction = 0;
            finish(direction);
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            finish(0);
            return true;
        }
        return axis == 1;
    }

    private void position(float offset) {
        if (current != null) current.setTranslationX(offset);
        if (previous != null) previous.setTranslationX(offset - getWidth());
        if (next != null) next.setTranslationX(offset + getWidth());
    }

    private void finish(int direction) {
        if (velocity != null) {
            velocity.recycle();
            velocity = null;
        }
        if (current == null) return;
        settling = true;
        int token = ++revision;
        float offset = -direction * getWidth();
        if (previous != null)
            previous.animate().translationX(offset - getWidth()).setDuration(180).start();
        if (next != null) next.animate().translationX(offset + getWidth()).setDuration(180).start();
        current.animate()
                .translationX(offset)
                .setDuration(180)
                .withEndAction(
                        () -> {
                            if (token != revision) return;
                            settling = false;
                            axis = 0;
                            if (direction != 0 && selected != null) selected.accept(direction);
                            else position(0);
                        })
                .start();
    }

    void stop() {
        revision++;
        settling = false;
        axis = 0;
        if (velocity != null) {
            velocity.recycle();
            velocity = null;
        }
        if (previous != null) previous.animate().cancel();
        if (next != null) next.animate().cancel();
        if (current != null) current.animate().cancel();
        position(0);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        position(0);
    }
}
