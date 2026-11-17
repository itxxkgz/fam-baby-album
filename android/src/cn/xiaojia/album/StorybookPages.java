package cn.xiaojia.album;

import android.app.Activity;
import android.content.ClipData;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.view.DragEvent;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;

/** Monthly stories keep their own reading, editing, and media-selection context. */
final class StorybookPages {
    private static final int MAX_PAGES = 80;
    private final MemoryFeatureHost host;
    private final Activity activity;
    private JSONArray months = new JSONArray();
    private String year = "", screen = "", month = "";
    private JSONObject book;
    private int serial, directoryPosition, directoryOffset, pageIndex, editScroll;
    private boolean disposed, dirty, saving, preview, mediaOpen, directEntry, chrome = true;
    private View currentView;
    private ListView directoryList;
    private ScrollView editorScroll;
    private StorySwipePager pager;
    private View readerTop, readerBottom;
    private TextView pageNumber;
    private final HashMap<String, Integer> readingPositions = new HashMap<>();
    private final ArrayList<Page> draft = new ArrayList<>();
    private String draftTitle = "", draftCover = "";
    private long draftRevision;
    private final ArrayList<JSONObject> pickerItems = new ArrayList<>();
    private final LinkedHashSet<String> pickerIds = new LinkedHashSet<>();
    private final LinkedHashMap<String, JSONObject> chosen = new LinkedHashMap<>();
    private boolean coverPicker, pickerLoading, pickerMore, pickerFailed;
    private String pickerCursor = "", pickerError = "";
    private int pickerPosition, pickerOffset;
    private ListView pickerList;
    private BaseAdapter pickerAdapter;
    private TextView pickerFooter, pickerDone;
    private WarmSheet storyDialog, storyExitDialog;

    private static final class Page {
        JSONObject media;
        String text, author;

        Page(JSONObject media, String text, String author) {
            this.media = media;
            this.text = text;
            this.author = author;
        }
    }

    StorybookPages(MemoryFeatureHost host) {
        this.host = host;
        activity = host.activity();
    }

    private int dp(float n) {
        return Ui.dp(activity, n);
    }

    private TextView text(String s, int n, int color) {
        return Ui.text(activity, s, n, color);
    }

    private void toast(String s) {
        Toast.makeText(activity, s, Toast.LENGTH_SHORT).show();
    }

    private String id(JSONObject item) {
        return item == null ? "" : item.optString("id");
    }

    private boolean allowed() {
        return host.canEdit() && book != null && book.optBoolean("can_edit");
    }

    private JSONArray values(JSONObject object, String key) {
        JSONArray a = object.optJSONArray(key);
        return a == null ? new JSONArray() : a;
    }

