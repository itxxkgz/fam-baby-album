package cn.xiaojia.album;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Maps a navigation rail only to dates and labels that exist in the album. */
final class AlbumAgeNavigation {
    private AlbumAgeNavigation() {}

    /** Nearest date anchor; the first and last dates occupy the rail ends. */
    static int indexAt(float y, float top, float bottom, int count) {
        if (count <= 0) return -1;
        float start = finite(top) ? top : 0f;
        float end = finite(bottom) && bottom > start ? bottom : start;
        if (count == 1 || end == start || Float.isNaN(y) || y <= start) return 0;
        if (y >= end) return count - 1;
        double ratio = ((double) y - start) / ((double) end - start);
        return (int) Math.max(0L, Math.min(count - 1L, Math.round(ratio * (count - 1L))));
    }

    /** Empty or collapsed tracks use their finite starting position. */
    static float positionAt(int index, float top, float bottom, int count) {
        float start = finite(top) ? top : 0f;
        float end = finite(bottom) && bottom > start ? bottom : start;
        if (count <= 1 || index <= 0 || end == start) return start;
        if (index >= count - 1) return end;
        return (float) (start + ((double) end - start) * index / (count - 1L));
    }

    /** Relative drag from the current date; travel is the distance for the full date span. */
    static int indexAfterDrag(float deltaY, float travel, int startIndex, int count) {
        if (count <= 0) return -1;
        int start = Math.max(0, Math.min(count - 1, startIndex));
        if (count == 1 || Float.isNaN(deltaY) || !finite(travel) || travel <= 0) return start;
        double candidate = start + ((double) deltaY / travel) * (count - 1L);
        if (candidate <= 0) return 0;
        if (candidate >= count - 1L) return count - 1;
        return (int) Math.round(candidate);
    }

    /** Equal distances keep the earlier entry (the newer day in a descending list). */
    static int nearestDateIndex(List<String> dates, String currentDate) {
        if (dates == null || dates.isEmpty()) return -1;
        LocalDate current = parseDate(currentDate);
        if (current == null) return 0;
        long target = current.toEpochDay(), closest = Long.MAX_VALUE;
        int result = 0;
        for (int index = 0; index < dates.size(); index++) {
            LocalDate candidate = parseDate(dates.get(index));
            if (candidate == null) continue;
            long distance = Math.abs(candidate.toEpochDay() - target);
            if (distance < closest) {
                closest = distance;
                result = index;
                if (distance == 0) break;
            }
        }
        return result;
    }

    /** First occurrences only; when space is limited, sample those actual labels. */
    static List<Integer> labelIndices(List<String> labels, int maxLabels) {
        if (labels == null || labels.isEmpty() || maxLabels <= 0) return Collections.emptyList();
        List<Integer> firsts = new ArrayList<Integer>();
        Set<String> seen = new HashSet<String>();
        for (int index = 0; index < labels.size(); index++) {
            String label = labels.get(index);
            if (label != null && !label.trim().isEmpty() && seen.add(label.trim()))
                firsts.add(index);
        }
        if (firsts.size() <= maxLabels) return firsts;
        List<Integer> result = new ArrayList<Integer>(maxLabels);
        if (maxLabels == 1) {
            result.add(firsts.get(0));
            return result;
        }
        for (int slot = 0; slot < maxLabels; slot++) {
            int index = (int) Math.round((double) slot * (firsts.size() - 1L) / (maxLabels - 1L));
            result.add(firsts.get(index));
        }
        return result;
    }

    private static boolean finite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }

    private static LocalDate parseDate(String value) {
        if (value == null || !value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) return null;
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }
}
