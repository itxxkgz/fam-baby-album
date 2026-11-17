package cn.xiaojia.album;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;

/** Family growth representatives, private comparisons, and native age-scoped selection. */
final class GrowthPages {
    private static final int HOME = 0, COMPARE = 1, PICKER = 2;
    private static final int REPRESENTATIVE = 0, LEFT_PHOTO = 1, RIGHT_PHOTO = 2;
    private final MemoryFeatureHost host;
    private final Activity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayList<JSONObject> years = new ArrayList<>();
    private final ArrayList<FrameLayout> ageFrames = new ArrayList<>();
    private View pageView;
    private int mode = HOME, revision, selectedIndex = -1;
    private boolean disposed, loadingGrowth, canManage, playing, heroReady;
    private String homeMessage = "正在翻开成长记录…";
    private ImageView hero;
    private TextView heroAge, heroDate, heroNotice, playButton;
    private HorizontalScrollView ageScroll;
    private LinearLayout ageStrip;
    private int leftIndex = -1, rightIndex = -1;
    private JSONObject leftPhoto, rightPhoto;
    private ImageView leftSource, rightSource;
    private Bitmap leftBitmap, rightBitmap;
    private GrowthCompareView comparison;
    private TextView comparisonNotice, saveButton, shareButton;
    private boolean overlayMode;
    private float comparisonDivider = .5f;
    private final ArrayList<JSONObject> pickerItems = new ArrayList<>();
    private final HashSet<String> pickerIds = new HashSet<>();
    private JSONObject pickerSelection;
    private int pickerIndex, pickerPurpose, pickerFirst, pickerOffset;
    private String pickerCursor = "", pickerMessage = "";
    private boolean pickerHasMore, pickerLoading, pickerFailed, pickerSaving;
    private GridView pickerGrid;
    private BaseAdapter pickerAdapter;
    private TextView pickerStatus, usePhotoButton;
    private final Runnable playback =
            new Runnable() {
                @Override
                public void run() {
                    if (!playing || mode != HOME || disposed) return;
                    if (!heroReady) {
                        handler.postDelayed(this, 800);
                        return;
                    }
                    if (selectedIndex >= years.size() - 1) {
                        stopPlayback();
                        return;
                    }
                    selectedIndex++;
                    bindHomePhoto();
                    handler.postDelayed(this, 3000);
                }
            };

    GrowthPages(MemoryFeatureHost host) {
        this.host = host;
        activity = host.activity();
    }

    void show() {
        if (disposed) return;
        stopPlayback();
        loadingGrowth = true;
        homeMessage = "正在翻开成长记录…";
        renderHome();
        fetchGrowth();
    }

    void pause() {
        stopPlayback();
    }

    void resume() {
        /* A slideshow never restarts without another tap. */
    }

    void configurationChanged() {
        if (disposed) return;
        if (mode == COMPARE) renderComparison();
        else if (mode == PICKER) renderPicker();
        else {
            stopPlayback();
            renderHome();
            if (loadingGrowth) fetchGrowth();
        }
    }

    void dispose() {
        disposed = true;
        revision++;
        stopPlayback();
        cancelImages(pageView);
        cancelCompareLoads();
        leftBitmap = rightBitmap = null;
        years.clear();
        pickerItems.clear();
        pageView = null;
    }

    private int dp(float value) {
        return Ui.dp(activity, value);
    }

    private TextView text(String value, int size, int color) {
        return Ui.text(activity, value, size, color);
    }

