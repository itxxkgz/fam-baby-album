package cn.xiaojia.album;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.StateListDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

/** Shared native form controls for account and administrator pages. */
abstract class SettingsScreen {
    interface Work {
        JSONObject run() throws Exception;
    }

    interface Done {
        void accept(JSONObject result) throws Exception;
    }

    interface Failure {
        void accept(String message);
    }

    interface Host {
        Activity activity();

        ApiClient client();

        JSONObject identity();

        JSONObject family();

        void runSettingTask(Work task, Done success, Failure failure);

        void openSettingPage(String key, View page, Runnable back);

        void acceptSession(String address, JSONObject session, boolean album) throws Exception;

        void accountHome();

        void signInPage();

        void signOut();
    }

    final Host host;
    final Activity activity;
    private LinearLayout pageRoot;
    private LinearLayout pageBar;
    int revision;

    SettingsScreen(Host host) {
        this.host = host;
        activity = host.activity();
    }

    int dp(float value) {
        return Ui.dp(activity, value);
    }

    TextView text(String value, int size, int color) {
        return Ui.text(activity, value, size, color);
    }

    JSONObject json(String key, Object value) {
        JSONObject object = new JSONObject();
        try {
            object.put(key, value);
        } catch (Exception ignored) {
        }
        return object;
    }

    LinearLayout page(String key, String title, Runnable back) {
        revision++;
        LinearLayout root = Ui.column(activity);
        pageRoot = root;
        root.setBackgroundColor(Ui.BG);
        root.setFocusableInTouchMode(true);
        LinearLayout bar = Ui.row(activity);
        pageBar = bar;
        bar.setBackgroundColor(Ui.BG);
        bar.setPadding(dp(6), 0, dp(18), 0);
        if (back != null) {
            FrameLayout button = new FrameLayout(activity);
            button.addView(
                    new Ui.Icon(activity, "back", Ui.INK),
                    new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
            button.setContentDescription("返回");
            button.setBackground(Ui.ripple(Ui.BG, 22, activity));
            button.setOnClickListener(v -> back.run());
            bar.addView(button, new LinearLayout.LayoutParams(dp(48), dp(56)));
        }
        TextView heading = text(title, 20, Ui.INK);
        heading.setTypeface(null, Typeface.BOLD);
        if (back == null) {
            Ui.Icon brand = new Ui.Icon(activity, "brand", Ui.ACCENT);
            LinearLayout.LayoutParams brandParams = new LinearLayout.LayoutParams(dp(32), dp(32));
            brandParams.leftMargin = dp(14);
            brandParams.rightMargin = dp(9);
            bar.addView(brand, brandParams);
        }
        heading.setMinHeight(dp(56));
        heading.setPadding(0, dp(8), 0, dp(8));
        bar.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(bar);
        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout panel = Ui.column(activity);
        panel.setPadding(dp(20), dp(12), dp(20), dp(28));
        scroll.addView(panel);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        host.openSettingPage(key, root, back);
        return panel;
    }

    void enableListRefresh(LinearLayout panel, PullRefreshLayout.Action action) {
        ScrollView scroll = (ScrollView) panel.getParent();
        LinearLayout root = (LinearLayout) scroll.getParent();
        int index = root.indexOfChild(scroll);
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) scroll.getLayoutParams();
        root.removeView(scroll);
        root.addView(new PullRefreshLayout(activity, scroll, scroll, action), index, params);
    }

