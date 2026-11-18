package cn.xiaojia.album;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.function.Consumer;

/** Small, focused baby lists and forms. Album data stays on the selected server. */
final class BabySettings extends SettingsScreen {
    interface Host extends SettingsScreen.Host {
        JSONArray babies();

        ImageLoader images();

        void selectBaby(String id);

        void childrenChanged(String preferred);
    }

    private final Host babyHost;
    private Runnable back;

    BabySettings(Host host) {
        super(host);
        babyHost = host;
        back = host::accountHome;
    }

    private boolean familyAdmin() {
        return host.identity().optBoolean("is_family_admin");
    }

    private boolean current(View panel, int requested, ApiClient source) {
        return requested == revision && panel.isAttachedToWindow() && sameScope(source);
    }

    private boolean sameScope(ApiClient source) {
        ApiClient now = host.client();
        return source.baseUrl.equals(now.baseUrl)
                && source.token.equals(now.token)
                && source.childId.equals(now.childId);
    }

    void selector(Runnable origin) {
        back = origin;
        WarmSheet sheet = new WarmSheet(activity, "切换宝宝");
        ApiClient source = host.client().snapshot();
        ImageLoader roster = rosterLoader(source);
        sheet.onDismiss(roster::close);
        JSONArray children = babyHost.babies();
        int visible = 0;
        for (int i = 0; i < children.length(); i++) {
            JSONObject child = children.optJSONObject(i);
            if (child == null || child.optBoolean("archived")) continue;
            if (visible++ > 0) divider(sheet.body);
            sheet.body.addView(
                    childRow(
                            child,
                            child.optString("id").equals(source.childId),
                            () -> {
                                sheet.dismiss();
                                if (sameScope(source)) babyHost.selectBaby(child.optString("id"));
                            },
                            roster,
                            false));
        }
        if (visible == 0) note(sheet.body, "还没有宝宝相册，可以使用家人的邀请加入。");
        sheet.secondary(
                "管理宝宝相册",
                () -> {
                    sheet.dismiss();
                    show(origin);
                });
        sheet.show();
    }

    void show(Runnable origin) {
        back = origin;
        show();
    }

    void show() {
        showList(false);
    }

    private int babyListRequest;

    private void showList(boolean archivedOnly) {
        LinearLayout panel =
                page(
                        archivedOnly ? "baby_archived" : "baby_settings",
                        archivedOnly ? "已收起的相册" : "宝宝相册",
                        archivedOnly ? this::show : back);
        final int requested = revision;
        ApiClient source = host.client().snapshot();
        ImageLoader roster = rosterLoader(source);
        TextView status = state(panel);
        status.setTextColor(Ui.MUTED);
        status.setText("正在读取宝宝资料…");
        LinearLayout list = group(panel);
        if (!archivedOnly) {
            if (familyAdmin()) {
                TextView add = footerButton("添加宝宝", () -> edit(null));
                textAction((LinearLayout) add.getParent(), "用邀请码加入", this::join);
            } else footerButton("用邀请码加入", this::join);
        }
        PullRefreshLayout.Action reload =
                done -> {
                    int ticket = ++babyListRequest;
                    host.runSettingTask(
                            () ->
                                    source.request(
                                            "GET",
                                            "/api/children"
                                                    + (familyAdmin()
                                                            ? "?include_archived=true"
                                                            : ""),
                                            null),
                            result -> {
                                if (ticket != babyListRequest || !current(panel, requested, source))
                                    return;
                                list.removeAllViews();
                                status.setText("");
                                if (done != null) done.accept(true);
                                JSONArray children = result.optJSONArray("items");
                                int shown = 0, archived = 0;
                                if (children != null)
                                    for (int i = 0; i < children.length(); i++) {
                                        JSONObject child = children.getJSONObject(i);
                                        if (child.optBoolean("archived")) archived++;
                                        if (child.optBoolean("archived") != archivedOnly) continue;
                                        if (shown++ > 0) divider(list);
                                        list.addView(
                                                childRow(
                                                        child,
                                                        child.optString("id")
                                                                .equals(source.childId),
                                                        archivedOnly
                                                                ? () ->
                                                                        archive(
                                                                                child, panel,
                                                                                requested, source)
                                                                : () ->
                                                                        babyHost.selectBaby(
                                                                                child.optString(
                                                                                        "id")),
                                                        roster,
                                                        true));
                                    }
                                if (shown == 0) {
                                    note(list, archivedOnly ? "没有收起的宝宝相册" : "还没有宝宝相册");
                                    if (!archivedOnly)
                                        note(
                                                list,
                                                familyAdmin() ? "添加宝宝，开始记录成长。" : "使用家人发来的邀请码加入。");
                                }
                                if (!archivedOnly && familyAdmin() && archived > 0)
                                    list.addView(
                                            valueRow(
                                                    "storage",
                                                    "已收起的相册",
                                                    String.valueOf(archived),
                                                    () -> showList(true)));
                                if (archivedOnly && shown > 0) note(list, "照片和家人权限仍然保留，点开即可恢复。");
                            },
                            error -> {
                                if (ticket != babyListRequest || !current(panel, requested, source))
                                    return;
                                if (done != null) {
                                    done.accept(false);
                                    return;
                                }
                                status.setTextColor(Ui.ERROR);
                                status.setText(error);
                                textAction(list, "重新读取", () -> showList(archivedOnly));
                            });
                };
        enableListRefresh(panel, reload);
        closeWithPage(panel, roster);
        reload.run(null);
    }

