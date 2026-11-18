package cn.xiaojia.album;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

/** The memory shelf uses actual growth selections and actual monthly book covers. */
final class TimeHomePage {
    interface Actions {
        void openGrowth();

        void openStorybooks();

        void openStorybook(String month);

        void openVideos();

        void openMonths();

        void openFavorites();

        void refresh();

        void contentReady();
    }

    private final MemoryFeatureHost host;
    private final Activity activity;
    private final Actions actions;
    private final int bodyWidth;
    private final boolean stacked;
    private ScrollView scroll;
    private boolean growthLoaded, booksLoaded;
    private LinearLayout growthSlot, booksSlot;
    private int growthRequest, booksRequest;
    private boolean disposed;

    TimeHomePage(MemoryFeatureHost host, Actions actions) {
        this.host = host;
        activity = host.activity();
        this.actions = actions;
        int screenWidth =
                Math.round(
                        activity.getResources().getDisplayMetrics().widthPixels
                                / activity.getResources().getDisplayMetrics().density);
        bodyWidth = Math.min(screenWidth, 680) - 36;
        stacked = bodyWidth < 280 || activity.getResources().getConfiguration().fontScale > 1.25f;
    }

    private int dp(float value) {
        return Ui.dp(activity, value);
    }

    private TextView text(String value, int size, int color) {
        return Ui.text(activity, value, size, color);
    }

    private TextView title(String value, int size) {
        TextView view = text(value, size, Ui.INK);
        view.setTypeface(null, Typeface.BOLD);
        return view;
    }

