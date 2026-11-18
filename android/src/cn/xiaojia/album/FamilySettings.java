package cn.xiaojia.album;

import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.text.InputFilter;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Family membership and single-use invitations; no invitation is sent automatically. */
final class FamilySettings extends SettingsScreen {
    private Runnable returnToOrigin;
    private Dialog inviteDialog;
    private View familyPanel;

    private boolean familyAdmin() {
        return host.identity().optBoolean("is_family_admin");
    }

    private boolean managesChildren() {
        return familyAdmin() || allowed("can_invite_child");
    }

    FamilySettings(Host host) {
        super(host);
        returnToOrigin = host::accountHome;
    }

    void invite() {
        returnToOrigin = host::accountHome;
        if (!managesChildren()) {
            Toast.makeText(activity, "邀请家人需要管理员操作", Toast.LENGTH_SHORT).show();
            return;
        }
        showInvite();
    }

    void open(Runnable back) {
        returnToOrigin = back;
        show();
    }

    private int familyListRequest;

    void show() {
        boolean manage = managesChildren();
        LinearLayout panel = page("family_settings", "相册家人", returnToOrigin);
        int pageRevision = revision;
        familyPanel = panel;
        ApiClient source = host.client().snapshot();
        TextView context = text(host.family().optString("child_name", "宝宝") + "的相册", 14, Ui.MUTED);
        context.setPadding(0, dp(2), 0, dp(12));
        panel.addView(context);
        if (manage) toolbarAction("更多", () -> showMore(panel, source));
        TextView loading = note(panel, "正在读取家人资料…");
        LinearLayout list = group(panel);
        if (manage) footerButton("添加家人", this::showInvite);
        PullRefreshLayout.Action reload =
                done -> {
                    int ticket = ++familyListRequest;
                    host.runSettingTask(
                            () ->
                                    source.request(
                                            "GET",
                                            manage
                                                    ? "/api/children/"
                                                            + ApiClient.encode(source.childId)
                                                            + "/members"
                                                    : "/api/members",
                                            null),
                            result -> {
                                if (ticket != familyListRequest
                                        || revision != pageRevision
                                        || !current(panel, source)) return;
                                list.removeAllViews();
                                loading.setVisibility(View.GONE);
                                if (done != null) done.accept(true);
                                JSONArray members = registered(result.optJSONArray("items"));
                                if (members.length() == 0) {
                                    note(list, "还没有家人加入这个相册。");
                                    return;
                                }
                                for (int i = 0; i < members.length(); i++) {
                                    if (i > 0) divider(list);
                                    list.addView(memberRow(members.getJSONObject(i), manage));
                                }
                            },
                            error -> {
                                if (ticket != familyListRequest
                                        || revision != pageRevision
                                        || !current(panel, source)) return;
                                if (done != null) {
                                    done.accept(false);
                                    return;
                                }
                                loading.setText(error);
                                loading.setTextColor(Ui.ERROR);
                                textAction(list, "重新读取", this::show);
                            });
                };
        enableListRefresh(panel, reload);
        reload.run(null);
    }

    private void showMore(View origin, ApiClient source) {
        if (!current(origin, source) || !managesChildren()) return;
        WarmSheet menu = new WarmSheet(activity, "家人管理");
        menu.body.addView(
                iconRow(
                        "mail",
                        "邀请记录",
                        null,
                        () -> {
                            menu.dismiss();
                            showInvitations(false);
                        }));
        if (familyAdmin()) {
            divider(menu.body);
            menu.body.addView(
                    iconRow(
                            "people",
                            "家庭账号管理",
                            null,
                            () -> {
                                menu.dismiss();
                                showFamilyAccounts();
                            }));
        }
        divider(menu.body);
        menu.body.addView(
                iconRow(
                        "photo",
                        "历史上传者",
                        null,
                        () -> {
                            menu.dismiss();
                            showLegacyRecords();
                        }));
        watch(menu, origin);
        menu.show();
    }

    private View memberRow(JSONObject member, boolean manage) {
        String name =
                MemberAccessState.relationship(
                        member.optString("relationship", member.optString("name", "家人")));
        if (name.isEmpty()) name = "家人";
        boolean self = member.optString("id").equals(host.identity().optString("id"));
        return personRow(
                name,
                name + (self ? "（我）" : ""),
                memberRole(member),
                manage
                        ? () ->
                                new MemberInfoSettings(host, member.optString("id"), this::show)
                                        .show()
                        : null);
    }

