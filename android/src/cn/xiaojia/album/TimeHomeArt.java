package cn.xiaojia.album;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;
import android.widget.FrameLayout;

/** Original small paper-and-sun details behind real family photographs. */
final class TimeHomeArt extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final boolean pair;

    TimeHomeArt(Context context, String kind) {
        super(context);
        pair = kind.equals("growth_pair");
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    private void fill(int color) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
    }

    private void stroke(int color, float width) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(color);
        paint.setStrokeWidth(width);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float scale = Math.min(getWidth() / 200f, getHeight() / 174f);
        canvas.save();
        canvas.translate((getWidth() - 200 * scale) / 2, (getHeight() - 174 * scale) / 2);
        canvas.scale(scale, scale);
        fill(0x55EBC995);
        canvas.drawOval(9, 139, 197, 167, paint);
        fill(0xFFE1CB92);
        leaf(canvas, 17, 110, -25);
        leaf(canvas, 27, 126, 16);
        leaf(canvas, 178, 129, 38);
        fill(0xFFE8D9B0);
        leaf(canvas, 13, 132, -48);
        leaf(canvas, 187, 145, 51);
        leaf(canvas, 170, 143, -5);
        stroke(0xFFE8AB45, 3);
        canvas.drawLine(153, 16, 156, 9, paint);
        canvas.drawLine(165, 17, 171, 12, paint);
        canvas.drawLine(143, 17, 141, 10, paint);
        if (pair) {
            stroke(0xFFBD704C, 1.25f);
            paint.setPathEffect(new DashPathEffect(new float[] {3, 4}, 0));
            Path path = new Path();
            path.moveTo(72, 157);
            path.cubicTo(88, 173, 117, 172, 136, 157);
            canvas.drawPath(path, paint);
            paint.setPathEffect(null);
            canvas.drawLine(130, 157, 136, 157, paint);
            canvas.drawLine(136, 157, 135, 163, paint);
        }
        canvas.restore();
    }

    private void leaf(Canvas canvas, float x, float y, float angle) {
        canvas.save();
        canvas.rotate(angle, x, y);
        canvas.drawOval(x - 6, y - 18, x + 6, y + 4, paint);
        canvas.restore();
    }

    /** The book edge is a light drawing; cover photos remain ordinary cancellable ImageViews. */
    static final class BookFrame extends FrameLayout {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int spine;

        BookFrame(Context context, int spine) {
            super(context);
            this.spine = spine;
            setWillNotDraw(false);
            setClipChildren(false);
        }

        private float dp(float value) {
            return Ui.dp(getContext(), value);
        }

        private void fill(int color) {
            paint.setColor(color);
            paint.setStyle(Paint.Style.FILL);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth(), h = getHeight(), r = dp(9);
            fill(0x16000000);
            canvas.drawRoundRect(dp(3), dp(5), w, h, r, r, paint);
            fill(spine);
            canvas.drawRoundRect(0, 0, w - dp(1), h - dp(2), r, r, paint);
            fill(0xFFF9E5C7);
            canvas.drawRoundRect(dp(8), dp(5), w - dp(2), h - dp(3), r, r, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(.7f));
            paint.setColor(0xFFDFC7A5);
            canvas.drawLine(dp(12), h - dp(8), w - dp(8), h - dp(8), paint);
            canvas.drawLine(dp(12), h - dp(5), w - dp(8), h - dp(5), paint);
            fill(0x36FFFFFF);
            canvas.drawRoundRect(dp(3), dp(3), dp(7), h - dp(7), dp(3), dp(3), paint);
            fill(0x18000000);
            canvas.drawRect(dp(8), dp(5), dp(11), h - dp(12), paint);
        }
    }
}