    private JSONObject copy(JSONObject value) {
        try {
            return new JSONObject(value.toString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void cancelImages(View view) {
        if (view instanceof ImageView) host.images().cancel((ImageView) view);
        if (view instanceof ViewGroup)
            for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
                cancelImages(((ViewGroup) view).getChildAt(i));
    }

    private void present(String key, View page, Runnable back) {
        serial++;
        cancelImages(currentView);
        if (pager != null) pager.stop();
        pager = null;
        currentView = page;
        screen = key;
        host.showFeature("storybook_" + key, page, back);
    }

    private TextView button(String label, boolean primary, Runnable action) {
        TextView view = text(label, 15, primary ? Color.WHITE : Ui.ACCENT_DARK);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(14), dp(10), dp(14), dp(10));
        view.setMinimumHeight(dp(48));
        view.setBackground(Ui.button(activity, primary));
        view.setOnClickListener(v -> action.run());
        return view;
    }

    private View icon(String name, String label, Runnable action) {
        FrameLayout frame = new FrameLayout(activity);
        frame.setContentDescription(label);
        frame.addView(
                new Ui.Icon(activity, name, Ui.INK),
                new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
        frame.setBackground(Ui.ripple(Ui.BG, 24, activity));
        frame.setOnClickListener(v -> action.run());
        return frame;
    }

    private LinearLayout bar(String title, Runnable back, String action, Runnable click) {
        LinearLayout bar = Ui.row(activity);
        bar.setPadding(dp(4), 0, dp(12), 0);
        bar.setBackgroundColor(Ui.BG);
        bar.addView(icon("back", "返回", back), new LinearLayout.LayoutParams(dp(48), dp(52)));
        TextView label = text(title, 19, Ui.INK);
        label.setTypeface(null, Typeface.BOLD);
        label.setMaxLines(2);
        bar.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        if (action != null) {
            TextView link = text(action, 14, Ui.ACCENT_DARK);
            link.setPadding(dp(12), 0, dp(10), 0);
            link.setGravity(Gravity.CENTER);
            link.setMinHeight(dp(48));
            link.setOnClickListener(v -> click.run());
            bar.addView(link);
        }
        bar.setMinimumHeight(dp(52));
        return bar;
    }

    private LinearLayout base() {
        LinearLayout page = Ui.column(activity);
        page.setBackgroundColor(Ui.BG);
        return page;
    }

    private ImageView photo(JSONObject media, int size) {
        ImageView image = new ImageView(activity);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setBackgroundColor(Ui.SOFT);
        if (media != null) host.images().load(image, media.optString("thumbnail_url"), size);
        return image;
    }

    private void rememberDirectory() {
        if (directoryList != null && directoryList.getChildAt(0) != null) {
            directoryPosition = directoryList.getFirstVisiblePosition();
            directoryOffset = directoryList.getChildAt(0).getTop();
        }
    }

    private void leave() {
        rememberDirectory();
        serial++;
        screen = "";
        cancelImages(currentView);
        if (pager != null) pager.stop();
        host.returnToTime();
    }

    void show() {
        disposed = false;
        directEntry = false;
        if (months.length() == 0) loadDirectory();
        else showDirectory();
    }

    /** Open a homepage cover and return to that same homepage after reading. */
    void showMonth(String value) {
        disposed = false;
        directEntry = true;
        openBook(value, false);
    }

    private void leaveBook() {
        if (directEntry) leave();
        else showDirectory();
    }

    private void loadDirectory() {
        LinearLayout page = base();
        page.addView(bar("每月故事册", this::leave, null, null));
        TextView loading = text("正在翻开每个月的回忆…", 14, Ui.MUTED);
        loading.setGravity(Gravity.CENTER);
        page.addView(loading, new LinearLayout.LayoutParams(-1, 0, 1));
        present("directory_loading", page, this::leave);
        int request = serial;
        host.request(
                "GET",
                "/api/storybooks",
                null,
                result -> {
                    if (disposed || request != serial) return;
                    months = values(result, "months");
                    if (year.isEmpty() && months.length() > 0)
                        year = months.optJSONObject(0).optString("month").substring(0, 4);
                    showDirectory();
                },
                message -> {
                    if (disposed || request != serial) return;
                    loading.setText(message + "\n轻点这里重新读取");
                    loading.setOnClickListener(v -> loadDirectory());
                });
    }

    private void showDirectory() {
        LinearLayout page = base();
        page.addView(
                bar(
                        "每月故事册",
                        this::leave,
                        host.canEdit() ? "制作" : "刷新",
                        host.canEdit() ? this::chooseMonth : this::loadDirectory));
        LinearLayout intro = Ui.row(activity);
        intro.setPadding(dp(18), dp(2), dp(18), dp(8));
        TextView caption = text("把一个月的小日子，收成一本书", 13, Ui.MUTED);
        intro.addView(caption, new LinearLayout.LayoutParams(0, -2, 1));
        if (host.canEdit()) {
            TextView refresh = text("刷新", 12, Ui.ACCENT_DARK);
            refresh.setMinHeight(dp(40));
            refresh.setPadding(dp(10), 0, 0, 0);
            refresh.setOnClickListener(
                    v -> {
                        rememberDirectory();
                        loadDirectory();
                    });
            intro.addView(refresh);
        }
        page.addView(intro);
        if (months.length() == 0) {
            TextView empty = text("上传照片和视频后，每个月的故事会出现在这里", 15, Ui.MUTED);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(32), 0, dp(32), 0);
            page.addView(empty, new LinearLayout.LayoutParams(-1, 0, 1));
            present("directory", page, this::leave);
            return;
        }
        ArrayList<JSONObject> selected = new ArrayList<>();
        LinkedHashSet<String> years = new LinkedHashSet<>();
        for (int i = 0; i < months.length(); i++) {
            JSONObject item = months.optJSONObject(i);
            String y = item.optString("month").substring(0, 4);
            years.add(y);
            if (y.equals(year)) selected.add(item);
        }
        if (selected.isEmpty() && !years.isEmpty()) {
            year = years.iterator().next();
            showDirectory();
            return;
        }
        directoryList = new ListView(activity);
        directoryList.setDivider(null);
        directoryList.setSelector(android.R.color.transparent);
        directoryList.setVerticalScrollBarEnabled(false);
        directoryList.setAdapter(
                new BaseAdapter() {
                    public int getCount() {
                        return (selected.size() + 1) / 2;
                    }

                    public Object getItem(int p) {
                        return selected.get(p * 2);
                    }

                    public long getItemId(int p) {
                        return p;
                    }

                    public boolean isEnabled(int p) {
                        return false;
                    }

                    public View getView(int position, View old, ViewGroup parent) {
                        cancelImages(old);
                        LinearLayout row = Ui.row(activity);
                        row.setGravity(Gravity.TOP);
                        row.setPadding(dp(16), dp(5), dp(16), dp(8));
                        int width =
                                (activity.getResources().getDisplayMetrics().widthPixels - dp(44))
                                        / 2;
                        for (int column = 0; column < 2; column++) {
                            int index = position * 2 + column;
                            LinearLayout.LayoutParams params =
                                    new LinearLayout.LayoutParams(0, -2, 1);
                            if (column == 0) params.rightMargin = dp(12);
                            if (index >= selected.size()) {
                                row.addView(new View(activity), params);
                                continue;
                            }
                            JSONObject entry = selected.get(index);
                            LinearLayout card = Ui.column(activity);
                            card.setBackground(Ui.card(activity));
                            card.setClipToOutline(true);
                            card.addView(
                                    photo(entry.optJSONObject("cover"), 600),
                                    new LinearLayout.LayoutParams(-1, width * 6 / 5));
                            LinearLayout labels = Ui.column(activity);
                            labels.setPadding(dp(12), dp(10), dp(10), dp(12));
                            TextView name = text(Ui.month(entry.optString("month")), 17, Ui.INK);
                            name.setTypeface(null, Typeface.BOLD);
                            labels.addView(name);
                            String title = entry.optString("title");
                            if (!title.isEmpty()) {
                                TextView sub = text(title, 13, Ui.INK);
                                sub.setMaxLines(2);
                                sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
                                sub.setPadding(0, dp(4), 0, 0);
                                labels.addView(sub);
                            }
                            TextView count =
                                    text(
                                            entry.optInt("page_count")
                                                    + "页 · "
                                                    + entry.optInt("total")
                                                    + "个瞬间",
                                            11,
                                            Ui.MUTED);
                            count.setPadding(0, dp(6), 0, 0);
                            labels.addView(count);
                            card.addView(labels);
                            card.setContentDescription("翻开" + Ui.month(entry.optString("month")));
                            card.setOnClickListener(
                                    v -> {
                                        rememberDirectory();
                                        openBook(entry.optString("month"), false);
                                    });
                            row.addView(card, params);
                        }
                        return row;
                    }
                });
        page.addView(directoryList, new LinearLayout.LayoutParams(-1, 0, 1));
        HorizontalScrollView strip = new HorizontalScrollView(activity);
        strip.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = Ui.row(activity);
        chips.setPadding(dp(16), dp(4), dp(16), dp(4));
        strip.addView(chips);
        for (String y : years) {
            TextView chip = text(y + "年", 14, y.equals(year) ? Ui.ACCENT_DARK : Ui.MUTED);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(20), 0, dp(20), 0);
            chip.setBackground(Ui.chip(activity, y.equals(year)));
            chip.setOnClickListener(
                    v -> {
                        year = y;
                        directoryPosition = directoryOffset = 0;
                        showDirectory();
                    });
            chips.addView(chip, new LinearLayout.LayoutParams(-2, dp(44)));
        }
        page.addView(strip, new LinearLayout.LayoutParams(-1, dp(52)));
        present("directory", page, this::leave);
        directoryList.setSelectionFromTop(directoryPosition, directoryOffset);
    }

    private void chooseMonth() {
        if (months.length() == 0) {
            toast("先上传这个月的照片或视频，就可以整理故事册了");
            return;
        }
        String[] labels = new String[months.length()];
        for (int i = 0; i < labels.length; i++)
            labels[i] = Ui.month(months.optJSONObject(i).optString("month"));
        choices(
                "整理哪个月的故事？",
                "calendar",
                labels,
                -1,
                which -> {
                    rememberDirectory();
                    openBook(months.optJSONObject(which).optString("month"), true);
                });
    }

    private void choices(
            String title,
            String icon,
            String[] labels,
            int selected,
            java.util.function.IntConsumer action) {
        final int request = serial;
        WarmSheet sheet = new WarmSheet(activity, title);
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            sheet.body.addView(
                    SecondaryUi.row(
                            activity,
                            icon,
                            labels[i],
                            i == selected ? "正在看" : "",
                            () -> {
                                sheet.dismiss();
                                if (!disposed && request == serial) action.accept(index);
                            }));
            if (i < labels.length - 1) SecondaryUi.divider(activity, sheet.body);
        }
        sheet.show();
    }

    private void openBook(String value, boolean edit) {
        month = value;
        LinearLayout page = base();
        page.addView(bar(Ui.month(month), this::leaveBook, null, null));
        TextView loading = text("正在翻开故事册…", 14, Ui.MUTED);
        loading.setGravity(Gravity.CENTER);
        page.addView(loading, new LinearLayout.LayoutParams(-1, 0, 1));
        present("loading", page, this::leaveBook);
        int request = serial;
        host.request(
                "GET",
                "/api/storybooks/" + ApiClient.encode(month),
                null,
                result -> {
                    if (disposed || request != serial) return;
                    book = result;
                    pageIndex =
                            readingPositions.containsKey(month) ? readingPositions.get(month) : 0;
                    draft.clear();
                    dirty = false;
                    if (edit && allowed()) {
                        startEditing();
                    } else showReader(false);
                },
                message -> {
                    if (disposed || request != serial) return;
                    loading.setText(message + "\n轻点这里重新读取");
                    loading.setOnClickListener(v -> openBook(value, edit));
                });
    }

