package cn.xiaojia.album;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

/** Avatar rows for choosing existing baby albums; original files are never duplicated. */
final class ChildChoices {
    final LinearLayout view;
    private final ArrayList<String> ids = new ArrayList<>();
    private final ArrayList<String> names = new ArrayList<>();
    private final ArrayList<CheckBox> choices = new ArrayList<>();
    private final ArrayList<View> rows = new ArrayList<>();
    private Runnable changed;

    ChildChoices(Activity activity, JSONArray children, String selected, boolean manageOnly) {
        this(activity, children, selected, manageOnly, null);
    }

    ChildChoices(
            Activity activity,
            JSONArray children,
            String selected,
            boolean manageOnly,
            ImageLoader images) {
        view = Ui.column(activity);
        for (int i = 0; i < children.length(); i++) {
            JSONObject child = children.optJSONObject(i);
            if (child == null || child.optBoolean("archived")) continue;
            JSONObject permissions = child.optJSONObject("permissions");
            boolean allowed =
                    permissions != null
                            && permissions.optBoolean(manageOnly ? "can_manage" : "can_upload")
                            && (manageOnly
                                    ? "admin".equals(child.optString("role"))
                                    : BabyScope.canUpload(child.optString("role")));
            if (!allowed) continue;
            if (!choices.isEmpty()) SecondaryUi.divider(activity, view);
            String name = child.optString("name", "宝宝");
            LinearLayout row = Ui.row(activity);
            row.setPadding(0, Ui.dp(activity, 10), 0, Ui.dp(activity, 10));
            row.setMinimumHeight(Ui.dp(activity, 70));
            FrameLayout avatar = new FrameLayout(activity);
            avatar.setBackground(Ui.round(Ui.SOFT, 24, activity));
            avatar.setClipToOutline(true);
            avatar.addView(
                    new Ui.Icon(activity, "baby", Ui.ACCENT, true),
                    new FrameLayout.LayoutParams(-1, -1));
            String url = child.optString("avatar_url", "");
            if (images != null && !url.isEmpty() && !url.equals("null")) {
                ImageView picture = new ImageView(activity);
                picture.setScaleType(ImageView.ScaleType.CENTER_CROP);
                avatar.addView(picture, new FrameLayout.LayoutParams(-1, -1));
                images.load(picture, url, 160);
            }
            avatar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            row.addView(
                    avatar,
                    new LinearLayout.LayoutParams(Ui.dp(activity, 48), Ui.dp(activity, 48)));
            LinearLayout words = Ui.column(activity);
            words.setPadding(Ui.dp(activity, 12), 0, Ui.dp(activity, 6), 0);
            TextView title = Ui.text(activity, name, 16, Ui.INK);
            title.setTypeface(null, Typeface.BOLD);
            words.addView(title);
            String age = child.optString("age", "");
            if (!age.isEmpty()) {
                TextView detail = Ui.text(activity, age, 12, Ui.MUTED);
                detail.setPadding(0, Ui.dp(activity, 4), 0, 0);
                words.addView(detail);
            }
            row.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
            CheckBox check = new CheckBox(activity);
            check.setButtonDrawable(null);
            check.setTextColor(Color.WHITE);
            check.setTextSize(18);
            check.setTypeface(null, Typeface.BOLD);
            check.setPadding(0, 0, 0, 0);
            check.setGravity(Gravity.CENTER);
            check.setIncludeFontPadding(false);
            check.setContentDescription("选择" + name + "的相册");
            StateListDrawable background = new StateListDrawable();
            background.addState(
                    new int[] {android.R.attr.state_checked},
                    new InsetDrawable(Ui.round(Ui.ACCENT, 15, activity), Ui.dp(activity, 9)));
            background.addState(
                    new int[] {},
                    new InsetDrawable(
                            Ui.border(Ui.SURFACE, 15, Ui.LINE, activity), Ui.dp(activity, 9)));
            check.setBackground(background);
            check.setChecked(child.optString("id").equals(selected));
            check.setText(check.isChecked() ? "✓" : "");
            check.setOnCheckedChangeListener(
                    (button, checked) -> {
                        check.setText(checked ? "✓" : "");
                        if (changed != null) changed.run();
                    });
            row.addView(
                    check, new LinearLayout.LayoutParams(Ui.dp(activity, 48), Ui.dp(activity, 48)));
            row.setBackground(Ui.ripple(Ui.BG, 12, activity));
            row.setOnClickListener(
                    v -> {
                        if (check.isEnabled()) check.toggle();
                    });
            ids.add(child.optString("id"));
            names.add(name);
            choices.add(check);
            rows.add(row);
            view.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    void onChanged(Runnable listener) {
        changed = listener;
        if (listener != null) listener.run();
    }

    void setEnabled(boolean enabled) {
        for (CheckBox choice : choices) choice.setEnabled(enabled);
        for (View row : rows) {
            row.setEnabled(enabled);
            row.setAlpha(enabled ? 1f : .65f);
        }
    }

    int count() {
        int count = 0;
        for (CheckBox choice : choices) if (choice.isChecked()) count++;
        return count;
    }

    JSONArray selected() {
        ArrayList<String> picked = new ArrayList<>();
        for (int i = 0; i < choices.size(); i++)
            if (choices.get(i).isChecked()) picked.add(ids.get(i));
        JSONArray result = new JSONArray();
        for (String id : BabyScope.uploadSelection(picked, ids)) result.put(id);
        return result;
    }

    String names() {
        ArrayList<String> result = new ArrayList<>();
        for (int i = 0; i < choices.size(); i++)
            if (choices.get(i).isChecked()) result.add(names.get(i));
        return String.join("、", result);
    }

    boolean isEmpty() {
        return choices.isEmpty();
    }
}
