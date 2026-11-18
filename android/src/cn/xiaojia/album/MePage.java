package cn.xiaojia.album;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.function.Consumer;

/** The family's personal space. Administrative data never appears in the presentation. */
final class MePage {
    private final SettingsScreen.Host host;
    private final Activity activity;
    private final Consumer<String> action;
    private ScrollView scroll;
    private TextView storageSize, memberLabel, familyLabel, roleLabel;
    private final PullRefreshLayout.Action refreshProfile;
    private int position, revision;

    MePage(
            SettingsScreen.Host host,
            Consumer<String> action,
            PullRefreshLayout.Action refreshProfile) {
        this.refreshProfile = refreshProfile;
        this.host = host;
        this.activity = host.activity();
        this.action = action;
    }

    private int dp(float value) {
        return Ui.dp(activity, value);
    }

    private TextView text(String value, int size, int color) {
        return Ui.text(activity, value, size, color);
    }

    private boolean allowed(String key) {
        JSONObject permissions = host.identity().optJSONObject("permissions");
        return permissions != null && permissions.optBoolean(key);
    }

    void remember() {
        if (scroll != null) position = scroll.getScrollY();
    }

    void dispose() {
        revision++;
        scroll = null;
        storageSize = null;
        position = 0;
    }

    void refreshFamily() {
        if (memberLabel != null) memberLabel.setText(host.identity().optString("name", "家人"));
        if (roleLabel != null) roleLabel.setText(host.identity().optString("role_label", "家人"));
        if (familyLabel != null)
            familyLabel.setText(host.family().optString("child_name", "宝宝") + "的家庭相册");
        if (storageSize != null)
            storageSize.setText("相册共占用 " + Ui.size(host.family().optLong("storage_bytes")));
    }

