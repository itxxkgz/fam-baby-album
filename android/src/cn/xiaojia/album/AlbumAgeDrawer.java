package cn.xiaojia.album;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.TreeMap;

/** Warm two-column picker containing only photographed months. */
final class AlbumAgeDrawer extends FrameLayout {
    interface Listener {
        void onDateSelected(String date);

        void onDismiss();
    }

    private static final class Day {
        final String date, monthLabel;
        final LocalDate parsed;
        final Integer ageMonths;
        final int count;

        Day(JSONObject value, LocalDate date) {
            this.date = date.toString();
            parsed = date;
            count = Math.max(1, value.optInt("count", 1));
            ageMonths =
                    value.has("age_months") && !value.isNull("age_months")
                            ? Integer.valueOf(value.optInt("age_months", -1))
                            : null;
            monthLabel = value.optString("month_label", "").trim();
        }
    }

    private static final class Month {
        final String key, label, latestDate;
        int count;

        Month(String key, String label, Day day) {
            this.key = key;
            this.label = label;
            latestDate = day.date;
        }
    }

    private static final class Group {
        final String key, label;
        final LinkedHashMap<String, Month> months = new LinkedHashMap<>();

        Group(String key, String label) {
            this.key = key;
            this.label = label;
        }
    }

    private final Listener listener;
    private final ArrayList<Day> days = new ArrayList<>();
    private final ArrayList<String> dates = new ArrayList<>();
    private final LinkedHashMap<String, Group> groups = new LinkedHashMap<>();
    private final LinearLayout panel, leftColumn, rightColumn;
    private final ScrollView leftScroll, rightScroll;
    private final TextView ageButton, dateButton, leftHeading, rightHeading;
    private boolean ageMode, hasBirthday, dismissed;
    private String currentDate = "", selectedGroup = "";

