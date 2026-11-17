package cn.xiaojia.album;

public final class AlbumDragNavigationTest {
    private static int checks;
    private static final String A = "2025-06-10", B = "2024-04-08", C = "2023-01-02";

    private static void yes(boolean value) {
        checks++;
        if (!value) throw new AssertionError("Expected condition to be true at check " + checks);
    }

    private static void no(boolean value) {
        yes(!value);
    }

    private static void equal(long expected, long actual) {
        checks++;
        if (expected != actual)
            throw new AssertionError(
                    "Expected " + expected + ", got " + actual + " at check " + checks);
    }

    private static void date(String expected, AlbumDragNavigation.Ticket ticket) {
        yes(ticket != null && expected.equals(ticket.date));
    }

    private static AlbumDragNavigation begin() {
        AlbumDragNavigation value = new AlbumDragNavigation();
        value.begin();
        return value;
    }

    public static void main(String[] args) {
        firstMoveAndDeduplication();
        newestTargetAndDirectionChange();
        finalTargetAndFailures();
        cancellationAndScopeChange();
        localNavigationAndStaleLayouts();
        timingEdges();
        System.out.println("实时拖动定位检查通过，共" + checks + "项。");
    }

    private static void firstMoveAndDeduplication() {
        AlbumDragNavigation state = new AlbumDragNavigation();
        no(state.isActive());
        no(state.isDragging());
        yes(state.current() == null);
        equal(-1, state.delayUntilRequest(0));
        state.preview(A);
        state.finish(A);
        yes(state.current() == null);
        state.begin();
        yes(state.isActive());
        yes(state.isDragging());
        equal(-1, state.delayUntilRequest(0));
        state.preview(null);
        state.preview("  ");
        yes(state.current() == null);
        state.preview(A);
        AlbumDragNavigation.Ticket intent = state.current();
        date(A, intent);
        equal(0, state.delayUntilRequest(1000));
        AlbumDragNavigation.Ticket request = state.takeRequest(1000);
        // This request exists during MOVE, before any release occurs.
        yes(state.isDragging());
        date(A, request);
        yes(state.accepts(request));
        no(state.displayed(intent));
        equal(-1, state.delayUntilRequest(1001));
        yes(state.takeRequest(2000) == null);
        state.preview(A);
        yes(state.current() == intent);
        equal(-1, state.delayUntilRequest(2000));
        state.finish(A);
        no(state.isDragging());
        yes(state.isActive());
        yes(state.accepts(request));
        equal(-1, state.delayUntilRequest(2000));
        state.markDisplayed(request);
        state.complete(request);
        yes(state.displayed(intent));
        equal(-1, state.delayUntilRequest(3000));
        state.finish(A);
        state.preview(B);
        date(A, state.current());
        equal(-1, state.delayUntilRequest(4000));
        no(state.accepts(null));
        no(state.displayed(null));
    }

    private static void newestTargetAndDirectionChange() {
        AlbumDragNavigation state = begin();
        state.preview(A);
        AlbumDragNavigation.Ticket first = state.takeRequest(1000);
        state.preview(B);
        AlbumDragNavigation.Ticket middle = state.current();
        no(state.accepts(first));
        equal(149, state.delayUntilRequest(1001));
        state.preview(C);
        no(state.accepts(middle));
        equal(100, state.delayUntilRequest(1050));
        // Repeated MOVE does not postpone the original throttle deadline.
        state.preview(C);
        equal(1, state.delayUntilRequest(1149));
        yes(state.takeRequest(1149) == null);
        AlbumDragNavigation.Ticket latest = state.takeRequest(1150);
        date(C, latest);
        no(state.accepts(first));
        yes(state.accepts(latest));
        state.complete(first);
        state.failed(first);
        equal(-1, state.delayUntilRequest(1300));
        // Turning back to A creates a fresh intent; the original A response stays stale.
        state.preview(A);
        no(state.accepts(first));
        no(state.accepts(latest));
        equal(149, state.delayUntilRequest(1151));
        AlbumDragNavigation.Ticket reverse = state.takeRequest(1300);
        date(A, reverse);
        yes(state.accepts(reverse));
        state.markDisplayed(first);
        no(state.displayed(reverse));
        state.markDisplayed(reverse);
        state.complete(reverse);
        yes(state.displayed(reverse));
        equal(-1, state.delayUntilRequest(1400));
    }

