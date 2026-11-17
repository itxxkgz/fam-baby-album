package cn.xiaojia.album;

import android.app.Activity;
import android.content.ContentValues;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/** Runs on the activity's worker, taking ownership of the rendered bitmap. */
final class ComparisonExporter {
    static Uri write(Activity activity, Bitmap bitmap, boolean share) throws IOException {
        try {
            if (share) {
                File directory = new File(activity.getCacheDir(), "comparisons");
                if (!directory.isDirectory() && !directory.mkdirs())
                    throw new IOException("手机暂时无法准备分享图片");
                File[] stale = directory.listFiles();
                if (stale != null)
                    for (File file : stale)
                        if (file.getName().matches("comparison-[a-f0-9]{32}\\.jpg")
                                && file.lastModified()
                                        < System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000)
                            file.delete();
                File target =
                        new File(
                                directory,
                                "comparison-"
                                        + UUID.randomUUID().toString().replace("-", "")
                                        + ".jpg");
                try (OutputStream stream = new FileOutputStream(target)) {
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 96, stream))
                        throw new IOException("图片生成未完成，请重试");
                } catch (IOException error) {
                    target.delete();
                    throw error;
                }
                return new Uri.Builder()
                        .scheme("content")
                        .authority(activity.getPackageName() + ".comparison")
                        .appendPath(target.getName())
                        .build();
            }
            ContentValues values = new ContentValues();
            values.put(
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    "成长对比-"
                            + LocalDateTime.now()
                                    .format(DateTimeFormatter.ofPattern("yyyy年M月d日-HH时mm分ss秒"))
                            + ".jpg");
            values.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
            values.put(MediaStore.MediaColumns.DATE_TAKEN, System.currentTimeMillis());
            if (Build.VERSION.SDK_INT >= 29) {
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/FAM宝贝相册");
                values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            }
            Uri target =
                    activity.getContentResolver()
                            .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (target == null) throw new IOException("手机相册暂时无法保存图片");
            try {
                try (OutputStream stream = activity.getContentResolver().openOutputStream(target)) {
                    if (stream == null || !bitmap.compress(Bitmap.CompressFormat.JPEG, 96, stream))
                        throw new IOException("图片生成未完成，请重试");
                }
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues ready = new ContentValues();
                    ready.put(MediaStore.MediaColumns.IS_PENDING, 0);
                    activity.getContentResolver().update(target, ready, null, null);
                }
                return target;
            } catch (IOException | RuntimeException error) {
                activity.getContentResolver().delete(target, null, null);
                throw error;
            }
        } finally {
            bitmap.recycle();
        }
    }
}