    private String memberRole(JSONObject member) {
        if (member.optBoolean("disabled")) return "已停用";
        if (member.optBoolean("is_family_admin")) return "家庭管理员";
        if (member.optString("role").equals("admin")) return "宝宝管理员";
        if (member.optString("role").equals("contributor")) return "可上传";
        return "仅查看";
    }

    private View personRow(String face, String name, String detail, Runnable action) {
        LinearLayout row = Ui.row(activity);
        row.setMinimumHeight(dp(82));
        row.setPadding(0, dp(14), 0, dp(14));
        row.addView(avatar(face, 50));
        LinearLayout words = Ui.column(activity);
        words.setPadding(dp(14), 0, dp(10), 0);
        TextView title = text(name, 17, Ui.INK);
        title.setTypeface(null, Typeface.BOLD);
        words.addView(title);
        TextView status = text(detail, 13, Ui.MUTED);
        status.setPadding(0, dp(5), 0, 0);
        words.addView(status);
        row.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        if (action != null) {
            row.addView(
                    new Ui.Icon(activity, "next", Ui.MUTED),
                    new LinearLayout.LayoutParams(dp(17), dp(17)));
            row.setBackground(Ui.ripple(Ui.BG, 12, activity));
            row.setFocusable(true);
            row.setOnClickListener(v -> action.run());
        }
        return row;
    }

    private void showInvitations(boolean ended) {
        if (!managesChildren()) return;
        LinearLayout panel = page("family_invitations", "邀请记录", this::show);
        familyPanel = panel;
        ApiClient source = host.client().snapshot();
        LinearLayout tabs = Ui.row(activity);
        for (int i = 0; i < 2; i++) {
            final boolean tabEnded = i == 1;
            TextView tab =
                    text(
                            tabEnded ? "已结束" : "待加入",
                            16,
                            ended == tabEnded ? Ui.ACCENT_DARK : Ui.MUTED);
            tab.setGravity(Gravity.CENTER);
            tab.setMinHeight(dp(48));
            tab.setBackground(Ui.chip(activity, ended == tabEnded));
            tab.setTypeface(null, ended == tabEnded ? Typeface.BOLD : Typeface.NORMAL);
            tab.setOnClickListener(
                    v -> {
                        if (current(panel, source) && ended != tabEnded) showInvitations(tabEnded);
                    });
            tabs.addView(tab, new LinearLayout.LayoutParams(0, -2, 1));
        }
        panel.addView(tabs);
        TextView state = note(panel, "正在读取邀请…");
        LinearLayout list = group(panel);
        host.runSettingTask(
                () -> source.request("GET", "/api/invitations", null),
                result -> {
                    if (!current(panel, source)) return;
                    state.setVisibility(View.GONE);
                    JSONArray items = result.optJSONArray("items");
                    int count = 0;
                    if (items != null)
                        for (int i = 0; i < items.length(); i++) {
                            JSONObject item = items.getJSONObject(i);
                            boolean active = "active".equals(item.optString("status"));
                            if (active == ended) continue;
                            if (count++ > 0) divider(list);
                            list.addView(invitationRow(item, ended, panel, source));
                        }
                    if (count == 0) note(list, ended ? "还没有已结束的邀请。" : "没有等待加入的邀请。");
                    else note(panel, "邀请码只在生成时展示。");
                },
                error -> {
                    if (!current(panel, source)) return;
                    state.setTextColor(Ui.ERROR);
                    state.setText(error);
                    textAction(list, "重新读取", () -> showInvitations(ended));
                });
    }

    private View invitationRow(JSONObject item, boolean ended, View origin, ApiClient source) {
        LinearLayout row = Ui.row(activity);
        row.setPadding(0, dp(16), 0, dp(16));
        row.addView(
                new Ui.Icon(activity, "mail", Ui.ACCENT, true),
                new LinearLayout.LayoutParams(dp(36), dp(36)));
        LinearLayout words = Ui.column(activity);
        words.setPadding(dp(14), 0, dp(6), 0);
        String name = MemberAccessState.relationship(item.optString("name", "新的家人"));
        TextView title = text(name.isEmpty() ? "新的家人" : name, 17, Ui.INK);
        title.setTypeface(null, Typeface.BOLD);
        words.addView(title);
        String status = item.optString("status");
        String detail =
                ended
                        ? (status.equals("used") ? "已加入" : status.equals("revoked") ? "已撤销" : "已失效")
                        : invitationDeadline(item.optString("expires_at"));
        TextView subtitle = text(detail, 13, Ui.MUTED);
        subtitle.setPadding(0, dp(5), 0, 0);
        words.addView(subtitle);
        row.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        if (!ended) {
            TextView revoke = text("撤销", 14, Ui.ACCENT_DARK);
            revoke.setMinHeight(dp(48));
            revoke.setMinWidth(dp(48));
            revoke.setGravity(Gravity.CENTER);
            revoke.setBackground(Ui.ripple(Ui.BG, 12, activity));
            revoke.setOnClickListener(
                    v -> {
                        if (current(origin, source)) revoke(item);
                    });
            row.addView(revoke, new LinearLayout.LayoutParams(-2, -2));
        }
        return row;
    }

