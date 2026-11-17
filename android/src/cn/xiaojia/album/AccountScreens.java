package cn.xiaojia.album;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

/** Sign-in and account changes, with one focused step at a time. */
final class AccountScreens extends SettingsScreen {
    private String loginAddress;
    private String suggestedUsername;

    AccountScreens(Host host) {
        super(host);
    }

    private boolean current(View panel, int requested, ApiClient source) {
        ApiClient now = host.client();
        return revision == requested
                && panel.isAttachedToWindow()
                && source.baseUrl.equals(now.baseUrl)
                && source.token.equals(now.token);
    }

    private void toast(String message) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
    }

    void showConnection(Runnable reconnect) {
        LinearLayout panel = page("server_connection", "服务器连接", host::accountHome);
        ApiClient source = host.client().snapshot();
        LinearLayout current = group(panel);
        current.addView(valueRow("server", "当前服务器", "", null));
        TextView address = note(current, source.baseUrl);
        address.setTextIsSelectable(true);
        note(panel, "手机通过这个地址访问相册。");
        button(panel, "重新连接", true, reconnect);
        textAction(
                panel,
                "更换服务器",
                () -> {
                    WarmSheet sheet = new WarmSheet(activity, "更换服务器？");
                    note(sheet.body, "需要使用对应服务器的账号登录，原相册仍会保留。");
                    sheet.primary(
                            "继续",
                            () -> {
                                sheet.dismiss();
                                loginAddress = "";
                                showLogin();
                            });
                    sheet.secondary("取消", sheet::dismiss);
                    sheet.show();
                });
    }

    void showLogin() {
        if (loginAddress == null) loginAddress = host.client().baseUrl;
        LinearLayout panel = page("login", "FAM宝贝相册", null);
        int requested = revision;
        ApiClient origin = host.client().snapshot();
        boolean[] busy = {false};
        LinearLayout serverCard = group(panel);
        LinearLayout serverHeading = Ui.row(activity);
        serverHeading.addView(
                new Ui.Icon(activity, "server", Ui.ACCENT, true),
                new LinearLayout.LayoutParams(dp(42), dp(42)));
        TextView serverLabel = text(loginAddress.isEmpty() ? "填写相册服务器" : "已填写服务器地址", 16, Ui.INK);
        serverLabel.setPadding(dp(12), 0, 0, 0);
        serverHeading.addView(serverLabel, new LinearLayout.LayoutParams(0, dp(58), 1));
        TextView change = text("更换", 14, Ui.ACCENT_DARK);
        change.setGravity(Gravity.CENTER);
        change.setMinHeight(dp(48));
        serverHeading.addView(change, new LinearLayout.LayoutParams(dp(48), dp(48)));
        serverCard.addView(serverHeading);
        TextView addressLabel = note(serverCard, loginAddress);
        addressLabel.setTextIsSelectable(true);
        TextView state = state(panel);
        change.setOnClickListener(
                v -> {
                    if (busy[0]) return;
                    editAddress(
                            loginAddress,
                            address -> {
                                loginAddress = address;
                                serverLabel.setText("已填写服务器地址");
                                addressLabel.setText(address);
                                state.setText("");
                            });
                });
        if (loginAddress.isEmpty()) {
            change.setText("填写");
            addressLabel.setText("使用家人提供的相册地址");
        }
        LinearLayout form = group(panel);
        EditText username =
                field(
                        form,
                        "用户名",
                        "输入用户名",
                        suggestedUsername == null
                                ? activity.getSharedPreferences("album", 0)
                                        .getString("username", "")
                                : suggestedUsername,
                        false);
        suggestedUsername = null;
        EditText password = field(form, "密码", "输入密码", "", true);
        TextView login = button(panel, "登录", true, null);
        TextView join =
                textAction(
                        panel,
                        "用邀请码加入",
                        () -> {
                            if (!busy[0]) showJoin(new JoinDraft(loginAddress));
                        });
        TextView setup = textAction(panel, "首次管理员设置", null);
        login.setOnClickListener(
                v -> {
                    if (busy[0]) return;
                    if (loginAddress.isEmpty()) {
                        state.setText("请先填写服务器地址");
                        change.performClick();
                        return;
                    }
                    String address = loginAddress,
                            account = value(username),
                            secret = password.getText().toString();
                    busy[0] = true;
                    enable(false, login, join, setup, change, username, password);
                    login.setText("正在登录…");
                    state.setTextColor(Ui.ERROR);
                    state.setText("");
                    host.runSettingTask(
                            () -> {
                                ApiClient candidate = publicClient(address);
                                JSONObject status = candidate.request("GET", "/api/status", null);
                                if (status.optBoolean("setup_required"))
                                    return json("setup_required", true);
                                if (status.optBoolean("default_credentials")
                                        && (account.isEmpty() || secret.isEmpty()))
                                    return json("default_credentials", true);
                                if (account.isEmpty() || secret.isEmpty())
                                    throw new java.io.IOException("请填写用户名和密码");
                                return candidate.request(
                                        "POST",
                                        "/api/login",
                                        json("username", account).put("password", secret));
                            },
                            result -> {
                                if (!current(panel, requested, origin)) return;
                                busy[0] = false;
                                if (result.optBoolean("setup_required")) {
                                    showSetup(address);
                                    return;
                                }
                                if (result.optBoolean("default_credentials")) {
                                    enable(true, login, join, setup, change, username, password);
                                    login.setText("登录");
                                    if (value(username).isEmpty()) username.setText("admin");
                                    state.setTextColor(Ui.MUTED);
                                    state.setText("首次使用的账号和密码都是 admin，登录后需设置自己的密码。");
                                    password.requestFocus();
                                    return;
                                }
                                if (acceptSessionIfComplete(host, address, result, true)) {
                                    password.setText("");
                                } else {
                                    enable(true, login, join, setup, change, username, password);
                                    login.setText("登录");
                                    state.setText("没有收到完整的登录结果，请重新登录。");
                                }
                            },
                            error -> {
                                if (!current(panel, requested, origin)) return;
                                busy[0] = false;
                                enable(true, login, join, setup, change, username, password);
                                login.setText("登录");
                                state.setText(error);
                            });
                });
        setup.setOnClickListener(
                v -> {
                    if (busy[0]) return;
                    if (loginAddress.isEmpty()) {
                        state.setText("请先填写服务器地址");
                        change.performClick();
                        return;
                    }
                    String address = loginAddress;
                    busy[0] = true;
                    enable(false, login, join, setup, change, username, password);
                    state.setTextColor(Ui.MUTED);
                    state.setText("正在检查服务器…");
                    host.runSettingTask(
                            () -> publicClient(address).request("GET", "/api/status", null),
                            result -> {
                                if (!current(panel, requested, origin)) return;
                                busy[0] = false;
                                enable(true, login, join, setup, change, username, password);
                                if (result.optBoolean("setup_required")) showSetup(address);
                                else if (result.optBoolean("default_credentials")) {
                                    if (value(username).isEmpty()) username.setText("admin");
                                    state.setText("首次使用的账号和密码都是 admin，登录后需设置自己的密码。");
                                    password.requestFocus();
                                } else {
                                    state.setTextColor(Ui.ERROR);
                                    state.setText("相册已有管理员，请登录或使用家人的邀请加入。");
                                }
                            },
                            error -> {
                                if (!current(panel, requested, origin)) return;
                                busy[0] = false;
                                enable(true, login, join, setup, change, username, password);
                                state.setTextColor(Ui.ERROR);
                                state.setText(error);
                            });
                });
    }

    private void editAddress(String initial, java.util.function.Consumer<String> chosen) {
        WarmSheet sheet = new WarmSheet(activity, "相册服务器");
        EditText server = field(sheet.body, "服务器地址", "家人提供的相册地址", initial, false);
        server.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        TextView state = state(sheet.body);
        sheet.primary(
                "确定",
                () -> {
                    try {
                        if (value(server).isEmpty()) throw new IllegalArgumentException("请填写服务器地址");
                        String normalized = publicClient(value(server)).baseUrl;
                        chosen.accept(normalized);
                        sheet.dismiss();
                    } catch (Exception error) {
                        state.setText(error.getMessage());
                    }
                });
        sheet.show();
    }

    private ApiClient publicClient(String address) throws Exception {
        ApiClient candidate = new ApiClient(activity);
        candidate.configure(address);
        candidate.token = "";
        return candidate;
    }

    private void showSetup(String address) {
        boolean[] busy = {false};
        LinearLayout panel =
                page(
                        "account_setup",
                        "设置相册管理员",
                        () -> {
                            if (busy[0]) toast("正在创建，请稍等");
                            else showLogin();
                        });
        int requested = revision;
        ApiClient origin = host.client().snapshot();
        LinearLayout form = group(panel);
        EditText code = field(form, "首次设置口令", "服务器连接说明中的设置口令", "", true);
        EditText username = field(form, "用户名", "设置登录用户名", "admin", false);
        EditText name = field(form, "家人怎么称呼你", "例如：爸爸、妈妈", "", false);
        name.setFilters(
                new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(40)});
        EditText password = field(form, "密码", "至少8个字符", "", true);
        EditText confirm = field(form, "确认密码", "再输入一次密码", "", true);
        TextView state = state(panel);
        TextView submit = button(panel, "创建管理员", true, null);
        submit.setOnClickListener(
                v -> {
                    if (busy[0]) return;
                    String secret = password.getText().toString();
                    if (value(code).isEmpty() || value(name).isEmpty()) {
                        state.setText("请填写首次设置口令和家人称呼");
                        return;
                    }
                    if (!registrationValid(
                            value(username), secret, confirm.getText().toString(), state)) return;
                    busy[0] = true;
                    enable(false, submit, code, username, name, password, confirm);
                    state.setText("");
                    submit.setText("正在创建…");
                    JSONObject body = json("setup_code", value(code));
                    try {
                        body.put("username", value(username))
                                .put("password", secret)
                                .put("name", value(name));
                    } catch (Exception ignored) {
                    }
                    host.runSettingTask(
                            () -> requestSession(publicClient(address), "/api/setup", body),
                            result -> {
                                if (!current(panel, requested, origin)) return;
                                if (acceptSessionIfComplete(host, address, result, true)) {
                                    code.setText("");
                                    password.setText("");
                                    confirm.setText("");
                                } else {
                                    busy[0] = false;
                                    enable(true, submit, code, username, name, password, confirm);
                                    state.setText("创建结果暂时无法确认。管理员可能已创建，请用刚设置的账号和密码登录。");
                                    submit.setText("返回登录");
                                    submit.setOnClickListener(
                                            view -> loginWith(address, value(username)));
                                }
                            },
                            error -> {
                                if (!current(panel, requested, origin)) return;
                                busy[0] = false;
                                enable(true, submit, code, username, name, password, confirm);
                                submit.setText("创建管理员");
                                state.setText(error);
                            });
                });
    }

    private static final class JoinDraft {
        String server, code = "", username = "", password = "", confirmation = "", name = "";
        boolean needsName, resultUnconfirmed;

        JoinDraft(String server) {
            this.server = server;
        }

        void clearSecrets() {
            code = password = confirmation = "";
        }
    }

    private void showJoin(JoinDraft draft) {
        LinearLayout panel =
                page(
                        "account_join",
                        "填写家人邀请",
                        () -> {
                            draft.clearSecrets();
                            showLogin();
                        });
        note(panel, "第1步，共2步").setGravity(Gravity.CENTER);
        LinearLayout form = group(panel);
        EditText server = field(form, "服务器地址", "家人邀请中的相册地址", draft.server, false);
        server.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        EditText code = field(form, "邀请码", "6位数字，也可粘贴完整邀请", draft.code, false);
        code.setRawInputType(InputType.TYPE_CLASS_NUMBER);
        code.setTextSize(23);
        code.setLetterSpacing(.1f);
        TextView state = state(panel);
        textAction(
                panel,
                "粘贴家人邀请",
                () -> {
                    try {
                        ClipboardManager manager =
                                (ClipboardManager)
                                        activity.getSystemService(Context.CLIPBOARD_SERVICE);
                        ClipData clip = manager.getPrimaryClip();
                        if (clip == null
                                || clip.getItemCount() == 0
                                || clip.getItemAt(0).getText() == null)
                            throw new IllegalArgumentException("剪贴板里还没有邀请文字");
                        InvitationText.Values invite =
                                InvitationText.parse(clip.getItemAt(0).getText().toString());
                        if (!invite.server.isEmpty()) server.setText(invite.server);
                        code.setText(invite.code);
                        state.setText("");
                    } catch (IllegalArgumentException error) {
                        code.setText("");
                        state.setText(error.getMessage());
                    } catch (Exception error) {
                        code.setText("");
                        state.setText("暂时无法读取剪贴板，请手动粘贴邀请码");
                    }
                });
        button(
                panel,
                "下一步",
                true,
                () -> {
                    try {
                        InvitationText.Values invite = InvitationText.parse(value(code));
                        if (!invite.server.isEmpty()) server.setText(invite.server);
                        if (value(server).isEmpty()) {
                            state.setText("请填写相册服务器地址");
                            return;
                        }
                        String target = publicClient(value(server)).baseUrl;
                        if (!target.equals(draft.server) || !invite.code.equals(draft.code)) {
                            draft.needsName = false;
                            draft.resultUnconfirmed = false;
                            draft.name = "";
                        }
                        draft.server = target;
                        draft.code = invite.code;
                        showJoinAccount(draft);
                    } catch (Exception error) {
                        state.setText(error.getMessage());
                    }
                });
        textAction(
                panel,
                "已有账号？返回登录",
                () -> {
                    loginAddress = value(server);
                    draft.clearSecrets();
                    showLogin();
                });
    }

    private void showJoinAccount(JoinDraft draft) {
        boolean[] busy = {false};
        final EditText[] inputs = new EditText[4];
        Runnable remember =
                () -> {
                    if (inputs[0] == null) return;
                    draft.username = value(inputs[0]);
                    draft.password = inputs[1].getText().toString();
                    draft.confirmation = inputs[2].getText().toString();
                    draft.name = value(inputs[3]);
                };
        Runnable back =
                () -> {
                    if (busy[0]) toast("正在加入，请稍等");
                    else {
                        remember.run();
                        showJoin(draft);
                    }
                };
        LinearLayout panel = page("account_join_create", "创建账号", back);
        int requested = revision;
        ApiClient origin = host.client().snapshot();
        note(panel, "第2步，共2步").setGravity(Gravity.CENTER);
        LinearLayout summary = group(panel);
        summary.addView(valueRow("mail", "邀请码已填写", "修改", back));
        LinearLayout form = group(panel);
        EditText username = field(form, "用户名", "设置登录用户名", draft.username, false);
        EditText password = field(form, "密码", "至少8个字符", draft.password, true);
        EditText confirm = field(form, "确认密码", "再输入一次密码", draft.confirmation, true);
        LinearLayout nameGroup = Ui.column(activity);
        form.addView(nameGroup);
        EditText name = field(nameGroup, "家人怎么称呼你", "这份旧邀请还没有填写称呼", draft.name, false);
        name.setFilters(
                new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(40)});
        nameGroup.setVisibility(draft.needsName ? View.VISIBLE : View.GONE);
        inputs[0] = username;
        inputs[1] = password;
        inputs[2] = confirm;
        inputs[3] = name;
        TextView state = state(panel);
        TextView submit = button(panel, draft.resultUnconfirmed ? "返回登录" : "加入相册", true, null);
        if (draft.resultUnconfirmed) state.setText("加入结果暂时无法确认，请用刚设置的账号和密码登录。");
        TextView login =
                textAction(
                        panel,
                        "已有账号？返回登录",
                        () -> {
                            if (!busy[0]) {
                                loginAddress = draft.server;
                                draft.clearSecrets();
                                showLogin();
                            }
                        });
        submit.setOnClickListener(
                v -> {
                    if (busy[0]) return;
                    if (draft.resultUnconfirmed) {
                        loginWith(draft.server, value(username));
                        return;
                    }
                    String secret = password.getText().toString();
                    if (!registrationValid(
                            value(username), secret, confirm.getText().toString(), state)) return;
                    if (draft.needsName && value(name).isEmpty()) {
                        state.setText("请补充家人称呼");
                        name.requestFocus();
                        return;
                    }
                    remember.run();
                    busy[0] = true;
                    enable(false, username, password, confirm, name, submit, login);
                    submit.setText("正在加入…");
                    state.setText("");
                    JSONObject body = json("code", draft.code);
                    try {
                        body.put("username", value(username)).put("password", secret);
                        if (draft.needsName) body.put("name", value(name));
                    } catch (Exception ignored) {
                    }
                    host.runSettingTask(
                            () ->
                                    requestSession(
                                            publicClient(draft.server),
                                            "/api/invitations/accept",
                                            body),
                            result -> {
                                if (!current(panel, requested, origin)) return;
                                if (acceptSessionIfComplete(host, draft.server, result, true)) {
                                    password.setText("");
                                    confirm.setText("");
                                    draft.clearSecrets();
                                } else {
                                    busy[0] = false;
                                    draft.resultUnconfirmed = true;
                                    enable(true, username, password, confirm, name, submit, login);
                                    submit.setText("返回登录");
                                    state.setText("加入结果暂时无法确认，邀请码可能已使用。请用刚设置的账号和密码登录。");
                                }
                            },
                            error -> {
                                if (!current(panel, requested, origin)) return;
                                busy[0] = false;
                                enable(true, username, password, confirm, name, submit, login);
                                submit.setText("加入相册");
                                if ("请填写四十字以内的家人称呼。".equals(error)) {
                                    draft.needsName = true;
                                    nameGroup.setVisibility(View.VISIBLE);
                                    state.setText("这份旧邀请没有称呼，请补充后再加入。");
                                    name.requestFocus();
                                } else state.setText(error);
                            });
                });
    }

    private boolean registrationValid(
            String username, String password, String confirmation, TextView state) {
        if (!validUsername(username)) {
            state.setText("用户名需为3～32位，可用字母、数字、点、下划线或短横线");
            return false;
        }
        if (!validPassword(password)) {
            state.setText("密码需为8～200个字符");
            return false;
        }
        if (!password.equals(confirmation)) {
            state.setText("两次输入的密码不一致");
            return false;
        }
        return true;
    }

    void showAccount() {
        if (host.identity().optBoolean("password_change_required")) {
            showRequiredPassword();
            return;
        }
        LinearLayout panel = page("account", "账号与安全", host::accountHome);
        JSONObject me = host.identity();
        LinearLayout identity = Ui.row(activity);
        identity.setPadding(dp(4), dp(14), dp(4), dp(25));
        identity.addView(avatar(me.optString("name", "家人"), 64));
        LinearLayout words = Ui.column(activity);
        words.setPadding(dp(14), 0, 0, 0);
        TextView name = text(me.optString("name", "家人"), 21, Ui.INK);
        name.setTypeface(null, Typeface.BOLD);
        words.addView(name);
        LinearLayout.LayoutParams roleParams = new LinearLayout.LayoutParams(-2, -2);
        roleParams.topMargin = dp(7);
        words.addView(badge(me.optString("role_label", "家人"), true), roleParams);
        identity.addView(words);
        panel.addView(identity);
        LinearLayout settings = group(panel);
        settings.addView(valueRow("me", "用户名", me.optString("username"), this::showUsername));
        divider(settings);
        settings.addView(valueRow("lock", "修改密码", "", this::showPassword));
        note(panel, "称呼在家人信息里修改");
        textAction(
                        panel,
                        "退出登录",
                        () -> {
                            WarmSheet sheet = new WarmSheet(activity, "退出当前账号？");
                            note(sheet.body, "服务器上的照片和视频会保留。");
                            sheet.primary(
                                    "退出登录",
                                    () -> {
                                        sheet.dismiss();
                                        host.signOut();
                                    });
                            sheet.secondary("取消", sheet::dismiss);
                            sheet.show();
                        })
                .setTextColor(Ui.MUTED);
    }

    void showRequiredPassword() {
        showPassword(true);
    }

    private void showPassword() {
        showPassword(host.identity().optBoolean("password_change_required"));
    }

    private void showPassword(boolean required) {
        boolean[] busy = {false};
        EditText[] fields = new EditText[3];
        Runnable leave =
                () -> {
                    if (busy[0]) {
                        toast("正在保存，请稍等");
                        return;
                    }
                    if (hasText(fields)) confirmLeave(this::showAccount);
                    else showAccount();
                };
        LinearLayout panel =
                page(
                        required ? "account_first_password" : "account_password",
                        required ? "设置你的密码" : "修改密码",
                        required ? null : leave);
        int requested = revision;
        ApiClient source = host.client().snapshot();
        if (required) note(panel, "首次登录，请先设置自己的密码。");
        LinearLayout form = group(panel);
        EditText old = required ? null : field(form, "当前密码", "输入现在的密码", "", true);
        EditText next = field(form, "新密码", "至少8个字符", "", true);
        EditText confirm = field(form, "确认新密码", "再输入一次新密码", "", true);
        fields[0] = old;
        fields[1] = next;
        fields[2] = confirm;
        TextView state = state(panel);
        TextView submit =
                required ? button(panel, "保存并进入相册", true, null) : toolbarAction("保存", null);
        TextView exit =
                required
                        ? textAction(
                                panel,
                                "退出登录",
                                () -> {
                                    if (!busy[0]) host.signOut();
                                })
                        : null;
        if (!required) note(panel, "修改后，其他设备需要重新登录。");
        submit.setOnClickListener(
                v -> {
                    if (busy[0]) return;
                    String password = next.getText().toString();
                    if ((!required && old.getText().length() == 0) || !validPassword(password)) {
                        state.setText(required ? "请设置8～200个字符的新密码" : "请填写当前密码，新密码需为8～200个字符");
                        return;
                    }
                    if (!password.equals(confirm.getText().toString())) {
                        state.setText("两次输入的新密码不一致");
                        return;
                    }
                    busy[0] = true;
                    enable(false, submit, old, next, confirm, exit);
                    state.setText("");
                    JSONObject body =
                            json("current_password", required ? "admin" : old.getText().toString());
                    try {
                        body.put("new_password", password);
                    } catch (Exception ignored) {
                    }
                    host.runSettingTask(
                            () -> requestSession(source, "/api/me/password", body),
                            result -> {
                                if (!current(panel, requested, source)) return;
                                if (acceptSessionIfComplete(
                                        host, source.baseUrl, result, required)) {
                                    if (old != null) old.setText("");
                                    next.setText("");
                                    confirm.setText("");
                                } else {
                                    busy[0] = false;
                                    enable(true, submit, old, next, confirm, exit);
                                    state.setText("保存结果暂时无法确认，密码可能已更新。请先使用新密码登录，未成功时再尝试原密码。");
                                    submit.setText("去登录");
                                    submit.setOnClickListener(
                                            view ->
                                                    loginWith(
                                                            source.baseUrl,
                                                            host.identity().optString("username")));
                                }
                            },
                            error -> {
                                if (!current(panel, requested, source)) return;
                                busy[0] = false;
                                enable(true, submit, old, next, confirm, exit);
                                state.setText(error);
                            });
                });
    }

    private void showUsername() {
        boolean[] busy = {false};
        EditText[] fields = new EditText[2];
        String previous = host.identity().optString("username");
        Runnable leave =
                () -> {
                    if (busy[0]) {
                        toast("正在保存，请稍等");
                        return;
                    }
                    if (fields[0] != null
                            && (!value(fields[0]).equals(previous)
                                    || fields[1].getText().length() > 0))
                        confirmLeave(this::showAccount);
                    else showAccount();
                };
        LinearLayout panel = page("account_username", "修改用户名", leave);
        int requested = revision;
        ApiClient source = host.client().snapshot();
        TextView current = note(group(panel), "当前用户名：" + previous);
        current.setTextColor(Ui.INK);
        LinearLayout form = group(panel);
        EditText username = field(form, "新用户名", "设置新的登录用户名", previous, false);
        note(form, "3～32位，可用字母、数字、点、下划线或短横线");
        EditText password = field(form, "当前密码", "输入现在的密码", "", true);
        fields[0] = username;
        fields[1] = password;
        TextView state = state(panel);
        TextView submit = toolbarAction("保存", null);
        note(panel, "修改后，其他设备需要重新登录。");
        submit.setOnClickListener(
                v -> {
                    if (busy[0]) return;
                    String value = value(username);
                    if (!validUsername(value)) {
                        state.setText("用户名需为3～32位，可用字母、数字、点、下划线或短横线");
                        return;
                    }
                    if (value.equalsIgnoreCase(previous)) {
                        state.setText("请填写一个新的用户名");
                        return;
                    }
                    if (password.getText().length() == 0) {
                        state.setText("请填写当前密码");
                        return;
                    }
                    busy[0] = true;
                    enable(false, submit, username, password);
                    state.setText("");
                    JSONObject body = json("new_username", value);
                    try {
                        body.put("current_password", password.getText().toString());
                    } catch (Exception ignored) {
                    }
                    host.runSettingTask(
                            () -> requestSession(source, "/api/me/username", body),
                            result -> {
                                if (!current(panel, requested, source)) return;
                                if (acceptSessionIfComplete(host, source.baseUrl, result, false)) {
                                    password.setText("");
                                } else {
                                    busy[0] = false;
                                    enable(true, submit, username, password);
                                    state.setText("保存结果暂时无法确认，用户名可能已更新。请先使用新用户名登录，未成功时再尝试原用户名。");
                                    submit.setText("去登录");
                                    submit.setOnClickListener(
                                            view -> loginWith(source.baseUrl, value));
                                }
                            },
                            error -> {
                                if (!current(panel, requested, source)) return;
                                busy[0] = false;
                                enable(true, submit, username, password);
                                state.setText(error);
                            });
                });
    }

    /** A rejected HTTP request is safe to correct; an interrupted response may have committed. */
    static JSONObject requestSession(ApiClient source, String path, JSONObject body)
            throws Exception {
        try {
            return source.request("POST", path, body);
        } catch (ApiClient.HttpError rejected) {
            if (rejected.status >= 500 || rejected.status == 408) return new JSONObject();
            throw rejected;
        } catch (java.io.IOException | org.json.JSONException uncertain) {
            return new JSONObject();
        }
    }

    static boolean acceptSessionIfComplete(
            Host host, String address, JSONObject session, boolean album) {
        if (session == null
                || !(session.opt("token") instanceof String)
                || session.optString("token").trim().isEmpty()) return false;
        JSONObject member = session.optJSONObject("member");
        if (member == null
                || !(member.opt("id") instanceof String)
                || member.optString("id").trim().isEmpty()
                || !(member.opt("username") instanceof String)
                || member.optString("username").trim().isEmpty()) return false;
        try {
            host.acceptSession(address, session, album);
            return true;
        } catch (Exception error) {
            return false;
        }
    }

    private void loginWith(String address, String username) {
        loginAddress = address;
        suggestedUsername = username;
        host.signInPage();
    }

    private boolean hasText(EditText[] fields) {
        for (EditText field : fields)
            if (field != null && field.getText().length() > 0) return true;
        return false;
    }

    private void enable(boolean value, View... views) {
        for (View view : views) if (view != null) view.setEnabled(value);
    }

    private void confirmLeave(Runnable leave) {
        WarmSheet sheet = new WarmSheet(activity, "还有修改没有保存");
        note(sheet.body, "离开后，这次输入不会保留。");
        sheet.primary("继续编辑", sheet::dismiss);
        sheet.secondary(
                "放弃修改并离开",
                () -> {
                    sheet.dismiss();
                    leave.run();
                });
        sheet.show();
    }
}
