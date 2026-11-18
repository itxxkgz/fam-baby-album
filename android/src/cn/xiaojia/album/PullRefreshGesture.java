package cn.xiaojia.album;

/** One-finger downward pull starting at the top of a scrolling view. */
final class PullRefreshGesture {
    private final float slop, threshold;
    private float startX, startY;
    private boolean tracking, dragging, armed;

    PullRefreshGesture(float slop, float threshold) {
        this.slop = slop;
        this.threshold = threshold;
    }

    void start(float x, float y, boolean atTop) {
        cancel();
        startX = x;
        startY = y;
        tracking = atTop;
    }

    boolean move(float x, float y, boolean atTop) {
        if (!tracking) return false;
        float dx = Math.abs(x - startX), dy = y - startY;
        if (!dragging) {
            if (!atTop || dy < -slop || (dx > slop && dx >= dy)) {
                cancel();
                return false;
            }
            if (dy <= slop || dy < dx * 1.5f) return false;
            dragging = true;
        }
        armed = dy >= threshold;
        return true;
    }

    boolean armed() {
        return armed;
    }

    boolean release(float x, float y) {
        boolean refresh = dragging && move(x, y, true) && armed;
        cancel();
        return refresh;
    }

    void cancel() {
        tracking = dragging = armed = false;
    }
}
