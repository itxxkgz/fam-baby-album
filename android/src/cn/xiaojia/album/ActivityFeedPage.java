package cn.xiaojia.album;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;

/** Grouped family updates, their own browsing position, and explicit photo-level replies. */
final class ActivityFeedPage {
    private static final String[] FILTERS = {"all", "media", "comment"};
    private static final String[] FILTER_LABELS = {"全部", "照片与视频", "留言"};
    private final SocialFeedHost host;
    private final Activity activity;
    private final Map<String, FeedState> states = new HashMap<>();
    private final Map<String, Draft> drafts = new HashMap<>();
    private final Map<String, String> sentResponses = new HashMap<>();
    private final Map<String, String> sentResponseIds = new HashMap<>();
    private final HashSet<String> removedComments = new HashSet<>();
    private String filter = "all";
    private LinearLayout root;
    private ListView list;
    private BaseAdapter adapter;
    private TextView status, footer, fresh;
    private boolean visible, disposed, mediaOpen, hasNewResponse;
    private Boolean responsePermission;
    private int permissionRequest;
    private String updateMessage = "相册有更新，点这里刷新动态";
    private int groupRequest;
    private Runnable groupAbort;
    private Runnable groupCancel;
    private WarmSheet groupDialog, pickerDialog, composer;
    private BaseAdapter pickerAdapter;
    private TextView groupProgress, composerError, composerSend, composerLater;
    private EditText composerInput;
    private Draft composerDraft;

    private static final class FeedState {
        final ArrayList<JSONObject> items = new ArrayList<>();
        final HashSet<String> ids = new HashSet<>();
        String cursor = "", error = "", anchor = "";
        boolean loaded, loading, more = true;
        int first, offset, request;
    }

    private static final class Draft {
        JSONObject event, media;
        String text = "", requestId = "", sentText = "", error = "";
        int errorStatus;
        boolean sending;
    }

    private interface ScopeReady {
        void accept(ArrayList<JSONObject> scope);
    }

    ActivityFeedPage(SocialFeedHost host) {
        this.host = host;
        activity = host.activity();
        for (String kind : FILTERS) states.put(kind, new FeedState());
    }

    private int dp(float value) {
        return Ui.dp(activity, value);
    }

    private TextView text(String value, int size, int color) {
        return Ui.text(activity, value, size, color);
    }

    private FeedState state() {
        return states.get(filter);
    }

    private boolean canComment() {
        if (responsePermission != null) return responsePermission;
        JSONObject permissions = host.identity().optJSONObject("permissions");
        return permissions != null && permissions.optBoolean("can_comment");
    }

    private boolean canRespond(JSONObject event) {
        return canComment() && responseMedia(event) != null;
    }