    private String invitationDeadline(String value) {
        try {
            OffsetDateTime time =
                    OffsetDateTime.parse(value)
                            .atZoneSameInstant(ZoneId.of("Asia/Shanghai"))
                            .toOffsetDateTime();
            LocalDate day = time.toLocalDate(), today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
            String date =
                    day.equals(today)
                            ? "今天"
                            : day.equals(today.plusDays(1))
                                    ? "明天"
                                    : time.getYear()
                                            + "年"
                                            + time.getMonthValue()
                                            + "月"
                                            + time.getDayOfMonth()
                                            + "日";
            return date + " " + time.format(DateTimeFormatter.ofPattern("HH:mm")) + " 前有效";
        } catch (Exception ignored) {
            return "有效期暂时无法确认";
        }
    }

    private void showFamilyAccounts() {
        if (!familyAdmin()) return;
        LinearLayout panel = page("family_accounts", "家庭账号管理", this::show);
        familyPanel = panel;
        ApiClient source = host.client().snapshot();
        TextView state = note(panel, "正在读取家庭账号…");
        LinearLayout list = group(panel);
        host.runSettingTask(
                () -> source.request("GET", "/api/members", null),
                result -> {
                    if (!current(panel, source)) return;
                    state.setVisibility(View.GONE);
                    JSONArray accounts = registered(result.optJSONArray("items"));
                    if (accounts.length() == 0) {
                        note(list, "还没有已加入的家庭账号。");
                        return;
                    }
                    for (int i = 0; i < accounts.length(); i++) {
                        JSONObject account = accounts.getJSONObject(i);
                        if (i > 0) divider(list);
                        String name = account.optString("name", "家人");
                        String role =
                                account.optBoolean("disabled")
                                        ? "已停用"
                                        : account.optBoolean("is_family_admin") ? "家庭管理员" : "已加入家庭";
                        list.addView(
                                personRow(
                                        name,
                                        name,
                                        role,
                                        () -> showAccount(account.optString("id"))));
                    }
                },
                error -> {
                    if (!current(panel, source)) return;
                    state.setText(error);
                    state.setTextColor(Ui.ERROR);
                    textAction(list, "重新读取", this::showFamilyAccounts);
                });
    }

    private JSONObject readAccount(ApiClient source, String memberId) throws Exception {
        JSONArray accounts =
                registered(source.request("GET", "/api/members", null).optJSONArray("items"));
        for (int i = 0; i < accounts.length(); i++) {
            JSONObject account = accounts.getJSONObject(i);
            if (memberId.equals(account.optString("id"))) return account;
        }
        throw new IOException("这位家人的账号暂时无法读取，请返回列表刷新。");
    }

    private void showAccount(String memberId) {
        if (!familyAdmin()) return;
        LinearLayout panel = page("family_account_detail", "家人账号", this::showFamilyAccounts);
        familyPanel = panel;
        ApiClient source = host.client().snapshot();
        TextView state = state(panel);
        state.setTextColor(Ui.MUTED);
        state.setText("正在读取账号…");
        host.runSettingTask(
                () -> readAccount(source, memberId),
                result -> {
                    if (!current(panel, source)) return;
                    state.setText("");
                    new AccountEditor(panel, source, result, state).build();
                },
                error -> {
                    if (!current(panel, source)) return;
                    state.setTextColor(Ui.ERROR);
                    state.setText(error);
                    textAction(panel, "重新读取", () -> showAccount(memberId));
                });
    }

    private final class AccountEditor {
        final LinearLayout panel;
        final ApiClient source;
        final TextView state;
        JSONObject account;
        Switch administrator, login;
        View accessRow;
        TextView roleLabel, accessValue;
        boolean binding, busy;

