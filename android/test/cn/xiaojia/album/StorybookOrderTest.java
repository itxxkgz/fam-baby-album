package cn.xiaojia.album;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class StorybookOrderTest {
    private static int checked;
    private static void expect(String wanted, List<String> actual) {
        if (!wanted.equals(String.join("", actual))) throw new AssertionError("故事页顺序不正确");
        checked++;
    }
    public static void main(String[] args) {
        List<String> pages = new ArrayList<>(Arrays.asList("a", "b", "c", "d"));
        StorybookOrder.move(pages, 0, 3); expect("bcda", pages);
        StorybookOrder.move(pages, 3, 0); expect("abcd", pages);
        StorybookOrder.move(pages, 1, 2); expect("acbd", pages);
        StorybookOrder.move(pages, 2, 2); expect("acbd", pages);
        StorybookOrder.move(pages, -1, 2); expect("acbd", pages);
        StorybookOrder.move(pages, 0, 4); expect("acbd", pages);
        pages.clear(); StorybookOrder.move(pages, 0, 0); expect("", pages);
        pages.add("a"); StorybookOrder.move(pages, 0, 0); expect("a", pages);
        System.out.println("故事页排序检查通过，共" + checked + "项。");
    }
}
