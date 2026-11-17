package cn.xiaojia.album;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;

/** Three visible pages, moved with the finger, without owning media or network state. */
final class MediaPagerView extends FrameLayout {
    interface Listener {
        void onPageSelected(int direction);

        void onBoundary(int direction);

        void onDismiss();
    }

    private View previous, current, next, pendingPrevious, pendingNext;
    private Listener listener;
    private final int touchSlop, minimumFling, maximumFling;
    private final float density;
    private boolean pagingEnabled = true,
            settling,
            touching,
            multiplePointers,
            ignoreUntilUp,
            pendingNeighbors;
    private int pointerId = -1, axis, revision;
    private float startX, startY, offsetX, offsetY, rawX, rawY;
    private VelocityTracker velocity;
    private long lastTrackedTime = -1;
    private ValueAnimator animation;

    MediaPagerView(Context context) {
        super(context);
        ViewConfiguration configuration = ViewConfiguration.get(context);
        touchSlop = configuration.getScaledTouchSlop();
        density = getResources().getDisplayMetrics().density;
        minimumFling =
                Math.max(configuration.getScaledMinimumFlingVelocity(), (int) (500 * density));
        maximumFling = configuration.getScaledMaximumFlingVelocity();
        setClipChildren(true);
        setClipToPadding(true);
    }

    void setListener(Listener value) {
        listener = value;
    }

    void setPages(View before, View selected, View after) {
        boolean interrupted = touching;
        pendingNeighbors = false;
        pendingPrevious = pendingNext = null;
        cancelAnimations();
        ignoreUntilUp = interrupted;
        removeAllViews();
        previous = before;
        current = selected;
        next = after;
        attach(previous);
        attach(next);
        attach(current);
        positionPages(0, 0);
    }

    /** Refresh previews without detaching the displayed photo or video surface. */
    void setNeighbors(View before, View after) {
        pendingPrevious = before;
        pendingNext = after;
        pendingNeighbors = true;
        applyPendingNeighbors();
    }

    private void applyPendingNeighbors() {
        if (!pendingNeighbors
                || isInteracting()
                || Math.abs(offsetX) > .5f
                || Math.abs(offsetY) > .5f) return;
        if (previous != null && previous.getParent() == this) removeView(previous);
        if (next != null && next.getParent() == this) removeView(next);
        previous = pendingPrevious;
        next = pendingNext;
        pendingPrevious = pendingNext = null;
        pendingNeighbors = false;
        attach(previous);
        attach(next);
        if (current != null) current.bringToFront();
        positionPages(0, 0);
    }

    private void attach(View view) {
        if (view == null) return;
        ViewParent parent = view.getParent();
        if (parent instanceof ViewGroup) ((ViewGroup) parent).removeView(view);
        view.setTranslationX(0);
        view.setTranslationY(0);
        view.setAlpha(1);
        view.setScaleX(1);
        view.setScaleY(1);
        addView(view, new FrameLayout.LayoutParams(-1, -1));
    }

    void setPagingEnabled(boolean enabled) {
        if (pagingEnabled == enabled) return;
        pagingEnabled = enabled;
        if (!enabled) cancelAnimations();
    }

    boolean isSettling() {
        return settling;
    }

    boolean isInteracting() {
        return settling || (touching && axis != 0);
    }

    void cancelAnimations() {
        revision++;
        ValueAnimator old = animation;
        animation = null;
        if (old != null) old.cancel();
        settling = false;
        finishTouch();
        positionPages(0, 0);
        applyPendingNeighbors();
    }

    void dispose() {
        pendingNeighbors = false;
        pendingPrevious = pendingNext = null;
        cancelAnimations();
        listener = null;
        previous = current = next = null;
        removeAllViews();
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        cancelAnimations();
    }