    private ArrayList<Page> bookPages() {
        ArrayList<Page> result = new ArrayList<>();
        if (book != null) {
            JSONArray pages = values(book, "pages");
            for (int i = 0; i < pages.length(); i++) {
                JSONObject p = pages.optJSONObject(i);
                if (p != null && p.optJSONObject("media") != null)
                    result.add(
                            new Page(
                                    p.optJSONObject("media"),
                                    p.optString("text"),
                                    p.optString("author_name")));
            }
        }
        return result;
    }

    private ArrayList<Page> readerPages() {
        return preview ? draft : bookPages();
    }

    private void showReader(boolean isPreview) {
        preview = isPreview;
        ArrayList<Page> pages = readerPages();
        pageIndex = Math.max(0, Math.min(pageIndex, pages.size() - 1));
        chrome = true;
        LinearLayout page = base();
        Runnable back = isPreview ? this::showEditor : this::leaveBook;
        LinearLayout top =
                bar(
                        Ui.month(month),
                        back,
                        isPreview ? "返回整理" : allowed() ? "整理" : null,
                        isPreview ? this::showEditor : this::startEditing);
        readerTop = top;
        page.addView(top);
        if (pages.isEmpty()) {
            TextView empty = text("这本故事册暂时没有可阅读的内容", 15, Ui.MUTED);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(24), 0, dp(24), 0);
            page.addView(empty, new LinearLayout.LayoutParams(-1, 0, 1));
            if (allowed()) page.addView(button("重新整理", false, this::startEditing));
            present(isPreview ? "preview" : "reader", page, back);
            return;
        }
        StorySwipePager nextPager = new StorySwipePager(activity);
        page.addView(nextPager, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout footer = Ui.row(activity);
        footer.setPadding(dp(8), 0, dp(8), 0);
        footer.setBackgroundColor(Ui.BG);
        footer.addView(
                icon("grid", "故事册目录", this::showContents),
                new LinearLayout.LayoutParams(dp(48), dp(48)));
        pageNumber = text("", 13, Ui.MUTED);
        pageNumber.setGravity(Gravity.CENTER);
        footer.addView(pageNumber, new LinearLayout.LayoutParams(0, dp(48), 1));
        footer.addView(
                icon("back", "上一页", () -> movePage(-1)),
                new LinearLayout.LayoutParams(dp(48), dp(48)));
        footer.addView(
                icon("next", "下一页", () -> movePage(1)),
                new LinearLayout.LayoutParams(dp(48), dp(48)));
        readerBottom = footer;
        page.addView(footer);
        present(isPreview ? "preview" : "reader", page, back);
        pager = nextPager;
        pager.onSelected(this::movePage);
        bindReader();
    }

    private void bindReader() {
        if (pager == null) return;
        ArrayList<Page> pages = readerPages();
        if (pages.isEmpty()) return;
        cancelImages(pager);
        pageIndex = Math.max(0, Math.min(pageIndex, pages.size() - 1));
        pager.pages(
                pageIndex > 0 ? readerPage(pages.get(pageIndex - 1), false) : null,
                readerPage(pages.get(pageIndex), true),
                pageIndex + 1 < pages.size() ? readerPage(pages.get(pageIndex + 1), false) : null);
        pageNumber.setText((pageIndex + 1) + " / " + pages.size());
        if (!preview) readingPositions.put(month, pageIndex);
    }

    private void movePage(int direction) {
        ArrayList<Page> pages = readerPages();
        int next = pageIndex + direction;
        if (next < 0 || next >= pages.size()) {
            toast(next < 0 ? "已经是第一页了" : "这本故事册已经翻完了");
            return;
        }
        pageIndex = next;
        bindReader();
    }

    private View readerPage(Page story, boolean current) {
        ScrollView scroll = new ScrollView(activity);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFillViewport(true);
        LinearLayout content = Ui.column(activity);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        FrameLayout picture = new FrameLayout(activity);
        ImageView image = new ImageView(activity);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setBackgroundColor(Ui.SOFT);
        picture.addView(image, new FrameLayout.LayoutParams(-1, -1));
        int width = activity.getResources().getDisplayMetrics().widthPixels;
        int mediaWidth = story.media.optInt("width"), mediaHeight = story.media.optInt("height");
        float naturalHeight =
                mediaWidth > 0 && mediaHeight > 0
                        ? width * ((float) mediaHeight / mediaWidth)
                        : width * 1.08f;
        int height =
                Math.max(
                        1,
                        Math.round(
                                Math.min(
                                        naturalHeight,
                                        activity.getResources().getDisplayMetrics().heightPixels
                                                * .62f)));
        content.addView(picture, new LinearLayout.LayoutParams(-1, height));
        if (current) {
            TextView notice = text("正在读取画面…", 12, Ui.MUTED);
            notice.setPadding(dp(10), dp(5), dp(10), dp(5));
            notice.setBackground(Ui.round(Ui.BG, 12, activity));
            FrameLayout.LayoutParams np =
                    new FrameLayout.LayoutParams(
                            -2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            np.bottomMargin = dp(12);
            picture.addView(notice, np);
            String original =
                    story.media.optString("kind").equals("video")
                            ? story.media.optString("thumbnail_url")
                            : story.media.optString("file_url");
            Runnable load =
                    () -> {
                        notice.setText("正在读取画面…");
                        notice.setVisibility(View.VISIBLE);
                        host.images()
                                .loadProgressive(
                                        image,
                                        story.media.optString("thumbnail_url"),
                                        original,
                                        1600,
                                        () -> notice.setVisibility(View.GONE),
                                        () -> {
                                            notice.setText("画面暂未载入，轻点重试");
                                            notice.setVisibility(View.VISIBLE);
                                        });
                    };
            notice.setOnClickListener(v -> load.run());
            load.run();
        } else host.images().load(image, story.media.optString("thumbnail_url"), 900);
        if (current) {
            GestureDetector gesture =
                    new GestureDetector(
                            activity,
                            new GestureDetector.SimpleOnGestureListener() {
                                public boolean onDown(MotionEvent e) {
                                    return true;
                                }

                                public boolean onSingleTapConfirmed(MotionEvent e) {
                                    toggleChrome();
                                    return true;
                                }

                                public boolean onDoubleTap(MotionEvent e) {
                                    openMedia(story.media);
                                    return true;
                                }
                            });
            image.setOnTouchListener((v, event) -> gesture.onTouchEvent(event));
            image.setContentDescription("轻点收起操作，双击查看大图");
        }
        if (story.media.optString("kind").equals("video")) {
            FrameLayout play = new FrameLayout(activity);
            play.setBackground(Ui.round(0x992C211D, 30, activity));
            play.addView(
                    new Ui.Icon(activity, "play", Color.WHITE),
                    new FrameLayout.LayoutParams(dp(27), dp(27), Gravity.CENTER));
            play.setContentDescription("播放这段视频");
            if (current) play.setOnClickListener(v -> openMedia(story.media));
            picture.addView(play, new FrameLayout.LayoutParams(dp(60), dp(60), Gravity.CENTER));
            TextView length = text(Ui.duration(story.media.optDouble("duration")), 12, Color.WHITE);
            length.setPadding(dp(8), dp(3), dp(8), dp(3));
            length.setBackground(Ui.round(0x992C211D, 8, activity));
            FrameLayout.LayoutParams badge =
                    new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.RIGHT);
            badge.setMargins(0, 0, dp(12), dp(12));
            picture.addView(length, badge);
        }
        LinearLayout words = Ui.column(activity);
        words.setPadding(dp(22), dp(18), dp(22), dp(24));
        String title = preview ? draftTitle : book.optString("title");
        TextView heading = text(title, 23, Ui.INK);
        heading.setTypeface(null, Typeface.BOLD);
        words.addView(heading);
        TextView age =
                text(
                        Ui.age(
                                host.profile().optString("birthday"),
                                story.media.optString("album_date")),
                        15,
                        Ui.INK);
        age.setPadding(0, dp(12), 0, 0);
        words.addView(age);
        TextView date = text(Ui.date(story.media.optString("album_date")), 12, Ui.MUTED);
        date.setPadding(0, dp(3), 0, 0);
        words.addView(date);
        if (!story.text.isEmpty()) {
            TextView prose = text(story.text, 17, Ui.INK);
            prose.setLineSpacing(dp(4), 1.08f);
            prose.setPadding(0, dp(18), 0, 0);
            words.addView(prose);
            if (!story.author.isEmpty()) {
                TextView author = text(story.author + "写下的故事", 12, Ui.MUTED);
                author.setGravity(Gravity.RIGHT);
                author.setPadding(0, dp(12), 0, 0);
                words.addView(author, new LinearLayout.LayoutParams(-1, -2));
            }
        }
        content.addView(words);
        return scroll;
    }

