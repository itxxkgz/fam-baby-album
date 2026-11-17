package cn.xiaojia.album;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.ViewConfiguration;
import android.view.ViewParent;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;

/** A bounded, pinchable photograph. Paging belongs to the surrounding pager. */
final class ZoomImageView extends ImageView {
    interface Swipe {
        void move(int direction);
    }

    private final Matrix transform = new Matrix();
    private final RectF imageBounds = new RectF();
    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestures;
    private final int touchSlop;
    private final Runnable singleTap;
    private final Swipe legacySwipe;
    private float minimum = 1f, scale = 1f, lastX, lastY, startX, startY;
    private int pointerId = -1;
    private boolean multiTouch, moved, doubleTap, fitPending = true, changingDrawable;
    private ValueAnimator zoomAnimation;

    ZoomImageView(Context context, Runnable onSingleTap) {
        this(context, onSingleTap, null);
    }

    /** Kept for callers outside the immersive viewer. */
    ZoomImageView(Context context, Swipe swipe) {
        this(context, null, swipe);
    }

    private ZoomImageView(Context context, Runnable onSingleTap, Swipe swipe) {
        super(context);
        singleTap = onSingleTap;
        legacySwipe = swipe;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        setScaleType(ScaleType.MATRIX);
        setClickable(true);
        setFocusable(true);
        scaleDetector =
                new ScaleGestureDetector(
                        context,
                        new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                            @Override
                            public boolean onScaleBegin(ScaleGestureDetector detector) {
                                cancelZoomAnimation();
                                multiTouch = true;
                                disallowPaging(true);
                                return getDrawable() != null;
                            }

                            @Override
                            public boolean onScale(ScaleGestureDetector detector) {
                                zoomTo(
                                        scale * detector.getScaleFactor(),
                                        detector.getFocusX(),
                                        detector.getFocusY());
                                return true;
                            }
                        });
        scaleDetector.setQuickScaleEnabled(false);
        scaleDetector.setStylusScaleEnabled(false);
        gestures =
                new GestureDetector(
                        context,
                        new GestureDetector.SimpleOnGestureListener() {
                            @Override
                            public boolean onDown(MotionEvent event) {
                                return true;
                            }

                            @Override
                            public boolean onSingleTapConfirmed(MotionEvent event) {
                                if (!multiTouch && !moved && !doubleTap) performClick();
                                return true;
                            }

                            @Override
                            public boolean onDoubleTap(MotionEvent event) {
                                doubleTap = true;
                                disallowPaging(true);
                                animateZoom(
                                        isZoomed() ? minimum : minimum * 2.5f,
                                        event.getX(),
                                        event.getY());
                                return true;
                            }
                        });
        gestures.setIsLongpressEnabled(false);
    }

    boolean isZoomed() {
        return !fitPending && scale > minimum * 1.02f;
    }

    void resetZoom() {
        cancelZoomAnimation();
        fitPending = true;
        fit();
    }

    static boolean shouldSnapAfterPinch(float currentScale, float fitScale) {
        return fitScale > 0 && currentScale > fitScale && currentScale <= fitScale * 1.10f;
    }

    @Override
    public void setImageBitmap(Bitmap bitmap) {
        ImagePosition position = transform == null ? null : rememberPosition();
        if (transform != null) cancelZoomAnimation();
        changingDrawable = true;
        try {
            super.setImageBitmap(bitmap);
        } finally {
            changingDrawable = false;
        }
        if (transform != null) restorePosition(position);
    }

    @Override
    public void setImageDrawable(Drawable drawable) {
        // ImageView can call this from its constructor, before our fields exist.
        if (changingDrawable) {
            super.setImageDrawable(drawable);
            return;
        }
        ImagePosition position = transform == null ? null : rememberPosition();
        if (transform != null) cancelZoomAnimation();
        super.setImageDrawable(drawable);
        fitPending = true;
        if (transform != null) restorePosition(position);
    }

    private static final class ImagePosition {
        float zoom, centerX, centerY, aspect;
    }

    private ImagePosition rememberPosition() {
        Drawable drawable = getDrawable();
        if (!isZoomed()
                || drawable == null
                || drawable.getIntrinsicWidth() <= 0
                || drawable.getIntrinsicHeight() <= 0) return null;
        Matrix inverse = new Matrix();
        if (!transform.invert(inverse)) return null;
        float[] center = {getWidth() * .5f, getHeight() * .5f};
        inverse.mapPoints(center);
        ImagePosition result = new ImagePosition();
        result.zoom = scale / minimum;
        result.centerX = center[0] / drawable.getIntrinsicWidth();
        result.centerY = center[1] / drawable.getIntrinsicHeight();
        result.aspect = (float) drawable.getIntrinsicWidth() / drawable.getIntrinsicHeight();
        return result;
    }

    private void restorePosition(ImagePosition position) {
        fitPending = true;
        Drawable drawable = getDrawable();
        if (position == null
                || drawable == null
                || getWidth() <= 0
                || getHeight() <= 0
                || drawable.getIntrinsicWidth() <= 0
                || drawable.getIntrinsicHeight() <= 0) {
            fit();
            return;
        }
        float aspect = (float) drawable.getIntrinsicWidth() / drawable.getIntrinsicHeight();
        if (Math.abs(aspect / position.aspect - 1) > .04f) {
            fit();
            return;
        }
        minimum =
                Math.min(
                        (float) getWidth() / drawable.getIntrinsicWidth(),
                        (float) getHeight() / drawable.getIntrinsicHeight());
        scale = minimum * Math.max(1, Math.min(5, position.zoom));
        transform.reset();
        transform.postScale(scale, scale);
        transform.postTranslate(
                getWidth() * .5f - position.centerX * drawable.getIntrinsicWidth() * scale,
                getHeight() * .5f - position.centerY * drawable.getIntrinsicHeight() * scale);
        constrain();
        fitPending = false;
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        resetZoom();
    }

    private void fit() {
        Drawable drawable = getDrawable();
        if (drawable == null
                || getWidth() <= 0
                || getHeight() <= 0
                || drawable.getIntrinsicWidth() <= 0
                || drawable.getIntrinsicHeight() <= 0) return;
        minimum =
                Math.min(
                        (float) getWidth() / drawable.getIntrinsicWidth(),
                        (float) getHeight() / drawable.getIntrinsicHeight());
        scale = minimum;
        transform.reset();
        transform.postScale(scale, scale);
        constrain();
        fitPending = false;
    }

    private void zoomTo(float wanted, float focusX, float focusY) {
        if (fitPending) fit();
        if (fitPending || scale <= 0 || Float.isNaN(wanted) || Float.isInfinite(wanted)) return;
        float next = Math.max(minimum, Math.min(minimum * 5f, wanted));
        transform.postScale(next / scale, next / scale, focusX, focusY);
        scale = next;
        constrain();
    }

    private void animateZoom(float target, float x, float y) {
        cancelZoomAnimation();
        if (getDrawable() == null) return;
        if (fitPending) fit();
        if (fitPending) return;
        ValueAnimator animation = ValueAnimator.ofFloat(scale, target);
        zoomAnimation = animation;
        animation.setDuration(190);
        animation.setInterpolator(new DecelerateInterpolator());
        animation.addUpdateListener(
                value -> {
                    if (zoomAnimation == animation) zoomTo((Float) value.getAnimatedValue(), x, y);
                });
        animation.start();
    }

    private void cancelZoomAnimation() {
        ValueAnimator previous = zoomAnimation;
        zoomAnimation = null;
        if (previous != null) previous.cancel();
    }

    private boolean readBounds() {
        Drawable drawable = getDrawable();
        if (drawable == null) return false;
        imageBounds.set(0, 0, drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight());
        transform.mapRect(imageBounds);
        return true;
    }

    private void constrain() {
        if (!readBounds()) return;
        float dx = correction(imageBounds.left, imageBounds.right, getWidth());
        float dy = correction(imageBounds.top, imageBounds.bottom, getHeight());
        transform.postTranslate(dx, dy);
        setImageMatrix(transform);
    }

    private static float correction(float start, float end, float viewport) {
        if (end - start <= viewport) return (viewport - (end - start)) * .5f - start;
        if (start > 0) return -start;
        if (end < viewport) return viewport - end;
        return 0;
    }

    @Override
    public boolean canScrollHorizontally(int direction) {
        if (!isZoomed() || !readBounds() || direction == 0) return false;
        return direction < 0 ? imageBounds.left < -1 : imageBounds.right > getWidth() + 1;
    }

    private void disallowPaging(boolean value) {
        ViewParent parent = getParent();
        if (parent != null) parent.requestDisallowInterceptTouchEvent(value);
    }

    @Override
    public boolean performClick() {
        super.performClick();
        if (singleTap != null) singleTap.run();
        return true;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            cancelZoomAnimation();
            pointerId = event.getPointerId(0);
            startX = lastX = event.getX();
            startY = lastY = event.getY();
            moved = multiTouch = doubleTap = false;
            disallowPaging(isZoomed());
        } else if (action == MotionEvent.ACTION_POINTER_DOWN) {
            multiTouch = true;
            moved = true;
            disallowPaging(true);
        }
        scaleDetector.onTouchEvent(event);
        gestures.onTouchEvent(event);
        int index = pointerId < 0 ? -1 : event.findPointerIndex(pointerId);
        if (action == MotionEvent.ACTION_MOVE && index >= 0) {
            float x = event.getX(index), y = event.getY(index);
            if (Math.abs(x - startX) > touchSlop || Math.abs(y - startY) > touchSlop) moved = true;
            if (!scaleDetector.isInProgress() && isZoomed()) {
                transform.postTranslate(x - lastX, y - lastY);
                constrain();
                disallowPaging(true);
            }
            lastX = x;
            lastY = y;
        } else if (action == MotionEvent.ACTION_POINTER_UP) {
            int raised = event.getActionIndex();
            if (event.getPointerId(raised) == pointerId) {
                int remaining = raised == 0 ? 1 : 0;
                pointerId = event.getPointerId(remaining);
                lastX = event.getX(remaining);
                lastY = event.getY(remaining);
            }
        } else if (action == MotionEvent.ACTION_UP) {
            // Android can stop pinch scaling before closely spaced fingers reach the fit scale.
            if (multiTouch && shouldSnapAfterPinch(scale, minimum)) resetZoom();
            if (legacySwipe != null && !multiTouch && !doubleTap && !isZoomed()) {
                float dx = event.getX() - startX, dy = event.getY() - startY;
                if (Math.abs(dx) > getWidth() * .18f && Math.abs(dx) > Math.abs(dy) * 1.5f)
                    legacySwipe.move(dx < 0 ? 1 : -1);
            }
            pointerId = -1;
            disallowPaging(false);
        } else if (action == MotionEvent.ACTION_CANCEL) {
            pointerId = -1;
            moved = true;
            disallowPaging(false);
        }
        return true;
    }

    @Override
    protected void onDetachedFromWindow() {
        cancelZoomAnimation();
        super.onDetachedFromWindow();
    }
}