        AccountEditor(LinearLayout panel, ApiClient source, JSONObject account, TextView state) {
            this.panel = panel;
            this.source = source;
            this.account = account;
            this.state = state;
        }

        void build() {
            LinearLayout identity = Ui.row(activity);
            identity.setPadding(0, dp(10), 0, dp(24));
            String name = account.optString("name", "家人");
            identity.addView(avatar(name, 60));
            LinearLayout labels = Ui.column(activity);
            labels.setPadding(dp(14), 0, 0, 0);
            TextView title = text(name, 22, Ui.INK);
            title.setTypeface(null, Typeface.BOLD);
            labels.addView(title);
            roleLabel = text("", 13, Ui.MUTED);
            roleLabel.setPadding(0, dp(6), 0, 0);
            labels.addView(roleLabel);
            identity.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
            panel.addView(identity);
            LinearLayout group = group(panel);
            LinearLayout accessLine = Ui.row(activity);
            accessLine.setPadding(0, dp(12), 0, dp(12));
            accessLine.setMinimumHeight(dp(58));
            accessLine.addView(
                    new Ui.Icon(activity, "baby", Ui.ACCENT),
                    new LinearLayout.LayoutParams(dp(24), dp(24)));
            TextView accessTitle = text("可访问的宝宝", 16, Ui.INK);
            accessTitle.setPadding(dp(12), 0, dp(8), 0);
            accessLine.addView(accessTitle, new LinearLayout.LayoutParams(0, -2, 1));
            accessValue = text("", 14, Ui.MUTED);
            accessValue.setPadding(0, 0, dp(8), 0);
            accessLine.addView(accessValue, new LinearLayout.LayoutParams(-2, -2));
            accessLine.addView(
                    new Ui.Icon(activity, "next", Ui.MUTED),
                    new LinearLayout.LayoutParams(dp(16), dp(16)));
            accessLine.setBackground(Ui.ripple(Ui.BG, 12, activity));
            accessLine.setOnClickListener(
                    v -> {
                        if (!busy
                                && !account.optBoolean("is_family_admin")
                                && current(panel, source))
                            new ChildAccessSettings(
                                            host,
                                            account,
                                            () -> showAccount(account.optString("id")))
                                    .show();
                    });
            accessRow = accessLine;
            group.addView(accessRow);
            divider(group);
            administrator = accountToggle(group, "people", "家庭管理员", "管理整个家庭的账号和设置");
            LinearLayout loginGroup = group(panel);
            login = accountToggle(loginGroup, "lock", "允许登录", "关闭后，这个账号将无法登录");
            if (self()) note(panel, "这是你的账号，管理和登录权限受到保护。");
            administrator.setOnCheckedChangeListener(
                    (button, enabled) -> {
                        if (!binding) {
                            bind();
                            confirm("family_role", enabled);
                        }
                    });
            login.setOnCheckedChangeListener(
                    (button, enabled) -> {
                        if (!binding) {
                            bind();
                            confirm("disabled", !enabled);
                        }
                    });
            bind();
        }

        boolean self() {
            return account.optString("id").equals(host.identity().optString("id"));
        }

        void bind() {
            binding = true;
            boolean admin = account.optBoolean("is_family_admin"),
                    disabled = account.optBoolean("disabled");
            roleLabel.setText(disabled ? "已停用" : admin ? "家庭管理员" : "已加入家庭");
            accessValue.setText(admin ? "全部宝宝" : "按宝宝设置");
            administrator.setChecked(admin);
            login.setChecked(!disabled);
            administrator.setEnabled(!busy && !self());
            login.setEnabled(!busy && !self());
            administrator.setAlpha(administrator.isEnabled() ? 1f : .45f);
            login.setAlpha(login.isEnabled() ? 1f : .45f);
            accessRow.setEnabled(!busy && !admin && !self());
            accessRow.setAlpha(admin || self() ? .45f : 1f);
            binding = false;
        }

