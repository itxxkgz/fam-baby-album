package cn.xiaojia.album;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.function.Consumer;

/** Simple storage setup with separate connected-location details and write-only credentials. */
final class StorageSettings extends SettingsScreen {
    private JSONArray localRoots = new JSONArray();
    private static final String[] PRESETS = {"自己的服务器", "NAS", "阿里云 OSS", "腾讯云 COS", "通用 S3"};
    private static final String[] DESCRIPTIONS = {
        "后端和照片放一起", "使用服务器已挂载的目录", "照片存到阿里云", "照片存到腾讯云", "其他云存储或自建存储"
    };
    private static final String[] PROVIDERS = {"local", "local", "oss", "cos", "s3"};

    StorageSettings(Host host) {
        super(host);
    }

    private boolean live(int expected, View panel) {
        return revision == expected && panel.isAttachedToWindow();
    }

    void show() {
        LinearLayout panel = page("storage_settings", "照片存放", host::accountHome);
        int expected = revision;
        if (!allowed("can_manage_storage")) {
            note(panel, "只有家庭管理员可以设置照片存放位置。");
            return;
        }
        TextView add = toolbarAction("添加", this::chooseStorage);
        add.setEnabled(false);
        TextView status = state(panel);
        status.setTextColor(Ui.MUTED);
        status.setText("正在读取存放位置…");
        LinearLayout list = Ui.column(activity);
        panel.addView(list);
        host.runSettingTask(
                () -> host.client().request("GET", "/api/storage", null),
                result -> {
                    if (!live(expected, panel)) return;
                    status.setText("");
                    localRoots = result.optJSONArray("allowed_local_roots");
                    if (localRoots == null) localRoots = new JSONArray();
                    add.setEnabled(true);
                    JSONArray items = result.optJSONArray("items");
                    if (items == null || items.length() == 0) {
                        summary(list, "storage", "还没有存放位置", "添加一个位置，就能保存新上传的照片。");
                        button(list, "添加存放位置", true, this::chooseStorage);
                        return;
                    }
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject item = items.getJSONObject(i);
                        if (item.optBoolean("is_default")) {
                            LinearLayout current = group(list);
                            current.setBackground(Ui.round(Ui.SOFT, 18, activity));
                            current.addView(
                                    iconRow(
                                            storageIcon(item),
                                            storageName(item),
                                            "当前上传位置 · "
                                                    + item.optString("status_label", "尚未测试")
                                                    + "\n已保存 "
                                                    + item.optInt("media_count")
                                                    + " 项照片和视频",
                                            () -> details(item, "")));
                        }
                    }
                    LinearLayout others = null;
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject item = items.getJSONObject(i);
                        if (item.optBoolean("is_default")) continue;
                        if (others == null) {
                            smallHeading(list, "其他位置");
                            others = group(list);
                        } else divider(others);
                        others.addView(
                                iconRow(
                                        storageIcon(item),
                                        storageName(item),
                                        item.optString("status_label", "尚未测试")
                                                + " · "
                                                + item.optInt("media_count")
                                                + " 项内容",
                                        () -> details(item, "")));
                    }
                },
                message -> {
                    if (!live(expected, panel)) return;
                    status.setTextColor(Ui.ERROR);
                    status.setText(message);
                    textAction(list, "重新读取", this::show);
                });
    }

    private void chooseStorage() {
        LinearLayout panel = page("storage_choose", "添加存放位置", this::show);
        if (!allowed("can_manage_storage")) {
            note(panel, "只有家庭管理员可以添加存放位置。");
            return;
        }
        for (int i = 0; i < PRESETS.length; i++) {
            final int selection = i;
            LinearLayout option = group(panel);
            option.addView(
                    iconRow(
                            presetIcon(i),
                            PRESETS[i],
                            DESCRIPTIONS[i],
                            () -> edit(null, selection, false)));
        }
    }

    private String presetIcon(int preset) {
        return preset == 0 ? "server" : preset == 1 ? "nas" : PROVIDERS[preset];
    }

    private int preset(JSONObject item) {
        String provider = item.optString("provider");
        if (provider.equals("oss")) return 2;
        if (provider.equals("cos")) return 3;
        if (provider.equals("s3")) return 4;
        return item.optString("name").toUpperCase(java.util.Locale.ROOT).contains("NAS") ? 1 : 0;
    }

    private String storageIcon(JSONObject item) {
        return presetIcon(preset(item));
    }

    private String storageName(JSONObject item) {
        String name = item.optString("name", "照片存放位置");
        if (item.optString("id").equals("local-default")
                && item.optString("provider").equals("local")
                && name.equals("本机相册")) return "自己的服务器";
        return name;
    }

    private String storageType(JSONObject item) {
        if (item.optString("provider").equals("local")
                && !item.optString("id").equals("local-default")
                && preset(item) == 0) return "服务器硬盘或 NAS";
        return PRESETS[preset(item)];
    }

    private void details(JSONObject item, String notice) {
        LinearLayout panel = page("storage_detail", storageName(item), this::show);
        final int expected = revision;
        if (!allowed("can_manage_storage")) {
            note(panel, "只有家庭管理员可以管理存放位置。");
            return;
        }
        boolean local = item.optString("provider").equals("local");
        boolean used = item.optInt("media_count") > 0;
        summary(
                panel,
                storageIcon(item),
                item.optBoolean("is_default") ? "当前上传位置" : "已添加的存放位置",
                storageType(item) + " · 已保存 " + item.optInt("media_count") + " 项内容");
        LinearLayout rows = group(panel);
        rows.addView(
                valueRow("edit", "名称", storageName(item), () -> rename(item, panel, expected)));
        divider(rows);
        rows.addView(
                valueRow(
                        "storage",
                        local ? "照片目录" : "存储桶",
                        used ? "已设置" : "查看",
                        () -> showLocation(item)));
        if (used) note(rows, "这里已保存照片，存放地址不能直接更换。");
        divider(rows);
        TextView status = state(panel);
        if (!notice.isEmpty()) {
            status.setTextColor(Ui.SUCCESS);
            status.setText(notice);
        }
        ArrayList<View> actions = new ArrayList<>();
        View connection =
                valueRow(
                        "refresh",
                        "连接状态",
                        item.optString("status_label", "尚未测试"),
                        () -> checkLocation(item, false, panel, expected, status, actions));
        connection.setContentDescription("测试连接，" + item.optString("status_label", "尚未测试"));
        rows.addView(connection);
        actions.add(connection);
        divider(rows);
        if (!local) {
            View keys =
                    valueRow(
                            "security",
                            "访问密钥",
                            item.optBoolean("has_credentials") ? "已保存" : "未设置",
                            () -> edit(item, preset(item), true));
            rows.addView(keys);
            actions.add(keys);
            divider(rows);
        }
        View configuration =
                valueRow("settings", "查看详细设置", "", () -> edit(item, preset(item), false));
        rows.addView(configuration);
        actions.add(configuration);
        // Disable every entry while changing the current location, including rename.
        actions.add(rows);
        if (!item.optBoolean("is_default")) {
            TextView use =
                    footerButton(
                            "用于新上传",
                            () -> {
                                WarmSheet sheet = new WarmSheet(activity, "更换上传位置？");
                                note(
                                        sheet.body,
                                        "之后上传的照片和视频将存到“" + storageName(item) + "”。旧照片保留在原来的位置。");
                                sheet.primary(
                                        "检查并设为上传位置",
                                        () -> {
                                            sheet.dismiss();
                                            if (live(expected, panel))
                                                checkLocation(
                                                        item, true, panel, expected, status,
                                                        actions);
                                        });
                                sheet.secondary("取消", sheet::dismiss);
                                sheet.show();
                            });
            actions.add(use);
        }
    }

    private void checkLocation(
            JSONObject item,
            boolean activate,
            LinearLayout panel,
            int expected,
            TextView state,
            ArrayList<View> actions) {
        if (!live(expected, panel) || !allowed("can_manage_storage")) return;
        for (View action : actions) enable(action, false);
        state.setTextColor(Ui.MUTED);
        state.setText("正在检查连接…");
        if (!actions.isEmpty()) rowValue(actions.get(0), "正在检查…");
        host.runSettingTask(
                () -> {
                    String path = "/api/storage/" + ApiClient.encode(item.optString("id"));
                    JSONObject result = host.client().request("POST", path + "/test", null);
                    JSONObject saved = result.optJSONObject("storage");
                    if (activate) return host.client().request("POST", path + "/default", null);
                    if (saved == null) throw new IOException("检查已完成，请返回列表确认连接状态。");
                    return saved;
                },
                result -> {
                    if (live(expected, panel)) details(result, activate ? "新上传的照片将保存在这里。" : "连接正常");
                },
                message -> {
                    if (!live(expected, panel)) return;
                    for (View action : actions) enable(action, true);
                    if (!actions.isEmpty()) rowValue(actions.get(0), "检查未完成");
                    state.setTextColor(Ui.ERROR);
                    state.setText(message);
                });
    }

    private void rename(JSONObject item, LinearLayout panel, int expected) {
        WarmSheet sheet = new WarmSheet(activity, "修改名称");
        EditText name = compactField(sheet.body, "名称", "给这个位置起个名字", storageName(item), false, 80);
        TextView state = state(sheet.body);
        TextView save = sheet.primary("保存", null);
        save.setOnClickListener(
                v -> {
                    if (!live(expected, panel) || !sheet.isShowing()) {
                        sheet.dismiss();
                        return;
                    }
                    if (value(name).isEmpty()) {
                        state.setText("请填写名称");
                        return;
                    }
                    save.setEnabled(false);
                    name.setEnabled(false);
                    sheet.setCancelable(false);
                    host.runSettingTask(
                            () ->
                                    host.client()
                                            .request(
                                                    "PUT",
                                                    "/api/storage/"
                                                            + ApiClient.encode(
                                                                    item.optString("id")),
                                                    json("name", value(name))),
                            result -> {
                                if (!live(expected, panel)) {
                                    sheet.dismiss();
                                    return;
                                }
                                sheet.dismiss();
                                details(result, "名称已保存");
                            },
                            message -> {
                                if (!live(expected, panel)) {
                                    sheet.dismiss();
                                    return;
                                }
                                if (!sheet.isShowing()) return;
                                sheet.setCancelable(true);
                                save.setEnabled(true);
                                name.setEnabled(true);
                                state.setText(message);
                            });
                });
        sheet.show();
    }

    private void showLocation(JSONObject item) {
        WarmSheet sheet = new WarmSheet(activity, "存放位置");
        if (item.optString("provider").equals("local")) {
            smallHeading(sheet.body, "服务器上的照片文件夹");
            selectable(sheet.body, item.optString("local_path"));
        } else {
            smallHeading(sheet.body, "存储桶");
            selectable(sheet.body, item.optString("bucket"));
            if (!item.optString("prefix").isEmpty()) {
                smallHeading(sheet.body, "照片子目录");
                selectable(sheet.body, item.optString("prefix"));
            }
        }
        if (item.optInt("media_count") > 0) note(sheet.body, "需要换地方保存时，请添加新的存放位置。");
        sheet.primary("知道了", sheet::dismiss);
        sheet.show();
    }

    private void edit(JSONObject existing, int selection, boolean credentialsOnly) {
        String provider = PROVIDERS[selection];
        boolean local = provider.equals("local");
        boolean used = existing != null && existing.optInt("media_count") > 0;
        JSONObject[] saved = {existing};
        StorageSaveState progress =
                new StorageSaveState(existing == null ? "" : existing.optString("id"));
        boolean[] busy = {false};
        Runnable back =
                () -> {
                    if (progress.needsReview()) show();
                    else if (saved[0] != null) details(saved[0], "");
                    else chooseStorage();
                };
        Runnable[] requestBack = {back};
        LinearLayout panel =
                page(
                        "storage_edit",
                        credentialsOnly
                                ? "更新访问密钥"
                                : existing == null ? "添加" + PRESETS[selection] : "详细设置",
                        () -> requestBack[0].run());
        int expected = revision;
        if (!allowed("can_manage_storage")) {
            note(panel, "只有家庭管理员可以设置存放位置。");
            return;
        }
        if (credentialsOnly)
            summary(panel, storageIcon(existing), storageName(existing), "原来的密钥不会显示在这里");
        else if (existing == null && selection == 0)
            summary(panel, "server", "自己的服务器", "后端和照片放在同一台服务器");
        else if (!local)
            panel.addView(valueRow(presetIcon(selection), "服务商", PRESETS[selection], null));
        LinearLayout basic = group(panel);
        EditText name =
                credentialsOnly
                        ? null
                        : compactField(
                                basic,
                                "名称",
                                "给这个位置起个名字",
                                existing == null
                                        ? (selection == 1 ? "家里的 NAS" : PRESETS[selection])
                                        : storageName(existing),
                                false,
                                80);
        EditText path = null, region = null, bucket = null, prefix = null, endpoint = null;
        EditText key = null, secret = null, session = null;
        Spinner addressing = null;
        ArrayList<EditText> locked = new ArrayList<>();
        if (local) {
            String defaultPath = localRoots.length() > 0 ? localRoots.optString(0) : "";
            String initial =
                    existing == null
                            ? (selection == 1 ? "" : defaultPath)
                            : existing.optString("local_path");
            if (selection == 1) {
                divider(basic);
                path = compactField(basic, "挂载目录", "填写服务器上的挂载目录", initial, false, 1024);
                note(basic, "先在服务器挂载 NAS，再填写这里。");
                textAction(panel, "如何准备目录", () -> directoryHelp(true));
            } else {
                divider(basic);
                View directorySummary = valueRow("storage", "照片文件夹", "", null);
                basic.addView(directorySummary);
                LinearLayout manual = Ui.column(activity);
                path = compactField(manual, "文件夹", "填写服务器上的照片目录", initial, false, 1024);
                note(manual, "使用服务器上的文件夹。");
                final EditText directory = path;
                Runnable describeDirectory =
                        () ->
                                rowValue(
                                        directorySummary,
                                        value(directory).isEmpty()
                                                ? "未填写"
                                                : used
                                                        ? "已设置"
                                                        : value(directory).equals(defaultPath)
                                                                ? "默认文件夹"
                                                                : "自定义文件夹");
                describeDirectory.run();
                directory.addTextChangedListener(
                        new TextWatcher() {
                            public void beforeTextChanged(
                                    CharSequence s, int start, int count, int after) {}

                            public void onTextChanged(
                                    CharSequence s, int start, int before, int count) {
                                describeDirectory.run();
                            }

                            public void afterTextChanged(Editable s) {}
                        });
                if (!used && !defaultPath.isEmpty())
                    textAction(manual, "使用默认文件夹", () -> directory.setText(defaultPath));
                textAction(manual, "查看可用目录", () -> directoryHelp(false));
                fold(panel, used ? "查看照片目录" : "手动填写目录", manual, initial.isEmpty());
            }
            locked.add(path);
        } else {
            if (!credentialsOnly) {
                if (provider.equals("s3")) {
                    endpoint =
                            compactField(
                                    basic,
                                    "服务地址",
                                    "例如 https://s3.example.com",
                                    endpointValue(existing, provider),
                                    false,
                                    1024);
                    locked.add(endpoint);
                }
                region =
                        compactField(
                                basic,
                                "所在区域",
                                provider.equals("oss")
                                        ? "例如 cn-hangzhou"
                                        : provider.equals("cos")
                                                ? "例如 ap-guangzhou"
                                                : "例如 us-east-1",
                                value(existing, "region", provider.equals("s3") ? "us-east-1" : ""),
                                false,
                                50);
                bucket =
                        compactField(
                                basic,
                                "存储桶",
                                provider.equals("cos") ? "完整的存储桶名称" : "云控制台中的存储桶名称",
                                value(existing, "bucket", ""),
                                false,
                                63);
                locked.add(region);
                locked.add(bucket);
            }
            boolean hasKeys = existing != null && existing.optBoolean("has_credentials");
            key =
                    compactField(
                            basic,
                            "访问密钥",
                            hasKeys ? "已保存，留空保留" : provider.equals("cos") ? "SecretId" : "访问密钥 ID",
                            "",
                            true,
                            8192);
            secret =
                    compactField(
                            basic,
                            "密钥密码",
                            hasKeys
                                    ? "已保存，留空保留"
                                    : provider.equals("cos") ? "SecretKey" : "与访问密钥配套的密码",
                            "",
                            true,
                            8192);
            if (hasKeys) note(basic, "更换密钥时，两项一起填写。");
            LinearLayout advanced = Ui.column(activity);
            if (!credentialsOnly) {
                prefix =
                        compactField(
                                advanced, "照片子目录", "选填", value(existing, "prefix", ""), false, 600);
                locked.add(prefix);
                if (!provider.equals("s3")) {
                    endpoint =
                            compactField(
                                    advanced,
                                    "服务地址",
                                    "留空时按区域填写",
                                    endpointValue(existing, provider),
                                    false,
                                    1024);
                    locked.add(endpoint);
                }
            }
            session =
                    compactField(
                            advanced, "临时凭证", hasKeys ? "留空保持不变" : "使用临时密钥时填写", "", true, 8192);
            if (provider.equals("s3") && !credentialsOnly) {
                smallHeading(advanced, "访问方式");
                addressing = new Spinner(activity);
                ArrayAdapter<String> adapter =
                        new ArrayAdapter<>(
                                activity,
                                android.R.layout.simple_spinner_item,
                                new String[] {"自动选择", "路径形式（适合自建）", "域名形式"});
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                addressing.setAdapter(adapter);
                String style = value(existing, "addressing_style", "auto");
                addressing.setSelection(style.equals("path") ? 1 : style.equals("virtual") ? 2 : 0);
                addressing.setMinimumHeight(dp(48));
                addressing.setContentDescription("访问方式");
                advanced.addView(addressing, new LinearLayout.LayoutParams(-1, -2));
            }
            fold(panel, "更多连接设置", advanced, false);
        }
        if (used && !credentialsOnly) {
            for (EditText input : locked) input.setEnabled(false);
            if (addressing != null) addressing.setEnabled(false);
            note(panel, "这里已保存照片，可改名称和密钥；换地方请新增位置。");
        }
        TextView state = state(panel);
        TextView submit =
                footerButton(
                        existing == null ? "连接并保存" : credentialsOnly ? "保存并检查连接" : "保存设置", null);
        final EditText pathField = path,
                regionField = region,
                bucketField = bucket,
                prefixField = prefix,
                endpointField = endpoint;
        final EditText keyField = key, secretField = secret, sessionField = session;
        final Spinner addressingField = addressing;
        final EditText[] formFields = {
            name, path, region, bucket, prefix, endpoint, key, secret, session
        };
        ArrayList<String> savedValues = formValues(formFields, addressingField);
        WarmSheet[] leavePrompt = {null};
        Consumer<Runnable> leave =
                destination -> {
                    if (!live(expected, panel)) return;
                    if (busy[0]) {
                        Toast.makeText(activity, "正在保存或检查连接，请稍等", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (progress.needsReview()) {
                        show();
                        return;
                    }
                    if (savedValues.equals(formValues(formFields, addressingField))) {
                        destination.run();
                        return;
                    }
                    if (leavePrompt[0] != null && leavePrompt[0].isShowing()) return;
                    WarmSheet sheet = new WarmSheet(activity, "还没有保存");
                    leavePrompt[0] = sheet;
                    note(sheet.body, "离开后，这次修改不会保留。");
                    sheet.primary("继续编辑", sheet::dismiss);
                    sheet.secondary(
                            "放弃修改",
                            () -> {
                                sheet.dismiss();
                                if (live(expected, panel) && !busy[0]) destination.run();
                            });
                    sheet.show();
                };
        requestBack[0] = () -> leave.accept(back);
        TextView review = textAction(panel, "返回存放位置列表", () -> leave.accept(this::show));
        review.setVisibility(View.GONE);
        submit.setOnClickListener(
                v -> {
                    if (!live(expected, panel)
                            || !allowed("can_manage_storage")
                            || busy[0]
                            || progress.needsReview()) return;
                    JSONObject body;
                    try {
                        body = new JSONObject();
                        if (name != null) {
                            if (value(name).isEmpty()) throw new IOException("请填写存放位置的名称");
                            body.put("name", value(name));
                        }
                        if (!credentialsOnly && !used) {
                            body.put("provider", provider);
                            if (local) {
                                if (value(pathField).isEmpty())
                                    throw new IOException("请填写服务器上的照片文件夹");
                                body.put("local_path", value(pathField));
                            } else {
                                if (value(bucketField).isEmpty()) throw new IOException("请填写存储桶名称");
                                String area = value(regionField), service = value(endpointField);
                                if (!provider.equals("s3") && area.isEmpty())
                                    throw new IOException("请填写云存储所在区域");
                                if (provider.equals("s3") && service.isEmpty())
                                    throw new IOException("请填写存储服务地址");
                                body.put("region", area)
                                        .put("bucket", value(bucketField))
                                        .put("prefix", value(prefixField))
                                        .put("endpoint", service);
                                body.put(
                                        "addressing_style",
                                        addressingField == null
                                                ? "virtual"
                                                : new String[] {"auto", "path", "virtual"}
                                                        [
                                                        addressingField.getSelectedItemPosition()]);
                            }
                        }
                        if (!local) {
                            String access = value(keyField),
                                    password = secretField.getText().toString();
                            boolean hasKeys =
                                    saved[0] != null && saved[0].optBoolean("has_credentials");
                            if (!hasKeys && (access.isEmpty() || password.isEmpty()))
                                throw new IOException("请填写访问密钥和密钥密码");
                            if (access.isEmpty() != password.isEmpty())
                                throw new IOException("更换密钥时，请一起填写两项");
                            if (!access.isEmpty())
                                body.put("access_key_id", access)
                                        .put("secret_access_key", password);
                            if (!value(sessionField).isEmpty())
                                body.put("session_token", value(sessionField));
                        }
                    } catch (Exception failure) {
                        state.setTextColor(Ui.ERROR);
                        state.setText(failure.getMessage());
                        return;
                    }
                    boolean check = existing == null || credentialsOnly;
                    boolean[] savedThisAttempt = {false};
                    ArrayList<String> submittedValues = formValues(formFields, addressingField);
                    busy[0] = true;
                    submit.setEnabled(false);
                    enable(panel, false);
                    state.setTextColor(Ui.MUTED);
                    state.setText(check ? "正在保存并检查连接…" : "正在保存…");
                    host.runSettingTask(
                            () -> {
                                String id = progress.begin();
                                JSONObject result;
                                try {
                                    result =
                                            host.client()
                                                    .request(
                                                            id.isEmpty() ? "POST" : "PUT",
                                                            "/api/storage"
                                                                    + (id.isEmpty()
                                                                            ? ""
                                                                            : "/"
                                                                                    + ApiClient
                                                                                            .encode(
                                                                                                    id)),
                                                            body);
                                    progress.saved(result.optString("id"));
                                    saved[0] = result;
                                    savedThisAttempt[0] = true;
                                } catch (Exception failure) {
                                    progress.failed(
                                            failure instanceof ApiClient.HttpError
                                                    && ((ApiClient.HttpError) failure).status >= 400
                                                    && ((ApiClient.HttpError) failure).status
                                                            < 500);
                                    throw failure;
                                }
                                if (check) {
                                    JSONObject tested =
                                            host.client()
                                                    .request(
                                                            "POST",
                                                            "/api/storage/"
                                                                    + ApiClient.encode(
                                                                            result.optString("id"))
                                                                    + "/test",
                                                            null);
                                    JSONObject testedStorage = tested.optJSONObject("storage");
                                    if (testedStorage == null)
                                        throw new IOException("设置已保存，请返回列表确认连接状态。");
                                    result = testedStorage;
                                    saved[0] = result;
                                }
                                return result;
                            },
                            result -> {
                                if (!live(expected, panel)) return;
                                busy[0] = false;
                                if (keyField != null) {
                                    keyField.setText("");
                                    secretField.setText("");
                                    sessionField.setText("");
                                }
                                details(result, check ? "连接正常，设置已保存。" : "设置已保存");
                            },
                            message -> {
                                if (!live(expected, panel)) return;
                                busy[0] = false;
                                if (savedThisAttempt[0]) {
                                    savedValues.clear();
                                    savedValues.addAll(submittedValues);
                                }
                                enable(panel, true);
                                if (used) {
                                    for (EditText input : locked) input.setEnabled(false);
                                    if (addressingField != null) addressingField.setEnabled(false);
                                }
                                submit.setEnabled(!progress.needsReview());
                                review.setVisibility(
                                        progress.needsReview() || progress.isSaved()
                                                ? View.VISIBLE
                                                : View.GONE);
                                state.setTextColor(Ui.ERROR);
                                state.setText(
                                        progress.needsReview()
                                                ? "暂时无法确认是否保存成功，请返回列表查看，避免重复添加。"
                                                : savedThisAttempt[0] && check
                                                        ? "设置已保存，连接检查未完成。" + message
                                                        : message);
                            });
                });
    }

    private ArrayList<String> formValues(EditText[] fields, Spinner addressing) {
        ArrayList<String> values = new ArrayList<>();
        for (EditText field : fields) values.add(field == null ? "" : field.getText().toString());
        values.add(addressing == null ? "" : String.valueOf(addressing.getSelectedItemPosition()));
        return values;
    }

    private String value(JSONObject object, String key, String fallback) {
        return object == null ? fallback : object.optString(key, fallback);
    }

    private String endpointValue(JSONObject existing, String provider) {
        if (existing == null) return "";
        String endpoint = existing.optString("endpoint");
        String region = existing.optString("region");
        String automatic =
                provider.equals("oss")
                        ? "https://oss-" + region + ".aliyuncs.com"
                        : provider.equals("cos") ? "https://cos." + region + ".myqcloud.com" : "";
        return !automatic.isEmpty() && endpoint.equalsIgnoreCase(automatic) ? "" : endpoint;
    }

    private void directoryHelp(boolean nas) {
        WarmSheet sheet = new WarmSheet(activity, nas ? "准备 NAS 目录" : "可用的照片目录");
        note(sheet.body, nas ? "先在运行相册服务的服务器上挂载 NAS，再把挂载后的文件夹填入相册。" : "这里使用运行相册服务的服务器上的文件夹。");
        if (localRoots.length() > 0) {
            smallHeading(sheet.body, "可使用这些目录或其中的子文件夹");
            for (int i = 0; i < localRoots.length(); i++)
                selectable(sheet.body, localRoots.optString(i));
        } else note(sheet.body, "需要先由部署管理员设置允许使用的目录。");
        sheet.primary("知道了", sheet::dismiss);
        sheet.show();
    }

    private void summary(LinearLayout panel, String icon, String title, String detail) {
        LinearLayout line = Ui.row(activity);
        line.setPadding(0, dp(8), 0, dp(20));
        line.addView(
                new Ui.Icon(activity, icon, Ui.ACCENT, true),
                new LinearLayout.LayoutParams(dp(50), dp(50)));
        LinearLayout copy = Ui.column(activity);
        copy.setPadding(dp(14), 0, 0, 0);
        TextView name = text(title, 17, Ui.INK);
        name.setTypeface(null, Typeface.BOLD);
        copy.addView(name);
        if (!detail.isEmpty()) {
            TextView subtitle = text(detail, 13, Ui.MUTED);
            subtitle.setPadding(0, dp(6), 0, 0);
            copy.addView(subtitle);
        }
        line.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        panel.addView(line);
    }

    private void smallHeading(LinearLayout panel, String title) {
        TextView heading = text(title, 13, Ui.MUTED);
        heading.setPadding(0, dp(20), 0, dp(8));
        panel.addView(heading);
    }

    private void selectable(LinearLayout panel, String value) {
        TextView content = text(value, 15, Ui.INK);
        content.setTextIsSelectable(true);
        content.setPadding(0, dp(8), 0, dp(8));
        panel.addView(content);
    }

    private EditText compactField(
            LinearLayout parent,
            String label,
            String hint,
            String initial,
            boolean secret,
            int limit) {
        LinearLayout line = Ui.row(activity);
        line.setPadding(0, dp(9), 0, dp(9));
        boolean stack =
                activity.getResources().getConfiguration().fontScale > 1.15f
                        || activity.getResources().getConfiguration().screenWidthDp < 360;
        if (stack) line.setOrientation(LinearLayout.VERTICAL);
        TextView title = text(label, 15, Ui.INK);
        title.setTypeface(null, Typeface.BOLD);
        title.setPadding(0, 0, dp(8), stack ? dp(8) : 0);
        line.addView(title, new LinearLayout.LayoutParams(stack ? -1 : dp(88), -2));
        EditText input = new EditText(activity);
        input.setId(View.generateViewId());
        title.setLabelFor(input.getId());
        input.setContentDescription(label);
        input.setSingleLine(true);
        input.setTextSize(15);
        input.setTextColor(
                new ColorStateList(
                        new int[][] {new int[] {-android.R.attr.state_enabled}, new int[] {}},
                        new int[] {Ui.MUTED, Ui.INK}));
        input.setHintTextColor(Ui.MUTED);
        input.setInputType(
                InputType.TYPE_CLASS_TEXT
                        | (secret
                                ? InputType.TYPE_TEXT_VARIATION_PASSWORD
                                : InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS));
        input.setFilters(new InputFilter[] {new InputFilter.LengthFilter(limit)});
        input.setText(initial);
        input.setHint(hint);
        input.setPadding(dp(11), dp(10), dp(11), dp(10));
        input.setMinimumHeight(dp(48));
        input.setBackground(Ui.border(Ui.SURFACE, 10, Ui.LINE, activity));
        LinearLayout controls = Ui.row(activity);
        controls.addView(input, new LinearLayout.LayoutParams(0, -2, 1));
        if (secret) {
            TextView reveal = text("显示", 12, Ui.MUTED);
            reveal.setGravity(Gravity.CENTER);
            reveal.setFocusable(true);
            reveal.setContentDescription("显示" + label);
            reveal.setBackground(Ui.ripple(Ui.SURFACE, 10, activity));
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
            controls.addView(reveal, new LinearLayout.LayoutParams(dp(44), dp(48)));
        }
        line.addView(
                controls,
                stack
                        ? new LinearLayout.LayoutParams(-1, -2)
                        : new LinearLayout.LayoutParams(0, -2, 1));
        parent.addView(line);
        return input;
    }

    private void fold(LinearLayout panel, String title, LinearLayout content, boolean open) {
        boolean[] expanded = {open};
        LinearLayout group = group(panel);
        View toggle = valueRow("settings", title, open ? "收起" : "展开", null);
        toggle.setBackground(Ui.ripple(Color.TRANSPARENT, 12, activity));
        toggle.setFocusable(true);
        toggle.setContentDescription(title + (open ? "，已展开" : "，已收起"));
        group.addView(toggle);
        group.addView(content);
        content.setVisibility(open ? View.VISIBLE : View.GONE);
        toggle.setOnClickListener(
                v -> {
                    expanded[0] = !expanded[0];
                    content.setVisibility(expanded[0] ? View.VISIBLE : View.GONE);
                    toggle.setContentDescription(title + (expanded[0] ? "，已展开" : "，已收起"));
                    // Replace only the short label, without rebuilding the form or losing input.
                    if (toggle instanceof ViewGroup)
                        updateFoldLabel((ViewGroup) toggle, expanded[0]);
                });
    }

    private void updateFoldLabel(ViewGroup group, boolean expanded) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof TextView) {
                TextView label = (TextView) child;
                if (label.getText().toString().equals("展开")
                        || label.getText().toString().equals("收起"))
                    label.setText(expanded ? "收起" : "展开");
            } else if (child instanceof ViewGroup) updateFoldLabel((ViewGroup) child, expanded);
        }
    }

    private void enable(View view, boolean enabled) {
        view.setEnabled(enabled);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) enable(group.getChildAt(i), enabled);
        }
    }

    private void rowValue(View view, String value) {
        if (!(view instanceof ViewGroup)) return;
        ViewGroup row = (ViewGroup) view;
        for (int i = row.getChildCount() - 1; i >= 0; i--) {
            if (row.getChildAt(i) instanceof TextView) {
                ((TextView) row.getChildAt(i)).setText(value);
                return;
            }
        }
    }
}
