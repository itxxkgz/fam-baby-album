package cn.xiaojia.album;

public final class AlbumTimelineProgressTest {
    private static int checks;

    private static void expect(float expected, float actual) {
        checks++;
        if (Float.isNaN(actual)
                || Float.isInfinite(actual)
                || Math.abs(expected - actual) > 0.00001f)
            throw new AssertionError("Expected progress " + expected + ", got " + actual);
    }

    public static void main(String[] args) {
        expect(0f, AlbumTimelineProgress.fraction(0, 10, 0, false, false));
        expect(.05f, AlbumTimelineProgress.fraction(0, 10, .5f, false, false));
        expect(.5f, AlbumTimelineProgress.fraction(5, 10, 0, false, false));
        expect(.55f, AlbumTimelineProgress.fraction(5, 10, .5f, false, false));
        expect(.9f, AlbumTimelineProgress.fraction(9, 10, 0, false, false));
        expect(.95f, AlbumTimelineProgress.fraction(9, 10, .5f, false, false));
        expect(1f, AlbumTimelineProgress.fraction(9, 10, 1, false, false));
        expect(0f, AlbumTimelineProgress.fraction(0, 1, 0, false, false));
        expect(.5f, AlbumTimelineProgress.fraction(0, 1, .5f, false, false));
        expect(1f, AlbumTimelineProgress.fraction(0, 1, 1, false, false));
        expect(0f, AlbumTimelineProgress.fraction(7, 10, .9f, true, false));
        expect(1f, AlbumTimelineProgress.fraction(2, 10, .1f, false, true));
        expect(0f, AlbumTimelineProgress.fraction(0, 1, .9f, true, true));
        expect(0f, AlbumTimelineProgress.fraction(3, 10, .9f, true, true));
        expect(0f, AlbumTimelineProgress.fraction(0, 0, 1, false, true));
        expect(0f, AlbumTimelineProgress.fraction(0, -1, .5f, false, false));
        expect(0f, AlbumTimelineProgress.fraction(-1, 10, .5f, false, false));
        expect(0f, AlbumTimelineProgress.fraction(Integer.MIN_VALUE, 10, 1, false, false));
        expect(1f, AlbumTimelineProgress.fraction(10, 10, 0, false, false));
        expect(1f, AlbumTimelineProgress.fraction(Integer.MAX_VALUE, 10, 0, false, false));
        expect(.5f, AlbumTimelineProgress.fraction(5, 10, Float.NaN, false, false));
        expect(.6f, AlbumTimelineProgress.fraction(5, 10, Float.POSITIVE_INFINITY, false, false));
        expect(.5f, AlbumTimelineProgress.fraction(5, 10, Float.NEGATIVE_INFINITY, false, false));
        expect(.6f, AlbumTimelineProgress.fraction(5, 10, 8, false, false));
        expect(.5f, AlbumTimelineProgress.fraction(5, 10, -8, false, false));

        expect(0f, AlbumTimelineProgress.withinDay(0, 3, 0, 120));
        expect(.0125f, AlbumTimelineProgress.withinDay(0, 3, .5f, 120));
        expect(.025f, AlbumTimelineProgress.withinDay(0, 3, 1, 120));
        expect(.5f, AlbumTimelineProgress.withinDay(60, 3, 0, 120));
        expect(.5125f, AlbumTimelineProgress.withinDay(60, 3, .5f, 120));
        expect(1f, AlbumTimelineProgress.withinDay(117, 3, 1, 120));
        expect(.5f, AlbumTimelineProgress.withinDay(0, 1, .5f, 1));
        expect(1f, AlbumTimelineProgress.withinDay(0, 1, 1, 1));
        expect(0f, AlbumTimelineProgress.withinDay(5, 3, .5f, 0));
        expect(0f, AlbumTimelineProgress.withinDay(5, 3, .5f, -1));
        expect(.05f, AlbumTimelineProgress.withinDay(-5, 2, .5f, 20));
        expect(.25f, AlbumTimelineProgress.withinDay(5, -2, .5f, 20));
        expect(0f, AlbumTimelineProgress.withinDay(-5, -2, .5f, 20));
        expect(.5f, AlbumTimelineProgress.withinDay(10, 2, Float.NaN, 20));
        expect(.5f, AlbumTimelineProgress.withinDay(10, 2, -1, 20));
        expect(.6f, AlbumTimelineProgress.withinDay(10, 2, 2, 20));
        expect(.6f, AlbumTimelineProgress.withinDay(10, 2, Float.POSITIVE_INFINITY, 20));
        expect(.5f, AlbumTimelineProgress.withinDay(10, 2, Float.NEGATIVE_INFINITY, 20));
        expect(1f, AlbumTimelineProgress.withinDay(200, 3, 0, 120));
        expect(
                1f,
                AlbumTimelineProgress.withinDay(
                        Integer.MAX_VALUE, Integer.MAX_VALUE, 1, Integer.MAX_VALUE));
        expect(.5f, AlbumTimelineProgress.withinDay(0, Integer.MAX_VALUE, .5f, Integer.MAX_VALUE));

        // Scrolling to the end of the first loaded page is not the end of a
        // 120-photo day. Fetching another page leaves the same visible anchor
        // at the same full-day and full-album fraction.
        float beforeFetch = AlbumTimelineProgress.withinDay(57, 3, 1, 120);
        float afterFetch = AlbumTimelineProgress.withinDay(60, 3, 0, 120);
        expect(.5f, beforeFetch);
        expect(beforeFetch, afterFetch);
        expect(.45f, AlbumTimelineProgress.fraction(4, 10, beforeFetch, false, false));
        expect(.45f, AlbumTimelineProgress.fraction(4, 10, afterFetch, false, false));
        expect(
                .475f,
                AlbumTimelineProgress.fraction(
                        4, 10, AlbumTimelineProgress.withinDay(87, 3, 1, 120), false, false));

        // With a day starting before the loaded window, mediaBefore includes
        // the missing preceding photos. A partial page never resets to zero.
        expect(.7625f, AlbumTimelineProgress.withinDay(90, 3, .5f, 120));
        expect(
                .97625f,
                AlbumTimelineProgress.fraction(
                        9, 10, AlbumTimelineProgress.withinDay(90, 3, .5f, 120), false, false));
        expect(
                1f,
                AlbumTimelineProgress.fraction(
                        9, 10, AlbumTimelineProgress.withinDay(90, 3, .5f, 120), false, true));
        System.out.println("相册滚动位置检查通过，共" + checks + "项。");
    }
}
