package cn.xiaojia.album;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;

/** Baby access is changed individually; removing access is distinct from read-only access. */
final class ChildAccessSettings extends SettingsScreen {
    private final JSONObject person;
    private final Runnable back;
    private LinearLayout panel;
    private ApiClient source;
    private WarmSheet activeSheet;
    private boolean familyAdmin;

    ChildAccessSettings(Host host, JSONObject person, Runnable back) {
        super(host);
        this.person = person;
        this.back = back;
    }

    void show() {
        source = host.client().snapshot();
        familyAdmin = host.identity().optBoolean("is_family_admin");
        panel = page("family_child_access", "可访问的宝宝", back);
        final int requested = revision;
        final LinearLayout origin = panel;
        final ApiClient client = source;
        LinearLayout identity = Ui.row(activity);
        identity.setPadding(0, dp(10), 0, dp(20));
        identity.addView(avatar(person.optString("name", "家人"), 52));
        TextView name = text(person.optString("name", "家人"), 20, Ui.INK);
        name.setTypeface(null, Typeface.BOLD);
        name.setPadding(dp(14), 0, 0, 0);
        identity.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        panel.addView(identity);
        divider(panel);
        TextView state = state(panel);
        state.setTextColor(Ui.MUTED);
        state.setText("正在读取宝宝权限…");
        LinearLayout list = group(panel);
        ApiClient imageClient = source.snapshot();
        imageClient.childId = "";
        final ImageLoader images = new ImageLoader(activity, imageClient);
        origin.addOnAttachStateChangeListener(
                new View.OnAttachStateChangeListener() {
                    public void onViewAttachedToWindow(View view) {}

                    public void onViewDetachedFromWindow(View view) {
                        images.close();
                        if (activeSheet != null) activeSheet.dismiss();
                    }
                });
        host.runSettingTask(
                () -> {
                    JSONArray children =
                            client.request("GET", "/api/children", null).optJSONArray("items");
                    JSONArray rows = new JSONArray();
                    if (children != null)
                        for (int i = 0; i < children.length(); i++) {
                            JSONObject child = children.getJSONObject(i);
                            JSONObject permissions = child.optJSONObject("permissions");
                            if (child.optBoolean("archived")
                                    || permissions == null
                                    || !permissions.optBoolean("can_manage")) continue;
                            JSONObject grant = readAccess(client, child.optString("id"));
                            if (!familyAdmin && !grant.optBoolean("present")) continue;
                            grant.put("name", child.optString("name", "宝宝"));
                            grant.put("avatar_url", child.optString("avatar_url", ""));
                            rows.put(grant);
                        }
                    return json("items", rows);
                },
                result -> {
                    if (!current(requested, origin, client)) return;
                    state.setText("");
                    JSONArray rows = result.optJSONArray("items");
                    if (rows == null || rows.length() == 0) {
                        note(list, "目前没有可以调整的宝宝相册。");
                        return;
                    }
                    for (int i = 0; i < rows.length(); i++) {
                        JSONObject grant = rows.getJSONObject(i);
                        if (i > 0) divider(list);
                        list.addView(accessRow(grant, images, requested, origin, client));
                    }
                },
                error -> {
                    if (!current(requested, origin, client)) return;
                    state.setTextColor(Ui.ERROR);
                    state.setText(error);
                    textAction(list, "重新读取", this::show);
                });
    }

    private JSONObject readAccess(ApiClient client, String child) throws Exception {
        JSONObject result =
                client.request(
                        "GET", "/api/children/" + ApiClient.encode(child) + "/members", null);
        JSONArray members = result.optJSONArray("items");
        JSONObject target = null;
        if (members != null)
            for (int i = 0; i < members.length(); i++) {
                JSONObject row = members.getJSONObject(i);
                if (person.optString("id").equals(row.optString("id"))) {
                    target = row;
                    break;
                }
            }
        JSONObject info = result.optJSONObject("child");
        JSONObject permissions = info == null ? null : info.optJSONObject("permissions");
        boolean protectedAccount =
                person.optString("id").equals(host.identity().optString("id"))
                        || target != null && target.optBoolean("is_family_admin");
        boolean permitted =
                permissions != null
                        && permissions.optBoolean("can_manage")
                        && !protectedAccount
                        && (familyAdmin || target != null);
        return json("id", child)
                .put("role", target == null ? "" : target.optString("role", ""))
                .put("present", target != null)
                .put("can_change", permitted)
                .put("is_family_admin", target != null && target.optBoolean("is_family_admin"));
    }