    EditText field(LinearLayout panel, String label, String hint, String value, boolean secret) {
        TextView name = text(label, 14, Ui.INK);
        name.setPadding(0, dp(12), 0, dp(8));
        panel.addView(name);
        EditText input = new EditText(activity);
        input.setId(View.generateViewId());
        input.setContentDescription(label);
        name.setLabelFor(input.getId());
        input.setTextSize(16);
        input.setTextColor(Ui.INK);
        input.setHintTextColor(Ui.MUTED);
        input.setSingleLine(true);
        input.setInputType(
                InputType.TYPE_CLASS_TEXT
                        | (secret
                                ? InputType.TYPE_TEXT_VARIATION_PASSWORD
                                : InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS));
        input.setText(value == null ? "" : value);
        input.setHint(hint);
        input.setPadding(dp(14), dp(13), dp(14), dp(13));
        input.setMinimumHeight(dp(54));
        StateListDrawable backgrounds = new StateListDrawable();
        backgrounds.addState(
                new int[] {-android.R.attr.state_enabled}, Ui.border(Ui.BG, 12, Ui.LINE, activity));
        backgrounds.addState(
                new int[] {android.R.attr.state_focused},
                Ui.border(Ui.SURFACE, 12, Ui.ACCENT, activity));
        backgrounds.addState(new int[] {}, Ui.border(Ui.BG, 12, Ui.LINE, activity));
        input.setBackground(backgrounds);
        input.setTextColor(
                new ColorStateList(
                        new int[][] {new int[] {-android.R.attr.state_enabled}, new int[] {}},
                        new int[] {Ui.MUTED, Ui.INK}));
        if (secret) {
            LinearLayout line = Ui.row(activity);
            line.addView(input, new LinearLayout.LayoutParams(0, -2, 1));
            TextView reveal = text("显示", 13, Ui.MUTED);
            reveal.setGravity(Gravity.CENTER);
            reveal.setContentDescription("显示" + label);
            reveal.setBackground(Ui.ripple(Ui.BG, 12, activity));
            reveal.setFocusable(true);
            reveal.setOnClickListener(
                    v -> {
                        if (!input.isEnabled()) return;
                        int cursor = input.getSelectionStart();
                        boolean showing = input.getTransformationMethod() == null;
                        input.setTransformationMethod(
                                showing
                                        ? android.text.method.PasswordTransformationMethod
                                                .getInstance()
                                        : null);
                        input.setSelection(Math.max(0, Math.min(cursor, input.length())));
                        reveal.setText(showing ? "显示" : "隐藏");
                        reveal.setContentDescription((showing ? "显示" : "隐藏") + label);
                    });
            line.addView(reveal, new LinearLayout.LayoutParams(dp(48), dp(48)));
            panel.addView(line, new LinearLayout.LayoutParams(-1, -2));
        } else panel.addView(input, new LinearLayout.LayoutParams(-1, -2));
        return input;
    }

    TextView note(LinearLayout panel, String value) {
        TextView label = text(value, 13, Ui.MUTED);
        label.setPadding(0, dp(10), 0, dp(8));
        label.setLineSpacing(dp(3), 1);
        panel.addView(label, new LinearLayout.LayoutParams(-1, -2));
        return label;
    }

    TextView state(LinearLayout panel) {
        TextView label = note(panel, "");
        label.setTextColor(Ui.ERROR);
        label.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        label.setVisibility(View.GONE);
        label.addTextChangedListener(
                new TextWatcher() {
                    public void beforeTextChanged(
                            CharSequence s, int start, int count, int after) {}

                    public void onTextChanged(CharSequence s, int start, int before, int count) {
                        label.setVisibility(s.length() == 0 ? View.GONE : View.VISIBLE);
                    }

                    public void afterTextChanged(Editable s) {}
                });
        return label;
    }

    TextView button(LinearLayout panel, String title, boolean primary, Runnable click) {
        TextView button = text(title, 16, primary ? Color.WHITE : Ui.ACCENT);
        button.setGravity(Gravity.CENTER);
        button.setTypeface(null, Typeface.BOLD);
        button.setPadding(dp(16), dp(14), dp(16), dp(14));
        button.setMinHeight(dp(52));
        button.setBackground(Ui.button(activity, primary));
        button.setTextColor(
                new ColorStateList(
                        new int[][] {new int[] {-android.R.attr.state_enabled}, new int[] {}},
                        new int[] {Ui.MUTED, primary ? Color.WHITE : Ui.ACCENT_DARK}));
        button.setFocusable(true);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(12);
        panel.addView(button, params);
        if (click != null) button.setOnClickListener(v -> click.run());
        return button;
    }

