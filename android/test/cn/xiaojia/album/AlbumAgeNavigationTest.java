package cn.xiaojia.album;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class AlbumAgeNavigationTest {
    private static int checks;

    private static void expect(Object expected, Object actual) {
        checks++;
        if (!expected.equals(actual))
            throw new AssertionError("Expected " + expected + ", got " + actual);
    }

    private static void position(float expected, float actual) {
        checks++;
        if (Float.isNaN(actual) || Float.isInfinite(actual) || Math.abs(expected - actual) > 0.001f)
            throw new AssertionError("Expected position " + expected + ", got " + actual);
    }

    public static void main(String[] args) {
        expect(-1, AlbumAgeNavigation.indexAt(20, 10, 210, 0));
        expect(-1, AlbumAgeNavigation.indexAt(20, 10, 210, -3));
        expect(0, AlbumAgeNavigation.indexAt(100, 10, 210, 1));
        expect(0, AlbumAgeNavigation.indexAt(-100, 10, 210, 5));
        expect(4, AlbumAgeNavigation.indexAt(999, 10, 210, 5));
        expect(0, AlbumAgeNavigation.indexAt(10, 10, 210, 5));
        expect(1, AlbumAgeNavigation.indexAt(60, 10, 210, 5));
        expect(2, AlbumAgeNavigation.indexAt(110, 10, 210, 5));
        expect(3, AlbumAgeNavigation.indexAt(160, 10, 210, 5));
        expect(4, AlbumAgeNavigation.indexAt(210, 10, 210, 5));
        expect(0, AlbumAgeNavigation.indexAt(34, 10, 210, 5));
        expect(1, AlbumAgeNavigation.indexAt(36, 10, 210, 5));
        expect(0, AlbumAgeNavigation.indexAt(Float.NaN, 10, 210, 5));
        expect(0, AlbumAgeNavigation.indexAt(100, 10, 10, 5));
        expect(0, AlbumAgeNavigation.indexAt(100, 20, 10, 5));
        expect(0, AlbumAgeNavigation.indexAt(100, 10, Float.NaN, 5));
        expect(0, AlbumAgeNavigation.indexAt(Float.NEGATIVE_INFINITY, 10, 210, 5));
        expect(4, AlbumAgeNavigation.indexAt(Float.POSITIVE_INFINITY, 10, 210, 5));
        expect(1, AlbumAgeNavigation.indexAt(50, Float.NaN, 100, 3));
        position(10, AlbumAgeNavigation.positionAt(-10, 10, 210, 5));
        position(10, AlbumAgeNavigation.positionAt(0, 10, 210, 5));
        position(60, AlbumAgeNavigation.positionAt(1, 10, 210, 5));
        position(110, AlbumAgeNavigation.positionAt(2, 10, 210, 5));
        position(210, AlbumAgeNavigation.positionAt(100, 10, 210, 5));
        position(10, AlbumAgeNavigation.positionAt(0, 10, 210, 0));
        position(10, AlbumAgeNavigation.positionAt(100, 10, 210, 1));
        position(10, AlbumAgeNavigation.positionAt(1, 10, 10, 3));
        position(0, AlbumAgeNavigation.positionAt(1, Float.NaN, Float.NaN, 3));
        position(10, AlbumAgeNavigation.positionAt(1, 10, Float.POSITIVE_INFINITY, 3));
        position(0, AlbumAgeNavigation.positionAt(1, -Float.MAX_VALUE, Float.MAX_VALUE, 3));
        for (int index = 0; index < 51; index++) {
            float y = AlbumAgeNavigation.positionAt(index, 18, 590, 51);
            expect(index, AlbumAgeNavigation.indexAt(y, 18, 590, 51));
        }

        expect(-1, AlbumAgeNavigation.indexAfterDrag(0, 100, 0, 0));
        expect(0, AlbumAgeNavigation.indexAfterDrag(500, 100, 0, 1));
        expect(8, AlbumAgeNavigation.indexAfterDrag(0, 100, 8, 11));
        expect(9, AlbumAgeNavigation.indexAfterDrag(10, 100, 8, 11));
        expect(7, AlbumAgeNavigation.indexAfterDrag(-10, 100, 8, 11));
        expect(0, AlbumAgeNavigation.indexAfterDrag(-1000, 100, 8, 11));
        expect(10, AlbumAgeNavigation.indexAfterDrag(1000, 100, 8, 11));
        expect(8, AlbumAgeNavigation.indexAfterDrag(Float.NaN, 100, 8, 11));
        expect(8, AlbumAgeNavigation.indexAfterDrag(50, 0, 8, 11));
        expect(8, AlbumAgeNavigation.indexAfterDrag(50, -100, 8, 11));
        expect(8, AlbumAgeNavigation.indexAfterDrag(50, Float.NaN, 8, 11));
        expect(8, AlbumAgeNavigation.indexAfterDrag(50, Float.POSITIVE_INFINITY, 8, 11));
        expect(10, AlbumAgeNavigation.indexAfterDrag(Float.POSITIVE_INFINITY, 100, 8, 11));
        expect(0, AlbumAgeNavigation.indexAfterDrag(Float.NEGATIVE_INFINITY, 100, 8, 11));
        expect(0, AlbumAgeNavigation.indexAfterDrag(0, 100, -100, 11));
        expect(10, AlbumAgeNavigation.indexAfterDrag(0, 100, 100, 11));
        // Beginning a gesture at a far-away date never jumps to the screen's midpoint.
        expect(98, AlbumAgeNavigation.indexAfterDrag(0, 500, 98, 100));
        expect(98, AlbumAgeNavigation.indexAfterDrag(1, 500, 98, 100));

        List<String> dates = Arrays.asList("2026-05-17", "2024-02-29", "2024-02-27", "2020-01-01");
        expect(
                -1,
                AlbumAgeNavigation.nearestDateIndex(Collections.<String>emptyList(), "2024-02-29"));
        expect(-1, AlbumAgeNavigation.nearestDateIndex(null, "2024-02-29"));
        expect(0, AlbumAgeNavigation.nearestDateIndex(dates, ""));
        expect(0, AlbumAgeNavigation.nearestDateIndex(dates, null));
        expect(0, AlbumAgeNavigation.nearestDateIndex(dates, "2023-02-29"));
        expect(0, AlbumAgeNavigation.nearestDateIndex(dates, "2024-2-29"));
        expect(0, AlbumAgeNavigation.nearestDateIndex(dates, "2030-01-01"));
        expect(3, AlbumAgeNavigation.nearestDateIndex(dates, "2010-12-31"));
        expect(1, AlbumAgeNavigation.nearestDateIndex(dates, "2024-02-29"));
        expect(1, AlbumAgeNavigation.nearestDateIndex(dates, "2024-02-28"));
        expect(2, AlbumAgeNavigation.nearestDateIndex(dates, "2024-02-26"));
        expect(
                0,
                AlbumAgeNavigation.nearestDateIndex(
                        Arrays.asList("2025-01-01", "2024-12-30"), "2024-12-31"));
        expect(
                1,
                AlbumAgeNavigation.nearestDateIndex(
                        Arrays.asList("bad", "2024-02-29", null), "2024-03-01"));
        expect(
                0,
                AlbumAgeNavigation.nearestDateIndex(
                        Arrays.asList("bad", "2024-02-30"), "2024-03-01"));
        expect(
                0,
                AlbumAgeNavigation.nearestDateIndex(
                        Arrays.asList("2024-02-29", "2024-02-29"), "2024-02-29"));

        List<String> labels = Arrays.asList("6岁", "6岁", "5岁", "3岁", "3岁", "1岁");
        expect(Arrays.asList(0, 2, 3, 5), AlbumAgeNavigation.labelIndices(labels, 10));
        expect(Arrays.asList(0, 3, 5), AlbumAgeNavigation.labelIndices(labels, 3));
        expect(Arrays.asList(0, 5), AlbumAgeNavigation.labelIndices(labels, 2));
        expect(Arrays.asList(0), AlbumAgeNavigation.labelIndices(labels, 1));
        expect(Collections.emptyList(), AlbumAgeNavigation.labelIndices(labels, 0));
        expect(Collections.emptyList(), AlbumAgeNavigation.labelIndices(labels, -1));
        expect(Collections.emptyList(), AlbumAgeNavigation.labelIndices(null, 3));
        expect(
                Collections.emptyList(),
                AlbumAgeNavigation.labelIndices(Collections.<String>emptyList(), 3));
        expect(
                Arrays.asList(0),
                AlbumAgeNavigation.labelIndices(Arrays.asList("6岁", "6岁", "6岁"), 5));
        expect(
                Arrays.asList(0, 1),
                AlbumAgeNavigation.labelIndices(Arrays.asList("6岁", "5岁", "6岁"), 5));
        expect(
                Arrays.asList(1, 3),
                AlbumAgeNavigation.labelIndices(
                        Arrays.asList(null, "2026年5月", " ", "2025年9月", "2025年9月"), 5));
        expect(
                Collections.emptyList(),
                AlbumAgeNavigation.labelIndices(Arrays.asList("", " ", null), 5));
        expect(
                Arrays.asList(0, 2, 4, 6),
                AlbumAgeNavigation.labelIndices(
                        Arrays.asList("7岁", "6岁", "5岁", "4岁", "3岁", "2岁", "1岁"), 4));
        // Sparse albums only expose photographed ages; missing ages are never fabricated.
        List<String> sparse = Arrays.asList("5岁", "5岁", "3岁", "3岁", "出生当天");
        expect(Arrays.asList(0, 2, 4), AlbumAgeNavigation.labelIndices(sparse, 12));
        System.out.println("年龄导航逻辑检查通过，共" + checks + "项。");
    }
}
