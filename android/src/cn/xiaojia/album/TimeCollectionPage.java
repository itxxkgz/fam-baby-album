package cn.xiaojia.album;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

/** A time-tab collection with its own pagination and return position. */
final class TimeCollectionPage {
    interface Host {
        Activity activity();

        ImageLoader images();

        String birthday();

        void request(String path, SettingsScreen.Done success, SettingsScreen.Failure failure);

        void openMedia(TimeCollectionPage page, JSONObject item);

        void goBack(TimeCollectionPage page);
    }

    final String key, title, kind, month, returnPage;
    final boolean favorites;
    private final Host host;
    private final Activity activity;
    private final ArrayList<JSONObject> items = new ArrayList<>();
    private final HashSet<String> ids = new HashSet<>();
    private String cursor = "";
    private String failureMessage = "";
    private boolean loading, hasMore = true, failed, invalidated, dirty, refreshing;
    private boolean refreshFailed;
    private int requestRevision;
    private final TimeCollectionPosition bookmark = new TimeCollectionPosition();
    private int total, firstPosition, firstOffset;
    private ListView list;
    private TextView summary, footer, emptyTitle, emptyDescription, emptyAction;
    private LinearLayout emptyState;
    private BaseAdapter adapter;
    private static final int COLUMNS = 3;

    TimeCollectionPage(
            Host host,
            String key,
            String title,
            String kind,
            String month,
            boolean favorites,
            String returnPage) {
        this.host = host;
        activity = host.activity();
        this.key = key;
        this.title = title;
        this.kind = kind;
        this.month = month;
        this.favorites = favorites;
        this.returnPage = returnPage;
    }

    List<JSONObject> items() {
        return items;
    }

    private int dp(float value) {
        return Ui.dp(activity, value);
    }

    private TextView text(String value, int size, int color) {
        return Ui.text(activity, value, size, color);
    }