    TextView footerButton(String title, Runnable click) {
        LinearLayout footer = Ui.column(activity);
        footer.setBackgroundColor(Ui.BG);
        footer.setPadding(dp(20), dp(6), dp(20), dp(16));
        TextView action = button(footer, title, true, click);
        ((LinearLayout.LayoutParams) action.getLayoutParams()).topMargin = 0;
        pageRoot.addView(footer, new LinearLayout.LayoutParams(-1, -2));
        return action;
    }

    TextView toolbarAction(String title, Runnable click) {
        TextView action = text(title, 16, Ui.ACCENT_DARK);
        action.setGravity(Gravity.CENTER);
        action.setMinWidth(dp(48));
        action.setMinHeight(dp(48));
        action.setPadding(dp(10), 0, dp(2), 0);
        action.setBackground(Ui.ripple(Ui.BG, 12, activity));
        action.setTextColor(
                new ColorStateList(
                        new int[][] {new int[] {-android.R.attr.state_enabled}, new int[] {}},
                        new int[] {Ui.MUTED, Ui.ACCENT_DARK}));
        action.setFocusable(true);
        if (click != null) action.setOnClickListener(v -> click.run());
        pageBar.addView(action, new LinearLayout.LayoutParams(-2, -1));
        return action;
    }

    TextView textAction(LinearLayout parent, String title, Runnable click) {
        TextView action = text(title, 15, Ui.ACCENT_DARK);
        action.setGravity(Gravity.CENTER);
        action.setMinHeight(dp(48));
        action.setPadding(dp(12), dp(10), dp(12), dp(10));
        action.setBackground(Ui.ripple(Ui.BG, 12, activity));
        action.setFocusable(true);
        if (click != null) action.setOnClickListener(v -> click.run());
        parent.addView(action, new LinearLayout.LayoutParams(-1, -2));
        return action;
    }

    LinearLayout group(LinearLayout parent) {
        LinearLayout group = Ui.column(activity);
        group.setPadding(dp(12), dp(4), dp(12), dp(4));
        group.setBackground(Ui.round(Ui.SURFACE, 16, activity));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(12);
        parent.addView(group, params);
        return group;
    }

