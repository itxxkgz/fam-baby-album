package cn.xiaojia.album;

import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.util.LruCache;
import android.util.Size;
import android.view.*;
import android.widget.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

// Gallery for devices without the system photo picker (Android 8 and up).
public final class PhoneGalleryActivity extends Activity {
    private static final int READ_LIBRARY = 1, PICK_FILES = 2, LIMIT = 500;
    private final ExecutorService worker = Executors.newFixedThreadPool(3);
    private final LruCache<Uri, Bitmap> thumbs =
            new LruCache<Uri, Bitmap>(8 * 1024 * 1024) {
                protected int sizeOf(Uri key, Bitmap image) {
                    return image.getByteCount();
                }
            };
    private final LinkedHashSet<Uri> selected = new LinkedHashSet<>();
    private final ArrayList<GalleryRow> rows = new ArrayList<>();
    private ListView list;
    private TextView next, message;
    private GalleryAdapter adapter;
    private int edge;

    static final class Item {
        final long id, time, duration;
        final boolean video;
        final Uri uri;

        Item(long id, long time, boolean video, long duration, Uri base) {
            this.id = id;
            this.time = time;
            this.video = video;
            this.duration = duration;
            this.uri = ContentUris.withAppendedId(base, id);
        }
    }

    static final class GalleryRow {
        final String date;
        final List<Item> items;

        GalleryRow(String date, List<Item> items) {
            this.date = date;
            this.items = items;
        }
    }

    private int dp(int size) {
        return Ui.dp(this, size);
    }