    View createView() {
        rememberPosition();
        cancelImages(list);
        LinearLayout page = Ui.column(activity);
        page.setBackgroundColor(Ui.BG);
        LinearLayout bar = Ui.row(activity);
        bar.setPadding(dp(4), 0, dp(18), 0);
        FrameLayout back = new FrameLayout(activity);
        back.setBackground(Ui.ripple(Ui.BG, 24, activity));
        back.addView(
                new Ui.Icon(activity, "back", Ui.INK),
                new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
        back.setContentDescription(
                returnPage.equals("months")
                        ? "返回月份回忆"
                        : returnPage.equals("me") ? "返回我的" : "返回成长时光");
        back.setOnClickListener(
                v -> {
                    rememberPosition();
                    host.goBack(this);
                });
        bar.addView(back, new LinearLayout.LayoutParams(dp(48), dp(52)));
        TextView heading = text(title, 20, Ui.INK);
        heading.setTypeface(null, Typeface.BOLD);
        bar.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        TextView refresh = text("刷新", 13, Ui.ACCENT_DARK);
        refresh.setGravity(Gravity.CENTER);
        refresh.setContentDescription("刷新" + title);
        refresh.setBackground(Ui.ripple(Ui.BG, 20, activity));
        refresh.setOnClickListener(v -> refresh());
        bar.addView(refresh, new LinearLayout.LayoutParams(dp(48), dp(48)));
        page.addView(bar, new LinearLayout.LayoutParams(-1, dp(52)));
        list = new ListView(activity);
        list.setDivider(null);
        list.setSelector(android.R.color.transparent);
        list.setVerticalScrollBarEnabled(false);
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout intro = Ui.column(activity);
        intro.setPadding(dp(12), 0, dp(12), dp(12));
        summary = text("", 12, Ui.MUTED);
        summary.setGravity(Gravity.CENTER);
        intro.addView(summary);
        // Keep one header row so the existing photo-anchor bookmark stays valid.
        list.addHeaderView(intro, null, false);
        footer = text("", 13, Ui.MUTED);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(dp(24), dp(24), dp(24), dp(30));
        footer.setOnClickListener(v -> retryOrLoad());
        list.addFooterView(footer, null, false);
        adapter =
                new BaseAdapter() {
                    @Override
                    public int getCount() {
                        return (items.size() + COLUMNS - 1) / COLUMNS;
                    }

                    @Override
                    public Object getItem(int position) {
                        return position;
                    }

                    @Override
                    public long getItemId(int position) {
                        return position;
                    }

                    @Override
                    public boolean isEnabled(int position) {
                        return false;
                    }

                    @Override
                    public View getView(int position, View old, ViewGroup parent) {
                        cancelImages(old);
                        LinearLayout row = Ui.row(activity);
                        row.setGravity(Gravity.TOP);
                        row.setPadding(dp(12), 0, dp(12), dp(4));
                        int columns = COLUMNS;
                        int width =
                                (activity.getResources().getDisplayMetrics().widthPixels
                                                - dp(24)
                                                - dp((columns - 1) * 4))
                                        / columns;
                        for (int column = 0; column < columns; column++) {
                            int index = position * columns + column;
                            LinearLayout.LayoutParams slot =
                                    new LinearLayout.LayoutParams(0, -2, 1);
                            if (column < columns - 1) slot.rightMargin = dp(4);
                            if (index >= items.size()) {
                                row.addView(new View(activity), slot);
                                continue;
                            }
                            row.addView(mediaCard(items.get(index), width), slot);
                        }
                        return row;
                    }
                };
        list.setAdapter(adapter);
        list.setOnScrollListener(
                new AbsListView.OnScrollListener() {
                    @Override
                    public void onScrollStateChanged(AbsListView view, int state) {}

                    @Override
                    public void onScroll(AbsListView view, int first, int count, int totalRows) {
                        if (totalRows > 0 && first + count >= totalRows - 3 && !failed) load();
                    }
                });
        FrameLayout body = new FrameLayout(activity);
        body.addView(list, new FrameLayout.LayoutParams(-1, -1));
        emptyState = Ui.column(activity);
        emptyState.setGravity(Gravity.CENTER);
        emptyState.setPadding(dp(36), dp(28), dp(36), dp(48));
        FrameLayout illustration = new FrameLayout(activity);
        illustration.setBackground(Ui.round(Ui.SOFT, 42, activity));
        illustration.addView(
                new Ui.Icon(
                        activity,
                        favorites ? "collection" : kind.equals("video") ? "video" : "photo",
                        Ui.ACCENT,
                        true),
                new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER));
        emptyState.addView(illustration, new LinearLayout.LayoutParams(dp(84), dp(84)));
        emptyTitle = text("", 19, Ui.INK);
        emptyTitle.setTypeface(null, Typeface.BOLD);
        emptyTitle.setGravity(Gravity.CENTER);
        emptyTitle.setPadding(0, dp(22), 0, dp(8));
        emptyState.addView(emptyTitle);
        emptyDescription = text("", 14, Ui.MUTED);
        emptyDescription.setGravity(Gravity.CENTER);
        emptyDescription.setLineSpacing(dp(4), 1);
        emptyState.addView(emptyDescription);
        emptyAction = text("", 15, Ui.ACCENT_DARK);
        emptyAction.setGravity(Gravity.CENTER);
        emptyAction.setMinHeight(dp(48));
        emptyAction.setPadding(dp(24), dp(12), dp(24), dp(12));
        emptyAction.setBackground(Ui.ripple(Ui.SOFT, 24, activity));
        emptyAction.setOnClickListener(
                v -> {
                    if (failed) retryOrLoad();
                    else host.goBack(this);
                });
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(-2, -2);
        actionParams.topMargin = dp(24);
        emptyState.addView(emptyAction, actionParams);
        body.addView(emptyState, new FrameLayout.LayoutParams(-1, -1));
        page.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        updateLabels();
        if (!items.isEmpty()) list.setSelectionFromTop(firstPosition, firstOffset);
        if (dirty) refresh(false);
        else if (items.isEmpty()) load();
        return page;
    }

