package cn.xiaojia.album;

import android.content.Context;
import android.graphics.*;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.*;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/** Account-scoped image loading, with cancellable requests and immediate previews. */
final class ImageLoader {
    // Bounded striped locks are shared by every loader, including an old loader
    // winding down while the same account opens a new one.
    private static final ReentrantLock[] FILE_LOCKS = new ReentrantLock[256];
    private static final ReentrantLock TRIM_LOCK = new ReentrantLock();
    private static long lastTrim;

    static {
        for (int i = 0; i < FILE_LOCKS.length; i++) FILE_LOCKS[i] = new ReentrantLock();
    }

    private final ApiClient api;
    private final File cacheDirectory;
    private final ThreadPoolExecutor executor =
            new ThreadPoolExecutor(3, 3, 0, TimeUnit.SECONDS, new PriorityBlockingQueue<>());
    private final ThreadPoolExecutor prefetchExecutor =
            new ThreadPoolExecutor(1, 1, 20, TimeUnit.SECONDS, new ArrayBlockingQueue<>(4));
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Set<Request> requests = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, Prefetch> prefetching = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    private volatile boolean closed;
    private final LruCache<String, Bitmap> memory =
            new LruCache<String, Bitmap>(
                    (int)
                            Math.max(
                                    24L * 1024 * 1024,
                                    Math.min(
                                            64L * 1024 * 1024,
                                            Runtime.getRuntime().maxMemory() / 8))) {
                @Override
                protected int sizeOf(String key, Bitmap bitmap) {
                    return bitmap.getByteCount();
                }
            };

    private static final class Request {
        final WeakReference<ImageView> view;
        final ApiClient.DownloadCancellation cancellation = new ApiClient.DownloadCancellation();
        volatile boolean active = true;
        volatile Runnable task;

        Request(ImageView view) {
            this.view = new WeakReference<>(view);
        }
    }

    private final class LoadTask implements Runnable, Comparable<LoadTask> {
        final Request request;
        final Runnable work;
        final int priority;
        final long order = sequence.incrementAndGet();

        LoadTask(Request request, boolean viewer, Runnable work) {
            this.request = request;
            this.work = work;
            priority = viewer ? 0 : 1;
        }

        @Override
        public int compareTo(LoadTask other) {
            int difference = Integer.compare(priority, other.priority);
            return difference != 0 ? difference : Long.compare(other.order, order);
        }

        @Override
        public void run() {
            try {
                if (available(request)) work.run();
            } finally {
                request.task = null;
                requests.remove(request);
            }
        }
    }

    private final class Prefetch implements Runnable {
        final String cacheKey, path;
        final int size;
        final ApiClient.DownloadCancellation cancellation = new ApiClient.DownloadCancellation();

        Prefetch(String cacheKey, String path, int size) {
            this.cacheKey = cacheKey;
            this.path = path;
            this.size = size;
        }

        @Override
        public void run() {
            try {
                quietlyRead(path, size, cancellation);
            } finally {
                prefetching.remove(cacheKey, this);
            }
        }

        void cancel() {
            cancellation.cancel();
            prefetchExecutor.remove(this);
            prefetching.remove(cacheKey, this);
        }
    }

    ImageLoader(Context context, ApiClient api) {
        this.api = api.snapshot();
        cacheDirectory = new File(context.getCacheDir(), "photos");
        cacheDirectory.mkdirs();
    }

    private String key(String path, int size) {
        return api.cacheScope() + "|" + path + "|" + size;
    }

    private boolean available(Request request) {
        return !closed && request.active && request.view.get() != null;
    }

    private Request begin(ImageView view) {
        cancel(view);
        Request request = new Request(view);
        view.setTag(request);
        return request;
    }

    /** Invalidates callbacks immediately; socket shutdown happens off the UI thread. */
    void cancel(ImageView view) {
        if (view == null) return;
        Object previous = view.getTag();
        if (!(previous instanceof Request)) return;
        cancel((Request) previous);
        view.setTag(null);
    }

    private void cancel(Request request) {
        request.active = false;
        request.cancellation.cancel();
        Runnable task = request.task;
        if (task != null) executor.remove(task);
        requests.remove(request);
    }