    private void toggleChrome() {
        chrome = !chrome;
        if (readerTop != null) readerTop.setVisibility(chrome ? View.VISIBLE : View.GONE);
        if (readerBottom != null) readerBottom.setVisibility(chrome ? View.VISIBLE : View.GONE);
    }

    private void openMedia(JSONObject media) {
        ArrayList<JSONObject> scope = new ArrayList<>();
        for (Page page : readerPages()) scope.add(page.media);
        final boolean wasPreview = preview;
        final int saved = pageIndex;
        mediaOpen = true;
        host.openFeatureMedia(
                media,
                scope,
                () -> {
                    mediaOpen = false;
                    pageIndex = saved;
                    showReader(wasPreview);
                });
    }

    private void showContents() {
        ArrayList<Page> pages = readerPages();
        String[] labels = new String[pages.size()];
        for (int i = 0; i < pages.size(); i++) {
            Page page = pages.get(i);
            labels[i] =
                    "第"
                            + (i + 1)
                            + "页 · "
                            + Ui.date(page.media.optString("album_date"))
                            + (page.media.optString("kind").equals("video") ? " · 视频" : "");
        }
        choices(
                "故事册目录",
                "book",
                labels,
                pageIndex,
                which -> {
                    pageIndex = which;
                    bindReader();
                });
    }

    private String saveError = "";

    private void startEditing() {
        if (!allowed()) {
            toast("你目前可以阅读故事册，整理需要上传权限");
            return;
        }
        draft.clear();
        for (Page item : bookPages()) draft.add(new Page(copy(item.media), item.text, item.author));
        draftTitle = book.optString("title");
        draftCover = id(book.optJSONObject("cover"));
        draftRevision = book.optLong("revision");
        dirty = false;
        saving = false;
        saveError = "";
        editScroll = 0;
        showEditor();
    }

    private void rememberEditor() {
        if (editorScroll != null) editScroll = editorScroll.getScrollY();
    }

    private void editorBack() {
        if (saving) {
            toast("故事册正在保存，请稍等一下");
            return;
        }
        if (!dirty) {
            showReader(false);
            return;
        }
        final int request = serial;
        boolean editable = allowed();
        WarmSheet sheet = new WarmSheet(activity, editable ? "还有改动没有保存" : "草稿还保留在这里");
        SecondaryUi.note(
                activity, sheet.body, editable ? "先保存这次整理，再离开故事册？" : "你目前没有保存权限，可以先复制文字。草稿仍保留在这里。");
        sheet.primary(
                editable ? "保存" : "复制文字",
                () -> {
                    sheet.dismiss();
                    if (disposed || request != serial) return;
                    if (editable) saveBook();
                    else copyDraftText();
                });
        sheet.secondary("继续整理", sheet::dismiss);
        sheet.secondary(
                "放弃改动",
                () -> {
                    sheet.dismiss();
                    if (disposed || request != serial) return;
                    dirty = false;
                    draft.clear();
                    showReader(false);
                });
        sheet.show();
    }