    private boolean zoomed(View view) {
        if (view instanceof ZoomImageView) return ((ZoomImageView) view).isZoomed();
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                if (zoomed(group.getChildAt(index))) return true;
            }
        }
        return false;
    }

    private void beginTouch(MotionEvent event) {
        finishTouch();
        touching = true;
        multiplePointers = false;
        ignoreUntilUp = false;
        pointerId = event.getPointerId(0);
        startX = event.getX();
        startY = event.getY();
        rawX = rawY = 0;
        axis = 0;
        velocity = VelocityTracker.obtain();
        lastTrackedTime = -1;
        track(event);
    }

    private void track(MotionEvent event) {
        if (velocity != null && event.getEventTime() != lastTrackedTime) {
            velocity.addMovement(event);
            lastTrackedTime = event.getEventTime();
        }
    }

    private boolean chooseAxis(MotionEvent event) {
        int index = event.findPointerIndex(pointerId);
        if (index < 0 || multiplePointers || zoomed(current)) return false;
        rawX = event.getX(index) - startX;
        rawY = event.getY(index) - startY;
        if (axis == 0) {
            if (Math.abs(rawX) > touchSlop && Math.abs(rawX) > Math.abs(rawY) * 1.25f) axis = 1;
            else if (rawY > touchSlop && rawY > Math.abs(rawX) * 1.5f) axis = 2;
            else if (rawY < -touchSlop && -rawY > Math.abs(rawX) * 1.5f) axis = 3;
            if (axis == 1 || axis == 2) {
                ViewParent parent = getParent();
                if (parent != null) parent.requestDisallowInterceptTouchEvent(true);
            }
        }
        return axis == 1 || axis == 2;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            if (settling) {
                ignoreUntilUp = true;
                return true;
            }
            beginTouch(event);
        }
        if (ignoreUntilUp) return true;
        if (!pagingEnabled || current == null) return false;
        track(event);
        if (action == MotionEvent.ACTION_POINTER_DOWN || event.getPointerCount() > 1) {
            multiplePointers = true;
            return false;
        }
        if (action == MotionEvent.ACTION_MOVE) return chooseAxis(event);
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            finishTouch();
            applyPendingNeighbors();
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (ignoreUntilUp || settling) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                ignoreUntilUp = false;
                finishTouch();
                applyPendingNeighbors();
            }
            return true;
        }
        if (!pagingEnabled || current == null) return false;
        if (action == MotionEvent.ACTION_DOWN) {
            if (!touching) beginTouch(event);
            return true;
        }
        track(event);
        if (action == MotionEvent.ACTION_POINTER_DOWN || event.getPointerCount() > 1) {
            multiplePointers = true;
            ignoreUntilUp = true;
            settle(0, false, 0);
            return true;
        }
        if (action == MotionEvent.ACTION_MOVE && chooseAxis(event)) {
            if (axis == 1) {
                boolean missing = rawX < 0 ? next == null : previous == null;
                float distance = missing ? rawX * .25f : rawX;
                positionPages(Math.max(-getWidth(), Math.min(getWidth(), distance)), 0);
            } else positionPages(0, Math.max(0, rawY));
            return true;
        }
        if (action == MotionEvent.ACTION_UP) {
            float vx = 0, vy = 0;
            if (velocity != null) {
                velocity.computeCurrentVelocity(1000, maximumFling);
                vx = velocity.getXVelocity(pointerId);
                vy = velocity.getYVelocity(pointerId);
            }
            int chosenAxis = axis;
            finishTouch();
            if (chosenAxis == 1) {
                int direction = pageDirection(rawX, vx, getWidth(), 24 * density, minimumFling);
                boolean missing = rawX < 0 ? next == null : previous == null;
                int boundary =
                        missing && Math.abs(rawX) >= Math.max(36 * density, getWidth() * .12f)
                                ? (rawX < 0 ? 1 : -1)
                                : 0;
                settle(missing ? 0 : direction, false, boundary);
            } else if (chosenAxis == 2) {
                boolean dismiss =
                        vy >= -minimumFling
                                && (rawY > Math.max(96 * density, getHeight() * .16f)
                                        || (rawY > 48 * density && vy > minimumFling));
                settle(0, dismiss, 0);
            } else {
                positionPages(0, 0);
                applyPendingNeighbors();
            }
            return true;
        }
        if (action == MotionEvent.ACTION_CANCEL) {
            finishTouch();
            settle(0, false, 0);
        }
        return true;
    }

    /** The finger's release direction wins over an earlier long drag. */
    static int pageDirection(
            float distance,
            float velocity,
            float width,
            float minimumDistance,
            float minimumVelocity) {
        if (width <= 0
                || distance == 0
                || Float.isNaN(distance)
                || Float.isNaN(velocity)
                || Float.isInfinite(distance)
                || Float.isInfinite(velocity)) return 0;
        boolean reverseFling =
                Math.abs(velocity) >= minimumVelocity
                        && Math.signum(distance) != Math.signum(velocity);
        if (reverseFling) return 0;
        boolean distanceReached = Math.abs(distance) >= width * .22f;
        boolean flingReached =
                Math.abs(distance) >= minimumDistance && Math.abs(velocity) >= minimumVelocity;
        if (!distanceReached && !flingReached) return 0;
        return distance < 0 ? 1 : -1;
    }

    private void positionPages(float x, float y) {
        offsetX = x;
        offsetY = y;
        if (previous != null) previous.setTranslationX(x - getWidth());
        if (next != null) next.setTranslationX(x + getWidth());
        if (current != null) {
            current.setTranslationX(x);
            current.setTranslationY(y);
            float fraction = Math.min(1, y / Math.max(1, getHeight()));
            float shrinking = 1 - fraction * .12f;
            current.setScaleX(shrinking);
            current.setScaleY(shrinking);
            current.setAlpha(1 - fraction * .35f);
        }
    }

    private void settle(int direction, boolean dismiss, int boundary) {
        final int request = ++revision;
        ValueAnimator old = animation;
        animation = null;
        if (old != null) old.cancel();
        final float fromX = offsetX, fromY = offsetY;
        final float toX = -direction * getWidth(), toY = dismiss ? getHeight() : 0;
        if (Math.abs(fromX - toX) < .5f && Math.abs(fromY - toY) < .5f) {
            finishSettlement(request, direction, dismiss, boundary);
            return;
        }
        ValueAnimator motion = ValueAnimator.ofFloat(0, 1);
        animation = motion;
        settling = true;
        motion.setDuration(direction != 0 || dismiss ? 190 : 170);
        motion.setInterpolator(new DecelerateInterpolator());
        motion.addUpdateListener(
                value -> {
                    if (animation != motion || request != revision) return;
                    float fraction = (Float) value.getAnimatedValue();
                    positionPages(
                            fromX + (toX - fromX) * fraction, fromY + (toY - fromY) * fraction);
                });
        motion.addListener(
                new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator value) {
                        if (animation != motion || request != revision) return;
                        animation = null;
                        finishSettlement(request, direction, dismiss, boundary);
                    }
                });
        motion.start();
    }

    private void finishSettlement(int request, int direction, boolean dismiss, int boundary) {
        settling = false;
        Listener target = listener;
        if (target != null) {
            if (dismiss) target.onDismiss();
            else if (direction != 0) target.onPageSelected(direction);
            else if (boundary != 0) target.onBoundary(boundary);
        }
        if (request == revision) {
            positionPages(0, 0);
            applyPendingNeighbors();
        }
    }

    private void finishTouch() {
        touching = false;
        pointerId = -1;
        axis = 0;
        if (velocity != null) velocity.recycle();
        velocity = null;
        lastTrackedTime = -1;
        ViewParent parent = getParent();
        if (parent != null) parent.requestDisallowInterceptTouchEvent(false);
    }

    @Override
    protected void onDetachedFromWindow() {
        cancelAnimations();
        super.onDetachedFromWindow();
    }
}
