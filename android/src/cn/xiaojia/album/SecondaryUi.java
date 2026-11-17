package cn.xiaojia.album;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

/** Small shared controls for photo tools and quiet secondary pages. */
final class SecondaryUi {
    static String time(String value) {
        try {
            java.time.ZoneId zone = java.time.ZoneId.of("Asia/Shanghai");
            java.time.ZonedDateTime date =
                    java.time.OffsetDateTime.parse(value).atZoneSameInstant(zone);
            java.time.LocalDate today = java.time.LocalDate.now(zone);
            String clock = date.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
            if (date.toLocalDate().equals(today)) return "今天 " + clock;
            if (date.toLocalDate().equals(today.minusDays(1))) return "昨天 " + clock;
            return Ui.readable(value);
        } catch (Exception ignored) {
            return "时间待确认";
        }
    }

    static TextView note(Context c, LinearLayout parent, String value) {
        TextView text = Ui.text(c, value, 13, Ui.MUTED);
        text.setPadding(0, Ui.dp(c, 10), 0, Ui.dp(c, 10));
        text.setLineSpacing(Ui.dp(c, 3), 1);
        parent.addView(text, new LinearLayout.LayoutParams(-1, -2));
        return text;
    }

    static void divider(Context c, LinearLayout parent) {
        View line = new View(c);
        line.setBackgroundColor(Ui.LINE);
        parent.addView(line, new LinearLayout.LayoutParams(-1, Ui.dp(c, .5f)));
    }

    static View row(Context c, String icon, String title, String value, Runnable click) {
        LinearLayout row = Ui.row(c);
        row.setMinimumHeight(Ui.dp(c, 56));
        row.setPadding(0, Ui.dp(c, 10), 0, Ui.dp(c, 10));
        if (icon != null) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(Ui.dp(c, 25), Ui.dp(c, 25));
            p.rightMargin = Ui.dp(c, 12);
            row.addView(new Ui.Icon(c, icon, Ui.ACCENT), p);
        }
        TextView label = Ui.text(c, title, 16, Ui.INK);
        row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        if (value != null && !value.isEmpty()) {
            TextView detail = Ui.text(c, value, 14, Ui.MUTED);
            detail.setMaxWidth(
                    Math.max(
                            Ui.dp(c, 72),
                            (c.getResources().getDisplayMetrics().widthPixels - Ui.dp(c, 68))
                                    * 46
                                    / 100));
            detail.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            detail.setPadding(Ui.dp(c, 6), 0, Ui.dp(c, 8), 0);
            row.addView(detail, new LinearLayout.LayoutParams(-2, -2));
        }
        if (click != null) {
            row.addView(
                    new Ui.Icon(c, "next", Ui.MUTED),
                    new LinearLayout.LayoutParams(Ui.dp(c, 16), Ui.dp(c, 16)));
            row.setBackground(Ui.ripple(Ui.BG, 12, c));
            row.setFocusable(true);
            row.setOnClickListener(v -> click.run());
        }
        return row;
    }

    static Switch toggle(Context c, LinearLayout parent, String title, boolean value) {
        LinearLayout row = Ui.row(c);
        row.setMinimumHeight(Ui.dp(c, 58));
        row.addView(Ui.text(c, title, 16, Ui.INK), new LinearLayout.LayoutParams(0, -2, 1));
        Switch control = new Switch(c);
        control.setContentDescription(title);
        control.setMinHeight(Ui.dp(c, 48));
        control.setChecked(value);
        control.setThumbTintList(ColorStateList.valueOf(Ui.SURFACE));
        control.setTrackTintList(
                new ColorStateList(
                        new int[][] {new int[] {android.R.attr.state_checked}, new int[] {}},
                        new int[] {Ui.ACCENT, Ui.LINE}));
        row.addView(control);
        parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return control;
    }

    static View empty(
            Context c,
            String icon,
            String title,
            String description,
            String label,
            Runnable action) {
        LinearLayout panel = Ui.column(c);
        panel.setGravity(Gravity.CENTER);
        panel.setPadding(Ui.dp(c, 26), Ui.dp(c, 36), Ui.dp(c, 26), Ui.dp(c, 36));
        panel.addView(
                new Ui.Icon(c, icon, Ui.ACCENT, true),
                new LinearLayout.LayoutParams(Ui.dp(c, 52), Ui.dp(c, 52)));
        TextView heading = Ui.text(c, title, 18, Ui.INK);
        heading.setGravity(Gravity.CENTER);
        heading.setTypeface(null, Typeface.BOLD);
        heading.setPadding(0, Ui.dp(c, 20), 0, Ui.dp(c, 4));
        panel.addView(heading, new LinearLayout.LayoutParams(-1, -2));
        TextView note = note(c, panel, description);
        note.setGravity(Gravity.CENTER);
        if (label != null && action != null) {
            TextView button = Ui.text(c, label, 15, Ui.SURFACE);
            button.setGravity(Gravity.CENTER);
            button.setPadding(Ui.dp(c, 22), Ui.dp(c, 12), Ui.dp(c, 22), Ui.dp(c, 12));
            button.setMinHeight(Ui.dp(c, 48));
            button.setBackground(Ui.button(c, true));
            button.setFocusable(true);
            button.setOnClickListener(v -> action.run());
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
            p.topMargin = Ui.dp(c, 12);
            panel.addView(button, p);
        }
        return panel;
    }
}