    AlbumAgeDrawer(Context context, JSONArray items, String currentDate, Listener listener) {
        super(context);
        this.listener = listener;
        parse(items);
        ageMode = hasBirthday;
        setFocusableInTouchMode(true);
        setContentDescription("相册快速定位");

        View shade = new View(context);
        shade.setBackgroundColor(Color.argb(74, 48, 37, 34));
        shade.setContentDescription("关闭快速定位");
        shade.setOnClickListener(view -> dismiss());
        addView(shade, new FrameLayout.LayoutParams(-1, -1));

        panel = Ui.column(context);
        panel.setBackground(Ui.round(Ui.BG, 22, context));
        panel.setElevation(dp(10));
        panel.setClickable(true);
        panel.setPadding(dp(12), dp(12), dp(12), dp(12));
        int width = Math.round(context.getResources().getDisplayMetrics().widthPixels * .70f);
        addView(panel, new FrameLayout.LayoutParams(width, -1, Gravity.RIGHT));

        LinearLayout titleRow = Ui.row(context);
        TextView title = Ui.text(context, "快速定位", 18, Ui.INK);
        title.setTypeface(null, Typeface.BOLD);
        title.setPadding(dp(4), 0, 0, 0);
        titleRow.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        FrameLayout close = new FrameLayout(context);
        close.setContentDescription("关闭快速定位");
        close.setBackground(Ui.ripple(Color.TRANSPARENT, 22, context));
        close.setOnClickListener(view -> dismiss());
        close.setFocusable(true);
        close.addView(
                new Ui.Icon(context, "close", Ui.SOFT_INK),
                new FrameLayout.LayoutParams(dp(19), dp(19), Gravity.CENTER));
        titleRow.addView(close, new LinearLayout.LayoutParams(dp(44), dp(44)));
        panel.addView(titleRow, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout modes = Ui.row(context);
        modes.setPadding(dp(4), dp(4), dp(4), dp(4));
        modes.setBackground(Ui.round(Ui.SOFT, 15, context));
        ageButton = navText("按年龄", 13);
        dateButton = navText("按日期", 13);
        ageButton.setGravity(Gravity.CENTER);
        dateButton.setGravity(Gravity.CENTER);
        ageButton.setEnabled(hasBirthday);
        ageButton.setAlpha(hasBirthday ? 1f : .45f);
        ageButton.setOnClickListener(view -> setMode(true));
        dateButton.setOnClickListener(view -> setMode(false));
        modes.addView(ageButton, new LinearLayout.LayoutParams(0, dp(44), 1));
        modes.addView(dateButton, new LinearLayout.LayoutParams(0, dp(44), 1));
        panel.addView(modes, new LinearLayout.LayoutParams(-1, -2));

        if (!hasBirthday && !days.isEmpty()) {
            TextView hint = Ui.text(context, "填写宝宝生日后，也能按年龄找照片", 11, Ui.MUTED);
            hint.setPadding(dp(4), dp(10), dp(4), 0);
            hint.setLineSpacing(dp(2), 1);
            panel.addView(hint, new LinearLayout.LayoutParams(-1, -2));
        }

        LinearLayout columnHeadings = Ui.row(context);
        leftHeading = Ui.text(context, "", 11, Ui.MUTED);
        rightHeading = Ui.text(context, "月份", 11, Ui.MUTED);
        leftHeading.setPadding(dp(9), dp(17), dp(4), dp(9));
        rightHeading.setPadding(dp(11), dp(17), dp(4), dp(9));
        columnHeadings.addView(leftHeading, new LinearLayout.LayoutParams(0, -2, .34f));
        columnHeadings.addView(rightHeading, new LinearLayout.LayoutParams(0, -2, .66f));
        panel.addView(columnHeadings, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout columns = Ui.row(context);
        columns.setGravity(Gravity.TOP);
        leftColumn = Ui.column(context);
        rightColumn = Ui.column(context);
        leftScroll = new ScrollView(context);
        rightScroll = new ScrollView(context);
        leftScroll.setVerticalScrollBarEnabled(false);
        rightScroll.setVerticalScrollBarEnabled(false);
        leftScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        rightScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        leftScroll.addView(leftColumn, new ScrollView.LayoutParams(-1, -2));
        rightScroll.addView(rightColumn, new ScrollView.LayoutParams(-1, -2));
        columns.addView(leftScroll, new LinearLayout.LayoutParams(0, -1, .34f));
        View divider = new View(context);
        divider.setBackgroundColor(Ui.LINE);
        columns.addView(divider, new LinearLayout.LayoutParams(dp(1), -1));
        columns.addView(rightScroll, new LinearLayout.LayoutParams(0, -1, .66f));
        panel.addView(columns, new LinearLayout.LayoutParams(-1, 0, 1));

        setCurrentDate(currentDate);
    }

    private int dp(float value) {
        return Ui.dp(getContext(), value);
    }

    private TextView navText(String label, int size) {
        TextView view =
                new TextView(getContext()) {
                    @Override
                    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
                        super.onInitializeAccessibilityNodeInfo(info);
                        info.setClassName(Button.class.getName());
                    }
                };
        view.setText(label);
        view.setTextSize(size);
        view.setTextColor(Ui.INK);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setMinHeight(dp(44));
        view.setFocusable(true);
        view.setLineSpacing(dp(2), 1);
        return view;
    }

    private void parse(JSONArray values) {
        TreeMap<String, Day> ordered = new TreeMap<>(Collections.reverseOrder());
        if (values != null)
            for (int index = 0; index < values.length(); index++) {
                JSONObject value = values.optJSONObject(index);
                if (value == null || value.optInt("count", 1) <= 0) continue;
                try {
                    LocalDate date = LocalDate.parse(value.optString("date", ""));
                    Day day = new Day(value, date);
                    ordered.put(day.date, day);
                    if (day.ageMonths != null) hasBirthday = true;
                } catch (RuntimeException ignored) {
                    // Invalid rows cannot become a visible or selectable month.
                }
            }
        days.addAll(ordered.values());
        for (Day day : days) dates.add(day.date);
    }

    void setCurrentDate(String date) {
        int index = AlbumAgeNavigation.nearestDateIndex(dates, date);
        currentDate = index >= 0 ? days.get(index).date : "";
        buildGroups();
        selectedGroup = index >= 0 ? groupKey(days.get(index)) : "";
        if (!groups.containsKey(selectedGroup))
            selectedGroup = groups.isEmpty() ? "" : groups.keySet().iterator().next();
        renderGroups(true);
    }

    private void setMode(boolean age) {
        if (age && !hasBirthday) return;
        if (ageMode == age) return;
        ageMode = age;
        setCurrentDate(currentDate);
    }

    private String groupKey(Day day) {
        if (!ageMode) return String.valueOf(day.parsed.getYear());
        return day.ageMonths == null
                ? ""
                : String.valueOf(day.ageMonths < 0 ? -1 : day.ageMonths / 12);
    }

    private String monthKey(Day day) {
        return ageMode ? String.valueOf(day.ageMonths) : String.valueOf(day.parsed.getMonthValue());
    }

    private void buildGroups() {
        groups.clear();
        for (Day day : days) {
            if (ageMode && day.ageMonths == null) continue;
            String key = groupKey(day);
            Group group = groups.get(key);
            if (group == null) {
                String label =
                        !ageMode
                                ? key + "年"
                                : day.ageMonths < 0
                                        ? "出生前"
                                        : day.ageMonths < 12 ? "未满1岁" : key + "岁";
                group = new Group(key, label);
                groups.put(key, group);
            }
            String monthKey = monthKey(day);
            Month month = group.months.get(monthKey);
            if (month == null) {
                String label = day.parsed.getMonthValue() + "月";
                if (ageMode) {
                    int years = Math.max(0, day.ageMonths / 12),
                            months = Math.max(0, day.ageMonths % 12);
                    label =
                            day.ageMonths < 0
                                    ? "出生前"
                                    : day.ageMonths == 0
                                            ? "出生"
                                            : (years > 0 ? years + "岁" : "")
                                                    + (months > 0 ? months + "个月" : "");
                    if (!day.monthLabel.isEmpty()) label = day.monthLabel;
                }
                month = new Month(monthKey, label, day);
                group.months.put(monthKey, month);
            }
            month.count = (int) Math.min(Integer.MAX_VALUE, (long) month.count + day.count);
        }
    }

    private void renderGroups(boolean scrollToCurrent) {
        ageButton.setSelected(ageMode);
        dateButton.setSelected(!ageMode);
        ageButton.setTextColor(ageMode ? Ui.ACCENT_DARK : Ui.SOFT_INK);
        dateButton.setTextColor(ageMode ? Ui.SOFT_INK : Ui.ACCENT_DARK);
        ageButton.setBackground(
                Ui.ripple(ageMode ? Ui.SURFACE : Color.TRANSPARENT, 12, getContext()));
        dateButton.setBackground(
                Ui.ripple(ageMode ? Color.TRANSPARENT : Ui.SURFACE, 12, getContext()));
        ageButton.setContentDescription(hasBirthday ? "按年龄定位" : "按年龄定位，需要先填写宝宝生日");
        dateButton.setContentDescription("按日期定位");
        leftHeading.setText(ageMode ? "年龄" : "年份");
        rightHeading.setText("月份");
        leftColumn.removeAllViews();
        View current = null;
        for (Group group : groups.values()) {
            boolean selected = group.key.equals(selectedGroup);
            TextView button = navText(group.label, 13);
            button.setPadding(dp(8), dp(10), dp(6), dp(10));
            button.setMinHeight(dp(52));
            button.setTextColor(selected ? Ui.ACCENT_DARK : Ui.SOFT_INK);
            button.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
            button.setBackground(
                    Ui.ripple(selected ? Ui.SOFT : Color.TRANSPARENT, 10, getContext()));
            button.setSelected(selected);
            button.setContentDescription(
                    group.label + (selected ? "，已选中" : "") + "，" + group.months.size() + "个月份有记录");
            button.setOnClickListener(
                    view -> {
                        selectedGroup = group.key;
                        renderGroups(false);
                    });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
            params.rightMargin = dp(5);
            params.bottomMargin = dp(4);
            leftColumn.addView(button, params);
            if (selected) current = button;
        }
        if (scrollToCurrent && current != null) center(leftScroll, current);
        renderMonths(scrollToCurrent);
    }

    private void renderMonths(boolean scrollToCurrent) {
        rightColumn.removeAllViews();
        Group group = groups.get(selectedGroup);
        if (group == null) {
            TextView empty = Ui.text(getContext(), "还没有可定位的照片", 12, Ui.MUTED);
            empty.setPadding(dp(12), dp(20), dp(6), dp(20));
            rightColumn.addView(empty);
            return;
        }
        Day current = null;
        for (Day day : days)
            if (day.date.equals(currentDate)) {
                current = day;
                break;
            }
        View highlighted = null;
        for (Month month : group.months.values()) {
            boolean selected =
                    current != null
                            && group.key.equals(groupKey(current))
                            && month.key.equals(monthKey(current));
            TextView button = navText(month.label, 14);
            button.setMinHeight(dp(52));
            button.setPadding(dp(11), dp(9), dp(7), dp(9));
            button.setTextColor(selected ? Ui.ACCENT_DARK : Ui.INK);
            button.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
            button.setBackground(
                    selected
                            ? Ui.border(Ui.SOFT, 12, 0xFFE7C4AE, getContext())
                            : Ui.ripple(Color.TRANSPARENT, 12, getContext()));
            button.setSelected(selected);
            button.setContentDescription(
                    group.label
                            + "，"
                            + month.label
                            + "，"
                            + month.count
                            + "份回忆"
                            + (selected ? "，当前浏览月份" : "")
                            + "，点击查看");
            button.setOnClickListener(
                    view -> {
                        if (dismissed) return;
                        dismissed = true;
                        listener.onDateSelected(month.latestDate);
                    });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
            params.leftMargin = dp(6);
            params.bottomMargin = dp(5);
            rightColumn.addView(button, params);
            if (selected) highlighted = button;
        }
        if (scrollToCurrent && highlighted != null) center(rightScroll, highlighted);
        else rightScroll.scrollTo(0, 0);
    }

    private void center(ScrollView scroll, View child) {
        scroll.post(
                () -> {
                    if (isAttachedToWindow() && child.getParent() != null)
                        scroll.scrollTo(
                                0,
                                Math.max(
                                        0,
                                        child.getTop()
                                                - (scroll.getHeight() - child.getHeight()) / 2));
                });
    }

    private void dismiss() {
        if (dismissed) return;
        dismissed = true;
        listener.onDismiss();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) panel.getLayoutParams();
        int width = Math.max(1, Math.round(w * .70f));
        if (params.width != width) {
            params.width = width;
            panel.setLayoutParams(params);
        }
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_ESCAPE) {
            dismiss();
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }
}
