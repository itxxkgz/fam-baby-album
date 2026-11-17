package cn.xiaojia.album;

/** Boundary cases for page commits; run with the Android platform jar on the classpath. */
public final class MediaPagerDecisionTest {
  private static int checked;

  private static void expect(int wanted, float distance, float velocity, float width) {
    int actual = MediaPagerView.pageDirection(distance, velocity, width, 24, 500);
    if (actual != wanted) throw new AssertionError("翻页手势判定未通过");
    checked++;
  }

  public static void main(String[] arguments) {
    expect(0, 0, 1000, 400); // A quick tap is not a fling.
    expect(0, 10, 1200, 400); // Jitter must travel a useful distance.
    expect(0, -70, -100, 400); // A short slow pull springs back.
    expect(1, -100, -100, 400); // A deliberate left pull advances.
    expect(-1, 100, 0, 400); // A deliberate right pull goes back.
    expect(1, -30, -900, 400); // A short fast flick may advance.
    expect(-1, 30, 900, 400);
    expect(0, -180, 800, 400); // Reversing before release cancels the page turn.
    expect(0, 180, -800, 400);
    expect(0, -100, -900, 0); // An unmeasured view cannot page.
    expect(0, Float.NaN, 0, 400);
    expect(0, Float.POSITIVE_INFINITY, 0, 400);
    expect(0, 100, Float.NEGATIVE_INFINITY, 400);
    expect(0, -100, -100, 800); // Large screens use proportionate drag distance.
    expect(1, -200, -100, 800);
    System.out.println("翻页手势判定检查通过，共" + checked + "项。");
  }
}
