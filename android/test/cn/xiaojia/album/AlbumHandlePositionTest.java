package cn.xiaojia.album;

public final class AlbumHandlePositionTest {
    private static int checks;

    private static void at(float expected, float actual) {
        checks++;
        if (!Float.isFinite(actual) || Math.abs(expected - actual) > .001f)
            throw new AssertionError("Handle position: expected " + expected + ", got " + actual);
    }

    public static void main(String[] args) {
        AlbumHandlePosition handle = new AlbumHandlePosition();
        at(400, handle.center(800, 24));
        // Gripping below the middle must not snap the chip to the finger center.
        handle.begin(412, 800, 24);
        handle.move(412, 800, 24);
        at(400, handle.center(800, 24));
        handle.move(512, 800, 24);
        at(500, handle.center(800, 24));
        handle.move(312, 800, 24);
        at(300, handle.center(800, 24));
        handle.finish(true);
        at(300, handle.center(800, 24));
        // Closing the bubble after a successful drag must preserve the new spot.
        handle.finish(false);
        at(300, handle.center(800, 24));
        // The next touch starts at the new handle location, including an upper grip.
        handle.begin(290, 800, 24);
        handle.move(390, 800, 24);
        at(400, handle.center(800, 24));
        handle.finish(false);
        at(300, handle.center(800, 24));
        handle.move(700, 800, 24);
        at(300, handle.center(800, 24));
        // Hit target remains entirely on screen at both limits.
        handle.begin(300, 800, 24);
        handle.move(-100, 800, 24);
        at(24, handle.center(800, 24));
        handle.move(1000, 800, 24);
        at(776, handle.center(800, 24));
        handle.finish(true);
        at(376, handle.center(400, 24));
        handle.begin(376, 400, 24);
        handle.move(200, 400, 24);
        at(200, handle.center(400, 24));
        handle.finish(false);
        at(776, handle.center(800, 24));
        // Long press without movement and tiny layouts do not shift or divide by zero.
        handle.begin(772, 800, 24);
        handle.finish(false);
        at(776, handle.center(800, 24));
        at(10, handle.center(20, 24));
        at(0, handle.center(0, 24));
        // The preview uses the same clamped center as the visible handle.
        handle.begin(776, 800, 24);
        handle.move(400, 800, 24);
        at(-376, handle.center(800, 24) - handle.startCenter(800, 24));
        at(776, handle.startCenter(800, 24));
        handle.finish(true);
        at(200, handle.center(400, 24));
        // Full-album scrolling can place the handle anywhere on an asymmetric track.
        handle.setFraction(0);
        at(160, handle.centerWithin(160, 776));
        handle.setFraction(.5f);
        at(468, handle.centerWithin(160, 776));
        handle.setFraction(1);
        at(776, handle.centerWithin(160, 776));
        handle.setFraction(.25f);
        at(314, handle.centerWithin(160, 776));
        handle.beginWithin(324, 160, 776);
        handle.setFraction(.9f);
        at(314, handle.centerWithin(160, 776));
        handle.moveWithin(424, 160, 776);
        at(414, handle.centerWithin(160, 776));
        handle.setFraction(0);
        at(414, handle.centerWithin(160, 776));
        handle.finish(false);
        at(314, handle.centerWithin(160, 776));
        handle.setFraction(.75f);
        at(622, handle.centerWithin(160, 776));
        handle.beginWithin(622, 160, 776);
        handle.moveWithin(900, 160, 776);
        at(776, handle.centerWithin(160, 776));
        handle.finish(true);
        handle.finish(false);
        at(776, handle.centerWithin(160, 776));
        // Once actual list layout completes, ordinary scrolling owns the position again.
        handle.setFraction(.4f);
        at(406.4f, handle.centerWithin(160, 776));
        at(236.4f, handle.centerWithin(120, 411));
        handle.setFraction(Float.NaN);
        at(406.4f, handle.centerWithin(160, 776));
        handle.setFraction(-1);
        at(160, handle.centerWithin(160, 776));
        handle.setFraction(2);
        at(776, handle.centerWithin(160, 776));
        at(100, handle.centerWithin(100, 100));
        System.out.println("把手跟随与位置检查通过，共" + checks + "项。");
    }
}