        void confirm(String field, boolean enabled) {
            if (busy || self() || !current(panel, source)) return;
            boolean role = field.equals("family_role");
            String title =
                    role
                            ? (enabled ? "设为家庭管理员？" : "取消家庭管理员？")
                            : (enabled ? "停用这个账号？" : "允许这个账号登录？");
            String message =
                    role
                            ? (enabled ? "这位家人将能管理所有宝宝、家庭账号和存放位置。" : "取消后，按每位宝宝已设置的权限访问。")
                            : enabled ? "原照片和留言会保留，之后仍可重新启用。" : "这位家人将能重新登录相册。";
            WarmSheet sheet = new WarmSheet(activity, title);
            note(sheet.body, message);
            TextView error = state(sheet.body);
            TextView submit = sheet.primary("确认", null);
            TextView cancel = sheet.secondary("取消", sheet::dismiss);
            watch(sheet, panel);
            submit.setOnClickListener(
                    v -> {
                        if (busy || !sheet.isShowing() || !current(panel, source)) return;
                        busy = true;
                        bind();
                        cancel.setEnabled(false);
                        sheet.setCancelable(false);
                        submit.setEnabled(false);
                        submit.setText("正在保存…");
                        error.setText("");
                        JSONObject change =
                                json(field, role ? (enabled ? "admin" : "viewer") : enabled);
                        host.runSettingTask(
                                () ->
                                        source.request(
                                                "PATCH",
                                                "/api/members/"
                                                        + ApiClient.encode(account.optString("id")),
                                                change),
                                result -> {
                                    if (!current(panel, source)) return;
                                    account = result;
                                    busy = false;
                                    bind();
                                    sheet.dismiss();
                                    state.setTextColor(Ui.MUTED);
                                    state.setText("已保存");
                                },
                                failure -> {
                                    if (!current(panel, source)) return;
                                    busy = false;
                                    bind();
                                    sheet.setCancelable(true);
                                    cancel.setEnabled(true);
                                    submit.setEnabled(true);
                                    submit.setText("确认");
                                    error.setText(failure);
                                });
                    });
            sheet.show();
        }
    }

    private Switch accountToggle(LinearLayout parent, String icon, String title, String helper) {
        LinearLayout row = Ui.row(activity);
        row.setPadding(0, dp(14), 0, dp(14));
        row.addView(
                new Ui.Icon(activity, icon, Ui.ACCENT),
                new LinearLayout.LayoutParams(dp(24), dp(24)));
        LinearLayout words = Ui.column(activity);
        words.setPadding(dp(12), 0, dp(8), 0);
        TextView name = text(title, 17, Ui.INK);
        words.addView(name);
        TextView note = text(helper, 13, Ui.MUTED);
        note.setPadding(0, dp(4), 0, 0);
        words.addView(note);
        row.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        Switch toggle = new Switch(activity);
        toggle.setContentDescription(title);
        toggle.setMinHeight(dp(48));
        toggle.setSwitchMinWidth(dp(52));
        toggle.setShowText(false);
        toggle.setThumbTintList(ColorStateList.valueOf(Color.WHITE));
        toggle.setTrackTintList(
                new ColorStateList(
                        new int[][] {new int[] {android.R.attr.state_checked}, new int[] {}},
                        new int[] {Ui.ACCENT, 0xFFD7D0CC}));
        row.addView(toggle, new LinearLayout.LayoutParams(-2, dp(48)));
        parent.addView(row);
        return toggle;
    }

    private JSONArray registered(JSONArray source) {
        JSONArray result = new JSONArray();
        if (source != null)
            for (int i = 0; i < source.length(); i++) {
                JSONObject member = source.optJSONObject(i);
                if (member != null && member.optBoolean("registered")) result.put(member);
            }
        return result;
    }

    private boolean current(View origin, ApiClient source) {
        ApiClient now = host.client();
        return origin != null
                && origin.isAttachedToWindow()
                && !activity.isFinishing()
                && !activity.isDestroyed()
                && source.baseUrl.equals(now.baseUrl)
                && source.token.equals(now.token)
                && source.childId.equals(now.childId);
    }

    private void watch(WarmSheet sheet, View origin) {
        View.OnAttachStateChangeListener leaving =
                new View.OnAttachStateChangeListener() {
                    public void onViewAttachedToWindow(View view) {}

                    public void onViewDetachedFromWindow(View view) {
                        sheet.dismiss();
                    }
                };
        origin.addOnAttachStateChangeListener(leaving);
        sheet.onDismiss(() -> origin.removeOnAttachStateChangeListener(leaving));
    }

    private boolean currentInvite(Dialog dialog, View origin, ApiClient source) {
        return inviteDialog == dialog && dialog.isShowing() && current(origin, source);
    }

