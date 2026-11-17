package cn.xiaojia.album;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/** Read-only access to an explicitly shared, rendered comparison image. */
public final class ComparisonProvider extends ContentProvider {
    @Override
    public boolean onCreate() {
        return true;
    }

    private File image(Uri uri) {
        String name = uri.getLastPathSegment();
        if (uri.getPathSegments().size() != 1
                || name == null
                || !name.matches("comparison-[a-f0-9]{32}\\.jpg"))
            throw new IllegalArgumentException("这张对比图暂时无法读取");
        return new File(new File(getContext().getCacheDir(), "comparisons"), name);
    }

    @Override
    public String getType(Uri uri) {
        image(uri);
        return "image/jpeg";
    }

    @Override
    public Cursor query(
            Uri uri, String[] projection, String selection, String[] arguments, String order) {
        File file = image(uri);
        String[] columns =
                projection == null
                        ? new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}
                        : projection;
        MatrixCursor result = new MatrixCursor(columns);
        Object[] values = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) values[i] = "成长对比.jpg";
            else if (OpenableColumns.SIZE.equals(columns[i])) values[i] = file.length();
        }
        result.addRow(values);
        return result;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("对比图仅供查看");
        return ParcelFileDescriptor.open(image(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        throw new UnsupportedOperationException();
    }
}