    private View mediaCard(JSONObject item, int width) {
        FrameLayout media = new FrameLayout(activity);
        media.setBackground(Ui.round(Ui.SOFT, 8, activity));
        media.setClipToOutline(true);
        media.setMinimumHeight(width);
        ImageView photo = new TopCropImageView(activity);
        media.addView(photo, new FrameLayout.LayoutParams(-1, width));
        host.images().load(photo, item.optString("thumbnail_url"), 420);
        boolean video = item.optString("kind").equals("video");
        if (video) {
            LinearLayout badge = Ui.row(activity);
            badge.setPadding(dp(5), dp(3), dp(5), dp(3));
            badge.setBackground(Ui.round(0xA6302522, 7, activity));
            badge.addView(
                    new Ui.Icon(activity, "play", Color.WHITE),
                    new LinearLayout.LayoutParams(dp(12), dp(12)));
            TextView duration = text(Ui.duration(item.optDouble("duration")), 11, Color.WHITE);
            duration.setPadding(dp(3), 0, 0, 0);
            badge.addView(duration);
            FrameLayout.LayoutParams position =
                    new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
            position.setMargins(dp(4), 0, dp(4), dp(4));
            media.addView(badge, position);
        }
        String day = item.optString("album_date");
        String age = Ui.age(host.birthday(), day);
        media.setContentDescription(
                (age.isEmpty() ? "" : age + "，") + Ui.date(day) + (video ? "的视频" : "的照片"));
        media.setFocusable(true);
        media.setOnClickListener(
                v -> {
                    rememberPosition();
                    host.openMedia(this, item);
                });
        return media;
    }

    private void load() {
        if (loading || refreshing || !hasMore || invalidated) return;
        final int request = requestRevision;
        loading = true;
        failed = false;
        refreshFailed = false;
        failureMessage = "";
        updateLabels();
        String path = "/api/media?limit=60&kind=" + kind + "&favorite=" + favorites;
        if (!month.isEmpty()) path += "&month=" + ApiClient.encode(month);
        if (!cursor.isEmpty()) path += "&cursor=" + ApiClient.encode(cursor);
        host.request(
                path,
                result -> {
                    if (invalidated || request != requestRevision) return;
                    loading = false;
                    JSONArray incoming = result.optJSONArray("items");
                    if (incoming != null)
                        for (int i = 0; i < incoming.length(); i++) {
                            JSONObject item = incoming.getJSONObject(i);
                            if (ids.add(item.optString("id"))) items.add(item);
                        }
                    cursor = result.isNull("next_cursor") ? "" : result.optString("next_cursor");
                    hasMore = !cursor.isEmpty();
                    total = result.optInt("total", items.size());
                    if (adapter != null) adapter.notifyDataSetChanged();
                    updateLabels();
                },
                message -> {
                    if (invalidated || request != requestRevision) return;
                    loading = false;
                    failed = true;
                    failureMessage = message;
                    updateLabels();
                });
    }

    private void updateLabels() {
        if (summary != null)
            summary.setText(total > 0 ? "共 " + total + (kind.equals("video") ? " 个视频" : " 项") : "");
        if (footer != null)
            footer.setText(
                    failed
                            ? failureMessage + "，点这里重试"
                            : refreshing
                                    ? "正在更新…"
                                    : loading
                                            ? "正在读取…"
                                            : items.isEmpty() ? "" : hasMore ? "继续往下看" : "已经看完了");
        if (emptyState == null) return;
        boolean empty = items.isEmpty();
        list.setVisibility(empty ? View.INVISIBLE : View.VISIBLE);
        emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (!empty) return;
        boolean waiting = loading || refreshing;
        emptyTitle.setText(
                waiting
                        ? "正在读取…"
                        : failed
                                ? "暂时没有读取成功"
                                : favorites
                                        ? "还没有收藏"
                                        : kind.equals("video") ? "还没有视频" : "这个月还没有照片");
        emptyDescription.setText(
                waiting
                        ? ""
                        : failed
                                ? failureMessage
                                : favorites
                                        ? "在喜欢的照片或视频上点一下爱心，就能收在这里。"
                                        : kind.equals("video")
                                                ? "上传视频后，可以在这里回看。"
                                                : "照片和视频上传后，会按拍摄月份整理。 ");
        emptyAction.setVisibility(waiting ? View.GONE : View.VISIBLE);
        emptyAction.setText(
                failed
                        ? "重新读取"
                        : returnPage.equals("me")
                                ? "返回我的"
                                : returnPage.equals("months") ? "返回月份回忆" : "返回成长时光");
    }

    void rememberPosition() {
        if (list == null || list.getChildAt(0) == null) return;
        View first = list.getChildAt(0);
        firstPosition = list.getFirstVisiblePosition();
        firstOffset = first.getTop();
        bookmark.remember(itemIds(items), firstPosition, firstOffset, COLUMNS);
    }