    private ImageLoader rosterLoader(ApiClient source) {
        ApiClient copy = source.snapshot();
        copy.childId = "";
        return new ImageLoader(activity, copy);
    }

    private void closeWithPage(View panel, ImageLoader loader) {
        panel.addOnAttachStateChangeListener(
                new View.OnAttachStateChangeListener() {
                    public void onViewAttachedToWindow(View view) {}

                    public void onViewDetachedFromWindow(View view) {
                        loader.close();
                    }
                });
    }

    private View childRow(
            JSONObject child, boolean selected, Runnable click, ImageLoader loader, boolean more) {
        LinearLayout row = Ui.row(activity);
        row.setPadding(dp(8), dp(14), dp(4), dp(14));
        LinearLayout identity = Ui.row(activity);
        identity.addView(avatarPhoto(child.optString("avatar_url"), 54, loader));
        LinearLayout words = Ui.column(activity);
        words.setPadding(dp(12), 0, dp(6), 0);
        TextView name = text(child.optString("name", "宝宝"), 18, Ui.INK);
        name.setTypeface(null, Typeface.BOLD);
        words.addView(name);
        TextView age =
                text(
                        child.optBoolean("archived") ? "照片仍然保留" : child.optString("age", ""),
                        13,
                        Ui.MUTED);
        age.setPadding(0, dp(5), 0, 0);
        if (!age.getText().toString().isEmpty()) words.addView(age);
        identity.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        if (selected && !child.optBoolean("archived")) identity.addView(badge("正在看", true));
        else
            identity.addView(
                    new Ui.Icon(activity, "next", Ui.MUTED),
                    new LinearLayout.LayoutParams(dp(16), dp(16)));
        identity.setMinimumHeight(dp(58));
        identity.setBackground(Ui.ripple(selected ? Ui.SOFT : Ui.SURFACE, 16, activity));
        identity.setOnClickListener(v -> click.run());
        row.addView(identity, new LinearLayout.LayoutParams(0, -2, 1));
        JSONObject permissions = child.optJSONObject("permissions");
        boolean canManage =
                familyAdmin() || permissions != null && permissions.optBoolean("can_manage");
        if (more && canManage && !child.optBoolean("archived")) {
            TextView options = text("•••", 17, Ui.MUTED);
            options.setGravity(Gravity.CENTER);
            options.setContentDescription(child.optString("name", "宝宝") + "的更多操作");
            options.setBackground(Ui.ripple(Ui.SURFACE, 22, activity));
            options.setOnClickListener(v -> childActions(child));
            row.addView(options, new LinearLayout.LayoutParams(dp(44), dp(48)));
        }
        return row;
    }