    private void showEditor() {
        LinearLayout page = base();
        page.addView(
                bar(
                        "整理故事册",
                        this::editorBack,
                        "预览",
                        () -> {
                            if (saving) return;
                            rememberEditor();
                            pageIndex = 0;
                            showReader(true);
                        }));
        editorScroll =
                new ScrollView(activity) {
                    @Override
                    public boolean dispatchDragEvent(DragEvent event) {
                        if (event.getLocalState() instanceof Page
                                && event.getAction() == DragEvent.ACTION_DRAG_LOCATION) {
                            if (event.getY() < dp(80)) scrollBy(0, -dp(12));
                            else if (event.getY() > getHeight() - dp(80)) scrollBy(0, dp(12));
                        }
                        return super.dispatchDragEvent(event);
                    }
                };
        editorScroll.setVerticalScrollBarEnabled(false);
        LinearLayout panel = Ui.column(activity);
        panel.setPadding(dp(18), dp(12), dp(18), dp(24));
        editorScroll.addView(panel);
        LinearLayout cover = Ui.row(activity);
        JSONObject coverMedia = null;
        for (Page story : draft) if (id(story.media).equals(draftCover)) coverMedia = story.media;
        FrameLayout coverFrame = new FrameLayout(activity);
        coverFrame.setBackground(Ui.round(Ui.SOFT, 12, activity));
        coverFrame.setClipToOutline(true);
        coverFrame.addView(photo(coverMedia, 400), new FrameLayout.LayoutParams(-1, -1));
        cover.addView(coverFrame, new LinearLayout.LayoutParams(dp(94), dp(94)));
        LinearLayout labels = Ui.column(activity);
        labels.setPadding(dp(16), 0, 0, 0);
        TextView name = text(Ui.month(month), 20, Ui.INK);
        name.setTypeface(null, Typeface.BOLD);
        labels.addView(name);
        TextView change = text("更换封面  ›", 14, Ui.MUTED);
        change.setPadding(0, dp(8), 0, 0);
        labels.addView(change);
        cover.addView(labels);
        cover.setContentDescription("更换故事册封面");
        cover.setOnClickListener(
                v -> {
                    if (!saving) {
                        rememberEditor();
                        openPicker(true);
                    }
                });
        panel.addView(cover);
        EditText title = new EditText(activity);
        title.setContentDescription("故事册标题");
        title.setText(draftTitle);
        title.setTextSize(20);
        title.setTextColor(Ui.INK);
        title.setHint("给这个月起个名字");
        title.setHintTextColor(Ui.MUTED);
        title.setSingleLine(false);
        title.setMaxLines(3);
        title.setFilters(new InputFilter[] {new InputFilter.LengthFilter(120)});
        title.setPadding(0, dp(18), 0, dp(12));
        title.setEnabled(!saving);
        title.addTextChangedListener(
                new TextWatcher() {
                    public void beforeTextChanged(CharSequence s, int st, int c, int a) {}

                    public void onTextChanged(CharSequence s, int st, int before, int count) {
                        draftTitle = s.toString();
                        dirty = true;
                    }

                    public void afterTextChanged(Editable s) {}
                });
        panel.addView(title, new LinearLayout.LayoutParams(-1, -2));
        if (!allowed()) {
            TextView notice = text("权限已更新，草稿仍保留在这里。可以继续预览或复制文字，暂时不能保存。", 13, Ui.MUTED);
            notice.setPadding(0, dp(12), 0, dp(10));
            panel.addView(notice);
            panel.addView(button("复制草稿文字", false, this::copyDraftText));
        }
        if (!saveError.isEmpty()) {
            TextView error = text(saveError, 13, Ui.ERROR);
            error.setPadding(0, dp(10), 0, dp(10));
            panel.addView(error);
        }
        LinearLayout rows = Ui.column(activity);
        panel.addView(rows);
        if (draft.isEmpty()) {
            TextView empty = text("先加入照片或视频，再整理这个月的故事", 14, Ui.MUTED);
            empty.setPadding(0, dp(24), 0, dp(24));
            rows.addView(empty);
        }
        for (Page story : draft) {
            LinearLayout row = Ui.row(activity);
            row.setPadding(dp(8), dp(8), dp(6), dp(8));
            row.setBackground(Ui.border(Ui.BG, 12, Ui.LINE, activity));
            FrameLayout picture = new FrameLayout(activity);
            picture.setBackground(Ui.round(Ui.SOFT, 8, activity));
            picture.setClipToOutline(true);
            picture.addView(photo(story.media, 400), new FrameLayout.LayoutParams(-1, -1));
            if (story.media.optString("kind").equals("video")) {
                TextView duration =
                        text(
                                "▶ " + Ui.duration(story.media.optDouble("duration")),
                                11,
                                Color.WHITE);
                duration.setPadding(dp(4), dp(1), dp(4), dp(1));
                duration.setBackground(Ui.round(0xA0302522, 4, activity));
                picture.addView(
                        duration,
                        new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.RIGHT));
            }
            row.addView(picture, new LinearLayout.LayoutParams(dp(80), dp(80)));
            LinearLayout words = Ui.column(activity);
            words.setPadding(dp(12), 0, dp(4), 0);
            TextView age =
                    text(
                            Ui.age(
                                    host.profile().optString("birthday"),
                                    story.media.optString("album_date")),
                            15,
                            Ui.INK);
            age.setTypeface(null, Typeface.BOLD);
            age.setMaxLines(2);
            words.addView(age);
            TextView body =
                    text(
                            story.text.isEmpty() ? "写下一句话…" : story.text,
                            13,
                            story.text.isEmpty() ? Ui.MUTED : Ui.INK);
            body.setPadding(0, dp(6), 0, 0);
            body.setMaxLines(2);
            body.setEllipsize(android.text.TextUtils.TruncateAt.END);
            words.addView(body);
            row.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
            View handle =
                    new View(activity) {
                        android.graphics.Paint paint =
                                new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

                        @Override
                        protected void onDraw(android.graphics.Canvas canvas) {
                            paint.setColor(Ui.MUTED);
                            paint.setStrokeWidth(dp(1.3f));
                            paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
                            float center = getHeight() / 2f;
                            for (int line = -1; line <= 1; line++)
                                canvas.drawLine(
                                        dp(12),
                                        center + dp(5) * line,
                                        getWidth() - dp(12),
                                        center + dp(5) * line,
                                        paint);
                        }
                    };
            handle.setContentDescription("调整这一页的顺序或移出");
            handle.setOnClickListener(
                    v -> {
                        if (!saving) pageMenu(story);
                    });
            handle.setOnLongClickListener(
                    v -> {
                        if (saving) return false;
                        rememberEditor();
                        return v.startDragAndDrop(
                                ClipData.newPlainText("故事页", "调整顺序"),
                                new View.DragShadowBuilder(row),
                                story,
                                0);
                    });
            row.addView(handle, new LinearLayout.LayoutParams(dp(44), dp(64)));
            row.setContentDescription("编辑" + Ui.date(story.media.optString("album_date")) + "的故事");
            row.setOnClickListener(
                    v -> {
                        if (!saving) editText(story);
                    });
            row.setOnDragListener(
                    (v, event) -> {
                        if (!(event.getLocalState() instanceof Page) || saving) return false;
                        if (event.getAction() == DragEvent.ACTION_DRAG_ENTERED)
                            row.setBackground(Ui.border(Ui.SOFT, 12, Ui.ACCENT, activity));
                        if (event.getAction() == DragEvent.ACTION_DRAG_EXITED
                                || event.getAction() == DragEvent.ACTION_DRAG_ENDED)
                            row.setBackground(Ui.border(Ui.BG, 12, Ui.LINE, activity));
                        if (event.getAction() == DragEvent.ACTION_DROP) {
                            int from = draft.indexOf((Page) event.getLocalState()),
                                    to = draft.indexOf(story);
                            if (StorybookOrder.move(draft, from, to)) {
                                dirty = true;
                                rememberEditor();
                                showEditor();
                            }
                            return true;
                        }
                        return true;
                    });
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
            rp.topMargin = dp(10);
            rows.addView(row, rp);
        }
        TextView add =
                button(
                        "＋ 加入照片或视频",
                        false,
                        () -> {
                            if (saving) return;
                            rememberEditor();
                            openPicker(false);
                        });
        add.setEnabled(!saving && draft.size() < MAX_PAGES);
        LinearLayout.LayoutParams addParams = new LinearLayout.LayoutParams(-1, -2);
        addParams.topMargin = dp(18);
        panel.addView(add, addParams);
        TextView hint = text(draft.size() + "页 · 长按右侧手柄，调整顺序", 12, Ui.MUTED);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(10), 0, 0);
        panel.addView(hint);
        editorScroll.setOnDragListener(
                (v, event) -> {
                    if (!(event.getLocalState() instanceof Page)) return false;
                    if (event.getAction() == DragEvent.ACTION_DRAG_LOCATION) {
                        if (event.getY() < dp(90)) editorScroll.smoothScrollBy(0, -dp(36));
                        else if (event.getY() > editorScroll.getHeight() - dp(90))
                            editorScroll.smoothScrollBy(0, dp(36));
                    }
                    return true;
                });
        page.addView(editorScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout bottom = Ui.column(activity);
        bottom.setPadding(dp(18), dp(8), dp(18), dp(10));
        TextView save =
                button(saving ? "正在保存…" : allowed() ? "完成" : "暂时无法保存", true, this::saveBook);
        save.setEnabled(!saving && allowed());
        save.setAlpha(save.isEnabled() ? 1f : .55f);
        bottom.addView(save, new LinearLayout.LayoutParams(-1, -2));
        page.addView(bottom);
        present("editor", page, this::editorBack);
        final ScrollView displayed = editorScroll;
        displayed.post(
                () -> {
                    if (editorScroll == displayed) displayed.scrollTo(0, editScroll);
                });
    }

    private void editText(Page story) {
        EditText input = new EditText(activity);
        input.setContentDescription("这一页的故事");
        input.setText(story.text);
        input.setTextSize(16);
        input.setTextColor(Ui.INK);
        input.setGravity(Gravity.TOP);
        input.setMinLines(4);
        input.setMaxLines(10);
        input.setFilters(new InputFilter[] {new InputFilter.LengthFilter(5000)});
        input.setHint("记下这一天发生的小事");
        input.setHintTextColor(Ui.MUTED);
        input.setPadding(0, dp(12), 0, dp(12));
        input.setBackgroundColor(Color.TRANSPARENT);
        WarmSheet dialog = new WarmSheet(activity, "写下这一页的故事");
        storyDialog = dialog;
        dialog.body.addView(input, new LinearLayout.LayoutParams(-1, -2));
        dialog.primary("写好了", () -> finishStoryText(dialog, input, story));
        dialog.secondary("取消", () -> confirmStoryTextExit(dialog, input, story));
        dialog.onClose(() -> confirmStoryTextExit(dialog, input, story));
        dialog.onDismiss(
                () -> {
                    if (storyDialog == dialog) storyDialog = null;
                });
        dialog.show();
    }

    private void finishStoryText(WarmSheet dialog, EditText input, Page story) {
        if (disposed || !draft.contains(story)) {
            dialog.dismiss();
            return;
        }
        String changed = input.getText().toString().trim();
        if (!changed.equals(story.text)) {
            story.text = changed;
            story.author = "";
            dirty = true;
        }
        dialog.dismiss();
        rememberEditor();
        showEditor();
    }

    private void confirmStoryTextExit(WarmSheet dialog, EditText input, Page story) {
        if (disposed || input.getText().toString().equals(story.text)) {
            dialog.dismiss();
            return;
        }
        if (storyExitDialog != null && storyExitDialog.isShowing()) return;
        WarmSheet confirm = new WarmSheet(activity, "这段文字还没有记下");
        storyExitDialog = confirm;
        SecondaryUi.note(activity, confirm.body, "记到草稿后可以继续整理，放弃后这段文字不会保留。");
        confirm.primary(
                "记到草稿",
                () -> {
                    confirm.dismiss();
                    finishStoryText(dialog, input, story);
                });
        confirm.secondary("继续写", confirm::dismiss);
        confirm.secondary(
                "放弃文字",
                () -> {
                    confirm.dismiss();
                    dialog.dismiss();
                });
        confirm.onDismiss(
                () -> {
                    if (storyExitDialog == confirm) storyExitDialog = null;
                });
        confirm.show();
    }

    private void pageMenu(Page story) {
        int index = draft.indexOf(story);
        ArrayList<String> labels = new ArrayList<>();
        ArrayList<Runnable> actions = new ArrayList<>();
        labels.add("写这一页的故事");
        actions.add(() -> editText(story));
        labels.add("设为封面");
        actions.add(
                () -> {
                    draftCover = id(story.media);
                    dirty = true;
                    rememberEditor();
                    showEditor();
                });
        if (index > 0) {
            labels.add("向前移一页");
            actions.add(
                    () -> {
                        StorybookOrder.move(draft, index, index - 1);
                        dirty = true;
                        rememberEditor();
                        showEditor();
                    });
        }
        if (index < draft.size() - 1) {
            labels.add("向后移一页");
            actions.add(
                    () -> {
                        StorybookOrder.move(draft, index, index + 1);
                        dirty = true;
                        rememberEditor();
                        showEditor();
                    });
        }
        labels.add("从故事册移出");
        actions.add(
                () -> {
                    final int request = serial;
                    WarmSheet confirm = new WarmSheet(activity, "移出这一页？");
                    SecondaryUi.note(activity, confirm.body, "照片或视频仍会保留在相册里。");
                    confirm.primary(
                            "移出这一页",
                            () -> {
                                confirm.dismiss();
                                if (disposed || request != serial || !draft.contains(story)) return;
                                draft.remove(story);
                                if (draftCover.equals(id(story.media)))
                                    draftCover = draft.isEmpty() ? "" : id(draft.get(0).media);
                                dirty = true;
                                rememberEditor();
                                showEditor();
                            });
                    confirm.secondary("取消", confirm::dismiss);
                    confirm.show();
                });
        choices("这一页", null, labels.toArray(new String[0]), -1, which -> actions.get(which).run());
    }

    private void saveBook() {
        if (saving) return;
        if (!allowed()) {
            toast("你目前只能阅读故事册，不能保存修改");
            return;
        }
        if (draftTitle.trim().isEmpty()) {
            toast("请给这个月的故事册起个名字");
            return;
        }
        if (draft.isEmpty()) {
            toast("至少加入一张照片或一段视频，再保存故事册");
            return;
        }
        if (draftCover.isEmpty()) draftCover = id(draft.get(0).media);
        JSONObject payload = new JSONObject();
        try {
            JSONArray pages = new JSONArray();
            for (Page page : draft)
                pages.put(new JSONObject().put("media_id", id(page.media)).put("text", page.text));
            payload.put("title", draftTitle.trim())
                    .put("cover_media_id", draftCover)
                    .put("pages", pages)
                    .put("revision", draftRevision);
        } catch (Exception e) {
            toast("暂时无法整理这些内容，请稍后重试");
            return;
        }
        rememberEditor();
        saving = true;
        saveError = "";
        showEditor();
        final int request = serial;
        host.requestDetailed(
                "PUT",
                "/api/storybooks/" + ApiClient.encode(month),
                payload,
                result -> {
                    if (disposed || request != serial) return;
                    saving = false;
                    dirty = false;
                    book = result;
                    draft.clear();
                    saveError = "";
                    for (int i = 0; i < months.length(); i++) {
                        JSONObject summary = months.optJSONObject(i);
                        if (summary.optString("month").equals(month)) {
                            summary.put("title", result.optString("title"))
                                    .put("cover", result.optJSONObject("cover"))
                                    .put("page_count", values(result, "pages").length())
                                    .put("saved", true)
                                    .put("revision", result.optLong("revision"));
                            break;
                        }
                    }
                    showReader(false);
                    toast("故事册已保存，家人也能看到了");
                },
                (status, message) -> {
                    if (disposed || request != serial) return;
                    saving = false;
                    saveError = message;
                    showEditor();
                    if (status == 409) showConflict();
                });
    }

    private void showConflict() {
        final int request = serial;
        WarmSheet sheet = new WarmSheet(activity, "家人刚更新了这本故事册");
        SecondaryUi.note(activity, sheet.body, "你的改动还保留在草稿里。可以继续整理，或重新读取家人的版本。");
        sheet.primary("保留我的草稿", sheet::dismiss);
        sheet.secondary(
                "复制我的文字",
                () -> {
                    sheet.dismiss();
                    if (!disposed && request == serial) copyDraftText();
                });
        sheet.secondary(
                "重新读取",
                () -> {
                    sheet.dismiss();
                    if (disposed || request != serial) return;
                    WarmSheet confirm = new WarmSheet(activity, "重新读取家人的版本？");
                    SecondaryUi.note(activity, confirm.body, "未保存的选片和排序会被替换，需要的文字请先复制。");
                    confirm.primary(
                            "重新读取",
                            () -> {
                                confirm.dismiss();
                                if (!disposed && request == serial) openBook(month, true);
                            });
                    confirm.secondary("继续保留草稿", confirm::dismiss);
                    confirm.show();
                });
        sheet.show();
    }

    private void copyDraftText() {
        StringBuilder content = new StringBuilder(draftTitle).append("\n");
        for (Page page : draft)
            if (!page.text.isEmpty())
                content.append("\n")
                        .append(Ui.date(page.media.optString("album_date")))
                        .append("\n")
                        .append(page.text)
                        .append("\n");
        android.content.ClipboardManager clipboard =
                (android.content.ClipboardManager)
                        activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("故事册文字", content.toString()));
        toast("故事文字已复制");
    }

    private void openPicker(boolean cover) {
        if (cover && draft.isEmpty()) {
            toast("先加入照片或视频，再选择封面");
            return;
        }
        if (!cover && draft.size() >= MAX_PAGES) {
            toast("一本故事册最多放80页，可以先移出几页");
            return;
        }
        coverPicker = cover;
        pickerItems.clear();
        pickerIds.clear();
        chosen.clear();
        pickerCursor = "";
        pickerLoading = false;
        pickerFailed = false;
        pickerError = "";
        pickerPosition = pickerOffset = 0;
        pickerMore = !cover;
        if (cover)
            for (Page story : draft) {
                pickerItems.add(story.media);
                pickerIds.add(id(story.media));
                if (id(story.media).equals(draftCover)) chosen.put(draftCover, story.media);
            }
        showPicker();
    }

    private boolean inDraft(String key) {
        for (Page page : draft) if (id(page.media).equals(key)) return true;
        return false;
    }

    private void rememberPicker() {
        if (pickerList != null && pickerList.getChildAt(0) != null) {
            pickerPosition = pickerList.getFirstVisiblePosition();
            pickerOffset = pickerList.getChildAt(0).getTop();
        }
    }

    private void showPicker() {
        LinearLayout page = base();
        page.addView(bar(coverPicker ? "挑一张封面" : "加入照片或视频", this::showEditor, null, null));
        TextView tip =
                text(
                        coverPicker ? "从故事册中挑一个最想记住的瞬间" : Ui.month(month) + " · 只显示这个月的照片和视频",
                        13,
                        Ui.MUTED);
        tip.setPadding(dp(18), dp(5), dp(18), dp(12));
        page.addView(tip);
        pickerList = new ListView(activity);
        pickerList.setDivider(null);
        pickerList.setSelector(android.R.color.transparent);
        pickerList.setVerticalScrollBarEnabled(false);
        pickerFooter = text("", 13, Ui.MUTED);
        pickerFooter.setGravity(Gravity.CENTER);
        pickerFooter.setPadding(dp(20), dp(22), dp(20), dp(22));
        pickerFooter.setOnClickListener(v -> loadPicker());
        pickerList.addFooterView(pickerFooter, null, false);
        pickerAdapter =
                new BaseAdapter() {
                    public int getCount() {
                        return (pickerItems.size() + 2) / 3;
                    }

                    public Object getItem(int p) {
                        return p;
                    }

                    public long getItemId(int p) {
                        return p;
                    }

                    public boolean isEnabled(int p) {
                        return false;
                    }

                    public View getView(int position, View old, ViewGroup parent) {
                        cancelImages(old);
                        LinearLayout row = Ui.row(activity);
                        row.setPadding(dp(12), 0, dp(12), dp(4));
                        int side =
                                (activity.getResources().getDisplayMetrics().widthPixels - dp(32))
                                        / 3;
                        for (int column = 0; column < 3; column++) {
                            int index = position * 3 + column;
                            FrameLayout cell = new FrameLayout(activity);
                            LinearLayout.LayoutParams params =
                                    new LinearLayout.LayoutParams(0, side, 1);
                            if (column < 2) params.rightMargin = dp(4);
                            row.addView(cell, params);
                            if (index >= pickerItems.size()) continue;
                            JSONObject media = pickerItems.get(index);
                            String key = id(media);
                            boolean selected = chosen.containsKey(key);
                            boolean existing = !coverPicker && inDraft(key);
                            cell.setBackground(
                                    Ui.border(
                                            selected ? Ui.ACCENT : Ui.SOFT,
                                            7,
                                            selected ? Ui.ACCENT : Ui.SOFT,
                                            activity));
                            cell.setPadding(
                                    dp(selected ? 3 : 0),
                                    dp(selected ? 3 : 0),
                                    dp(selected ? 3 : 0),
                                    dp(selected ? 3 : 0));
                            cell.setClipToOutline(true);
                            cell.addView(photo(media, 450), new FrameLayout.LayoutParams(-1, -1));
                            if (selected) {
                                FrameLayout check = new FrameLayout(activity);
                                check.setBackground(Ui.round(Ui.ACCENT, 14, activity));
                                check.addView(
                                        new Ui.Icon(activity, "check", Color.WHITE),
                                        new FrameLayout.LayoutParams(
                                                dp(18), dp(18), Gravity.CENTER));
                                FrameLayout.LayoutParams cp =
                                        new FrameLayout.LayoutParams(
                                                dp(26), dp(26), Gravity.TOP | Gravity.RIGHT);
                                cp.setMargins(0, dp(5), dp(5), 0);
                                cell.addView(check, cp);
                            }
                            if (existing) {
                                TextView mark = text("已在册中", 11, Color.WHITE);
                                mark.setGravity(Gravity.CENTER);
                                mark.setPadding(dp(3), dp(3), dp(3), dp(3));
                                mark.setBackgroundColor(0xA0302522);
                                cell.addView(
                                        mark, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
                            } else if (media.optString("kind").equals("video")) {
                                TextView duration =
                                        text(
                                                "▶ " + Ui.duration(media.optDouble("duration")),
                                                11,
                                                Color.WHITE);
                                duration.setPadding(dp(4), dp(2), dp(4), dp(2));
                                duration.setBackgroundColor(0x99302522);
                                cell.addView(
                                        duration,
                                        new FrameLayout.LayoutParams(
                                                -2, -2, Gravity.BOTTOM | Gravity.RIGHT));
                            }
                            cell.setContentDescription(
                                    (selected ? "已选择 " : existing ? "已在册中 " : "")
                                            + Ui.date(media.optString("album_date"))
                                            + (media.optString("kind").equals("video")
                                                    ? "的视频"
                                                    : "的照片"));
                            cell.setOnClickListener(
                                    v -> {
                                        if (existing) {
                                            toast("这一项已经在故事册里了");
                                            return;
                                        }
                                        if (coverPicker) {
                                            chosen.clear();
                                            chosen.put(key, media);
                                        } else if (chosen.containsKey(key)) chosen.remove(key);
                                        else {
                                            if (draft.size() + chosen.size() >= MAX_PAGES) {
                                                toast("一本故事册最多放80页");
                                                return;
                                            }
                                            chosen.put(key, media);
                                        }
                                        pickerAdapter.notifyDataSetChanged();
                                        updatePickerLabels();
                                    });
                        }
                        return row;
                    }
                };
        pickerList.setAdapter(pickerAdapter);
        pickerList.setOnScrollListener(
                new AbsListView.OnScrollListener() {
                    public void onScrollStateChanged(AbsListView view, int state) {}

                    public void onScroll(AbsListView view, int first, int count, int total) {
                        if (total > 0 && first + count >= total - 3 && !pickerFailed) loadPicker();
                    }
                });
        page.addView(pickerList, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout footer = Ui.column(activity);
        footer.setPadding(dp(18), dp(8), dp(18), dp(10));
        pickerDone =
                button(
                        "",
                        true,
                        () -> {
                            if (chosen.isEmpty()) return;
                            if (coverPicker) {
                                String next = chosen.keySet().iterator().next();
                                if (!next.equals(draftCover)) {
                                    draftCover = next;
                                    dirty = true;
                                }
                            } else
                                for (JSONObject media : chosen.values())
                                    if (!inDraft(id(media))) {
                                        draft.add(
                                                new Page(
                                                        copy(media),
                                                        media.optString("description", ""),
                                                        ""));
                                        dirty = true;
                                    }
                            if (draftCover.isEmpty() && !draft.isEmpty())
                                draftCover = id(draft.get(0).media);
                            showEditor();
                        });
        footer.addView(pickerDone, new LinearLayout.LayoutParams(-1, -2));
        page.addView(footer);
        present("picker", page, this::showEditor);
        updatePickerLabels();
        final ListView displayed = pickerList;
        displayed.post(
                () -> {
                    if (pickerList == displayed && screen.equals("picker"))
                        displayed.setSelectionFromTop(pickerPosition, pickerOffset);
                });
        if (pickerItems.isEmpty() && !coverPicker && !pickerFailed) loadPicker();
    }

    private void updatePickerLabels() {
        if (pickerDone != null) {
            pickerDone.setText(
                    coverPicker
                            ? "就用这张"
                            : "加入故事册" + (chosen.isEmpty() ? "" : "（" + chosen.size() + "项）"));
            pickerDone.setEnabled(!chosen.isEmpty());
        }
        if (pickerFooter != null)
            pickerFooter.setText(
                    pickerFailed
                            ? (pickerError.isEmpty() ? "暂时没有读到照片和视频" : pickerError) + "，点这里重试"
                            : pickerLoading
                                    ? "正在读取…"
                                    : pickerItems.isEmpty()
                                            ? "这个月暂时没有可加入的内容"
                                            : pickerMore ? "继续往下看" : "这个月的内容都在这里了");
    }

    private void loadPicker() {
        if (coverPicker || pickerLoading || !pickerMore || disposed || !screen.equals("picker"))
            return;
        pickerLoading = true;
        pickerFailed = false;
        pickerError = "";
        updatePickerLabels();
        final int request = serial;
        String path = "/api/media?limit=60&month=" + ApiClient.encode(month);
        if (!pickerCursor.isEmpty()) path += "&cursor=" + ApiClient.encode(pickerCursor);
        host.request(
                "GET",
                path,
                null,
                result -> {
                    if (disposed || request != serial) return;
                    pickerLoading = false;
                    JSONArray items = values(result, "items");
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject item = items.optJSONObject(i);
                        if (item != null && pickerIds.add(id(item))) pickerItems.add(item);
                    }
                    pickerCursor =
                            result.isNull("next_cursor") ? "" : result.optString("next_cursor");
                    pickerMore = !pickerCursor.isEmpty();
                    if (pickerAdapter != null) pickerAdapter.notifyDataSetChanged();
                    updatePickerLabels();
                },
                message -> {
                    if (disposed || request != serial) return;
                    pickerLoading = false;
                    pickerFailed = true;
                    pickerError = message;
                    updatePickerLabels();
                });
    }

    void pause() {
        if (pager != null) pager.stop();
    }

    void resume() {}

    void permissionsChanged() {
        if (disposed || screen.isEmpty()) return;
        if (book != null) {
            try {
                book.put("can_edit", host.canEdit());
            } catch (org.json.JSONException impossible) {
                throw new IllegalStateException(impossible);
            }
        }
        boolean hasDraft =
                screen.equals("editor") || screen.equals("preview") || screen.equals("picker");
        toast(hasDraft && !host.canEdit() ? "权限已更新，草稿仍保留在这里，暂时不能保存" : "账号权限已更新，当前浏览位置已保留");
        // Keep pending saves, the full-screen player, and uncommitted story input intact.
        if (saving || mediaOpen || (storyDialog != null && storyDialog.isShowing())) return;
        if (screen.equals("editor")) {
            rememberEditor();
            showEditor();
        } else if (screen.equals("picker")) {
            rememberPicker();
            pickerLoading = false;
            showPicker();
        } else if (screen.equals("reader") || screen.equals("preview")) {
            boolean wasChrome = chrome;
            showReader(preview);
            if (!wasChrome && pager != null) toggleChrome();
        } else if (screen.equals("directory")) {
            rememberDirectory();
            showDirectory();
        }
    }

    void configurationChanged() {
        // The sheet resizes itself; rebuilding its host would discard the text being edited.
        if (disposed
                || screen.isEmpty()
                || saving
                || (storyDialog != null && storyDialog.isShowing())) return;
        if (screen.equals("directory")) {
            rememberDirectory();
            showDirectory();
        } else if (screen.equals("reader") || screen.equals("preview")) showReader(preview);
        else if (screen.equals("editor")) {
            rememberEditor();
            showEditor();
        } else if (screen.equals("picker")) {
            rememberPicker();
            pickerLoading = false;
            showPicker();
        }
    }

    void dispose() {
        disposed = true;
        directEntry = false;
        serial++;
        if (pager != null) pager.stop();
        pager = null;
        if (storyExitDialog != null) storyExitDialog.dismiss();
        if (storyDialog != null) storyDialog.dismiss();
        mediaOpen = false;
        cancelImages(currentView);
        currentView = null;
        months = new JSONArray();
        draft.clear();
        chosen.clear();
        pickerItems.clear();
        pickerIds.clear();
        readingPositions.clear();
        screen = "";
        book = null;
    }
}
