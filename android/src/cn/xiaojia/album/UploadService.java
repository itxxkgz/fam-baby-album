package cn.xiaojia.album;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import android.text.format.Formatter;

import org.json.JSONArray;

import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class UploadService extends Service {
    private static final String CHANNEL = "album_uploads";
    private static final int NOTICE = 301;
    private static Batch current;
    private static long nextBatch;
    private static Runnable listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Batch active;

    // Batch state and the UI observer are owned by the main thread. The worker only
    // reads the selection and posts updates for its own batch.
    static final class Batch {
        final long id = ++nextBatch;
        final ArrayList<Uri> files;
        final ApiClient client;
        final JSONArray children;
        final String targets;
        final ApiClient.DownloadCancellation cancellation = new ApiClient.DownloadCancellation();
        final ArrayList<Uri> failed = new ArrayList<>();
        int processed, unconfirmed;
        long bytes, size = -1;
        boolean running = true, saving;
        String error = "";

        Batch(ArrayList<Uri> selection, ApiClient account, JSONArray children, String targets) {
            files = new ArrayList<>(selection);
            client = account.snapshot();
            this.children = children;
            this.targets = targets;
        }

        String title() {
            if (running)
                return "正在上传第 "
                        + Math.min(processed + 1, files.size())
                        + " 项，共 "
                        + files.size()
                        + " 项";
            int ok = processed - failed.size() - unconfirmed;
            String result = "已上传 " + ok + " 项";
            if (!failed.isEmpty()) result += "，" + failed.size() + " 项未完成";
            if (unconfirmed > 0) result += "，" + unconfirmed + " 项待确认";
            return result;
        }

        String detail(Context context) {
            if (!running && unconfirmed > 0) return "点此查看结果";
            if (!running) return failed.isEmpty() ? "已保存到“" + targets + "”" : "点此查看并重试";
            if (saving) return "正在保存到相册";
            if (bytes == 0) return "正在准备照片或视频";
            int percent = UploadTransfer.percent(bytes, size);
            return percent >= 0
                    ? "当前文件 " + percent + "%"
                    : "已传 " + Formatter.formatShortFileSize(context, bytes);
        }
    }

    static Batch forAccount(ApiClient account) {
        return current != null
                        && current.client.token.equals(account.token)
                        && current.client.baseUrl.equals(account.baseUrl)
                ? current
                : null;
    }

    static void observe(Runnable callback) {
        listener = callback;
    }

    static void unobserve(Runnable callback) {
        if (listener == callback) listener = null;
    }

    private static void changed() {
        if (listener != null) listener.run();
    }

    static boolean start(Context context, Batch batch) {
        if (current != null && current.running) return false;
        // Forward the picker grants for the lifetime of the service, including when
        // the activity is closed during a transfer.
        Intent intent = new Intent(context, UploadService.class);
        ClipData grants = ClipData.newRawUri("照片和视频", batch.files.get(0));
        for (int i = 1; i < batch.files.size(); i++)
            grants.addItem(new ClipData.Item(batch.files.get(i)));
        intent.putExtra("batch", batch.id);
        intent.setClipData(grants);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        context.startForegroundService(intent);
        current = batch;
        changed();
        return true;
    }

    static void forget(Context context, ApiClient account) {
        Batch batch = forAccount(account);
        if (batch == null) return;
        current = null;
        batch.cancellation.cancel();
        if (!batch.running) context.getSystemService(NotificationManager.class).cancel(NOTICE);
        changed();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(
                new NotificationChannel(CHANNEL, "相册上传", NotificationManager.IMPORTANCE_LOW));
        Batch batch = current;
        if (batch != null && intent != null && intent.getLongExtra("batch", 0) != batch.id)
            return START_NOT_STICKY;
        try {
            startForeground(NOTICE, notice(batch));
        } catch (RuntimeException unavailable) {
            if (batch != null && batch.running) {
                batch.failed.addAll(batch.files);
                batch.processed = batch.files.size();
                batch.running = false;
                batch.error = "手机暂时无法开始后台上传，请稍后再试。";
                changed();
            }
            stopSelfResult(startId);
            return START_NOT_STICKY;
        }
        if (batch == null || !batch.running) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelfResult(startId);
            return START_NOT_STICKY;
        }
        if (active == batch) return START_NOT_STICKY;
        active = batch;
        worker.execute(() -> transfer(batch, startId));
        return START_NOT_STICKY;
    }

    private Notification notice(Batch batch) {
        Intent open =
                new Intent(this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent tap =
                PendingIntent.getActivity(
                        this,
                        0,
                        open,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder n =
                new Notification.Builder(this, CHANNEL)
                        .setSmallIcon(android.R.drawable.stat_sys_upload)
                        .setVisibility(Notification.VISIBILITY_PRIVATE)
                        .setOnlyAlertOnce(true)
                        .setContentIntent(tap)
                        .setShowWhen(false)
                        .setContentTitle(batch == null ? "准备上传" : batch.title());
        if (batch != null) {
            n.setContentText(batch.detail(this))
                    .setOngoing(batch.running)
                    .setAutoCancel(!batch.running);
            if (batch.running) {
                int p = UploadTransfer.percent(batch.bytes, batch.size);
                n.setProgress(100, Math.max(0, p), p < 0 || batch.saving);
            }
        }
        return n.build();
    }

    private long fileSize(Uri uri) {
        try (Cursor c =
                getContentResolver()
                        .query(uri, new String[] {OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getLong(0);
        } catch (RuntimeException ignored) {
        }
        return -1;
    }

    private void update(Batch batch, Runnable mutation, boolean notify) {
        main.post(
                () -> {
                    if (current != batch || !batch.running) return;
                    mutation.run();
                    changed();
                    if (notify)
                        getSystemService(NotificationManager.class).notify(NOTICE, notice(batch));
                });
    }

    private void transfer(Batch batch, int startId) {
        for (Uri file : batch.files) {
            if (batch.cancellation.isCancelled()) break;
            try {
                long size = fileSize(file);
                update(
                        batch,
                        () -> {
                            batch.size = size;
                            batch.bytes = 0;
                            batch.saving = false;
                        },
                        true);
                String mime = getContentResolver().getType(file);
                if (mime == null || !(mime.startsWith("image/") || mime.startsWith("video/")))
                    throw new IOException("手机暂时无法读取这项照片或视频");
                String name = UploadMedia.displayName(this, file, mime);
                String captured = UploadMedia.captureTime(this, file, mime);
                batch.client.upload(
                        file,
                        name,
                        mime,
                        captured,
                        batch.children,
                        batch.cancellation,
                        new UploadTransfer.Listener() {
                            long lastNotice;

                            public void sent(long bytes) throws IOException {
                                batch.cancellation.check();
                                long now = SystemClock.elapsedRealtime();
                                boolean notify = now - lastNotice >= 1000;
                                if (notify) lastNotice = now;
                                update(batch, () -> batch.bytes = bytes, notify);
                            }

                            public void saving() throws IOException {
                                batch.cancellation.check();
                                update(batch, () -> batch.saving = true, true);
                            }
                        });
                update(batch, () -> batch.processed++, false);
            } catch (Exception e) {
                if (batch.cancellation.isCancelled()) break;
                int responseStatus =
                        e instanceof ApiClient.HttpError ? ((ApiClient.HttpError) e).status : 0;
                String reason =
                        e instanceof SecurityException
                                ? "手机已收回读取权限，请重新选择照片或视频。"
                                : MainActivity.errorText(e);
                update(
                        batch,
                        () -> {
                            batch.processed++;
                            // A completed request may have been saved even if the response was
                            // lost.
                            if (UploadTransfer.needsConfirmation(batch.saving, responseStatus)) {
                                batch.unconfirmed++;
                            } else {
                                batch.failed.add(file);
                                batch.error = reason;
                            }
                        },
                        false);
            }
        }
        main.post(
                () -> {
                    if (active != batch) return;
                    if (current == batch && batch.running) {
                        batch.running = false;
                        changed();
                        stopForeground(STOP_FOREGROUND_REMOVE);
                        getSystemService(NotificationManager.class).notify(NOTICE, notice(batch));
                    } else stopForeground(STOP_FOREGROUND_REMOVE);
                    active = null;
                    stopSelfResult(startId);
                });
    }

    private void interrupted() {
        Batch batch = active;
        if (batch != null && current == batch && batch.running) {
            batch.cancellation.cancel();
            int firstRemaining = batch.processed;
            if (batch.saving && firstRemaining < batch.files.size()) {
                batch.unconfirmed++;
                firstRemaining++;
            }
            batch.failed.addAll(batch.files.subList(firstRemaining, batch.files.size()));
            batch.processed = batch.files.size();
            batch.running = false;
            batch.error = "手机暂停了上传，可以继续上传剩余内容。";
            changed();
        }
        active = null;
    }

    @Override
    public void onTimeout(int startId, int type) {
        interrupted();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf(startId);
    }

    @Override
    public void onDestroy() {
        interrupted();
        worker.shutdownNow();
        super.onDestroy();
    }
}