    private FrameLayout avatarPhoto(String url, int size, ImageLoader loader) {
        FrameLayout avatar = new FrameLayout(activity);
        avatar.setLayoutParams(new LinearLayout.LayoutParams(dp(size), dp(size)));
        avatar.setBackground(Ui.round(Ui.SOFT, size / 2f, activity));
        avatar.setClipToOutline(true);
        avatar.addView(
                new Ui.Icon(activity, "baby", Ui.ACCENT, true),
                new FrameLayout.LayoutParams(-1, -1));
        if (url != null && !url.isEmpty() && !url.equals("null")) {
            ImageView picture = new ImageView(activity);
            picture.setScaleType(ImageView.ScaleType.CENTER_CROP);
            avatar.addView(picture, new FrameLayout.LayoutParams(-1, -1));
            loader.load(picture, url, Math.max(180, size * 3));
        }
        return avatar;
    }

    private void childActions(JSONObject child) {
        WarmSheet sheet = new WarmSheet(activity, child.optString("name", "宝宝"));
        sheet.body.addView(
                iconRow(
                        "edit",
                        "编辑宝宝资料",
                        "",
                        () -> {
                            sheet.dismiss();
                            edit(child);
                        }));
        if (familyAdmin())
            sheet.body.addView(
                    iconRow(
                            "storage",
                            "收起宝宝相册",
                            "",
                            () -> {
                                sheet.dismiss();
                                archive(child, null, revision, host.client().snapshot());
                            }));
        sheet.show();
    }