    private TextView button(String title, Runnable action) {
        TextView v = Ui.text(this, title, 15, Ui.ACCENT_DARK);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(12), dp(12), dp(12), dp(12));
        v.setBackground(Ui.ripple(Ui.BG, 12, this));
        v.setOnClickListener(ignored -> action.run());
        return v;
    }

    @Override
    public void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (saved != null) {
            ArrayList<Uri> previous = saved.getParcelableArrayList("selection");
            if (previous != null) selected.addAll(previous);
        }
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        getWindow()
                .getDecorView()
                .setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                                | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        edge = getResources().getDisplayMetrics().widthPixels / 3;
        LinearLayout page = Ui.column(this);
        page.setBackgroundColor(Ui.BG);
        LinearLayout top = Ui.row(this);
        top.addView(button("返回", this::finish));
        top.addView(Ui.text(this, "手机照片和视频", 19, Ui.INK));
        page.addView(top);
        message = Ui.text(this, "正在读取图库…", 15, Ui.MUTED);
        message.setGravity(Gravity.CENTER);
        page.addView(message, new LinearLayout.LayoutParams(-1, 0, 1));
        list = new ListView(this);
        list.setDivider(null);
        list.setVisibility(View.GONE);
        list.setRecyclerListener(this::cancelRow);
        adapter = new GalleryAdapter();
        list.setAdapter(adapter);
        page.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        next = button("下一步", this::finishSelection);
        next.setBackground(Ui.button(this, true));
        next.setTextColor(Color.WHITE);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(dp(14), dp(8), dp(14), dp(8));
        page.addView(next, p);
        setContentView(page);
        selectionChanged();
        if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED) load();
        else if (Build.VERSION.SDK_INT >= 33) showFileChoice("手机图库暂时不可用，可以手动选择照片和视频。");
        else if (saved == null)
            requestPermissions(
                    new String[] {android.Manifest.permission.READ_EXTERNAL_STORAGE}, READ_LIBRARY);
        else showFileChoice("需要允许读取照片和视频才能展示手机图库。");
    }

    @Override
    public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request != READ_LIBRARY) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) load();
        else showFileChoice("没有获得图库权限，仍可手动选择照片和视频。");
    }

    private void showFileChoice(String reason) {
        message.setText(reason);
        next.setEnabled(true);
        next.setAlpha(1);
        next.setText("从文件选择");
        next.setOnClickListener(
                v -> {
                    Intent i =
                            new Intent(Intent.ACTION_OPEN_DOCUMENT)
                                    .setType("*/*")
                                    .addCategory(Intent.CATEGORY_OPENABLE);
                    i.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {"image/*", "video/*"});
                    i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    try {
                        startActivityForResult(i, PICK_FILES);
                    } catch (ActivityNotFoundException e) {
                        message.setText("手机上没有可用的照片选择工具。");
                    }
                });
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == PICK_FILES && result == RESULT_OK && data != null) {
            setResult(RESULT_OK, data);
            finish();
        }
    }

    private void load() {
        worker.execute(
                () -> {
                    ArrayList<Item> items = new ArrayList<>();
                    try {
                        read(items, false);
                        read(items, true);
                        items.sort(Comparator.comparingLong((Item i) -> i.time).reversed());
                        LinkedHashMap<LocalDate, List<Item>> days = new LinkedHashMap<>();
                        for (Item i : items) {
                            LocalDate date =
                                    i.time <= 0
                                            ? null
                                            : Instant.ofEpochMilli(i.time)
                                                    .atZone(ZoneId.systemDefault())
                                                    .toLocalDate();
                            days.computeIfAbsent(date, k -> new ArrayList<>()).add(i);
                        }
                        ArrayList<GalleryRow> grouped = new ArrayList<>();
                        for (Map.Entry<LocalDate, List<Item>> day : days.entrySet()) {
                            List<Item> photos = day.getValue();
                            grouped.add(
                                    new GalleryRow(
                                            day.getKey() == null
                                                    ? "日期待确认"
                                                    : Ui.date(day.getKey().toString()),
                                            photos));
                            for (int i = 0; i < photos.size(); i += 3)
                                grouped.add(
                                        new GalleryRow(
                                                null,
                                                photos.subList(i, Math.min(i + 3, photos.size()))));
                        }
                        runOnUiThread(
                                () -> {
                                    if (isDestroyed() || isFinishing()) return;
                                    if (grouped.isEmpty()) {
                                        showFileChoice("手机图库里还没有照片或视频。");
                                        return;
                                    }
                                    rows.addAll(grouped);
                                    message.setVisibility(View.GONE);
                                    list.setVisibility(View.VISIBLE);
                                    adapter.notifyDataSetChanged();
                                });
                    } catch (RuntimeException error) {
                        runOnUiThread(
                                () -> {
                                    if (!isDestroyed()) showFileChoice("暂时无法读取手机图库。");
                                });
                    }
                });
    }

    private void read(List<Item> items, boolean video) {
        Uri base =
                video
                        ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                        : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        String[] cols =
                video
                        ? new String[] {"_id", "datetaken", "date_added", "duration"}
                        : new String[] {"_id", "datetaken", "date_added"};
        try (Cursor c = getContentResolver().query(base, cols, null, null, null)) {
            if (c == null) throw new IllegalStateException();
            while (c.moveToNext()) {
                long taken = c.getLong(1);
                items.add(
                        new Item(
                                c.getLong(0),
                                taken > 0 ? taken : c.getLong(2) * 1000L,
                                video,
                                video ? c.getLong(3) : 0,
                                base));
            }
        }
    }

    private void selectionChanged() {
        next.setEnabled(!selected.isEmpty());
        next.setAlpha(selected.isEmpty() ? 0.5f : 1f);
        next.setText(selected.isEmpty() ? "下一步" : "下一步（" + selected.size() + "）");
    }

    private void toggle(List<Item> items) {
        boolean all = true;
        for (Item i : items)
            if (!selected.contains(i.uri)) {
                all = false;
                break;
            }
        for (Item i : items) {
            if (all) selected.remove(i.uri);
            else if (!selected.contains(i.uri) && selected.size() >= LIMIT) {
                Toast.makeText(this, "一次最多选 " + LIMIT + " 项，其余可以分批上传", Toast.LENGTH_SHORT).show();
                break;
            } else selected.add(i.uri);
        }
        selectionChanged();
        adapter.notifyDataSetChanged();
    }

    private void finishSelection() {
        if (selected.isEmpty()) return;
        Iterator<Uri> it = selected.iterator();
        ClipData clips = ClipData.newRawUri("所选照片和视频", it.next());
        while (it.hasNext()) clips.addItem(new ClipData.Item(it.next()));
        Intent result = new Intent().addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        result.setClipData(clips);
        setResult(RESULT_OK, result);
        finish();
    }

    private void cancelRow(View v) {
        if (v.getTag() instanceof List<?>)
            for (Object f : (List<?>) v.getTag()) ((Future<?>) f).cancel(false);
        v.setTag(null);
    }

    private class GalleryAdapter extends BaseAdapter {
        public int getCount() {
            return rows.size();
        }

        public GalleryRow getItem(int i) {
            return rows.get(i);
        }

        public long getItemId(int i) {
            return i;
        }

        public View getView(int position, View old, ViewGroup parent) {
            if (old != null) cancelRow(old);
            LinearLayout row =
                    old instanceof LinearLayout
                            ? (LinearLayout) old
                            : Ui.row(PhoneGalleryActivity.this);
            row.removeAllViews();
            GalleryRow group = getItem(position);
            if (group.date != null) {
                TextView date = Ui.text(PhoneGalleryActivity.this, group.date, 15, Ui.INK);
                date.setPadding(dp(12), 0, 0, 0);
                row.addView(date, new LinearLayout.LayoutParams(0, -2, 1));
                boolean all = true;
                for (Item i : group.items)
                    if (!selected.contains(i.uri)) {
                        all = false;
                        break;
                    }
                row.addView(button(all ? "取消全选" : "全选", () -> toggle(group.items)));
            } else {
                ArrayList<Future<?>> jobs = new ArrayList<>();
                row.setTag(jobs);
                for (Item i : group.items)
                    row.addView(tile(i, jobs), new LinearLayout.LayoutParams(edge, edge));
            }
            return row;
        }
    }

    private View tile(Item item, List<Future<?>> jobs) {
        FrameLayout tile = new FrameLayout(this);
        tile.setPadding(dp(1), dp(1), dp(1), dp(1));
        ImageView image = new ImageView(this);
        image.setBackgroundColor(Ui.SOFT);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        tile.addView(image, new FrameLayout.LayoutParams(-1, -1));
        Bitmap cached = thumbs.get(item.uri);
        if (cached != null) image.setImageBitmap(cached);
        else
            jobs.add(
                    worker.submit(
                            () -> {
                                try {
                                    Bitmap b;
                                    if (Build.VERSION.SDK_INT >= 29)
                                        b =
                                                getContentResolver()
                                                        .loadThumbnail(
                                                                item.uri,
                                                                new Size(edge, edge),
                                                                null);
                                    else if (item.video)
                                        b =
                                                MediaStore.Video.Thumbnails.getThumbnail(
                                                        getContentResolver(),
                                                        item.id,
                                                        MediaStore.Video.Thumbnails.MINI_KIND,
                                                        null);
                                    else
                                        b =
                                                MediaStore.Images.Thumbnails.getThumbnail(
                                                        getContentResolver(),
                                                        item.id,
                                                        MediaStore.Images.Thumbnails.MINI_KIND,
                                                        null);
                                    if (b != null && !isDestroyed()) {
                                        thumbs.put(item.uri, b);
                                        runOnUiThread(() -> image.setImageBitmap(b));
                                    }
                                } catch (Exception ignored) {
                                }
                            }));
        boolean checked = selected.contains(item.uri);
        TextView mark = Ui.text(this, checked ? "✓" : "○", 18, Color.WHITE);
        mark.setGravity(Gravity.CENTER);
        mark.setBackground(Ui.round(checked ? Ui.ACCENT : 0x88000000, 20, this));
        FrameLayout.LayoutParams badge =
                new FrameLayout.LayoutParams(dp(26), dp(26), Gravity.TOP | Gravity.RIGHT);
        badge.setMargins(0, dp(5), dp(5), 0);
        tile.addView(mark, badge);
        if (item.video) {
            long s = item.duration / 1000;
            TextView duration =
                    Ui.text(
                            this,
                            "▶ " + String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60),
                            12,
                            Color.WHITE);
            duration.setPadding(dp(4), 0, dp(4), 0);
            duration.setBackgroundColor(0x99000000);
            tile.addView(
                    duration, new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.RIGHT));
        }
        tile.setContentDescription((item.video ? "视频" : "照片") + (checked ? "，已选" : "，未选"));
        tile.setOnClickListener(v -> toggle(Collections.singletonList(item)));
        return tile;
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putParcelableArrayList("selection", new ArrayList<>(selected));
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }
}
