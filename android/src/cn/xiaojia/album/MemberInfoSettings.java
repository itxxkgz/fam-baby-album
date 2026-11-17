package cn.xiaojia.album;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.InputFilter;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;

/** A family member's relationship and access belong to the current baby only. */
final class MemberInfoSettings extends SettingsScreen {
    private final String memberId;
    private final String childId;
    private final Runnable back;
    private ApiClient source;
    private String actorId, childName;
    private JSONObject person;
    private MemberAccessState access;
    private LinearLayout panel, body;
    private TextView title, relationshipValue, edit, status, remove, retry;
    private Switch management, upload;
    private View relationshipRow;
    private WarmSheet editor, removal;
    private boolean binding;

    MemberInfoSettings(Host host, String memberId, Runnable back) {
        super(host);
        this.memberId = memberId;
        this.childId = host.client().childId;
        this.back = back;
    }

    void show() {
        if (!childId.equals(host.client().childId)) return;
        source = host.client().snapshot();
        actorId = host.identity().optString("id");
        childName = host.family().optString("child_name", "宝宝");
        person = null;
        access = null;
        panel = page("family_member_info", "家人信息", back);
        body = Ui.column(activity);
        panel.addView(body);
        status = state(panel);
        status.setTextColor(Ui.MUTED);
        status.setText("正在读取家人信息…");
        retry = button(panel, "重新读取", false, this::show);
        retry.setVisibility(View.GONE);
        remove = footerButton("移出宝宝相册", this::confirmRemove);
        remove.setBackground(Ui.button(activity, false));
        remove.setTextColor(Ui.ACCENT_DARK);
        remove.setEnabled(false);
        remove.setVisibility(View.GONE);
        int requested = revision;
        ApiClient requestClient = source;
        LinearLayout target = panel;
        target.addOnAttachStateChangeListener(
                new View.OnAttachStateChangeListener() {
                    public void onViewAttachedToWindow(View view) {}

                    public void onViewDetachedFromWindow(View view) {
                        if (editor != null) editor.dismiss();
                        if (removal != null) removal.dismiss();
                    }
                });
        host.runSettingTask(
                () -> readMember(requestClient),
                result -> {
                    if (!current(requested)) return;
                    person = result;
                    access = new MemberAccessState(result.optString("role"), canChange(result));
                    buildInformation();
                    status.setText("");
                    bind();
                },
                error -> {
                    if (!current(requested)) return;
                    status.setTextColor(Ui.ERROR);
                    status.setText(error);
                    retry.setVisibility(View.VISIBLE);
                });
    }

    private boolean current(int requested) {
        ApiClient now = host.client();
        return requested == revision
                && panel != null
                && panel.isAttachedToWindow()
                && !activity.isFinishing()
                && !activity.isDestroyed()
                && source.baseUrl.equals(now.baseUrl)
                && source.token.equals(now.token)
                && source.childId.equals(now.childId);
    }

    private String membersPath() {
        return "/api/children/" + ApiClient.encode(childId) + "/members";
    }

    private String memberPath() {
        return membersPath() + "/" + ApiClient.encode(memberId);
    }

    private JSONObject readMember(ApiClient client) throws Exception {
        JSONArray members = client.request("GET", membersPath(), null).optJSONArray("items");
        if (members != null)
            for (int i = 0; i < members.length(); i++) {
                JSONObject member = members.optJSONObject(i);
                if (member != null && memberId.equals(member.optString("id"))) return member;
            }
        throw new IOException("这位家人已不在当前宝宝的相册中，请返回家人列表查看。");
    }

    private boolean protectedAccount(JSONObject member) {
        return actorId.equals(memberId) || member.optBoolean("is_family_admin");
    }

    private boolean canChange(JSONObject member) {
        return !protectedAccount(member) && member.optBoolean("can_change_role");
    }

    private boolean canRemove(JSONObject member) {
        return !protectedAccount(member) && member.optBoolean("can_remove");
    }

    private String relation(JSONObject member) {
        String value =
                MemberAccessState.relationship(
                        member.optString("relationship", member.optString("name", "家人")));
        return value.isEmpty() ? "家人" : value;
    }