    View createView() {
        remember();
        int request = ++revision;
        scroll = new ScrollView(activity);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFillViewport(true);
        LinearLayout panel = Ui.column(activity);
        panel.setPadding(dp(18), 0, dp(18), dp(16));
        scroll.addView(panel, new ScrollView.LayoutParams(-1, -2));

        LinearLayout bar = Ui.row(activity);
        TextView title = text("我的", 24, Ui.INK);
        title.setTypeface(null, Typeface.BOLD);
        bar.addView(title, new LinearLayout.LayoutParams(0, dp(56), 1));
        bar.addView(
                iconAction("settings", "账号与安全", "account", 44),
                new LinearLayout.LayoutParams(dp(48), dp(48)));
        panel.addView(bar);

        LinearLayout profile = Ui.row(activity);
        profile.setPadding(dp(4), dp(8), 0, dp(14));
        profile.setBackground(
                new GradientDrawable(
                        GradientDrawable.Orientation.TL_BR, new int[] {Ui.BG, 0x7FFBE7DA, Ui.BG}));
        String name = host.identity().optString("name", "家人");
        profile.addView(
                new FamilyAvatar(activity, name), new LinearLayout.LayoutParams(dp(58), dp(58)));
        LinearLayout identity = Ui.column(activity);
        identity.setPadding(dp(12), 0, dp(2), 0);
        TextView heading = text(name, 21, Ui.INK);
        memberLabel = heading;
        heading.setTypeface(null, Typeface.BOLD);
        heading.setMaxLines(2);
        heading.setEllipsize(TextUtils.TruncateAt.END);
        identity.addView(heading);
        TextView family = text(host.family().optString("child_name", "宝宝") + "的家庭相册", 12, Ui.MUTED);
        familyLabel = family;
        family.setMaxLines(2);
        family.setEllipsize(TextUtils.TruncateAt.END);
        family.setPadding(0, dp(4), 0, dp(5));
        identity.addView(family);
        TextView role = text(host.identity().optString("role_label", "家人"), 10, Ui.ACCENT_DARK);
        roleLabel = role;
        role.setPadding(dp(7), dp(3), dp(7), dp(3));
        role.setBackground(Ui.round(Ui.SOFT, 9, activity));
        identity.addView(role, new LinearLayout.LayoutParams(-2, -2));
        profile.addView(identity, new LinearLayout.LayoutParams(0, -2, 1));
        int screenWidth =
                Math.round(
                        activity.getResources().getDisplayMetrics().widthPixels
                                / activity.getResources().getDisplayMetrics().density);
        profile.addView(
                new WarmIllustration(activity, "home"),
                new LinearLayout.LayoutParams(dp(screenWidth < 360 ? 70 : 94), dp(90)));
        profile.setContentDescription(name + "，" + host.identity().optString("role_label", "家人"));
        panel.addView(profile, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout familyCard = Ui.column(activity);
        familyCard.setPadding(dp(12), dp(6), dp(10), dp(12));
        familyCard.setBackground(Ui.round(Ui.SURFACE, 17, activity));
        familyCard.setElevation(dp(1));
        LinearLayout familyHeading = Ui.row(activity);
        TextView familyTitle = text("我们一家", 18, Ui.INK);
        familyTitle.setTypeface(null, Typeface.BOLD);
        familyHeading.addView(familyTitle, new LinearLayout.LayoutParams(0, dp(44), 1));
        TextView familyCount = text("查看家人  ›", 12, Ui.MUTED);
        familyCount.setPadding(dp(8), 0, dp(4), 0);
        familyCount.setMinHeight(dp(44));
        familyCount.setContentDescription(allowed("can_manage_members") ? "家人管理" : "相册家人");
        familyCount.setOnClickListener(v -> action.accept("family"));
        familyHeading.addView(familyCount);
        familyCard.addView(familyHeading);
        LinearLayout familyRow = Ui.row(activity);
        LinearLayout faces = Ui.row(activity);
        familyRow.addView(faces, new LinearLayout.LayoutParams(0, -2, 1));
        JSONArray initial = new JSONArray();
        initial.put(host.identity());
        bindFaces(faces, initial, screenWidth);
        if (allowed("can_manage_members") || allowed("can_invite_child")) {
            View divider = new View(activity);
            divider.setBackgroundColor(Ui.LINE);
            LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(dp(1), dp(45));
            dividerParams.setMargins(dp(6), 0, dp(8), 0);
            familyRow.addView(divider, dividerParams);
            familyRow.addView(
                    shortcut("invite", "添加家人", "invite", 34),
                    new LinearLayout.LayoutParams(dp(62), -2));
        }
        familyCard.addView(familyRow);
        panel.addView(familyCard);

        LinearLayout shortcuts = Ui.row(activity);
        shortcuts.setGravity(Gravity.TOP);
        shortcuts.setPadding(0, dp(16), 0, dp(16));
        shortcuts.addView(
                shortcut("baby", "宝宝相册", "baby", 53), new LinearLayout.LayoutParams(0, -2, 1));
        shortcuts.addView(
                shortcut("collection", "我的收藏", "favorites", 53),
                new LinearLayout.LayoutParams(0, -2, 1));
        if (allowed("can_view_trash"))
            shortcuts.addView(
                    shortcut("recover", "回收站", "trash", 53),
                    new LinearLayout.LayoutParams(0, -2, 1));
        panel.addView(shortcuts);

        LinearLayout storage = Ui.row(activity);
        storage.setPadding(dp(8), dp(12), dp(12), dp(12));
        storage.setBackground(Ui.round(0xFFFFEFE1, 17, activity));
        storage.addView(
                new WarmIllustration(activity, "storage"),
                new LinearLayout.LayoutParams(dp(screenWidth < 360 ? 68 : 94), dp(88)));
        LinearLayout storageWords = Ui.column(activity);
        storageWords.setPadding(dp(10), 0, dp(4), 0);
        TextView storageTitle = text("照片存放", 18, Ui.INK);
        storageTitle.setTypeface(null, Typeface.BOLD);
        storageWords.addView(storageTitle);
        TextView storageName =
                text(allowed("can_manage_storage") ? "正在读取存放位置…" : "由家庭管理员设置", 12, Ui.MUTED);
        storageName.setPadding(0, dp(6), 0, dp(4));
        storageName.setMaxLines(2);
        storageName.setEllipsize(TextUtils.TruncateAt.END);
        storageWords.addView(storageName);
        TextView size =
                text("相册共占用 " + Ui.size(host.family().optLong("storage_bytes")), 12, Ui.INK);
        storageSize = size;
        storageWords.addView(size);
        storage.addView(storageWords, new LinearLayout.LayoutParams(0, -2, 1));
        TextView manage = text(allowed("can_manage_storage") ? "管理  ›" : "查看  ›", 12, Ui.INK);
        manage.setGravity(Gravity.CENTER);
        storage.addView(manage, new LinearLayout.LayoutParams(dp(48), dp(48)));
        storage.setContentDescription("照片存放");
        storage.setOnClickListener(v -> action.accept("storage"));
        panel.addView(storage, new LinearLayout.LayoutParams(-1, -2));
        View gap = new View(activity);
        panel.addView(gap, new LinearLayout.LayoutParams(-1, dp(12)));
        panel.addView(menu("security", "账号与安全", "account"));
        separator(panel);
        panel.addView(menu("server", "服务器连接", "connection"));
        separator(panel);
        panel.addView(menu("brand", "关于FAM宝贝相册", "about"));

        loadCards(request, faces, familyCount, screenWidth, storageName, null);
        ScrollView current = scroll;
        current.post(
                () -> {
                    if (scroll == current) current.scrollTo(0, position);
                });
        return new PullRefreshLayout(
                activity,
                current,
                current,
                done -> {
                    Consumer<Boolean> part =
                            PullRefreshLayout.together(allowed("can_manage_storage") ? 3 : 2, done);
                    refreshProfile.run(part);
                    loadCards(++revision, faces, familyCount, screenWidth, storageName, part);
                });
    }

    private void loadCards(
            int request,
            LinearLayout faces,
            TextView familyCount,
            int screenWidth,
            TextView storageName,
            Consumer<Boolean> part) {
        final ApiClient source = host.client().snapshot();
        final ScrollView familyPage = scroll;
        final boolean manageFamily = allowed("can_manage_members") || allowed("can_invite_child");
        host.runSettingTask(
                () ->
                        source.request(
                                "GET",
                                manageFamily && !source.childId.isEmpty()
                                        ? "/api/children/"
                                                + ApiClient.encode(source.childId)
                                                + "/members"
                                        : "/api/members",
                                null),
                result -> {
                    if (!currentFamily(request, familyPage, source)) return;
                    JSONArray members = registered(result.optJSONArray("items"));
                    bindFaces(faces, members, screenWidth);
                    familyCount.setText(members.length() + "位家人  ›");
                    if (part != null) part.accept(true);
                },
                message -> {
                    if (!currentFamily(request, familyPage, source)) return;
                    if (part != null) {
                        part.accept(false);
                        return;
                    }
                    faces.removeAllViews();
                    TextView retry = text("家人暂未载入，点此查看", 12, Ui.MUTED);
                    retry.setMinHeight(dp(62));
                    retry.setOnClickListener(v -> action.accept("family"));
                    faces.addView(retry);
                });
        if (allowed("can_manage_storage"))
            host.runSettingTask(
                    () -> source.request("GET", "/api/storage", null),
                    result -> {
                        if (request != revision) return;
                        JSONArray items = result.optJSONArray("items");
                        String label = "尚未设置上传位置";
                        if (items != null)
                            for (int i = 0; i < items.length(); i++) {
                                JSONObject item = items.getJSONObject(i);
                                if (!item.optBoolean("is_default")) continue;
                                label = item.optString("name", "自己的服务器");
                                if (item.optString("id").equals("local-default")
                                        && label.equals("本机相册")) label = "自己的服务器";
                                label = "新照片存到：" + label;
                                break;
                            }
                        storageName.setText(label);
                        if (part != null) part.accept(true);
                    },
                    message -> {
                        if (request != revision) return;
                        if (part != null) part.accept(false);
                        else storageName.setText("点此查看存放位置");
                    });
    }

    private boolean currentFamily(int request, ScrollView page, ApiClient source) {
        ApiClient current = host.client();
        return request == revision
                && scroll == page
                && page.isAttachedToWindow()
                && source.baseUrl.equals(current.baseUrl)
                && source.token.equals(current.token)
                && source.childId.equals(current.childId);
    }

    private JSONArray registered(JSONArray values) {
        JSONArray result = new JSONArray();
        if (values != null)
            for (int i = 0; i < values.length(); i++) {
                JSONObject member = values.optJSONObject(i);
                if (member != null && member.optBoolean("registered")) result.put(member);
            }
        return result;
    }

    private void bindFaces(LinearLayout row, JSONArray members, int width) {
        members = registered(members);
        row.removeAllViews();
        ArrayList<JSONObject> ordered = new ArrayList<>();
        String me = host.identity().optString("id");
        for (int i = 0; i < members.length(); i++)
            if (members.optJSONObject(i) != null
                    && members.optJSONObject(i).optString("id").equals(me))
                ordered.add(members.optJSONObject(i));
        for (int i = 0; i < members.length(); i++)
            if (members.optJSONObject(i) != null
                    && !members.optJSONObject(i).optString("id").equals(me))
                ordered.add(members.optJSONObject(i));
        int slots = width < 360 && allowed("can_manage_members") ? 3 : 5;
        int individuals = ordered.size() > slots ? slots - 1 : ordered.size();
        for (int i = 0; i < individuals; i++) {
            String name = ordered.get(i).optString("relationship").trim();
            if (name.isEmpty()) name = ordered.get(i).optString("name", "家人");
            LinearLayout face = Ui.column(activity);
            face.setGravity(Gravity.CENTER);
            face.addView(
                    new FamilyAvatar(activity, name),
                    new LinearLayout.LayoutParams(dp(38), dp(38)));
            TextView caption = text(shortName(name), 11, Ui.INK);
            caption.setGravity(Gravity.CENTER);
            caption.setSingleLine(true);
            caption.setEllipsize(TextUtils.TruncateAt.END);
            caption.setPadding(0, dp(5), 0, 0);
            face.addView(caption, new LinearLayout.LayoutParams(-1, dp(24)));
            face.setContentDescription(name);
            face.setOnClickListener(v -> action.accept("family"));
            row.addView(face, new LinearLayout.LayoutParams(0, -2, 1));
        }
        if (ordered.size() > individuals) {
            LinearLayout extra = Ui.column(activity);
            extra.setGravity(Gravity.CENTER);
            TextView count = text("+" + (ordered.size() - individuals), 14, Ui.ACCENT_DARK);
            count.setGravity(Gravity.CENTER);
            count.setBackground(Ui.round(Ui.SOFT, 22, activity));
            extra.addView(count, new LinearLayout.LayoutParams(dp(38), dp(38)));
            TextView caption = text("更多", 11, Ui.MUTED);
            caption.setGravity(Gravity.CENTER);
            caption.setPadding(0, dp(5), 0, 0);
            extra.addView(caption, new LinearLayout.LayoutParams(-1, dp(24)));
            extra.setContentDescription("查看其他" + (ordered.size() - individuals) + "位家人");
            extra.setOnClickListener(v -> action.accept("family"));
            row.addView(extra, new LinearLayout.LayoutParams(0, -2, 1));
        }
    }

    private String shortName(String name) {
        return name.startsWith("宝宝") && name.length() > 2 ? name.substring(2) : name;
    }

    private View shortcut(String icon, String label, String key, int iconSize) {
        LinearLayout view = Ui.column(activity);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(3), dp(6), dp(3), dp(6));
        view.setMinimumHeight(dp(76));
        view.setBackground(Ui.ripple(Color.TRANSPARENT, 14, activity));
        view.addView(
                new Ui.Icon(activity, icon, Ui.ACCENT, true),
                new LinearLayout.LayoutParams(dp(iconSize), dp(iconSize)));
        TextView caption = text(label, iconSize < 40 ? 11 : 14, Ui.INK);
        caption.setGravity(Gravity.CENTER);
        caption.setPadding(0, dp(7), 0, 0);
        view.addView(caption, new LinearLayout.LayoutParams(-1, -2));
        view.setContentDescription(label);
        view.setOnClickListener(v -> action.accept(key));
        return view;
    }

