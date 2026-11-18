package cn.xiaojia.album;

public final class PullRefreshGestureTest {
    private static void check(boolean ok) {
        if (!ok) throw new AssertionError();
    }

    public static void main(String[] args) {
        PullRefreshGesture g = new PullRefreshGesture(8, 72);
        g.start(10, 20, true);
        check(!g.move(11, 24, true));
        check(g.move(14, 73, true) && !g.armed());
        check(!g.release(14, 73));
        g.start(0, 0, true);
        check(g.move(0, 72, true) && g.armed());
        check(g.move(0, 30, true) && !g.armed());
        check(!g.release(0, 30));
        g.start(0, 0, true);
        check(g.move(3, 80, true));
        check(g.release(3, 80));
        check(!g.release(3, 80));
        g.start(0, 0, false);
        check(!g.move(0, 100, true));
        check(!g.release(0, 100));
        g.start(0, 0, true);
        check(!g.move(35, 12, true));
        check(!g.move(35, 110, true));
        g.start(0, 0, true);
        check(!g.move(2, -20, true));
        check(!g.move(2, 100, true));
        g.start(0, 0, true);
        check(!g.move(1, 36, false));
        check(!g.release(1, 108));
        g.start(0, 0, true);
        check(g.move(4, 95, true));
        g.cancel();
        check(!g.release(4, 100));
        g.start(0, 0, true);
        check(!g.release(0, 90));
    }
}
