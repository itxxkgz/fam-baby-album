package cn.xiaojia.album;

import java.util.List;

/** Moves a story page to an explicit visible position without duplicating it. */
final class StorybookOrder {
    static <T> boolean move(List<T> pages, int from, int to) {
        if (from < 0 || to < 0 || from >= pages.size() || to >= pages.size() || from == to)
            return false;
        T page = pages.remove(from);
        pages.add(to, page);
        return true;
    }
}