    private static void finalTargetAndFailures() {
        AlbumDragNavigation state = begin();
        state.preview(A);
        AlbumDragNavigation.Ticket first = state.takeRequest(1000);
        state.preview(B);
        equal(140, state.delayUntilRequest(1010));
        state.finish(C);
        date(C, state.current());
        equal(0, state.delayUntilRequest(1010));
        AlbumDragNavigation.Ticket last = state.takeRequest(1010);
        date(C, last);
        no(state.accepts(first));
        state.complete(first);
        equal(-1, state.delayUntilRequest(1200));
        state.failed(last);
        equal(-1, state.delayUntilRequest(2000));
        state.finish(C);
        equal(-1, state.delayUntilRequest(3000));

        // A failed preview does not spin; release retries that final target exactly once.
        state.begin();
        state.preview(A);
        first = state.takeRequest(5000);
        state.failed(first);
        state.preview(A);
        equal(-1, state.delayUntilRequest(6000));
        state.finish(A);
        equal(0, state.delayUntilRequest(5001));
        last = state.takeRequest(5001);
        yes(last != first);
        yes(state.accepts(last));
        state.complete(first);
        state.failed(first);
        equal(-1, state.delayUntilRequest(6000));
        state.failed(last);
        equal(-1, state.delayUntilRequest(7000));
        state.finish(A);
        equal(-1, state.delayUntilRequest(8000));

        // A different MOVE target is allowed after a failure, using normal throttling.
        state.begin();
        state.preview(A);
        first = state.takeRequest(9000);
        state.failed(first);
        state.preview(B);
        equal(140, state.delayUntilRequest(9010));
        date(B, state.takeRequest(9150));
    }

    private static void cancellationAndScopeChange() {
        AlbumDragNavigation state = begin();
        state.preview(A);
        AlbumDragNavigation.Ticket old = state.takeRequest(1000);
        state.cancel();
        no(state.isDragging());
        no(state.isActive());
        no(state.accepts(old));
        yes(state.current() == null);
        equal(-1, state.delayUntilRequest(1001));
        state.preview(B);
        state.finish(B);
        state.markDisplayed(old);
        state.complete(old);
        state.failed(old);
        yes(state.current() == null);
        equal(-1, state.delayUntilRequest(2000));

        // A new baby's/range's gesture cannot accept any old response, even on the same date.
        state.begin();
        state.preview(A);
        equal(0, state.delayUntilRequest(1001));
        AlbumDragNavigation.Ticket fresh = state.takeRequest(1001);
        no(state.accepts(old));
        yes(state.accepts(fresh));
        state.begin();
        no(state.accepts(fresh));
        state.preview(A);
        equal(0, state.delayUntilRequest(1002));
        AlbumDragNavigation.Ticket newest = state.takeRequest(1002);
        state.complete(fresh);
        state.failed(fresh);
        equal(-1, state.delayUntilRequest(2000));
        yes(state.accepts(newest));
    }

    private static void localNavigationAndStaleLayouts() {
        AlbumDragNavigation state = begin();
        state.preview(A);
        AlbumDragNavigation.Ticket local = state.current();
        // Loaded dates are displayed during MOVE without taking a network request.
        state.markDisplayed(local);
        yes(state.displayed(local));
        equal(-1, state.delayUntilRequest(0));
        state.finish(A);
        equal(-1, state.delayUntilRequest(0));

        state.begin();
        state.preview(A);
        AlbumDragNavigation.Ticket remote = state.takeRequest(1000);
        state.preview(B);
        local = state.current();
        state.markDisplayed(local);
        state.complete(remote);
        state.failed(remote);
        no(state.accepts(remote));
        yes(state.displayed(local));
        equal(-1, state.delayUntilRequest(2000));
        state.preview(C);
        no(state.accepts(local));
        no(state.displayed(local));
        state.markDisplayed(local);
        no(state.displayed(state.current()));
        // The stale layout of B cannot mark C as positioned or suppress its fetch.
        equal(0, state.delayUntilRequest(2000));
        date(C, state.takeRequest(2000));
    }

    private static void timingEdges() {
        AlbumDragNavigation state = begin();
        state.preview(" " + A + " ");
        date(A, state.current());
        AlbumDragNavigation.Ticket first = state.takeRequest(0);
        state.preview(B);
        equal(150, state.delayUntilRequest(0));
        equal(0, state.delayUntilRequest(-1));
        date(B, state.takeRequest(-1));
        state.complete(first);
        equal(-1, state.delayUntilRequest(1000));
        state.begin();
        state.preview(A);
        state.takeRequest(Long.MIN_VALUE);
        state.preview(B);
        equal(0, state.delayUntilRequest(Long.MAX_VALUE));
        date(B, state.takeRequest(Long.MAX_VALUE));
        state.complete(null);
        state.failed(null);
        state.markDisplayed(null);
        equal(-1, state.delayUntilRequest(Long.MAX_VALUE));
    }
}