    private void showLegacyRecords() {
        if (!managesChildren()) return;
        LinearLayout panel = page("family_legacy", "历史上传者", this::show);
        familyPanel = panel;
        ApiClient source = host.client().snapshot();
        note(panel, "这些记录来自以前的相册，关联邀请后保留原上传归属。");
        TextView state = note(panel, "正在读取历史上传者…");
        LinearLayout list = group(panel);
        host.runSettingTask(
                () -> source.request("GET", "/api/members?include_legacy=true", null),
                result -> {
                    if (!current(panel, source)) return;
                    state.setVisibility(View.GONE);
                    JSONArray items = result.optJSONArray("items");
                    int count = 0;
                    if (items != null)
                        for (int i = 0; i < items.length(); i++) {
                            JSONObject member = items.getJSONObject(i);
                            if (member.optBoolean("registered")) continue;
                            if (count++ > 0) divider(list);
                            String name = member.optString("name", "原相册上传者");
                            list.addView(
                                    personRow(
                                            name,
                                            name,
                                            member.optBoolean("disabled") ? "已停用，原照片仍保留" : "关联邀请",
                                            member.optBoolean("disabled")
                                                    ? null
                                                    : () -> showLegacyInvite(member)));
                        }
                    if (count == 0) note(list, "没有需要关联的历史上传者。");
                },
                error -> {
                    if (!current(panel, source)) return;
                    state.setTextColor(Ui.ERROR);
                    state.setText(error);
                    textAction(list, "重新读取", this::showLegacyRecords);
                });
    }

    private static final class InviteDraft {
        boolean busy, generated;
    }

    private void showInvite() {
        openInvitation(null);
    }

    private void showLegacyInvite(JSONObject existing) {
        openInvitation(existing);
    }