    ScrollView createView() {
        if (scroll != null) return scroll;
        scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setBackgroundColor(Ui.BG);
        FrameLayout centering = new FrameLayout(activity);
        LinearLayout panel = Ui.column(activity);
        panel.setPadding(dp(18), 0, dp(18), dp(22));
        centering.addView(
                panel,
                new FrameLayout.LayoutParams(
                        dp(bodyWidth + 36), -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        scroll.addView(centering, new ScrollView.LayoutParams(-1, -2));

        LinearLayout heading = Ui.row(activity);
        heading.addView(title("成长时光", 24), new LinearLayout.LayoutParams(0, -2, 1));
        FrameLayout refresh = new FrameLayout(activity);
        refresh.setContentDescription("刷新成长时光");
        refresh.setBackground(Ui.ripple(Color.TRANSPARENT, 24, activity));
        refresh.addView(
                new Ui.Icon(activity, "refresh", Ui.INK),
                new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
        refresh.setOnClickListener(v -> actions.refresh());
        heading.addView(refresh, new LinearLayout.LayoutParams(dp(48), dp(48)));
        heading.setMinimumHeight(dp(58));
        panel.addView(heading);

        growthSlot = Ui.column(activity);
        panel.addView(growthSlot, new LinearLayout.LayoutParams(-1, -2));
        showGrowth(new ArrayList<>(), "正在寻找成长的足迹…", false);

        LinearLayout storyHeading = Ui.row(activity);
        storyHeading.setPadding(0, dp(20), 0, dp(12));
        storyHeading.addView(
                new Ui.Icon(activity, "time", Ui.ACCENT, true),
                new LinearLayout.LayoutParams(dp(50), dp(50)));
        LinearLayout storyWords = Ui.column(activity);
        storyWords.setPadding(dp(9), 0, dp(5), 0);
        storyWords.addView(title("每月故事册", 20));
        TextView caption = text("把一个月，收成一本书", 12, Ui.MUTED);
        caption.setPadding(0, dp(3), 0, 0);
        storyWords.addView(caption);
        storyHeading.addView(storyWords, new LinearLayout.LayoutParams(0, -2, 1));
        if (!stacked)
            storyHeading.addView(
                    link("全部故事册", actions::openStorybooks),
                    new LinearLayout.LayoutParams(-2, dp(48)));
        panel.addView(storyHeading);
        if (stacked) {
            TextView all = link("全部故事册", actions::openStorybooks);
            all.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
            panel.addView(all, new LinearLayout.LayoutParams(-1, dp(44)));
        }

        booksSlot = Ui.column(activity);
        panel.addView(booksSlot, new LinearLayout.LayoutParams(-1, -2));
        showBookMessage("正在翻找每个月的回忆…", false);

        LinearLayout shortcuts = Ui.row(activity);
        shortcuts.setPadding(0, dp(26), 0, 0);
        shortcuts.setGravity(Gravity.TOP);
        addShortcut(shortcuts, "memory_video", "视频回忆", actions::openVideos);
        addShortcut(shortcuts, "memory_month", "月份回忆", actions::openMonths);
        addShortcut(shortcuts, "collection", "我的收藏", actions::openFavorites);
        panel.addView(shortcuts);

        fetchGrowth();
        fetchBooks();
        return scroll;
    }

    private TextView link(String label, Runnable click) {
        TextView view = text(label + "  ›", 12, Ui.ACCENT_DARK);
        view.setPadding(dp(8), 0, dp(2), 0);
        view.setGravity(Gravity.CENTER);
        view.setMinimumHeight(dp(44));
        view.setContentDescription(label);
        view.setBackground(Ui.ripple(Color.TRANSPARENT, 12, activity));
        view.setOnClickListener(v -> click.run());
        return view;
    }

    private void addShortcut(LinearLayout row, String icon, String label, Runnable click) {
        LinearLayout entry = Ui.column(activity);
        entry.setGravity(Gravity.CENTER);
        entry.setPadding(dp(3), dp(8), dp(3), dp(8));
        entry.setMinimumHeight(dp(98));
        entry.setBackground(Ui.ripple(Color.TRANSPARENT, 16, activity));
        entry.addView(
                new Ui.Icon(activity, icon, Ui.ACCENT, true),
                new LinearLayout.LayoutParams(dp(54), dp(54)));
        TextView caption = text(label, 14, Ui.INK);
        caption.setGravity(Gravity.CENTER);
        caption.setPadding(0, dp(7), 0, 0);
        entry.addView(caption, new LinearLayout.LayoutParams(-1, -2));
        entry.setContentDescription(label);
        entry.setOnClickListener(v -> click.run());
        row.addView(entry, new LinearLayout.LayoutParams(0, -2, 1));
    }

    private void fetchGrowth() {
        fetchGrowth(null);
    }

    private void fetchGrowth(java.util.function.Consumer<Boolean> done) {
        final int request = ++growthRequest;
        host.request(
                "GET",
                "/api/growth",
                null,
                result -> {
                    if (disposed || request != growthRequest) {
                        if (done != null) done.accept(false);
                        return;
                    }
                    ArrayList<JSONObject> years = new ArrayList<>();
                    JSONArray incoming = result.optJSONArray("years");
                    if (incoming != null)
                        for (int i = 0; i < incoming.length(); i++) {
                            JSONObject year = incoming.optJSONObject(i);
                            if (year != null && year.optJSONObject("selected") != null)
                                years.add(year);
                        }
                    years.sort(
                            (first, second) ->
                                    Integer.compare(first.optInt("year"), second.optInt("year")));
                    String message = result.optString("message");
                    if (years.size() > 1) message = "把每个年纪，放在一起看";
                    else if (years.size() == 1) message = "先记住这个年纪的模样";
                    else if (message.isEmpty()) message = "上传照片后，慢慢收集成长的模样";
                    showGrowth(years, message, false);
                    growthLoaded = true;
                    ready();
                    if (done != null) done.accept(true);
                },
                message -> {
                    if (disposed || request != growthRequest) {
                        if (done != null) done.accept(false);
                        return;
                    }
                    if (!growthLoaded) showGrowth(new ArrayList<>(), "成长照片暂时没有读到", true);
                    ready();
                    if (done != null) done.accept(false);
                });
    }

    private void showGrowth(ArrayList<JSONObject> years, String message, boolean failed) {
        cancelImages(growthSlot);
        growthSlot.removeAllViews();
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(stacked ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(16), dp(14), dp(12), dp(14));
        GradientDrawable wash =
                new GradientDrawable(
                        GradientDrawable.Orientation.TL_BR,
                        new int[] {0xFFFFF0E3, 0xFFFCE3CF, 0xFFFFF4E9});
        wash.setCornerRadius(dp(19));
        card.setBackground(wash);
        card.setMinimumHeight(dp(stacked ? 170 : 182));
        card.setContentDescription("从小到大");
        card.setOnClickListener(v -> actions.openGrowth());
        LinearLayout words = Ui.column(activity);
        words.addView(title("从小到大", 24));
        TextView subtitle = text(message, 12, Ui.INK);
        subtitle.setLineSpacing(dp(2), 1);
        subtitle.setPadding(0, dp(7), 0, dp(11));
        words.addView(subtitle);
        TextView open =
                link(failed ? "重新读取" : "看看成长", failed ? this::fetchGrowth : actions::openGrowth);
        open.setTextSize(13);
        open.setPadding(dp(12), 0, dp(10), 0);
        open.setBackground(Ui.ripple(0xFFFFDDBD, 23, activity));
        words.addView(open, new LinearLayout.LayoutParams(-2, dp(44)));
        card.addView(words, new LinearLayout.LayoutParams(stacked ? -1 : 0, -2, stacked ? 0 : 1));
        int artWidth =
                stacked
                        ? Math.min(bodyWidth - 28, 300)
                        : Math.min(198, Math.round(bodyWidth * .49f));
        FrameLayout art = new FrameLayout(activity);
        art.setClipChildren(false);
        art.setClipToPadding(false);
        art.addView(
                new TimeHomeArt(activity, years.size() > 1 ? "growth_pair" : "growth_single"),
                new FrameLayout.LayoutParams(-1, -1));
        if (years.isEmpty()) {
            Ui.Icon emblem = new Ui.Icon(activity, "time", Ui.ACCENT, true);
            art.addView(emblem, new FrameLayout.LayoutParams(dp(72), dp(72), Gravity.CENTER));
        } else {
            int count = years.size() > 1 ? 2 : 1;
            int printWidth = Math.min(count == 2 ? (artWidth - 14) / 2 : artWidth - 24, 88);
            int printHeight = Math.round(printWidth * 1.36f);
            int span = count == 2 ? printWidth * 2 + 3 : printWidth;
            int start = (artWidth - span) / 2;
            for (int i = 0; i < count; i++) {
                JSONObject year = years.get(i == 0 ? 0 : years.size() - 1);
                LinearLayout print = print(year, printWidth);
                print.setRotation(count == 1 ? 5 : i == 0 ? -8 : 7);
                FrameLayout.LayoutParams params =
                        new FrameLayout.LayoutParams(dp(printWidth), dp(printHeight));
                params.leftMargin = dp(start + i * (printWidth + 3));
                params.topMargin = dp(count == 1 ? 18 : i == 0 ? 25 : 8);
                art.addView(print, params);
            }
        }
        LinearLayout.LayoutParams artParams = new LinearLayout.LayoutParams(dp(artWidth), dp(158));
        artParams.gravity = Gravity.CENTER_HORIZONTAL;
        if (stacked) artParams.topMargin = dp(5);
        card.addView(art, artParams);
        growthSlot.addView(card, new LinearLayout.LayoutParams(-1, -2));
    }

    private LinearLayout print(JSONObject year, int width) {
        LinearLayout print = Ui.column(activity);
        print.setPadding(dp(5), dp(5), dp(5), dp(3));
        print.setBackground(Ui.round(0xFFFFFCF6, 7, activity));
        print.setElevation(dp(2));
        FrameLayout crop = new FrameLayout(activity);
        crop.setBackground(Ui.round(Ui.SOFT, 4, activity));
        crop.setClipToOutline(true);
        ImageView image = new TopCropImageView(activity);
        crop.addView(image, new FrameLayout.LayoutParams(-1, -1));
        JSONObject item = year.optJSONObject("selected");
        if (item != null) host.images().load(image, item.optString("thumbnail_url"), 400);
        print.addView(crop, new LinearLayout.LayoutParams(-1, 0, 1));
        String age = year.optString("label");
        if (age.isEmpty()) age = year.optInt("year") == 0 ? "出生至1岁" : year.optInt("year") + "岁";
        TextView caption = text(age, width < 80 ? 9 : 10, Ui.ACCENT_DARK);
        caption.setGravity(Gravity.CENTER);
        caption.setPadding(0, dp(3), 0, 0);
        print.addView(caption, new LinearLayout.LayoutParams(-1, -2));
        print.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        return print;
    }

    private void fetchBooks() {
        fetchBooks(null);
    }

    private void fetchBooks(java.util.function.Consumer<Boolean> done) {
        final int request = ++booksRequest;
        host.request(
                "GET",
                "/api/storybooks",
                null,
                result -> {
                    if (disposed || request != booksRequest) {
                        if (done != null) done.accept(false);
                        return;
                    }
                    JSONArray incoming = result.optJSONArray("months");
                    ArrayList<JSONObject> books = new ArrayList<>();
                    if (incoming != null)
                        for (int i = 0; i < incoming.length(); i++) {
                            JSONObject book = incoming.optJSONObject(i);
                            if (book != null
                                    && book.optString("month").matches("\\d{4}-(0[1-9]|1[0-2])"))
                                books.add(book);
                        }
                    books.sort(
                            (first, second) ->
                                    second.optString("month").compareTo(first.optString("month")));
                    showBooks(books);
                    booksLoaded = true;
                    ready();
                    if (done != null) done.accept(true);
                },
                message -> {
                    if (disposed || request != booksRequest) {
                        if (done != null) done.accept(false);
                        return;
                    }
                    if (!booksLoaded) showBookMessage("故事册暂时没有读到，轻点重新读取", true);
                    ready();
                    if (done != null) done.accept(false);
                });
    }

    private void showBooks(ArrayList<JSONObject> books) {
        cancelImages(booksSlot);
        booksSlot.removeAllViews();
        if (books.isEmpty()) {
            showBookMessage("上传照片和视频后，每个月的回忆都会收在这里", false);
            return;
        }
        LinearLayout row = Ui.row(activity);
        row.setGravity(Gravity.TOP);
        int width = (bodyWidth - 14) / 2;
        for (int i = 0; i < Math.min(2, books.size()); i++) {
            JSONObject book = books.get(i);
            String month = book.optString("month");
            TimeHomeArt.BookFrame frame =
                    new TimeHomeArt.BookFrame(activity, i == 0 ? 0xFFDB906B : 0xFFE2B765);
            LinearLayout contents = Ui.column(activity);
            FrameLayout imageArea = new FrameLayout(activity);
            imageArea.setBackground(Ui.round(Ui.SOFT, 8, activity));
            imageArea.setClipToOutline(true);
            JSONObject cover = book.optJSONObject("cover");
            if (cover != null && !cover.optString("thumbnail_url").isEmpty()) {
                ImageView image = new TopCropImageView(activity);
                imageArea.addView(image, new FrameLayout.LayoutParams(-1, -1));
                host.images().load(image, cover.optString("thumbnail_url"), 700);
            } else {
                LinearLayout placeholder = Ui.column(activity);
                placeholder.setGravity(Gravity.CENTER);
                placeholder.addView(
                        new Ui.Icon(activity, "time", Ui.ACCENT, true),
                        new LinearLayout.LayoutParams(dp(60), dp(60)));
                TextView label = text("尚未选择封面", 12, Ui.MUTED);
                label.setGravity(Gravity.CENTER);
                label.setPadding(dp(6), dp(8), dp(6), 0);
                placeholder.addView(label);
                imageArea.addView(placeholder, new FrameLayout.LayoutParams(-1, -1));
            }
            contents.addView(
                    imageArea,
                    new LinearLayout.LayoutParams(-1, dp(Math.min(226, Math.round(width * .92f)))));
            TextView label = title(Ui.month(month), 18);
            label.setGravity(Gravity.CENTER);
            label.setPadding(dp(5), dp(13), dp(5), dp(14));
            label.setBackgroundColor(0xFFFFF1DD);
            contents.addView(label, new LinearLayout.LayoutParams(-1, -2));
            FrameLayout.LayoutParams inner = new FrameLayout.LayoutParams(-1, -2);
            inner.setMargins(dp(11), dp(3), dp(4), dp(12));
            frame.addView(contents, inner);
            frame.setMinimumHeight(dp(180));
            frame.setContentDescription("翻开" + Ui.month(month) + "的故事册");
            frame.setOnClickListener(v -> actions.openStorybook(month));
            frame.setForeground(Ui.ripple(Color.TRANSPARENT, 10, activity));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(width), -2);
            if (i == 0) params.rightMargin = dp(14);
            row.addView(frame, params);
        }
        booksSlot.addView(row);
    }

    private void showBookMessage(String message, boolean failed) {
        cancelImages(booksSlot);
        booksSlot.removeAllViews();
        LinearLayout state = Ui.column(activity);
        state.setGravity(Gravity.CENTER);
        state.setPadding(dp(22), dp(25), dp(22), dp(25));
        state.setBackground(Ui.round(0xFFFFF2E7, 16, activity));
        state.addView(
                new Ui.Icon(activity, "time", Ui.ACCENT, true),
                new LinearLayout.LayoutParams(dp(54), dp(54)));
        TextView label = text(message, 14, Ui.MUTED);
        label.setGravity(Gravity.CENTER);
        label.setPadding(0, dp(10), 0, 0);
        label.setLineSpacing(dp(3), 1);
        state.addView(label);
        if (failed)
            state.setOnClickListener(
                    v -> {
                        showBookMessage("正在翻找每个月的回忆…", false);
                        fetchBooks();
                    });
        booksSlot.addView(state, new LinearLayout.LayoutParams(-1, -2));
    }

    void refresh(java.util.function.Consumer<Boolean> done) {
        java.util.function.Consumer<Boolean> part = PullRefreshLayout.together(2, done);
        fetchGrowth(part);
        fetchBooks(part);
    }

    private void ready() {
        if (scroll == null || disposed) return;
        scroll.post(
                () -> {
                    if (!disposed) actions.contentReady();
                });
    }

    private void cancelImages(View view) {
        if (view instanceof ImageView) host.images().cancel((ImageView) view);
        if (view instanceof ViewGroup)
            for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
                cancelImages(((ViewGroup) view).getChildAt(i));
    }

    void dispose() {
        disposed = true;
        growthRequest++;
        booksRequest++;
        cancelImages(scroll);
        scroll = null;
        growthSlot = booksSlot = null;
    }
}
