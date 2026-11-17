package cn.xiaojia.album;

/** Stable full-album progress, independent of how many pages are loaded. */
final class AlbumTimelineProgress {
    private AlbumTimelineProgress() {}

    /** Every photographed day owns an equal part of the full navigation track. */
    static float fraction(
            int dayIndex, int dayCount, float dayFraction, boolean atStart, boolean atEnd) {
        if (dayCount <= 0 || atStart) return 0f;
        if (atEnd) return 1f;
        return clamp(((double) dayIndex + clamp(dayFraction)) / dayCount);
    }

    /** The denominator is the complete day's count, never the loaded subset. */
    static float withinDay(int mediaBefore, int rowItems, float rowFraction, int fullDayCount) {
        if (fullDayCount <= 0) return 0f;
        double before = Math.max(0, mediaBefore);
        double items = Math.max(0, rowItems);
        return clamp((before + items * clamp(rowFraction)) / fullDayCount);
    }

    private static float clamp(double value) {
        if (Double.isNaN(value)) return 0f;
        return (float) Math.max(0d, Math.min(1d, value));
    }
}