    View valueRow(String icon, String title, String value, Runnable click) {
        LinearLayout row = Ui.row(activity);
        row.setMinimumHeight(dp(58));
        row.setPadding(dp(4), dp(12), dp(4), dp(12));
        if (icon != null) {
            LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(25), dp(25));
            iconParams.rightMargin = dp(12);
            row.addView(new Ui.Icon(activity, icon, Ui.ACCENT), iconParams);
        }
        TextView label = text(title, 16, Ui.INK);
        row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        TextView detail = text(value == null ? "" : value, 14, Ui.MUTED);
        detail.setMaxWidth(
                Math.max(
                        dp(72),
                        (activity.getResources().getDisplayMetrics().widthPixels - dp(76))
                                * 44
                                / 100));
        detail.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        detail.setPadding(dp(8), 0, dp(8), 0);
        row.addView(detail, new LinearLayout.LayoutParams(-2, -2));
        if (click != null) {
            row.addView(
                    new Ui.Icon(activity, "next", Ui.MUTED),
                    new LinearLayout.LayoutParams(dp(16), dp(16)));
            row.setBackground(Ui.ripple(Ui.SURFACE, 12, activity));
            row.setFocusable(true);
            row.setOnClickListener(v -> click.run());
        }
        return row;
    }

    View row(String title, String subtitle, Runnable click) {
        return iconRow(null, title, subtitle, click);
    }

    View iconRow(String icon, String title, String subtitle, Runnable click) {
        LinearLayout row = Ui.row(activity);
        row.setPadding(dp(4), dp(16), dp(4), dp(16));
        row.setMinimumHeight(dp(64));
        if (icon != null) {
            Ui.Icon image = new Ui.Icon(activity, icon, Ui.ACCENT);
            LinearLayout.LayoutParams imageParams = new LinearLayout.LayoutParams(dp(24), dp(24));
            imageParams.rightMargin = dp(14);
            row.addView(image, imageParams);
        }
        LinearLayout labels = Ui.column(activity);
        TextView heading = text(title, 16, Ui.INK);
        heading.setTypeface(null, Typeface.BOLD);
        labels.addView(heading);
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView sub = text(subtitle, 13, Ui.MUTED);
            sub.setPadding(0, dp(6), dp(10), 0);
            labels.addView(sub);
        }
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        if (click != null) {
            row.addView(
                    new Ui.Icon(activity, "next", Ui.MUTED),
                    new LinearLayout.LayoutParams(dp(16), dp(16)));
            row.setOnClickListener(v -> click.run());
            row.setBackground(Ui.ripple(Ui.SURFACE, 12, activity));
        }
        return row;
    }

    LinearLayout card(LinearLayout parent) {
        LinearLayout card = Ui.column(activity);
        card.setPadding(dp(16), dp(6), dp(16), dp(12));
        card.setBackground(Ui.round(Ui.SURFACE, 16, activity));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(12);
        parent.addView(card, params);
        return card;
    }

    void section(LinearLayout parent, String title) {
        TextView label = text(title, 14, Ui.MUTED);
        label.setPadding(dp(2), dp(22), 0, dp(6));
        parent.addView(label, new LinearLayout.LayoutParams(-1, -2));
    }

    void hero(LinearLayout parent, String icon, String title, String subtitle) {
        LinearLayout row = Ui.row(activity);
        row.setPadding(0, dp(8), 0, dp(18));
        FrameLayout mark = new FrameLayout(activity);
        mark.setBackground(Ui.round(Ui.SOFT, 18, activity));
        mark.addView(
                new Ui.Icon(activity, icon, Ui.ACCENT),
                new FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER));
        LinearLayout.LayoutParams markParams = new LinearLayout.LayoutParams(dp(58), dp(58));
        markParams.rightMargin = dp(14);
        row.addView(mark, markParams);
        LinearLayout labels = Ui.column(activity);
        TextView heading = text(title, 22, Ui.INK);
        heading.setTypeface(null, Typeface.BOLD);
        labels.addView(heading);
        TextView detail = text(subtitle, 13, Ui.MUTED);
        detail.setLineSpacing(dp(3), 1);
        detail.setPadding(0, dp(6), 0, 0);
        labels.addView(detail);
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        parent.addView(row);
    }

    TextView badge(String label, boolean highlighted) {
        TextView badge = text(label, 12, highlighted ? Ui.ACCENT_DARK : Ui.MUTED);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(dp(9), dp(4), dp(9), dp(4));
        badge.setBackground(Ui.round(highlighted ? Ui.SOFT : Ui.BG, 9, activity));
        return badge;
    }

    View avatar(String name, int size) {
        View avatar = new FamilyAvatar(activity, name);
        avatar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        avatar.setLayoutParams(new LinearLayout.LayoutParams(dp(size), dp(size)));
        return avatar;
    }

    void divider(LinearLayout panel) {
        View line = new View(activity);
        line.setBackgroundColor(Ui.LINE);
        panel.addView(line, new LinearLayout.LayoutParams(-1, dp(.5f)));
    }

    String value(EditText field) {
        return field.getText().toString().trim();
    }

    boolean validUsername(String value) {
        return value.matches("[A-Za-z0-9][A-Za-z0-9._-]{2,31}");
    }

    boolean validPassword(String value) {
        return value.length() >= 8 && value.length() <= 200;
    }

    boolean allowed(String permission) {
        JSONObject permissions = host.identity().optJSONObject("permissions");
        return permissions != null && permissions.optBoolean(permission, false);
    }
}
