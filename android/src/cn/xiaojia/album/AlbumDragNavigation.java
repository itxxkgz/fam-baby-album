package cn.xiaojia.album;

/** UI-thread state for live dragging; only the newest navigation intent may be displayed. */
final class AlbumDragNavigation {
    private static final long REQUEST_INTERVAL_MS = 150;

    static final class Ticket {
        final String date;
        private final long epoch, revision;

        private Ticket(String date, long epoch, long revision) {
            this.date = date;
            this.epoch = epoch;
            this.revision = revision;
        }
    }

    private long epoch, revision, lastDispatch;
    private boolean active, dragging, hasDispatch, visible, failed, immediate;
    private Ticket current, inFlight;

    void begin() {
        clear(true);
    }

    /** Repeated MOVE events keep the existing intent and its scheduled request time. */
    void preview(String date) {
        if (active && dragging) select(date);
    }

    /** Releasing flushes the final target; a failure during dragging gets one final attempt. */
    void finish(String date) {
        if (!active || !dragging) return;
        select(date);
        dragging = false;
        immediate = true;
        failed = false;
    }

    void cancel() {
        clear(false);
    }

    boolean isDragging() {
        return dragging;
    }

    /** Remains active after release so its final request/layout can still be accepted. */
    boolean isActive() {
        return active;
    }

    Ticket current() {
        return current;
    }

    boolean accepts(Ticket ticket) {
        return active
                && ticket != null
                && current != null
                && ticket.epoch == epoch
                && ticket.revision == revision
                && current.date.equals(ticket.date);
    }

    boolean displayed(Ticket ticket) {
        return accepts(ticket) && visible;
    }

    void markDisplayed(Ticket ticket) {
        if (accepts(ticket)) visible = true;
    }

    /** -1 means no work, zero means dispatch now, otherwise wait this many milliseconds. */
    long delayUntilRequest(long now) {
        if (!active || current == null || visible || failed || accepts(inFlight)) return -1;
        if (immediate || !hasDispatch || now < lastDispatch) return 0;
        long elapsed = now - lastDispatch;
        // A wrapped subtraction represents an interval much larger than the throttle.
        if (elapsed < 0 || elapsed >= REQUEST_INTERVAL_MS) return 0;
        return REQUEST_INTERVAL_MS - elapsed;
    }

    Ticket takeRequest(long now) {
        if (delayUntilRequest(now) != 0) return null;
        // Identity distinguishes retries of one intent, so an old completion cannot
        // accidentally clear a later request for that same date.
        inFlight = new Ticket(current.date, epoch, revision);
        hasDispatch = true;
        lastDispatch = now;
        immediate = false;
        return inFlight;
    }

    void complete(Ticket ticket) {
        if (ticket != null && ticket == inFlight) inFlight = null;
    }

    void failed(Ticket ticket) {
        if (ticket == null || ticket != inFlight) return;
        inFlight = null;
        if (accepts(ticket)) failed = true;
    }

    private void select(String date) {
        if (date == null || date.trim().isEmpty()) return;
        String target = date.trim();
        if (current != null && current.date.equals(target)) return;
        revision++;
        current = new Ticket(target, epoch, revision);
        visible = false;
        failed = false;
        immediate = false;
    }

    private void clear(boolean start) {
        epoch++;
        revision = 0;
        active = dragging = start;
        hasDispatch = visible = failed = immediate = false;
        current = inFlight = null;
    }
}