    private void buildInformation() {
        body.removeAllViews();
        LinearLayout identity = Ui.row(activity);
        identity.setPadding(0, dp(16), 0, dp(24));
        identity.addView(avatar(relation(person), 68));
        LinearLayout labels = Ui.column(activity);
        labels.setPadding(dp(16), 0, 0, 0);
        title = text(relation(person), 24, Ui.INK);
        title.setTypeface(null, Typeface.BOLD);
        labels.addView(title);
        TextView subtitle = text(childName + "的家人", 15, Ui.MUTED);
        subtitle.setPadding(0, dp(6), 0, 0);
        labels.addView(subtitle);
        identity.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        body.addView(identity);
        divider(body);
        TextView relationLabel = text("和宝宝的关系", 14, Ui.MUTED);
        relationLabel.setPadding(0, dp(22), 0, 0);
        body.addView(relationLabel);
        LinearLayout relationLine = Ui.row(activity);
        relationLine.setMinimumHeight(dp(62));
        relationshipValue = text(relation(person), 19, Ui.INK);
        relationshipValue.setTypeface(null, Typeface.BOLD);
        relationLine.addView(relationshipValue, new LinearLayout.LayoutParams(0, -2, 1));
        edit = text("修改  ›", 16, Ui.MUTED);
        edit.setGravity(Gravity.CENTER);
        relationLine.addView(edit, new LinearLayout.LayoutParams(dp(72), dp(48)));
        relationLine.setBackground(Ui.ripple(Ui.BG, 12, activity));
        relationLine.setOnClickListener(v -> editRelationship());
        relationshipRow = relationLine;
        body.addView(relationLine);
        divider(body);
        TextView permissions = text("权限管理", 14, Ui.MUTED);
        permissions.setPadding(0, dp(23), 0, dp(12));
        body.addView(permissions);
        management = permissionRow("people", "管理", "邀请家人，整理相册和资料");
        upload = permissionRow("photo", "上传", "上传照片和视频");
        management.setOnCheckedChangeListener(
                (button, enabled) -> {
                    if (!binding) changePermission(true, enabled);
                });
        upload.setOnCheckedChangeListener(
                (button, enabled) -> {
                    if (!binding) changePermission(false, enabled);
                });
        if (person.optBoolean("is_family_admin")) note(body, "家庭管理员可以管理全部宝宝，不能在这里调整管理权限或移出。");
        else if (actorId.equals(memberId)) note(body, "这是你当前的账号。为保留访问权限，不能在这里调整自己的权限或移出自己。");
        if (person.optBoolean("disabled")) note(body, "这个账号已停用，启用前无法登录相册。");
    }

    private Switch permissionRow(String icon, String heading, String detail) {
        LinearLayout row = Ui.row(activity);
        row.setPadding(0, dp(13), 0, dp(13));
        row.addView(
                new Ui.Icon(activity, icon, Ui.ACCENT),
                new LinearLayout.LayoutParams(dp(28), dp(28)));
        LinearLayout labels = Ui.column(activity);
        labels.setPadding(dp(14), 0, dp(10), 0);
        TextView title = text(heading, 19, Ui.INK);
        title.setTypeface(null, Typeface.BOLD);
        labels.addView(title);
        TextView explanation = text(detail, 13, Ui.MUTED);
        explanation.setPadding(0, dp(4), 0, 0);
        labels.addView(explanation);
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        Switch toggle = new Switch(activity);
        toggle.setShowText(false);
        toggle.setSwitchMinWidth(dp(52));
        toggle.setMinHeight(dp(48));
        toggle.setContentDescription(heading + "权限，" + detail);
        toggle.setThumbTintList(ColorStateList.valueOf(Color.WHITE));
        toggle.setTrackTintList(
                new ColorStateList(
                        new int[][] {new int[] {android.R.attr.state_checked}, new int[] {}},
                        new int[] {Ui.ACCENT, 0xFFD7D0CC}));
        row.addView(toggle, new LinearLayout.LayoutParams(-2, dp(48)));
        body.addView(row);
        return toggle;
    }

    private void bind() {
        if (access == null || person == null) return;
        binding = true;
        title.setText(relation(person));
        relationshipValue.setText(relation(person));
        boolean mayEdit = person.optBoolean("can_edit_relationship") && !access.busy();
        relationshipRow.setEnabled(mayEdit);
        edit.setVisibility(person.optBoolean("can_edit_relationship") ? View.VISIBLE : View.GONE);
        edit.setAlpha(mayEdit ? 1f : .45f);
        management.setChecked(access.managementOn());
        upload.setChecked(access.uploadOn());
        management.setEnabled(access.managementEnabled());
        upload.setEnabled(access.uploadEnabled());
        management.setAlpha(management.isEnabled() ? 1f : .5f);
        upload.setAlpha(upload.isEnabled() ? 1f : .5f);
        remove.setVisibility(canRemove(person) ? View.VISIBLE : View.GONE);
        remove.setEnabled(canRemove(person) && !access.busy());
        binding = false;
    }

