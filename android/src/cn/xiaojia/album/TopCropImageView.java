package cn.xiaojia.album;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;

/** Fills an album square while keeping the top of a portrait in view. */
final class TopCropImageView extends ImageView {
    TopCropImageView(Context context) {
        super(context);
        setScaleType(ScaleType.MATRIX);
    }

    @Override
    public void setImageDrawable(Drawable drawable) {
        super.setImageDrawable(drawable);
        updateCrop();
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        updateCrop();
    }

    private void updateCrop() {
        Drawable drawable = getDrawable();
        if (drawable == null || getWidth() <= 0 || getHeight() <= 0) return;
        int imageWidth = drawable.getIntrinsicWidth();
        int imageHeight = drawable.getIntrinsicHeight();
        if (imageWidth <= 0 || imageHeight <= 0) return;
        int availableWidth = getWidth() - getPaddingLeft() - getPaddingRight();
        int availableHeight = getHeight() - getPaddingTop() - getPaddingBottom();
        float scale =
                Math.max(
                        (float) availableWidth / imageWidth, (float) availableHeight / imageHeight);
        Matrix matrix = new Matrix();
        matrix.setScale(scale, scale);
        matrix.postTranslate((availableWidth - imageWidth * scale) / 2f, 0);
        setImageMatrix(matrix);
    }
}
