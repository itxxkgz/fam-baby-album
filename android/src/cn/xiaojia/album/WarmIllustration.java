package cn.xiaojia.album;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** Small original illustrations; kept vector-sharp at every display density. */
final class WarmIllustration extends View {
    private final String kind;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    WarmIllustration(Context context, String kind) {
        super(context);
        this.kind = kind == null ? "home" : kind;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    private void fill(int color) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
    }

    private void line(int color, float width) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(color);
        paint.setStrokeWidth(width);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }

    private void photo(Canvas c, float x, float y, float angle) {
        c.save();
        c.rotate(angle, x + 22, y + 19);
        fill(0xFFEEBE8B);
        c.drawRoundRect(x + 1, y + 2, x + 46, y + 41, 4, 4, paint);
        fill(0xFFFFFBF1);
        c.drawRoundRect(x, y, x + 45, y + 39, 4, 4, paint);
        fill(0xFFF8D7AC);
        c.drawRoundRect(x + 5, y + 5, x + 40, y + 32, 2, 2, paint);
        fill(0xFFF5BA59);
        c.drawCircle(x + 31, y + 12, 4, paint);
        Path hills = new Path();
        hills.moveTo(x + 5, y + 31);
        hills.lineTo(x + 15, y + 18);
        hills.quadTo(x + 17, y + 16, x + 19, y + 19);
        hills.lineTo(x + 25, y + 25);
        hills.lineTo(x + 32, y + 21);
        hills.lineTo(x + 40, y + 31);
        hills.close();
        fill(0xFFE6AA78);
        c.drawPath(hills, paint);
        c.restore();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth() - getPaddingLeft() - getPaddingRight();
        float h = getHeight() - getPaddingTop() - getPaddingBottom();
        float scale = Math.min(w / 140, h / 112);
        if (scale <= 0) return;
        canvas.save();
        canvas.translate(
                getPaddingLeft() + (w - 140 * scale) / 2, getPaddingTop() + (h - 112 * scale) / 2);
        canvas.scale(scale, scale);
        fill(0xFFFFE6D2);
        canvas.drawOval(5, 91, 136, 109, paint);
        boolean storage = kind.equals("storage");
        // Soft warm leaves support the house without becoming a competing illustration.
        fill(0xFFE4C690);
        canvas.drawOval(8, 69, 22, 95, paint);
        canvas.drawOval(17, 61, 30, 94, paint);
        fill(0xFFF1D7A9);
        canvas.drawOval(111, 63, 126, 94, paint);
        canvas.drawOval(122, 75, 134, 98, paint);
        fill(0xFFE6B38C);
        canvas.drawRoundRect(36, 38, 99, 94, 5, 5, paint);
        fill(0xFFFFE5BE);
        canvas.drawRoundRect(32, 35, 94, 92, 5, 5, paint);
        fill(0xFFF8C68D);
        canvas.drawRoundRect(83, 12, 94, 33, 2, 2, paint);
        Path roof = new Path();
        roof.moveTo(22, 42);
        roof.lineTo(59, 12);
        roof.quadTo(63, 9, 67, 12);
        roof.lineTo(106, 42);
        roof.quadTo(110, 47, 105, 52);
        roof.quadTo(102, 55, 98, 52);
        roof.lineTo(63, 25);
        roof.lineTo(30, 52);
        roof.quadTo(24, 56, 20, 50);
        roof.quadTo(18, 46, 22, 42);
        roof.close();
        fill(0xFFDC865F);
        canvas.drawPath(roof, paint);
        line(0xFFEDAA7D, 3);
        canvas.drawLine(25, 44, 62, 15, paint);
        canvas.drawLine(64, 15, 103, 45, paint);
        fill(0xFFF7F0DA);
        canvas.drawRoundRect(47, 45, 74, 74, 3, 3, paint);
        fill(0xFFD6BCA1);
        canvas.drawRoundRect(51, 49, 70, 70, 1, 1, paint);
        line(0xFFFFF9EC, 3);
        canvas.drawLine(60.5f, 49, 60.5f, 70, paint);
        canvas.drawLine(51, 59, 70, 59, paint);
        if (storage) {
            photo(canvas, 52, 51, -13);
            photo(canvas, 74, 45, 12);
            fill(0xFFE9A35D);
            canvas.drawRoundRect(26, 74, 117, 104, 7, 7, paint);
            fill(0xFFFFD593);
            canvas.drawRoundRect(22, 71, 112, 101, 7, 7, paint);
            fill(0xFFF8BC70);
            canvas.drawRoundRect(22, 71, 112, 80, 4, 4, paint);
            fill(0xFFD89452);
            canvas.drawRoundRect(58, 84, 78, 90, 3, 3, paint);
        } else {
            fill(0xFFE9B77E);
            canvas.drawRoundRect(49, 78, 67, 93, 3, 3, paint);
            photo(canvas, 82, 67, 12);
        }
        line(0xFFE9AD4A, 3);
        canvas.drawLine(14, 29, 9, 25, paint);
        canvas.drawLine(21, 22, 19, 16, paint);
        canvas.drawLine(8, 39, 3, 38, paint);
        canvas.restore();
    }
}