    private void openInvitation(JSONObject existing) {
        if (!managesChildren()) return;
        if (inviteDialog != null && inviteDialog.isShowing()) return;
        final ApiClient source = host.client().snapshot();
        if (source.childId.isEmpty()) {
            Toast.makeText(activity, "请先选择一位宝宝，再添加家人", Toast.LENGTH_SHORT).show();
            return;
        }
        ViewGroup content = activity.findViewById(android.R.id.content);
        final View origin =
                content == null || content.getChildCount() == 0 ? null : content.getChildAt(0);
        if (!current(origin, source)) return;
        final InviteDraft draft = new InviteDraft();
        final Dialog dialog = new Dialog(activity);
        inviteDialog = dialog;
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout sheet = Ui.column(activity);
        sheet.setPadding(dp(22), dp(10), dp(22), dp(20));
        sheet.setBackground(Ui.round(Ui.BG, 24, activity));
        View grip = new View(activity);
        grip.setBackground(Ui.round(Ui.LINE, 2, activity));
        LinearLayout.LayoutParams gripParams = new LinearLayout.LayoutParams(dp(32), dp(4));
        gripParams.gravity = Gravity.CENTER_HORIZONTAL;
        gripParams.bottomMargin = dp(8);
        sheet.addView(grip, gripParams);
        LinearLayout heading = Ui.row(activity);
        TextView title = text(existing == null ? "添加家人" : "关联历史上传者", 22, Ui.INK);
        title.setTypeface(null, Typeface.BOLD);
        heading.addView(title, new LinearLayout.LayoutParams(0, dp(50), 1));
        TextView close = text("关闭", 14, Ui.MUTED);
        close.setGravity(Gravity.CENTER);
        close.setContentDescription("关闭添加家人");
        close.setBackground(Ui.ripple(Ui.BG, 20, activity));
        close.setOnClickListener(v -> dialog.dismiss());
        heading.addView(close, new LinearLayout.LayoutParams(dp(48), dp(48)));
        sheet.addView(heading);
        ScrollView scroll =
                new ScrollView(activity) {
                    @Override
                    protected void onMeasure(int width, int height) {
                        int maximum =
                                (int) (getResources().getDisplayMetrics().heightPixels * .65f);
                        if (MeasureSpec.getMode(height) != MeasureSpec.UNSPECIFIED)
                            maximum = Math.min(maximum, MeasureSpec.getSize(height));
                        super.onMeasure(
                                width, MeasureSpec.makeMeasureSpec(maximum, MeasureSpec.AT_MOST));
                    }
                };
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout panel = Ui.column(activity);
        scroll.addView(panel, new ScrollView.LayoutParams(-1, -2));
        sheet.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
        final EditText name;
        if (existing == null) {
            name = field(panel, "和宝宝的关系", "例如：奶奶、小姨", "", false);
            name.setFilters(new InputFilter[] {new InputFilter.LengthFilter(40)});
            name.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
            note(panel, "家人加入后，可在家人信息里调整权限");
        } else {
            name = null;
            TextView relation = text(existing.optString("name", "原相册上传者"), 22, Ui.INK);
            relation.setTypeface(null, Typeface.BOLD);
            relation.setPadding(0, dp(18), 0, dp(6));
            panel.addView(relation);
            note(panel, "邀请这位家人关联原上传记录，照片和留言都会保留。加入后可在家人信息里调整权限。");
        }
        String childName = host.family().optString("child_name", "当前宝宝");
        TextView state = state(panel);
        TextView create = button(sheet, "生成邀请", true, null);
        create.setOnClickListener(
                v -> {
                    if (!currentInvite(dialog, origin, source) || draft.busy || draft.generated)
                        return;
                    String relation =
                            MemberAccessState.relationship(
                                    name == null
                                            ? existing.optString("name")
                                            : name.getText().toString());
                    if (relation.isEmpty() || relation.codePointCount(0, relation.length()) > 40) {
                        state.setText("请填写1到40个字的关系称呼，例如奶奶、小姨。");
                        if (name != null) name.requestFocus();
                        return;
                    }
                    final String selectedNames = childName;
                    JSONObject body = json("name", relation);
                    try {
                        body.put("role", "viewer");
                        body.put("expires_hours", 24);
                        body.put("child_ids", new JSONArray().put(source.childId));
                        if (existing != null) body.put("member_id", existing.optString("id"));
                    } catch (Exception ignored) {
                        state.setText("邀请内容尚未准备好，请重新填写。");
                        return;
                    }
                    draft.busy = true;
                    create.setEnabled(false);
                    create.setText("正在生成…");
                    if (name != null) name.setEnabled(false);
                    state.setText("");
                    host.runSettingTask(
                            () -> source.request("POST", "/api/invitations", body),
                            result -> {
                                if (!currentInvite(dialog, origin, source)) return;
                                draft.busy = false;
                                draft.generated = true;
                                String invitedName = result.optString("name", relation).trim();
                                if (invitedName.isEmpty()) invitedName = relation;
                                final String invitationCode = result.optString("code");
                                String invitation =
                                        "邀请"
                                                + invitedName
                                                + "加入“"
                                                + selectedNames
                                                + "”的家庭相册\n"
                                                + "服务器："
                                                + source.baseUrl
                                                + "\n邀请码："
                                                + invitationCode
                                                + "\n有效至："
                                                + Ui.readable(result.optString("expires_at"))
                                                + "\n\n首次使用：打开FAM宝贝相册，选择“使用邀请码加入”，设置自己的账号和密码。"
                                                + "\n已有账号：在宝宝管理中选择“用邀请码加入宝宝相册”，继续使用原账号。";
                                title.setText("邀请已准备好");
                                close.setText("完成");
                                close.setContentDescription("完成添加家人");
                                panel.removeAllViews();
                                note(panel, "邀请" + invitedName + "加入" + selectedNames + "的相册");
                                boolean shortCode = invitationCode.matches("[0-9]{6}");
                                LinearLayout codeCard = card(panel);
                                codeCard.setBackground(Ui.round(Ui.SOFT, 16, activity));
                                TextView codeLabel =
                                        text(shortCode ? "6位邀请码" : "邀请码", 12, Ui.MUTED);
                                codeLabel.setGravity(Gravity.CENTER);
                                codeCard.addView(codeLabel, new LinearLayout.LayoutParams(-1, -2));
                                TextView codeValue =
                                        text(invitationCode, shortCode ? 40 : 18, Ui.ACCENT_DARK);
                                codeValue.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
                                codeValue.setGravity(Gravity.CENTER);
                                codeValue.setPadding(dp(4), dp(12), dp(4), dp(10));
                                codeValue.setTextIsSelectable(true);
                                if (shortCode) {
                                    codeValue.setSingleLine(true);
                                    codeValue.setLetterSpacing(.12f);
                                    codeValue.setAutoSizeTextTypeUniformWithConfiguration(
                                            20, 40, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
                                } else {
                                    codeValue.setSingleLine(false);
                                    codeValue.setHorizontallyScrolling(false);
                                }
                                codeCard.addView(codeValue, new LinearLayout.LayoutParams(-1, -2));
                                note(panel, "有效至 " + Ui.readable(result.optString("expires_at")));
                                TextView fullInvite = text("复制完整邀请", 14, Ui.ACCENT_DARK);
                                fullInvite.setGravity(Gravity.CENTER);
                                fullInvite.setMinHeight(dp(48));
                                fullInvite.setBackground(Ui.ripple(Ui.BG, 16, activity));
                                fullInvite.setOnClickListener(
                                        link -> {
                                            if (currentInvite(dialog, origin, source))
                                                copyInvitation(invitation, true);
                                        });
                                panel.addView(fullInvite, new LinearLayout.LayoutParams(-1, -2));
                                note(panel, "首次使用可复制完整邀请。邀请码请在关闭前复制。");
                                create.setEnabled(true);
                                create.setText("复制邀请码");
                                create.setOnClickListener(
                                        button -> {
                                            if (!currentInvite(dialog, origin, source)) return;
                                            copyInvitation(invitationCode, false);
                                        });
                                android.view.inputmethod.InputMethodManager keyboard =
                                        (android.view.inputmethod.InputMethodManager)
                                                activity.getSystemService(
                                                        Context.INPUT_METHOD_SERVICE);
                                if (keyboard != null)
                                    keyboard.hideSoftInputFromWindow(
                                            dialog.getWindow().getDecorView().getWindowToken(), 0);
                                scroll.scrollTo(0, 0);
                            },
                            error -> {
                                if (!currentInvite(dialog, origin, source)) return;
                                draft.busy = false;
                                create.setEnabled(true);
                                create.setText("生成邀请");
                                if (name != null) name.setEnabled(true);
                                state.setText(error);
                            });
                });
        if (name != null)
            name.setOnEditorActionListener(
                    (field, action, event) -> {
                        if (action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                            create.performClick();
                            return true;
                        }
                        return false;
                    });
        dialog.setContentView(sheet);
        View.OnAttachStateChangeListener leaving =
                new View.OnAttachStateChangeListener() {
                    public void onViewAttachedToWindow(View view) {}

                    public void onViewDetachedFromWindow(View view) {
                        dialog.dismiss();
                    }
                };
        origin.addOnAttachStateChangeListener(leaving);
        dialog.setOnDismissListener(
                d -> {
                    origin.removeOnAttachStateChangeListener(leaving);
                    if (inviteDialog == dialog) inviteDialog = null;
                    if (draft.generated
                            && current(origin, source)
                            && familyPanel != null
                            && familyPanel.isAttachedToWindow()) show();
                });
        dialog.show();
        Window window = dialog.getWindow();
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.setGravity(Gravity.BOTTOM);
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        window.setNavigationBarColor(Ui.BG);
    }

    private void copyInvitation(String value, boolean complete) {
        ClipboardManager clipboard =
                (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) return;
        clipboard.setPrimaryClip(ClipData.newPlainText(complete ? "家庭相册邀请" : "邀请码", value));
        Toast.makeText(activity, complete ? "完整邀请已复制，包含相册地址" : "邀请码已复制", Toast.LENGTH_SHORT).show();
    }

    private void revoke(JSONObject invitation) {
        final View origin = familyPanel;
        final ApiClient source = host.client().snapshot();
        if (!current(origin, source)) return;
        WarmSheet sheet = new WarmSheet(activity, "撤销这个邀请？");
        note(sheet.body, "撤销后，这个邀请码将无法加入相册。已加入的家人不受影响。");
        TextView state = state(sheet.body);
        TextView submit = sheet.primary("撤销邀请", null);
        TextView cancel = sheet.secondary("取消", sheet::dismiss);
        boolean[] busy = {false};
        submit.setOnClickListener(
                v -> {
                    if (busy[0] || !sheet.isShowing() || !current(origin, source)) return;
                    busy[0] = true;
                    submit.setEnabled(false);
                    cancel.setEnabled(false);
                    sheet.setCancelable(false);
                    state.setText("");
                    host.runSettingTask(
                            () ->
                                    source.request(
                                            "DELETE",
                                            "/api/invitations/"
                                                    + ApiClient.encode(invitation.optString("id")),
                                            null),
                            result -> {
                                if (!current(origin, source)) return;
                                sheet.dismiss();
                                showInvitations(false);
                            },
                            error -> {
                                if (!current(origin, source)) return;
                                busy[0] = false;
                                submit.setEnabled(true);
                                cancel.setEnabled(true);
                                sheet.setCancelable(true);
                                state.setText(error);
                            });
                });
        watch(sheet, origin);
        sheet.show();
    }
}
