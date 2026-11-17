package cn.xiaojia.album;

import java.util.List;

/** Restores the visible memory after insertions or deletions during a refresh. */
final class TimeCollectionPosition {
    private String anchor = "";
    private int row, offset;

    void remember(List<String> ids, int firstRow, int top, int columns) {
        row = Math.max(0, firstRow);
        offset = top;
        int index = (row - 1) * Math.max(1, columns);
        anchor = index >= 0 && index < ids.size() ? ids.get(index) : "";
    }

    int row(List<String> ids, int columns) {
        int index = anchor.isEmpty() ? -1 : ids.indexOf(anchor);
        if (index >= 0) return index / Math.max(1, columns) + 1;
        int rows = (ids.size() + Math.max(1, columns) - 1) / Math.max(1, columns);
        return Math.min(row, Math.max(0, rows));
    }

    int offset() {
        return offset;
    }
}