    void markDirty() {
        dirty = true;
    }

    void refresh() {
        refresh(true);
    }

    private void retryOrLoad() {
        if (failed && refreshFailed) refresh();
        else load();
    }

    private void refresh(boolean rememberVisiblePosition) {
        if (invalidated || refreshing) return;
        if (rememberVisiblePosition) rememberPosition();
        refreshing = true;
        loading = false;
        failed = false;
        failureMessage = "";
        final int revision = ++requestRevision;
        updateLabels();
        requestRefresh(new ArrayList<>(), "", Math.max(60, items.size()), revision);
    }

    private void requestRefresh(
            ArrayList<JSONObject> fresh, String next, int wanted, int revision) {
        String path = "/api/media?limit=60&kind=" + kind + "&favorite=" + favorites;
        if (!month.isEmpty()) path += "&month=" + ApiClient.encode(month);
        if (!next.isEmpty()) path += "&cursor=" + ApiClient.encode(next);
        host.request(
                path,
                result -> {
                    if (invalidated || revision != requestRevision) return;
                    JSONArray incoming = result.optJSONArray("items");
                    if (incoming != null)
                        for (int i = 0; i < incoming.length(); i++)
                            fresh.add(incoming.getJSONObject(i));
                    String following =
                            result.isNull("next_cursor") ? "" : result.optString("next_cursor");
                    if (!following.isEmpty() && fresh.size() < wanted) {
                        requestRefresh(fresh, following, wanted, revision);
                        return;
                    }
                    items.clear();
                    ids.clear();
                    for (JSONObject item : fresh)
                        if (ids.add(item.optString("id"))) items.add(item);
                    cursor = following;
                    hasMore = !cursor.isEmpty();
                    total = result.optInt("total", items.size());
                    dirty = refreshing = false;
                    refreshFailed = false;
                    if (adapter != null) adapter.notifyDataSetChanged();
                    updateLabels();
                    firstPosition = bookmark.row(itemIds(items), COLUMNS);
                    firstOffset = bookmark.offset();
                    if (list != null) list.setSelectionFromTop(firstPosition, firstOffset);
                },
                message -> {
                    if (invalidated || revision != requestRevision) return;
                    refreshing = false;
                    failed = true;
                    refreshFailed = true;
                    failureMessage = message;
                    updateLabels();
                });
    }

    private List<String> itemIds(List<JSONObject> source) {
        ArrayList<String> result = new ArrayList<>();
        for (JSONObject item : source) result.add(item.optString("id"));
        return result;
    }

    void relayout() {
        rememberPosition();
        if (adapter != null) adapter.notifyDataSetChanged();
        if (list != null) list.setSelectionFromTop(firstPosition, firstOffset);
    }

    void updateMedia(JSONObject item) {
        String id = item.optString("id");
        for (int i = 0; i < items.size(); i++) {
            if (!items.get(i).optString("id").equals(id)) continue;
            boolean keep =
                    (item.isNull("deleted_at") || item.optString("deleted_at").isEmpty())
                            && (!favorites || item.optBoolean("favorite"))
                            && (month.isEmpty()
                                    || item.optString("album_date").startsWith(month + "-"));
            if (keep) items.set(i, item);
            else {
                items.remove(i);
                ids.remove(id);
                total = Math.max(0, total - 1);
            }
            Collections.sort(
                    items,
                    (a, b) -> {
                        int date = b.optString("album_date").compareTo(a.optString("album_date"));
                        if (date != 0) return date;
                        int stamp =
                                Double.compare(
                                        a.optDouble("sort_time", 0), b.optDouble("sort_time", 0));
                        return stamp != 0 ? stamp : a.optString("id").compareTo(b.optString("id"));
                    });
            if (adapter != null) adapter.notifyDataSetChanged();
            updateLabels();
            return;
        }
    }

    void dispose() {
        invalidated = true;
        cancelImages(list);
        list = null;
        adapter = null;
        emptyState = null;
        emptyTitle = emptyDescription = emptyAction = summary = footer = null;
    }

    private void cancelImages(View view) {
        if (view instanceof ImageView) host.images().cancel((ImageView) view);
        else if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) cancelImages(group.getChildAt(i));
        }
    }
}
