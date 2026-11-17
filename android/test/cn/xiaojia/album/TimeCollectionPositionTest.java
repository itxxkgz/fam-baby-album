package cn.xiaojia.album;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class TimeCollectionPositionTest {
    private static int checks;

    private static void expect(int expected, int actual) {
        if (expected != actual)
            throw new AssertionError("Expected " + expected + ", got " + actual);
        checks++;
    }

    public static void main(String[] args) {
        TimeCollectionPosition position = new TimeCollectionPosition();
        position.remember(Arrays.asList("a", "b", "c", "d"), 3, -27, 1);
        expect(4, position.row(Arrays.asList("new", "a", "b", "c", "d"), 1));
        expect(-27, position.offset());
        expect(2, position.row(Arrays.asList("a", "c", "d"), 1));
        position.remember(Arrays.asList("a", "b", "c", "d", "e", "f"), 3, -12, 2);
        expect(4, position.row(Arrays.asList("new1", "new2", "a", "b", "c", "d", "e", "f"), 2));
        expect(5, position.row(Arrays.asList("a", "b", "c", "d", "e", "f"), 1));
        expect(1, position.row(Arrays.asList("a", "b"), 2));
        expect(0, position.row(Collections.emptyList(), 2));
        position.remember(Arrays.asList("a", "b"), 0, 0, 2);
        expect(0, position.row(Arrays.asList("new", "a", "b"), 2));
        // The first visible row in the compact grid begins with d. Keep that photo
        // visible when new uploads arrive, earlier items disappear, or columns change.
        List<String> grid = Arrays.asList("a", "b", "c", "d", "e", "f", "g", "h", "i");
        position.remember(grid, 2, -19, 3);
        expect(2, position.row(grid, 3));
        expect(-19, position.offset());
        expect(
                3,
                position.row(
                        Arrays.asList(
                                "n1", "n2", "n3", "a", "b", "c", "d", "e", "f", "g", "h", "i"),
                        3));
        expect(1, position.row(Arrays.asList("c", "d", "e", "f", "g", "h", "i"), 3));
        expect(2, position.row(grid, 2));
        expect(4, position.row(grid, 1));
        expect(1, position.row(grid, 4));

        // If d was removed from a collection, return to the closest remaining row.
        expect(2, position.row(Arrays.asList("a", "b", "c", "e", "f", "g", "h", "i"), 3));
        expect(1, position.row(Arrays.asList("a", "b"), 3));
        expect(0, position.row(Collections.emptyList(), 3));

        // A visible title stays at the top; an old footer cannot restore past the end.
        position.remember(grid, 0, 0, 3);
        expect(0, position.row(grid, 3));
        position.remember(grid, 4, -8, 3);
        expect(3, position.row(grid, 3));
        System.out.println("浏览位置检查通过，共" + checks + "项。");
    }
}
