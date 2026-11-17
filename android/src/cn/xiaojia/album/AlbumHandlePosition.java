package cn.xiaojia.album;

/** Keeps the small edge handle under the same grip point across a drag. */
final class AlbumHandlePosition {
    private float fraction = .5f, startFraction = .5f, gripOffset;
    private boolean active;

    static float top(float height, float inset) {
        return Math.min(Math.max(0, inset), Math.max(0, height) / 2);
    }

    static float bottom(float height, float inset) {
        return Math.max(0, height) - top(height, inset);
    }

    float center(float height, float inset) {
        return centerWithin(top(height, inset), bottom(height, inset));
    }

    float startCenter(float height, float inset) {
        return startCenterWithin(top(height, inset), bottom(height, inset));
    }

    void begin(float pointerY, float height, float inset) {
        beginWithin(pointerY, top(height, inset), bottom(height, inset));
    }

    float centerWithin(float top, float bottom) {
        return top + Math.max(0, bottom - top) * fraction;
    }

    float startCenterWithin(float top, float bottom) {
        return top + Math.max(0, bottom - top) * startFraction;
    }

    /** Scrolling cannot pull a handle away from the finger that is holding it. */
    void setFraction(float value) {
        if (!active && Float.isFinite(value)) fraction = Math.max(0, Math.min(1, value));
    }

    void beginWithin(float pointerY, float top, float bottom) {
        startFraction = fraction;
        gripOffset = pointerY - centerWithin(top, bottom);
        active = true;
    }

    void move(float pointerY, float height, float inset) {
        moveWithin(pointerY, top(height, inset), bottom(height, inset));
    }

    void moveWithin(float pointerY, float top, float bottom) {
        if (!active) return;
        float first = top, last = Math.max(top, bottom);
        if (last <= first) return;
        float next = Math.max(first, Math.min(last, pointerY - gripOffset));
        fraction = (next - first) / (last - first);
    }

    void finish(boolean commit) {
        if (active && !commit) fraction = startFraction;
        active = false;
    }
}