    private View accessRow(
            JSONObject grant, ImageLoader images, int requested, View origin, ApiClient client) {
        LinearLayout row = Ui.row(activity);
        row.setMinimumHeight(dp(80));
        row.setPadding(0, dp(13), 0, dp(13));
        FrameLayout face = new FrameLayout(activity);
        face.setBackground(Ui.round(Ui.SOFT, 26, activity));
        face.setClipToOutline(true);
        face.addView(
                new Ui.Icon(activity, "baby", Ui.ACCENT, true),
                new FrameLayout.LayoutParams(-1, -1));
        String url = grant.optString("avatar_url", "");
        if (!url.isEmpty() && !url.equals("null")) {
            ImageView photo = new ImageView(activity);
            photo.setScaleType(ImageView.ScaleType.CENTER_CROP);
            face.addView(photo, new FrameLayout.LayoutParams(-1, -1));
            images.load(photo, url, 160);
        }
        row.addView(face, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout labels = Ui.column(activity);
        labels.setPadding(dp(14), 0, dp(8), 0);
        TextView title = text(grant.optString("name", "宝宝"), 17, Ui.INK);
        title.setTypeface(null, Typeface.BOLD);
        labels.addView(title);
        TextView role =
                text(
                        grant.optBoolean("is_family_admin")
                                ? "家庭管理员"
                                : label(grant.optString("role")),
                        13,
                        Ui.MUTED);
        role.setPadding(0, dp(5), 0, 0);
        labels.addView(role);
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(
                new Ui.Icon(activity, "next", Ui.MUTED),
                new LinearLayout.LayoutParams(dp(17), dp(17)));
        row.setBackground(Ui.ripple(Ui.BG, 12, activity));
        row.setOnClickListener(
                v -> {
                    if (current(requested, origin, client))
                        new AccessEditor(grant, role, requested, origin, client).open();
                });
        return row;
    }

    private boolean current(int requested, View origin, ApiClient client) {
        ApiClient now = host.client();
        return requested == revision
                && origin.isAttachedToWindow()
                && !activity.isFinishing()
                && !activity.isDestroyed()
                && client.baseUrl.equals(now.baseUrl)
                && client.token.equals(now.token)
                && client.childId.equals(now.childId);
    }

    private String label(String role) {
        if ("admin".equals(role)) return "宝宝管理员";
        if ("contributor".equals(role)) return "可上传";
        if ("viewer".equals(role)) return "仅查看";
        return "未加入";
    }

    private final class AccessEditor {
        final JSONObject grant;
        final TextView rowStatus;
        final int requested;
        final View origin;
        final ApiClient client;
        final ChildAccessState access;
        final WarmSheet sheet;
        final Switch[] toggles = new Switch[3];
        TextView status;
        boolean binding;

        AccessEditor(
                JSONObject grant,
                TextView rowStatus,
                int requested,
                View origin,
                ApiClient client) {
            this.grant = grant;
            this.rowStatus = rowStatus;
            this.requested = requested;
            this.origin = origin;
            this.client = client;
            access = new ChildAccessState(grant.optString("role"), grant.optBoolean("can_change"));
            sheet = new WarmSheet(activity, grant.optString("name", "宝宝") + "的相册");
        }

        void open() {
            if (activeSheet != null && activeSheet.isShowing()) activeSheet.dismiss();
            activeSheet = sheet;
            toggles[0] = permission("photo", "允许查看", ChildAccessState.VIEW);
            divider(sheet.body);
            toggles[1] = permission("people", "管理", ChildAccessState.MANAGEMENT);
            toggles[2] = permission("upload", "上传", ChildAccessState.UPLOAD);
            if (!grant.optBoolean("can_change"))
                note(
                        sheet.body,
                        grant.optBoolean("is_family_admin") ? "家庭管理员可以管理全部宝宝。" : "不能在这里调整自己的访问权限。");
            else note(sheet.body, "管理包含上传；关闭查看会移出这个宝宝的相册。");
            status = state(sheet.body);
            sheet.onDismiss(
                    () -> {
                        if (activeSheet == sheet) activeSheet = null;
                    });
            bind();
            sheet.show();
        }

        Switch permission(String icon, String title, int control) {
            LinearLayout row = Ui.row(activity);
            row.setPadding(0, dp(10), 0, dp(10));
            row.addView(
                    new Ui.Icon(activity, icon, Ui.ACCENT),
                    new LinearLayout.LayoutParams(dp(24), dp(24)));
            TextView label = text(title, 17, Ui.INK);
            label.setPadding(dp(12), 0, dp(8), 0);
            row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
            Switch toggle = new Switch(activity);
            toggle.setMinHeight(dp(48));
            toggle.setSwitchMinWidth(dp(52));
            toggle.setShowText(false);
            toggle.setContentDescription(title);
            toggle.setThumbTintList(ColorStateList.valueOf(Color.WHITE));
            toggle.setTrackTintList(
                    new ColorStateList(
                            new int[][] {new int[] {android.R.attr.state_checked}, new int[] {}},
                            new int[] {Ui.ACCENT, 0xFFD7D0CC}));
            row.addView(toggle, new LinearLayout.LayoutParams(-2, dp(48)));
            sheet.body.addView(row);
            toggle.setOnCheckedChangeListener(
                    (button, checked) -> {
                        if (!binding) request(control, checked);
                    });
            return toggle;
        }

        void bind() {
            binding = true;
            for (int i = 0; i < toggles.length; i++) {
                toggles[i].setChecked(access.checked(i));
                toggles[i].setEnabled(access.enabled(i));
                toggles[i].setAlpha(access.enabled(i) ? 1f : .45f);
            }
            sheet.setCancelable(!access.busy());
            binding = false;
        }

        void request(int control, boolean checked) {
            if (!current(requested, origin, client)
                    || !sheet.isShowing()
                    || !access.enabled(control)) {
                bind();
                return;
            }
            if (control == ChildAccessState.VIEW && !checked) {
                bind();
                WarmSheet confirmation = new WarmSheet(activity, "移出这个宝宝的相册？");
                note(confirmation.body, "这位家人将不能查看这个宝宝，原照片和留言仍会保留。");
                confirmation.primary(
                        "移出相册",
                        () -> {
                            confirmation.dismiss();
                            change(control, checked);
                        });
                confirmation.secondary("取消", confirmation::dismiss);
                View.OnAttachStateChangeListener leaving =
                        new View.OnAttachStateChangeListener() {
                            public void onViewAttachedToWindow(View view) {}

                            public void onViewDetachedFromWindow(View view) {
                                confirmation.dismiss();
                            }
                        };
                origin.addOnAttachStateChangeListener(leaving);
                confirmation.onDismiss(() -> origin.removeOnAttachStateChangeListener(leaving));
                confirmation.show();
            } else change(control, checked);
        }

        void change(int control, boolean checked) {
            if (!current(requested, origin, client)
                    || !sheet.isShowing()
                    || !access.begin(control, checked)) {
                bind();
                return;
            }
            bind();
            status.setTextColor(Ui.MUTED);
            status.setText("正在保存…");
            host.runSettingTask(
                    () -> {
                        JSONObject latest = readAccess(client, grant.optString("id"));
                        if (!latest.optBoolean("can_change"))
                            throw new IOException("当前权限已变化，请重新打开后查看。");
                        String previous = latest.optString("role");
                        String next = ChildAccessState.next(previous, control, checked);
                        if (!previous.equals(next))
                            client.request(
                                    "PUT",
                                    "/api/children/"
                                            + ApiClient.encode(grant.optString("id"))
                                            + "/members/"
                                            + ApiClient.encode(person.optString("id")),
                                    json("role", next.isEmpty() ? JSONObject.NULL : next));
                        return latest.put("role", next)
                                .put("can_change", familyAdmin || !next.isEmpty());
                    },
                    result -> {
                        if (!current(requested, origin, client)) return;
                        grant.put("role", result.optString("role"));
                        grant.put("can_change", result.optBoolean("can_change"));
                        access.complete(result.optString("role"), result.optBoolean("can_change"));
                        rowStatus.setText(label(access.role()));
                        if (sheet.isShowing()) {
                            bind();
                            status.setTextColor(Ui.MUTED);
                            status.setText("已保存");
                        }
                    },
                    error -> {
                        if (!current(requested, origin, client)) return;
                        access.fail();
                        if (sheet.isShowing()) {
                            bind();
                            status.setTextColor(Ui.ERROR);
                            status.setText(error);
                        }
                    });
        }
    }
}
