package cn.xiaojia.album;

import android.content.Context;
import android.database.Cursor;
import android.media.ExifInterface;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.MediaStore;
import android.provider.OpenableColumns;

import java.io.InputStream;
import java.time.*;
import java.time.format.DateTimeFormatter;

final class UploadMedia {
    static String displayName(Context context, Uri uri, String mime) {
        try (Cursor cursor =
                context.getContentResolver()
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

    static String captureTime(Context context, Uri uri, String mime) {
        // MediaStore dates are absolute instants, unlike EXIF values without a time zone.
        try (Cursor cursor =
                context.getContentResolver()
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
                try (InputStream stream = context.getContentResolver().openInputStream(uri)) {
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
                    retriever.setDataSource(context, uri);
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
}