    private void changePermission(boolean managementToggle, boolean enabled) {
        if (!current(revision)
                || access == null
                || !access.beginToggle(managementToggle, enabled)) {
            bind();
            return;
        }
        int requested = revision;
        ApiClient requestClient = source;
        bind();
        status.setTextColor(Ui.MUTED);
        status.setText("正在保存…");
        retry.setVisibility(View.GONE);
        host.runSettingTask(
                () -> {
                    JSONObject latest = readMember(requestClient);
                    if (!canChange(latest)) throw new IOException("这位家人的权限已变化，请重新读取后查看。");
                    String previous = latest.optString("role", "viewer");
                    String next =
                            managementToggle
                                    ? MemberAccessState.management(previous, enabled)
                                    : MemberAccessState.upload(previous, enabled);
                    if (!previous.equals(next))
                        requestClient.request("PUT", memberPath(), json("role", next));
                    latest.put("role", next);
                    return latest;
                },
                result -> {
                    if (!current(requested)) return;
                    person = result;
                    access.complete(result.optString("role"), canChange(result));
                    bind();
                    status.setTextColor(Ui.MUTED);
                    status.setText("已保存");
                },
                error -> {
                    if (!current(requested)) return;
                    access.fail();
                    bind();
                    status.setTextColor(Ui.ERROR);
                    status.setText(error + " 可以重试或重新读取当前权限。");
                    retry.setVisibility(View.VISIBLE);
                });
    }

    private void editRelationship() {
        if (!current(revision)
                || access == null
                || access.busy()
                || !person.optBoolean("can_edit_relationship")) return;
        int requested = revision;
        ApiClient requestClient = source;
        WarmSheet sheet = new WarmSheet(activity, "修改关系");
        editor = sheet;
        EditText input = field(sheet.body, "和宝宝的关系", "例如：奶奶、小姨", relation(person), false);
        input.setFilters(new InputFilter[] {new InputFilter.LengthFilter(40)});
        TextView error = state(sheet.body);
        TextView submit = sheet.primary("保存", null);
        sheet.onDismiss(
                () -> {
                    if (editor == sheet) editor = null;
                });
        submit.setOnClickListener(
                v -> {
                    if (!current(requested) || !sheet.isShowing() || access.busy()) return;
                    String relation = MemberAccessState.relationship(input.getText().toString());
                    if (relation.isEmpty() || relation.codePointCount(0, relation.length()) > 40) {
                        error.setText("请填写1到40个字的关系称呼。");
                        return;
                    }
                    if (!access.beginSave()) return;
                    bind();
                    input.setEnabled(false);
                    submit.setEnabled(false);
                    sheet.setCancelable(false);
                    error.setText("");
                    host.runSettingTask(
                            () ->
                                    requestClient.request(
                                            "PATCH", memberPath(), json("relationship", relation)),
                            result -> {
                                if (!current(requested)) return;
                                person = result;
                                access.complete(result.optString("role"), canChange(result));
                                buildInformation();
                                bind();
                                sheet.dismiss();
                                status.setTextColor(Ui.MUTED);
                                status.setText("关系已更新");
                            },
                            message -> {
                                if (!current(requested)) return;
                                access.fail();
                                bind();
                                if (sheet.isShowing()) {
                                    input.setEnabled(true);
                                    submit.setEnabled(true);
                                    sheet.setCancelable(true);
                                    error.setText(message);
                                } else {
                                    status.setTextColor(Ui.ERROR);
                                    status.setText(message);
                                }
                            });
                });
        sheet.show();
    }

    private void confirmRemove() {
        if (!current(revision) || access == null || access.busy() || !canRemove(person)) return;
        int requested = revision;
        ApiClient requestClient = source;
        WarmSheet sheet = new WarmSheet(activity, "移出宝宝相册？");
        removal = sheet;
        note(sheet.body, "这位家人将不能查看" + childName + "的相册。原照片和留言保留，其他宝宝不受影响。");
        TextView error = state(sheet.body);
        TextView submit = sheet.primary("移出相册", null);
        TextView cancel = sheet.secondary("取消", sheet::dismiss);
        sheet.onDismiss(
                () -> {
                    if (removal == sheet) removal = null;
                });
        submit.setOnClickListener(
                v -> {
                    if (!current(requested) || !sheet.isShowing() || !access.beginSave()) return;
                    bind();
                    submit.setEnabled(false);
                    cancel.setEnabled(false);
                    sheet.setCancelable(false);
                    error.setText("");
                    host.runSettingTask(
                            () -> {
                                JSONObject latest = readMember(requestClient);
                                if (!canRemove(latest))
                                    throw new IOException("这位家人的权限已变化，请重新读取后查看。");
                                return requestClient.request(
                                        "PUT", memberPath(), json("role", JSONObject.NULL));
                            },
                            result -> {
                                if (!current(requested)) return;
                                sheet.dismiss();
                                back.run();
                            },
                            message -> {
                                if (!current(requested)) return;
                                access.fail();
                                bind();
                                submit.setEnabled(true);
                                cancel.setEnabled(true);
                                sheet.setCancelable(true);
                                error.setText(message);
                            });
                });
        sheet.show();
    }
}