    void load(ImageView view, String path, int maxSize) {
        if (closed) return;
        Request request = begin(view);
        view.setImageDrawable(null);
        if (path == null || path.isEmpty()) return;
        Bitmap cached = memory.get(key(path, maxSize));
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }
        submit(
                request,
                false,
                () -> {
                    Bitmap bitmap = quietlyRead(path, maxSize, request.cancellation);
                    deliver(request, bitmap, null, null);
                });
    }

    void loadProgressive(
            ImageView view,
            String previewPath,
            String originalPath,
            int maxSize,
            Runnable onReady,
            Runnable onError) {
        if (closed) return;
        Request request = begin(view);
        Bitmap cached = memory.get(key(originalPath, maxSize));
        if (cached != null) {
            view.setImageBitmap(cached);
            if (onReady != null) onReady.run();
            return;
        }
        Bitmap preview = previewPath == null ? null : memory.get(key(previewPath, 900));
        if (preview != null) view.setImageBitmap(preview);
        else view.setImageDrawable(null);
        submit(
                request,
                true,
                () -> {
                    if (preview == null && previewPath != null && !previewPath.isEmpty()) {
                        Bitmap quick = quietlyRead(previewPath, 900, request.cancellation);
                        if (quick != null) deliver(request, quick, null, null);
                    }
                    if (!available(request)) return;
                    Bitmap full = quietlyRead(originalPath, maxSize, request.cancellation);
                    deliver(request, full, onReady, onError);
                });
    }

    void prefetch(String path, int maxSize) {
        if (closed || path == null || path.isEmpty()) return;
        String cacheKey = key(path, maxSize);
        if (memory.get(cacheKey) != null) return;
        Prefetch task = new Prefetch(cacheKey, path, maxSize);
        if (prefetching.putIfAbsent(cacheKey, task) != null) return;
        // Discarded work must remove its own deduplication entry. A standard
        // discard policy leaves a key stuck forever and prevents later retries.
        synchronized (prefetchExecutor) {
            while (!closed) {
                try {
                    prefetchExecutor.execute(task);
                    return;
                } catch (RejectedExecutionException ignored) {
                    if (prefetchExecutor.isShutdown()) break;
                    Runnable oldest = prefetchExecutor.getQueue().poll();
                    if (oldest instanceof Prefetch) ((Prefetch) oldest).cancel();
                }
            }
        }
        task.cancel();
    }

    private void submit(Request request, boolean viewer, Runnable work) {
        LoadTask task = new LoadTask(request, viewer, work);
        request.task = task;
        requests.add(request);
        if (closed) {
            cancel(request);
            return;
        }
        try {
            executor.execute(task);
        } catch (RejectedExecutionException ignored) {
            cancel(request);
        }
    }

    private void deliver(Request request, Bitmap bitmap, Runnable ready, Runnable error) {
        if (!available(request)) return;
        handler.post(
                () -> {
                    ImageView view = request.view.get();
                    if (!available(request) || view == null || view.getTag() != request) return;
                    if (bitmap != null) {
                        view.setImageBitmap(bitmap);
                        if (ready != null) ready.run();
                    } else if (error != null) error.run();
                });
    }

    private boolean cancelled(ApiClient.DownloadCancellation cancellation) {
        return closed || cancellation.isCancelled() || Thread.currentThread().isInterrupted();
    }

    private Bitmap quietlyRead(
            String path, int maxSize, ApiClient.DownloadCancellation cancellation) {
        if (cancelled(cancellation) || path == null || path.isEmpty()) return null;
        try {
            Bitmap result = read(path, maxSize, cancellation);
            if (result != null && !cancelled(cancellation)) trimDisk();
            return result;
        } catch (Exception | OutOfMemoryError ignored) {
            return null;
        }
    }

    private static ReentrantLock fileLock(File file) {
        return FILE_LOCKS[file.getAbsolutePath().hashCode() & (FILE_LOCKS.length - 1)];
    }

    private Bitmap read(String path, int maxSize, ApiClient.DownloadCancellation cancellation)
            throws Exception {
        String cacheKey = key(path, maxSize);
        Bitmap cached = memory.get(cacheKey);
        if (cached != null) return cached;
        String fileKey =
                hex(
                        MessageDigest.getInstance("SHA-256")
                                .digest(
                                        (api.cacheScope() + "|" + path)
                                                .getBytes(StandardCharsets.UTF_8)));
        File file = new File(cacheDirectory, fileKey);
        ReentrantLock lock = fileLock(file);
        // A cancelled waiter must not occupy a foreground worker until another
        // account instance or a prefetch finishes a slow request.
        while (!lock.tryLock(100, TimeUnit.MILLISECONDS)) {
            if (cancelled(cancellation)) return null;
        }
        try {
            if (cancelled(cancellation)) return null;
            cached = memory.get(cacheKey);
            if (cached != null) return cached;
            if (!file.exists()) {
                File temporary = File.createTempFile(fileKey + "-", ".part", cacheDirectory);
                try {
                    try (OutputStream output = new FileOutputStream(temporary)) {
                        api.download(path, output, cancellation);
                    }
                    if (cancelled(cancellation)) return null;
                    if (!temporary.renameTo(file)) throw new IOException("照片暂时没有读取完成");
                } finally {
                    if (temporary.exists()) temporary.delete();
                }
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                file.delete();
                throw new IOException("照片暂时没有读取完成");
            }
            if (cancelled(cancellation)) return null;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 1;
            int target = Math.max(128, Math.min(maxSize, 3200));
            // Leave room for four decodes, EXIF rotation and the displayed pages.
            long pixels =
                    Math.max(
                            1_000_000L,
                            Math.min(8_000_000L, Runtime.getRuntime().maxMemory() / 48));
            while (Math.max(bounds.outWidth, bounds.outHeight) / options.inSampleSize > target
                    || (long) bounds.outWidth
                                    * bounds.outHeight
                                    / ((long) options.inSampleSize * options.inSampleSize)
                            > pixels) options.inSampleSize *= 2;
            Bitmap bitmap = BitmapFactory.decodeFile(file.getPath(), options);
            if (bitmap == null) return null;
            try {
                android.media.ExifInterface exif = new android.media.ExifInterface(file.getPath());
                int orientation =
                        exif.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1);
                Matrix transform = new Matrix();
                switch (orientation) {
                    case 2:
                        transform.postScale(-1, 1);
                        break;
                    case 3:
                        transform.postRotate(180);
                        break;
                    case 4:
                        transform.postScale(1, -1);
                        break;
                    case 5:
                        transform.postRotate(90);
                        transform.postScale(-1, 1);
                        break;
                    case 6:
                        transform.postRotate(90);
                        break;
                    case 7:
                        transform.postRotate(270);
                        transform.postScale(-1, 1);
                        break;
                    case 8:
                        transform.postRotate(270);
                        break;
                    default:
                        break;
                }
                if (!transform.isIdentity()) {
                    Bitmap rotated =
                            Bitmap.createBitmap(
                                    bitmap,
                                    0,
                                    0,
                                    bitmap.getWidth(),
                                    bitmap.getHeight(),
                                    transform,
                                    true);
                    if (rotated != bitmap) bitmap.recycle();
                    bitmap = rotated;
                }
            } catch (IOException ignored) {
            }
            synchronized (memory) {
                if (cancelled(cancellation)) {
                    bitmap.recycle();
                    return null;
                }
                memory.put(cacheKey, bitmap);
            }
            file.setLastModified(System.currentTimeMillis());
            return bitmap;
        } finally {
            lock.unlock();
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) result.append(String.format("%02x", value));
        return result.toString();
    }

    private static final class CacheEntry {
        final File file;
        final long modified;

        CacheEntry(File file) {
            this.file = file;
            modified = file.lastModified();
        }
    }

    private void trimDisk() {
        if (!TRIM_LOCK.tryLock()) return;
        try {
            long now = System.nanoTime();
            if (lastTrim != 0 && now - lastTrim < TimeUnit.SECONDS.toNanos(30)) return;
            lastTrim = now;
            File[] files = cacheDirectory.listFiles((directory, name) -> !name.endsWith(".part"));
            if (files == null) return;
            long bytes = 0;
            ArrayList<CacheEntry> entries = new ArrayList<>(files.length);
            for (File file : files) {
                bytes += file.length();
                entries.add(new CacheEntry(file));
            }
            if (bytes < 256L * 1024 * 1024) return;
            // Snapshot timestamps: a concurrent decode may update modification
            // times during sorting, which otherwise breaks comparator ordering.
            entries.sort((a, b) -> Long.compare(a.modified, b.modified));
            for (CacheEntry entry : entries) {
                if (bytes < 192L * 1024 * 1024) break;
                ReentrantLock lock = fileLock(entry.file);
                if (!lock.tryLock()) continue;
                try {
                    if (entry.file.lastModified() != entry.modified) continue;
                    long size = entry.file.length();
                    if (entry.file.delete()) bytes -= size;
                } finally {
                    lock.unlock();
                }
            }
        } finally {
            TRIM_LOCK.unlock();
        }
    }

    void close() {
        closed = true;
        handler.removeCallbacksAndMessages(null);
        for (Request request : requests) cancel(request);
        for (Prefetch task : prefetching.values()) task.cancel();
        executor.shutdownNow();
        prefetchExecutor.shutdownNow();
        requests.clear();
        prefetching.clear();
        synchronized (memory) {
            memory.evictAll();
        }
    }
}
