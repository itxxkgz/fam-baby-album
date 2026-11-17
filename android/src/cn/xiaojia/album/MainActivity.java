package cn.xiaojia.album;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.ExifInterface;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.*;
import android.widget.*;

import org.json.*;

import java.io.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity
        implements BabySettings.Host, MemoryFeatureHost, SocialFeedHost {
    private ApiClient api;
    private ImageLoader images;
    private final ExecutorService work = Executors.newFixedThreadPool(4);
    // Some Android socket implementations release a cancelled read only after
    // disconnect finishes. Keep these optional group reads off the main API pool.
    private final ExecutorService cancelableReads = Executors.newFixedThreadPool(2);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private LinearLayout root, navigation;
    private FrameLayout content;
    private JSONObject profile = new JSONObject();
    private JSONObject member = new JSONObject();
    private JSONArray babies = new JSONArray();
    private BabySettings babySettings;
    private AccountScreens accounts;
    private FamilySettings familySettings;
    private StorageSettings storageSettings;
    private GrowthPages growthPages;
    private StorybookPages storybookPages;
    private ActivityFeedPage activityFeed;
    private MePage mePage;
    private String activeMemoryFeature = "";
    private Runnable featureMediaBack;
    private boolean detailFeatureScope;
    private android.graphics.Bitmap pendingComparison;
    private boolean pendingComparisonShare;
    private boolean comparisonWriting;
    private Runnable settingsBack;
    private volatile int accountEpoch = 0;
    private boolean identityRefreshBusy;
    private WarmSheet activeCommentsDialog;
    private final ArrayList<WarmSheet> secondarySheets = new ArrayList<>();
    private final ThreadLocal<ApiClient> settingClient = new ThreadLocal<>();
    private int uploadPickerEpoch;
    private WarmSheet uploadProgress;
    private String currentPage = "album",
            selectedTab = "album",
            filter = "all",
            selectedMonth = "",
            selectedDate = "";
    private boolean favoritesOnly = false,
            destroyed = false,
            loading = false,
            loadFailed = false,
            hasMore = true,
            trashMode = false;
    private String cursor = "";
    private int generation = 0, detailIndex = 0, savedListPosition = 0, savedListOffset = 0;
    private final ArrayList<JSONObject> media = new ArrayList<>(),
            displayOrder = new ArrayList<>(),
            detailItems = new ArrayList<>();
    private final HashSet<String> mediaIds = new HashSet<>();
    private ListView albumList;
    private View albumFloatingTools;
    private boolean albumToolsVisible;
    private AlbumAdapter albumAdapter;
    private AlbumAgeNavigator albumAgeNavigator;
    private View albumProfileHeader;
    private boolean albumTrackMeasured, albumPositionAdjusting;
    private final AlbumDragNavigation albumDragNavigation = new AlbumDragNavigation();
    private Runnable albumDragCancel;
    private AlbumDragNavigation.Ticket albumDragRequest;
    private final Runnable albumDragPump = this::pumpAlbumDrag;
    private AlbumDragSnapshot albumDragOrigin;
    private boolean albumWindowHeadComplete;
    private TextView albumNewerLink;
    private String previousAlbumCursor = "";
    private boolean albumHasNewer, loadingNewer, locatingAlbum;
    private long albumNavigationSettleUntil;
    private TextView loadFooter,
            detailDate,
            detailAge,
            detailCount,
            detailDescription,
            detailComments;
    private Ui.Icon detailFavoriteIcon;
    private View detailFavoriteButton;
    private View detailEditIcon;
    private final HashMap<String, TextView> statLabels = new HashMap<>();
    private TextView storageLabel, albumSummary, detailUploader, detailSaveLabel;
    private LinearLayout detailTop, detailStory, detailSheet, detailVideoControls;
    private TextView detailNotice, detailHint, detailVideoTime;
    private SeekBar detailSeek;
    private PlaybackButton detailPlay;
    private MediaPagerView detailPager;
    private View detailCurrentView, detailPreviousView, detailNextView;
    private boolean detailChromeVisible = true, detailSeeking;
    private int detailInsetTop, detailInsetBottom;
    private int detailBindGeneration, detailWindowBase, detailTotal, detailPendingDirection;
    private boolean detailWindowReady, detailHasBefore, detailHasAfter;
    private boolean detailLoadingInitial, detailLoadingBefore, detailLoadingAfter;
    private String detailPendingAnchor = "", detailKind = "all", detailMonth = "", detailDay = "";
    private boolean detailFavorites, detailDeleted;
    private static final int VIEWER_BACKGROUND = 0xFF0B0B0B, VIEWER_TEXT = 0xFFFFF9F3;
    private static final int DETAIL_IMAGE_SIZE = 2600;
    private final Runnable detailAutoHide =
            () -> {
                if (currentPage.equals("detail")
                        && this.video != null
                        && this.videoPrepared
                        && this.video.isPlaying()
                        && !detailSeeking) setDetailChrome(false, true);
            };
    private final Runnable detailTicker =
            new Runnable() {
                @Override
                public void run() {
                    if (!currentPage.equals("detail")
                            || video == null
                            || !resumed
                            || !videoPrepared) return;
                    updateVideoControls();
                    handler.postDelayed(this, 500);
                }
            };
    private String detailReturnPage = "album";
    private ScrollView timeHomeView;
    private TimeHomePage timeHomePage;
    private View timeMonthsView;
    private int timeHomeRestoreY = -1, timeMonthsPosition, timeMonthsOffset;
    private ListView timeMonthsList;
    private boolean timeHomeDirty, timeMonthsDirty;
    private final HashMap<String, String> timeMonthCovers = new HashMap<>();
    private final LinkedHashMap<String, TimeCollectionPage> timeCollections = new LinkedHashMap<>();
    private TimeCollectionPage activeTimeCollection;
    private String detailTimeCollectionKey = "";
    private int detailLoadGeneration = 0;
    private FrameLayout detailMedia;
    private VideoView video;
    private boolean resumed = false, videoPrepared = false, videoWantsPlay = false;
    private boolean videoRestorePending = false;
    private int videoSavedPosition = 0, videoSavedDuration = 0;
    private String storyReturnVideo = "";
    private int storyReturnPosition;
    private boolean uploadRunning = false;
    private boolean smallPhotos = true;
    private JSONObject pendingSave;
    private static final int PICK_UPLOAD = 20, PICK_AVATAR = 21;

    private interface Task {
        JSONObject run() throws Exception;
    }

    private interface Result {
        void accept(JSONObject value) throws Exception;
    }

    private interface MenuChoice {
        void select(int index);
    }

    @Override
    public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        getWindow()
                .getDecorView()
                .setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                                | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        api = new ApiClient(this);
        accounts = new AccountScreens(this);
        babySettings = new BabySettings(this);
        familySettings = new FamilySettings(this);
        storageSettings = new StorageSettings(this);
        smallPhotos = getPreferences(MODE_PRIVATE).getBoolean("small_photos", true);
        images = new ImageLoader(this, api);
        if (api.token.isEmpty()) showLogin();
        else connect();
    }

    private WarmSheet sheet(String title) {
        WarmSheet sheet = new WarmSheet(this, title);
        secondarySheets.add(sheet);
        sheet.onDismiss(
                () -> {
                    secondarySheets.remove(sheet);
                    cancelPageImages(sheet.body);
                });
        return sheet;
    }

    private void dismissSecondarySheets() {
        WarmSheet.dismissAll(this);
        for (WarmSheet sheet : new ArrayList<>(secondarySheets)) sheet.dismiss();
        secondarySheets.clear();
    }

    private ImageView mediaThumb(JSONObject item, int size) {
        ImageView image = new TopCropImageView(this);
        image.setBackground(Ui.round(Ui.SOFT, 12, this));
        image.setClipToOutline(true);
        image.setContentDescription("照片预览");
        image.setLayoutParams(new LinearLayout.LayoutParams(dp(size), dp(size)));
        images.load(image, item.optString("thumbnail_url"), 450);
        return image;
    }

    private void confirmAction(String title, String message, String label, Runnable action) {
        WarmSheet confirm = sheet(title);
        SecondaryUi.note(this, confirm.body, message);
        confirm.primary(
                label,
                () -> {
                    confirm.dismiss();
                    action.run();
                });
        confirm.secondary("取消", confirm::dismiss);
        confirm.show();
    }

    private int dp(float size) {
        return Ui.dp(this, size);
    }

    private TextView text(String value, int size, int color) {
        return Ui.text(this, value, size, color);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private String errorText(Exception error) {
        if (error instanceof java.net.SocketTimeoutException) return "网络响应较慢，请稍后重试";
        String message = error.getMessage();
        if (message != null
                && message.matches(".*[\\u4e00-\\u9fff].*")
                && message.length() < 150
                && !message.contains("http")) return message;
        if (error instanceof IOException) return "无法连接相册服务器，请确认相册服务已启动，并检查网络";
        return "操作未完成，请稍后重试";
    }

    private void async(Task task, Result success, Result failure) {
        final int requestAccount = accountEpoch;
        final ApiClient requestClient = api.snapshot();
        work.execute(
                () -> {
                    if (requestAccount != accountEpoch) return;
                    settingClient.set(requestClient);
                    try {
                        JSONObject result = task.run();
                        handler.post(
                                () -> {
                                    if (!destroyed && requestAccount == accountEpoch)
                                        try {
                                            success.accept(result);
                                        } catch (Exception e) {
                                            toast(errorText(e));
                                        }
                                });
                    } catch (Exception error) {
                        handler.post(
                                () -> {
                                    if (!destroyed && requestAccount == accountEpoch)
                                        try {
                                            if (failure != null)
                                                failure.accept(
                                                        new JSONObject()
                                                                .put("message", errorText(error))
                                                                .put(
                                                                        "status",
                                                                        error
                                                                                        instanceof
                                                                                        ApiClient
                                                                                                .HttpError
                                                                                ? ((ApiClient
                                                                                                        .HttpError)
                                                                                                error)
                                                                                        .status
                                                                                : 0));
                                            else toast(errorText(error));
                                        } catch (Exception ignored) {
                                        }
                                });
                    } finally {
                        settingClient.remove();
                    }
                });
    }

    private JSONObject object(String key, Object value) {
        JSONObject object = new JSONObject();
        try {
            object.put(key, value);
        } catch (Exception ignored) {
        }
        return object;
    }

    private String str(JSONObject object, String key) {
        return object.isNull(key) ? "" : object.optString(key, "");
    }

    private JSONArray array(JSONObject object, String key) {
        JSONArray result = object.optJSONArray(key);
        return result == null ? new JSONArray() : result;
    }

    private void stopVideo() {
        handler.removeCallbacks(detailTicker);
        handler.removeCallbacks(detailAutoHide);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (video != null) {
            video.stopPlayback();
            video = null;
        }
        videoPrepared = false;
        videoWantsPlay = false;
        videoRestorePending = false;
        videoSavedPosition = videoSavedDuration = 0;
    }

    private void screen(boolean tabs) {
        dismissSecondarySheets();
        cancelAlbumDrag(false);
        if (albumAgeNavigator != null) {
            albumAgeNavigator.dispose();
            albumAgeNavigator = null;
        }
        albumNewerLink = null;
        albumProfileHeader = null;
        albumTrackMeasured = false;
        albumPositionAdjusting = false;
        if (activityFeed != null && !currentPage.equals("activity")) activityFeed.pause();
        stopVideo();
        cancelDetailImage();
        cancelDetailPreviews();
        if (detailPager != null) {
            detailPager.dispose();
            detailPager = null;
        }
        detailCurrentView = null;
        detailBindGeneration++;
        settingsBack = null;
        boolean dark = currentPage.equals("detail");
        getWindow().setStatusBarColor(dark ? Color.TRANSPARENT : Ui.BG);
        getWindow().setNavigationBarColor(dark ? Color.TRANSPARENT : Ui.BG);
        getWindow()
                .getDecorView()
                .setSystemUiVisibility(
                        dark
                                ? View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                                        | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        root = Ui.column(this);
        albumFloatingTools = null;
        albumToolsVisible = false;
        root.setBackgroundColor(dark ? VIEWER_BACKGROUND : Ui.BG);
        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        if (tabs) {
            View line = new View(this);
            line.setBackgroundColor(Ui.LINE);
            root.addView(line, new LinearLayout.LayoutParams(-1, dp(.5f)));
            navigation = Ui.row(this);
            navigation.setPadding(dp(8), 0, dp(8), 0);
            navigation.setBackgroundColor(Ui.BG);
            navigation.setMinimumHeight(dp(52));
            root.addView(navigation, new LinearLayout.LayoutParams(-1, -2));
            addTab("album", "相册", "photo");
            addTab("time", "时光", "time");
            addTab("activity", "动态", "activity");
            addTab("me", "我的", "me");
        }
        setContentView(root);
    }

    private void addTab(String key, String title, String icon) {
        boolean selected = selectedTab.equals(key);
        int color = selected ? Ui.ACCENT_DARK : Ui.MUTED;
        FrameLayout slot = new FrameLayout(this);
        LinearLayout tab = Ui.column(this);
        tab.setGravity(Gravity.CENTER);
        tab.setPadding(dp(8), dp(3), dp(8), dp(2));
        tab.setMinimumHeight(dp(52));
        tab.setBackground(Ui.ripple(selected ? Ui.SOFT : Ui.BG, 18, this));
        Ui.Icon image = new Ui.Icon(this, icon, color, selected);
        tab.addView(image, new LinearLayout.LayoutParams(dp(24), dp(24)));
        TextView label = text(title, 11, color);
        label.setGravity(Gravity.CENTER);
        label.setMinHeight(dp(18));
        if (selected) label.setTypeface(null, Typeface.BOLD);
        tab.addView(label, new LinearLayout.LayoutParams(-2, -2));
        slot.addView(tab, new FrameLayout.LayoutParams(dp(66), -2, Gravity.CENTER));
        navigation.addView(slot, new LinearLayout.LayoutParams(0, -2, 1));
        slot.setContentDescription(title);
        slot.setOnClickListener(v -> selectTab(key));
        tab.setOnClickListener(v -> selectTab(key));
        tab.setSelected(selected);
    }

    private void selectTab(String key) {
        if (routePasswordChangeIfNeeded()) return;
        rememberBrowsingPosition();
        refreshIdentity();
        if (key.equals("album") && !api.token.isEmpty()) refreshProfile();
        if (key.equals("album") && currentPage.equals("album") && albumList != null) {
            if (albumHasNewer || locatingAlbum || loadingNewer) {
                showAlbum(true);
                return;
            }
            savedListPosition = 0;
            savedListOffset = 0;
            albumList.setSelection(0);
            return;
        }
        cancelAlbumRequests();
        loadFailed = false;
        selectedTab = key;
        currentPage = key;
        if (key.equals("album")) {
            trashMode = false;
            showAlbum(false);
        } else if (key.equals("time")) showTime();
        else if (key.equals("months")) showMonths();
        else if (key.equals("activity")) showActivity();
        else showMe();
    }

    private View iconButton(String icon, int color, Runnable action) {
        FrameLayout frame = new FrameLayout(this);
        Ui.Icon image = new Ui.Icon(this, icon, color);
        FrameLayout.LayoutParams params =
                new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER);
        frame.addView(image, params);
        frame.setContentDescription(image.getContentDescription());
        frame.setOnClickListener(v -> action.run());
        return frame;
    }

    private LinearLayout toolbar(String title, Runnable back) {
        LinearLayout bar = Ui.row(this);
        bar.setPadding(dp(8), 0, dp(16), 0);
        bar.setBackgroundColor(Ui.BG);
        if (back != null)
            bar.addView(
                    iconButton("back", Ui.INK, back),
                    new LinearLayout.LayoutParams(dp(44), dp(56)));
        TextView name = text(title, 20, Ui.INK);
        name.setTypeface(null, Typeface.BOLD);
        name.setGravity(Gravity.CENTER_VERTICAL);
        if (back == null) name.setPadding(dp(12), 0, 0, 0);
        bar.addView(name, new LinearLayout.LayoutParams(0, dp(56), 1));
        return bar;
    }

    private TextView action(String title, Runnable click) {
        TextView view = text(title, 14, Ui.ACCENT_DARK);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(14), dp(10), dp(14), dp(10));
        view.setMinHeight(dp(44));
        view.setBackground(Ui.ripple(Color.TRANSPARENT, 12, this));
        view.setOnClickListener(v -> click.run());
        return view;
    }

    private void showLogin() {
        if (passwordChangeRequired()) {
            clearSession(true);
            return;
        }
        cancelAlbumRequests();
        accounts.showLogin();
    }

    private EditText input(String hint, String value, int type) {
        EditText field = new EditText(this);
        field.setTextSize(16);
        field.setTextColor(Ui.INK);
        field.setHintTextColor(Ui.MUTED);
        field.setHint(hint);
        field.setContentDescription(hint);
        field.setText(value);
        field.setInputType(type);
        field.setPadding(dp(14), dp(8), dp(14), dp(8));
        field.setBackground(Ui.border(Ui.SURFACE, 14, Ui.LINE, this));
        return field;
    }

    private void connect() {
        clearChildContent();
        currentPage = "connecting";
        screen(false);
        showEmpty(content, "正在打开家庭相册…", null, null);
        async(
                () -> {
                    ApiClient scoped = client();
                    String preferred = scoped.childId;
                    scoped.childId = "";
                    JSONObject me = scoped.request("GET", "/api/me", null);
                    JSONObject result = object("member", me);
                    if (!me.optBoolean("password_change_required")) {
                        JSONObject available = scoped.request("GET", "/api/children", null);
                        JSONArray children = array(available, "items");
                        ArrayList<String> ids = new ArrayList<>();
                        for (int i = 0; i < children.length(); i++) {
                            JSONObject child = children.getJSONObject(i);
                            if (!child.optBoolean("archived")) ids.add(child.optString("id"));
                        }
                        String owner = scoped.baseUrl + "|" + me.optString("id");
                        String previousOwner =
                                getSharedPreferences("album", 0)
                                        .getString("selected_child_owner", "");
                        if (!previousOwner.isEmpty() && !owner.equals(previousOwner))
                            preferred = "";
                        String selected = BabyScope.choose(preferred, ids);
                        result.put("children", children)
                                .put("selected_child", selected)
                                .put("owner", owner);
                        if (!selected.isEmpty()) {
                            scoped.childId = selected;
                            result.put("member", scoped.request("GET", "/api/me", null));
                            result.put("profile", scoped.request("GET", "/api/profile", null));
                        }
                    }
                    return result;
                },
                result -> {
                    if (!currentPage.equals("connecting")) return;
                    member = result.getJSONObject("member");
                    rememberMember();
                    if (routePasswordChangeIfNeeded()) return;
                    babies = array(result, "children");
                    api.selectChild(result.optString("selected_child"));
                    getSharedPreferences("album", 0)
                            .edit()
                            .putString("selected_child_owner", result.optString("owner"))
                            .apply();
                    profile = result.optJSONObject("profile");
                    if (profile == null) profile = new JSONObject();
                    images.close();
                    images = new ImageLoader(this, api);
                    resetAlbum();
                    if (api.childId.isEmpty()) {
                        showNoChildren();
                        return;
                    }
                    selectedTab = "album";
                    showAlbum(false);
                },
                failure -> {
                    content.removeAllViews();
                    showEmpty(content, failure.optString("message"), "重新连接", this::connect);
                    TextView settings = action("修改服务器或重新登录", this::showLogin);
                    FrameLayout.LayoutParams p =
                            new FrameLayout.LayoutParams(
                                    -2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
                    p.bottomMargin = dp(35);
                    content.addView(settings, p);
                });
    }

    private void showEmpty(FrameLayout target, String message, String button, Runnable click) {
        LinearLayout panel = Ui.column(this);
        panel.setGravity(Gravity.CENTER);
        panel.setPadding(dp(32), dp(24), dp(32), dp(24));
        FrameLayout emblem = new FrameLayout(this);
        emblem.setBackground(Ui.round(Ui.SOFT, 26, this));
        emblem.addView(
                new Ui.Icon(this, "photo", Ui.ACCENT),
                new FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER));
        panel.addView(emblem, new LinearLayout.LayoutParams(dp(64), dp(64)));
        TextView title = text(message, 15, Ui.INK);
        title.setGravity(Gravity.CENTER);
        title.setLineSpacing(dp(4), 1);
        title.setPadding(0, dp(20), 0, dp(10));
        panel.addView(title, new LinearLayout.LayoutParams(-1, -2));
        if (button != null) {
            TextView retry = action(button, click);
            retry.setBackground(Ui.button(this, false));
            panel.addView(retry);
        }
        target.addView(panel, new FrameLayout.LayoutParams(-1, -1));
    }

    private void cancelAlbumRequests() {
        cancelAlbumDrag(false);
        generation++;
        loading = false;
        loadingNewer = false;
        locatingAlbum = false;
        if (albumAgeNavigator != null) albumAgeNavigator.setLoading(false);
    }

    private void resetAlbum() {
        cancelAlbumDrag(false);
        media.clear();
        mediaIds.clear();
        cursor = "";
        hasMore = true;
        loading = false;
        loadFailed = false;
        previousAlbumCursor = "";
        albumHasNewer = false;
        loadingNewer = false;
        locatingAlbum = false;
        albumWindowHeadComplete = false;
        generation++;
        savedListPosition = 0;
        savedListOffset = 0;
    }

    private void showAlbum(boolean reload) {
        currentPage = trashMode ? "trash" : "album";
        if (!trashMode) selectedTab = "album";
        if (reload) resetAlbum();
        screen(!trashMode);
        LinearLayout layout = Ui.column(this);
        content.addView(layout, new FrameLayout.LayoutParams(-1, -1));
        if (trashMode) {
            layout.addView(
                    toolbar(
                            "回收站",
                            () -> {
                                trashMode = false;
                                resetAlbum();
                                showMe();
                            }),
                    new LinearLayout.LayoutParams(-1, dp(56)));
            TextView hint = text("移出的照片和视频仍在这里，可点开恢复", 13, Ui.MUTED);
            hint.setPadding(dp(20), 0, dp(20), dp(8));
            layout.addView(hint);
        }
        albumList = new TimelineListView(!trashMode);
        albumList.setDivider(null);
        albumList.setBackgroundColor(Ui.BG);
        albumList.setCacheColorHint(Color.TRANSPARENT);
        albumList.setSelector(android.R.color.transparent);
        albumList.setRecyclerListener(this::cancelPageImages);
        albumList.setVerticalScrollBarEnabled(false);
        albumList.setOverScrollMode(View.OVER_SCROLL_NEVER);
        if (!trashMode) {
            albumProfileHeader = albumHeader();
            albumList.addHeaderView(albumProfileHeader, null, false);
        }
        loadFooter = text("正在加载…", 13, Ui.MUTED);
        loadFooter.setGravity(Gravity.CENTER);
        loadFooter.setPadding(dp(20), dp(22), dp(20), dp(26));
        loadFooter.setOnClickListener(v -> loadMedia());
        albumList.addFooterView(loadFooter, null, false);
        albumAdapter = new AlbumAdapter();
        rebuildRows();
        albumList.setAdapter(albumAdapter);
        layout.addView(albumList, new LinearLayout.LayoutParams(-1, 0, 1));
        if (!trashMode) {
            albumFloatingTools = albumTools();
            albumFloatingTools.setVisibility(View.INVISIBLE);
            albumFloatingTools.setTranslationY(-dp(52));
            content.addView(
                    albumFloatingTools, new FrameLayout.LayoutParams(-1, dp(52), Gravity.TOP));
            albumAgeNavigator =
                    new AlbumAgeNavigator(
                            this,
                            new AlbumAgeNavigator.Listener() {
                                @Override
                                public void onRequestIndex() {
                                    loadAlbumAgeIndex();
                                }

                                @Override
                                public void onDateSelected(String day) {
                                    locateAlbumDate(day);
                                }

                                @Override
                                public void onDragStarted() {
                                    beginAlbumDrag();
                                }

                                @Override
                                public void onDatePreview(String day) {
                                    previewAlbumDrag(day);
                                }

                                @Override
                                public void onDragFinished(String day) {
                                    finishAlbumDrag(day);
                                }

                                @Override
                                public void onDragCancelled() {
                                    cancelAlbumDrag(true);
                                }
                            });
            content.addView(albumAgeNavigator, new FrameLayout.LayoutParams(-1, -1));
        }
        albumList.setOnScrollListener(
                new AbsListView.OnScrollListener() {
                    private boolean browsing;
                    private int previousFirst = -1, previousTop, distance;

                    public void onScrollStateChanged(AbsListView view, int state) {
                        if (state == SCROLL_STATE_TOUCH_SCROLL && !albumDragNavigation.isDragging())
                            cancelAlbumDrag(false);
                        browsing = state != SCROLL_STATE_IDLE;
                        if (!browsing) distance = 0;
                    }

                    public void onScroll(AbsListView view, int first, int count, int total) {
                        View child = view.getChildAt(0);
                        int top = child == null ? 0 : child.getTop();
                        if (!trashMode && view == albumList) {
                            updateAlbumAge(first);
                            if (first == 0) setAlbumToolsVisible(false);
                            else if (browsing && previousFirst >= 0) {
                                int delta =
                                        first == previousFirst
                                                ? previousTop - top
                                                : (first > previousFirst ? dp(18) : -dp(18));
                                if (Integer.signum(delta) != Integer.signum(distance)) distance = 0;
                                distance += delta;
                                if (Math.abs(distance) >= dp(16)) {
                                    setAlbumToolsVisible(distance < 0);
                                    distance = 0;
                                }
                            }
                            boolean movingToNewer =
                                    previousFirst >= 0
                                            && (first < previousFirst
                                                    || (first == previousFirst
                                                            && top > previousTop));
                            if (browsing
                                    && movingToNewer
                                    && first <= albumList.getHeaderViewsCount() + 1
                                    && android.os.SystemClock.elapsedRealtime()
                                            > albumNavigationSettleUntil) loadNewerAlbumMedia();
                        }
                        previousFirst = first;
                        previousTop = top;
                        if (total > 0
                                && first + count >= total - 4
                                && hasMore
                                && !loading
                                && !loadFailed) loadMedia();
                    }
                });
        if (media.isEmpty()) loadMedia();
        else {
            updateFooter();
            albumList.setSelectionFromTop(savedListPosition, savedListOffset);
        }
        if (albumAgeNavigator != null) {
            final AlbumAgeNavigator navigator = albumAgeNavigator;
            final ListView target = albumList;
            navigator.preload();
            target.post(
                    () -> {
                        if (navigator != albumAgeNavigator || target != albumList) return;
                        measureAlbumAgeTrack();
                        updateAlbumAge(target.getFirstVisiblePosition());
                    });
        }
    }

    private void setAlbumToolsVisible(boolean visible) {
        if (albumFloatingTools == null || albumToolsVisible == visible) return;
        albumToolsVisible = visible;
        View tools = albumFloatingTools;
        tools.animate().cancel();
        if (visible) tools.setVisibility(View.VISIBLE);
        tools.animate()
                .translationY(visible ? 0 : -dp(52))
                .alpha(visible ? 1 : 0)
                .setDuration(160)
                .withEndAction(
                        () -> {
                            if (!visible && tools == albumFloatingTools && !albumToolsVisible)
                                tools.setVisibility(View.INVISIBLE);
                        })
                .start();
    }

    private View albumHeader() {
        statLabels.clear();
        albumSummary = null;
        LinearLayout header = Ui.column(this);
        header.setPadding(dp(16), dp(6), dp(16), dp(2));
        LinearLayout info = Ui.row(this);
        LinearLayout identity = Ui.row(this);
        FrameLayout avatarFrame = new FrameLayout(this);
        avatarFrame.setBackground(Ui.round(Ui.SOFT, 28, this));
        avatarFrame.setPadding(dp(2), dp(2), dp(2), dp(2));
        avatarFrame.setClipToOutline(true);
        avatarFrame.addView(
                new Ui.Icon(this, "baby", Ui.ACCENT, true), new FrameLayout.LayoutParams(-1, -1));
        if (!str(profile, "avatar_url").isEmpty()) {
            ImageView avatar = new ImageView(this);
            avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
            avatarFrame.addView(avatar, new FrameLayout.LayoutParams(-1, -1));
            images.load(avatar, str(profile, "avatar_url"), 250);
        }
        identity.addView(avatarFrame, new LinearLayout.LayoutParams(dp(52), dp(52)));
        LinearLayout names = Ui.column(this);
        names.setPadding(dp(10), 0, dp(4), 0);
        TextView child = text(profile.optString("child_name", "宝宝"), 22, Ui.INK);
        child.setTypeface(null, Typeface.BOLD);
        child.setMaxLines(1);
        child.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout nameLine = Ui.row(this);
        nameLine.addView(child, new LinearLayout.LayoutParams(-2, -2, 1));
        FrameLayout nextBadge = new FrameLayout(this);
        nextBadge.setBackground(Ui.round(0xFFF3ECE6, 10, this));
        nextBadge.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        nextBadge.addView(
                new Ui.Icon(this, "next", Ui.SOFT_INK),
                new FrameLayout.LayoutParams(dp(12), dp(12), Gravity.CENTER));
        LinearLayout.LayoutParams badgeSpace = new LinearLayout.LayoutParams(dp(20), dp(20));
        badgeSpace.leftMargin = dp(7);
        nameLine.addView(nextBadge, badgeSpace);
        names.addView(nameLine, new LinearLayout.LayoutParams(-2, -2));
        TextView age =
                text(
                        Ui.age(
                                profile.optString("birthday"),
                                LocalDate.now(ZoneId.of("Asia/Shanghai")).toString()),
                        12,
                        Ui.MUTED);
        age.setMaxLines(2);
        age.setPadding(0, dp(3), 0, 0);
        names.addView(age);
        identity.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
        identity.setOnClickListener(v -> babySettings.selector(() -> showAlbum(false)));
        identity.setContentDescription(
                "当前宝宝：" + profile.optString("child_name", "宝宝") + "，切换或管理宝宝相册");
        identity.setMinimumHeight(dp(68));
        info.addView(identity, new LinearLayout.LayoutParams(0, -2, 1));
        addAlbumActions(info);
        header.addView(info, new LinearLayout.LayoutParams(-1, -2));
        JSONObject counts = profile.optJSONObject("counts");
        if (counts == null) counts = new JSONObject();
        LinearLayout statistics = Ui.row(this);
        statistics.addView(
                stat(
                        "photo",
                        counts.optInt("photos", profile.optInt("photos_count")),
                        Ui.ACCENT,
                        () -> applyFilter("photo", false)));
        statistics.addView(
                stat(
                        "video",
                        counts.optInt("videos", profile.optInt("videos_count")),
                        Ui.ACCENT,
                        () -> applyFilter("video", false)));
        statistics.addView(
                stat(
                        "people",
                        counts.optInt("members", profile.optInt("members_count")),
                        Ui.ACCENT,
                        this::showMembers));
        header.addView(statistics, new LinearLayout.LayoutParams(-1, dp(44)));
        if (!filter.equals("all")
                || favoritesOnly
                || !selectedMonth.isEmpty()
                || !selectedDate.isEmpty()) {
            ArrayList<String> labels = new ArrayList<>();
            if (filter.equals("photo")) labels.add("仅看照片");
            if (filter.equals("video")) labels.add("仅看视频");
            if (favoritesOnly) labels.add("我的收藏");
            if (!selectedDate.isEmpty()) labels.add(Ui.date(selectedDate));
            else if (!selectedMonth.isEmpty()) labels.add(Ui.month(selectedMonth));
            LinearLayout active = Ui.row(this);
            TextView label = text(String.join(" · ", labels), 12, Ui.ACCENT_DARK);
            label.setMaxLines(2);
            active.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
            active.addView(action("查看全部", () -> applyFilter("all", false)));
            header.addView(active, new LinearLayout.LayoutParams(-1, dp(44)));
        }
        albumNewerLink = text("向上看更近的照片", 13, Ui.ACCENT_DARK);
        albumNewerLink.setGravity(Gravity.CENTER);
        albumNewerLink.setMinimumHeight(dp(48));
        albumNewerLink.setBackground(Ui.ripple(Ui.SOFT, 16, this));
        albumNewerLink.setOnClickListener(v -> loadNewerAlbumMedia());
        albumNewerLink.setVisibility(albumHasNewer ? View.VISIBLE : View.GONE);
        header.addView(albumNewerLink, new LinearLayout.LayoutParams(-1, -2));
        return header;
    }

    private String albumCounts() {
        JSONObject counts = profile.optJSONObject("counts");
        if (counts == null) counts = new JSONObject();
        return counts.optInt("photos", profile.optInt("photos_count"))
                + " 张照片 · "
                + counts.optInt("videos", profile.optInt("videos_count"))
                + " 个视频 · "
                + counts.optInt("members", profile.optInt("members_count"))
                + " 位家人";
    }

    private View albumTools() {
        LinearLayout tools = Ui.row(this);
        tools.setPadding(dp(16), 0, dp(16), 0);
        tools.setBackgroundColor(Ui.BG);
        TextView title = text(profile.optString("child_name", "宝宝") + "的相册", 16, Ui.INK);
        title.setTypeface(null, Typeface.BOLD);
        title.setMaxLines(1);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tools.addView(title, new LinearLayout.LayoutParams(0, -1, 1));
        addAlbumActions(tools);
        return tools;
    }

    private void addAlbumActions(LinearLayout tools) {
        View filterButton = iconButton("filter", Ui.INK, this::showFilter);
        filterButton.setContentDescription("筛选相册");
        filterButton.setBackground(Ui.ripple(Color.TRANSPARENT, 14, this));
        tools.addView(filterButton, new LinearLayout.LayoutParams(dp(48), dp(48)));
        if (permitted("can_upload")) {
            FrameLayout target = new FrameLayout(this);
            LinearLayout upload = Ui.row(this);
            upload.setGravity(Gravity.CENTER);
            upload.setPadding(dp(10), 0, dp(12), 0);
            upload.setMinimumHeight(dp(32));
            upload.setBackground(Ui.button(this, true));
            upload.addView(
                    new Ui.Icon(this, "plus", Color.WHITE),
                    new LinearLayout.LayoutParams(dp(15), dp(15)));
            TextView label = text("上传", 13, Color.WHITE);
            label.setSingleLine(true);
            label.setPadding(dp(3), 0, 0, 0);
            upload.addView(label);
            target.addView(upload, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
            target.setContentDescription("上传");
            target.setOnClickListener(v -> pickUpload());
            tools.addView(
                    target,
                    new LinearLayout.LayoutParams(
                            Math.max(
                                    dp(72),
                                    dp(46) + (int) Math.ceil(label.getPaint().measureText("上传"))),
                            Math.max(dp(48), label.getLineHeight() + dp(12))));
        }
    }

    private View stat(String icon, int count, int color, Runnable click) {
        LinearLayout row = Ui.row(this);
        row.addView(
                new Ui.Icon(this, icon, color, true),
                new LinearLayout.LayoutParams(dp(18), dp(18)));
        TextView value = text(" " + count, 14, Ui.MUTED);
        statLabels.put(icon, value);
        row.addView(value);
        row.setPadding(0, 0, dp(24), 0);
        row.setMinimumHeight(dp(44));
        String label = icon.equals("photo") ? "张照片" : icon.equals("video") ? "个视频" : "位家人";
        row.setContentDescription(count + label);
        row.setOnClickListener(v -> click.run());
        return row;
    }

    private void applyFilter(String kind, boolean favorite) {
        filter = kind;
        favoritesOnly = favorite;
        selectedDate = "";
        selectedMonth = "";
        showAlbum(true);
    }

    private void showFilter() {
        final int epoch = accountEpoch;
        WarmSheet sheet = sheet("筛选相册");
        boolean[] small = {smallPhotos};
        String[] kind = {filter}, month = {selectedMonth};
        boolean[] monthChanged = {false};
        SecondaryUi.note(this, sheet.body, "显示大小");
        LinearLayout sizeOptions = Ui.row(this);
        Runnable[] bindSize = new Runnable[1];
        bindSize[0] =
                () -> {
                    sizeOptions.removeAllViews();
                    sizeOptions.addView(
                            drawerChoice(
                                    "large_grid",
                                    "大图",
                                    !small[0],
                                    false,
                                    () -> {
                                        small[0] = false;
                                        bindSize[0].run();
                                    }),
                            new LinearLayout.LayoutParams(0, dp(48), 1));
                    sizeOptions.addView(
                            drawerChoice(
                                    "grid",
                                    "小图",
                                    small[0],
                                    false,
                                    () -> {
                                        small[0] = true;
                                        bindSize[0].run();
                                    }),
                            new LinearLayout.LayoutParams(0, dp(48), 1));
                };
        sheet.body.addView(sizeOptions);
        bindSize[0].run();
        SecondaryUi.note(this, sheet.body, "内容");
        LinearLayout typeOptions = Ui.row(this);
        String[] labels = {"全部", "照片", "视频"}, kinds = {"all", "photo", "video"};
        String[] icons = {"grid", "photo", "video"};
        Runnable[] bindTypes = new Runnable[1];
        bindTypes[0] =
                () -> {
                    typeOptions.removeAllViews();
                    for (int i = 0; i < kinds.length; i++) {
                        final int choice = i;
                        typeOptions.addView(
                                drawerChoice(
                                        icons[i],
                                        labels[i],
                                        kind[0].equals(kinds[i]),
                                        false,
                                        () -> {
                                            kind[0] = kinds[choice];
                                            bindTypes[0].run();
                                        }),
                                new LinearLayout.LayoutParams(0, dp(48), 1));
                    }
                };
        sheet.body.addView(typeOptions);
        bindTypes[0].run();
        Switch favorites = SecondaryUi.toggle(this, sheet.body, "只看收藏", favoritesOnly);
        LinearLayout monthLine = Ui.column(this);
        sheet.body.addView(monthLine);
        Runnable[] bindMonth = new Runnable[1];
        bindMonth[0] =
                () -> {
                    monthLine.removeAllViews();
                    monthLine.addView(
                            SecondaryUi.row(
                                    this,
                                    "calendar",
                                    "月份",
                                    month[0].isEmpty() ? "不限" : Ui.month(month[0]),
                                    () -> {
                                        WarmSheet picker = sheet("选择月份");
                                        TextView loading =
                                                SecondaryUi.note(this, picker.body, "正在读取月份…");
                                        picker.show();
                                        async(
                                                () -> client().request("GET", "/api/months", null),
                                                result -> {
                                                    if (epoch != accountEpoch
                                                            || !picker.isShowing()
                                                            || !sheet.isShowing()) return;
                                                    picker.body.removeAllViews();
                                                    picker.body.addView(
                                                            SecondaryUi.row(
                                                                    this,
                                                                    null,
                                                                    "不限月份",
                                                                    "",
                                                                    () -> {
                                                                        month[0] = "";
                                                                        monthChanged[0] = true;
                                                                        bindMonth[0].run();
                                                                        picker.dismiss();
                                                                    }));
                                                    JSONArray months = array(result, "months");
                                                    for (int i = 0; i < months.length(); i++) {
                                                        String value =
                                                                months.getJSONObject(i)
                                                                        .optString("month");
                                                        picker.body.addView(
                                                                SecondaryUi.row(
                                                                        this,
                                                                        null,
                                                                        Ui.month(value),
                                                                        "",
                                                                        () -> {
                                                                            month[0] = value;
                                                                            monthChanged[0] = true;
                                                                            bindMonth[0].run();
                                                                            picker.dismiss();
                                                                        }));
                                                    }
                                                },
                                                problem ->
                                                        loading.setText(
                                                                problem.optString("message")));
                                    }));
                };
        bindMonth[0].run();
        sheet.primary(
                "查看照片",
                () -> {
                    if (epoch != accountEpoch) {
                        sheet.dismiss();
                        return;
                    }
                    boolean filterChanged =
                            !kind[0].equals(filter)
                                    || favorites.isChecked() != favoritesOnly
                                    || !month[0].equals(selectedMonth)
                                    || monthChanged[0];
                    boolean sizeChanged = smallPhotos != small[0];
                    smallPhotos = small[0];
                    filter = kind[0];
                    favoritesOnly = favorites.isChecked();
                    selectedMonth = month[0];
                    if (filterChanged) selectedDate = "";
                    getPreferences(MODE_PRIVATE)
                            .edit()
                            .putBoolean("small_photos", smallPhotos)
                            .apply();
                    if (sizeChanged) savedListPosition = savedListOffset = 0;
                    sheet.dismiss();
                    showAlbum(filterChanged);
                });
        sheet.secondary(
                "重置",
                () -> {
                    small[0] = true;
                    kind[0] = "all";
                    month[0] = "";
                    monthChanged[0] = true;
                    favorites.setChecked(false);
                    bindSize[0].run();
                    bindTypes[0].run();
                    bindMonth[0].run();
                });
        sheet.show();
    }

    private void drawerSection(LinearLayout panel, String title) {
        TextView label = text(title, 14, Ui.MUTED);
        label.setPadding(0, dp(28), 0, dp(13));
        panel.addView(label, new LinearLayout.LayoutParams(-1, -2));
    }

    private View drawerChoice(
            String icon, String title, boolean selected, boolean vertical, Runnable click) {
        LinearLayout choice = vertical ? Ui.column(this) : Ui.row(this);
        choice.setGravity(Gravity.CENTER);
        int color = selected ? Ui.ACCENT_DARK : Ui.INK;
        choice.addView(
                new Ui.Icon(this, icon, color), new LinearLayout.LayoutParams(dp(22), dp(22)));
        TextView label = text(title, 13, color);
        label.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(-2, -2);
        if (vertical) labelParams.topMargin = dp(10);
        else labelParams.leftMargin = dp(8);
        choice.addView(label, labelParams);
        choice.setBackground(Ui.chip(this, selected));
        choice.setContentDescription(title);
        choice.setOnClickListener(v -> click.run());
        choice.setSelected(selected);
        return choice;
    }

    private void drawerRow(LinearLayout panel, String title, Runnable click) {
        LinearLayout row = Ui.row(this);
        TextView label = text(title, 16, Ui.INK);
        row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(
                new Ui.Icon(this, "next", Ui.MUTED), new LinearLayout.LayoutParams(dp(14), dp(14)));
        row.setOnClickListener(v -> click.run());
        panel.addView(row, new LinearLayout.LayoutParams(-1, dp(78)));
    }

    private void changePhotoSize(Dialog drawer, boolean small) {
        drawer.dismiss();
        if (smallPhotos == small) return;
        smallPhotos = small;
        getPreferences(MODE_PRIVATE).edit().putBoolean("small_photos", small).apply();
        savedListPosition = 0;
        savedListOffset = 0;
        showAlbum(false);
    }

    private String albumQuery() {
        String query = "kind=" + filter + "&favorite=" + favoritesOnly;
        if (!selectedMonth.isEmpty()) query += "&month=" + ApiClient.encode(selectedMonth);
        if (!selectedDate.isEmpty()) query += "&date=" + ApiClient.encode(selectedDate);
        return query;
    }

    private void updateAlbumAge(int first) {
        if (albumAgeNavigator == null
                || rows.isEmpty()
                || albumList == null
                || locatingAlbum
                || albumPositionAdjusting) return;
        measureAlbumAgeTrack();
        View firstView = albumList.getChildAt(0);
        if (firstView == null) return;
        int bodyPosition = first - albumList.getHeaderViewsCount();
        int index = Math.max(0, Math.min(rows.size() - 1, bodyPosition));
        AlbumRow row = rows.get(index);
        if (row.date == null) return;
        boolean atStart =
                !albumHasNewer && first == 0 && firstView.getTop() >= albumList.getPaddingTop();
        View lastView = albumList.getChildAt(albumList.getChildCount() - 1);
        boolean atEnd =
                !hasMore
                        && !loading
                        && !loadFailed
                        && lastView != null
                        && albumList.getLastVisiblePosition() >= albumList.getCount() - 1
                        && lastView.getBottom()
                                <= albumList.getHeight() - albumList.getPaddingBottom() + 1;
        float rowFraction =
                bodyPosition < 0
                        ? 0
                        : Math.max(
                                0,
                                Math.min(
                                        1,
                                        (albumList.getPaddingTop() - firstView.getTop())
                                                / (float) Math.max(1, firstView.getHeight())));
        int fullCount = albumAgeNavigator.dayCount(row.date);
        int before = row.mediaBefore;
        // A prepended page may start partway through a day. Once its end is loaded,
        // the complete index count supplies the missing prefix without moving the thumb.
        if (albumHasNewer
                && !media.isEmpty()
                && row.date.equals(media.get(0).optString("album_date"))
                && (!hasMore
                        || !row.date.equals(media.get(media.size() - 1).optString("album_date"))))
            before += Math.max(0, fullCount - row.dayLoadedCount);
        float dayFraction =
                AlbumTimelineProgress.withinDay(
                        before, row.items == null ? 0 : row.items.size(), rowFraction, fullCount);
        albumAgeNavigator.setViewport(row.date, dayFraction, atStart, atEnd);
    }

    private void measureAlbumAgeTrack() {
        if (albumTrackMeasured
                || albumProfileHeader == null
                || albumAgeNavigator == null
                || albumList == null
                || albumList.getWidth() <= 0) return;
        int height = albumProfileHeader.getMeasuredHeight();
        if (height <= 0) {
            albumProfileHeader.measure(
                    View.MeasureSpec.makeMeasureSpec(
                            albumList.getWidth(), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            height = albumProfileHeader.getMeasuredHeight();
        }
        if (height > 0) {
            albumAgeNavigator.setTrackTop(height + dp(24));
            albumTrackMeasured = true;
        }
    }

    private void loadAlbumAgeIndex() {
        final AlbumAgeNavigator navigator = albumAgeNavigator;
        if (navigator == null || trashMode) return;
        final String endpoint = "/api/album-locator?" + albumQuery();
        navigator.setLoading(true);
        async(
                () -> client().request("GET", endpoint, null),
                result -> {
                    if (navigator != albumAgeNavigator || !currentPage.equals("album")) return;
                    navigator.setItems(array(result, "items"));
                    updateAlbumAge(albumList.getFirstVisiblePosition());
                },
                failure -> {
                    if (navigator != albumAgeNavigator || !currentPage.equals("album")) return;
                    navigator.setError(
                            failure.optInt("status") == 404
                                    ? "请先更新相册服务，再使用年龄定位"
                                    : failure.optString("message"));
                });
    }

    private void updateNewerAlbumLink() {
        if (albumNewerLink == null) return;
        albumNewerLink.setVisibility(albumHasNewer ? View.VISIBLE : View.GONE);
        albumNewerLink.setText(loadingNewer ? "正在找更近的照片…" : "向上看更近的照片");
        albumNewerLink.setEnabled(!loadingNewer && !locatingAlbum);
    }

    private static final class AlbumDragSnapshot {
        final ArrayList<JSONObject> items;
        final ListView list;
        final AlbumAgeNavigator navigator;
        final String olderCursor, newerCursor;
        final boolean more, newer, headComplete;
        final int position, offset;

        AlbumDragSnapshot(MainActivity activity) {
            items = new ArrayList<>(activity.media);
            list = activity.albumList;
            navigator = activity.albumAgeNavigator;
            olderCursor = activity.cursor;
            newerCursor = activity.previousAlbumCursor;
            more = activity.hasMore;
            newer = activity.albumHasNewer;
            headComplete = activity.albumWindowHeadComplete;
            position = list == null ? 0 : list.getFirstVisiblePosition();
            View first = list == null ? null : list.getChildAt(0);
            offset = first == null ? 0 : first.getTop();
        }
    }

    private void cancelAlbumDragRead() {
        Runnable cancel = albumDragCancel;
        AlbumDragNavigation.Ticket ticket = albumDragRequest;
        albumDragCancel = null;
        albumDragRequest = null;
        if (cancel != null) cancel.run();
        albumDragNavigation.complete(ticket);
    }

    private void cancelAlbumDrag(boolean restore) {
        boolean active = albumDragNavigation.isActive();
        AlbumDragSnapshot original = albumDragOrigin;
        albumDragOrigin = null;
        albumDragNavigation.cancel();
        handler.removeCallbacks(albumDragPump);
        cancelAlbumDragRead();
        if (!active) return;
        generation++;
        locatingAlbum = false;
        loading = false;
        loadingNewer = false;
        albumPositionAdjusting = false;
        if (restore
                && original != null
                && original.list == albumList
                && original.navigator == albumAgeNavigator
                && currentPage.equals("album")) {
            cancelPageImages(albumList);
            media.clear();
            media.addAll(original.items);
            mediaIds.clear();
            for (JSONObject item : media) mediaIds.add(item.optString("id"));
            cursor = original.olderCursor;
            previousAlbumCursor = original.newerCursor;
            hasMore = original.more;
            albumHasNewer = original.newer;
            albumWindowHeadComplete = original.headComplete;
            albumPositionAdjusting = true;
            rebuildRows();
            albumAdapter.notifyDataSetChanged();
            updateNewerAlbumLink();
            updateFooter();
            albumList.setSelectionFromTop(original.position, original.offset);
            settleAlbumViewport(albumList, albumAgeNavigator, true);
        } else if (albumAgeNavigator != null) {
            updateAlbumAge(albumList == null ? 0 : albumList.getFirstVisiblePosition());
            albumAgeNavigator.finishLocation();
        }
        if (media.isEmpty() && hasMore && currentPage.equals("album") && restore) loadMedia();
    }

    private void beginAlbumDrag() {
        if (trashMode || !currentPage.equals("album") || albumList == null) return;
        cancelAlbumDrag(false);
        albumList.smoothScrollBy(0, 0);
        generation++;
        loading = false;
        loadingNewer = false;
        locatingAlbum = false;
        loadFailed = false;
        albumPositionAdjusting = false;
        albumDragOrigin = new AlbumDragSnapshot(this);
        albumDragNavigation.begin();
        updateNewerAlbumLink();
    }

    private void previewAlbumDrag(String day) {
        albumDragNavigation.preview(day);
        if (albumDragRequest != null && !albumDragNavigation.accepts(albumDragRequest))
            cancelAlbumDragRead();
        pumpAlbumDrag();
    }

    private void finishAlbumDrag(String day) {
        albumDragNavigation.finish(day);
        if (albumDragRequest != null && !albumDragNavigation.accepts(albumDragRequest))
            cancelAlbumDragRead();
        locatingAlbum = true;
        pumpAlbumDrag();
    }

    private void locateAlbumDate(String day) {
        if (trashMode || !currentPage.equals("album")) return;
        beginAlbumDrag();
        finishAlbumDrag(day);
    }

    /** A loaded first day may be a truncated reverse page, rather than its real beginning. */
    private int loadedAlbumDayStart(String day) {
        for (int i = 0; i < rows.size(); i++) {
            AlbumRow row = rows.get(i);
            if (row.type != 0 || !day.equals(row.date)) continue;
            if (i > 0
                    || !albumHasNewer
                    || albumWindowHeadComplete
                    || (albumAgeNavigator != null
                            && albumAgeNavigator.dayCount(day) > 0
                            && row.dayLoadedCount >= albumAgeNavigator.dayCount(day))) return i;
            return -1;
        }
        return -1;
    }

    private void pumpAlbumDrag() {
        handler.removeCallbacks(albumDragPump);
        if (!albumDragNavigation.isActive()
                || !currentPage.equals("album")
                || trashMode
                || albumList == null
                || albumAgeNavigator == null
                || destroyed) return;
        AlbumDragNavigation.Ticket current = albumDragNavigation.current();
        if (current == null) return;
        final ListView target = albumList;
        final AlbumAgeNavigator navigator = albumAgeNavigator;
        final int requested = generation;
        if (albumDragNavigation.displayed(current)) {
            if (!albumDragNavigation.isDragging())
                settleAlbumDragViewport(target, navigator, current, requested);
            return;
        }
        int local = loadedAlbumDayStart(current.date);
        if (local >= 0) {
            cancelAlbumDragRead();
            albumPositionAdjusting = true;
            albumDragNavigation.markDisplayed(current);
            savedListPosition = target.getHeaderViewsCount() + local;
            savedListOffset = dp(8);
            albumNavigationSettleUntil = android.os.SystemClock.elapsedRealtime() + 350;
            target.setSelectionFromTop(savedListPosition, savedListOffset);
            settleAlbumDragViewport(target, navigator, current, requested);
            return;
        }
        long now = android.os.SystemClock.uptimeMillis();
        long wait = albumDragNavigation.delayUntilRequest(now);
        if (wait < 0) return;
        if (wait > 0) {
            handler.postDelayed(albumDragPump, wait);
            return;
        }
        AlbumDragNavigation.Ticket ticket = albumDragNavigation.takeRequest(now);
        if (ticket == null) return;
        cancelAlbumDragRead();
        albumDragRequest = ticket;
        String endpoint =
                "/api/media?limit=60&"
                        + albumQuery()
                        + "&start_date="
                        + ApiClient.encode(ticket.date);
        albumDragCancel =
                requestCancelableGet(
                        endpoint,
                        result -> {
                            if (requested != generation
                                    || target != albumList
                                    || navigator != albumAgeNavigator
                                    || !currentPage.equals("album")
                                    || !albumDragNavigation.accepts(ticket)) return;
                            JSONArray items = array(result, "items");
                            if (items.length() == 0) {
                                failAlbumDrag(ticket, "这一天的照片有变化，请重新选择", target, navigator);
                                return;
                            }
                            albumDragNavigation.complete(ticket);
                            if (albumDragRequest == ticket) {
                                albumDragRequest = null;
                                albumDragCancel = null;
                            }
                            cancelPageImages(target);
                            media.clear();
                            mediaIds.clear();
                            for (int i = 0; i < items.length(); i++) {
                                JSONObject item = items.optJSONObject(i);
                                if (item != null && mediaIds.add(item.optString("id")))
                                    media.add(item);
                            }
                            cursor = str(result, "next_cursor");
                            hasMore = !cursor.isEmpty();
                            previousAlbumCursor = str(result, "previous_cursor");
                            albumHasNewer = result.optBoolean("has_previous");
                            albumWindowHeadComplete = true;
                            albumPositionAdjusting = true;
                            albumDragNavigation.markDisplayed(ticket);
                            rebuildRows();
                            albumAdapter.notifyDataSetChanged();
                            updateNewerAlbumLink();
                            updateFooter();
                            savedListPosition = target.getHeaderViewsCount();
                            savedListOffset = dp(8);
                            albumNavigationSettleUntil =
                                    android.os.SystemClock.elapsedRealtime() + 350;
                            target.setSelectionFromTop(savedListPosition, savedListOffset);
                            settleAlbumDragViewport(target, navigator, ticket, requested);
                        },
                        (status, message) -> {
                            if (requested != generation
                                    || target != albumList
                                    || navigator != albumAgeNavigator
                                    || !currentPage.equals("album")
                                    || !albumDragNavigation.accepts(ticket)) return;
                            failAlbumDrag(ticket, message, target, navigator);
                        });
    }

    private void failAlbumDrag(
            AlbumDragNavigation.Ticket ticket,
            String message,
            ListView target,
            AlbumAgeNavigator navigator) {
        albumDragNavigation.failed(ticket);
        if (albumDragRequest == ticket) {
            albumDragRequest = null;
            albumDragCancel = null;
        }
        albumPositionAdjusting = false;
        if (!albumDragNavigation.isDragging()) {
            locatingAlbum = false;
            updateAlbumAge(target.getFirstVisiblePosition());
            navigator.locationFailed(message);
            updateNewerAlbumLink();
            updateFooter();
        }
    }

    private void settleAlbumDragViewport(
            ListView target,
            AlbumAgeNavigator navigator,
            AlbumDragNavigation.Ticket ticket,
            int requested) {
        target.getViewTreeObserver()
                .addOnPreDrawListener(
                        new ViewTreeObserver.OnPreDrawListener() {
                            @Override
                            public boolean onPreDraw() {
                                ViewTreeObserver observer = target.getViewTreeObserver();
                                if (observer.isAlive()) observer.removeOnPreDrawListener(this);
                                if (requested != generation
                                        || target != albumList
                                        || navigator != albumAgeNavigator
                                        || !currentPage.equals("album")
                                        || !albumDragNavigation.accepts(ticket)) return true;
                                albumPositionAdjusting = false;
                                if (!albumDragNavigation.isDragging()) locatingAlbum = false;
                                updateAlbumAge(target.getFirstVisiblePosition());
                                navigator.finishLocation();
                                if (!albumDragNavigation.isDragging()) {
                                    albumDragOrigin = null;
                                    updateNewerAlbumLink();
                                    updateFooter();
                                    setAlbumToolsVisible(false);
                                }
                                return true;
                            }
                        });
        target.invalidate();
    }

    private void loadNewerAlbumMedia() {
        if (albumDragNavigation.isDragging()
                || loadingNewer
                || loading
                || locatingAlbum
                || !albumHasNewer
                || previousAlbumCursor.isEmpty()) return;
        final int requested = generation;
        final ListView target = albumList;
        final String endpoint =
                "/api/media?limit=60&"
                        + albumQuery()
                        + "&newer_cursor="
                        + ApiClient.encode(previousAlbumCursor);
        loadingNewer = true;
        updateNewerAlbumLink();
        async(
                () -> client().request("GET", endpoint, null),
                result -> {
                    if (requested != generation) return;
                    loadingNewer = false;
                    if (!currentPage.equals("album") || target != albumList) return;
                    int oldPosition =
                            target.getFirstVisiblePosition() - target.getHeaderViewsCount();
                    AlbumRow anchor =
                            rows.isEmpty()
                                    ? null
                                    : rows.get(Math.max(0, Math.min(rows.size() - 1, oldPosition)));
                    String anchorId =
                            anchor != null && anchor.items != null && !anchor.items.isEmpty()
                                    ? anchor.items.get(0).optString("id")
                                    : "";
                    View firstView = target.getChildAt(0);
                    int offset = oldPosition < 0 || firstView == null ? dp(8) : firstView.getTop();
                    JSONArray items = array(result, "items");
                    ArrayList<JSONObject> prepend = new ArrayList<>();
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject item = items.getJSONObject(i);
                        if (mediaIds.add(item.getString("id"))) prepend.add(item);
                    }
                    media.addAll(0, prepend);
                    if (!prepend.isEmpty()) albumWindowHeadComplete = false;
                    previousAlbumCursor = str(result, "previous_cursor");
                    albumHasNewer = result.optBoolean("has_previous");
                    albumPositionAdjusting = true;
                    rebuildRows();
                    albumAdapter.notifyDataSetChanged();
                    updateNewerAlbumLink();
                    int restored = 0;
                    for (int i = 0; anchor != null && i < rows.size(); i++) {
                        AlbumRow row = rows.get(i);
                        if (!anchorId.isEmpty() && row.items != null) {
                            boolean found = false;
                            for (JSONObject item : row.items)
                                if (anchorId.equals(item.optString("id"))) found = true;
                            if (found) {
                                restored = i;
                                break;
                            }
                        } else if (anchorId.isEmpty()
                                && row.type == anchor.type
                                && Objects.equals(row.date, anchor.date)) {
                            restored = i;
                            break;
                        }
                    }
                    albumNavigationSettleUntil = android.os.SystemClock.elapsedRealtime() + 350;
                    target.setSelectionFromTop(target.getHeaderViewsCount() + restored, offset);
                    settleAlbumViewport(target, albumAgeNavigator, false);
                },
                failure -> {
                    if (requested != generation) return;
                    loadingNewer = false;
                    updateNewerAlbumLink();
                    if (target == albumList && currentPage.equals("album")) {
                        if (albumNewerLink != null) albumNewerLink.setText("没有加载成功，点这里重试");
                        toast(failure.optString("message"));
                    }
                });
    }

    private void loadMedia() {
        if (albumDragNavigation.isDragging()
                || loading
                || loadingNewer
                || locatingAlbum
                || !hasMore) return;
        loading = true;
        loadFailed = false;
        int requestGeneration = generation;
        if (loadFooter != null) loadFooter.setText("正在加载…");
        String path = "/api/media?limit=60&" + albumQuery() + "&deleted=" + trashMode;
        if (!cursor.isEmpty()) path += "&cursor=" + ApiClient.encode(cursor);
        String endpoint = path;
        async(
                () -> client().request("GET", endpoint, null),
                result -> {
                    if (requestGeneration != generation) return;
                    loading = false;
                    if (media.isEmpty()) {
                        albumWindowHeadComplete = true;
                        previousAlbumCursor = str(result, "previous_cursor");
                        albumHasNewer = result.optBoolean("has_previous");
                        updateNewerAlbumLink();
                    }
                    JSONArray items = array(result, "items");
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject item = items.getJSONObject(i);
                        if (mediaIds.add(item.getString("id"))) media.add(item);
                    }
                    cursor = str(result, "next_cursor");
                    hasMore = !cursor.isEmpty();
                    if (albumAdapter != null) {
                        rebuildRows();
                        albumAdapter.notifyDataSetChanged();
                    }
                    updateFooter();
                },
                failure -> {
                    if (requestGeneration != generation) return;
                    loading = false;
                    loadFailed = true;
                    if (loadFooter != null)
                        loadFooter.setText(failure.optString("message") + "，点这里重试");
                });
    }

    private void settleAlbumViewport(
            ListView target, AlbumAgeNavigator navigator, boolean finishLocation) {
        final int requested = generation;
        target.getViewTreeObserver()
                .addOnPreDrawListener(
                        new ViewTreeObserver.OnPreDrawListener() {
                            @Override
                            public boolean onPreDraw() {
                                ViewTreeObserver observer = target.getViewTreeObserver();
                                if (observer.isAlive()) observer.removeOnPreDrawListener(this);
                                if (requested != generation
                                        || target != albumList
                                        || navigator != albumAgeNavigator
                                        || !currentPage.equals("album")) return true;
                                albumPositionAdjusting = false;
                                updateAlbumAge(target.getFirstVisiblePosition());
                                if (finishLocation && navigator != null) navigator.finishLocation();
                                return true;
                            }
                        });
        target.invalidate();
    }

    private void updateFooter() {
        if (loadFooter != null)
            loadFooter.setText(
                    media.isEmpty()
                            ? (trashMode ? "回收站是空的" : "这里还没有照片和视频")
                            : hasMore ? "继续加载" : "已经看完了");
    }

    private final ArrayList<AlbumRow> rows = new ArrayList<>();

    private static final class AlbumRow {
        String date, uploader;
        ArrayList<JSONObject> items;
        int type, mediaBefore, dayLoadedCount;

        AlbumRow(int type) {
            this.type = type;
        }
    }

    private void rebuildRows() {
        rows.clear();
        displayOrder.clear();
        if (trashMode) {
            displayOrder.addAll(media);
            for (int start = 0; start < media.size(); start += 3) {
                AlbumRow row = new AlbumRow(1);
                row.items =
                        new ArrayList<>(media.subList(start, Math.min(start + 3, media.size())));
                rows.add(row);
            }
            return;
        }
        LinkedHashMap<String, ArrayList<JSONObject>> groups = new LinkedHashMap<>();
        for (JSONObject item : media) {
            String day = item.optString("album_date", Ui.day(item.optString("captured_at")));
            if (!groups.containsKey(day)) groups.put(day, new ArrayList<>());
            groups.get(day).add(item);
        }
        for (Map.Entry<String, ArrayList<JSONObject>> group : groups.entrySet()) {
            ArrayList<JSONObject> items = group.getValue();
            Collections.sort(
                    items,
                    (a, b) -> a.optString("captured_at").compareTo(b.optString("captured_at")));
            AlbumRow heading = new AlbumRow(0);
            heading.date = group.getKey();
            heading.dayLoadedCount = items.size();
            rows.add(heading);
            displayOrder.addAll(items);
            int columns = smallPhotos ? 3 : 1;
            for (int start = 0; start < items.size(); start += columns) {
                AlbumRow row = new AlbumRow(1);
                row.date = group.getKey();
                row.mediaBefore = start;
                row.dayLoadedCount = items.size();
                row.items =
                        new ArrayList<>(
                                items.subList(start, Math.min(start + columns, items.size())));
                rows.add(row);
            }
            LinkedHashSet<String> names = new LinkedHashSet<>();
            for (JSONObject item : items) {
                String name = item.optString("uploader_name", "");
                if (!name.isEmpty()) names.add(name);
            }
            AlbumRow uploader = new AlbumRow(2);
            uploader.date = group.getKey();
            uploader.mediaBefore = items.size();
            uploader.dayLoadedCount = items.size();
            uploader.uploader = String.join("、", names) + (names.isEmpty() ? "" : "上传");
            rows.add(uploader);
        }
    }

    private final class AlbumAdapter extends BaseAdapter {
        public int getCount() {
            return rows.size();
        }

        public Object getItem(int position) {
            return rows.get(position);
        }

        public long getItemId(int position) {
            return position;
        }

        public int getViewTypeCount() {
            return 3;
        }

        public int getItemViewType(int position) {
            return rows.get(position).type;
        }

        public boolean isEnabled(int position) {
            return false;
        }

        public View getView(int position, View convert, android.view.ViewGroup parent) {
            if (convert != null) cancelPageImages(convert);
            AlbumRow row = rows.get(position);
            if (row.type == 0) {
                LinearLayout heading = Ui.row(MainActivity.this);
                heading.setPadding(dp(16), dp(10), dp(16), dp(8));
                heading.setMinimumHeight(dp(48));
                heading.setLayoutParams(new AbsListView.LayoutParams(-1, -2));
                View mark = new View(MainActivity.this);
                mark.setBackground(Ui.round(Ui.ACCENT, 3, MainActivity.this));
                heading.addView(mark, new LinearLayout.LayoutParams(dp(5), dp(5)));
                String ageLabel = Ui.age(profile.optString("birthday"), row.date);
                TextView age = text(ageLabel.isEmpty() ? Ui.date(row.date) : ageLabel, 18, Ui.INK);
                age.setTypeface(null, Typeface.BOLD);
                age.setPadding(dp(8), 0, dp(8), 0);
                age.setMaxLines(1);
                age.setEllipsize(android.text.TextUtils.TruncateAt.END);
                TextView date = text(ageLabel.isEmpty() ? "" : Ui.date(row.date), 11, Ui.MUTED);
                date.setMaxLines(1);
                date.setEllipsize(android.text.TextUtils.TruncateAt.END);
                if (getResources().getConfiguration().screenWidthDp < 360
                        || getResources().getConfiguration().fontScale > 1.15f) {
                    LinearLayout labels = Ui.column(MainActivity.this);
                    labels.addView(age, new LinearLayout.LayoutParams(-1, -2));
                    date.setPadding(dp(8), dp(3), dp(8), 0);
                    labels.addView(date, new LinearLayout.LayoutParams(-1, -2));
                    heading.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
                } else {
                    heading.addView(age, new LinearLayout.LayoutParams(-2, -2));
                    heading.addView(date, new LinearLayout.LayoutParams(0, -2, 1));
                }
                heading.addView(
                        new Ui.Icon(MainActivity.this, "next", Ui.MUTED),
                        new LinearLayout.LayoutParams(dp(14), dp(14)));
                heading.setOnClickListener(
                        v -> {
                            selectedDate = row.date;
                            selectedMonth = "";
                            showAlbum(true);
                        });
                return heading;
            }
            if (row.type == 2) {
                TextView label = text(row.uploader, 12, Ui.MUTED);
                label.setPadding(dp(16), dp(5), dp(16), dp(9));
                return label;
            }
            LinearLayout photoRow = Ui.row(MainActivity.this);
            photoRow.setPadding(dp(16), 0, dp(16), dp(3));
            int columns = trashMode || smallPhotos ? 3 : 1;
            int side =
                    (getResources().getDisplayMetrics().widthPixels
                                    - dp(32)
                                    - dp(3 * (columns - 1)))
                            / columns;
            for (int column = 0; column < columns; column++) {
                FrameLayout cell = new FrameLayout(MainActivity.this);
                LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, side, 1);
                if (column < columns - 1) cp.rightMargin = dp(3);
                photoRow.addView(cell, cp);
                if (column >= row.items.size()) continue;
                JSONObject item = row.items.get(column);
                cell.setBackground(Ui.round(Ui.SOFT, smallPhotos ? 8 : 12, MainActivity.this));
                cell.setClipToOutline(true);
                ImageView photo = new TopCropImageView(MainActivity.this);
                cell.addView(photo, new FrameLayout.LayoutParams(-1, -1));
                images.load(photo, str(item, "thumbnail_url"), smallPhotos ? 500 : 1000);
                if (item.optString("kind").equals("video")) {
                    TextView duration =
                            text(Ui.duration(item.optDouble("duration", 0)), 11, Color.WHITE);
                    duration.setPadding(dp(6), dp(2), dp(6), dp(2));
                    duration.setBackground(Ui.round(0xA6302522, 6, MainActivity.this));
                    FrameLayout.LayoutParams lp =
                            new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.RIGHT);
                    lp.setMargins(0, 0, dp(6), dp(6));
                    cell.addView(duration, lp);
                }
                if (item.optBoolean("favorite")) {
                    Ui.Icon star = new Ui.Icon(MainActivity.this, "heart", Ui.ON_DARK);
                    star.setBackground(Ui.round(0xA6302522, 8, MainActivity.this));
                    star.setPadding(dp(3), dp(3), dp(3), dp(3));
                    FrameLayout.LayoutParams lp =
                            new FrameLayout.LayoutParams(
                                    dp(22), dp(22), Gravity.TOP | Gravity.RIGHT);
                    lp.setMargins(0, dp(6), dp(6), 0);
                    cell.addView(star, lp);
                }
                cell.setContentDescription(
                        Ui.date(item.optString("album_date"))
                                + (item.optString("kind").equals("video") ? "的视频" : "的照片"));
                cell.setOnClickListener(
                        v -> {
                            if (trashMode && item.optBoolean("can_restore", false)) restore(item);
                            else openDetail(item);
                        });
                cell.setOnLongClickListener(
                        v -> {
                            WarmSheet actions = sheet("更多操作");
                            actions.body.addView(
                                    SecondaryUi.row(
                                            MainActivity.this,
                                            "photo",
                                            "查看详情",
                                            "",
                                            () -> {
                                                actions.dismiss();
                                                openDetail(item);
                                            }));
                            actions.body.addView(
                                    SecondaryUi.row(
                                            MainActivity.this,
                                            "download",
                                            "保存到手机",
                                            "",
                                            () -> {
                                                actions.dismiss();
                                                saveMedia(item);
                                            }));
                            actions.show();
                            return true;
                        });
            }
            return photoRow;
        }
    }

    private final class TimelineListView extends ListView {
        private float downY;

        TimelineListView(boolean hasHeader) {
            super(MainActivity.this);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) downY = event.getY();
            if (event.getActionMasked() == MotionEvent.ACTION_MOVE
                    && event.getPointerCount() == 1
                    && event.getY() - downY > dp(14)
                    && this == albumList
                    && !trashMode
                    && currentPage.equals("album")
                    && getFirstVisiblePosition() <= getHeaderViewsCount() + 1
                    && android.os.SystemClock.elapsedRealtime() > albumNavigationSettleUntil)
                loadNewerAlbumMedia();
            return super.onTouchEvent(event);
        }
    }

    private void openDetail(JSONObject item) {
        detailFeatureScope = false;
        featureMediaBack = null;
        boolean fromTimeCollection =
                currentPage.equals("time_collection") && activeTimeCollection != null;
        rememberBrowsingPosition();
        detailTimeCollectionKey = fromTimeCollection ? activeTimeCollection.key : "";
        detailReturnPage =
                fromTimeCollection
                        ? "time_collection"
                        : currentPage.equals("activity")
                                ? "activity"
                                : currentPage.equals("time") ? "time" : "album";
        boolean fromAlbum = detailReturnPage.equals("album");
        if (albumList != null && fromAlbum) {
            savedListPosition = albumList.getFirstVisiblePosition();
            View first = albumList.getChildAt(0);
            savedListOffset = first == null ? 0 : first.getTop();
        }
        detailKind =
                fromTimeCollection
                        ? activeTimeCollection.kind
                        : fromAlbum
                                ? filter
                                : detailReturnPage.equals("time")
                                                && item.optString("kind").equals("video")
                                        ? "video"
                                        : "all";
        detailMonth =
                fromTimeCollection ? activeTimeCollection.month : fromAlbum ? selectedMonth : "";
        detailDay = fromAlbum ? selectedDate : "";
        detailFavorites =
                fromTimeCollection ? activeTimeCollection.favorites : fromAlbum && favoritesOnly;
        detailDeleted = fromAlbum && trashMode;
        detailLoadGeneration++;
        detailWindowReady = false;
        detailLoadingInitial = detailLoadingBefore = detailLoadingAfter = false;
        detailHasBefore = detailHasAfter = true;
        detailWindowBase = detailTotal = detailPendingDirection = 0;
        detailPendingAnchor = "";
        detailItems.clear();
        List<JSONObject> source = fromTimeCollection ? activeTimeCollection.items() : displayOrder;
        int selected =
                fromAlbum || fromTimeCollection ? indexOfMedia(source, item.optString("id")) : -1;
        if (selected >= 0) {
            int start = Math.max(0, selected - 10), end = Math.min(source.size(), selected + 11);
            detailItems.addAll(source.subList(start, end));
            detailIndex = selected - start;
        } else {
            detailItems.add(item);
            detailIndex = 0;
        }
        showDetail();
        requestDetailWindow(item.optString("id"), 0);
    }

    private int indexOfMedia(List<JSONObject> items, String id) {
        for (int i = 0; i < items.size(); i++)
            if (items.get(i).optString("id").equals(id)) return i;
        return -1;
    }

    private long mediaCaptureTime(JSONObject item) {
        String value = item.optString("captured_at");
        try {
            return OffsetDateTime.parse(value).toInstant().toEpochMilli();
        } catch (Exception ignored) {
            try {
                return LocalDateTime.parse(value)
                        .atZone(ZoneId.of("Asia/Shanghai"))
                        .toInstant()
                        .toEpochMilli();
            } catch (Exception invalid) {
                return 0;
            }
        }
    }

    private double mediaSortTime(JSONObject item) {
        double precise = item.optDouble("sort_time", Double.NaN);
        return Double.isNaN(precise) || Double.isInfinite(precise)
                ? mediaCaptureTime(item) / 1000d
                : precise;
    }

    private String detailWindowPath(String anchor, int direction) {
        String path =
                "/api/media-window?anchor="
                        + ApiClient.encode(anchor)
                        + "&kind="
                        + ApiClient.encode(detailKind)
                        + "&favorite="
                        + detailFavorites
                        + "&deleted="
                        + detailDeleted
                        + "&before="
                        + (direction < 0 ? 24 : direction == 0 ? 16 : 0)
                        + "&after="
                        + (direction > 0 ? 24 : direction == 0 ? 16 : 0);
        if (!detailMonth.isEmpty()) path += "&month=" + ApiClient.encode(detailMonth);
        if (!detailDay.isEmpty()) path += "&date=" + ApiClient.encode(detailDay);
        return path;
    }

    private void finishWindowLoading(int direction) {
        if (direction < 0) detailLoadingBefore = false;
        else if (direction > 0) detailLoadingAfter = false;
        else detailLoadingInitial = false;
    }

    private void requestDetailWindow(String anchor, int direction) {
        if (detailFeatureScope) return;
        if (!currentPage.equals("detail") || anchor.isEmpty()) return;
        if (direction < 0) {
            if (detailLoadingBefore) return;
            detailLoadingBefore = true;
        } else if (direction > 0) {
            if (detailLoadingAfter) return;
            detailLoadingAfter = true;
        } else {
            if (detailLoadingInitial) return;
            detailLoadingInitial = true;
        }
        final int request = detailLoadGeneration;
        final String path = detailWindowPath(anchor, direction);
        final ApiClient client = api.snapshot();
        async(
                () -> client.request("GET", path, null),
                result -> {
                    if (request != detailLoadGeneration
                            || !currentPage.equals("detail")
                            || detailItems.isEmpty()) return;
                    finishWindowLoading(direction);
                    if (direction != 0 && indexOfMedia(detailItems, anchor) < 0) {
                        prefetchDetailWindow();
                        return;
                    }
                    String currentId = currentItem().optString("id");
                    JSONArray incoming = array(result, "items");
                    if (incoming.length() == 0) return;
                    // Adjacent windows overlap at their anchor. Keep identity stable while merging
                    // metadata.
                    LinkedHashMap<String, JSONObject> byId = new LinkedHashMap<>();
                    if (direction != 0)
                        for (JSONObject value : detailItems) byId.put(value.optString("id"), value);
                    for (int i = 0; i < incoming.length(); i++) {
                        JSONObject value = incoming.getJSONObject(i);
                        byId.put(value.optString("id"), value);
                    }
                    if (!byId.containsKey(currentId))
                        for (JSONObject value : detailItems)
                            byId.putIfAbsent(value.optString("id"), value);
                    ArrayList<JSONObject> merged = new ArrayList<>(byId.values());
                    Collections.sort(
                            merged,
                            (a, b) -> {
                                int day =
                                        b.optString("album_date")
                                                .compareTo(a.optString("album_date"));
                                if (day != 0) return day;
                                int time = Double.compare(mediaSortTime(a), mediaSortTime(b));
                                return time != 0
                                        ? time
                                        : a.optString("id").compareTo(b.optString("id"));
                            });
                    int selected = indexOfMedia(merged, currentId);
                    if (selected < 0) return;
                    int firstIncoming =
                            indexOfMedia(merged, incoming.getJSONObject(0).optString("id"));
                    detailWindowBase =
                            Math.max(
                                    0,
                                    result.optInt("position")
                                            - result.optInt("index")
                                            - firstIncoming);
                    detailTotal = result.optInt("total", merged.size());
                    if (direction <= 0) detailHasBefore = result.optBoolean("has_before");
                    if (direction >= 0) detailHasAfter = result.optBoolean("has_after");
                    detailItems.clear();
                    detailItems.addAll(merged);
                    detailIndex = selected;
                    // A sliding metadata window keeps even a very large family library inexpensive.
                    if (detailIndex > 48) {
                        int remove = detailIndex - 24;
                        detailItems.subList(0, remove).clear();
                        detailIndex -= remove;
                        detailWindowBase += remove;
                        detailHasBefore = true;
                    }
                    if (detailItems.size() > detailIndex + 65) {
                        detailItems.subList(detailIndex + 41, detailItems.size()).clear();
                        detailHasAfter = true;
                    }
                    detailWindowReady = true;
                    updateDetailCount();
                    refreshDetailNeighbors();
                    hideDetailNotice();
                    int pending = detailPendingDirection;
                    boolean stillWaiting = currentId.equals(detailPendingAnchor);
                    detailPendingDirection = 0;
                    detailPendingAnchor = "";
                    if (pending != 0 && stillWaiting) moveDetail(pending);
                    else prefetchDetailWindow();
                },
                error -> {
                    if (request != detailLoadGeneration || !currentPage.equals("detail")) return;
                    finishWindowLoading(direction);
                    if (direction != 0 && indexOfMedia(detailItems, anchor) < 0) {
                        prefetchDetailWindow();
                        return;
                    }
                    detailPendingDirection = 0;
                    int status = error.optInt("status");
                    if (status == 401) {
                        clearSession(false);
                        toast("登录已过期，请重新登录");
                    } else if (status == 404
                            && !detailItems.isEmpty()
                            && !currentItem().optString("id").equals(anchor)) {
                        // Another family member may remove an off-screen anchor. Rebuild around
                        // the visible item once; a missing visible anchor then exits normally.
                        reloadDetailWindowAroundCurrent();
                    } else if (status == 403 || status == 404) {
                        closeDetail();
                        toast("这份内容已移走，或当前账号无权查看");
                    } else {
                        showDetailNotice(
                                "暂时没能读取相邻内容，点此重试",
                                () -> {
                                    if (detailItems.isEmpty()) return;
                                    String retryAnchor =
                                            direction == 0
                                                    ? currentItem().optString("id")
                                                    : detailItems
                                                            .get(
                                                                    direction < 0
                                                                            ? 0
                                                                            : detailItems.size()
                                                                                    - 1)
                                                            .optString("id");
                                    showDetailNotice("正在读取相邻内容…", null);
                                    requestDetailWindow(retryAnchor, direction);
                                });
                    }
                });
    }

    private void reloadDetailWindowAroundCurrent() {
        if (!currentPage.equals("detail") || detailItems.isEmpty()) return;
        if (detailFeatureScope) {
            detailWindowReady = true;
            detailTotal = detailItems.size();
            updateDetailCount();
            refreshDetailNeighbors();
            return;
        }
        JSONObject visible = currentItem();
        detailLoadGeneration++;
        detailLoadingInitial = detailLoadingBefore = detailLoadingAfter = false;
        detailWindowReady = false;
        detailHasBefore = detailHasAfter = true;
        detailWindowBase = detailTotal = detailPendingDirection = 0;
        detailPendingAnchor = "";
        detailItems.clear();
        detailItems.add(visible);
        detailIndex = 0;
        updateDetailCount();
        refreshDetailNeighbors();
        requestDetailWindow(visible.optString("id"), 0);
    }

    private void prefetchDetailWindow() {
        if (detailFeatureScope) return;
        if (!detailWindowReady || detailItems.isEmpty()) return;
        if (detailIndex <= 3 && detailHasBefore)
            requestDetailWindow(detailItems.get(0).optString("id"), -1);
        if (detailItems.size() - detailIndex <= 4 && detailHasAfter)
            requestDetailWindow(detailItems.get(detailItems.size() - 1).optString("id"), 1);
    }

    private void closeDetail() {
        detailLoadGeneration++;
        detailPendingDirection = 0;
        if (detailFeatureScope && featureMediaBack != null) {
            Runnable back = featureMediaBack;
            detailFeatureScope = false;
            featureMediaBack = null;
            back.run();
            return;
        }
        if (detailReturnPage.equals("time_collection")) {
            TimeCollectionPage page = timeCollections.get(detailTimeCollectionKey);
            if (page != null) showTimeCollection(page);
            else showTime();
        } else if (detailReturnPage.equals("activity")) showActivity();
        else if (detailReturnPage.equals("time")) showTime();
        else showAlbum(false);
    }

    private JSONObject currentItem() {
        return detailItems.get(detailIndex);
    }

    private boolean isCurrentDetail(JSONObject item) {
        return currentPage.equals("detail")
                && !detailItems.isEmpty()
                && currentItem().optString("id").equals(item.optString("id"));
    }

    private android.graphics.drawable.GradientDrawable viewerGradient(boolean top) {
        return new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                top
                        ? new int[] {0xD9000000, 0x80000000, Color.TRANSPARENT}
                        : new int[] {Color.TRANSPARENT, 0xAA000000, 0xE6000000});
    }

    private void showDetail() {
        if (detailItems.isEmpty()) {
            showAlbum(false);
            return;
        }
        currentPage = "detail";
        screen(false);
        detailChromeVisible = true;
        detailInsetTop = detailInsetBottom = 0;
        detailMedia = new FrameLayout(this);
        detailMedia.setBackgroundColor(VIEWER_BACKGROUND);
        content.addView(detailMedia, new FrameLayout.LayoutParams(-1, -1));
        detailPager = new MediaPagerView(this);
        detailMedia.addView(detailPager, new FrameLayout.LayoutParams(-1, -1));
        detailPager.setListener(
                new MediaPagerView.Listener() {
                    @Override
                    public void onPageSelected(int direction) {
                        moveDetail(direction);
                    }

                    @Override
                    public void onBoundary(int direction) {
                        moveDetail(direction);
                    }

                    @Override
                    public void onDismiss() {
                        closeDetail();
                    }
                });

        detailTop = Ui.column(this);
        detailTop.setBackground(viewerGradient(true));
        LinearLayout bar = Ui.row(this);
        View back = iconButton("back", VIEWER_TEXT, this::closeDetail);
        back.setContentDescription(
                detailFeatureScope
                        ? (activeMemoryFeature.equals("activity") ? "返回家人动态" : "返回回忆页面")
                        : detailReturnPage.equals("time_collection") && activeTimeCollection != null
                                ? "返回" + activeTimeCollection.title
                                : detailReturnPage.equals("time") ? "返回成长时光" : "返回相册");
        bar.addView(back, new LinearLayout.LayoutParams(dp(48), dp(52)));
        LinearLayout dates = Ui.column(this);
        dates.setGravity(Gravity.CENTER);
        detailAge = text("", 15, VIEWER_TEXT);
        detailAge.setTypeface(null, Typeface.BOLD);
        detailAge.setGravity(Gravity.CENTER);
        detailDate = text("", 11, 0xFFD8D1CD);
        detailDate.setGravity(Gravity.CENTER);
        detailDate.setPadding(0, dp(3), 0, 0);
        dates.addView(detailAge);
        dates.addView(detailDate);
        dates.setContentDescription("照片信息");
        dates.setOnClickListener(
                v -> {
                    pauseForDetailDialog();
                    showInfo(currentItem());
                });
        bar.addView(dates, new LinearLayout.LayoutParams(0, dp(52), 1));
        detailCount = text("", 11, VIEWER_TEXT);
        detailCount.setGravity(Gravity.CENTER);
        detailCount.setPadding(dp(8), dp(4), dp(8), dp(4));
        detailCount.setBackground(Ui.round(0x26FFFFFF, 8, this));
        bar.addView(detailCount, new LinearLayout.LayoutParams(-2, -2));
        View more =
                iconButton(
                        "more",
                        VIEWER_TEXT,
                        () -> {
                            pauseForDetailDialog();
                            showMediaMenu();
                        });
        more.setContentDescription("更多");
        bar.addView(more, new LinearLayout.LayoutParams(dp(46), dp(52)));
        detailTop.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        detailMedia.addView(detailTop, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        detailSheet = Ui.column(this);
        detailSheet.setBackground(viewerGradient(false));
        detailHint = text("左右滑动浏览 · 点一下收起操作", 12, 0xFFECD9CA);
        detailHint.setGravity(Gravity.CENTER);
        detailHint.setPadding(dp(8), dp(7), dp(8), dp(7));
        detailHint.setVisibility(View.GONE);
        detailSheet.addView(detailHint, new LinearLayout.LayoutParams(-1, -2));
        detailNotice = text("", 12, VIEWER_TEXT);
        detailNotice.setGravity(Gravity.CENTER);
        detailNotice.setPadding(dp(12), dp(10), dp(12), dp(10));
        detailNotice.setBackground(Ui.ripple(0xCC3B3029, 12, this));
        detailNotice.setVisibility(View.GONE);
        detailSheet.addView(detailNotice, new LinearLayout.LayoutParams(-1, -2));
        detailStory = Ui.row(this);
        detailStory.setPadding(dp(4), dp(12), dp(4), dp(12));
        detailDescription = text("", 14, VIEWER_TEXT);
        detailDescription.setMaxLines(2);
        detailDescription.setEllipsize(android.text.TextUtils.TruncateAt.END);
        detailDescription.setLineSpacing(dp(2), 1);
        detailStory.addView(detailDescription, new LinearLayout.LayoutParams(0, -2, 1));
        detailEditIcon = new Ui.Icon(this, "edit", 0xFFE9B18F);
        LinearLayout.LayoutParams editParams = new LinearLayout.LayoutParams(dp(17), dp(17));
        editParams.leftMargin = dp(10);
        detailStory.addView(detailEditIcon, editParams);
        detailStory.setContentDescription("这一刻的故事");
        detailStory.setOnClickListener(
                v -> {
                    pauseForDetailDialog();
                    if (!detailFeatureScope && currentItem().optBoolean("can_edit"))
                        editDescription(currentItem());
                    else showInfo(currentItem());
                });
        detailSheet.addView(detailStory);
        detailUploader = text("", 11, 0xFFCBC3BD);
        detailUploader.setVisibility(View.GONE);
        detailSheet.addView(detailUploader);

        detailVideoControls = Ui.row(this);
        detailVideoControls.setPadding(0, dp(4), 0, dp(7));
        detailPlay = new PlaybackButton();
        detailPlay.setOnClickListener(v -> toggleVideoPlayback());
        detailVideoControls.addView(detailPlay, new LinearLayout.LayoutParams(dp(44), dp(44)));
        detailSeek = new SeekBar(this);
        detailSeek.setMax(1000);
        detailSeek.setContentDescription("视频播放进度");
        detailSeek.setProgressTintList(android.content.res.ColorStateList.valueOf(0xFFE9B18F));
        detailSeek.setThumbTintList(android.content.res.ColorStateList.valueOf(0xFFE9B18F));
        detailSeek.setProgressBackgroundTintList(
                android.content.res.ColorStateList.valueOf(0x66FFFFFF));
        detailVideoControls.addView(detailSeek, new LinearLayout.LayoutParams(0, dp(44), 1));
        detailVideoTime = text("", 11, VIEWER_TEXT);
        detailVideoControls.addView(detailVideoTime, new LinearLayout.LayoutParams(-2, -2));
        detailSeek.setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onStartTrackingTouch(SeekBar bar) {
                        detailSeeking = true;
                        handler.removeCallbacks(detailAutoHide);
                        if (detailPager != null) detailPager.setPagingEnabled(false);
                    }

                    @Override
                    public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                        if (fromUser && video != null && videoPrepared) {
                            int duration = video.getDuration();
                            detailVideoTime.setText(
                                    videoTime((int) (duration * (progress / 1000d)))
                                            + " / "
                                            + videoTime(duration));
                        }
                    }

                    @Override
                    public void onStopTrackingTouch(SeekBar bar) {
                        if (video != null && videoPrepared)
                            seekDetailVideo(
                                    (int) (video.getDuration() * (bar.getProgress() / 1000d)));
                        detailSeeking = false;
                        if (detailPager != null) detailPager.setPagingEnabled(true);
                        scheduleDetailAutoHide();
                    }
                });
        detailSheet.addView(detailVideoControls, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout controls = Ui.row(this);
        LinearLayout favorite = Ui.row(this);
        favorite.setGravity(Gravity.CENTER);
        favorite.setPadding(dp(12), 0, dp(12), 0);
        favorite.setBackground(Ui.ripple(0x26FFFFFF, 22, this));
        detailFavoriteIcon = new Ui.Icon(this, "heart", VIEWER_TEXT);
        favorite.addView(detailFavoriteIcon, new LinearLayout.LayoutParams(dp(22), dp(22)));
        TextView favoriteLabel = text("收藏", 12, VIEWER_TEXT);
        favoriteLabel.setPadding(dp(6), 0, 0, 0);
        favorite.addView(favoriteLabel);
        favorite.setOnClickListener(v -> toggleFavorite(currentItem()));
        detailFavoriteButton = favorite;
        controls.addView(favorite, new LinearLayout.LayoutParams(-2, dp(44)));
        LinearLayout comments = Ui.row(this);
        comments.setGravity(Gravity.CENTER);
        comments.setPadding(dp(12), 0, dp(12), 0);
        comments.setBackground(Ui.ripple(0x26FFFFFF, 22, this));
        comments.addView(
                new Ui.Icon(this, "comment", VIEWER_TEXT),
                new LinearLayout.LayoutParams(dp(22), dp(22)));
        detailComments = text("评论", 12, VIEWER_TEXT);
        detailComments.setPadding(dp(6), 0, 0, 0);
        comments.addView(detailComments);
        comments.setContentDescription("评论");
        comments.setOnClickListener(
                v -> {
                    pauseForDetailDialog();
                    showComments(currentItem());
                });
        LinearLayout.LayoutParams commentsParams = new LinearLayout.LayoutParams(-2, dp(44));
        commentsParams.leftMargin = dp(8);
        controls.addView(comments, commentsParams);
        controls.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        LinearLayout save = Ui.row(this);
        save.setGravity(Gravity.CENTER);
        save.setPadding(dp(13), 0, dp(13), 0);
        save.setBackground(Ui.ripple(0x593D3028, 22, this));
        save.addView(
                new Ui.Icon(this, "download", 0xFFE9B18F),
                new LinearLayout.LayoutParams(dp(21), dp(21)));
        detailSaveLabel = text("保存", 12, VIEWER_TEXT);
        detailSaveLabel.setPadding(dp(6), 0, 0, 0);
        save.addView(detailSaveLabel);
        save.setContentDescription("保存到手机");
        save.setOnClickListener(v -> saveMedia(currentItem()));
        controls.addView(save, new LinearLayout.LayoutParams(-2, dp(44)));
        detailSheet.addView(controls, new LinearLayout.LayoutParams(-1, -2));
        detailMedia.addView(detailSheet, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        detailMedia.setOnApplyWindowInsetsListener(
                (v, insets) -> {
                    detailInsetTop = insets.getStableInsetTop();
                    detailInsetBottom = insets.getStableInsetBottom();
                    updateDetailLayout();
                    return insets;
                });
        detailMedia.requestApplyInsets();
        updateDetailLayout();
        bindDetail();
        if (!getPreferences(MODE_PRIVATE).getBoolean("immersive_hint_seen", false)) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("immersive_hint_seen", true).apply();
            final TextView hint = detailHint;
            hint.setVisibility(View.VISIBLE);
            handler.postDelayed(
                    () -> {
                        if (detailHint == hint) hint.setVisibility(View.GONE);
                    },
                    4500);
        }
    }

    private void updateDetailLayout() {
        if (detailTop == null || detailSheet == null || detailPager == null) return;
        boolean landscape =
                getResources().getConfiguration().orientation
                        == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        detailTop.setPadding(dp(4), detailInsetTop + dp(3), dp(6), dp(18));
        detailSheet.setPadding(dp(16), dp(28), dp(16), detailInsetBottom + dp(landscape ? 8 : 16));
        detailStory.setVisibility(
                !landscape && detailDescription.length() > 0 ? View.VISIBLE : View.GONE);
        detailAge.setVisibility(landscape ? View.GONE : View.VISIBLE);
        applyDetailSystemBars();
    }

    private void applyDetailSystemBars() {
        if (!currentPage.equals("detail")) return;
        int flags =
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        if (!detailChromeVisible)
            flags |=
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        if (getWindow().getDecorView().getSystemUiVisibility() != flags)
            getWindow().getDecorView().setSystemUiVisibility(flags);
    }

    private void setDetailChrome(boolean visible, boolean animate) {
        if (!currentPage.equals("detail") || detailTop == null || detailSheet == null) return;
        detailChromeVisible = visible;
        handler.removeCallbacks(detailAutoHide);
        for (View overlay : new View[] {detailTop, detailSheet}) {
            overlay.animate().cancel();
            overlay.setImportantForAccessibility(
                    visible
                            ? View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                            : View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            if (visible) {
                overlay.setVisibility(View.VISIBLE);
                if (animate) overlay.animate().alpha(1).setDuration(160).start();
                else overlay.setAlpha(1);
            } else if (animate) {
                overlay.animate()
                        .alpha(0)
                        .setDuration(160)
                        .withEndAction(
                                () -> {
                                    if (!detailChromeVisible) overlay.setVisibility(View.GONE);
                                })
                        .start();
            } else {
                overlay.setAlpha(0);
                overlay.setVisibility(View.GONE);
            }
        }
        applyDetailSystemBars();
        if (visible) scheduleDetailAutoHide();
    }

    private void toggleDetailChrome() {
        setDetailChrome(!detailChromeVisible, true);
    }

    private void scheduleDetailAutoHide() {
        handler.removeCallbacks(detailAutoHide);
        if (currentPage.equals("detail")
                && video != null
                && videoPrepared
                && video.isPlaying()
                && !detailSeeking) handler.postDelayed(detailAutoHide, 3500);
    }

    private void pauseForDetailDialog() {
        handler.removeCallbacks(detailAutoHide);
        if (video != null && videoPrepared && video.isPlaying()) video.pause();
        videoWantsPlay = false;
        updateVideoControls();
    }

    private void updateDetailCount() {
        if (detailCount == null) return;
        detailCount.setText(
                detailWindowReady
                        ? (detailWindowBase + detailIndex + 1) + " / " + detailTotal
                        : "浏览中");
        detailCount.setContentDescription(
                detailWindowReady
                        ? "第 " + (detailWindowBase + detailIndex + 1) + " 项，共 " + detailTotal + " 项"
                        : "正在读取相册");
    }

    private void showDetailNotice(String message, Runnable retry) {
        if (detailNotice == null || !currentPage.equals("detail")) return;
        detailNotice.setText(message);
        detailNotice.setOnClickListener(retry == null ? null : v -> retry.run());
        detailNotice.setVisibility(View.VISIBLE);
        setDetailChrome(true, true);
    }

    private void hideDetailNotice() {
        if (detailNotice != null) detailNotice.setVisibility(View.GONE);
    }

    private void updateDetailActions(JSONObject item) {
        boolean favorite = item.optBoolean("favorite");
        boolean deleted = !str(item, "deleted_at").isEmpty();
        boolean canFavorite = permitted("can_favorite") && !deleted;
        detailFavoriteIcon.color(favorite ? 0xFFE9B18F : VIEWER_TEXT);
        detailFavoriteButton.setContentDescription(deleted ? "恢复后可以收藏" : favorite ? "取消收藏" : "收藏");
        detailFavoriteButton.setSelected(favorite);
        detailFavoriteButton.setEnabled(canFavorite);
        detailFavoriteButton.setAlpha(canFavorite ? 1f : .55f);
        detailEditIcon.setVisibility(
                !detailFeatureScope && item.optBoolean("can_edit", false)
                        ? View.VISIBLE
                        : View.GONE);
        int comments = item.optInt("comments_count");
        detailComments.setText(comments > 0 ? "评论 " + comments : "评论");
        detailSaveLabel.setText("保存");
        detailUploader.setText(
                item.optString("uploader_name", "家人")
                        + "上传 · 拍摄于 "
                        + Ui.readable(item.optString("captured_at")));
        updateDetailLayout();
    }

    private View makeDetailPreview(int index) {
        if (index < 0 || index >= detailItems.size()) return null;
        JSONObject item = detailItems.get(index);
        FrameLayout preview = new FrameLayout(this);
        preview.setBackgroundColor(VIEWER_BACKGROUND);
        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.addView(image, new FrameLayout.LayoutParams(-1, -1));
        images.load(image, str(item, "thumbnail_url"), 900);
        if (item.optString("kind").equals("video")) {
            FrameLayout badge = new FrameLayout(this);
            badge.setBackground(Ui.round(0x66000000, 27, this));
            badge.addView(
                    new Ui.Icon(this, "play", Color.WHITE),
                    new FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER));
            preview.addView(badge, new FrameLayout.LayoutParams(dp(54), dp(54), Gravity.CENTER));
        } else images.prefetch(str(item, "file_url"), DETAIL_IMAGE_SIZE);
        preview.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        return preview;
    }

    private void refreshDetailNeighbors() {
        if (detailPager == null || detailItems.isEmpty()) return;
        if (detailPager.isInteracting()) {
            final int binding = detailBindGeneration;
            handler.postDelayed(
                    () -> {
                        if (binding == detailBindGeneration && currentPage.equals("detail"))
                            refreshDetailNeighbors();
                    },
                    80);
            return;
        }
        cancelDetailPreviews();
        detailPreviousView = makeDetailPreview(detailIndex - 1);
        detailNextView = makeDetailPreview(detailIndex + 1);
        detailPager.setNeighbors(detailPreviousView, detailNextView);
    }

    private void cancelDetailImage() {
        cancelPageImages(detailCurrentView);
    }

    private void cancelDetailPreviews() {
        cancelPageImages(detailPreviousView);
        cancelPageImages(detailNextView);
        detailPreviousView = detailNextView = null;
    }

    private void cancelPageImages(View page) {
        if (images == null || page == null) return;
        if (page instanceof ImageView) images.cancel((ImageView) page);
        else if (page instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) page;
            for (int i = 0; i < group.getChildCount(); i++) cancelPageImages(group.getChildAt(i));
        }
    }

    private void bindDetail() {
        stopVideo();
        cancelDetailImage();
        cancelDetailPreviews();
        final int binding = ++detailBindGeneration;
        detailSeeking = false;
        final JSONObject item = currentItem();
        String day = item.optString("album_date", Ui.day(item.optString("captured_at")));
        detailAge.setText(Ui.age(profile.optString("birthday"), day));
        detailDate.setText(Ui.date(day));
        updateDetailCount();
        detailDescription.setText(storyText(item));
        updateDetailActions(item);
        hideDetailNotice();
        boolean isVideo = item.optString("kind").equals("video");
        detailVideoControls.setVisibility(isVideo ? View.VISIBLE : View.GONE);
        FrameLayout page = new FrameLayout(this);
        page.setBackgroundColor(VIEWER_BACKGROUND);
        if (isVideo) {
            final VideoView playerView = new VideoView(this);
            video = playerView;
            boolean fromStory = item.optString("id").equals(storyReturnVideo);
            videoWantsPlay = !fromStory;
            if (fromStory) videoSavedPosition = storyReturnPosition;
            storyReturnVideo = "";
            storyReturnPosition = 0;
            page.addView(playerView, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
            ProgressBar progress = new ProgressBar(this);
            progress.setIndeterminateTintList(
                    android.content.res.ColorStateList.valueOf(0xFFE9B18F));
            page.addView(progress, new FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER));
            // Observe, rather than replace, VideoView's own SurfaceHolder callback. Android
            // releases its player when the app is hidden, even though this view survives.
            playerView
                    .getHolder()
                    .addCallback(
                            new SurfaceHolder.Callback() {
                                @Override
                                public void surfaceCreated(SurfaceHolder holder) {
                                    if (video == playerView && binding == detailBindGeneration)
                                        progress.setVisibility(View.VISIBLE);
                                }

                                @Override
                                public void surfaceChanged(
                                        SurfaceHolder holder, int format, int width, int height) {}

                                @Override
                                public void surfaceDestroyed(SurfaceHolder holder) {
                                    if (video != playerView || binding != detailBindGeneration)
                                        return;
                                    videoPrepared = false;
                                    videoRestorePending = videoSavedPosition > 0;
                                    detailPlay.setEnabled(false);
                                    detailSeek.setEnabled(false);
                                    handler.removeCallbacks(detailTicker);
                                    handler.removeCallbacks(detailAutoHide);
                                    getWindow()
                                            .clearFlags(
                                                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                                }
                            });
            GestureDetector taps =
                    new GestureDetector(
                            this,
                            new GestureDetector.SimpleOnGestureListener() {
                                @Override
                                public boolean onDown(MotionEvent event) {
                                    return true;
                                }

                                @Override
                                public boolean onSingleTapConfirmed(MotionEvent event) {
                                    toggleDetailChrome();
                                    return true;
                                }
                            });
            playerView.setOnTouchListener(
                    (view, event) -> {
                        taps.onTouchEvent(event);
                        return true;
                    });
            detailSeek.setEnabled(false);
            detailSeek.setProgress(0);
            detailPlay.setEnabled(false);
            detailPlay.showPlaying(false);
            detailVideoTime.setText("正在加载…");
            playerView.setOnPreparedListener(
                    player -> {
                        if (video != playerView
                                || binding != detailBindGeneration
                                || !currentPage.equals("detail")) return;
                        videoPrepared = true;
                        videoSavedDuration = Math.max(0, playerView.getDuration());
                        progress.setVisibility(View.GONE);
                        player.setLooping(false);
                        player.setOnSeekCompleteListener(
                                seeked -> {
                                    if (video != playerView
                                            || binding != detailBindGeneration
                                            || !videoPrepared) return;
                                    videoRestorePending = false;
                                    videoSavedPosition =
                                            Math.max(0, playerView.getCurrentPosition());
                                    if (resumed && videoWantsPlay) playerView.start();
                                    else playerView.pause();
                                    updateVideoControls();
                                    scheduleDetailAutoHide();
                                });
                        detailSeek.setEnabled(true);
                        detailPlay.setEnabled(true);
                        if (videoSavedPosition > 0) {
                            playerView.pause();
                            seekDetailVideo(Math.min(videoSavedPosition, videoSavedDuration));
                        } else if (resumed && videoWantsPlay) playerView.start();
                        else playerView.pause();
                        handler.removeCallbacks(detailTicker);
                        handler.post(detailTicker);
                        updateVideoControls();
                        scheduleDetailAutoHide();
                    });
            playerView.setOnCompletionListener(
                    player -> {
                        if (video != playerView || binding != detailBindGeneration) return;
                        videoWantsPlay = false;
                        videoRestorePending = false;
                        videoSavedPosition = Math.max(0, playerView.getDuration());
                        updateVideoControls();
                        setDetailChrome(true, true);
                    });
            playerView.setOnErrorListener(
                    (player, what, extra) -> {
                        if (video != playerView || binding != detailBindGeneration) return true;
                        progress.setVisibility(View.GONE);
                        videoPrepared = false;
                        detailPlay.setEnabled(false);
                        detailSeek.setEnabled(false);
                        detailVideoTime.setText("暂时无法播放");
                        showDetailNotice("视频暂时没载入，点此重试", this::bindDetail);
                        return true;
                    });
            try {
                playerView.setVideoURI(Uri.parse(api.url(str(item, "file_url"))), api.headers());
            } catch (Exception error) {
                progress.setVisibility(View.GONE);
                showDetailNotice("视频暂时没载入，点此重试", this::bindDetail);
            }
        } else {
            ZoomImageView photo = new ZoomImageView(this, this::toggleDetailChrome);
            photo.setContentDescription("照片，左右滑动浏览，点一下显示或收起操作，双击放大");
            page.addView(photo, new FrameLayout.LayoutParams(-1, -1));
            ProgressBar progress = new ProgressBar(this);
            progress.setIndeterminateTintList(
                    android.content.res.ColorStateList.valueOf(0xFFE9B18F));
            page.addView(progress, new FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER));
            images.loadProgressive(
                    photo,
                    str(item, "thumbnail_url"),
                    str(item, "file_url"),
                    DETAIL_IMAGE_SIZE,
                    () -> {
                        if (binding == detailBindGeneration && isCurrentDetail(item))
                            progress.setVisibility(View.GONE);
                    },
                    () -> {
                        if (binding != detailBindGeneration || !isCurrentDetail(item)) return;
                        progress.setVisibility(View.GONE);
                        showDetailNotice("原图暂时没载入，点此重试", this::bindDetail);
                    });
        }
        detailCurrentView = page;
        detailPager.setPagingEnabled(true);
        detailPreviousView = makeDetailPreview(detailIndex - 1);
        detailNextView = makeDetailPreview(detailIndex + 1);
        detailPager.setPages(detailPreviousView, page, detailNextView);
        prefetchDetailWindow();
        String id = item.optString("id");
        final ApiClient client = api.snapshot();
        async(
                () -> client.request("GET", "/api/media/" + ApiClient.encode(id), null),
                result -> {
                    if (binding != detailBindGeneration || !isCurrentDetail(item)) return;
                    JSONObject value = result.optJSONObject("media");
                    if (value == null) value = result;
                    updateItem(value);
                    detailDescription.setText(storyText(value));
                    updateDetailActions(value);
                },
                error -> {
                    if (binding != detailBindGeneration || !isCurrentDetail(item)) return;
                    if (error.optInt("status") == 401) {
                        clearSession(false);
                        toast("登录已过期，请重新登录");
                    } else if (error.optInt("status") == 403 || error.optInt("status") == 404) {
                        closeDetail();
                        toast("这份内容已移走，或当前账号无权查看");
                    }
                });
    }

    private void moveDetail(int direction) {
        if (direction == 0 || !currentPage.equals("detail") || detailItems.isEmpty()) return;
        direction = direction < 0 ? -1 : 1;
        int next = detailIndex + direction;
        if (next >= 0 && next < detailItems.size()) {
            detailPendingDirection = 0;
            detailPendingAnchor = "";
            detailIndex = next;
            bindDetail();
            return;
        }
        boolean hasMoreInDirection = direction < 0 ? detailHasBefore : detailHasAfter;
        if (detailWindowReady && !hasMoreInDirection) {
            toast(direction < 0 ? "已经是第一项了" : "已经看到最后一项了");
            return;
        }
        detailPendingDirection = direction;
        detailPendingAnchor = currentItem().optString("id");
        showDetailNotice("正在读取相邻内容…", null);
        if (!detailWindowReady) requestDetailWindow(detailPendingAnchor, 0);
        else
            requestDetailWindow(
                    detailItems.get(direction < 0 ? 0 : detailItems.size() - 1).optString("id"),
                    direction);
    }

    private String videoTime(int millis) {
        int seconds = Math.max(0, millis / 1000);
        if (seconds >= 3600)
            return String.format(
                    Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60);
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    private void toggleVideoPlayback() {
        if (video == null || !videoPrepared) return;
        if (video.isPlaying() || (videoRestorePending && videoWantsPlay)) {
            video.pause();
            videoWantsPlay = false;
        } else {
            videoWantsPlay = true;
            if (video.getCurrentPosition() >= video.getDuration() - 150) seekDetailVideo(0);
            else if (!videoRestorePending) video.start();
        }
        updateVideoControls();
        scheduleDetailAutoHide();
    }

    private void seekDetailVideo(int position) {
        if (video == null || !videoPrepared) return;
        videoSavedPosition = Math.max(0, position);
        videoRestorePending = true;
        video.seekTo(videoSavedPosition);
        updateVideoControls();
    }

    private void updateVideoControls() {
        if (video == null || !videoPrepared || detailPlay == null) return;
        int duration = video.getDuration();
        if (duration > 0) videoSavedDuration = duration;
        else duration = videoSavedDuration;
        int position =
                videoRestorePending ? videoSavedPosition : Math.max(0, video.getCurrentPosition());
        if (!videoRestorePending) videoSavedPosition = position;
        if (!detailSeeking) {
            String label = videoTime(position) + " / " + videoTime(duration);
            if (!label.contentEquals(detailVideoTime.getText())) detailVideoTime.setText(label);
            int progress = duration > 0 ? (int) (position * 1000L / duration) : 0;
            if (detailSeek.getProgress() != progress) detailSeek.setProgress(progress);
        }
        boolean playing = video.isPlaying();
        detailPlay.showPlaying(playing);
        if (playing) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private final class PlaybackButton extends View {
        private final android.graphics.Paint paint =
                new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private boolean playing;

        PlaybackButton() {
            super(MainActivity.this);
            setContentDescription("播放视频");
            setBackground(Ui.ripple(0x26FFFFFF, 22, MainActivity.this));
            setFocusable(true);
        }

        void showPlaying(boolean value) {
            if (value == playing) return;
            playing = value;
            setContentDescription(playing ? "暂停视频" : "播放视频");
            invalidate();
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            super.onDraw(canvas);
            paint.setColor(VIEWER_TEXT);
            float x = getWidth() / 2f, y = getHeight() / 2f;
            if (playing) {
                canvas.drawRoundRect(
                        x - dp(7), y - dp(9), x - dp(2), y + dp(9), dp(1), dp(1), paint);
                canvas.drawRoundRect(
                        x + dp(2), y - dp(9), x + dp(7), y + dp(9), dp(1), dp(1), paint);
            } else {
                android.graphics.Path path = new android.graphics.Path();
                path.moveTo(x - dp(6), y - dp(10));
                path.lineTo(x + dp(10), y);
                path.lineTo(x - dp(6), y + dp(10));
                path.close();
                canvas.drawPath(path, paint);
            }
        }
    }

    private void updateItem(JSONObject updated) {
        for (TimeCollectionPage page : timeCollections.values()) page.updateMedia(updated);
        String id = updated.optString("id");
        for (int i = 0; i < media.size(); i++)
            if (media.get(i).optString("id").equals(id)) media.set(i, updated);
        for (int i = 0; i < detailItems.size(); i++)
            if (detailItems.get(i).optString("id").equals(id)) detailItems.set(i, updated);
    }

    private void toggleFavorite(JSONObject item) {
        if (!str(item, "deleted_at").isEmpty()) {
            toast("这份内容在回收站，恢复后就能收藏");
            return;
        }
        if (!permitted("can_favorite")) {
            toast("当前账号不能修改收藏");
            return;
        }
        boolean next = !item.optBoolean("favorite");
        async(
                () ->
                        client().request(
                                        "PUT",
                                        "/api/media/"
                                                + ApiClient.encode(item.getString("id"))
                                                + "/favorite",
                                        object("favorite", next)),
                result -> {
                    item.put("favorite", next);
                    updateItem(item);
                    if (favoritesOnly && !next) {
                        media.removeIf(value -> value.optString("id").equals(item.optString("id")));
                        mediaIds.remove(item.optString("id"));
                    }
                    timeContentChanged();
                    if (isCurrentDetail(item)) {
                        if (detailFavorites && !next) {
                            if (!detailReturnPage.startsWith("time")) resetAlbum();
                            closeDetail();
                        } else updateDetailActions(item);
                    } else if (currentPage.equals("detail") && detailFavorites && !next) {
                        if (!detailReturnPage.startsWith("time")) resetAlbum();
                        reloadDetailWindowAroundCurrent();
                    }
                    toast(next ? "已收藏" : "已取消收藏");
                },
                null);
    }

    private String storyText(JSONObject item) {
        if (detailFeatureScope) return "";
        String story = str(item, "description");
        if (!story.isEmpty()) return story;
        return !detailFeatureScope && item.optBoolean("can_edit", false) ? "写下这一刻的故事…" : "";
    }

    private void showMediaMenu() {
        JSONObject item = currentItem();
        if (detailFeatureScope) {
            ArrayList<String> featureLabels = new ArrayList<>();
            ArrayList<Runnable> featureActions = new ArrayList<>();
            featureLabels.add("查看拍摄信息");
            featureActions.add(() -> showInfo(item));
            featureLabels.add("保存到手机");
            featureActions.add(() -> saveMedia(item));
            if (item.optBoolean("can_edit", false)) {
                featureLabels.add("添加到其他宝宝相册");
                featureActions.add(() -> linkChildren(item));
            }
            if (!item.optString("kind").equals("video") && permitted("can_manage_child")) {
                featureLabels.add("设为宝宝头像");
                featureActions.add(() -> setAvatar(item));
            }
            showBottomMenu(
                    featureLabels.toArray(new String[0]), which -> featureActions.get(which).run());
            return;
        }
        boolean deleted = !str(item, "deleted_at").isEmpty();
        ArrayList<String> labels = new ArrayList<>();
        ArrayList<Runnable> actions = new ArrayList<>();
        if (deleted) {
            if (item.optBoolean("can_restore", false)) {
                labels.add("恢复到相册");
                actions.add(() -> restore(item));
            }
        } else {
            if (item.optBoolean("can_edit", false)) {
                labels.add("编辑这一刻的故事");
                actions.add(() -> editDescription(item));
                labels.add("修改拍摄时间");
                actions.add(() -> editCaptured(item));
            }
            if (item.optBoolean("can_edit", false)) {
                labels.add("添加到其他宝宝相册");
                actions.add(() -> linkChildren(item));
            }
            if (!item.optString("kind").equals("video") && permitted("can_manage_child")) {
                labels.add("设为宝宝头像");
                actions.add(() -> setAvatar(item));
            }
        }
        labels.add("查看拍摄信息");
        actions.add(() -> showInfo(item));
        if (!deleted && item.optBoolean("can_delete", false)) {
            labels.add("移入回收站");
            actions.add(() -> delete(item));
        }
        showBottomMenu(labels.toArray(new String[0]), index -> actions.get(index).run());
    }

    private void showBottomMenu(String[] options, MenuChoice click) {
        WarmSheet sheet = sheet("更多操作");
        LinearLayout shortcuts = Ui.row(this);
        sheet.body.addView(shortcuts);
        for (int i = 0; i < options.length; i++) {
            final int index = i;
            String label = options[i];
            String icon =
                    label.contains("故事")
                            ? "edit"
                            : label.contains("时间")
                                    ? "calendar"
                                    : label.contains("其他宝宝")
                                            ? "collection"
                                            : label.contains("头像") ? "baby" : "";
            if (!icon.isEmpty()) {
                LinearLayout tile = Ui.column(this);
                tile.setGravity(Gravity.CENTER);
                tile.setPadding(dp(2), dp(10), dp(2), dp(12));
                tile.addView(
                        new Ui.Icon(this, icon, Ui.ACCENT, true),
                        new LinearLayout.LayoutParams(dp(31), dp(31)));
                String shortLabel =
                        label.contains("故事")
                                ? "写故事"
                                : label.contains("时间")
                                        ? "改时间"
                                        : label.contains("头像") ? "设为头像" : "放入相册";
                TextView title = text(shortLabel, 13, Ui.INK);
                title.setGravity(Gravity.CENTER);
                title.setPadding(0, dp(8), 0, 0);
                tile.addView(title);
                tile.setContentDescription(label);
                tile.setFocusable(true);
                tile.setBackground(Ui.ripple(Ui.BG, 12, this));
                tile.setOnClickListener(
                        v -> {
                            sheet.dismiss();
                            click.select(index);
                        });
                shortcuts.addView(tile, new LinearLayout.LayoutParams(0, -2, 1));
            }
        }
        if (shortcuts.getChildCount() > 0) SecondaryUi.divider(this, sheet.body);
        for (int i = 0; i < options.length; i++) {
            final int index = i;
            String label = options[i];
            if (label.contains("故事")
                    || label.contains("时间")
                    || label.contains("其他宝宝")
                    || label.contains("头像")) continue;
            if (label.equals("移入回收站")) SecondaryUi.divider(this, sheet.body);
            String icon =
                    label.contains("恢复")
                            ? "restore"
                            : label.contains("回收站")
                                    ? "trash"
                                    : label.contains("保存") ? "download" : "info";
            sheet.body.addView(
                    SecondaryUi.row(
                            this,
                            icon,
                            label.replace("查看拍摄信息", "拍摄信息"),
                            "",
                            () -> {
                                sheet.dismiss();
                                click.select(index);
                            }));
        }
        sheet.show();
    }

    private void setAvatar(JSONObject item) {
        if (!permitted("can_manage_child")) {
            toast("只有管理员可以修改宝宝头像");
            return;
        }
        async(
                () -> {
                    ApiClient selected = client();
                    selected.request(
                            "PATCH",
                            "/api/children/" + ApiClient.encode(selected.childId),
                            object("avatar_media_id", item.getString("id")));
                    return selected.request("GET", "/api/profile", null);
                },
                result -> {
                    profile = result;
                    if (mePage != null) mePage.refreshFamily();
                    toast("宝宝头像已更新");
                },
                null);
    }

    private void editDescription(JSONObject item) {
        if (!item.optBoolean("can_edit", false)) return;
        final int epoch = accountEpoch;
        final boolean wasVideo = item.optString("kind").equals("video");
        final int playbackPosition =
                video != null && videoPrepared && !videoRestorePending
                        ? Math.max(0, video.getCurrentPosition())
                        : videoSavedPosition;
        detailLoadGeneration++;
        detailLoadingInitial = detailLoadingBefore = detailLoadingAfter = false;
        detailPendingDirection = 0;
        detailPendingAnchor = "";
        Runnable returnToMedia =
                () -> {
                    if (epoch != accountEpoch || !currentPage.equals("photo_story")) return;
                    if (wasVideo) {
                        storyReturnVideo = item.optString("id");
                        storyReturnPosition = playbackPosition;
                    }
                    showDetail();
                    reloadDetailWindowAroundCurrent();
                    refreshIdentity();
                };
        final String original = str(item, "description");
        boolean[] saving = {false};
        EditText editor =
                input(
                        "写下以后还想记得的小事",
                        original,
                        InputType.TYPE_CLASS_TEXT
                                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        editor.setSingleLine(false);
        editor.setGravity(Gravity.TOP | Gravity.START);
        editor.setMinLines(8);
        editor.setMaxLines(24);
        editor.setTextSize(17);
        editor.setLineSpacing(dp(6), 1);
        editor.setPadding(0, dp(18), 0, dp(18));
        editor.setBackgroundColor(Color.TRANSPARENT);
        Runnable back =
                () -> {
                    if (saving[0]) {
                        toast("正在保存，请稍等");
                        return;
                    }
                    if (!editor.getText().toString().equals(original)) {
                        WarmSheet discard = sheet("还没有保存");
                        SecondaryUi.note(this, discard.body, "要保留刚才写的故事吗？");
                        discard.primary("继续编辑", discard::dismiss);
                        discard.secondary(
                                "放弃修改",
                                () -> {
                                    discard.dismiss();
                                    if (epoch == accountEpoch) returnToMedia.run();
                                });
                        discard.show();
                    } else if (epoch == accountEpoch) returnToMedia.run();
                };
        SettingsScreen page = new SettingsScreen(this) {};
        LinearLayout panel = page.page("photo_story", "这一刻的故事", back);
        LinearLayout preview = Ui.row(this);
        preview.addView(mediaThumb(item, 88));
        TextView day = text(Ui.date(item.optString("album_date")), 14, Ui.MUTED);
        day.setPadding(dp(16), 0, 0, 0);
        preview.addView(day);
        panel.addView(preview);
        if (item.optInt("shared_count", 1) > 1)
            SecondaryUi.note(this, panel, "这份内容由多个宝宝共用，故事会同步修改。");
        panel.addView(editor, new LinearLayout.LayoutParams(-1, -2));
        TextView error = page.state(panel);
        TextView save = page.toolbarAction("保存", null);
        save.setOnClickListener(
                v -> {
                    if (saving[0] || epoch != accountEpoch || !panel.isAttachedToWindow()) return;
                    String value = editor.getText().toString();
                    if (value.codePointCount(0, value.length()) > 5000) {
                        error.setText("故事最多可以写5000个字，请稍作精简。");
                        return;
                    }
                    if (value.equals(original)) {
                        returnToMedia.run();
                        return;
                    }
                    saving[0] = true;
                    save.setEnabled(false);
                    save.setText("保存中");
                    editor.setEnabled(false);
                    error.setText("");
                    async(
                            () ->
                                    client().request(
                                                    "PUT",
                                                    "/api/media/"
                                                            + ApiClient.encode(
                                                                    item.optString("id")),
                                                    object("description", value)),
                            result -> {
                                saving[0] = false;
                                item.put("description", value);
                                updateItem(item);
                                timeContentChanged();
                                if (panel.isAttachedToWindow()) {
                                    returnToMedia.run();
                                    toast("故事已保存");
                                }
                            },
                            problem -> {
                                saving[0] = false;
                                if (!panel.isAttachedToWindow()) return;
                                save.setEnabled(true);
                                save.setText("保存");
                                editor.setEnabled(true);
                                error.setText(problem.optString("message") + "，文字已保留。");
                            });
                });
    }

    private View padded(View view) {
        LinearLayout wrapper = Ui.column(this);
        wrapper.setPadding(dp(20), dp(10), dp(20), dp(5));
        wrapper.addView(view, new LinearLayout.LayoutParams(-1, -2));
        return wrapper;
    }

    private void editCaptured(JSONObject item) {
        if (!item.optBoolean("can_edit", false)) return;
        final int epoch = accountEpoch;
        if (item.optInt("shared_count", 1) > 1)
            confirmAction(
                    "修改拍摄时间？",
                    "其他宝宝相册里的这份内容也会一起更新。",
                    "继续修改",
                    () -> {
                        if (epoch == accountEpoch) editCapturedConfirmed(item);
                    });
        else editCapturedConfirmed(item);
    }

    private void editCapturedConfirmed(JSONObject item) {
        if (!item.optBoolean("can_edit", false)) return;
        final int epoch = accountEpoch;
        LocalDateTime initial;
        try {
            initial =
                    OffsetDateTime.parse(item.optString("captured_at"))
                            .atZoneSameInstant(ZoneId.of("Asia/Shanghai"))
                            .toLocalDateTime();
        } catch (Exception ignored) {
            initial = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
        }
        LocalDateTime[] selected = {initial};
        WarmSheet sheet = sheet("修改拍摄时间");
        LinearLayout fields = Ui.column(this);
        sheet.body.addView(fields);
        TextView error = SecondaryUi.note(this, sheet.body, "");
        error.setTextColor(Ui.ERROR);
        Runnable[] bind = new Runnable[1];
        bind[0] =
                () -> {
                    fields.removeAllViews();
                    fields.addView(
                            SecondaryUi.row(
                                    this,
                                    "calendar",
                                    "日期",
                                    Ui.date(selected[0].toLocalDate().toString()),
                                    () -> {
                                        DatePickerDialog picker =
                                                new DatePickerDialog(
                                                        this,
                                                        (d, y, m, day) -> {
                                                            if (epoch != accountEpoch
                                                                    || !sheet.isShowing()) return;
                                                            selected[0] =
                                                                    LocalDateTime.of(
                                                                            LocalDate.of(
                                                                                    y, m + 1, day),
                                                                            selected[0]
                                                                                    .toLocalTime());
                                                            bind[0].run();
                                                        },
                                                        selected[0].getYear(),
                                                        selected[0].getMonthValue() - 1,
                                                        selected[0].getDayOfMonth());
                                        picker.setTitle("拍摄日期");
                                        picker.show();
                                    }));
                    SecondaryUi.divider(this, fields);
                    fields.addView(
                            SecondaryUi.row(
                                    this,
                                    "time",
                                    "时间",
                                    selected[0].format(DateTimeFormatter.ofPattern("HH:mm")),
                                    () -> {
                                        TimePickerDialog picker =
                                                new TimePickerDialog(
                                                        this,
                                                        (d, hour, minute) -> {
                                                            if (epoch != accountEpoch
                                                                    || !sheet.isShowing()) return;
                                                            selected[0] =
                                                                    selected[0]
                                                                            .withHour(hour)
                                                                            .withMinute(minute);
                                                            bind[0].run();
                                                        },
                                                        selected[0].getHour(),
                                                        selected[0].getMinute(),
                                                        true);
                                        picker.setTitle("拍摄时间");
                                        picker.show();
                                    }));
                };
        bind[0].run();
        TextView save = sheet.primary("保存", null);
        save.setOnClickListener(
                v -> {
                    if (epoch != accountEpoch || !sheet.isShowing() || !save.isEnabled()) return;
                    save.setEnabled(false);
                    save.setText("正在保存…");
                    fields.setVisibility(View.GONE);
                    sheet.setCancelable(false);
                    error.setText("");
                    String value =
                            selected[0]
                                    .withSecond(0)
                                    .withNano(0)
                                    .atOffset(ZoneOffset.ofHours(8))
                                    .toString();
                    async(
                            () ->
                                    client().request(
                                                    "PUT",
                                                    "/api/media/"
                                                            + ApiClient.encode(
                                                                    item.optString("id")),
                                                    object("captured_at", value)),
                            result -> {
                                sheet.dismiss();
                                item.put("captured_at", value);
                                item.put("album_date", value.substring(0, 10));
                                updateItem(item);
                                if (isCurrentDetail(item)) {
                                    detailDate.setText(Ui.date(value));
                                    detailAge.setText(
                                            Ui.age(profile.optString("birthday"), Ui.day(value)));
                                }
                                timeContentChanged();
                                if (!currentPage.equals("detail")
                                        || !detailReturnPage.startsWith("time")) resetAlbum();
                                if (currentPage.equals("detail")) {
                                    String day = currentItem().optString("album_date");
                                    if ((!detailDay.isEmpty() && !detailDay.equals(day))
                                            || (!detailMonth.isEmpty()
                                                    && !day.startsWith(detailMonth + "-")))
                                        closeDetail();
                                    else reloadDetailWindowAroundCurrent();
                                }
                                toast("拍摄时间已更新");
                            },
                            problem -> {
                                if (!sheet.isShowing()) return;
                                save.setEnabled(true);
                                save.setText("保存");
                                fields.setVisibility(View.VISIBLE);
                                sheet.setCancelable(true);
                                error.setText(problem.optString("message"));
                            });
                });
        sheet.show();
    }

    private void showInfo(JSONObject item) {
        WarmSheet info = sheet("拍摄信息");
        info.body.addView(mediaThumb(item, 88));
        info.body.addView(
                SecondaryUi.row(
                        this,
                        "calendar",
                        "拍摄时间",
                        Ui.readable(item.optString("captured_at")),
                        !detailFeatureScope
                                        && item.optBoolean("can_edit", false)
                                        && str(item, "deleted_at").isEmpty()
                                ? () -> {
                                    info.dismiss();
                                    editCaptured(item);
                                }
                                : null));
        if (!str(item, "uploader_name").isEmpty())
            info.body.addView(
                    SecondaryUi.row(this, "people", "上传者", item.optString("uploader_name"), null));
        if (item.optInt("width") > 0 && item.optInt("height") > 0)
            info.body.addView(
                    SecondaryUi.row(
                            this,
                            "photo",
                            "尺寸",
                            item.optInt("width") + " × " + item.optInt("height"),
                            null));
        if (item.optLong("size_bytes") > 0)
            info.body.addView(
                    SecondaryUi.row(
                            this, "storage", "文件大小", Ui.size(item.optLong("size_bytes")), null));
        if (item.optString("kind").equals("video"))
            info.body.addView(
                    SecondaryUi.row(
                            this, "video", "视频时长", Ui.duration(item.optDouble("duration")), null));
        if (!str(item, "description").isEmpty()) {
            SecondaryUi.divider(this, info.body);
            SecondaryUi.note(this, info.body, item.optString("description"));
        }
        info.show();
    }

    private void delete(JSONObject item) {
        if (!item.optBoolean("can_delete", false)) {
            toast("当前账号不能移走这项内容");
            return;
        }
        final int epoch = accountEpoch;
        WarmSheet confirm = sheet("移入回收站？");
        confirm.body.addView(mediaThumb(item, 76));
        SecondaryUi.note(
                this,
                confirm.body,
                item.optInt("shared_count", 1) > 1
                        ? "只移出当前宝宝的相册，其他宝宝仍能查看。之后可以在回收站找回。"
                        : "之后可以在回收站找回。");
        TextView error = SecondaryUi.note(this, confirm.body, "");
        error.setTextColor(Ui.ERROR);
        TextView submit = confirm.primary("移入回收站", null);
        TextView cancel = confirm.secondary("取消", confirm::dismiss);
        submit.setOnClickListener(
                v -> {
                    if (epoch != accountEpoch || !submit.isEnabled() || !confirm.isShowing())
                        return;
                    submit.setEnabled(false);
                    cancel.setEnabled(false);
                    confirm.setCancelable(false);
                    error.setText("");
                    async(
                            () ->
                                    client().request(
                                                    "DELETE",
                                                    "/api/media/"
                                                            + ApiClient.encode(
                                                                    item.optString("id")),
                                                    null),
                            result -> {
                                confirm.dismiss();
                                timeContentChanged();
                                boolean returnToTime =
                                        currentPage.equals("detail")
                                                && detailReturnPage.startsWith("time");
                                if (returnToTime) {
                                    item.put("deleted_at", OffsetDateTime.now().toString());
                                    updateItem(item);
                                    media.removeIf(
                                            value ->
                                                    value.optString("id")
                                                            .equals(item.optString("id")));
                                    mediaIds.remove(item.optString("id"));
                                } else resetAlbum();
                                refreshProfile();
                                if (returnToTime) closeDetail();
                                else showAlbum(false);
                                toast("已移入回收站");
                            },
                            problem -> {
                                if (!confirm.isShowing()) return;
                                submit.setEnabled(true);
                                cancel.setEnabled(true);
                                confirm.setCancelable(true);
                                error.setText(problem.optString("message"));
                            });
                });
        confirm.show();
    }

    private void restore(JSONObject item) {
        if (!item.optBoolean("can_restore", false)) {
            toast("当前账号不能恢复这项内容");
            return;
        }
        final int epoch = accountEpoch;
        WarmSheet confirm = sheet("恢复到相册？");
        boolean[] restoring = {false};
        confirm.body.addView(mediaThumb(item, 88));
        SecondaryUi.note(this, confirm.body, "恢复到" + profile.optString("child_name", "宝宝") + "的相册");
        if (!currentPage.equals("detail")) {
            confirm.body.addView(
                    SecondaryUi.row(
                            this,
                            "photo",
                            item.optString("kind").equals("video") ? "播放视频" : "查看照片",
                            "",
                            () -> {
                                if (epoch != accountEpoch || restoring[0]) return;
                                confirm.dismiss();
                                openDetail(item);
                            }));
        }
        TextView error = SecondaryUi.note(this, confirm.body, "");
        error.setTextColor(Ui.ERROR);
        TextView submit = confirm.primary("恢复到相册", null);
        TextView cancel = confirm.secondary("取消", confirm::dismiss);
        submit.setOnClickListener(
                v -> {
                    if (epoch != accountEpoch || !submit.isEnabled() || !confirm.isShowing())
                        return;
                    restoring[0] = true;
                    submit.setEnabled(false);
                    cancel.setEnabled(false);
                    confirm.setCancelable(false);
                    error.setText("");
                    async(
                            () ->
                                    client().request(
                                                    "POST",
                                                    "/api/media/"
                                                            + ApiClient.encode(item.optString("id"))
                                                            + "/restore",
                                                    new JSONObject()),
                            result -> {
                                restoring[0] = false;
                                confirm.dismiss();
                                timeContentChanged();
                                resetAlbum();
                                refreshProfile();
                                showAlbum(false);
                                toast("已恢复到相册");
                            },
                            problem -> {
                                restoring[0] = false;
                                if (!confirm.isShowing()) return;
                                submit.setEnabled(true);
                                cancel.setEnabled(true);
                                confirm.setCancelable(true);
                                error.setText(problem.optString("message"));
                            });
                });
        confirm.show();
    }

    private void refreshProfile() {
        async(
                () -> client().request("GET", "/api/profile", null),
                result -> {
                    profile = result;
                    if (mePage != null) mePage.refreshFamily();
                    JSONObject counts = result.optJSONObject("counts");
                    if (counts == null) counts = new JSONObject();
                    if (statLabels.containsKey("photo"))
                        statLabels
                                .get("photo")
                                .setText(
                                        " "
                                                + counts.optInt(
                                                        "photos", result.optInt("photos_count")));
                    if (statLabels.containsKey("video"))
                        statLabels
                                .get("video")
                                .setText(
                                        " "
                                                + counts.optInt(
                                                        "videos", result.optInt("videos_count")));
                    if (statLabels.containsKey("people"))
                        statLabels
                                .get("people")
                                .setText(
                                        " "
                                                + counts.optInt(
                                                        "members", result.optInt("members_count")));
                    if (albumSummary != null) albumSummary.setText(albumCounts());
                    if (storageLabel != null)
                        storageLabel.setText("已保存 " + Ui.size(result.optLong("storage_bytes")));
                },
                null);
    }

    private void showComments(JSONObject item) {
        if (activeCommentsDialog != null) activeCommentsDialog.dismiss();
        final int epoch = accountEpoch;
        final String path = "/api/media/" + ApiClient.encode(item.optString("id")) + "/comments";
        boolean deletedMedia = !str(item, "deleted_at").isEmpty();
        boolean canComment = permitted("can_comment") && !deletedMedia;
        WarmSheet sheet = sheet("家人的留言");
        activeCommentsDialog = sheet;
        sheet.onDismiss(
                () -> {
                    if (activeCommentsDialog == sheet) activeCommentsDialog = null;
                });
        LinearLayout entries = Ui.column(this);
        entries.setMinimumHeight(dp(180));
        sheet.body.addView(entries, new LinearLayout.LayoutParams(-1, -2));
        EditText editor =
                input("说点什么…", "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        editor.setSingleLine(false);
        editor.setMinLines(1);
        editor.setMaxLines(4);
        editor.setTextSize(15);
        editor.setFilters(
                new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(2000)});
        TextView error = SecondaryUi.note(this, sheet.footer, "");
        error.setTextColor(Ui.ERROR);
        error.setVisibility(View.GONE);
        TextView send = text("发送", 15, Color.WHITE);
        send.setGravity(Gravity.CENTER);
        send.setMinHeight(dp(48));
        send.setBackground(Ui.button(this, true));
        if (canComment) {
            LinearLayout compose = Ui.row(this);
            compose.addView(editor, new LinearLayout.LayoutParams(0, -2, 1));
            LinearLayout.LayoutParams sendParams = new LinearLayout.LayoutParams(dp(64), dp(48));
            sendParams.leftMargin = dp(8);
            compose.addView(send, sendParams);
            sheet.footer.addView(compose);
        } else SecondaryUi.note(this, sheet.footer, deletedMedia ? "恢复后就可以留言" : "当前账号可以查看家人的留言");
        boolean[] sending = {false};
        String[] lastMessage = {""}, requestId = {""};
        int[] requestNumber = {0};
        sheet.onClose(
                () -> {
                    if (sending[0]) return;
                    if (canComment && !editor.getText().toString().trim().isEmpty())
                        confirmAction("留言还没发出", "关闭后，这段文字不会保存。", "放弃留言", sheet::dismiss);
                    else sheet.dismiss();
                });
        Runnable[] load = new Runnable[1];
        load[0] =
                () -> {
                    if (destroyed || epoch != accountEpoch || !sheet.isShowing()) return;
                    int request = ++requestNumber[0];
                    entries.removeAllViews();
                    SecondaryUi.note(this, entries, "正在读取留言…");
                    requestDetailed(
                            "GET",
                            path,
                            null,
                            result -> {
                                if (epoch != accountEpoch
                                        || request != requestNumber[0]
                                        || !sheet.isShowing()) return;
                                entries.removeAllViews();
                                JSONArray comments = array(result, "items");
                                if (comments.length() == 0)
                                    entries.addView(
                                            SecondaryUi.empty(
                                                    this,
                                                    "comment",
                                                    "还没有留言",
                                                    "和家人聊聊这一刻",
                                                    null,
                                                    null));
                                for (int i = 0; i < comments.length(); i++) {
                                    JSONObject comment = comments.getJSONObject(i);
                                    LinearLayout row = Ui.row(this);
                                    row.setGravity(Gravity.TOP);
                                    row.setPadding(0, dp(14), 0, dp(14));
                                    String person = comment.optString("name", "家人");
                                    row.addView(
                                            new FamilyAvatar(this, person),
                                            new LinearLayout.LayoutParams(dp(38), dp(38)));
                                    LinearLayout words = Ui.column(this);
                                    words.setPadding(dp(12), 0, 0, 0);
                                    LinearLayout heading = Ui.row(this);
                                    TextView name = text(person, 15, Ui.INK);
                                    name.setTypeface(null, Typeface.BOLD);
                                    heading.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
                                    TextView time =
                                            text(
                                                    SecondaryUi.time(
                                                            comment.optString("created_at")),
                                                    11,
                                                    Ui.MUTED);
                                    time.setGravity(Gravity.END);
                                    time.setMaxWidth(dp(130));
                                    heading.addView(time);
                                    if (!deletedMedia && comment.optBoolean("can_delete", false)) {
                                        View more =
                                                iconButton(
                                                        "more",
                                                        Ui.MUTED,
                                                        () -> {
                                                            WarmSheet confirm = sheet("删除这条留言？");
                                                            SecondaryUi.note(
                                                                    this, confirm.body, "删除后无法恢复。");
                                                            TextView failure =
                                                                    SecondaryUi.note(
                                                                            this, confirm.body, "");
                                                            failure.setTextColor(Ui.ERROR);
                                                            TextView remove =
                                                                    confirm.primary("删除留言", null);
                                                            TextView cancel =
                                                                    confirm.secondary(
                                                                            "取消", confirm::dismiss);
                                                            remove.setOnClickListener(
                                                                    v -> {
                                                                        if (epoch != accountEpoch
                                                                                || !remove
                                                                                        .isEnabled()
                                                                                || !sheet
                                                                                        .isShowing()) {
                                                                            confirm.dismiss();
                                                                            return;
                                                                        }
                                                                        remove.setEnabled(false);
                                                                        cancel.setEnabled(false);
                                                                        confirm.setCancelable(
                                                                                false);
                                                                        requestDetailed(
                                                                                "DELETE",
                                                                                path
                                                                                        + "/"
                                                                                        + ApiClient
                                                                                                .encode(
                                                                                                        comment
                                                                                                                .optString(
                                                                                                                        "id")),
                                                                                null,
                                                                                deleted -> {
                                                                                    confirm
                                                                                            .dismiss();
                                                                                    if (activityFeed
                                                                                            != null)
                                                                                        activityFeed
                                                                                                .removedComment(
                                                                                                        comment
                                                                                                                .optString(
                                                                                                                        "id"));
                                                                                    load[0].run();
                                                                                },
                                                                                (code, problem) -> {
                                                                                    if (!confirm
                                                                                            .isShowing())
                                                                                        return;
                                                                                    failure.setText(
                                                                                            problem);
                                                                                    remove
                                                                                            .setEnabled(
                                                                                                    code
                                                                                                            != 403);
                                                                                    cancel
                                                                                            .setEnabled(
                                                                                                    true);
                                                                                    confirm
                                                                                            .setCancelable(
                                                                                                    true);
                                                                                });
                                                                    });
                                                            confirm.show();
                                                        });
                                        more.setContentDescription("留言操作");
                                        heading.addView(
                                                more,
                                                new LinearLayout.LayoutParams(dp(44), dp(44)));
                                    }
                                    words.addView(heading);
                                    TextView body = text(comment.optString("text"), 15, Ui.INK);
                                    body.setPadding(0, dp(5), 0, 0);
                                    body.setLineSpacing(dp(3), 1);
                                    words.addView(body);
                                    row.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
                                    entries.addView(row);
                                    if (i < comments.length() - 1)
                                        SecondaryUi.divider(this, entries);
                                }
                                item.put("comments_count", comments.length());
                                updateItem(item);
                                if (isCurrentDetail(item)) updateDetailActions(item);
                            },
                            (code, message) -> {
                                if (epoch != accountEpoch
                                        || request != requestNumber[0]
                                        || !sheet.isShowing()) return;
                                entries.removeAllViews();
                                entries.addView(
                                        SecondaryUi.empty(
                                                this, "comment", "留言暂时没读到", message, "重新读取",
                                                load[0]));
                            });
                };
        send.setOnClickListener(
                v -> {
                    if (sending[0] || epoch != accountEpoch || !sheet.isShowing()) return;
                    String message = editor.getText().toString().trim();
                    if (message.isEmpty()) {
                        error.setText("先写一点内容吧");
                        error.setVisibility(View.VISIBLE);
                        return;
                    }
                    if (!message.equals(lastMessage[0]) || requestId[0].isEmpty())
                        requestId[0] = UUID.randomUUID().toString();
                    lastMessage[0] = message;
                    sending[0] = true;
                    editor.setEnabled(false);
                    send.setEnabled(false);
                    sheet.setCancelable(false);
                    error.setVisibility(View.GONE);
                    JSONObject body = object("text", message);
                    try {
                        body.put("request_id", requestId[0]);
                    } catch (Exception ignored) {
                    }
                    requestDetailed(
                            "POST",
                            path,
                            body,
                            result -> {
                                if (epoch != accountEpoch || !sheet.isShowing()) return;
                                sending[0] = false;
                                requestId[0] = lastMessage[0] = "";
                                editor.setText("");
                                editor.setEnabled(true);
                                send.setEnabled(true);
                                sheet.setCancelable(true);
                                load[0].run();
                            },
                            (code, problem) -> {
                                if (epoch != accountEpoch || !sheet.isShowing()) return;
                                sending[0] = false;
                                editor.setEnabled(true);
                                send.setEnabled(code != 403);
                                sheet.setCancelable(true);
                                error.setText(problem + "，文字已保留。");
                                error.setVisibility(View.VISIBLE);
                                if (code == 403 && activityFeed != null)
                                    activityFeed.commentPermissionDenied();
                            });
                });
        sheet.show();
        load[0].run();
    }

    private void rememberBrowsingPosition() {
        if (currentPage.equals("me") && mePage != null) mePage.remember();
        if (currentPage.equals("activity") && activityFeed != null) activityFeed.pause();
        if ((currentPage.equals("album") || currentPage.equals("trash")) && albumList != null) {
            savedListPosition = albumList.getFirstVisiblePosition();
            View first = albumList.getChildAt(0);
            savedListOffset = first == null ? 0 : first.getTop();
        } else if (currentPage.equals("time_collection") && activeTimeCollection != null)
            activeTimeCollection.rememberPosition();
    }

    private void attachTimeView(View page) {
        if (page.getParent() instanceof ViewGroup) ((ViewGroup) page.getParent()).removeView(page);
        content.addView(page, new FrameLayout.LayoutParams(-1, -1));
    }

    private void clearTimePages() {
        if (timeHomePage != null) timeHomePage.dispose();
        timeHomePage = null;
        cancelPageImages(timeHomeView);
        cancelPageImages(timeMonthsView);
        timeHomeView = null;
        timeMonthsView = null;
        timeMonthCovers.clear();
        timeHomeRestoreY = -1;
        timeMonthsPosition = timeMonthsOffset = 0;
        timeMonthsList = null;
        timeHomeDirty = timeMonthsDirty = false;
        for (TimeCollectionPage page : timeCollections.values()) page.dispose();
        timeCollections.clear();
        activeTimeCollection = null;
        detailTimeCollectionKey = "";
    }

    private void timeContentChanged() {
        timeHomeDirty = timeMonthsDirty = true;
        for (TimeCollectionPage page : timeCollections.values()) page.markDirty();
        if (activityFeed != null) activityFeed.invalidate();
    }

    private void refreshTimeHome() {
        if (timeHomeView != null) {
            timeHomeRestoreY = timeHomeView.getScrollY();
            cancelPageImages(timeHomeView);
        }
        if (timeHomePage != null) timeHomePage.dispose();
        timeHomePage = null;
        timeHomeView = null;
        timeHomeDirty = false;
        timeMonthCovers.clear();
        showTime();
    }

    private void restoreTimeHomePosition() {
        final ScrollView view = timeHomeView;
        if (view != null && timeHomeRestoreY >= 0)
            view.post(
                    () -> {
                        if (view == timeHomeView && timeHomeRestoreY >= 0)
                            view.scrollTo(0, timeHomeRestoreY);
                    });
    }

    private void refreshTimeMonths() {
        if (timeMonthsList != null) {
            timeMonthsPosition = timeMonthsList.getFirstVisiblePosition();
            View first = timeMonthsList.getChildAt(0);
            timeMonthsOffset = first == null ? 0 : first.getTop();
        }
        cancelPageImages(timeMonthsView);
        timeMonthsView = null;
        timeMonthsDirty = false;
        timeMonthCovers.clear();
        showMonths();
    }

    private void openTimeCollection(String kind, String month, boolean favorite, String parent) {
        rememberBrowsingPosition();
        String key = kind + ":" + month + ":" + favorite + ":" + parent;
        TimeCollectionPage page = timeCollections.get(key);
        if (page == null) {
            String title = favorite ? "我的收藏" : kind.equals("video") ? "视频回忆" : Ui.month(month);
            page =
                    new TimeCollectionPage(
                            new TimeCollectionPage.Host() {
                                @Override
                                public Activity activity() {
                                    return MainActivity.this;
                                }

                                @Override
                                public ImageLoader images() {
                                    return images;
                                }

                                @Override
                                public String birthday() {
                                    return profile.optString("birthday");
                                }

                                @Override
                                public void request(
                                        String path,
                                        SettingsScreen.Done success,
                                        SettingsScreen.Failure failure) {
                                    final ApiClient requestClient = api.snapshot();
                                    async(
                                            () -> requestClient.request("GET", path, null),
                                            success::accept,
                                            error -> failure.accept(error.optString("message")));
                                }

                                @Override
                                public void openMedia(TimeCollectionPage origin, JSONObject item) {
                                    activeTimeCollection = origin;
                                    openDetail(item);
                                }

                                @Override
                                public void goBack(TimeCollectionPage origin) {
                                    if (origin.returnPage.equals("months")) showMonths();
                                    else if (origin.returnPage.equals("me")) showMe();
                                    else showTime();
                                }
                            },
                            key,
                            title,
                            kind,
                            month,
                            favorite,
                            parent);
            timeCollections.put(key, page);
            while (timeCollections.size() > 6) {
                String oldest = timeCollections.keySet().iterator().next();
                timeCollections.remove(oldest).dispose();
            }
        }
        showTimeCollection(page);
    }

    private void showTimeCollection(TimeCollectionPage page) {
        rememberBrowsingPosition();
        activeTimeCollection = page;
        currentPage = "time_collection";
        selectedTab = page.returnPage.equals("me") ? "me" : "time";
        screen(true);
        content.addView(page.createView(), new FrameLayout.LayoutParams(-1, -1));
    }

    private void openGrowth() {
        activeMemoryFeature = "growth";
        if (growthPages == null) growthPages = new GrowthPages(this);
        growthPages.show();
    }

    private void openStorybooks() {
        activeMemoryFeature = "storybooks";
        if (storybookPages == null) storybookPages = new StorybookPages(this);
        storybookPages.show();
    }

    private void openStorybook(String month) {
        activeMemoryFeature = "storybooks";
        if (storybookPages == null) storybookPages = new StorybookPages(this);
        storybookPages.showMonth(month);
    }

    @Override
    public ImageLoader images() {
        return images;
    }

    @Override
    public JSONObject profile() {
        return profile;
    }

    @Override
    public boolean canEdit() {
        return permitted("can_upload");
    }

    @Override
    public void request(
            String method,
            String path,
            JSONObject body,
            SettingsScreen.Done success,
            SettingsScreen.Failure failure) {
        requestDetailed(
                method,
                path,
                body,
                success,
                (status, message) -> {
                    if (failure != null) failure.accept(message);
                    else toast(message);
                });
    }

    @Override
    public void requestDetailed(
            String method,
            String path,
            JSONObject body,
            SettingsScreen.Done success,
            MemoryFeatureHost.Failure failure) {
        final ApiClient client = api.snapshot();
        async(
                () -> client.request(method, path, body),
                result -> {
                    if (!method.equals("GET")) timeContentChanged();
                    success.accept(result);
                },
                error -> {
                    if (error.optInt("status") == 401) {
                        clearSession(false);
                        toast("登录已过期，请重新登录");
                    } else failure.accept(error.optInt("status"), error.optString("message"));
                });
    }

    @Override
    public Runnable requestCancelableGet(
            String path, SettingsScreen.Done success, MemoryFeatureHost.Failure failure) {
        final ApiClient client = api.snapshot();
        final int requestAccount = accountEpoch;
        final ApiClient.DownloadCancellation cancellation = new ApiClient.DownloadCancellation();
        final Future<?> task =
                cancelableReads.submit(
                        () -> {
                            if (destroyed
                                    || requestAccount != accountEpoch
                                    || cancellation.isCancelled()) return;
                            try {
                                JSONObject result = client.request("GET", path, null, cancellation);
                                handler.post(
                                        () -> {
                                            if (destroyed
                                                    || requestAccount != accountEpoch
                                                    || cancellation.isCancelled()) return;
                                            try {
                                                success.accept(result);
                                            } catch (Exception error) {
                                                toast(errorText(error));
                                            }
                                        });
                            } catch (Exception error) {
                                handler.post(
                                        () -> {
                                            if (destroyed
                                                    || requestAccount != accountEpoch
                                                    || cancellation.isCancelled()) return;
                                            int status =
                                                    error instanceof ApiClient.HttpError
                                                            ? ((ApiClient.HttpError) error).status
                                                            : 0;
                                            if (status == 401) {
                                                clearSession(false);
                                                toast("登录已过期，请重新登录");
                                            } else if (failure != null)
                                                failure.accept(status, errorText(error));
                                        });
                            }
                        });
        return () -> {
            cancellation.cancel();
            task.cancel(true);
            if (cancelableReads instanceof ThreadPoolExecutor && task instanceof Runnable)
                ((ThreadPoolExecutor) cancelableReads).remove((Runnable) task);
        };
    }

    @Override
    public void showFeature(String page, View view, Runnable back) {
        cancelAlbumRequests();
        currentPage = "feature_" + page;
        selectedTab = "time";
        screen(false);
        if (view.getParent() instanceof ViewGroup) ((ViewGroup) view.getParent()).removeView(view);
        content.addView(view, new FrameLayout.LayoutParams(-1, -1));
        settingsBack = back;
    }

    private void pauseMemoryFeatures() {
        if (growthPages != null) growthPages.pause();
        if (storybookPages != null) storybookPages.pause();
    }

    private void disposeMemoryFeatures() {
        if (growthPages != null) growthPages.dispose();
        if (storybookPages != null) storybookPages.dispose();
        growthPages = null;
        storybookPages = null;
        activeMemoryFeature = "";
        featureMediaBack = null;
        detailFeatureScope = false;
        if (pendingComparison != null) pendingComparison.recycle();
        pendingComparison = null;
    }

    @Override
    public void returnToTime() {
        pauseMemoryFeatures();
        activeMemoryFeature = "";
        showTime();
    }

    @Override
    public void openFeatureMedia(JSONObject item, List<JSONObject> scope, Runnable back) {
        pauseMemoryFeatures();
        featureMediaBack = back;
        detailFeatureScope = true;
        detailReturnPage = "time_feature";
        detailTimeCollectionKey = "";
        detailKind = "all";
        detailMonth = detailDay = "";
        detailFavorites = detailDeleted = false;
        detailLoadGeneration++;
        detailLoadingInitial = detailLoadingBefore = detailLoadingAfter = false;
        detailHasBefore = detailHasAfter = false;
        detailWindowBase = detailPendingDirection = 0;
        detailPendingAnchor = "";
        detailItems.clear();
        HashSet<String> seen = new HashSet<>();
        for (JSONObject value : scope) if (seen.add(value.optString("id"))) detailItems.add(value);
        detailIndex = indexOfMedia(detailItems, item.optString("id"));
        if (detailIndex < 0) {
            detailItems.add(item);
            detailIndex = detailItems.size() - 1;
        }
        detailTotal = detailItems.size();
        detailWindowReady = true;
        showDetail();
    }

    @Override
    public void saveComparison(android.graphics.Bitmap bitmap, boolean share) {
        if (bitmap == null || bitmap.isRecycled()) {
            toast("照片还没准备好，请稍后重试");
            return;
        }
        if (comparisonWriting) {
            bitmap.recycle();
            toast("正在准备图片，请稍候");
            return;
        }
        if (!share
                && Build.VERSION.SDK_INT < 29
                && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            if (pendingComparison != null) pendingComparison.recycle();
            pendingComparison = bitmap;
            pendingComparisonShare = share;
            requestPermissions(
                    new String[] {android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 24);
            return;
        }
        comparisonWriting = true;
        final int requestAccount = accountEpoch;
        toast(share ? "正在准备分享图片…" : "正在保存对比图…");
        work.execute(
                () -> {
                    try {
                        if (requestAccount != accountEpoch) {
                            bitmap.recycle();
                            return;
                        }
                        Uri uri = ComparisonExporter.write(this, bitmap, share);
                        handler.post(
                                () -> {
                                    if (destroyed || requestAccount != accountEpoch) return;
                                    if (!share) {
                                        toast("对比图已保存到手机相册");
                                        return;
                                    }
                                    Intent intent = new Intent(Intent.ACTION_SEND);
                                    intent.setType("image/jpeg");
                                    intent.putExtra(Intent.EXTRA_STREAM, uri);
                                    intent.setClipData(
                                            ClipData.newUri(getContentResolver(), "成长对比图", uri));
                                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                                    try {
                                        startActivity(Intent.createChooser(intent, "分享成长对比图"));
                                    } catch (ActivityNotFoundException error) {
                                        toast("暂无可用的分享应用，可以先保存对比图");
                                    }
                                });
                    } catch (Exception error) {
                        if (!bitmap.isRecycled()) bitmap.recycle();
                        handler.post(
                                () -> {
                                    if (!destroyed && requestAccount == accountEpoch)
                                        toast(errorText(error));
                                });
                    } finally {
                        handler.post(() -> comparisonWriting = false);
                    }
                });
    }

    private void showTime() {
        rememberBrowsingPosition();
        if (timeHomeDirty && timeHomeView != null) {
            refreshTimeHome();
            return;
        }
        currentPage = "time";
        selectedTab = "time";
        screen(true);
        if (timeHomeView != null) {
            attachTimeView(timeHomeView);
            return;
        }
        timeHomePage =
                new TimeHomePage(
                        this,
                        new TimeHomePage.Actions() {
                            public void openGrowth() {
                                MainActivity.this.openGrowth();
                            }

                            public void openStorybooks() {
                                MainActivity.this.openStorybooks();
                            }

                            public void openStorybook(String month) {
                                MainActivity.this.openStorybook(month);
                            }

                            public void openVideos() {
                                openTimeCollection("video", "", false, "time");
                            }

                            public void openMonths() {
                                showMonths();
                            }

                            public void openFavorites() {
                                openTimeCollection("all", "", true, "time");
                            }

                            public void refresh() {
                                refreshTimeHome();
                            }

                            public void contentReady() {
                                restoreTimeHomePosition();
                            }
                        });
        timeHomeView = timeHomePage.createView();
        timeHomeView.setOnTouchListener(
                (view, event) -> {
                    if (event.getActionMasked() == MotionEvent.ACTION_DOWN
                            || event.getActionMasked() == MotionEvent.ACTION_MOVE)
                        timeHomeRestoreY = -1;
                    return false;
                });
        attachTimeView(timeHomeView);
        restoreTimeHomePosition();
    }

    private FrameLayout timeMonthCover(String month) {
        FrameLayout cover = new FrameLayout(this);
        cover.setBackground(Ui.round(Ui.SOFT, 12, this));
        cover.setClipToOutline(true);
        cover.addView(
                new Ui.Icon(this, "calendar", Ui.ACCENT),
                new FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER));
        ImageView image = new TopCropImageView(this);
        cover.addView(image, new FrameLayout.LayoutParams(-1, -1));
        String cached = timeMonthCovers.get(month);
        if (cached != null) {
            if (!cached.isEmpty()) images.load(image, cached, 500);
            return cover;
        }
        final ApiClient client = api.snapshot();
        async(
                () ->
                        client.request(
                                "GET", "/api/media?limit=1&month=" + ApiClient.encode(month), null),
                result -> {
                    JSONArray values = array(result, "items");
                    String path =
                            values.length() == 0
                                    ? ""
                                    : values.getJSONObject(0).optString("thumbnail_url");
                    timeMonthCovers.put(month, path);
                    if (!path.isEmpty()) images.load(image, path, 500);
                },
                failure -> {});
        return cover;
    }

    private void showMonths() {
        rememberBrowsingPosition();
        if (timeMonthsDirty && timeMonthsView != null) {
            refreshTimeMonths();
            return;
        }
        currentPage = "months";
        selectedTab = "time";
        screen(false);
        if (timeMonthsView != null) {
            attachTimeView(timeMonthsView);
            return;
        }
        LinearLayout layout = Ui.column(this);
        timeMonthsView = layout;
        LinearLayout monthBar = toolbar("按月看照片", this::showTime);
        TextView refresh = action("刷新", this::refreshTimeMonths);
        refresh.setContentDescription("刷新月份回忆");
        monthBar.addView(refresh, new LinearLayout.LayoutParams(dp(48), dp(48)));
        layout.addView(monthBar);
        FrameLayout body = new FrameLayout(this);
        ListView list = new ListView(this);
        timeMonthsList = list;
        list.setDivider(null);
        list.setSelector(android.R.color.transparent);
        list.setVerticalScrollBarEnabled(false);
        body.addView(list, new FrameLayout.LayoutParams(-1, -1));
        layout.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        content.addView(layout, new FrameLayout.LayoutParams(-1, -1));
        final ApiClient monthsClient = api.snapshot();
        async(
                () -> monthsClient.request("GET", "/api/months", null),
                result -> {
                    if (timeMonthsView != layout) return;
                    JSONArray values = array(result, "months");
                    list.setAdapter(
                            new BaseAdapter() {
                                public int getCount() {
                                    return values.length();
                                }

                                public Object getItem(int p) {
                                    return values.optJSONObject(p);
                                }

                                public long getItemId(int p) {
                                    return p;
                                }

                                public View getView(
                                        int p, View view, android.view.ViewGroup parent) {
                                    if (view != null) cancelPageImages(view);
                                    JSONObject month = values.optJSONObject(p);
                                    LinearLayout wrapper = Ui.column(MainActivity.this);
                                    wrapper.setPadding(dp(20), 0, dp(20), 0);
                                    String year = month.optString("month").split("-")[0];
                                    if (p == 0
                                            || !values.optJSONObject(p - 1)
                                                    .optString("month")
                                                    .startsWith(year + "-")) {
                                        TextView yearLabel = text(year + "年", 14, Ui.MUTED);
                                        yearLabel.setPadding(0, dp(20), 0, dp(4));
                                        wrapper.addView(yearLabel);
                                    }
                                    LinearLayout row = Ui.row(MainActivity.this);
                                    row.setPadding(0, dp(12), 0, dp(12));
                                    row.setBackground(Ui.ripple(Ui.BG, 10, MainActivity.this));
                                    FrameLayout badge = timeMonthCover(month.optString("month"));
                                    row.addView(
                                            badge, new LinearLayout.LayoutParams(dp(100), dp(78)));
                                    LinearLayout labels = Ui.column(MainActivity.this);
                                    labels.setPadding(dp(14), 0, 0, 0);
                                    TextView title =
                                            text(Ui.month(month.optString("month")), 17, Ui.INK);
                                    title.setTypeface(null, Typeface.BOLD);
                                    labels.addView(title);
                                    TextView subtitle =
                                            text(
                                                    month.optInt("photos")
                                                            + " 张照片 · "
                                                            + month.optInt("videos")
                                                            + " 个视频",
                                                    12,
                                                    Ui.MUTED);
                                    subtitle.setPadding(0, dp(6), 0, 0);
                                    labels.addView(subtitle);
                                    row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
                                    row.addView(
                                            new Ui.Icon(MainActivity.this, "next", Ui.MUTED),
                                            new LinearLayout.LayoutParams(dp(16), dp(16)));
                                    wrapper.addView(row);
                                    SecondaryUi.divider(MainActivity.this, wrapper);
                                    return wrapper;
                                }
                            });
                    list.setOnItemClickListener(
                            (parent, view, position, id) ->
                                    openTimeCollection(
                                            "all",
                                            values.optJSONObject(position).optString("month"),
                                            false,
                                            "months"));
                    list.setSelectionFromTop(timeMonthsPosition, timeMonthsOffset);
                    if (values.length() == 0) {
                        list.setVisibility(View.GONE);
                        body.addView(
                                SecondaryUi.empty(
                                        this,
                                        "calendar",
                                        "还没有月份回忆",
                                        "上传照片后，会按拍摄月份整理在这里。",
                                        "回到相册",
                                        () -> {
                                            trashMode = false;
                                            showAlbum(false);
                                        }),
                                new FrameLayout.LayoutParams(-1, -1));
                    }
                },
                failure -> {
                    if (timeMonthsView != layout) return;
                    list.setVisibility(View.GONE);
                    showEmpty(
                            body,
                            failure.optString("message"),
                            "重新读取",
                            () -> {
                                timeMonthsView = null;
                                showMonths();
                            });
                });
    }

    private void showActivity() {
        rememberBrowsingPosition();
        currentPage = "activity";
        selectedTab = "activity";
        if (activityFeed == null) activityFeed = new ActivityFeedPage(this);
        activityFeed.show();
    }

    @Override
    public void showFeed(View view) {
        currentPage = "activity";
        selectedTab = "activity";
        screen(true);
        if (view.getParent() instanceof ViewGroup) ((ViewGroup) view.getParent()).removeView(view);
        content.addView(view, new FrameLayout.LayoutParams(-1, -1));
    }

    @Override
    public void openFeedMedia(JSONObject item, List<JSONObject> scope, Runnable back) {
        if (activityFeed != null) activityFeed.pause();
        activeMemoryFeature = "activity";
        openFeatureMedia(item, scope, back);
        detailReturnPage = "activity_feature";
        selectedTab = "activity";
    }

    private void disposeSocialPages() {
        if (activeCommentsDialog != null) activeCommentsDialog.dismiss();
        activeCommentsDialog = null;
        if (activityFeed != null) activityFeed.dispose();
        if (mePage != null) mePage.dispose();
        activityFeed = null;
        mePage = null;
    }

    private void showMe() {
        selectedTab = "me";
        currentPage = "me";
        screen(true);
        if (mePage == null) mePage = new MePage(this, this::myAction);
        content.addView(mePage.createView(), new FrameLayout.LayoutParams(-1, -1));
    }

    private void myAction(String key) {
        if (mePage != null) mePage.remember();
        switch (key) {
            case "family":
                showMembers();
                break;
            case "invite":
                familySettings.invite();
                break;
            case "baby":
                babySettings.show(this::accountHome);
                break;
            case "favorites":
                openTimeCollection("all", "", true, "me");
                break;
            case "trash":
                trashMode = true;
                filter = "all";
                favoritesOnly = false;
                selectedMonth = selectedDate = "";
                showAlbum(true);
                break;
            case "storage":
                if (permitted("can_manage_storage")) storageSettings.show();
                else {
                    WarmSheet details = sheet("照片存放");
                    details.body.addView(
                            SecondaryUi.row(
                                    this,
                                    "storage",
                                    "已保存",
                                    Ui.size(profile.optLong("storage_bytes")),
                                    null));
                    SecondaryUi.note(this, details.body, "照片由家庭管理员选择的服务器或云存储保管。调整位置请联系管理员。");
                    details.show();
                }
                break;
            case "account":
                accounts.showAccount();
                break;
            case "connection":
                accounts.showConnection(this::connect);
                break;
            case "about":
                showAbout();
                break;
            default:
                break;
        }
    }

    private void showAbout() {
        SettingsScreen page = new SettingsScreen(this) {};
        LinearLayout panel = page.page("about", "关于", this::showMe);
        LinearLayout brand = Ui.column(this);
        brand.setGravity(Gravity.CENTER);
        brand.setPadding(0, dp(30), 0, dp(30));
        ImageView logo = new ImageView(this);
        logo.setImageDrawable(new BrandIconDrawable(this));
        logo.setContentDescription("FAM宝贝相册");
        brand.addView(logo, new LinearLayout.LayoutParams(dp(80), dp(80)));
        TextView name = text("FAM宝贝相册", 23, Ui.INK);
        name.setGravity(Gravity.CENTER);
        name.setTypeface(null, Typeface.BOLD);
        name.setPadding(0, dp(20), 0, dp(8));
        brand.addView(name);
        String version = "";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        brand.addView(text(version, 14, Ui.MUTED));
        TextView motto = text("把成长的每一天，好好留住", 14, Ui.MUTED);
        motto.setPadding(0, dp(20), 0, 0);
        brand.addView(motto);
        panel.addView(brand);
        LinearLayout rows = page.group(panel);
        rows.addView(page.iconRow("book", "开源许可", null, () -> showLegal("开源许可", "LICENSE.txt")));
        page.divider(rows);
        rows.addView(
                page.iconRow(
                        "info",
                        "第三方组件",
                        null,
                        () -> showLegal("第三方组件", "THIRD_PARTY_NOTICES.txt")));
    }

    private void showLegal(String title, String asset) {
        SettingsScreen page = new SettingsScreen(this) {};
        LinearLayout panel = page.page("about_legal", title, this::showAbout);
        TextView body = text("", 14, Ui.INK);
        body.setLineSpacing(dp(4), 1);
        body.setTextIsSelectable(true);
        panel.addView(body);
        try (InputStream input = getAssets().open("legal/" + asset)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            body.setText(output.toString("UTF-8"));
        } catch (IOException error) {
            body.setText("说明暂时无法读取，请查看随源码提供的许可文件。");
        }
    }

    private LinearLayout menuGroup(LinearLayout parent, String title) {
        TextView label = text(title, 12, Ui.MUTED);
        label.setPadding(dp(3), dp(22), 0, dp(10));
        parent.addView(label);
        LinearLayout card = Ui.column(this);
        card.setBackground(Ui.card(this));
        parent.addView(card);
        return card;
    }

    private void groupDivider(LinearLayout parent) {
        View line = new View(this);
        line.setBackgroundColor(Ui.LINE);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(.5f));
        params.leftMargin = dp(51);
        params.rightMargin = dp(16);
        parent.addView(line, params);
    }

    private View menuRow(String icon, String title, String subtitle, Runnable click) {
        LinearLayout row = Ui.row(this);
        row.setPadding(dp(16), dp(12), dp(15), dp(12));
        row.addView(
                new Ui.Icon(this, icon, Ui.ACCENT), new LinearLayout.LayoutParams(dp(22), dp(22)));
        LinearLayout labels = Ui.column(this);
        labels.setPadding(dp(13), 0, dp(8), 0);
        labels.addView(text(title, 15, Ui.INK));
        if (!subtitle.isEmpty()) {
            TextView note = text(subtitle, 11, Ui.MUTED);
            note.setPadding(0, dp(5), 0, 0);
            note.setMaxLines(2);
            labels.addView(note);
        }
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(
                new Ui.Icon(this, "next", Ui.MUTED), new LinearLayout.LayoutParams(dp(15), dp(15)));
        row.setBackground(Ui.ripple(Color.TRANSPARENT, 18, this));
        row.setContentDescription(title);
        row.setOnClickListener(v -> click.run());
        row.setMinimumHeight(dp(66));
        return row;
    }

    private View initialAvatar(String name, int size) {
        return new FamilyAvatar(this, name);
    }

    private void showMembers() {
        boolean fromAlbum = currentPage.equals("album");
        if (fromAlbum && albumList != null) {
            savedListPosition = albumList.getFirstVisiblePosition();
            View first = albumList.getChildAt(0);
            savedListOffset = first == null ? 0 : first.getTop();
        }
        familySettings.open(fromAlbum ? () -> showAlbum(false) : this::accountHome);
    }

    @Override
    public JSONArray babies() {
        return babies;
    }

    @Override
    public void selectBaby(String id) {
        if (uploadRunning) {
            toast("这一批照片正在上传，完成后再切换宝宝。");
            return;
        }
        if (id.equals(api.childId)) {
            selectedTab = "album";
            showAlbum(false);
            return;
        }
        childrenChanged(id);
    }

    @Override
    public void childrenChanged(String preferred) {
        api.selectChild(preferred);
        connect();
    }

    private void clearChildContent() {
        dismissSecondarySheets();
        accountEpoch++;
        identityRefreshBusy = false;
        stopVideo();
        if (detailPager != null) {
            detailPager.dispose();
            detailPager = null;
        }
        disposeMemoryFeatures();
        disposeSocialPages();
        clearTimePages();
        if (activeCommentsDialog != null) activeCommentsDialog.dismiss();
        activeCommentsDialog = null;
        if (uploadProgress != null) {
            uploadProgress.dismiss();
            uploadProgress = null;
        }
        uploadRunning = false;
        pendingSave = null;
        if (pendingComparison != null && !pendingComparison.isRecycled())
            pendingComparison.recycle();
        pendingComparison = null;
        resetAlbum();
        displayOrder.clear();
        detailItems.clear();
        detailLoadGeneration++;
        detailBindGeneration++;
        savedListPosition = savedListOffset = 0;
        filter = "all";
        favoritesOnly = trashMode = false;
        selectedMonth = selectedDate = "";
        profile = new JSONObject();
        images.close();
        images = new ImageLoader(this, api);
    }

    private void showNoChildren() {
        currentPage = "children_empty";
        screen(false);
        showEmpty(
                content,
                member.optBoolean("is_family_admin") ? "给宝宝建一本自己的成长相册" : "还没有可访问的宝宝相册",
                member.optBoolean("is_family_admin") ? "添加或管理宝宝" : "使用邀请码加入",
                () -> babySettings.show(this::showNoChildren));
        TextView exit = action("退出账号", this::signOut);
        FrameLayout.LayoutParams params =
                new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        params.bottomMargin = dp(30);
        content.addView(exit, params);
    }

    private void chooseUploadChildren(ArrayList<Uri> files) {
        final int epoch = accountEpoch;
        final ArrayList<Uri> selection = new ArrayList<>(files);
        ApiClient rosterClient = api.snapshot();
        rosterClient.childId = "";
        ImageLoader roster = new ImageLoader(this, rosterClient);
        ChildChoices choices = new ChildChoices(this, babies, api.childId, false, roster);
        if (choices.isEmpty()) {
            roster.close();
            toast("目前没有可以上传的宝宝相册，请联系家人调整权限。");
            return;
        }
        WarmSheet picker = sheet("上传到宝宝相册");
        picker.onDismiss(roster::close);
        addUploadPreview(picker, selection, epoch);
        TextView count = SecondaryUi.note(this, picker.body, "已选择 " + selection.size() + " 项照片和视频");
        count.setGravity(Gravity.CENTER);
        picker.body.addView(choices.view);
        TextView targets = SecondaryUi.note(this, picker.body, "");
        targets.setGravity(Gravity.CENTER);
        TextView error = SecondaryUi.note(this, picker.body, "");
        error.setTextColor(Ui.ERROR);
        error.setVisibility(View.GONE);
        TextView start = picker.primary("开始上传", null);
        choices.onChanged(
                () -> {
                    int selectedCount = choices.count();
                    targets.setText(
                            selectedCount == 0
                                    ? "选择要放入的宝宝相册"
                                    : "已选 " + selectedCount + " 位宝宝 · 合照只保存一份");
                    start.setEnabled(selectedCount > 0);
                    error.setVisibility(View.GONE);
                });
        start.setOnClickListener(
                v -> {
                    if (!picker.isShowing() || !start.isEnabled()) return;
                    if (epoch != accountEpoch || !permitted("can_upload")) {
                        picker.dismiss();
                        toast("宝宝或权限已变化，请重新选择照片。");
                        return;
                    }
                    JSONArray selected = choices.selected();
                    if (selected.length() == 0) {
                        error.setText("请至少选择一位宝宝");
                        error.setVisibility(View.VISIBLE);
                        return;
                    }
                    start.setEnabled(false);
                    picker.dismiss();
                    uploadFiles(selection, selected, choices.names());
                });
        picker.show();
    }

    private void linkChildren(JSONObject item) {
        if (!item.optBoolean("can_edit", false)) {
            toast("当前账号不能修改这项内容");
            return;
        }
        final int epoch = accountEpoch;
        final ApiClient source = api.snapshot();
        ApiClient rosterClient = source.snapshot();
        rosterClient.childId = "";
        ImageLoader roster = new ImageLoader(this, rosterClient);
        ChildChoices choices = new ChildChoices(this, babies, "", false, roster);
        WarmSheet picker = sheet("添加到宝宝相册");
        picker.onDismiss(roster::close);
        if (choices.isEmpty()) {
            SecondaryUi.note(this, picker.body, "还没有其他可上传的宝宝相册。");
            picker.primary("知道了", picker::dismiss);
            picker.show();
            return;
        }
        LinearLayout preview = Ui.row(this);
        preview.setGravity(Gravity.CENTER);
        preview.addView(mediaThumb(item, 90));
        picker.body.addView(preview);
        SecondaryUi.note(this, picker.body, "已有归属保留，原文件只保存一份。");
        picker.body.addView(choices.view);
        TextView targets = SecondaryUi.note(this, picker.body, "");
        TextView error = SecondaryUi.note(this, picker.body, "");
        error.setTextColor(Ui.ERROR);
        error.setVisibility(View.GONE);
        boolean[] busy = {false};
        TextView save = picker.primary("添加到相册", null);
        choices.onChanged(
                () -> {
                    targets.setText(
                            choices.count() == 0 ? "选择要添加的宝宝" : "已选 " + choices.count() + " 位宝宝");
                    save.setEnabled(!busy[0] && choices.count() > 0);
                    error.setVisibility(View.GONE);
                });
        save.setOnClickListener(
                v -> {
                    if (busy[0] || !save.isEnabled() || !picker.isShowing()) return;
                    if (epoch != accountEpoch) {
                        picker.dismiss();
                        return;
                    }
                    JSONArray selected = choices.selected();
                    if (selected.length() == 0) return;
                    busy[0] = true;
                    choices.setEnabled(false);
                    save.setEnabled(false);
                    save.setText("正在添加…");
                    picker.setCancelable(false);
                    error.setVisibility(View.GONE);
                    async(
                            () ->
                                    source.request(
                                            "POST",
                                            "/api/media/"
                                                    + ApiClient.encode(item.optString("id"))
                                                    + "/children",
                                            object("child_ids", selected)),
                            result -> {
                                if (epoch != accountEpoch || !picker.isShowing()) return;
                                picker.dismiss();
                                updateItem(result);
                                timeContentChanged();
                                toast("已收进所选宝宝的相册");
                            },
                            failure -> {
                                if (epoch != accountEpoch || !picker.isShowing()) return;
                                busy[0] = false;
                                choices.setEnabled(true);
                                save.setEnabled(choices.count() > 0);
                                save.setText("添加到相册");
                                picker.setCancelable(true);
                                error.setText(failure.optString("message", "暂时未能添加，请稍后再试。"));
                                error.setVisibility(View.VISIBLE);
                            });
                });
        picker.show();
    }

    private void addUploadPreview(WarmSheet target, List<Uri> files, int epoch) {
        LinearLayout strip = Ui.row(this);
        strip.setPadding(0, dp(8), 0, dp(4));
        java.util.concurrent.atomic.AtomicBoolean closed =
                new java.util.concurrent.atomic.AtomicBoolean();
        ArrayList<CancellationSignal> cancellations = new ArrayList<>();
        ArrayList<Future<?>> jobs = new ArrayList<>();
        ArrayList<ImageView> pictures = new ArrayList<>();
        target.onDismiss(
                () -> {
                    closed.set(true);
                    for (CancellationSignal signal : cancellations) signal.cancel();
                    for (Future<?> job : jobs) job.cancel(true);
                    for (ImageView picture : pictures) picture.setImageDrawable(null);
                });
        int count = Math.min(3, files.size());
        for (int i = 0; i < count; i++) {
            Uri uri = files.get(i);
            FrameLayout frame = new FrameLayout(this);
            frame.setBackground(Ui.round(Ui.SOFT, 12, this));
            frame.setClipToOutline(true);
            frame.addView(
                    new Ui.Icon(this, "photo", Ui.ACCENT),
                    new FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER));
            ImageView picture = new ImageView(this);
            picture.setScaleType(ImageView.ScaleType.CENTER_CROP);
            picture.setContentDescription("所选照片或视频预览");
            frame.addView(picture, new FrameLayout.LayoutParams(-1, -1));
            pictures.add(picture);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(86), 1);
            if (i > 0) params.leftMargin = dp(8);
            strip.addView(frame, params);
            CancellationSignal signal = new CancellationSignal();
            cancellations.add(signal);
            try {
                jobs.add(
                        cancelableReads.submit(
                                () -> {
                                    android.graphics.Bitmap decoded = null;
                                    try {
                                        signal.throwIfCanceled();
                                        if (Build.VERSION.SDK_INT >= 29) {
                                            try {
                                                decoded =
                                                        getContentResolver()
                                                                .loadThumbnail(
                                                                        uri,
                                                                        new android.util.Size(
                                                                                256, 256),
                                                                        signal);
                                            } catch (Exception ignored) {
                                                signal.throwIfCanceled();
                                            }
                                        }
                                        if (decoded == null)
                                            decoded = readUploadPreview(uri, signal);
                                    } catch (Exception | OutOfMemoryError ignored) {
                                        // A provider without previews still keeps a useful
                                        // placeholder.
                                    }
                                    final android.graphics.Bitmap ready = decoded;
                                    handler.post(
                                            () -> {
                                                if (destroyed
                                                        || epoch != accountEpoch
                                                        || closed.get()
                                                        || !target.isShowing()) {
                                                    if (ready != null) ready.recycle();
                                                    return;
                                                }
                                                if (ready != null) picture.setImageBitmap(ready);
                                            });
                                }));
            } catch (RejectedExecutionException ignored) {
                signal.cancel();
            }
        }
        if (count > 0) target.body.addView(strip, new LinearLayout.LayoutParams(-1, -2));
    }

    private android.graphics.Bitmap readUploadPreview(Uri uri, CancellationSignal signal)
            throws IOException {
        String mime = getContentResolver().getType(uri);
        if (mime != null && !mime.startsWith("image/")) return null;
        android.graphics.BitmapFactory.Options bounds =
                new android.graphics.BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream stream = getContentResolver().openInputStream(uri)) {
            if (stream == null) return null;
            android.graphics.BitmapFactory.decodeStream(stream, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        signal.throwIfCanceled();
        android.graphics.BitmapFactory.Options options =
                new android.graphics.BitmapFactory.Options();
        options.inSampleSize = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / options.inSampleSize > 256)
            options.inSampleSize *= 2;
        android.graphics.Bitmap bitmap;
        try (InputStream stream = getContentResolver().openInputStream(uri)) {
            if (stream == null) return null;
            bitmap = android.graphics.BitmapFactory.decodeStream(stream, null, options);
        }
        if (bitmap == null) return null;
        try (InputStream stream = getContentResolver().openInputStream(uri)) {
            if (stream != null) {
                ExifInterface exif = new ExifInterface(stream);
                int orientation =
                        exif.getAttributeInt(
                                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                android.graphics.Matrix matrix = new android.graphics.Matrix();
                switch (orientation) {
                    case ExifInterface.ORIENTATION_ROTATE_90:
                        matrix.postRotate(90);
                        break;
                    case ExifInterface.ORIENTATION_ROTATE_180:
                        matrix.postRotate(180);
                        break;
                    case ExifInterface.ORIENTATION_ROTATE_270:
                        matrix.postRotate(270);
                        break;
                    case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                        matrix.setScale(-1, 1);
                        break;
                    case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                        matrix.setScale(1, -1);
                        break;
                    case ExifInterface.ORIENTATION_TRANSPOSE:
                        matrix.setScale(-1, 1);
                        matrix.postRotate(270);
                        break;
                    case ExifInterface.ORIENTATION_TRANSVERSE:
                        matrix.setScale(-1, 1);
                        matrix.postRotate(90);
                        break;
                    default:
                        break;
                }
                if (!matrix.isIdentity()) {
                    android.graphics.Bitmap rotated =
                            android.graphics.Bitmap.createBitmap(
                                    bitmap,
                                    0,
                                    0,
                                    bitmap.getWidth(),
                                    bitmap.getHeight(),
                                    matrix,
                                    true);
                    if (rotated != bitmap) bitmap.recycle();
                    bitmap = rotated;
                }
            }
        } catch (Exception ignored) {
            // Non-EXIF images can still show their sampled preview.
        }
        return bitmap;
    }

    private void showProfileEditor() {
        for (int i = 0; i < babies.length(); i++) {
            JSONObject child = babies.optJSONObject(i);
            if (child != null && child.optString("id").equals(api.childId)) {
                babySettings.edit(child);
                return;
            }
        }
        babySettings.show(this::accountHome);
    }

    private void pickUpload() {
        if (!permitted("can_upload")) {
            toast("当前账号仅可查看相册，不能上传");
            return;
        }
        if (uploadRunning) {
            toast("正在上传，请稍等");
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        uploadPickerEpoch = accountEpoch;
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {"image/*", "video/*"});
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, PICK_UPLOAD);
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null) return;
        if (request == PICK_UPLOAD) {
            if (uploadPickerEpoch != accountEpoch || !permitted("can_upload")) {
                toast("账号或权限已变化，请重新选择要上传的内容");
                return;
            }
            ArrayList<Uri> selected = new ArrayList<>();
            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++)
                    selected.add(data.getClipData().getItemAt(i).getUri());
            } else if (data.getData() != null) selected.add(data.getData());
            if (!selected.isEmpty()) chooseUploadChildren(selected);
        }
    }

    private void uploadFiles(
            ArrayList<Uri> selected, JSONArray targetChildren, String targetNames) {
        if (!permitted("can_upload")) {
            toast("当前账号不能上传");
            return;
        }
        if (uploadRunning) {
            toast("正在上传，请稍等");
            return;
        }
        if (selected.isEmpty() || targetChildren.length() == 0) return;
        final ArrayList<Uri> files = new ArrayList<>(selected);
        final JSONArray destinations;
        try {
            destinations = new JSONArray(targetChildren.toString());
        } catch (JSONException invalid) {
            toast("请重新选择要上传的宝宝相册");
            return;
        }
        final int uploadEpoch = accountEpoch;
        final ApiClient uploadClient = api.snapshot();
        uploadRunning = true;
        WarmSheet progress = sheet("正在上传");
        uploadProgress = progress;
        progress.setCancelable(false);
        addUploadPreview(progress, files, uploadEpoch);
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(files.size());
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
        bar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(Ui.SOFT));
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(-1, dp(12));
        barParams.topMargin = dp(20);
        barParams.bottomMargin = dp(8);
        progress.body.addView(bar, barParams);
        TextView processed =
                SecondaryUi.note(this, progress.body, "已处理 0 项，共 " + files.size() + " 项");
        processed.setGravity(Gravity.CENTER);
        processed.setTextColor(Ui.INK);
        TextView resultCounts =
                SecondaryUi.note(this, progress.body, "上传到“" + targetNames + "”的相册");
        resultCounts.setGravity(Gravity.CENTER);
        SecondaryUi.note(this, progress.body, "请保持当前页面打开").setGravity(Gravity.CENTER);
        progress.show();
        Window window = progress.dialog.getWindow();
        if (window != null) {
            window.setGravity(Gravity.CENTER);
            window.setLayout(
                    Math.min(dp(360), getResources().getDisplayMetrics().widthPixels - dp(36)), -2);
        }
        work.execute(
                () -> {
                    int completed = 0, failed = 0;
                    String lastError = "";
                    for (Uri source : files) {
                        if (destroyed || uploadEpoch != accountEpoch) break;
                        try {
                            String mime = getContentResolver().getType(source);
                            if (mime == null) throw new IOException("无法识别所选文件");
                            String name = displayName(source, mime);
                            String captured = captureTime(source, mime);
                            uploadClient.upload(source, name, mime, captured, destinations);
                            completed++;
                        } catch (Exception e) {
                            failed++;
                            lastError = errorText(e);
                        }
                        int current = completed + failed, succeeded = completed, errors = failed;
                        handler.post(
                                () -> {
                                    if (!destroyed
                                            && uploadEpoch == accountEpoch
                                            && progress.isShowing()) {
                                        bar.setProgress(current);
                                        processed.setText(
                                                "已处理 " + current + " 项，共 " + files.size() + " 项");
                                        resultCounts.setText(
                                                "已上传 "
                                                        + succeeded
                                                        + " 项"
                                                        + (errors > 0
                                                                ? " · " + errors + " 项未完成"
                                                                : ""));
                                    }
                                });
                    }
                    int success = completed, errors = failed;
                    String message = lastError;
                    handler.post(
                            () -> {
                                if (destroyed || uploadEpoch != accountEpoch) return;
                                uploadRunning = false;
                                uploadProgress = null;
                                progress.dismiss();
                                timeContentChanged();
                                refreshProfile();
                                resetAlbum();
                                selectedTab = "album";
                                trashMode = false;
                                filter = "all";
                                selectedMonth = "";
                                selectedDate = "";
                                favoritesOnly = false;
                                showAlbum(false);
                                WarmSheet done = sheet(errors == 0 ? "上传完成" : "部分内容未完成");
                                TextView summary =
                                        SecondaryUi.note(
                                                this, done.body, "已上传 " + success + " 项照片和视频");
                                summary.setTextSize(18);
                                summary.setTextColor(Ui.INK);
                                if (errors > 0) {
                                    SecondaryUi.note(
                                            this, done.body, "还有 " + errors + " 项未完成。" + message);
                                    SecondaryUi.note(this, done.body, "已上传的内容会保留，未完成的内容请稍后重新选择上传。");
                                }
                                done.primary("返回相册", done::dismiss);
                                done.show();
                            });
                });
    }

    private String displayName(Uri uri, String mime) {
        try (Cursor cursor =
                getContentResolver()
                        .query(
                                uri,
                                new String[] {OpenableColumns.DISPLAY_NAME},
                                null,
                                null,
                                null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (name != null && !name.isEmpty()) return name;
            }
        } catch (Exception ignored) {
        }
        return "家庭记录" + (mime.startsWith("video") ? ".mp4" : ".jpg");
    }

    private String captureTime(Uri uri, String mime) {
        // MediaStore dates are absolute instants, unlike EXIF values without a time zone.
        try (Cursor cursor =
                getContentResolver()
                        .query(
                                uri,
                                new String[] {MediaStore.MediaColumns.DATE_TAKEN},
                                null,
                                null,
                                null)) {
            if (cursor != null && cursor.moveToFirst() && cursor.getLong(0) > 0) {
                return Instant.ofEpochMilli(cursor.getLong(0)).toString();
            }
        } catch (Exception ignored) {
        }
        try {
            if (mime.startsWith("image"))
                try (InputStream stream = getContentResolver().openInputStream(uri)) {
                    ExifInterface exif = new ExifInterface(stream);
                    String value = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL);
                    String offset = exif.getAttribute("OffsetTimeOriginal");
                    if (value != null && offset != null)
                        return LocalDateTime.parse(
                                        value, DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss"))
                                .atOffset(ZoneOffset.of(offset.trim()))
                                .toString();
                }
            if (mime.startsWith("video")) {
                MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                try {
                    retriever.setDataSource(this, uri);
                    String date =
                            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE);
                    if (date != null) {
                        try {
                            return Instant.parse(date).toString();
                        } catch (Exception ignored) {
                        }
                    }
                } finally {
                    retriever.release();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void saveMedia(JSONObject item) {
        if (Build.VERSION.SDK_INT < 29
                && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            pendingSave = item;
            requestPermissions(
                    new String[] {android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 22);
            return;
        }
        toast("正在保存到手机相册…");
        final ApiClient downloadClient = api.snapshot();
        async(
                () -> {
                    String extension = mediaExtension(item);
                    String name = savedFilename(item, extension);
                    boolean isVideo = item.optString("kind").equals("video");
                    Uri collection =
                            isVideo
                                    ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                                    : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                    String mime =
                            android.webkit.MimeTypeMap.getSingleton()
                                    .getMimeTypeFromExtension(extension.substring(1));
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                    values.put(
                            MediaStore.MediaColumns.MIME_TYPE,
                            mime == null ? (isVideo ? "video/mp4" : "image/jpeg") : mime);
                    try {
                        values.put(
                                MediaStore.MediaColumns.DATE_TAKEN,
                                OffsetDateTime.parse(item.optString("captured_at"))
                                        .toInstant()
                                        .toEpochMilli());
                    } catch (Exception ignored) {
                    }
                    if (Build.VERSION.SDK_INT >= 29) {
                        values.put(
                                MediaStore.MediaColumns.RELATIVE_PATH,
                                isVideo ? "Movies/FAM宝贝相册" : "Pictures/FAM宝贝相册");
                        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
                    }
                    Uri saved = getContentResolver().insert(collection, values);
                    if (saved == null) throw new IOException("手机相册暂时无法保存文件");
                    try {
                        try (OutputStream stream = getContentResolver().openOutputStream(saved)) {
                            if (stream == null) throw new IOException("无法写入手机相册");
                            downloadClient.download(str(item, "file_url"), stream);
                        }
                        if (Build.VERSION.SDK_INT >= 29) {
                            ContentValues ready = new ContentValues();
                            ready.put(MediaStore.MediaColumns.IS_PENDING, 0);
                            getContentResolver().update(saved, ready, null, null);
                        }
                    } catch (Exception error) {
                        getContentResolver().delete(saved, null, null);
                        throw error;
                    }
                    return new JSONObject();
                },
                result -> toast("已保存到手机相册"),
                null);
    }

    private String savedFilename(JSONObject item, String extension) {
        String label = item.optString("kind").equals("video") ? "视频" : "照片";
        String date;
        try {
            date =
                    OffsetDateTime.parse(item.optString("captured_at"))
                            .atZoneSameInstant(ZoneId.of("Asia/Shanghai"))
                            .format(DateTimeFormatter.ofPattern("yyyy年M月d日 HH时mm分ss秒"));
        } catch (Exception error) {
            date = Ui.date(item.optString("album_date")).replace("/", "-");
        }
        return date + "-" + label + extension;
    }

    private String mediaExtension(JSONObject item) {
        String filename = item.optString("filename", "");
        int dot = filename.lastIndexOf('.');
        if (dot >= 0) {
            String extension = filename.substring(dot).toLowerCase(java.util.Locale.ROOT);
            if (extension.matches("\\.(jpg|jpeg|png|webp|gif|heic|heif|mp4|mov|m4v|3gp)"))
                return extension;
        }
        return item.optString("kind").equals("video") ? ".mp4" : ".jpg";
    }

    @Override
    public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        if (request == 24) {
            android.graphics.Bitmap bitmap = pendingComparison;
            pendingComparison = null;
            if (bitmap == null) return;
            if (grants.length > 0
                    && grants[0] == android.content.pm.PackageManager.PERMISSION_GRANTED)
                saveComparison(bitmap, pendingComparisonShare);
            else {
                bitmap.recycle();
                toast("保存对比图需要手机存储权限");
            }
            return;
        }
        if (request == 22) {
            JSONObject item = pendingSave;
            pendingSave = null;
            if (grants.length > 0
                    && grants[0] == android.content.pm.PackageManager.PERMISSION_GRANTED
                    && item != null) saveMedia(item);
            else toast("保存照片需要手机存储权限");
        }
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration configuration) {
        super.onConfigurationChanged(configuration);
        if (currentPage.equals("time")) refreshTimeHome();
        else if (timeHomePage != null) timeHomeDirty = true;
        if (currentPage.startsWith("feature_")) {
            if (activeMemoryFeature.equals("growth") && growthPages != null)
                growthPages.configurationChanged();
            if (activeMemoryFeature.equals("storybooks") && storybookPages != null)
                storybookPages.configurationChanged();
        }
        if (currentPage.equals("me")) showMe();
        if (currentPage.equals("activity") && activityFeed != null)
            activityFeed.configurationChanged();
        if (currentPage.equals("detail")) updateDetailLayout();
        if (currentPage.equals("time_collection") && activeTimeCollection != null)
            activeTimeCollection.relayout();
        if (albumAdapter != null && (currentPage.equals("album") || currentPage.equals("trash"))) {
            albumAdapter.notifyDataSetChanged();
        }
    }

    @Override
    public void onBackPressed() {
        if (albumAgeNavigator != null && albumAgeNavigator.collapseIfExpanded()) return;
        if (currentPage.equals("children_empty") || currentPage.equals("connecting")) {
            moveTaskToBack(true);
            return;
        }
        if (passwordChangeRequired()) {
            routePasswordChangeIfNeeded();
            moveTaskToBack(true);
            return;
        }
        if (settingsBack != null) {
            settingsBack.run();
            return;
        }
        if (currentPage.equals("detail")) {
            closeDetail();
            return;
        }
        if (currentPage.equals("time_collection") && activeTimeCollection != null) {
            activeTimeCollection.rememberPosition();
            if (activeTimeCollection.returnPage.equals("months")) showMonths();
            else if (activeTimeCollection.returnPage.equals("me")) showMe();
            else showTime();
            return;
        }
        if (currentPage.equals("members") || currentPage.equals("trash")) {
            trashMode = false;
            resetAlbum();
            showMe();
            return;
        }
        if (currentPage.equals("months")) {
            showTime();
            return;
        }
        if (!selectedTab.equals("album") && !currentPage.equals("login")) {
            selectTab("album");
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (routePasswordChangeIfNeeded()) return;
        refreshIdentity();
        if (currentPage.equals("activity") && activityFeed != null) activityFeed.resume();
        if (currentPage.startsWith("feature_")) {
            if (activeMemoryFeature.equals("growth") && growthPages != null) growthPages.resume();
            if (activeMemoryFeature.equals("storybooks") && storybookPages != null)
                storybookPages.resume();
        }
        if (video != null && videoPrepared && !videoRestorePending) {
            if (videoWantsPlay) video.start();
            else video.pause();
        }
        if (currentPage.equals("detail")) {
            applyDetailSystemBars();
            handler.removeCallbacks(detailTicker);
            handler.post(detailTicker);
            updateVideoControls();
            scheduleDetailAutoHide();
        }
    }

    private boolean identityRefreshDeferred() {
        return currentPage.equals("login")
                || currentPage.equals("connecting")
                || currentPage.equals("photo_story")
                || currentPage.equals("baby_edit")
                || currentPage.equals("storage_edit")
                || currentPage.equals("baby_join")
                || currentPage.startsWith("account_");
    }

    private void refreshIdentity() {
        if (identityRefreshBusy
                || api == null
                || api.token.isEmpty()
                || member.optString("id").isEmpty()
                || identityRefreshDeferred()) return;
        identityRefreshBusy = true;
        final ApiClient requested = api.snapshot();
        async(
                () -> {
                    ApiClient unscoped = requested.snapshot();
                    unscoped.childId = "";
                    JSONObject listing = unscoped.request("GET", "/api/children", null);
                    JSONArray children = array(listing, "items");
                    ArrayList<String> ids = new ArrayList<>();
                    for (int i = 0; i < children.length(); i++) {
                        JSONObject child = children.getJSONObject(i);
                        if (!child.optBoolean("archived")) ids.add(child.optString("id"));
                    }
                    String selected = BabyScope.choose(requested.childId, ids);
                    JSONObject result = object("children", children).put("selected", selected);
                    if (selected.equals(requested.childId))
                        result.put("member", requested.request("GET", "/api/me", null));
                    return result;
                },
                result -> {
                    identityRefreshBusy = false;
                    // Do not discard a form being edited. Each mutation is still checked by the
                    // server.
                    if (identityRefreshDeferred()) return;
                    babies = array(result, "children");
                    String selected = result.optString("selected");
                    if (!selected.equals(api.childId)) {
                        toast("宝宝访问范围已更新，正在打开可访问的相册");
                        childrenChanged(selected);
                        return;
                    }
                    JSONObject updated = result.optJSONObject("member");
                    if (updated == null) return;
                    if (!str(updated, "child_id").equals(selected)) {
                        childrenChanged(str(updated, "child_id"));
                        return;
                    }
                    boolean changed =
                            !member.optString("role").equals(updated.optString("role"))
                                    || member.optBoolean("is_family_admin")
                                            != updated.optBoolean("is_family_admin")
                                    || !String.valueOf(member.optJSONObject("permissions"))
                                            .equals(
                                                    String.valueOf(
                                                            updated.optJSONObject("permissions")));
                    member = updated;
                    rememberMember();
                    if (routePasswordChangeIfNeeded()) return;
                    if (changed) {
                        toast("家人权限已更新，正在刷新宝宝相册");
                        childrenChanged(selected);
                    }
                },
                failure -> {
                    identityRefreshBusy = false;
                    if (identityRefreshDeferred()) return;
                    if (failure.optInt("status") == 401) {
                        clearSession(false);
                        toast("登录已失效，请重新登录");
                    } else if (failure.optInt("status") == 403) {
                        childrenChanged("");
                    }
                });
    }

    private boolean permitted(String permission) {
        if (passwordChangeRequired()) return false;
        JSONObject permissions = member.optJSONObject("permissions");
        return permissions != null && permissions.optBoolean(permission, false);
    }

    private boolean passwordChangeRequired() {
        return api != null && !api.token.isEmpty() && member.optBoolean("password_change_required");
    }

    private boolean routePasswordChangeIfNeeded() {
        if (!passwordChangeRequired()) return false;
        if (!currentPage.equals("account_first_password")) {
            // Invalidate older reads before showing the restricted first-login screen.
            dismissSecondarySheets();
            accountEpoch++;
            identityRefreshBusy = false;
            disposeMemoryFeatures();
            disposeSocialPages();
            clearTimePages();
            if (uploadProgress != null) {
                uploadProgress.dismiss();
                uploadProgress = null;
            }
            uploadRunning = false;
            profile = new JSONObject();
            resetAlbum();
            displayOrder.clear();
            detailItems.clear();
            images.close();
            images = new ImageLoader(this, api);
            accounts.showRequiredPassword();
        }
        return true;
    }

    private void rememberMember() {
        getSharedPreferences("album", MODE_PRIVATE)
                .edit()
                .putString("member_name", member.optString("name", "家人"))
                .putString("username", member.optString("username", ""))
                .apply();
    }

    @Override
    public Activity activity() {
        return this;
    }

    @Override
    public ApiClient client() {
        ApiClient current = settingClient.get();
        return current != null ? current : api.snapshot();
    }

    @Override
    public JSONObject identity() {
        return member;
    }

    @Override
    public JSONObject family() {
        return profile;
    }

    @Override
    public void runSettingTask(
            SettingsScreen.Work task, SettingsScreen.Done success, SettingsScreen.Failure failure) {
        final ApiClient requestClient = api.snapshot();
        async(
                () -> {
                    settingClient.set(requestClient);
                    try {
                        return task.run();
                    } finally {
                        settingClient.remove();
                    }
                },
                success::accept,
                result -> failure.accept(result.optString("message")));
    }

    @Override
    public void openSettingPage(String key, View page, Runnable back) {
        if (!key.equals("account_first_password")
                && !key.equals("login")
                && routePasswordChangeIfNeeded()) return;
        cancelAlbumRequests();
        currentPage = key;
        screen(false);
        settingsBack = back;
        content.addView(page, new FrameLayout.LayoutParams(-1, -1));
        refreshIdentity();
    }

    @Override
    public void acceptSession(String address, JSONObject session, boolean album) throws Exception {
        String token = str(session, "token");
        JSONObject loggedMember = session.optJSONObject("member");
        if (token.isEmpty() || loggedMember == null) throw new IOException("服务器没有返回完整的账号信息，请重新登录");
        dismissSecondarySheets();
        accountEpoch++;
        disposeMemoryFeatures();
        disposeSocialPages();
        clearTimePages();
        identityRefreshBusy = false;
        if (uploadProgress != null) {
            uploadProgress.dismiss();
            uploadProgress = null;
        }
        uploadRunning = false;
        api.configure(address);
        api.saveLogin(token);
        member = loggedMember;
        if (session.optBoolean("password_change_required"))
            member.put("password_change_required", true);
        rememberMember();
        JSONObject loaded = session.optJSONObject("profile");
        if (loaded == null) loaded = session.optJSONObject("family");
        profile = loaded != null ? loaded : new JSONObject();
        images.close();
        images = new ImageLoader(this, api);
        resetAlbum();
        displayOrder.clear();
        detailItems.clear();
        trashMode = false;
        favoritesOnly = false;
        filter = "all";
        selectedMonth = "";
        selectedDate = "";
        if (routePasswordChangeIfNeeded()) return;
        if (!album) toast("账号信息已保存，其他设备需要重新登录");
        connect();
    }

    @Override
    public void accountHome() {
        if (routePasswordChangeIfNeeded()) return;
        if (api.childId.isEmpty()) {
            showNoChildren();
            return;
        }
        refreshProfile();
        showMe();
    }

    @Override
    public void signInPage() {
        showLogin();
    }

    @Override
    public void signOut() {
        clearSession(true);
    }

    private void clearSession(boolean revoke) {
        final ApiClient previous = api.snapshot();
        dismissSecondarySheets();
        accountEpoch++;
        disposeMemoryFeatures();
        disposeSocialPages();
        clearTimePages();
        identityRefreshBusy = false;
        if (uploadProgress != null) {
            uploadProgress.dismiss();
            uploadProgress = null;
        }
        uploadRunning = false;
        api.logout();
        member = new JSONObject();
        babies = new JSONArray();
        profile = new JSONObject();
        resetAlbum();
        displayOrder.clear();
        detailItems.clear();
        images.close();
        images = new ImageLoader(this, api);
        showLogin();
        if (revoke && !previous.token.isEmpty())
            work.execute(
                    () -> {
                        try {
                            previous.request("POST", "/api/logout", null);
                        } catch (Exception ignored) {
                        }
                    });
    }

    @Override
    protected void onPause() {
        cancelAlbumDrag(false);
        if (albumAgeNavigator != null) albumAgeNavigator.collapse();
        resumed = false;
        pauseMemoryFeatures();
        if (activityFeed != null) activityFeed.pause();
        handler.removeCallbacks(detailTicker);
        handler.removeCallbacks(detailAutoHide);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (video != null) {
            if (videoPrepared && !videoRestorePending) {
                videoSavedPosition = Math.max(0, video.getCurrentPosition());
                videoSavedDuration = Math.max(videoSavedDuration, video.getDuration());
            }
            video.pause();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        cancelAlbumDrag(false);
        if (albumAgeNavigator != null) albumAgeNavigator.dispose();
        dismissSecondarySheets();
        accountEpoch++;
        destroyed = true;
        disposeMemoryFeatures();
        disposeSocialPages();
        clearTimePages();
        stopVideo();
        if (detailPager != null) detailPager.dispose();
        images.close();
        work.shutdownNow();
        cancelableReads.shutdownNow();
        super.onDestroy();
    }
}