    void edit(JSONObject child) {
        String initialName = child == null ? "" : child.optString("name");
        String initialBirthday = child == null ? "" : child.optString("birthday");
        final boolean[] busy = {false};
        final EditText[] nameHolder = {null};
        final String[] birthday = {initialBirthday}, avatarId = {null};
        Runnable leave =
                () -> {
                    if (busy[0]) {
                        toast("正在保存，请稍等");
                        return;
                    }
                    boolean changed =
                            nameHolder[0] != null
                                    && (!value(nameHolder[0]).equals(initialName)
                                            || !birthday[0].equals(initialBirthday)
                                            || avatarId[0] != null);
                    if (changed) confirmLeave(this::show);
                    else show();
                };
        LinearLayout panel = page("baby_edit", child == null ? "添加宝宝" : "宝宝资料", leave);
        int requested = revision;
        ApiClient source = host.client().snapshot();
        ImageLoader loader = rosterLoader(source);
        closeWithPage(panel, loader);
        LinearLayout portrait = Ui.column(activity);
        portrait.setGravity(Gravity.CENTER_HORIZONTAL);
        portrait.setPadding(0, dp(12), 0, dp(24));
        panel.addView(portrait, new LinearLayout.LayoutParams(-1, -2));
        FrameLayout image = new FrameLayout(activity);
        image.addView(avatarPhoto(child == null ? "" : child.optString("avatar_url"), 104, loader));
        portrait.addView(image, new LinearLayout.LayoutParams(dp(104), dp(104)));
        if (child != null && !child.optBoolean("archived")) {
            TextView change =
                    textAction(
                            portrait,
                            "更换头像",
                            () -> {
                                if (busy[0]) return;
                                chooseAvatar(
                                        child,
                                        chosen -> {
                                            if (!current(panel, requested, source)) return;
                                            avatarId[0] = chosen.optString("id");
                                            image.removeAllViews();
                                            image.addView(
                                                    avatarPhoto(
                                                            chosen.optString("thumbnail_url"),
                                                            104,
                                                            loader));
                                        });
                            });
            image.setContentDescription("更换宝宝头像");
            image.setOnClickListener(v -> change.performClick());
        }
        LinearLayout form = group(panel);
        EditText name = field(form, "宝宝名字", "家人平时怎么称呼宝宝", initialName, false);
        nameHolder[0] = name;
        name.setFilters(
                new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(40)});
        LinearLayout birthRow = Ui.row(activity);
        birthRow.setPadding(dp(2), dp(14), dp(2), dp(14));
        birthRow.addView(
                new Ui.Icon(activity, "calendar", Ui.ACCENT),
                new LinearLayout.LayoutParams(dp(24), dp(24)));
        TextView birthLabel = text("生日", 16, Ui.INK);
        birthLabel.setPadding(dp(12), 0, 0, 0);
        birthRow.addView(birthLabel, new LinearLayout.LayoutParams(0, dp(48), 1));
        TextView birthValue =
                text(initialBirthday.isEmpty() ? "稍后填写" : Ui.date(initialBirthday), 15, Ui.MUTED);
        birthRow.addView(birthValue);
        birthRow.addView(
                new Ui.Icon(activity, "next", Ui.MUTED),
                new LinearLayout.LayoutParams(dp(18), dp(18)));
        birthRow.setBackground(Ui.ripple(Ui.SURFACE, 12, activity));
        birthRow.setOnClickListener(
                v -> {
                    if (!busy[0])
                        birthdayPicker(
                                birthday[0],
                                value -> {
                                    birthday[0] = value;
                                    birthValue.setText(Ui.date(value));
                                });
                });
        form.addView(birthRow);
        note(panel, "生日用于计算成长年龄，可以稍后填写。");
        TextView state = state(panel);
        TextView save = toolbarAction(child == null ? "添加" : "保存", null);
        save.setOnClickListener(
                v -> {
                    if (busy[0]) return;
                    if (value(name).isEmpty()) {
                        state.setText("请填写宝宝名字");
                        name.requestFocus();
                        return;
                    }
                    busy[0] = true;
                    save.setEnabled(false);
                    name.setEnabled(false);
                    state.setText("");
                    JSONObject body = json("name", value(name));
                    try {
                        body.put("birthday", birthday[0]);
                        if (avatarId[0] != null) body.put("avatar_media_id", avatarId[0]);
                    } catch (Exception ignored) {
                    }
                    host.runSettingTask(
                            () ->
                                    source.request(
                                            child == null ? "POST" : "PATCH",
                                            child == null
                                                    ? "/api/children"
                                                    : "/api/children/"
                                                            + ApiClient.encode(
                                                                    child.optString("id")),
                                            body),
                            result -> {
                                if (!current(panel, requested, source)) return;
                                busy[0] = false;
                                toast("宝宝资料已保存");
                                babyHost.childrenChanged(
                                        result.optString(
                                                "id", child == null ? "" : child.optString("id")));
                            },
                            error -> {
                                if (!current(panel, requested, source)) return;
                                busy[0] = false;
                                save.setEnabled(true);
                                name.setEnabled(true);
                                state.setText(error);
                            });
                });
        if (child != null && familyAdmin())
            textAction(
                    panel,
                    "收起宝宝相册",
                    () -> {
                        if (busy[0]) return;
                        if (!value(name).equals(initialName)
                                || !birthday[0].equals(initialBirthday)
                                || avatarId[0] != null)
                            confirmLeave(() -> archive(child, panel, requested, source));
                        else archive(child, panel, requested, source);
                    });
    }

    private void birthdayPicker(String value, Consumer<String> chosen) {
        LocalDate initial;
        try {
            initial = LocalDate.parse(value);
        } catch (Exception ignored) {
            initial = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        }
        WarmSheet sheet = new WarmSheet(activity, "宝宝生日");
        DatePicker picker = new DatePicker(activity);
        picker.setMaxDate(System.currentTimeMillis());
        picker.init(initial.getYear(), initial.getMonthValue() - 1, initial.getDayOfMonth(), null);
        sheet.body.addView(picker, new LinearLayout.LayoutParams(-1, -2));
        sheet.primary(
                "确定",
                () -> {
                    chosen.accept(
                            LocalDate.of(
                                            picker.getYear(),
                                            picker.getMonth() + 1,
                                            picker.getDayOfMonth())
                                    .toString());
                    sheet.dismiss();
                });
        sheet.show();
    }

    private void chooseAvatar(JSONObject child, Consumer<JSONObject> chosen) {
        WarmSheet sheet = new WarmSheet(activity, "选择宝宝头像");
        TextView state = state(sheet.body);
        state.setTextColor(Ui.MUTED);
        state.setText("正在读取照片…");
        ApiClient origin = host.client().snapshot();
        int requested = revision;
        ApiClient scoped = origin.snapshot();
        scoped.childId = child.optString("id");
        ImageLoader loader = new ImageLoader(activity, scoped);
        sheet.onDismiss(loader::close);
        sheet.show();
        host.runSettingTask(
                () -> scoped.request("GET", "/api/media?kind=photo&limit=60", null),
                result -> {
                    if (!sheet.isShowing()) return;
                    if (requested != revision || !sameScope(origin)) {
                        sheet.dismiss();
                        return;
                    }
                    state.setText("");
                    JSONArray photos = result.optJSONArray("items");
                    if (photos == null || photos.length() == 0) {
                        note(sheet.body, "还没有照片，上传后就能选择头像。");
                        return;
                    }
                    LinearLayout row = null;
                    for (int i = 0; i < photos.length(); i++) {
                        JSONObject photo = photos.getJSONObject(i);
                        if (i % 3 == 0) {
                            row = Ui.row(activity);
                            sheet.body.addView(row);
                        }
                        ImageView image = new ImageView(activity);
                        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                        image.setBackground(Ui.round(Ui.SOFT, 8, activity));
                        image.setClipToOutline(true);
                        LinearLayout.LayoutParams params =
                                new LinearLayout.LayoutParams(0, dp(100), 1);
                        params.setMargins(dp(3), dp(3), dp(3), dp(3));
                        row.addView(image, params);
                        image.setContentDescription("选择这张照片作为头像");
                        image.setOnClickListener(
                                v -> {
                                    if (requested == revision && sameScope(origin))
                                        chosen.accept(photo);
                                    sheet.dismiss();
                                });
                        loader.load(image, photo.optString("thumbnail_url"), 240);
                    }
                    if (photos.length() % 3 != 0)
                        for (int i = photos.length() % 3; i < 3; i++)
                            row.addView(
                                    new View(activity),
                                    new LinearLayout.LayoutParams(0, dp(100), 1));
                    if (result.optBoolean("has_more") || !result.optString("next_cursor").isEmpty())
                        note(sheet.body, "这里展示最近的照片，也可以在照片详情中设为头像。");
                },
                error -> {
                    if (sheet.isShowing() && requested == revision && sameScope(origin)) {
                        state.setTextColor(Ui.ERROR);
                        state.setText(error);
                        textAction(
                                sheet.body,
                                "重新读取",
                                () -> {
                                    sheet.dismiss();
                                    chooseAvatar(child, chosen);
                                });
                    }
                });
    }

    private void archive(JSONObject child, View panel, int requested, ApiClient source) {
        if (!familyAdmin()) return;
        boolean archived = child.optBoolean("archived");
        WarmSheet sheet = new WarmSheet(activity, archived ? "恢复宝宝相册？" : "收起宝宝相册？");
        note(sheet.body, archived ? "恢复后，有权限的家人可以继续查看。" : "照片和家人权限会保留，之后可以恢复。");
        TextView error = state(sheet.body);
        TextView save = sheet.primary(archived ? "恢复相册" : "收起相册", null);
        save.setOnClickListener(
                v -> {
                    if (requested != revision
                            || !sameScope(source)
                            || panel != null && !current(panel, requested, source)) {
                        sheet.dismiss();
                        return;
                    }
                    save.setEnabled(false);
                    sheet.setCancelable(false);
                    error.setText("");
                    host.runSettingTask(
                            () ->
                                    source.request(
                                            "PATCH",
                                            "/api/children/"
                                                    + ApiClient.encode(child.optString("id")),
                                            json("archived", !archived)),
                            result -> {
                                if (!sheet.isShowing()
                                        || requested != revision
                                        || !sameScope(source)) return;
                                sheet.dismiss();
                                babyHost.childrenChanged(
                                        archived ? child.optString("id") : source.childId);
                            },
                            message -> {
                                if (!sheet.isShowing()
                                        || requested != revision
                                        || !sameScope(source)) return;
                                save.setEnabled(true);
                                sheet.setCancelable(true);
                                error.setText(message);
                            });
                });
        sheet.show();
    }

    private void join() {
        WarmSheet sheet = new WarmSheet(activity, "加入宝宝相册");
        ApiClient source = host.client().snapshot();
        int requested = revision;
        note(sheet.body, "输入家人发来的邀请码");
        EditText code = field(sheet.body, "邀请码", "6位数字，也可粘贴完整邀请", "", false);
        code.setRawInputType(InputType.TYPE_CLASS_NUMBER);
        code.setTextSize(24);
        code.setLetterSpacing(.12f);
        TextView state = state(sheet.body);
        TextView paste =
                textAction(
                        sheet.body,
                        "粘贴邀请",
                        () -> {
                            try {
                                ClipboardManager manager =
                                        (ClipboardManager)
                                                activity.getSystemService(
                                                        Context.CLIPBOARD_SERVICE);
                                ClipData clip = manager.getPrimaryClip();
                                if (clip == null
                                        || clip.getItemCount() == 0
                                        || clip.getItemAt(0).getText() == null)
                                    throw new IllegalArgumentException("剪贴板里还没有邀请文字");
                                InvitationText.Values values =
                                        InvitationText.parse(
                                                clip.getItemAt(0).getText().toString());
                                if (!values.server.isEmpty()
                                        && !values.server.equals(source.baseUrl))
                                    throw new IllegalArgumentException(
                                            "这份邀请来自另一台服务器，请到对应服务器登录或注册。");
                                code.setText(values.code);
                                state.setText("");
                            } catch (IllegalArgumentException error) {
                                code.setText("");
                                state.setText(error.getMessage());
                            } catch (Exception error) {
                                code.setText("");
                                state.setText("暂时无法读取剪贴板，请手动粘贴邀请码");
                            }
                        });
        TextView submit = sheet.primary("加入相册", null);
        submit.setOnClickListener(
                v -> {
                    String invitation;
                    try {
                        InvitationText.Values parsed = InvitationText.parse(value(code));
                        if (!parsed.server.isEmpty() && !parsed.server.equals(source.baseUrl))
                            throw new IllegalArgumentException("这份邀请来自另一台服务器，请到对应服务器登录或注册。");
                        invitation = parsed.code;
                    } catch (IllegalArgumentException error) {
                        state.setText(error.getMessage());
                        return;
                    }
                    if (requested != revision || !sameScope(source)) {
                        sheet.dismiss();
                        return;
                    }
                    submit.setEnabled(false);
                    paste.setEnabled(false);
                    code.setEnabled(false);
                    sheet.setCancelable(false);
                    state.setText("");
                    host.runSettingTask(
                            () ->
                                    AccountScreens.requestSession(
                                            source,
                                            "/api/invitations/join",
                                            json("code", invitation)),
                            result -> {
                                if (!sheet.isShowing()
                                        || requested != revision
                                        || !sameScope(source)) return;
                                if (AccountScreens.acceptSessionIfComplete(
                                        host, source.baseUrl, result, true)) {
                                    code.setText("");
                                    sheet.dismiss();
                                    toast("已加入宝宝相册");
                                } else {
                                    sheet.setCancelable(true);
                                    code.setEnabled(true);
                                    paste.setEnabled(true);
                                    submit.setEnabled(true);
                                    state.setText("加入结果暂时无法确认，邀请码可能已使用。请重新登录后查看宝宝列表。");
                                    submit.setText("重新登录");
                                    submit.setOnClickListener(
                                            view -> {
                                                sheet.dismiss();
                                                host.signInPage();
                                            });
                                }
                            },
                            error -> {
                                if (!sheet.isShowing()
                                        || requested != revision
                                        || !sameScope(source)) return;
                                submit.setEnabled(true);
                                paste.setEnabled(true);
                                code.setEnabled(true);
                                sheet.setCancelable(true);
                                state.setText(error);
                            });
                });
        note(sheet.footer, "加入当前服务器的相册").setGravity(Gravity.CENTER);
        sheet.show();
    }

    private void confirmLeave(Runnable leave) {
        WarmSheet sheet = new WarmSheet(activity, "还有修改没有保存");
        note(sheet.body, "离开后，这次修改不会保留。");
        sheet.primary("继续编辑", sheet::dismiss);
        sheet.secondary(
                "放弃修改并离开",
                () -> {
                    sheet.dismiss();
                    leave.run();
                });
        sheet.show();
    }

    private void toast(String message) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
    }
}
