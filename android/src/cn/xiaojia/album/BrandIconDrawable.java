package cn.xiaojia.album;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/** The same approved FAM artwork as the launcher, shared by every in-app brand mark. */
final class BrandIconDrawable extends Drawable {
    private static Bitmap sharedArtwork;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final BitmapShader shader;
    private final RectF source;
    private final RectF destination = new RectF();
    private final Matrix matrix = new Matrix();

    BrandIconDrawable(Context context) {
        Bitmap bitmap = artwork(context);
        shader = new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        source = new RectF(0, 0, bitmap.getWidth(), bitmap.getHeight());
        // Launcher artwork reserves adaptive-mask margins; small in-app marks use more of the tile.
        source.inset(bitmap.getWidth() * .08f, bitmap.getHeight() * .08f);
        paint.setShader(shader);
    }

    private static synchronized Bitmap artwork(Context context) {
        if (sharedArtwork == null) {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inScaled = false;
            options.inSampleSize = 2;
            sharedArtwork =
                    BitmapFactory.decodeResource(
                            context.getResources(), R.drawable.fam_launcher_art, options);
        }
        return sharedArtwork;
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        destination.set(bounds);
        matrix.setRectToRect(source, destination, Matrix.ScaleToFit.FILL);
        shader.setLocalMatrix(matrix);
    }

    @Override
    public void draw(Canvas canvas) {
        float radius = Math.min(destination.width(), destination.height()) * .23f;
        canvas.drawRoundRect(destination, radius, radius, paint);
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter filter) {
        paint.setColorFilter(filter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
