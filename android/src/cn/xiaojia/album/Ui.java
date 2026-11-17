package cn.xiaojia.album;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.*;
import android.widget.*;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Shared warm palette, shapes, typography and original warm illustration icons. */
final class Ui {
    static final int BG = Color.rgb(255, 251, 247),
            SURFACE = Color.WHITE,
            SOFT = Color.rgb(251, 231, 218),
            INK = Color.rgb(72, 52, 45),
            MUTED = Color.rgb(132, 113, 101),
            SOFT_INK = Color.rgb(121, 100, 87),
            LINE = Color.rgb(235, 216, 205),
            ACCENT = Color.rgb(184, 90, 62),
            ACCENT_DARK = Color.rgb(148, 62, 40),
            DARK = Color.rgb(48, 37, 34),
            ON_DARK = Color.rgb(255, 249, 243),
            ERROR = Color.rgb(165, 59, 50),
            SUCCESS = Color.rgb(150, 96, 47),
            RED = ACCENT,
            BLUE = ACCENT_DARK;

    static int dp(Context c, float value) {
        return Math.round(c.getResources().getDisplayMetrics().density * value);
    }

    static TextView text(Context c, String value, int size, int color) {
        TextView view = new TextView(c);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    static GradientDrawable round(int color, float radius, Context c) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(c, radius));
        return background;
    }

    /** The stroke argument is a color; all shared cards use a subtle 1dp edge. */
    static GradientDrawable border(int color, float radius, int stroke, Context c) {
        GradientDrawable background = round(color, radius, c);
        background.setStroke(dp(c, 1), stroke);
        return background;
    }

    static GradientDrawable card(Context c) {
        return border(SURFACE, 18, LINE, c);
    }

    static Drawable ripple(int color, float radius, Context c) {
        return new RippleDrawable(
                ColorStateList.valueOf(Color.argb(28, 148, 62, 40)),
                round(color, radius, c),
                round(Color.WHITE, radius, c));
    }

    static Drawable button(Context c, boolean primary) {
        GradientDrawable background = round(primary ? ACCENT : SOFT, 24, c);
        background.setColor(
                new ColorStateList(
                        new int[][] {new int[] {-android.R.attr.state_enabled}, new int[] {}},
                        new int[] {
                            primary ? Color.rgb(212, 172, 153) : Color.rgb(248, 238, 230),
                            primary ? ACCENT : SOFT
                        }));
        return new RippleDrawable(
                ColorStateList.valueOf(
                        primary ? Color.argb(36, 255, 255, 255) : Color.argb(24, 148, 62, 40)),
                background,
                round(Color.WHITE, 24, c));
    }

    static Drawable chip(Context c, boolean selected) {
        return ripple(selected ? SOFT : BG, 14, c);
    }

    /** Kept for existing callers; the former red/yellow treatment is now quiet terracotta. */
    static GradientDrawable gradient(Context c) {
        GradientDrawable background =
                new GradientDrawable(
                        GradientDrawable.Orientation.LEFT_RIGHT,
                        new int[] {ACCENT, Color.rgb(178, 82, 55)});
        background.setCornerRadius(dp(c, 24));
        return background;
    }

    static LinearLayout column(Context c) {
        LinearLayout layout = new LinearLayout(c);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    static LinearLayout row(Context c) {
        LinearLayout layout = new LinearLayout(c);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    static String day(String value) {
        return value == null || value.length() < 10 ? "" : value.substring(0, 10);
    }

    static String date(String value) {
        try {
            LocalDate date = LocalDate.parse(day(value));
            return date.getYear() + "年" + date.getMonthValue() + "月" + date.getDayOfMonth() + "日";
        } catch (Exception e) {
            return "日期待确认";
        }
    }

    static String readable(String value) {
        try {
            OffsetDateTime parsed =
                    OffsetDateTime.parse(value)
                            .atZoneSameInstant(ZoneId.of("Asia/Shanghai"))
                            .toOffsetDateTime();
            return parsed.getYear()
                    + "年"
                    + parsed.getMonthValue()
                    + "月"
                    + parsed.getDayOfMonth()
                    + "日 "
                    + parsed.format(DateTimeFormatter.ofPattern("HH:mm"));
        } catch (Exception e) {
            return date(value);
        }
    }

    static String month(String value) {
        try {
            YearMonth month = YearMonth.parse(value);
            return month.getYear() + "年" + month.getMonthValue() + "月";
        } catch (Exception e) {
            return "全部月份";
        }
    }

    static String age(String birthday, String day) {
        try {
            LocalDate birth = LocalDate.parse(birthday), target = LocalDate.parse(day);
            if (target.isBefore(birth)) return "出生前";
            Period elapsed = Period.between(birth, target);
            StringBuilder result = new StringBuilder();
            if (elapsed.getYears() > 0) result.append(elapsed.getYears()).append("岁");
            if (elapsed.getMonths() > 0) result.append(elapsed.getMonths()).append("个月");
            if (elapsed.getDays() > 0) result.append(elapsed.getDays()).append("天");
            return result.length() == 0 ? "出生当天" : result.toString();
        } catch (Exception e) {
            return "成长记录";
        }
    }

    static String duration(double seconds) {
        int value = (int) Math.round(seconds);
        return String.format(Locale.CHINA, "%d:%02d", value / 60, value % 60);
    }

    static String size(long bytes) {
        return bytes >= 1000000000L
                ? String.format(Locale.CHINA, "%.2f GB", bytes / 1e9)
                : String.format(Locale.CHINA, "%.1f MB", bytes / 1e6);
    }

    /** Legacy decorative slot, now a quiet warm background rather than bright colored blobs. */
    static final class ProfileDecoration extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        ProfileDecoration(Context context) {
            super(context);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.save();
            canvas.scale(getWidth() / 450f, getWidth() / 450f);
            paint.setColor(SOFT);
            paint.setAlpha(90);
            canvas.drawOval(310, -130, 535, 95, paint);
            paint.setColor(LINE);
            paint.setAlpha(95);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1);
            canvas.drawArc(340, -100, 540, 100, 90, 90, false, paint);
            paint.setAlpha(255);
            paint.setStyle(Paint.Style.FILL);
            canvas.restore();
        }
    }

    /**
     * Original duotone glyphs; an explicit fill state keeps navigation selected and idle shapes
     * equal.
     */
    static final class Icon extends ControlGlyph {
        private static final int PEACH = 0xFFF9D8B4,
                PAPER = 0xFFFFF8ED,
                HONEY = 0xFFF3BB60,
                CORAL = 0xFFE99572,
                ROSE = 0xFFE78073,
                SAND = 0xFFE4B98D;
        private final String glyph;
        private int tint;
        private final Boolean explicitFill;
        private final Paint pen = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean colorful;

        Icon(Context context, String kind, int color) {
            this(context, kind, color, null);
        }

        Icon(Context context, String kind, int color, boolean filled) {
            this(context, kind, color, Boolean.valueOf(filled));
        }

        private Icon(Context context, String kind, int color, Boolean filled) {
            super(context, kind, color);
            glyph = kind;
            tint = color;
            explicitFill = filled;
            switch (kind) {
                case "collection":
                    setContentDescription("我的收藏");
                    break;
                case "memory_video":
                    setContentDescription("视频回忆");
                    break;
                case "memory_month":
                    setContentDescription("月份回忆");
                    break;
                case "recover":
                    setContentDescription("回收站");
                    break;
                case "server":
                    setContentDescription("自己的服务器");
                    break;
                case "nas":
                    setContentDescription("NAS存储");
                    break;
                case "security":
                    setContentDescription("账号安全");
                    break;
                case "reply":
                    setContentDescription("回复");
                    break;
                case "refresh":
                    setContentDescription("刷新");
                    break;
                case "bell":
                    setContentDescription("通知");
                    break;
            }
        }

        @Override
        void color(int value) {
            super.color(value);
            tint = value;
        }

        private void stroke() {
            pen.setColor(tint);
            pen.setStyle(Paint.Style.STROKE);
            pen.setStrokeWidth(colorful ? 1.15f : 1.5f);
            pen.setStrokeCap(Paint.Cap.ROUND);
            pen.setStrokeJoin(Paint.Join.ROUND);
        }

        private void solid(int color) {
            pen.setStyle(Paint.Style.FILL);
            pen.setColor(color);
        }

        private void shape(Canvas canvas, Path path, int fill) {
            if (colorful) {
                solid(fill);
                canvas.drawPath(path, pen);
            }
            stroke();
            canvas.drawPath(path, pen);
        }

        private void box(
                Canvas c,
                float left,
                float top,
                float right,
                float bottom,
                float radius,
                int fill) {
            if (colorful) {
                solid(fill);
                c.drawRoundRect(left, top, right, bottom, radius, radius, pen);
            }
            stroke();
            c.drawRoundRect(left, top, right, bottom, radius, radius, pen);
        }

        private void disk(Canvas c, float x, float y, float radius, int fill) {
            if (colorful) {
                solid(fill);
                c.drawCircle(x, y, radius, pen);
            }
            stroke();
            c.drawCircle(x, y, radius, pen);
        }

        private void lines(Canvas c, float... points) {
            stroke();
            Path p = new Path();
            p.moveTo(points[0], points[1]);
            for (int i = 2; i < points.length; i += 2) p.lineTo(points[i], points[i + 1]);
            c.drawPath(p, pen);
        }

        private void mark(Canvas c, float x, float y, float radius, int color) {
            solid(colorful ? color : tint);
            c.drawCircle(x, y, radius, pen);
            stroke();
        }

        private void ray(Canvas c, float x, float y, float dx, float dy) {
            stroke();
            if (colorful) pen.setColor(HONEY);
            c.drawLine(x, y, x + dx, y + dy, pen);
            stroke();
        }

        private void picture(
                Canvas c, float left, float top, float right, float bottom, boolean child) {
            box(c, left, top, right, bottom, 1.8f, PAPER);
            float width = right - left, height = bottom - top;
            if (child) {
                if (colorful) {
                    solid(PEACH);
                    c.drawRoundRect(left + 2, top + 2, right - 2, bottom - 2, .8f, .8f, pen);
                }
                disk(c, (left + right) / 2, top + height * .45f, width * .2f, 0xFFF9C69C);
                Path hair = new Path();
                float cx = (left + right) / 2, cy = top + height * .45f;
                hair.moveTo(cx - width * .2f, cy);
                hair.cubicTo(
                        cx - width * .28f,
                        cy - height * .28f,
                        cx + width * .25f,
                        cy - height * .28f,
                        cx + width * .2f,
                        cy);
                hair.quadTo(cx + width * .08f, cy - height * .15f, cx, cy - height * .07f);
                hair.quadTo(cx - width * .08f, cy - height * .03f, cx - width * .2f, cy);
                hair.close();
                if (colorful) {
                    solid(0xFF8B5942);
                    c.drawPath(hair, pen);
                } else {
                    stroke();
                    c.drawPath(hair, pen);
                }
                mark(c, cx - width * .07f, cy + .5f, .38f, 0xFF76503C);
                mark(c, cx + width * .07f, cy + .5f, .38f, 0xFF76503C);
                stroke();
                if (colorful) pen.setColor(0xFFAD6748);
                c.drawArc(cx - 1.3f, cy + .3f, cx + 1.3f, cy + 2.5f, 10, 160, false, pen);
                Path shoulders = new Path();
                shoulders.moveTo(left + width * .24f, bottom - 2);
                shoulders.cubicTo(
                        left + width * .24f,
                        bottom - height * .36f,
                        right - width * .24f,
                        bottom - height * .36f,
                        right - width * .24f,
                        bottom - 2);
                shoulders.close();
                if (colorful) {
                    solid(HONEY);
                    c.drawPath(shoulders, pen);
                } else {
                    stroke();
                    c.drawPath(shoulders, pen);
                }
            } else {
                if (colorful) {
                    solid(PEACH);
                    c.drawRoundRect(left + 2, top + 2, right - 2, bottom - 2, .8f, .8f, pen);
                }
                mark(
                        c,
                        right - width * .27f,
                        top + height * .3f,
                        Math.min(width, height) * .08f,
                        HONEY);
                Path hill = new Path();
                hill.moveTo(left + 2, bottom - 2);
                hill.lineTo(left + width * .35f, top + height * .52f);
                hill.lineTo(left + width * .57f, top + height * .72f);
                hill.lineTo(left + width * .72f, top + height * .60f);
                hill.lineTo(right - 2, bottom - 2);
                hill.close();
                if (colorful) {
                    solid(CORAL);
                    c.drawPath(hill, pen);
                } else {
                    stroke();
                    c.drawPath(hill, pen);
                }
            }
        }

        private void album(Canvas c, boolean baby, boolean collection) {
            if (!baby) {
                c.save();
                c.rotate(12, 13, 10);
                box(c, 6, 2, 21, 17.5f, 1.8f, HONEY);
                c.restore();
            }
            c.save();
            c.rotate(-8, 11, 13);
            picture(c, 2.8f, baby ? 5 : 6, 19.5f, 21.5f, baby);
            c.restore();
            if (baby) {
                ray(c, 17, 2.8f, -.4f, -1.4f);
                ray(c, 20, 3.6f, 1, -1.2f);
                ray(c, 21.4f, 6.5f, 1.4f, -.3f);
            }
            if (collection) {
                c.save();
                c.translate(12.2f, 12);
                c.scale(.49f, .49f);
                heart(c, true);
                c.restore();
            }
        }

        private void heart(Canvas c, boolean strong) {
            Path p = new Path();
            p.moveTo(12, 21);
            p.cubicTo(7, 17.5f, 1.8f, 13.5f, 2, 8);
            p.cubicTo(2, 1.8f, 9, 1.2f, 12, 6);
            p.cubicTo(15, 1.2f, 22, 1.8f, 22, 8);
            p.cubicTo(22.2f, 13.5f, 17, 17.5f, 12, 21);
            p.close();
            if (colorful || strong || tint == 0xFFE9B18F) {
                solid(colorful ? ROSE : tint);
                c.drawPath(p, pen);
            }
            stroke();
            if (colorful) pen.setColor(0xFFD87762);
            c.drawPath(p, pen);
        }

        private void book(Canvas c) {
            if (colorful) {
                solid(HONEY);
                c.drawCircle(12, 5.8f, 3.5f, pen);
            }
            ray(c, 12, 1.8f, 0, -1);
            ray(c, 7.8f, 3, -.8f, -.8f);
            ray(c, 16.4f, 3, .8f, -.8f);
            Path p = new Path();
            p.moveTo(2, 7);
            p.quadTo(6, 4.8f, 12, 8);
            p.quadTo(18, 4.8f, 22, 7);
            p.lineTo(22, 19);
            p.quadTo(17, 17.4f, 12, 20);
            p.quadTo(7, 17.4f, 2, 19);
            p.close();
            shape(c, p, PAPER);
            lines(c, 12, 8, 12, 18.6f);
            if (colorful) {
                pen.setColor(SAND);
                c.drawLine(4.5f, 10, 8.8f, 10.7f, pen);
                c.drawLine(4.5f, 13.2f, 8.8f, 13.9f, pen);
            }
            disk(c, 18.5f, 18.2f, 4.5f, PAPER);
            lines(c, 18.5f, 15.7f, 18.5f, 18.2f, 20.5f, 18.2f);
        }

        private void memoryVideo(Canvas c) {
            if (colorful) {
                solid(0xB3FBE7DA);
                c.drawOval(1, 18.3f, 23, 23.2f, pen);
            }
            Path lens = new Path();
            lens.moveTo(16.5f, 11.2f);
            lens.lineTo(21.4f, 8.7f);
            lens.quadTo(22.6f, 8.1f, 22.6f, 9.5f);
            lens.lineTo(22.6f, 19.5f);
            lens.quadTo(22.6f, 20.8f, 21.4f, 20.2f);
            lens.lineTo(16.5f, 17.7f);
            lens.close();
            shape(c, lens, HONEY);
            box(c, 2.4f, 5.8f, 17.8f, 21.5f, 3.2f, PAPER);
            box(c, 4.4f, 10.2f, 15.8f, 19.2f, 2.3f, HONEY);
            mark(c, 5.5f, 8.1f, .55f, HONEY);
            mark(c, 8.1f, 8.1f, .55f, SAND);
            Path play = new Path();
            play.moveTo(8.6f, 12.1f);
            play.lineTo(8.6f, 17.2f);
            play.lineTo(12.5f, 14.7f);
            play.close();
            if (colorful) {
                solid(PAPER);
                c.drawPath(play, pen);
            } else {
                stroke();
                c.drawPath(play, pen);
            }
            ray(c, 18.1f, 4.2f, -.5f, -2.1f);
            ray(c, 20.5f, 4.8f, .9f, -1.8f);
            ray(c, 22, 6.9f, 1.4f, -.5f);
        }

        private void memoryMonth(Canvas c) {
            if (colorful) {
                solid(0xB3FBE7DA);
                c.drawOval(1.2f, 19, 23, 23.3f, pen);
            }
            box(c, 2.7f, 4.2f, 18.8f, 21.2f, 2.8f, PAPER);
            if (colorful) {
                solid(PEACH);
                c.drawRoundRect(4.3f, 5.7f, 17.2f, 9.4f, 1.3f, 1.3f, pen);
            }
            lines(c, 3, 10, 18.5f, 10);
            box(c, 6.2f, 1.5f, 8.1f, 7.1f, .9f, PAPER);
            box(c, 13.4f, 1.5f, 15.3f, 7.1f, .9f, PAPER);
            mark(c, 6.5f, 13.4f, .65f, SAND);
            mark(c, 6.5f, 16.8f, .65f, SAND);
            c.save();
            c.rotate(-4, 16.3f, 16.8f);
            picture(c, 10.3f, 11.2f, 22.4f, 22.2f, false);
            c.restore();
        }

        private Path bubble(float left, float top, float right, float bottom, boolean rightTail) {
            Path p = new Path();
            p.addRoundRect(
                    left,
                    top,
                    right,
                    bottom,
                    (bottom - top) / 2.2f,
                    (bottom - top) / 2.2f,
                    Path.Direction.CW);
            Path tail = new Path();
            if (rightTail) {
                tail.moveTo(right - 6, bottom - 1);
                tail.lineTo(right - 2, bottom + 2.8f);
                tail.lineTo(right - 3, bottom - 3);
            } else {
                tail.moveTo(left + 3, bottom - 3);
                tail.lineTo(left + 2, bottom + 3);
                tail.lineTo(left + 7, bottom - 1);
            }
            tail.close();
            p.op(tail, Path.Op.UNION);
            return p;
        }

        private void conversation(Canvas c, boolean single) {
            if (!single) shape(c, bubble(10, 8, 22, 19, true), PEACH);
            shape(c, bubble(2, 3, single ? 22 : 19, single ? 18 : 16, false), CORAL);
            for (float x = single ? 7 : 6; x <= (single ? 17 : 14); x += single ? 5 : 4)
                mark(c, x, single ? 10.5f : 9.4f, .7f, PAPER);
        }

        private void house(Canvas c, boolean server) {
            box(c, 5, 8, 20, 22, 1.9f, PEACH);
            if (colorful) {
                solid(HONEY);
                c.drawRoundRect(17, 2, 20, 7, .7f, .7f, pen);
            } else lines(c, 17.5f, 5, 17.5f, 2, 20, 2, 20, 7);
            Path roof = new Path();
            roof.moveTo(1.8f, 9.8f);
            roof.lineTo(11, 2.5f);
            roof.quadTo(12, 1.6f, 13, 2.5f);
            roof.lineTo(22.2f, 9.8f);
            roof.quadTo(23, 11, 21.6f, 12);
            roof.lineTo(12, 4.5f);
            roof.lineTo(2.4f, 12);
            roof.quadTo(1, 11, 1.8f, 9.8f);
            roof.close();
            shape(c, roof, CORAL);
            if (server) {
                box(c, 7, 11.5f, 18, 15.4f, 1, PAPER);
                box(c, 7, 17, 18, 20.8f, 1, PAPER);
                mark(c, 9.4f, 13.5f, .6f, HONEY);
                mark(c, 9.4f, 18.9f, .6f, HONEY);
                lines(c, 12, 13.5f, 15.7f, 13.5f);
                lines(c, 12, 18.9f, 15.7f, 18.9f);
            } else {
                disk(c, 12.5f, 12.4f, 2.5f, HONEY);
                Path person = new Path();
                person.moveTo(8.1f, 20);
                person.cubicTo(8.1f, 14.3f, 16.9f, 14.3f, 16.9f, 20);
                person.close();
                shape(c, person, HONEY);
            }
        }

        private void recovery(Canvas c) {
            box(c, 4, 8, 20, 21.5f, 2.2f, PEACH);
            Path top = new Path();
            top.moveTo(6.5f, 6.3f);
            top.lineTo(8.7f, 3);
            top.lineTo(16.2f, 3);
            top.lineTo(18.2f, 6.3f);
            top.close();
            shape(c, top, CORAL);
            box(c, 2.5f, 6, 21.5f, 9.8f, 1.3f, HONEY);
            stroke();
            c.drawArc(9, 12.1f, 17.2f, 19, 214, 286, false, pen);
            lines(c, 8.4f, 12.4f, 8.3f, 15.7f, 11.5f, 15.7f);
        }

        private void family(Canvas c, boolean invitation) {
            Path shoulders = new Path();
            shoulders.moveTo(2, 21);
            shoulders.cubicTo(2, 13, 14, 13, 14, 21);
            shoulders.close();
            shape(c, shoulders, HONEY);
            disk(c, 8, 8.4f, 4, PEACH);
            shoulders.reset();
            shoulders.moveTo(12, 21);
            shoulders.cubicTo(12, 14, 23, 14, 23, 21);
            shoulders.close();
            shape(c, shoulders, CORAL);
            disk(c, 17.5f, 10, 3.6f, PEACH);
            if (colorful) {
                solid(0xFF9C6C51);
                c.drawArc(4.1f, 4.2f, 11.9f, 10.5f, 180, 180, true, pen);
                c.drawArc(13.8f, 6.2f, 21.2f, 12.6f, 180, 180, true, pen);
            }
            if (invitation) {
                disk(c, 19.5f, 18.9f, 4.3f, CORAL);
                stroke();
                if (colorful) pen.setColor(PAPER);
                c.drawLine(17.4f, 18.9f, 21.6f, 18.9f, pen);
                c.drawLine(19.5f, 16.8f, 19.5f, 21, pen);
            }
        }

        private void nas(Canvas c) {
            box(c, 2, 3, 20, 21, 2.8f, PEACH);
            box(c, 4.5f, 6, 9.4f, 19, 1.4f, PAPER);
            box(c, 11.6f, 6, 16.5f, 19, 1.4f, PAPER);
            mark(c, 7, 9, .75f, HONEY);
            mark(c, 14, 9, .75f, HONEY);
            lines(c, 7, 15, 7, 17);
            lines(c, 14, 15, 14, 17);
            if (colorful) {
                c.save();
                c.translate(11, 11);
                c.scale(.52f, .52f);
                picture(c, 3, 3, 22, 21, false);
                c.restore();
            }
        }

        private void cloud(Canvas c) {
            Path p = new Path();
            p.moveTo(6, 19.5f);
            p.cubicTo(0, 19.5f, 0, 11, 5.5f, 10);
            p.cubicTo(6, 2, 16, 2.2f, 18, 9);
            p.cubicTo(25.5f, 9, 25, 19.5f, 18.5f, 19.5f);
            p.close();
            shape(c, p, PEACH);
            c.save();
            c.translate(5.5f, 8);
            c.scale(.61f, .61f);
            picture(c, 2, 2, 21, 18, false);
            c.restore();
        }

        private void security(Canvas c) {
            Path p = new Path();
            p.moveTo(12, 2);
            p.quadTo(16.5f, 5, 21, 5.7f);
            p.lineTo(20.5f, 12.5f);
            p.cubicTo(20, 17.3f, 16, 21, 12, 22.5f);
            p.cubicTo(8, 21, 4, 17.3f, 3.5f, 12.5f);
            p.lineTo(3, 5.7f);
            p.quadTo(7.5f, 5, 12, 2);
            p.close();
            shape(c, p, HONEY);
            if (colorful) {
                c.save();
                c.scale(.75f, .75f, 12, 12);
                solid(PEACH);
                c.drawPath(p, pen);
                c.restore();
            }
            disk(c, 12, 10, 2.3f, CORAL);
            Path key = new Path();
            key.moveTo(11, 12);
            key.lineTo(9.8f, 17);
            key.lineTo(14.2f, 17);
            key.lineTo(13, 12);
            key.close();
            shape(c, key, CORAL);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            boolean custom =
                    glyph.equals("album")
                            || glyph.equals("photo")
                            || glyph.equals("baby")
                            || glyph.equals("collection")
                            || glyph.equals("memory_video")
                            || glyph.equals("memory_month")
                            || glyph.equals("time")
                            || glyph.equals("book")
                            || glyph.equals("activity")
                            || glyph.equals("comment")
                            || glyph.equals("home")
                            || glyph.equals("me")
                            || glyph.equals("house")
                            || glyph.equals("server")
                            || glyph.equals("nas")
                            || glyph.equals("cloud")
                            || glyph.equals("oss")
                            || glyph.equals("cos")
                            || glyph.equals("s3")
                            || glyph.equals("storage")
                            || glyph.equals("trash")
                            || glyph.equals("recover")
                            || glyph.equals("security")
                            || glyph.equals("shield")
                            || glyph.equals("people")
                            || glyph.equals("invite")
                            || glyph.equals("heart")
                            || glyph.equals("reply")
                            || glyph.equals("refresh")
                            || glyph.equals("bell");
            if (!custom) {
                super.onDraw(canvas);
                return;
            }
            float w = getWidth() - getPaddingLeft() - getPaddingRight();
            float h = getHeight() - getPaddingTop() - getPaddingBottom();
            float side = Math.min(w, h);
            if (side <= 0) return;
            boolean lightTint =
                    Color.red(tint) > 210 && Color.green(tint) > 155 && Color.blue(tint) > 120;
            colorful =
                    !lightTint
                            && (explicitFill != null
                                    ? explicitFill
                                    : tint == ACCENT || tint == ACCENT_DARK);
            canvas.save();
            canvas.translate(getPaddingLeft() + (w - side) / 2, getPaddingTop() + (h - side) / 2);
            canvas.scale(side / 24, side / 24);
            stroke();
            switch (glyph) {
                case "album":
                case "photo":
                    album(canvas, false, false);
                    break;
                case "baby":
                    album(canvas, true, false);
                    break;
                case "collection":
                    album(canvas, false, true);
                    break;
                case "memory_video":
                    memoryVideo(canvas);
                    break;
                case "memory_month":
                    memoryMonth(canvas);
                    break;
                case "time":
                case "book":
                    book(canvas);
                    break;
                case "activity":
                    conversation(canvas, false);
                    break;
                case "comment":
                    conversation(canvas, true);
                    break;
                case "home":
                case "house":
                case "me":
                    house(canvas, false);
                    break;
                case "storage":
                case "server":
                    house(canvas, true);
                    break;
                case "nas":
                    nas(canvas);
                    break;
                case "cloud":
                case "oss":
                case "cos":
                case "s3":
                    cloud(canvas);
                    break;
                case "recover":
                case "trash":
                    recovery(canvas);
                    break;
                case "security":
                case "shield":
                    security(canvas);
                    break;
                case "people":
                    family(canvas, false);
                    break;
                case "invite":
                    family(canvas, true);
                    break;
                case "heart":
                    heart(canvas, false);
                    break;
                case "reply":
                    lines(canvas, 8.5f, 4, 2.5f, 10, 8.5f, 16);
                    stroke();
                    Path reply = new Path();
                    reply.moveTo(3, 10);
                    reply.lineTo(13, 10);
                    reply.cubicTo(20, 10, 22, 13, 22, 20);
                    canvas.drawPath(reply, pen);
                    break;
                case "refresh":
                    stroke();
                    canvas.drawArc(3, 3, 21, 21, 35, 285, false, pen);
                    lines(canvas, 21, 2.5f, 21, 8, 15.5f, 8);
                    break;
                case "bell":
                    Path bell = new Path();
                    bell.moveTo(4, 18);
                    bell.quadTo(6, 15, 6, 9);
                    bell.cubicTo(6, 2, 18, 2, 18, 9);
                    bell.quadTo(18, 15, 20, 18);
                    bell.close();
                    shape(canvas, bell, PEACH);
                    lines(canvas, 12, 2, 12, 3.3f);
                    stroke();
                    canvas.drawArc(9, 18, 15, 22, 0, 180, false, pen);
                    break;
            }
            canvas.restore();
        }
    }

    /**
     * Quiet controls stay monoline; the family illustrations below share the same stroke rhythm.
     */
    static class ControlGlyph extends View {
        private final String kind;
        private int color;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final BrandIconDrawable brand;

        ControlGlyph(Context c, String kind, int color) {
            super(c);
            this.kind = kind;
            this.color = color;
            brand = kind.equals("brand") ? new BrandIconDrawable(c) : null;
            setContentDescription(label(kind));
        }

        void color(int value) {
            color = value;
            invalidate();
        }

        private static String label(String kind) {
            switch (kind) {
                case "filter":
                    return "筛选";
                case "search":
                    return "查找";
                case "plus":
                    return "上传";
                case "photo":
                case "album":
                    return "照片";
                case "video":
                case "play":
                    return "视频";
                case "people":
                    return "家人";
                case "invite":
                    return "邀请家人";
                case "time":
                    return "时光";
                case "activity":
                    return "动态";
                case "me":
                case "home":
                case "house":
                    return "我的";
                case "brand":
                    return "FAM宝贝相册";
                case "back":
                    return "返回";
                case "next":
                case "chevron":
                    return "查看";
                case "more":
                    return "更多";
                case "heart":
                case "star":
                    return "收藏";
                case "comment":
                    return "评论";
                case "download":
                    return "下载";
                case "share":
                    return "分享";
                case "calendar":
                    return "日期";
                case "trash":
                    return "回收站";
                case "restore":
                    return "恢复";
                case "edit":
                    return "编辑";
                case "grid":
                    return "小图";
                case "large_grid":
                    return "大图";
                case "nas":
                case "server":
                    return "本机与NAS";
                case "storage":
                    return "照片存放位置";
                case "cloud":
                case "oss":
                case "cos":
                case "s3":
                    return "云存储";
                case "shield":
                case "lock":
                    return "账号与安全";
                case "key":
                    return "访问密钥";
                case "settings":
                    return "设置";
                case "info":
                    return "信息";
                case "book":
                    return "说明";
                case "logout":
                    return "退出登录";
                case "mail":
                    return "家人邀请";
                case "copy":
                    return "复制";
                case "baby":
                    return "宝宝资料";
                case "check":
                    return "已选择";
                case "close":
                    return "关闭";
                default:
                    return "操作";
            }
        }

        private void line(Canvas canvas, float... points) {
            Path path = new Path();
            path.moveTo(points[0], points[1]);
            for (int i = 2; i < points.length; i += 2) path.lineTo(points[i], points[i + 1]);
            canvas.drawPath(path, paint);
        }

        private void dot(Canvas canvas, float x, float y, float radius) {
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(x, y, radius, paint);
            paint.setStyle(Paint.Style.STROKE);
        }

        private void star(Canvas canvas) {
            Path path = new Path();
            for (int i = 0; i < 10; i++) {
                double angle = -Math.PI / 2 + i * Math.PI / 5;
                float radius = i % 2 == 0 ? 9 : 4.5f;
                float x = 12 + (float) Math.cos(angle) * radius;
                float y = 12 + (float) Math.sin(angle) * radius;
                if (i == 0) path.moveTo(x, y);
                else path.lineTo(x, y);
            }
            path.close();
            if (color == ACCENT) paint.setStyle(Paint.Style.FILL);
            canvas.drawPath(path, paint);
            paint.setStyle(Paint.Style.STROKE);
        }

        private void cloud(Canvas canvas) {
            Path path = new Path();
            path.moveTo(7, 19);
            path.cubicTo(1, 19, 1, 11, 6, 10);
            path.cubicTo(6, 3, 16, 2, 18, 9);
            path.cubicTo(24, 9, 24, 19, 18, 19);
            path.close();
            canvas.drawPath(path, paint);
        }

        private void house(Canvas canvas) {
            Path path = new Path();
            path.moveTo(3.5f, 20);
            path.lineTo(3.5f, 11);
            path.quadTo(3.5f, 9, 5, 7.8f);
            path.lineTo(11, 2.8f);
            path.quadTo(12, 2, 13, 2.8f);
            path.lineTo(19, 7.8f);
            path.quadTo(20.5f, 9, 20.5f, 11);
            path.lineTo(20.5f, 20);
            path.quadTo(20.5f, 21.5f, 19, 21.5f);
            path.lineTo(5, 21.5f);
            path.quadTo(3.5f, 21.5f, 3.5f, 20);
            path.close();
            boolean filled = color == ACCENT;
            if (filled) paint.setStyle(Paint.Style.FILL);
            canvas.drawPath(path, paint);
            paint.setStyle(Paint.Style.STROKE);
            Path door = new Path();
            door.moveTo(9.5f, 20);
            door.lineTo(9.5f, 16);
            door.cubicTo(9.5f, 12.5f, 14.5f, 12.5f, 14.5f, 16);
            door.lineTo(14.5f, 20);
            if (filled) {
                door.close();
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(filled ? ON_DARK : Color.rgb(229, 160, 100));
            }
            canvas.drawPath(door, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(color);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float availableWidth = getWidth() - getPaddingLeft() - getPaddingRight();
            float availableHeight = getHeight() - getPaddingTop() - getPaddingBottom();
            float side = Math.min(availableWidth, availableHeight);
            if (side <= 0) return;
            canvas.save();
            canvas.translate(
                    getPaddingLeft() + (availableWidth - side) / 2,
                    getPaddingTop() + (availableHeight - side) / 2);
            canvas.scale(side / 24, side / 24);
            paint.setColor(color);
            paint.setAlpha(255);
            paint.setShader(null);
            paint.setStrokeWidth(1.65f);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            Path path = new Path();
            switch (kind) {
                case "filter":
                    line(canvas, 3, 7, 6, 7);
                    line(canvas, 10, 7, 21, 7);
                    line(canvas, 3, 17, 13, 17);
                    line(canvas, 17, 17, 21, 17);
                    canvas.drawCircle(8, 7, 2, paint);
                    canvas.drawCircle(15, 17, 2, paint);
                    break;
                case "photo":
                case "album":
                    line(canvas, 3, 8, 3, 19, 17, 19);
                    canvas.drawRoundRect(6, 3.5f, 22, 16.5f, 2.4f, 2.4f, paint);
                    canvas.drawCircle(17.2f, 7.5f, 1.3f, paint);
                    line(canvas, 8.5f, 13.5f, 12, 10, 15, 13, 17, 11, 19.5f, 13.5f);
                    break;
                case "video":
                    canvas.drawRoundRect(2.5f, 5.5f, 16.5f, 18.5f, 2.5f, 2.5f, paint);
                    line(canvas, 16.5f, 9, 21.5f, 6.5f, 21.5f, 17.5f, 16.5f, 15);
                    break;
                case "play":
                    path.moveTo(8, 4.5f);
                    path.lineTo(20, 12);
                    path.lineTo(8, 19.5f);
                    path.close();
                    canvas.drawPath(path, paint);
                    break;
                case "people":
                    canvas.drawCircle(9, 7.5f, 3, paint);
                    canvas.drawCircle(17.5f, 9, 2.5f, paint);
                    canvas.drawArc(2, 12, 16, 25, 180, 180, false, paint);
                    canvas.drawArc(13, 13.5f, 24, 24.5f, 190, 155, false, paint);
                    break;
                case "invite":
                    canvas.drawCircle(8.5f, 7.5f, 3, paint);
                    canvas.drawArc(1.5f, 12, 15.5f, 25, 180, 180, false, paint);
                    line(canvas, 18, 7, 18, 15);
                    line(canvas, 14, 11, 22, 11);
                    break;
                case "activity":
                    path.moveTo(5, 16);
                    path.cubicTo(0, 14, 1, 5, 7, 4);
                    path.cubicTo(17, 1, 21, 14, 12, 16);
                    path.lineTo(7, 16);
                    path.lineTo(3.5f, 19);
                    path.close();
                    canvas.drawPath(path, paint);
                    path.reset();
                    path.moveTo(19.5f, 9);
                    path.cubicTo(25, 12, 22, 19, 17, 20);
                    path.lineTo(20.5f, 22);
                    path.lineTo(15, 21);
                    canvas.drawPath(path, paint);
                    dot(canvas, 6, 10, .65f);
                    dot(canvas, 10, 10, .65f);
                    dot(canvas, 14, 10, .65f);
                    break;
                case "me":
                case "home":
                case "house":
                    house(canvas);
                    break;
                case "brand":
                    brand.setBounds(0, 0, 24, 24);
                    brand.draw(canvas);
                    break;
                case "time":
                    canvas.drawCircle(12, 13, 6.5f, paint);
                    line(canvas, 12, 9, 12, 13, 15, 15);
                    line(canvas, 12, 1.5f, 12, 3.2f);
                    line(canvas, 4.1f, 4.8f, 5.4f, 6.1f);
                    line(canvas, 19.9f, 4.8f, 18.6f, 6.1f);
                    line(canvas, 1.5f, 13, 3, 13);
                    line(canvas, 21, 13, 22.5f, 13);
                    line(canvas, 5, 20.1f, 6.2f, 19);
                    line(canvas, 19, 20.1f, 17.8f, 19);
                    break;
                case "plus":
                    line(canvas, 12, 4, 12, 20);
                    line(canvas, 4, 12, 20, 12);
                    break;
                case "back":
                    line(canvas, 15, 4.5f, 7.5f, 12, 15, 19.5f);
                    break;
                case "triangle":
                    path.moveTo(15, 5);
                    path.lineTo(7, 12);
                    path.lineTo(15, 19);
                    path.close();
                    paint.setStyle(Paint.Style.FILL);
                    canvas.drawPath(path, paint);
                    break;
                case "next":
                case "chevron":
                    line(canvas, 9, 5.5f, 15.5f, 12, 9, 18.5f);
                    break;
                case "more":
                    dot(canvas, 5, 12, 1.3f);
                    dot(canvas, 12, 12, 1.3f);
                    dot(canvas, 19, 12, 1.3f);
                    break;
                case "info":
                    canvas.drawCircle(12, 12, 9, paint);
                    dot(canvas, 12, 7.5f, 1);
                    line(canvas, 12, 11, 12, 17);
                    break;
                case "heart":
                case "star":
                    star(canvas);
                    break;
                case "grid":
                    for (int y = 4; y <= 14; y += 10)
                        for (int x = 4; x <= 14; x += 10)
                            canvas.drawRoundRect(x, y, x + 6, y + 6, 1.2f, 1.2f, paint);
                    break;
                case "large_grid":
                    canvas.drawRoundRect(4, 3, 20, 21, 2.5f, 2.5f, paint);
                    line(canvas, 4, 14, 20, 14);
                    break;
                case "edit":
                    line(canvas, 20, 14, 20, 21, 3, 21, 3, 5, 10, 5);
                    path.moveTo(8, 16);
                    path.lineTo(9, 12);
                    path.lineTo(18, 3);
                    path.lineTo(21, 6);
                    path.lineTo(12, 15);
                    path.close();
                    canvas.drawPath(path, paint);
                    line(canvas, 16, 5, 19, 8);
                    break;
                case "share":
                    line(canvas, 7, 8, 4, 8, 4, 21, 20, 21, 20, 8, 17, 8);
                    line(canvas, 12, 15, 12, 2, 7, 7);
                    line(canvas, 12, 2, 17, 7);
                    break;
                case "comment":
                    path.moveTo(6, 19);
                    path.cubicTo(1, 17, 1, 7, 7, 4.5f);
                    path.cubicTo(14, 1, 22, 6, 21, 13);
                    path.cubicTo(20, 18, 15, 20, 10, 19);
                    path.lineTo(4, 22);
                    path.close();
                    canvas.drawPath(path, paint);
                    break;
                case "download":
                    line(canvas, 12, 2, 12, 15);
                    line(canvas, 7, 10, 12, 15, 17, 10);
                    line(canvas, 4, 14, 4, 21, 20, 21, 20, 14);
                    break;
                case "calendar":
                    canvas.drawRoundRect(3, 5, 21, 21, 2.5f, 2.5f, paint);
                    line(canvas, 3, 10, 21, 10);
                    line(canvas, 8, 2.5f, 8, 7);
                    line(canvas, 16, 2.5f, 16, 7);
                    line(canvas, 8, 15, 11, 17.5f, 16, 13);
                    break;
                case "trash":
                    line(canvas, 3, 6, 21, 6);
                    canvas.drawRoundRect(6, 6, 18, 21.5f, 2, 2, paint);
                    line(canvas, 9, 5.5f, 9, 2.5f, 15, 2.5f, 15, 5.5f);
                    line(canvas, 10, 10, 10, 17.5f);
                    line(canvas, 14, 10, 14, 17.5f);
                    break;
                case "check":
                    line(canvas, 4, 12, 10, 18, 21, 6);
                    break;
                case "restore":
                    canvas.drawArc(4, 4, 21, 21, 205, 290, false, paint);
                    line(canvas, 3, 4, 3, 10, 9, 10);
                    line(canvas, 12, 8, 12, 13, 16, 13);
                    break;
                case "nas":
                case "server":
                    canvas.drawRoundRect(3, 6, 21, 21, 2.5f, 2.5f, paint);
                    line(canvas, 5, 6, 5, 2.5f, 19, 2.5f, 19, 6);
                    line(canvas, 7, 10, 7, 15);
                    line(canvas, 11, 10, 11, 15);
                    dot(canvas, 17, 17, 1);
                    break;
                case "storage":
                    canvas.drawOval(3.5f, 2.5f, 20.5f, 8.5f, paint);
                    line(canvas, 3.5f, 5.5f, 3.5f, 18);
                    line(canvas, 20.5f, 5.5f, 20.5f, 18);
                    canvas.drawArc(3.5f, 8.5f, 20.5f, 14.5f, 0, 180, false, paint);
                    canvas.drawArc(3.5f, 15, 20.5f, 21, 0, 180, false, paint);
                    break;
                case "cloud":
                case "oss":
                case "cos":
                case "s3":
                    cloud(canvas);
                    break;
                case "shield":
                    path.moveTo(12, 2);
                    path.lineTo(20.5f, 5.5f);
                    path.lineTo(20, 13);
                    path.cubicTo(19.5f, 17, 16, 20, 12, 22);
                    path.cubicTo(8, 20, 4.5f, 17, 4, 13);
                    path.lineTo(3.5f, 5.5f);
                    path.close();
                    canvas.drawPath(path, paint);
                    line(canvas, 8, 12, 11, 15, 16.5f, 9.5f);
                    break;
                case "lock":
                    canvas.drawRoundRect(5, 10, 19, 22, 2.5f, 2.5f, paint);
                    path.moveTo(8, 10);
                    path.lineTo(8, 7);
                    path.cubicTo(8, 1, 16, 1, 16, 7);
                    path.lineTo(16, 10);
                    canvas.drawPath(path, paint);
                    line(canvas, 12, 15, 12, 18);
                    break;
                case "key":
                    canvas.drawCircle(8, 8, 4.8f, paint);
                    line(canvas, 11.5f, 11.5f, 21, 21, 22, 17.5f);
                    line(canvas, 16.5f, 16.5f, 18.5f, 14.5f);
                    dot(canvas, 7, 7, .75f);
                    break;
                case "settings":
                    canvas.drawCircle(12, 12, 6, paint);
                    canvas.drawCircle(12, 12, 2.2f, paint);
                    for (int i = 0; i < 8; i++) {
                        double a = i * Math.PI / 4;
                        line(
                                canvas,
                                12 + (float) Math.cos(a) * 6,
                                12 + (float) Math.sin(a) * 6,
                                12 + (float) Math.cos(a) * 9,
                                12 + (float) Math.sin(a) * 9);
                    }
                    break;
                case "search":
                    canvas.drawCircle(10, 10, 6.5f, paint);
                    line(canvas, 15, 15, 21, 21);
                    break;
                case "logout":
                    line(canvas, 10, 3, 4, 3, 4, 21, 10, 21);
                    line(canvas, 10, 12, 22, 12, 17, 7);
                    line(canvas, 22, 12, 17, 17);
                    break;
                case "copy":
                    line(canvas, 6, 17, 3, 17, 3, 3, 16, 3, 16, 6);
                    canvas.drawRoundRect(7, 7, 21, 21, 2, 2, paint);
                    break;
                case "mail":
                    canvas.drawRoundRect(2.5f, 7, 21.5f, 21, 2, 2, paint);
                    line(canvas, 3, 8, 12, 15, 21, 8);
                    path.moveTo(7, 7);
                    path.lineTo(7, 3);
                    path.lineTo(17, 3);
                    path.lineTo(17, 7);
                    canvas.drawPath(path, paint);
                    break;
                case "baby":
                    canvas.drawCircle(12, 12, 8.2f, paint);
                    canvas.drawArc(9, 2, 15, 8, 10, 250, false, paint);
                    dot(canvas, 9, 11, .7f);
                    dot(canvas, 15, 11, .7f);
                    canvas.drawArc(8.5f, 11.5f, 15.5f, 17.5f, 15, 150, false, paint);
                    break;
                case "close":
                    line(canvas, 5, 5, 19, 19);
                    line(canvas, 19, 5, 5, 19);
                    break;
                default:
                    canvas.drawRoundRect(4, 4, 20, 20, 4, 4, paint);
                    dot(canvas, 12, 12, 1.2f);
                    break;
            }
            canvas.restore();
        }
    }
}