    private void toast(String message) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
    }

    private String birthday() {
        return host.profile().optString("birthday");
    }

    private String label(JSONObject year) {
        return year.optString("label", year.optInt("year") + "岁");
    }

    private JSONObject selectedPhoto(int index) {
        return index < 0 || index >= years.size()
                ? null
                : years.get(index).optJSONObject("selected");
    }

    private JSONObject json(String key, Object value) {
        JSONObject result = new JSONObject();
        try {
            result.put(key, value);
        } catch (Exception ignored) {
        }
        return result;
    }

    private void attach(int page, View view, Runnable back) {
        stopPlayback();
        cancelImages(pageView);
        cancelCompareLoads();
        revision++;
        mode = page;
        pageView = view;
        host.showFeature(
                "growth_" + (page == HOME ? "home" : page == COMPARE ? "compare" : "picker"),
                view,
                back);
    }

    private void leave() {
        stopPlayback();
        revision++;
        cancelImages(pageView);
        cancelCompareLoads();
        host.returnToTime();
    }

    private LinearLayout bar(String title, Runnable back) {
        LinearLayout bar = Ui.row(activity);
        bar.setPadding(dp(4), 0, dp(8), 0);
        bar.setMinimumHeight(dp(52));
        FrameLayout button = new FrameLayout(activity);
        button.setContentDescription("返回");
        button.setBackground(Ui.ripple(Ui.BG, 24, activity));
        button.addView(
                new Ui.Icon(activity, "back", Ui.INK),
                new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
        button.setOnClickListener(v -> back.run());
        bar.addView(button, new LinearLayout.LayoutParams(dp(48), dp(52)));
        TextView heading = text(title, 19, Ui.INK);
        heading.setTypeface(null, Typeface.BOLD);
        heading.setPadding(dp(4), dp(8), dp(4), dp(8));
        heading.setMaxLines(2);
        bar.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        return bar;
    }

    private TextView button(String title, boolean primary, Runnable action) {
        TextView button = text(title, 15, primary ? Color.WHITE : Ui.ACCENT_DARK);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(16), dp(8), dp(16), dp(8));
        button.setMinHeight(dp(46));
        button.setBackground(Ui.button(activity, primary));
        button.setOnClickListener(v -> action.run());
        return button;
    }

    private TextView smallAction(String title, Runnable action) {
        TextView button = text(title, 13, Ui.ACCENT_DARK);
        button.setPadding(dp(10), 0, dp(10), 0);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(48));
        button.setBackground(Ui.ripple(Color.TRANSPARENT, 16, activity));
        button.setOnClickListener(v -> action.run());
        return button;
    }

    private void fetchGrowth() {
        final int request = revision;
        final int previousYear =
                selectedIndex >= 0 && selectedIndex < years.size()
                        ? years.get(selectedIndex).optInt("year")
                        : -1;
        host.request(
                "GET",
                "/api/growth",
                null,
                result -> {
                    if (disposed || revision != request || mode != HOME) return;
                    years.clear();
                    JSONArray incoming = result.optJSONArray("years");
                    if (incoming != null)
                        for (int i = 0; i < incoming.length(); i++) {
                            JSONObject year = incoming.optJSONObject(i);
                            if (year != null && year.optJSONObject("selected") != null)
                                years.add(year);
                        }
                    canManage = result.optBoolean("can_edit") && host.canEdit();
                    selectedIndex = years.size() - 1;
                    for (int i = 0; i < years.size(); i++)
                        if (years.get(i).optInt("year") == previousYear) selectedIndex = i;
                    loadingGrowth = false;
                    homeMessage = result.optString("message");
                    if (homeMessage.isEmpty()) homeMessage = "有了照片，就能在这里看见孩子慢慢长大。";
                    renderHome();
                },
                message -> {
                    if (disposed || revision != request || mode != HOME) return;
                    loadingGrowth = false;
                    homeMessage = message;
                    renderHome();
                });
    }

    private void renderHome() {
        LinearLayout page = Ui.column(activity);
        page.setBackgroundColor(Ui.BG);
        LinearLayout heading = bar("从小到大", this::leave);
        FrameLayout more = new FrameLayout(activity);
        more.setContentDescription("成长记录选项");
        more.addView(
                new Ui.Icon(activity, "more", Ui.INK),
                new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
        more.setOnClickListener(v -> homeMenu());
        heading.addView(more, new LinearLayout.LayoutParams(dp(48), dp(48)));
        page.addView(heading);
        if (years.isEmpty()) {
            LinearLayout empty = Ui.column(activity);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(30), dp(24), dp(30), dp(24));
            empty.addView(
                    new Ui.Icon(activity, "photo", Ui.ACCENT),
                    new LinearLayout.LayoutParams(dp(48), dp(48)));
            TextView message = text(homeMessage, 16, Ui.INK);
            message.setGravity(Gravity.CENTER);
            message.setPadding(0, dp(20), 0, dp(20));
            empty.addView(message);
            if (!loadingGrowth) empty.addView(button("重新读取", false, this::show));
            page.addView(empty, new LinearLayout.LayoutParams(-1, 0, 1));
            attach(HOME, page, this::leave);
            return;
        }
        selectedIndex = Math.max(0, Math.min(selectedIndex, years.size() - 1));
        FrameLayout photo = new FrameLayout(activity);
        photo.setBackground(Ui.round(Ui.SOFT, 8, activity));
        photo.setClipToOutline(true);
        hero = new ImageView(activity);
        hero.setScaleType(ImageView.ScaleType.CENTER_CROP);
        photo.addView(hero, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout caption = Ui.column(activity);
        caption.setPadding(dp(18), dp(38), dp(18), dp(18));
        caption.setBackground(
                new GradientDrawable(
                        GradientDrawable.Orientation.TOP_BOTTOM,
                        new int[] {Color.TRANSPARENT, 0xA6000000}));
        heroAge = text("", 27, Color.WHITE);
        heroAge.setTypeface(null, Typeface.BOLD);
        caption.addView(heroAge);
        heroDate = text("", 14, Ui.ON_DARK);
        heroDate.setPadding(0, dp(4), 0, 0);
        caption.addView(heroDate);
        photo.addView(caption, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        heroNotice = text("", 14, Ui.INK);
        heroNotice.setGravity(Gravity.CENTER);
        heroNotice.setPadding(dp(22), dp(18), dp(22), dp(18));
        heroNotice.setBackground(Ui.round(0xEFFFFBF7, 12, activity));
        heroNotice.setOnClickListener(v -> bindHomePhoto());
        photo.addView(heroNotice, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        GestureDetector gestures =
                new GestureDetector(
                        activity,
                        new GestureDetector.SimpleOnGestureListener() {
                            @Override
                            public boolean onDown(MotionEvent event) {
                                return true;
                            }

                            @Override
                            public boolean onSingleTapUp(MotionEvent event) {
                                openHomePhoto();
                                return true;
                            }

                            @Override
                            public boolean onFling(
                                    MotionEvent first, MotionEvent last, float vx, float vy) {
                                if (first == null) return false;
                                float dx = last.getX() - first.getX(),
                                        dy = last.getY() - first.getY();
                                if (Math.abs(dx) < dp(42) || Math.abs(dx) < Math.abs(dy) * 1.3f)
                                    return false;
                                stopPlayback();
                                selectedIndex =
                                        Math.max(
                                                0,
                                                Math.min(
                                                        years.size() - 1,
                                                        selectedIndex + (dx < 0 ? 1 : -1)));
                                bindHomePhoto();
                                return true;
                            }
                        });
        photo.setOnTouchListener((view, event) -> gestures.onTouchEvent(event));
        photo.setContentDescription("成长照片，左右滑动切换年龄，轻点查看大图");
        LinearLayout.LayoutParams photoParams = new LinearLayout.LayoutParams(-1, 0, 1);
        photoParams.setMargins(dp(12), dp(4), dp(12), dp(8));
        page.addView(photo, photoParams);
        ageScroll = new HorizontalScrollView(activity);
        ageScroll.setHorizontalScrollBarEnabled(false);
        ageStrip = Ui.row(activity);
        ageStrip.setPadding(dp(12), dp(5), dp(6), dp(3));
        ageFrames.clear();
        for (int i = 0; i < years.size(); i++) {
            final int index = i;
            JSONObject year = years.get(i), item = selectedPhoto(i);
            LinearLayout tile = Ui.column(activity);
            tile.setGravity(Gravity.CENTER);
            FrameLayout frame = new FrameLayout(activity);
            frame.setPadding(dp(3), dp(3), dp(3), dp(3));
            frame.setBackground(
                    Ui.border(Ui.BG, 9, i == selectedIndex ? Ui.ACCENT : Ui.BG, activity));
            ImageView thumb = new ImageView(activity);
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumb.setBackground(Ui.round(Ui.SOFT, 6, activity));
            thumb.setClipToOutline(true);
            frame.addView(thumb, new FrameLayout.LayoutParams(-1, -1));
            tile.addView(frame, new LinearLayout.LayoutParams(dp(66), dp(66)));
            TextView age = text(label(year), 12, Ui.INK);
            age.setGravity(Gravity.CENTER);
            age.setMaxLines(2);
            tile.addView(age, new LinearLayout.LayoutParams(dp(76), -2));
            tile.setPadding(0, 0, dp(6), 0);
            tile.setContentDescription("查看" + label(year) + "的成长照片");
            tile.setOnClickListener(
                    v -> {
                        stopPlayback();
                        selectedIndex = index;
                        bindHomePhoto();
                    });
            ageFrames.add(frame);
            ageStrip.addView(tile, new LinearLayout.LayoutParams(-2, -2));
            if (item != null) host.images().load(thumb, item.optString("thumbnail_url"), 260);
        }
        ageScroll.addView(ageStrip, new HorizontalScrollView.LayoutParams(-2, -2));
        page.addView(ageScroll, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout actions = Ui.row(activity);
        actions.setPadding(dp(16), dp(8), dp(16), dp(12));
        playButton = smallAction("▶ 连续播放", this::togglePlayback);
        actions.addView(playButton, new LinearLayout.LayoutParams(0, -2, 1));
        actions.addView(
                button("对比看看", true, this::openComparison),
                new LinearLayout.LayoutParams(0, -2, 1));
        page.addView(actions);
        attach(HOME, page, this::leave);
        bindHomePhoto();
    }

    private void homeMenu() {
        stopPlayback();
        final int request = revision;
        WarmSheet sheet = new WarmSheet(activity, "成长记录");
        boolean editable = canManage && host.canEdit() && selectedPhoto(selectedIndex) != null;
        if (editable) {
            sheet.body.addView(
                    SecondaryUi.row(
                            activity,
                            "photo",
                            "更换" + label(years.get(selectedIndex)) + "的代表照",
                            "",
                            () -> {
                                sheet.dismiss();
                                if (!disposed && request == revision && host.canEdit())
                                    openPicker(
                                            selectedIndex,
                                            REPRESENTATIVE,
                                            selectedPhoto(selectedIndex));
                            }));
            SecondaryUi.divider(activity, sheet.body);
        }
        sheet.body.addView(
                SecondaryUi.row(
                        activity,
                        "refresh",
                        "重新读取",
                        "",
                        () -> {
                            sheet.dismiss();
                            if (!disposed && request == revision) show();
                        }));
        sheet.show();
    }

    private void bindHomePhoto() {
        JSONObject item = selectedPhoto(selectedIndex);
        if (item == null || hero == null) return;
        heroReady = false;
        heroAge.setText(Ui.age(birthday(), item.optString("album_date")));
        heroDate.setText(Ui.date(item.optString("album_date")));
        heroNotice.setVisibility(View.VISIBLE);
        heroNotice.setText("正在载入照片…");
        final ImageView target = hero;
        final int request = revision;
        host.images()
                .loadProgressive(
                        target,
                        item.optString("thumbnail_url"),
                        item.optString("file_url"),
                        2200,
                        () -> {
                            if (!disposed && revision == request && hero == target) {
                                heroReady = true;
                                heroNotice.setVisibility(View.GONE);
                            }
                        },
                        () -> {
                            if (!disposed && revision == request && hero == target) {
                                stopPlayback();
                                heroNotice.setText("照片还没载入，点此重试");
                            }
                        });
        for (int i = 0; i < ageFrames.size(); i++)
            ageFrames
                    .get(i)
                    .setBackground(
                            Ui.border(Ui.BG, 9, i == selectedIndex ? Ui.ACCENT : Ui.BG, activity));
        ageScroll.post(
                () -> {
                    if (!disposed && mode == HOME && selectedIndex < ageStrip.getChildCount()) {
                        View selected = ageStrip.getChildAt(selectedIndex);
                        ageScroll.smoothScrollTo(
                                Math.max(
                                        0,
                                        selected.getLeft()
                                                - (ageScroll.getWidth() - selected.getWidth()) / 2),
                                0);
                    }
                });
    }

    private void openHomePhoto() {
        JSONObject item = selectedPhoto(selectedIndex);
        if (item == null) return;
        stopPlayback();
        ArrayList<JSONObject> scope = new ArrayList<>();
        for (int i = 0; i < years.size(); i++)
            if (selectedPhoto(i) != null) scope.add(selectedPhoto(i));
        host.openFeatureMedia(item, scope, this::renderHome);
    }

    private void togglePlayback() {
        if (playing) {
            stopPlayback();
            return;
        }
        if (years.size() < 2) {
            toast("再多记录一个年龄，就能连着看成长变化了");
            return;
        }
        if (selectedIndex == years.size() - 1) {
            selectedIndex = 0;
            bindHomePhoto();
        }
        playing = true;
        playButton.setText("Ⅱ 暂停播放");
        handler.postDelayed(playback, 3000);
    }

    private void stopPlayback() {
        playing = false;
        handler.removeCallbacks(playback);
        if (playButton != null) playButton.setText("▶ 连续播放");
    }

    private void openComparison() {
        if (years.isEmpty()) return;
        stopPlayback();
        leftIndex = 0;
        rightIndex = Math.max(0, selectedIndex);
        leftPhoto = selectedPhoto(leftIndex);
        rightPhoto = selectedPhoto(rightIndex);
        overlayMode = false;
        comparisonDivider = .5f;
        renderComparison();
    }

    private LinearLayout comparisonChoice(boolean left) {
        int index = left ? leftIndex : rightIndex;
        LinearLayout choice = Ui.row(activity);
        TextView age = text(label(years.get(index)) + " ▾", 21, Ui.INK);
        age.setTypeface(null, Typeface.BOLD);
        age.setMaxLines(2);
        age.setMinHeight(dp(48));
        age.setOnClickListener(v -> chooseComparisonAge(left));
        age.setContentDescription(left ? "选择左边照片的年龄" : "选择右边照片的年龄");
        choice.addView(age, new LinearLayout.LayoutParams(0, -2, 1));
        choice.addView(
                smallAction(
                        "换一张",
                        () ->
                                openPicker(
                                        left ? leftIndex : rightIndex,
                                        left ? LEFT_PHOTO : RIGHT_PHOTO,
                                        left ? leftPhoto : rightPhoto)),
                new LinearLayout.LayoutParams(-2, dp(48)));
        return choice;
    }

    private void chooseComparisonAge(boolean left) {
        final int request = revision;
        WarmSheet sheet = new WarmSheet(activity, left ? "左边看几岁时" : "右边看几岁时");
        for (int i = 0; i < years.size(); i++) {
            final int index = i;
            sheet.body.addView(
                    SecondaryUi.row(
                            activity,
                            "time",
                            label(years.get(i)),
                            i == (left ? leftIndex : rightIndex) ? "已选择" : "",
                            () -> {
                                sheet.dismiss();
                                if (disposed || request != revision) return;
                                if (left) {
                                    leftIndex = index;
                                    leftPhoto = selectedPhoto(index);
                                } else {
                                    rightIndex = index;
                                    rightPhoto = selectedPhoto(index);
                                }
                                renderComparison();
                            }));
            if (i < years.size() - 1) SecondaryUi.divider(activity, sheet.body);
        }
        sheet.show();
    }

    private void renderComparison() {
        if (leftPhoto == null || rightPhoto == null || years.isEmpty()) {
            renderHome();
            return;
        }
        if (comparison != null) comparisonDivider = comparison.divider();
        LinearLayout page = Ui.column(activity);
        page.setBackgroundColor(Ui.BG);
        LinearLayout heading = bar("成长对比", this::renderHome);
        shareButton = smallAction("分享", () -> exportComparison(true));
        shareButton.setContentDescription("分享成长对比图");
        heading.addView(shareButton);
        page.addView(heading);
        LinearLayout choices = Ui.row(activity);
        choices.setPadding(dp(16), dp(3), dp(8), 0);
        LinearLayout.LayoutParams leftParams = new LinearLayout.LayoutParams(0, -2, 1);
        leftParams.rightMargin = dp(12);
        choices.addView(comparisonChoice(true), leftParams);
        choices.addView(comparisonChoice(false), new LinearLayout.LayoutParams(0, -2, 1));
        page.addView(choices);
        FrameLayout frame = new FrameLayout(activity);
        frame.setBackground(Ui.round(Ui.SOFT, 8, activity));
        frame.setClipToOutline(true);
        comparison = new GrowthCompareView(activity);
        comparison.setOverlay(overlayMode);
        comparison.setDivider(comparisonDivider);
        frame.addView(comparison, new FrameLayout.LayoutParams(-1, -1));
        comparisonNotice = text("正在准备对比照片…", 14, Ui.INK);
        comparisonNotice.setGravity(Gravity.CENTER);
        comparisonNotice.setPadding(dp(20), dp(16), dp(20), dp(16));
        comparisonNotice.setBackground(Ui.round(0xEFFFFBF7, 12, activity));
        comparisonNotice.setOnClickListener(v -> loadComparisonPhotos());
        frame.addView(comparisonNotice, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        LinearLayout.LayoutParams frameParams = new LinearLayout.LayoutParams(-1, 0, 1);
        frameParams.setMargins(dp(12), dp(6), dp(12), 0);
        page.addView(frame, frameParams);
        LinearLayout dates = Ui.row(activity);
        dates.setPadding(dp(12), dp(7), dp(12), dp(4));
        for (JSONObject item : new JSONObject[] {leftPhoto, rightPhoto}) {
            TextView date = text(Ui.date(item.optString("album_date")), 12, Ui.MUTED);
            date.setGravity(Gravity.CENTER);
            dates.addView(date, new LinearLayout.LayoutParams(0, -2, 1));
        }
        page.addView(dates);
        TextView words = text("原来，你已经长这么大了", 16, Ui.INK);
        words.setPadding(dp(12), dp(10), dp(12), dp(10));
        words.setGravity(Gravity.CENTER);
        page.addView(words);
        LinearLayout modes = Ui.row(activity);
        modes.setPadding(dp(3), dp(3), dp(3), dp(3));
        modes.setBackground(Ui.border(Ui.BG, 20, Ui.LINE, activity));
        final TextView[] controls = new TextView[2];
        for (int i = 0; i < 2; i++) {
            final boolean overlay = i == 1;
            TextView option = text(overlay ? "叠着看" : "并排看", 14, Ui.INK);
            option.setGravity(Gravity.CENTER);
            option.setBackground(Ui.ripple(overlayMode == overlay ? Ui.SOFT : Ui.BG, 18, activity));
            option.setSelected(overlayMode == overlay);
            option.setOnClickListener(
                    v -> {
                        overlayMode = overlay;
                        comparison.setOverlay(overlay);
                        for (int c = 0; c < controls.length; c++) {
                            boolean selected = (c == 1) == overlay;
                            controls[c].setSelected(selected);
                            controls[c].setBackground(
                                    Ui.ripple(selected ? Ui.SOFT : Ui.BG, 18, activity));
                        }
                    });
            controls[i] = option;
            modes.addView(option, new LinearLayout.LayoutParams(0, dp(42), 1));
        }
        LinearLayout.LayoutParams modeParams = new LinearLayout.LayoutParams(-1, -2);
        modeParams.setMargins(dp(24), 0, dp(24), dp(8));
        page.addView(modes, modeParams);
        saveButton = button("保存对比图", true, () -> exportComparison(false));
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(-1, -2);
        saveParams.setMargins(dp(24), 0, dp(24), dp(12));
        page.addView(saveButton, saveParams);
        attach(COMPARE, page, this::renderHome);
        loadComparisonPhotos();
    }

    private void loadComparisonPhotos() {
        cancelCompareLoads();
        leftBitmap = rightBitmap = null;
        comparison.setPhotos(null, null);
        comparisonNotice.setVisibility(View.VISIBLE);
        comparisonNotice.setText("正在准备对比照片…");
        saveButton.setEnabled(false);
        shareButton.setEnabled(false);
        final int request = revision;
        leftSource = new ImageView(activity);
        rightSource = new ImageView(activity);
        loadComparisonSide(leftSource, leftPhoto, true, request);
        loadComparisonSide(rightSource, rightPhoto, false, request);
    }

    private void loadComparisonSide(ImageView target, JSONObject item, boolean left, int request) {
        host.images()
                .loadProgressive(
                        target,
                        item.optString("thumbnail_url"),
                        item.optString("file_url"),
                        2200,
                        () -> {
                            if (disposed
                                    || mode != COMPARE
                                    || revision != request
                                    || (left ? leftSource : rightSource) != target) return;
                            if (!(target.getDrawable() instanceof BitmapDrawable)) return;
                            Bitmap bitmap = ((BitmapDrawable) target.getDrawable()).getBitmap();
                            if (left) leftBitmap = bitmap;
                            else rightBitmap = bitmap;
                            comparison.setPhotos(leftBitmap, rightBitmap);
                            boolean ready = comparison.ready();
                            saveButton.setEnabled(ready);
                            shareButton.setEnabled(ready);
                            if (ready) comparisonNotice.setVisibility(View.GONE);
                        },
                        () -> {
                            if (!disposed
                                    && mode == COMPARE
                                    && revision == request
                                    && (left ? leftSource : rightSource) == target)
                                comparisonNotice.setText("有一张照片没载入，点此重试");
                        });
    }

    private void exportComparison(boolean share) {
        if (comparison == null || !comparison.ready()) {
            toast("照片还在载入，请稍等一下");
            return;
        }
        try {
            Bitmap result =
                    comparison.export(
                            host.profile().optString("child_name"),
                            label(years.get(leftIndex)),
                            label(years.get(rightIndex)),
                            Ui.date(leftPhoto.optString("album_date")),
                            Ui.date(rightPhoto.optString("album_date")));
            if (result != null) host.saveComparison(result, share);
        } catch (OutOfMemoryError error) {
            toast("图片暂时无法处理，请关闭一些应用后重试");
        }
    }

    private void openPicker(int index, int purpose, JSONObject selected) {
        stopPlayback();
        if (purpose == REPRESENTATIVE && (!canManage || !host.canEdit())) {
            toast("当前账号可以查看成长记录，代表照由可上传家人整理");
            return;
        }
        pickerIndex = index;
        pickerPurpose = purpose;
        pickerSelection = selected;
        pickerItems.clear();
        pickerIds.clear();
        pickerCursor = "";
        pickerHasMore = true;
        pickerLoading = pickerFailed = pickerSaving = false;
        pickerMessage = "";
        pickerFirst = pickerOffset = 0;
        pickerGrid = null;
        renderPicker();
    }

    private void pickerBack() {
        if (pickerSaving) {
            toast("正在保存代表照，请稍等");
            return;
        }
        if (pickerPurpose == REPRESENTATIVE) renderHome();
        else renderComparison();
    }

    private void renderPicker() {
        if (pickerGrid != null) {
            View first = pickerGrid.getChildAt(0);
            if (first != null) {
                pickerFirst = pickerGrid.getFirstVisiblePosition();
                pickerOffset = first.getTop();
            }
        }
        pickerLoading = false;
        LinearLayout page = Ui.column(activity);
        page.setBackgroundColor(Ui.BG);
        page.addView(bar("选一张" + label(years.get(pickerIndex)) + "时的照片", this::pickerBack));
        TextView intro =
                text(
                        pickerPurpose == REPRESENTATIVE
                                ? "这张照片将出现在全家共享的成长记录里"
                                : "这次选择用于对比，不改变成长记录里的代表照",
                        13,
                        Ui.MUTED);
        intro.setPadding(dp(18), dp(4), dp(18), dp(12));
        page.addView(intro);
        pickerGrid = new GridView(activity);
        pickerGrid.setNumColumns(3);
        pickerGrid.setHorizontalSpacing(dp(4));
        pickerGrid.setVerticalSpacing(dp(4));
        pickerGrid.setPadding(dp(12), 0, dp(12), 0);
        pickerGrid.setClipToPadding(false);
        pickerGrid.setSelector(android.R.color.transparent);
        pickerGrid.setVerticalScrollBarEnabled(false);
        pickerAdapter =
                new BaseAdapter() {
                    @Override
                    public int getCount() {
                        return pickerItems.size();
                    }

                    @Override
                    public Object getItem(int position) {
                        return pickerItems.get(position);
                    }

                    @Override
                    public long getItemId(int position) {
                        return position;
                    }

                    @Override
                    public View getView(int position, View old, ViewGroup parent) {
                        cancelImages(old);
                        JSONObject item = pickerItems.get(position);
                        boolean selected =
                                pickerSelection != null
                                        && pickerSelection
                                                .optString("id")
                                                .equals(item.optString("id"));
                        int side =
                                (activity.getResources().getDisplayMetrics().widthPixels - dp(32))
                                        / 3;
                        FrameLayout frame = new FrameLayout(activity);
                        frame.setLayoutParams(new AbsListView.LayoutParams(-1, side));
                        frame.setPadding(dp(3), dp(3), dp(3), dp(3));
                        frame.setBackground(
                                Ui.border(Ui.BG, 8, selected ? Ui.ACCENT : Ui.BG, activity));
                        ImageView image = new ImageView(activity);
                        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                        image.setBackground(Ui.round(Ui.SOFT, 5, activity));
                        image.setClipToOutline(true);
                        frame.addView(image, new FrameLayout.LayoutParams(-1, -1));
                        host.images().load(image, item.optString("thumbnail_url"), 550);
                        if (selected) {
                            Ui.Icon check = new Ui.Icon(activity, "check", Color.WHITE);
                            check.setPadding(dp(4), dp(4), dp(4), dp(4));
                            check.setBackground(Ui.round(Ui.ACCENT, 14, activity));
                            FrameLayout.LayoutParams checkParams =
                                    new FrameLayout.LayoutParams(
                                            dp(26), dp(26), Gravity.TOP | Gravity.RIGHT);
                            checkParams.setMargins(0, dp(7), dp(7), 0);
                            frame.addView(check, checkParams);
                        }
                        frame.setContentDescription(
                                (selected ? "已选，" : "")
                                        + Ui.date(item.optString("album_date"))
                                        + "的照片");
                        frame.setSelected(selected);
                        frame.setOnClickListener(
                                v -> {
                                    if (pickerSaving) return;
                                    pickerSelection = item;
                                    pickerAdapter.notifyDataSetChanged();
                                    updatePickerStatus();
                                });
                        return frame;
                    }
                };
        pickerGrid.setAdapter(pickerAdapter);
        page.addView(pickerGrid, new LinearLayout.LayoutParams(-1, 0, 1));
        pickerStatus = text("", 13, Ui.MUTED);
        pickerStatus.setGravity(Gravity.CENTER);
        pickerStatus.setPadding(dp(20), dp(8), dp(20), dp(8));
        pickerStatus.setMinHeight(dp(40));
        pickerStatus.setOnClickListener(v -> loadPicker());
        page.addView(pickerStatus);
        usePhotoButton = button("就用这张", true, this::usePickerPhoto);
        LinearLayout.LayoutParams useParams = new LinearLayout.LayoutParams(-1, -2);
        useParams.setMargins(dp(24), 0, dp(24), dp(12));
        page.addView(usePhotoButton, useParams);
        attach(PICKER, page, this::pickerBack);
        pickerGrid.setOnScrollListener(
                new AbsListView.OnScrollListener() {
                    @Override
                    public void onScrollStateChanged(AbsListView view, int state) {}

                    @Override
                    public void onScroll(AbsListView view, int first, int count, int total) {
                        if (total > 0 && first + count >= total - 6 && !pickerFailed) loadPicker();
                    }
                });
        if (!pickerItems.isEmpty()) pickerGrid.setSelectionFromTop(pickerFirst, pickerOffset);
        updatePickerStatus();
        if (pickerItems.isEmpty()) loadPicker();
    }

    private void loadPicker() {
        if (disposed || mode != PICKER || pickerLoading || !pickerHasMore || pickerSaving) return;
        pickerLoading = true;
        pickerFailed = false;
        pickerMessage = "";
        updatePickerStatus();
        final int request = revision;
        String path = "/api/growth/" + years.get(pickerIndex).optInt("year") + "/photos?limit=60";
        if (!pickerCursor.isEmpty()) path += "&cursor=" + ApiClient.encode(pickerCursor);
        host.request(
                "GET",
                path,
                null,
                result -> {
                    if (disposed || revision != request || mode != PICKER) return;
                    pickerLoading = false;
                    JSONArray incoming = result.optJSONArray("items");
                    if (incoming != null)
                        for (int i = 0; i < incoming.length(); i++) {
                            JSONObject item = incoming.getJSONObject(i);
                            if (pickerIds.add(item.optString("id"))) pickerItems.add(item);
                        }
                    pickerCursor =
                            result.isNull("next_cursor") ? "" : result.optString("next_cursor");
                    pickerHasMore = !pickerCursor.isEmpty();
                    pickerAdapter.notifyDataSetChanged();
                    updatePickerStatus();
                },
                message -> {
                    if (disposed || revision != request || mode != PICKER) return;
                    pickerLoading = false;
                    pickerFailed = true;
                    pickerMessage = message;
                    updatePickerStatus();
                });
    }

    private void updatePickerStatus() {
        if (pickerStatus == null) return;
        pickerStatus.setText(
                pickerSaving
                        ? "正在保存代表照…"
                        : pickerFailed
                                ? pickerMessage + "，点这里重试"
                                : pickerLoading
                                        ? "正在读取照片…"
                                        : pickerItems.isEmpty()
                                                ? "这个年龄还没有照片"
                                                : pickerHasMore ? "继续往下看，挑一张喜欢的" : "这个年龄的照片都在这里了");
        usePhotoButton.setEnabled(pickerSelection != null && !pickerSaving);
    }

    private void usePickerPhoto() {
        if (pickerSelection == null || pickerSaving) return;
        if (pickerPurpose != REPRESENTATIVE) {
            if (pickerPurpose == LEFT_PHOTO) leftPhoto = pickerSelection;
            else rightPhoto = pickerSelection;
            renderComparison();
            return;
        }
        pickerSaving = true;
        updatePickerStatus();
        final int index = pickerIndex;
        host.request(
                "PUT",
                "/api/growth/" + years.get(index).optInt("year"),
                json("media_id", pickerSelection.optString("id")),
                result -> {
                    if (disposed) return;
                    JSONObject chosen = result.optJSONObject("selected");
                    if (chosen != null && index < years.size())
                        years.get(index).put("selected", chosen);
                    pickerSaving = false;
                    if (mode != PICKER || pickerPurpose != REPRESENTATIVE || pickerIndex != index)
                        return;
                    selectedIndex = index;
                    renderHome();
                    toast("成长代表照已保存，家人也能看到");
                },
                message -> {
                    if (disposed) return;
                    pickerSaving = false;
                    if (mode != PICKER || pickerPurpose != REPRESENTATIVE || pickerIndex != index)
                        return;
                    pickerStatus.setText(message);
                    usePhotoButton.setEnabled(pickerSelection != null);
                });
    }

    private void cancelCompareLoads() {
        if (leftSource != null) host.images().cancel(leftSource);
        if (rightSource != null) host.images().cancel(rightSource);
        leftSource = rightSource = null;
    }

    private void cancelImages(View view) {
        if (view instanceof ImageView) host.images().cancel((ImageView) view);
        else if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) cancelImages(group.getChildAt(i));
        }
    }
}