    private void toast(String message) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
    }

    private JSONArray values(JSONObject object, String key) {
        JSONArray result = object.optJSONArray(key);
        return result == null ? new JSONArray() : result;
    }

    private String id(JSONObject object) {
        return object == null ? "" : object.optString("id");
    }

    private String eventKey(JSONObject event) {
        return event.optString("event_key", id(event));
    }

    private String actor(JSONObject event) {
        JSONObject person = event.optJSONObject("actor");
        String name = person == null ? "" : person.optString("name");
        return name.trim().isEmpty() ? "家人" : name;
    }

    private JSONObject responseMedia(JSONObject event) {
        JSONObject target = event.optJSONObject("response_media");
        return target != null ? target : values(event, "preview").optJSONObject(0);
    }

    private String dayLabel(String day) {
        try {
            LocalDate date = LocalDate.parse(Ui.day(day));
            LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
            if (date.equals(today)) return "今天";
            if (date.equals(today.minusDays(1))) return "昨天";
            return Ui.date(day);
        } catch (Exception ignored) {
            return "日期待确认";
        }
    }

    private String timeLabel(String value) {
        try {
            return OffsetDateTime.parse(value)
                    .atZoneSameInstant(ZoneId.of("Asia/Shanghai"))
                    .format(DateTimeFormatter.ofPattern("HH:mm"));
        } catch (Exception ignored) {
            return "";
        }
    }

    void show() {
        if (disposed) return;
        visible = true;
        mediaOpen = false;
        if (root == null) build();
        else {
            host.showFeed(root);
            restorePosition();
            updateStatus();
        }
        if (!state().loaded && !state().loading) load(state(), filter);
    }

    private void build() {
        cancelImages(root);
        root = Ui.column(activity);
        root.setBackgroundColor(Ui.BG);
        LinearLayout heading = Ui.row(activity);
        heading.setPadding(dp(20), 0, dp(12), 0);
        TextView title = text("家人动态", 23, Ui.INK);
        title.setTypeface(null, Typeface.BOLD);
        heading.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        TextView refresh = smallAction("刷新", () -> refresh(true));
        refresh.setContentDescription("刷新家人动态");
        heading.addView(refresh, new LinearLayout.LayoutParams(dp(58), dp(48)));
        root.addView(heading, new LinearLayout.LayoutParams(-1, dp(56)));
        LinearLayout filters = Ui.row(activity);
        filters.setPadding(dp(16), 0, dp(16), dp(10));
        for (int index = 0; index < FILTERS.length; index++) {
            final String kind = FILTERS[index];
            TextView choice =
                    text(FILTER_LABELS[index], 14, kind.equals(filter) ? Ui.ACCENT_DARK : Ui.INK);
            choice.setGravity(Gravity.CENTER);
            choice.setSingleLine(true);
            choice.setBackground(Ui.chip(activity, kind.equals(filter)));
            choice.setSelected(kind.equals(filter));
            choice.setContentDescription("筛选" + FILTER_LABELS[index]);
            choice.setOnClickListener(v -> changeFilter(kind));
            LinearLayout.LayoutParams fp =
                    new LinearLayout.LayoutParams(0, dp(48), index == 1 ? 1.6f : 1f);
            if (index > 0) fp.leftMargin = dp(8);
            filters.addView(choice, fp);
        }
        root.addView(filters);
        fresh = text(updateMessage, 13, Ui.ACCENT_DARK);
        fresh.setGravity(Gravity.CENTER);
        fresh.setPadding(dp(18), dp(8), dp(18), dp(8));
        fresh.setBackgroundColor(Ui.SOFT);
        fresh.setMinimumHeight(dp(48));
        fresh.setOnClickListener(v -> refresh(true));
        fresh.setVisibility(hasNewResponse ? View.VISIBLE : View.GONE);
        root.addView(fresh);
        FrameLayout area = new FrameLayout(activity);
        list = new ListView(activity);
        list.setDivider(null);
        list.setSelector(android.R.color.transparent);
        list.setVerticalScrollBarEnabled(false);
        list.setClipToPadding(false);
        footer = text("", 13, Ui.MUTED);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(dp(20), dp(12), dp(20), dp(18));
        footer.setMinimumHeight(dp(54));
        footer.setOnClickListener(v -> load(state(), filter));
        list.addFooterView(footer, null, true);
        adapter =
                new BaseAdapter() {
                    public int getCount() {
                        return state().items.size();
                    }

                    public Object getItem(int position) {
                        return state().items.get(position);
                    }

                    public long getItemId(int position) {
                        return position;
                    }

                    public View getView(int position, View old, ViewGroup parent) {
                        cancelImages(old);
                        return eventView(state().items.get(position), position);
                    }
                };
        list.setAdapter(adapter);
        list.setOnScrollListener(
                new AbsListView.OnScrollListener() {
                    public void onScrollStateChanged(AbsListView view, int state) {}

                    public void onScroll(AbsListView view, int first, int count, int total) {
                        FeedState value = state();
                        if (visible
                                && value.loaded
                                && value.error.isEmpty()
                                && total > 0
                                && first + count >= total - 3) load(value, filter);
                    }
                });
        area.addView(list, new FrameLayout.LayoutParams(-1, -1));
        status = text("", 15, Ui.MUTED);
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(28), dp(28), dp(28), dp(28));
        status.setOnClickListener(
                v -> {
                    if (!state().loading) refresh(true);
                });
        area.addView(status, new FrameLayout.LayoutParams(-1, dp(200), Gravity.TOP));
        root.addView(area, new LinearLayout.LayoutParams(-1, 0, 1));
        host.showFeed(root);
        updateStatus();
        restorePosition();
    }

    private TextView smallAction(String label, Runnable action) {
        TextView result = text(label, 14, Ui.ACCENT_DARK);
        result.setGravity(Gravity.CENTER);
        result.setPadding(dp(8), dp(8), dp(8), dp(8));
        result.setMinHeight(dp(48));
        result.setBackground(Ui.ripple(Color.TRANSPARENT, 12, activity));
        result.setOnClickListener(v -> action.run());
        return result;
    }

    private void changeFilter(String next) {
        if (filter.equals(next)) return;
        rememberPosition();
        filter = next;
        build();
        if (!state().loaded) load(state(), filter);
    }

    private void refresh(boolean resetPosition) {
        if (disposed) return;
        if (!resetPosition && !state().items.isEmpty()) rememberPosition();
        FeedState value = state();
        value.request++;
        value.loading = false;
        value.loaded = false;
        value.more = true;
        value.cursor = value.error = "";
        value.items.clear();
        value.ids.clear();
        if (resetPosition) {
            value.first = value.offset = 0;
            value.anchor = "";
        }
        hasNewResponse = false;
        sentResponses.clear();
        sentResponseIds.clear();
        if (visible && adapter != null) {
            adapter.notifyDataSetChanged();
            updateStatus();
        }
        load(value, filter);
    }

    private void load(FeedState value, String kind) {
        if (disposed || value.loading || !value.more) return;
        value.loading = true;
        value.error = "";
        final int request = value.request;
        final int permissions = ++permissionRequest;
        final String previousCursor = value.cursor;
        if (visible && state() == value) updateStatus();
        String path = "/api/activity-feed?kind=" + kind + "&limit=20";
        if (!value.cursor.isEmpty()) path += "&cursor=" + ApiClient.encode(value.cursor);
        host.requestDetailed(
                "GET",
                path,
                null,
                result -> {
                    if (disposed || request != value.request) return;
                    if (permissions == permissionRequest && result.has("can_respond")) {
                        responsePermission = result.optBoolean("can_respond");
                        updateComposer();
                    }
                    value.loading = false;
                    value.loaded = true;
                    JSONArray incoming = values(result, "items");
                    for (int i = 0; i < incoming.length(); i++) {
                        JSONObject event = incoming.optJSONObject(i);
                        if (event != null
                                && !id(event).isEmpty()
                                && keepComment(event)
                                && value.ids.add(eventKey(event))) value.items.add(event);
                    }
                    value.cursor =
                            result.isNull("next_cursor") ? "" : result.optString("next_cursor");
                    value.more = !value.cursor.isEmpty();
                    if (value.more && value.cursor.equals(previousCursor)) {
                        value.more = false;
                        value.error = "这次没有读到更多动态，可以刷新后再试";
                    }
                    if (visible && state() == value && adapter != null) {
                        adapter.notifyDataSetChanged();
                        updateStatus();
                        if (previousCursor.isEmpty()) restorePosition();
                    }
                },
                (code, message) -> {
                    if (disposed || request != value.request) return;
                    value.loading = false;
                    value.error = message;
                    if (visible && state() == value) updateStatus();
                });
    }

    private void updateStatus() {
        if (status == null || footer == null) return;
        FeedState value = state();
        status.setVisibility(value.items.isEmpty() ? View.VISIBLE : View.GONE);
        if (value.loading) status.setText("正在看看家人的新动态…");
        else if (!value.error.isEmpty()) status.setText(value.error + "\n轻点这里重试");
        else status.setText(filter.equals("comment") ? "还没有留言\n给喜欢的照片写句话吧" : "家人的照片、视频和留言，会出现在这里");
        if (value.items.isEmpty()) footer.setText("");
        else if (value.loading) footer.setText("正在读取更早的动态…");
        else if (!value.error.isEmpty()) footer.setText(value.error + "\n点这里重试");
        else footer.setText(value.more ? "继续往下看，或点这里读取更早的动态" : "已经看到最早的动态了");
        if (fresh != null) {
            fresh.setText(updateMessage);
            fresh.setVisibility(hasNewResponse ? View.VISIBLE : View.GONE);
        }
    }

    private View eventView(JSONObject event, int position) {
        LinearLayout wrapper = Ui.column(activity);
        wrapper.setPadding(dp(16), 0, dp(16), 0);
        String day = event.optString("day", Ui.day(event.optString("created_at")));
        String previous = position == 0 ? "" : state().items.get(position - 1).optString("day");
        if (position == 0 || !day.equals(previous)) {
            LinearLayout dayRow = Ui.row(activity);
            dayRow.setPadding(0, dp(9), 0, dp(9));
            TextView dayText = text(dayLabel(day), 12, Ui.SOFT_INK);
            dayText.setPadding(dp(9), dp(3), dp(9), dp(3));
            dayText.setBackground(Ui.round(Ui.SOFT, 12, activity));
            dayRow.addView(dayText);
            View line = new View(activity);
            line.setBackgroundColor(Ui.LINE);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(.5f), 1);
            lp.leftMargin = dp(10);
            dayRow.addView(line, lp);
            wrapper.addView(dayRow);
        }
        LinearLayout row = Ui.row(activity);
        row.setGravity(Gravity.TOP);
        row.setPadding(0, dp(5), 0, dp(12));
        View avatar = new FamilyAvatar(activity, actor(event));
        avatar.setContentDescription(actor(event) + "的头像");
        row.addView(avatar, new LinearLayout.LayoutParams(dp(38), dp(38)));
        LinearLayout content = Ui.column(activity);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, -2, 1);
        cp.leftMargin = dp(10);
        row.addView(content, cp);
        LinearLayout names = Ui.row(activity);
        TextView name = text(actor(event), 15, Ui.INK);
        name.setTypeface(null, Typeface.BOLD);
        name.setMaxLines(2);
        names.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        TextView time = text(timeLabel(event.optString("created_at")), 12, Ui.MUTED);
        time.setPadding(dp(8), 0, 0, 0);
        names.addView(time);
        content.addView(names);
        if (event.optString("type").equals("comment")) commentBody(content, event);
        else uploadBody(content, event);
        String acknowledgement = sentResponses.get(eventKey(event));
        if (acknowledgement != null) {
            TextView sent = text("我：" + acknowledgement, 13, Ui.INK);
            sent.setPadding(dp(10), dp(8), dp(10), dp(8));
            sent.setBackground(Ui.round(Ui.SOFT, 8, activity));
            sent.setMaxLines(3);
            sent.setEllipsize(android.text.TextUtils.TruncateAt.END);
            content.addView(sent);
        }
        wrapper.addView(row);
        View divider = new View(activity);
        divider.setBackgroundColor(Ui.LINE);
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(-1, Ui.dp(activity, .5f));
        dp.leftMargin = dp(48);
        wrapper.addView(divider, dp);
        return wrapper;
    }

    private void uploadBody(LinearLayout content, JSONObject event) {
        TextView message = text(event.optString("text", "分享了新的照片"), 14, Ui.INK);
        message.setPadding(0, dp(5), 0, dp(8));
        content.addView(message);
        JSONArray previews = values(event, "preview");
        int total = event.optInt("media_count", previews.length());
        int count = Math.min(3, previews.length());
        int available = activity.getResources().getDisplayMetrics().widthPixels - dp(80);
        if (count == 1) {
            JSONObject item = previews.optJSONObject(0);
            int originalWidth = item == null ? 0 : item.optInt("width");
            int originalHeight = item == null ? 0 : item.optInt("height");
            float ratio =
                    originalWidth > 0 && originalHeight > 0
                            ? (float) originalWidth / originalHeight
                            : 4f / 3f;
            int width = Math.min(available, dp(420));
            int height = Math.round(width / ratio);
            if (height > dp(250)) {
                height = dp(250);
                width = Math.max(dp(72), Math.round(height * ratio));
            }
            height = Math.max(dp(72), height);
            content.addView(
                    photo(item, 0, () -> openEvent(event, item), true),
                    new LinearLayout.LayoutParams(Math.min(width, available), height));
        } else if (count > 1) {
            LinearLayout photos = Ui.row(activity);
            int height = Math.max(dp(76), (available - dp(4) * (count - 1)) / count);
            height = Math.min(dp(136), height);
            for (int i = 0; i < count; i++) {
                JSONObject item = previews.optJSONObject(i);
                int more = i == count - 1 ? Math.max(0, total - count) : 0;
                LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(0, height, 1);
                if (i > 0) pp.leftMargin = dp(4);
                photos.addView(photo(item, more, () -> openEvent(event, item)), pp);
            }
            content.addView(photos);
        }
        LinearLayout actions = Ui.row(activity);
        if (canRespond(event)) actions.addView(replyAction("回应", () -> respond(event)));
        else {
            TextView only = text("", 12, Ui.MUTED);
            actions.addView(only);
        }
        View space = new View(activity);
        actions.addView(space, new LinearLayout.LayoutParams(0, 1, 1));
        TextView open =
                smallAction(
                        total > 1 ? "查看这一组  ›" : event.optInt("videos") > 0 ? "播放视频  ›" : "查看照片  ›",
                        () -> openEvent(event, responseMedia(event)));
        open.setContentDescription("查看" + actor(event) + "分享的这一组");
        actions.addView(open);
        content.addView(actions);
    }

    private View replyAction(String label, Runnable action) {
        LinearLayout control = Ui.row(activity);
        control.setGravity(Gravity.CENTER_VERTICAL);
        control.setPadding(0, dp(7), dp(8), dp(7));
        control.setMinimumHeight(dp(48));
        control.addView(
                new Ui.Icon(activity, "comment", Ui.MUTED),
                new LinearLayout.LayoutParams(dp(18), dp(18)));
        TextView name = text(label, 13, Ui.MUTED);
        name.setPadding(dp(5), 0, 0, 0);
        control.addView(name);
        control.setBackground(Ui.ripple(Color.TRANSPARENT, 10, activity));
        control.setContentDescription(label);
        control.setOnClickListener(v -> action.run());
        return control;
    }

    private void commentBody(LinearLayout content, JSONObject event) {
        JSONObject comment = event.optJSONObject("comment");
        String body = comment == null ? event.optString("text") : comment.optString("text");
        LinearLayout bubbleRow = Ui.row(activity);
        bubbleRow.setGravity(Gravity.TOP);
        bubbleRow.setPadding(0, dp(8), 0, 0);
        LinearLayout bubble = Ui.column(activity);
        bubble.setPadding(dp(11), dp(10), dp(11), 0);
        bubble.setBackground(Ui.round(Ui.SOFT, 12, activity));
        JSONObject reply = comment == null ? null : comment.optJSONObject("reply_to");
        if (reply != null) {
            TextView recipient = text("回复" + reply.optString("name", "家人"), 12, Ui.ACCENT_DARK);
            recipient.setPadding(0, 0, 0, dp(4));
            bubble.addView(recipient);
        }
        TextView words = text(body, 14, Ui.INK);
        words.setLineSpacing(dp(3), 1);
        words.setMaxLines(4);
        words.setEllipsize(android.text.TextUtils.TruncateAt.END);
        words.setOnClickListener(v -> showComment(event));
        bubble.addView(words);
        if (canRespond(event)) {
            TextView replyButton = smallAction("回复", () -> respond(event));
            replyButton.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
            replyButton.setPadding(0, dp(6), 0, dp(6));
            bubble.addView(replyButton);
        } else {
            TextView view = smallAction("查看留言", () -> showComment(event));
            view.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
            view.setPadding(0, dp(6), 0, dp(6));
            bubble.addView(view);
        }
        bubbleRow.addView(bubble, new LinearLayout.LayoutParams(0, -2, 1));
        JSONObject media = responseMedia(event);
        if (media != null) {
            LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(dp(66), dp(78));
            pp.leftMargin = dp(6);
            bubbleRow.addView(photo(media, 0, () -> openEvent(event, media)), pp);
        }
        content.addView(bubbleRow);
    }

    private FrameLayout photo(JSONObject item, int more, Runnable open) {
        return photo(item, more, open, false);
    }

    private FrameLayout photo(JSONObject item, int more, Runnable open, boolean wholeImage) {
        FrameLayout frame = new FrameLayout(activity);
        frame.setBackground(Ui.round(Ui.SOFT, 9, activity));
        frame.setClipToOutline(true);
        ImageView image = new ImageView(activity);
        image.setScaleType(
                wholeImage ? ImageView.ScaleType.FIT_CENTER : ImageView.ScaleType.CENTER_CROP);
        frame.addView(image, new FrameLayout.LayoutParams(-1, -1));
        if (item != null) {
            host.images().load(image, item.optString("thumbnail_url"), 700);
            boolean video = item.optString("kind").equals("video");
            frame.setContentDescription(
                    Ui.date(item.optString("album_date")) + (video ? "的视频" : "的照片"));
            if (video) {
                FrameLayout play = new FrameLayout(activity);
                play.setBackground(Ui.round(0x80302522, 22, activity));
                play.addView(
                        new Ui.Icon(activity, "play", Color.WHITE),
                        new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
                frame.addView(play, new FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER));
                TextView duration = text(Ui.duration(item.optDouble("duration")), 11, Color.WHITE);
                duration.setPadding(dp(5), dp(2), dp(5), dp(2));
                duration.setBackground(Ui.round(0x99302522, 5, activity));
                FrameLayout.LayoutParams badge =
                        new FrameLayout.LayoutParams(-2, -2, Gravity.RIGHT | Gravity.BOTTOM);
                badge.setMargins(0, 0, dp(5), dp(5));
                frame.addView(duration, badge);
            }
        }
        if (more > 0) {
            TextView count = text("+" + more, 24, Color.WHITE);
            count.setGravity(Gravity.CENTER);
            count.setBackgroundColor(0x80302522);
            frame.addView(count, new FrameLayout.LayoutParams(-1, -1));
            frame.setContentDescription("还有" + more + "个瞬间，查看这一组");
        }
        frame.setOnClickListener(v -> open.run());
        return frame;
    }

    private void showComment(JSONObject event) {
        JSONObject comment = event.optJSONObject("comment");
        String words = comment == null ? event.optString("text") : comment.optString("text");
        WarmSheet dialog = new WarmSheet(activity, actor(event) + "的留言");
        TextView message = text(words, 16, Ui.INK);
        message.setPadding(0, dp(12), 0, dp(12));
        message.setLineSpacing(dp(4), 1);
        dialog.body.addView(message, new LinearLayout.LayoutParams(-1, -2));
        if (canRespond(event))
            dialog.primary(
                    "回复",
                    () -> {
                        dialog.dismiss();
                        if (!disposed && visible) respond(event);
                    });
        dialog.show();
    }

    private void openEvent(JSONObject event, JSONObject selected) {
        if (selected == null) return;
        rememberPosition();
        loadScope(
                event,
                scope -> {
                    JSONObject match = null;
                    for (JSONObject value : scope)
                        if (id(value).equals(id(selected))) match = value;
                    if (match == null) {
                        toast(
                                selected.optString("kind").equals("video")
                                        ? "这段视频已经移出相册，请刷新动态后再看"
                                        : "这张照片已经移出相册，请刷新动态后再看");
                        return;
                    }
                    mediaOpen = true;
                    visible = false;
                    host.openFeedMedia(match, scope, this::show);
                });
    }

    private void loadScope(JSONObject event, ScopeReady ready) {
        loadScope(event, ready, null);
    }

    private void loadScope(JSONObject event, ScopeReady ready, Runnable abort) {
        if (disposed || !visible) return;
        groupRequest++;
        finishScope();
        groupAbort = abort;
        final int request = groupRequest;
        groupProgress = text("正在准备这一组照片…", 14, Ui.MUTED);
        groupProgress.setPadding(dp(14), dp(18), 0, dp(18));
        groupDialog = new WarmSheet(activity, "这一组回忆");
        LinearLayout progress = Ui.row(activity);
        android.widget.ProgressBar spinner = new android.widget.ProgressBar(activity);
        spinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
        progress.addView(spinner, new LinearLayout.LayoutParams(dp(24), dp(24)));
        progress.addView(groupProgress, new LinearLayout.LayoutParams(0, -2, 1));
        groupDialog.body.addView(progress);
        groupDialog.secondary("取消", this::abortScope);
        groupDialog.onClose(this::abortScope);
        groupDialog.show();
        loadScopePage(
                event, "", new ArrayList<>(), new HashSet<>(), new HashSet<>(), request, ready);
    }

    private void loadScopePage(
            JSONObject event,
            String cursor,
            ArrayList<JSONObject> items,
            HashSet<String> ids,
            HashSet<String> cursors,
            int request,
            ScopeReady ready) {
        String path = "/api/activity-feed/" + ApiClient.encode(id(event)) + "/media?limit=60";
        if (!cursor.isEmpty()) path += "&cursor=" + ApiClient.encode(cursor);
        groupCancel =
                host.requestCancelableGet(
                        path,
                        result -> {
                            if (disposed || request != groupRequest || !visible) return;
                            groupCancel = null;
                            JSONArray incoming = values(result, "items");
                            for (int i = 0; i < incoming.length(); i++) {
                                JSONObject item = incoming.optJSONObject(i);
                                if (item != null && ids.add(id(item))) items.add(item);
                            }
                            String next =
                                    result.isNull("next_cursor")
                                            ? ""
                                            : result.optString("next_cursor");
                            if (!next.isEmpty()) {
                                if (!cursors.add(next)) {
                                    abortScope();
                                    toast("这组照片暂时没有读完整，请稍后再试");
                                    return;
                                }
                                if (groupProgress != null)
                                    groupProgress.setText(
                                            "已准备 "
                                                    + items.size()
                                                    + " / "
                                                    + result.optInt("total", items.size())
                                                    + " 个瞬间");
                                loadScopePage(event, next, items, ids, cursors, request, ready);
                            } else {
                                finishScope();
                                ready.accept(items);
                            }
                        },
                        (code, message) -> {
                            if (disposed || request != groupRequest || !visible) return;
                            groupCancel = null;
                            abortScope();
                            toast(message);
                        });
    }

    private void finishScope() {
        if (groupCancel != null) groupCancel.run();
        groupCancel = null;
        if (groupDialog != null) groupDialog.dismiss();
        groupDialog = null;
        groupProgress = null;
        groupAbort = null;
    }

    private void abortScope() {
        Runnable abort = groupAbort;
        groupRequest++;
        finishScope();
        if (!disposed && visible && abort != null) abort.run();
    }

    private void respond(JSONObject event) {
        if (!canRespond(event)) {
            toast("你目前可以查看动态，留言需要家人开通权限");
            return;
        }
        JSONObject media = responseMedia(event);
        if (media == null) {
            toast("这条动态关联的照片暂时无法查看，请刷新后再试");
            return;
        }
        Draft draft = drafts.get(eventKey(event));
        if (draft == null) {
            draft = new Draft();
            draft.event = event;
            draft.media = media;
            drafts.put(eventKey(event), draft);
        }
        draft.event = event;
        showComposer(draft);
    }

    private void showComposer(Draft draft) {
        if (disposed || !visible) return;
        if (composer != null) composer.dismiss();
        composerDraft = draft;
        boolean reply = draft.event.optString("type").equals("comment");
        LinearLayout panel = Ui.column(activity);
        panel.setPadding(0, dp(10), 0, dp(4));
        LinearLayout target = Ui.row(activity);
        target.addView(
                photo(draft.media, 0, () -> {}), new LinearLayout.LayoutParams(dp(64), dp(64)));
        LinearLayout labels = Ui.column(activity);
        labels.setPadding(dp(12), 0, 0, 0);
        labels.addView(
                text(
                        draft.media.optString("kind").equals("video") ? "回应这段视频" : "回应这张照片",
                        15,
                        Ui.INK));
        labels.addView(text(Ui.date(draft.media.optString("album_date")), 12, Ui.MUTED));
        if (!reply && draft.event.optInt("media_count") > 1)
            labels.addView(smallAction("换一张", () -> chooseResponseMedia(draft)));
        target.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        panel.addView(target);
        JSONObject comment = draft.event.optJSONObject("comment");
        if (reply && comment != null) {
            TextView quoted =
                    text(actor(draft.event) + "：" + comment.optString("text"), 13, Ui.MUTED);
            quoted.setMaxLines(2);
            quoted.setEllipsize(android.text.TextUtils.TruncateAt.END);
            quoted.setPadding(0, dp(12), 0, 0);
            panel.addView(quoted);
        }
        EditText input = new EditText(activity);
        composerInput = input;
        input.setContentDescription("写给家人的回应");
        input.setTextColor(Ui.INK);
        input.setTextSize(16);
        input.setHint("写句想对家人说的话…");
        input.setHintTextColor(Ui.MUTED);
        input.setInputType(
                InputType.TYPE_CLASS_TEXT
                        | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                        | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setGravity(Gravity.TOP);
        input.setMinLines(3);
        input.setMaxLines(6);
        input.setFilters(new InputFilter[] {new InputFilter.LengthFilter(2000)});
        input.setText(draft.text);
        input.setPadding(0, dp(14), 0, dp(10));
        input.setBackgroundColor(Color.TRANSPARENT);
        input.setEnabled(!draft.sending);
        input.addTextChangedListener(
                new TextWatcher() {
                    public void beforeTextChanged(
                            CharSequence s, int start, int count, int after) {}

                    public void onTextChanged(CharSequence s, int start, int before, int count) {
                        draft.text = s.toString();
                    }

                    public void afterTextChanged(Editable s) {}
                });
        panel.addView(input, new LinearLayout.LayoutParams(-1, -2));
        composerError = text(draft.error, 13, Ui.ERROR);
        composerError.setPadding(0, dp(6), 0, dp(8));
        composerError.setVisibility(draft.error.isEmpty() ? View.GONE : View.VISIBLE);
        panel.addView(composerError);
        WarmSheet dialog = new WarmSheet(activity, (reply ? "回复" : "回应") + actor(draft.event));
        composer = dialog;
        dialog.body.addView(panel, new LinearLayout.LayoutParams(-1, -2));
        composerSend = dialog.primary(draft.sending ? "正在发送…" : "发送", () -> send(draft));
        composerLater = dialog.secondary("稍后再说", () -> closeComposer(draft));
        dialog.secondary("复制文字", () -> copyDraft(draft));
        dialog.onClose(() -> closeComposer(draft));
        dialog.onDismiss(
                () -> {
                    cancelImages(panel);
                    if (composer == dialog) {
                        composer = null;
                        composerInput = null;
                        composerError = null;
                        composerSend = composerLater = null;
                        composerDraft = null;
                    }
                });
        dialog.show();
        updateComposer();
    }

    private void closeComposer(Draft draft) {
        if (draft.sending) {
            toast("正在发送，请稍等一下");
            return;
        }
        if (composer != null) composer.dismiss();
        if (!draft.text.trim().isEmpty()) toast("文字已留在当前草稿，下次回应可以继续写");
    }

    private void copyDraft(Draft draft) {
        if (draft.text.trim().isEmpty()) {
            toast("先写一句话，再复制给自己留存");
            return;
        }
        ClipboardManager clipboard =
                (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("给家人的回应", draft.text));
        toast("文字已复制");
    }

    private void updateComposer() {
        if (composer == null || composerDraft == null) return;
        Draft draft = composerDraft;
        boolean allowed = canRespond(draft.event);
        if (allowed && draft.errorStatus == 403) {
            draft.error = "";
            draft.errorStatus = 0;
        }
        composerSend.setText(draft.sending ? "正在发送…" : "发送");
        composerSend.setEnabled(!draft.sending && allowed);
        composerLater.setEnabled(!draft.sending);
        composer.setCancelable(!draft.sending);
        if (composerInput != null) composerInput.setEnabled(!draft.sending);
        if (composerError != null) {
            String problem = !allowed ? "暂时不能回应这份内容，文字仍保留在这里，可以先复制保存" : draft.error;
            composerError.setText(problem);
            composerError.setVisibility(problem.isEmpty() ? View.GONE : View.VISIBLE);
        }
    }

    private void send(Draft draft) {
        if (disposed || draft.sending) return;
        if (!canRespond(draft.event)) {
            updateComposer();
            return;
        }
        String words = draft.text.trim();
        if (words.isEmpty()) {
            toast("写一句话，再发给家人吧");
            return;
        }
        if (!words.equals(draft.sentText) || draft.requestId.isEmpty())
            draft.requestId = UUID.randomUUID().toString();
        draft.sentText = words;
        draft.sending = true;
        draft.error = "";
        draft.errorStatus = 0;
        updateComposer();
        JSONObject body = new JSONObject();
        try {
            body.put("text", words).put("request_id", draft.requestId);
            JSONObject comment = draft.event.optJSONObject("comment");
            if (draft.event.optString("type").equals("comment") && comment != null)
                body.put("reply_to_id", id(comment));
        } catch (Exception impossible) {
            draft.sending = false;
            draft.error = "这段文字暂时无法发送，请稍后重试";
            updateComposer();
            return;
        }
        host.requestDetailed(
                "POST",
                "/api/media/" + ApiClient.encode(id(draft.media)) + "/comments",
                body,
                result -> {
                    if (disposed) return;
                    draft.sending = false;
                    sentResponses.put(eventKey(draft.event), words);
                    sentResponseIds.put(eventKey(draft.event), id(result));
                    drafts.remove(eventKey(draft.event));
                    hasNewResponse = true;
                    updateMessage = "回应已发出，点这里看最新动态";
                    for (FeedState other : states.values()) {
                        if (other == state()) continue;
                        other.request++;
                        other.loading = false;
                        other.loaded = false;
                        other.more = true;
                        other.cursor = other.error = "";
                        other.items.clear();
                        other.ids.clear();
                    }
                    if (composerDraft == draft && composer != null) composer.dismiss();
                    if (visible) {
                        rememberPosition();
                        if (adapter != null) adapter.notifyDataSetChanged();
                        updateStatus();
                        restorePosition();
                        toast(
                                draft.media.optString("kind").equals("video")
                                        ? "回应已发出，家人能在这段视频下看到"
                                        : "回应已发出，家人能在这张照片下看到");
                    }
                },
                (code, message) -> {
                    if (disposed) return;
                    draft.sending = false;
                    draft.errorStatus = code;
                    if (code == 403) commentPermissionDenied();
                    draft.error = message + "\n文字已保留，可以继续修改或稍后重试";
                    updateComposer();
                });
    }

    private void chooseResponseMedia(Draft draft) {
        if (draft.sending) return;
        if (composer != null) composer.dismiss();
        loadScope(
                draft.event,
                items -> {
                    if (items.isEmpty()) {
                        toast("这组照片已经移出相册，请刷新动态");
                        showComposer(draft);
                        return;
                    }
                    GridView grid = new GridView(activity);
                    grid.setNumColumns(3);
                    grid.setHorizontalSpacing(dp(5));
                    grid.setVerticalSpacing(dp(5));
                    grid.setPadding(dp(14), dp(8), dp(14), dp(8));
                    grid.setSelector(android.R.color.transparent);
                    pickerAdapter =
                            new BaseAdapter() {
                                public int getCount() {
                                    return items.size();
                                }

                                public Object getItem(int position) {
                                    return items.get(position);
                                }

                                public long getItemId(int position) {
                                    return position;
                                }

                                public View getView(int position, View old, ViewGroup parent) {
                                    cancelImages(old);
                                    JSONObject item = items.get(position);
                                    FrameLayout frame =
                                            photo(
                                                    item,
                                                    0,
                                                    () -> {
                                                        if (!id(item).equals(id(draft.media)))
                                                            draft.requestId = draft.sentText = "";
                                                        draft.media = item;
                                                        if (pickerDialog != null)
                                                            pickerDialog.dismiss();
                                                        showComposer(draft);
                                                    });
                                    int side =
                                            Math.max(
                                                    dp(72),
                                                    (activity.getResources()
                                                                            .getDisplayMetrics()
                                                                            .widthPixels
                                                                    - dp(70))
                                                            / 3);
                                    frame.setLayoutParams(new AbsListView.LayoutParams(-1, side));
                                    return frame;
                                }
                            };
                    grid.setAdapter(pickerAdapter);
                    int height =
                            Math.min(
                                    dp(430),
                                    activity.getResources().getDisplayMetrics().heightPixels / 2);
                    FrameLayout holder = new FrameLayout(activity);
                    holder.addView(grid, new FrameLayout.LayoutParams(-1, height));
                    WarmSheet picker = new WarmSheet(activity, "选一个想回应的瞬间");
                    pickerDialog = picker;
                    picker.body.addView(holder, new LinearLayout.LayoutParams(-1, -2));
                    Runnable back =
                            () -> {
                                picker.dismiss();
                                showComposer(draft);
                            };
                    picker.secondary("返回写回应", back);
                    picker.onClose(back);
                    picker.onDismiss(
                            () -> {
                                cancelImages(holder);
                                if (pickerDialog == picker) {
                                    pickerDialog = null;
                                    pickerAdapter = null;
                                }
                            });
                    picker.show();
                },
                () -> showComposer(draft));
    }

    private void rememberPosition() {
        if (list == null || list.getChildCount() == 0) return;
        FeedState value = state();
        value.first = list.getFirstVisiblePosition();
        value.offset = list.getChildAt(0).getTop();
        value.anchor =
                value.first >= 0 && value.first < value.items.size()
                        ? eventKey(value.items.get(value.first))
                        : "";
    }

    private void restorePosition() {
        if (list == null) return;
        FeedState value = state();
        int first = value.first;
        if (!value.anchor.isEmpty())
            for (int i = 0; i < value.items.size(); i++)
                if (eventKey(value.items.get(i)).equals(value.anchor)) {
                    first = i;
                    break;
                }
        final int position = Math.min(first, Math.max(0, value.items.size() - 1));
        final ListView shown = list;
        shown.post(
                () -> {
                    if (!disposed && visible && shown == list && state() == value)
                        shown.setSelectionFromTop(position, value.offset);
                });
    }

    private void cancelImages(View view) {
        if (view instanceof ImageView) host.images().cancel((ImageView) view);
        if (view instanceof ViewGroup)
            for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
                cancelImages(((ViewGroup) view).getChildAt(i));
    }

    void pause() {
        rememberPosition();
        visible = false;
        groupRequest++;
        finishScope();
    }

    void resume() {
        if (disposed || mediaOpen) return;
        visible = true;
        rememberPosition();
        if (adapter != null) adapter.notifyDataSetChanged();
        updateStatus();
        updateComposer();
        restorePosition();
    }

    void configurationChanged() {
        if (disposed || !visible || mediaOpen) return;
        rememberPosition();
        // Rebuilding the host page closes sheets. Keep the active response and its input visible.
        if ((composer != null && composer.isShowing())
                || (pickerDialog != null && pickerDialog.isShowing())
                || (groupDialog != null && groupDialog.isShowing())) {
            if (adapter != null) adapter.notifyDataSetChanged();
            if (pickerAdapter != null) pickerAdapter.notifyDataSetChanged();
            restorePosition();
            return;
        }
        build();
        if (pickerAdapter != null) pickerAdapter.notifyDataSetChanged();
    }

    void permissionsChanged() {
        if (disposed) return;
        responsePermission = null;
        permissionRequest++;
        if (visible && adapter != null) {
            rememberPosition();
            adapter.notifyDataSetChanged();
            restorePosition();
        }
        updateComposer();
    }

    void invalidate() {
        if (disposed) return;
        hasNewResponse = true;
        updateMessage = "相册有更新，点这里刷新动态";
        if (visible) updateStatus();
    }

    void commentPermissionDenied() {
        responsePermission = false;
        permissionRequest++;
        if (visible && adapter != null) {
            rememberPosition();
            adapter.notifyDataSetChanged();
            restorePosition();
        }
        updateComposer();
    }

    private boolean keepComment(JSONObject event) {
        JSONObject comment = event.optJSONObject("comment");
        if (comment == null) return true;
        if (removedComments.contains(id(comment))) return false;
        JSONObject parent = comment.optJSONObject("reply_to");
        if (parent != null && removedComments.contains(id(parent))) comment.remove("reply_to");
        return true;
    }

    void removedComment(String commentId) {
        if (disposed || commentId.isEmpty()) return;
        if (visible) rememberPosition();
        removedComments.add(commentId);
        HashSet<String> removedKeys = new HashSet<>();
        for (FeedState value : states.values()) {
            for (int i = value.items.size() - 1; i >= 0; i--) {
                JSONObject event = value.items.get(i);
                if (keepComment(event)) continue;
                String key = eventKey(event);
                removedKeys.add(key);
                value.items.remove(i);
                value.ids.remove(key);
                if (i < value.first) value.first--;
                if (key.equals(value.anchor)) {
                    value.first = Math.min(i, Math.max(0, value.items.size() - 1));
                    value.anchor =
                            value.items.isEmpty() ? "" : eventKey(value.items.get(value.first));
                }
            }
        }
        drafts.entrySet()
                .removeIf(
                        entry -> {
                            if (keepComment(entry.getValue().event)) return false;
                            removedKeys.add(entry.getKey());
                            if (composerDraft == entry.getValue() && composer != null)
                                composer.dismiss();
                            return true;
                        });
        for (Map.Entry<String, String> entry : sentResponseIds.entrySet())
            if (commentId.equals(entry.getValue())) removedKeys.add(entry.getKey());
        for (String key : removedKeys) {
            sentResponses.remove(key);
            sentResponseIds.remove(key);
        }
        invalidate();
        if (adapter != null) adapter.notifyDataSetChanged();
        if (visible) {
            updateStatus();
            restorePosition();
        }
    }

    void dispose() {
        disposed = true;
        visible = false;
        groupRequest++;
        finishScope();
        if (composer != null) composer.dismiss();
        if (pickerDialog != null) pickerDialog.dismiss();
        cancelImages(root);
        root = null;
        list = null;
        adapter = null;
        drafts.clear();
        sentResponses.clear();
        sentResponseIds.clear();
        removedComments.clear();
        for (FeedState value : states.values()) {
            value.request++;
            value.items.clear();
            value.ids.clear();
        }
    }
}