    private View iconAction(String icon, String label, String key, int size) {
        FrameLayout frame = new FrameLayout(activity);
        frame.addView(
                new Ui.Icon(activity, icon, Ui.INK),
                new FrameLayout.LayoutParams(dp(23), dp(23), Gravity.CENTER));
        frame.setBackground(Ui.ripple(Ui.BG, 20, activity));
        frame.setContentDescription(label);
        frame.setOnClickListener(v -> action.accept(key));
        return frame;
    }

    private View menu(String icon, String label, String key) {
        LinearLayout row = Ui.row(activity);
        row.setPadding(dp(10), dp(10), dp(10), dp(10));
        row.setMinimumHeight(dp(52));
        int iconSize = icon.equals("brand") ? 32 : 25;
        FrameLayout iconSlot = new FrameLayout(activity);
        iconSlot.addView(
                new Ui.Icon(activity, icon, Ui.ACCENT, true),
                new FrameLayout.LayoutParams(dp(iconSize), dp(iconSize), Gravity.CENTER));
        row.addView(iconSlot, new LinearLayout.LayoutParams(dp(32), dp(32)));
        TextView title = text(label, 15, Ui.INK);
        title.setPadding(dp(13), 0, dp(8), 0);
        row.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(
                new Ui.Icon(activity, "next", Ui.MUTED),
                new LinearLayout.LayoutParams(dp(16), dp(16)));
        row.setBackground(Ui.ripple(Color.TRANSPARENT, 12, activity));
        row.setContentDescription(label);
        row.setOnClickListener(v -> action.accept(key));
        return row;
    }

    private void separator(LinearLayout panel) {
        View line = new View(activity);
        line.setBackgroundColor(Ui.LINE);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(.5f));
        params.leftMargin = dp(48);
        params.rightMargin = dp(12);
        panel.addView(line, params);
    }
}
