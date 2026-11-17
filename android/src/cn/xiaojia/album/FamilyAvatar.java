package cn.xiaojia.album;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** Original family portraits used only when a person has not supplied a photograph. */
final class FamilyAvatar extends View {
    private final String name;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final boolean elder, longHair, child;
    private final int variation;

    FamilyAvatar(Context context, String value) {
        super(context);
        name = value == null || value.trim().isEmpty() ? "家人" : value.trim();
        elder =
                name.contains("奶")
                        || name.contains("婆")
                        || name.contains("爷")
                        || name.contains("公")
                        || name.contains("太");
        longHair =
                name.contains("妈")
                        || name.contains("奶")
                        || name.contains("婆")
                        || name.contains("姨")
                        || name.contains("姑")
                        || name.contains("姐")
                        || name.contains("妹");
        child =
                !name.contains("爸")
                        && !name.contains("妈")
                        && (name.contains("宝宝") || name.contains("孩子"));
        variation = (name.hashCode() & 0x7fffffff) % 3;
        setContentDescription(name + "的头像");
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    private void fill(int color) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
    }

    private void stroke(int color, float width) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(width);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(color);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth() - getPaddingLeft() - getPaddingRight();
        float h = getHeight() - getPaddingTop() - getPaddingBottom();
        float side = Math.min(w, h);
        if (side <= 0) return;
        canvas.save();
        canvas.translate(getPaddingLeft() + (w - side) / 2, getPaddingTop() + (h - side) / 2);
        canvas.scale(side / 100, side / 100);
        Path clip = new Path();
        clip.addCircle(50, 50, 49, Path.Direction.CW);
        canvas.clipPath(clip);
        fill(variation == 0 ? 0xFFFBE3D1 : variation == 1 ? 0xFFF5DFCF : 0xFFF7E9DA);
        canvas.drawCircle(50, 50, 50, paint);
        fill(0xFFFDEFE5);
        canvas.drawOval(4, -12, 84, 67, paint);
        int hair = elder ? 0xFFB9AAA2 : variation == 2 ? 0xFF624137 : 0xFF744A37;
        int skin = 0xFFF6C59F;
        int shirt =
                elder
                        ? (longHair ? 0xFFD99376 : 0xFFB59682)
                        : variation == 1 ? 0xFFEBA95E : variation == 2 ? 0xFFD98E76 : 0xFFF0BA72;
        if (longHair) {
            fill(hair);
            canvas.drawRoundRect(19, 15, 81, 83, 27, 27, paint);
            canvas.drawCircle(22, 58, 10, paint);
            canvas.drawCircle(77, 60, 11, paint);
            if (elder) canvas.drawCircle(75, 24, 13, paint);
        }
        fill(shirt);
        canvas.drawOval(11, 72, 89, 119, paint);
        fill(0xFFEAB18A);
        canvas.drawRoundRect(42, 62, 58, 83, 6, 6, paint);
        fill(skin);
        canvas.drawCircle(23, 48, 6.5f, paint);
        canvas.drawCircle(77, 48, 6.5f, paint);
        canvas.drawOval(24, child ? 23 : 20, 76, 76, paint);
        fill(0xFFFFD7B5);
        canvas.drawOval(28, 27, 67, 67, paint);
        fill(hair);
        Path fringe = new Path();
        fringe.moveTo(23, 44);
        fringe.cubicTo(17, 23, 31, 10, 52, 14);
        fringe.cubicTo(65, 8, 81, 21, 77, 44);
        fringe.cubicTo(70, 37, 66, 30, 63, 26);
        fringe.cubicTo(54, 39, 41, 38, 37, 30);
        fringe.cubicTo(35, 39, 28, 38, 23, 44);
        fringe.close();
        canvas.drawPath(fringe, paint);
        if (!longHair && !elder) {
            Path tuft = new Path();
            tuft.moveTo(26, 24);
            tuft.cubicTo(24, 13, 34, 9, 46, 14);
            tuft.cubicTo(52, 8, 65, 10, 65, 18);
            tuft.close();
            canvas.drawPath(tuft, paint);
        }
        stroke(hair, 1.7f);
        canvas.drawArc(32, 41, 44, 47, 205, 125, false, paint);
        canvas.drawArc(56, 41, 68, 47, 205, 125, false, paint);
        fill(0xFF624233);
        canvas.drawOval(36, 47, 39, 51.5f, paint);
        canvas.drawOval(61, 47, 64, 51.5f, paint);
        fill(0xFFF1AA89);
        canvas.drawOval(29, 53, 40, 59, paint);
        canvas.drawOval(60, 53, 71, 59, paint);
        stroke(0xFFD09171, 1.5f);
        canvas.drawArc(47, 51, 53, 57, 35, 130, false, paint);
        stroke(0xFF9F5945, 2);
        canvas.drawArc(42, 55, 59, 67, 15, 150, false, paint);
        if (elder) {
            stroke(0xFF927466, 1.8f);
            canvas.drawOval(28, 43, 46, 57, paint);
            canvas.drawOval(54, 43, 72, 57, paint);
            canvas.drawLine(46, 48, 54, 48, paint);
            canvas.drawLine(25, 47, 28, 48, paint);
            canvas.drawLine(72, 48, 75, 47, paint);
        }
        fill(0xFFFFE4BF);
        Path collar = new Path();
        collar.moveTo(34, 77);
        collar.lineTo(48, 86);
        collar.lineTo(42, 93);
        collar.lineTo(29, 81);
        collar.close();
        canvas.drawPath(collar, paint);
        collar.reset();
        collar.moveTo(66, 77);
        collar.lineTo(52, 86);
        collar.lineTo(58, 93);
        collar.lineTo(71, 81);
        collar.close();
        canvas.drawPath(collar, paint);
        stroke(0xFFC78A58, 1.3f);
        canvas.drawLine(50, 88, 50, 99, paint);
        canvas.restore();
    }
}
