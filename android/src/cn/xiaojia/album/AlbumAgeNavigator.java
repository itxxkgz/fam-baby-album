package cn.xiaojia.album;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.TreeMap;

/** Persistent edge handle: tap for a drawer or drag to browse with a live age preview. */
final class AlbumAgeNavigator extends FrameLayout {
    private static final int TAB_IDLE = 0xFFF5EEE7,
            TAB_PRESSED = 0xFFFBE0BF,
            TAB_EDGE = 0xFFCDBFB1,
            TAB_PRESSED_EDGE = 0xFFD19F73,
            CHEVRON_INK = 0xFF675143,
            CHEVRON_PRESSED_INK = 0xFF65472F,
            AGE_SURFACE = 0xFFFFFAF2,
            AGE_EDGE = 0xFFDEC2AA,
            AGE_INK = 0xFF50392B;
    private static final float TAB_WIDTH = 18, TAB_HEIGHT = 36, AGE_GAP = 8;

    interface Listener {
        void onRequestIndex();

        void onDateSelected(String date);

        void onDragStarted();

        void onDatePreview(String date);

        void onDragFinished(String date);

        void onDragCancelled();
    }

    private static final class Day {
        final String date, monthLabel;
        final JSONObject source;
        final int count;

        Day(JSONObject source, String date) {
            this.source = source;
            this.date = date;
            count = Math.max(1, source.optInt("count", 1));
            String label = source.optString("month_label", "").trim();
            if (label.isEmpty()) label = source.optString("age", "").trim();
            monthLabel = label.isEmpty() ? Ui.month(date.substring(0, 7)) : label;
        }
    }

    private final Listener listener;
    private final Handle handle;
    private final AlbumHandlePosition handlePosition = new AlbumHandlePosition();
    private final ArrayList<Day> days = new ArrayList<>();
    private final ArrayList<String> dates = new ArrayList<>();
    private final HashMap<String, Integer> dayIndices = new HashMap<>();
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final RectF shadowBox = new RectF();
    private final Typeface ageTypeface = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private String currentDate = "", error = "";
    private float viewportDayFraction;
    private boolean viewportAtStart = true, viewportAtEnd, awaitingLocation;
    private int preferredTrackTop;
    private int selected = -1;
    private boolean loading, loaded, disposed, drawerOpen, bubble;
    private View drawerView;
    private AlbumAgeDrawer drawer;

    AlbumAgeNavigator(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        setWillNotDraw(false);
        setClickable(false);
        setFocusable(false);
        setClipChildren(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        handle = new Handle(context);
        handlePosition.setFraction(0);
        addView(handle, new LayoutParams(dp(48), LayoutParams.MATCH_PARENT, Gravity.RIGHT));
    }

    /** UI thread only. An index arriving during a drag immediately previews its current target. */
    void setItems(JSONArray items) {
        if (disposed) return;
        TreeMap<String, Day> ordered = new TreeMap<>(Collections.reverseOrder());
        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null || item.optInt("count", 1) <= 0) continue;
                try {
                    String date = LocalDate.parse(item.optString("date", "")).toString();
                    ordered.put(date, new Day(item, date));
                } catch (RuntimeException ignored) {
                    // An invalid entry cannot become a destination or show an unreadable date.
                }
            }
        }
        days.clear();
        dates.clear();
        dayIndices.clear();
        days.addAll(ordered.values());
        for (Day day : days) {
            dayIndices.put(day.date, dates.size());
            dates.add(day.date);
        }
        loaded = true;
        loading = false;
        error = "";
        selected = dateIndex(currentDate);
        if (handle.tracking && !handle.cancelled) {
            handle.startIndex = AlbumAgeNavigation.nearestDateIndex(dates, handle.startDate);
            selected = handle.startIndex;
            if (handle.dragging) previewDrag(false);
        }
        if (drawerOpen) renderDrawer();
        syncViewport();
        refresh();
    }

    void setLoading(boolean value) {
        if (disposed) return;
        loading = value;
        if (value) error = "";
        if (drawerOpen && drawer == null) renderDrawer();
        refresh();
    }

    void setError(String message) {
        if (disposed) return;
        loading = false;
        awaitingLocation = false;
        error = message == null || message.trim().isEmpty() ? "暂时无法读取，请稍后重试" : message.trim();
        if (drawerOpen) renderDrawer();
        else if (!bubble) {
            Toast.makeText(getContext(), error, Toast.LENGTH_LONG).show();
            handle.announceForAccessibility(error);
        }
        syncViewport();
        refresh();
    }

    void setCurrentDate(String date) {
        setViewport(date, 0, false, false);
    }

    void preload() {
        requestIfNeeded();
    }

    int dayCount(String date) {
        Integer index = dayIndices.get(date);
        return index == null ? 0 : days.get(index).count;
    }

    void setTrackTop(int pixels) {
        if (disposed || handle.tracking || pixels <= 0 || preferredTrackTop == pixels) return;
        preferredTrackTop = pixels;
        refresh();
    }

    void setViewport(String date, float dayFraction, boolean atStart, boolean atEnd) {
        if (disposed) return;
        String nextDate = date == null ? "" : date;
        boolean changedDate = !nextDate.equals(currentDate);
        currentDate = nextDate;
        viewportDayFraction = dayFraction;
        viewportAtStart = atStart;
        viewportAtEnd = atEnd;
        syncViewport();
        if (drawer != null && changedDate) drawer.setCurrentDate(currentDate);
        refresh();
    }

    /** Host calls only after the destination list has completed its layout. */
    void finishLocation() {
        awaitingLocation = false;
        syncViewport();
        refresh();
    }

    void locationFailed(String message) {
        awaitingLocation = false;
        syncViewport();
        refresh();
        if (!handle.tracking) Toast.makeText(getContext(), message, Toast.LENGTH_LONG).show();
    }

    private int dateIndex(String date) {
        Integer index = dayIndices.get(date);
        return index == null ? AlbumAgeNavigation.nearestDateIndex(dates, date) : index;
    }

    private void syncViewport() {
        if ((handle.tracking && !handle.cancelled) || awaitingLocation || (loading && loaded))
            return;
        selected = dateIndex(currentDate);
        handlePosition.setFraction(
                AlbumTimelineProgress.fraction(
                        selected,
                        days.size(),
                        viewportDayFraction,
                        viewportAtStart,
                        viewportAtEnd));
    }

    private float trackTop() {
        float wanted = preferredTrackTop > 0 ? preferredTrackTop : dp(156);
        return Math.min(Math.max(dp(24), wanted), Math.max(0, getHeight()) * .45f);
    }

    private float trackBottom() {
        return Math.max(trackTop(), getHeight() - dp(24));
    }

    /** Leaves the small handle visible; neither scrolling nor a timer hides it. */
    void collapse() {
        drawerOpen = false;
        bubble = false;
        if (drawerView != null) removeView(drawerView);
        drawerView = null;
        drawer = null;
        handle.cancelGesture();
        selected = AlbumAgeNavigation.nearestDateIndex(dates, currentDate);
        refresh();
    }

    boolean collapseIfExpanded() {
        if (!drawerOpen && !bubble) return false;
        collapse();
        return true;
    }

    void dispose() {
        if (disposed) return;
        collapse();
        disposed = true;
        handle.setEnabled(false);
    }

    private int dp(float value) {
        return Ui.dp(getContext(), value);
    }

    private float sp(float value) {
        return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, value, getResources().getDisplayMetrics());
    }

    private float px(float value) {
        return getResources().getDisplayMetrics().density * value;
    }

    /** Small translucent layers keep the edge soft without a software-rendered blur. */
    private void drawSoftShadow(Canvas canvas, RectF bounds, float radius) {
        paint.setStyle(Paint.Style.FILL);
        for (int layer = 3; layer >= 1; layer--) {
            float spread = px(layer * .65f);
            shadowBox.set(bounds);
            shadowBox.inset(-spread, -spread);
            shadowBox.offset(0, px(.9f));
            paint.setColor(layer == 3 ? 0x034C3425 : layer == 2 ? 0x044C3425 : 0x064C3425);
            canvas.drawRoundRect(shadowBox, radius + spread, radius + spread, paint);
        }
    }

    private void refresh() {
        handle.setContentDescription("快速定位照片，点击按年龄或日期选择，也可以按住上下拖动");
        handle.invalidate();
        invalidate();
    }

    void refreshUsedIndex() {
        if (loaded || loading) listener.onRequestIndex();
    }

    private void requestIfNeeded() {
        if (disposed || loading || (loaded && error.isEmpty())) return;
        loading = true;
        error = "";
        refresh();
        listener.onRequestIndex();
    }

    private void openDrawer() {
        if (disposed) return;
        bubble = false;
        drawerOpen = true;
        requestIfNeeded();
        renderDrawer();
        refresh();
    }

    private void renderDrawer() {
        if (!drawerOpen || disposed) return;
        if (drawerView != null) removeView(drawerView);
        drawer = null;
        if (loaded && !loading && error.isEmpty() && !days.isEmpty()) {
            JSONArray items = new JSONArray();
            for (Day day : days) items.put(day.source);
            drawer =
                    new AlbumAgeDrawer(
                            getContext(),
                            items,
                            currentDate,
                            new AlbumAgeDrawer.Listener() {
                                @Override
                                public void onDateSelected(String date) {
                                    chooseDate(date);
                                }

                                @Override
                                public void onDismiss() {
                                    collapse();
                                }
                            });
            drawerView = drawer;
        } else {
            drawerView = statusDrawer();
        }
        addView(drawerView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    private View statusDrawer() {
        FrameLayout overlay = new FrameLayout(getContext());
        overlay.setBackgroundColor(0x66000000);
        overlay.setOnClickListener(v -> collapse());
        overlay.setContentDescription("关闭快速定位");
        LinearLayout panel = Ui.column(getContext());
        panel.setBackgroundColor(Ui.BG);
        panel.setClickable(true);
        panel.setPadding(dp(18), dp(12), dp(14), dp(20));
        int width = Math.max(dp(200), Math.round(getWidth() * .70f));
        width = Math.min(Math.max(getWidth(), dp(200)), width);
        ScrollView scroll = new ScrollView(getContext());
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Ui.BG);
        scroll.setClickable(true);
        scroll.addView(panel, new ScrollView.LayoutParams(-1, -2));
        overlay.addView(scroll, new LayoutParams(width, LayoutParams.MATCH_PARENT, Gravity.RIGHT));
        LinearLayout heading = Ui.row(getContext());
        TextView title = Ui.text(getContext(), "快速定位", 19, Ui.INK);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        heading.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1));
        FrameLayout close = new FrameLayout(getContext());
        close.setContentDescription("关闭快速定位");
        close.setBackground(Ui.ripple(Ui.BG, 22, getContext()));
        close.setOnClickListener(v -> collapse());
        close.addView(
                new Ui.Icon(getContext(), "close", Ui.MUTED),
                new LayoutParams(dp(22), dp(22), Gravity.CENTER));
        heading.addView(close, new LinearLayout.LayoutParams(dp(44), dp(48)));
        panel.addView(heading);
        if (loading) {
            ProgressBar progress = new ProgressBar(getContext());
            LinearLayout.LayoutParams progressParams =
                    new LinearLayout.LayoutParams(dp(28), dp(28));
            progressParams.topMargin = dp(32);
            progressParams.bottomMargin = dp(18);
            panel.addView(progress, progressParams);
        }
        String titleText = loading ? "正在整理回忆" : !error.isEmpty() ? "暂时无法读取" : "还没有可定位的照片";
        TextView state = Ui.text(getContext(), titleText, 17, Ui.INK);
        state.setPadding(0, dp(24), 0, dp(12));
        panel.addView(state, new LinearLayout.LayoutParams(-1, -2));
        String message =
                loading ? "等日期准备好，就能回到想看的那一段时光。" : !error.isEmpty() ? error : "上传照片或调整筛选后，再来寻找回忆。";
        TextView detail = Ui.text(getContext(), message, 14, Ui.MUTED);
        detail.setLineSpacing(dp(4), 1);
        panel.addView(detail, new LinearLayout.LayoutParams(-1, -2));
        if (!error.isEmpty()) {
            TextView retry = Ui.text(getContext(), "重新读取", 15, Ui.ACCENT_DARK);
            retry.setGravity(Gravity.CENTER);
            retry.setBackground(Ui.button(getContext(), false));
            LinearLayout.LayoutParams retryParams = new LinearLayout.LayoutParams(-1, dp(48));
            retryParams.topMargin = dp(24);
            panel.addView(retry, retryParams);
            retry.setOnClickListener(
                    v -> {
                        requestIfNeeded();
                        renderDrawer();
                    });
        }
        return overlay;
    }

    private void previewDrag(boolean haptic) {
        if (days.isEmpty()) return;
        int initial = Math.max(0, Math.min(days.size() - 1, handle.startIndex));
        float startCenter = handlePosition.startCenterWithin(trackTop(), trackBottom());
        float delta = handle.centerY() - startCenter;
        int remaining = delta >= 0 ? days.size() - 1 - initial : initial;
        float available = delta >= 0 ? trackBottom() - startCenter : startCenter - trackTop();
        if (available <= 0) {
            selected = initial;
            emitPreview();
            return;
        }
        float travel =
                remaining == 0 ? available : available * Math.max(1, days.size() - 1) / remaining;
        int next =
                remaining == 0
                        ? initial
                        : AlbumAgeNavigation.indexAfterDrag(delta, travel, initial, days.size());
        boolean changed = selected != next;
        boolean changedMonth =
                selected < 0 || !days.get(selected).monthLabel.equals(days.get(next).monthLabel);
        selected = next;
        if (haptic && changed && changedMonth)
            handle.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        emitPreview();
        refresh();
    }

    private void emitPreview() {
        if (!handle.dragNotified || handle.cancelled || selected < 0 || !handle.movedBeyondSlop)
            return;
        String date = days.get(selected).date;
        if (!date.equals(handle.previewDate)) {
            handle.previewDate = date;
            listener.onDatePreview(date);
        }
    }

    private void chooseDate(String date) {
        if (disposed || loading || !error.isEmpty() || !dates.contains(date)) return;
        awaitingLocation = true;
        currentDate = date;
        collapse();
        listener.onDateSelected(date);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!bubble || drawerOpen || disposed || getWidth() < dp(120)) return;
        String title =
                selected >= 0 && selected < days.size()
                        ? days.get(selected).monthLabel
                        : loading
                                ? "正在读取"
                                : !error.isEmpty()
                                        ? "暂时无法定位"
                                        : selected < 0 ? "暂无照片" : days.get(selected).monthLabel;
        text.setTextSize(sp(13));
        text.setTypeface(ageTypeface);
        float right = getWidth() - px(TAB_WIDTH + AGE_GAP);
        float width =
                Math.min(
                        Math.max(dp(72), Math.min(dp(160), text.measureText(title) + dp(24))),
                        right - dp(8));
        Paint.FontMetrics metrics = text.getFontMetrics();
        float height = Math.max(dp(30), metrics.descent - metrics.ascent + dp(12));
        float top =
                Math.max(
                        dp(4),
                        Math.min(getHeight() - height - dp(4), handle.centerY() - height / 2));
        box.set(right - width, top, right, top + height);
        drawSoftShadow(canvas, box, height / 2);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(AGE_SURFACE);
        canvas.drawRoundRect(box, height / 2, height / 2, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(px(.75f));
        paint.setColor(AGE_EDGE);
        canvas.drawRoundRect(box, height / 2, height / 2, paint);
        paint.setStyle(Paint.Style.FILL);
        String fitted =
                TextUtils.ellipsize(
                                title, text, Math.max(0, width - dp(20)), TextUtils.TruncateAt.END)
                        .toString();
        text.setColor(AGE_INK);
        canvas.drawText(
                fitted,
                box.centerX() - text.measureText(fitted) / 2,
                box.centerY() - (metrics.ascent + metrics.descent) / 2,
                text);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (oldw > 0 && (w != oldw || h != oldh)) {
            bubble = false;
            handle.cancelGesture();
            if (drawerOpen) post(this::renderDrawer);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        collapse();
        super.onDetachedFromWindow();
    }

    private final class Handle extends View {
        private final RectF tabBounds = new RectF();
        private final Path chevron = new Path();
        private final int slop;
        private boolean tracking, dragging, cancelled, dragNotified, movedBeyondSlop;
        private float downY, lastY;
        private int startIndex;
        private String startDate = "";
        private String previewDate = "";
        private final Runnable hold =
                () -> {
                    if (tracking && !cancelled && !dragging) startDrag();
                };

        Handle(Context context) {
            super(context);
            slop = ViewConfiguration.get(context).getScaledTouchSlop();
            setClickable(true);
            setFocusable(true);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        }

        @Override
        protected void drawableStateChanged() {
            super.drawableStateChanged();
            invalidate();
        }

        float centerY() {
            return handlePosition.centerWithin(trackTop(), trackBottom());
        }

        void cancelGesture() {
            removeCallbacks(hold);
            handlePosition.finish(false);
            if (tracking) {
                cancelled = true;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
            }
            setPressed(false);
            if (dragNotified) {
                dragNotified = false;
                movedBeyondSlop = false;
                listener.onDragCancelled();
            }
            // A cancelled pointer still consumes its remaining events, but no longer
            // owns the handle: catch up with any list motion that happened meanwhile.
            syncViewport();
        }

        void startDrag() {
            dragging = true;
            bubble = true;
            removeCallbacks(hold);
            if (!dragNotified) {
                dragNotified = true;
                listener.onDragStarted();
            }
            requestIfNeeded();
            handlePosition.moveWithin(lastY, trackTop(), trackBottom());
            previewDrag(false);
            refresh();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (disposed) return;
            float center = centerY();
            boolean active = isPressed() && !cancelled;
            // The right-hand rounding lies outside the screen, leaving a flush page edge.
            tabBounds.set(
                    getWidth() - px(TAB_WIDTH),
                    center - px(TAB_HEIGHT / 2),
                    getWidth() + px(12),
                    center + px(TAB_HEIGHT / 2));
            drawSoftShadow(canvas, tabBounds, px(11));
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(active ? TAB_PRESSED : TAB_IDLE);
            canvas.drawRoundRect(tabBounds, px(11), px(11), paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(px(.75f));
            paint.setColor(active ? TAB_PRESSED_EDGE : TAB_EDGE);
            canvas.drawRoundRect(tabBounds, px(11), px(11), paint);

            float arrowCenter = getWidth() - px(TAB_WIDTH / 2);
            chevron.reset();
            chevron.moveTo(arrowCenter + px(2.6f), center - px(4.4f));
            chevron.lineTo(arrowCenter - px(2.6f), center);
            chevron.lineTo(arrowCenter + px(2.6f), center + px(4.4f));
            paint.setColor(active ? CHEVRON_PRESSED_INK : CHEVRON_INK);
            paint.setStrokeWidth(px(1.65f));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            canvas.drawPath(chevron, paint);
            paint.setStyle(Paint.Style.FILL);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (disposed) return false;
            int action = event.getActionMasked();
            float y = event.getY();
            if (action == MotionEvent.ACTION_DOWN) {
                if (drawerOpen || Math.abs(y - centerY()) > dp(24)) return false;
                tracking = true;
                dragging = false;
                cancelled = false;
                dragNotified = false;
                movedBeyondSlop = false;
                previewDate = "";
                downY = lastY = y;
                handlePosition.beginWithin(y, trackTop(), trackBottom());
                startDate = currentDate;
                startIndex = AlbumAgeNavigation.nearestDateIndex(dates, startDate);
                selected = startIndex;
                setPressed(true);
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                requestIfNeeded();
                postDelayed(hold, ViewConfiguration.getLongPressTimeout());
                return true;
            }
            if (!tracking) return false;
            if (action == MotionEvent.ACTION_POINTER_DOWN || event.getPointerCount() > 1) {
                cancelGesture();
                bubble = false;
                selected = AlbumAgeNavigation.nearestDateIndex(dates, currentDate);
                refresh();
                return true;
            }
            if (action == MotionEvent.ACTION_MOVE) {
                lastY = y;
                if (cancelled) return true;
                if (event.getX() < -dp(72)) {
                    cancelGesture();
                    bubble = false;
                    selected = AlbumAgeNavigation.nearestDateIndex(dates, currentDate);
                    refresh();
                    return true;
                }
                if (Math.abs(y - downY) > slop) movedBeyondSlop = true;
                if (movedBeyondSlop && !dragging) startDrag();
                if (dragging) {
                    handlePosition.moveWithin(y, trackTop(), trackBottom());
                    refresh();
                    previewDrag(true);
                }
                return true;
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                removeCallbacks(hold);
                boolean wasDragging = dragging,
                        wasCancelled = cancelled || action == MotionEvent.ACTION_CANCEL;
                tracking = false;
                dragging = false;
                setPressed(false);
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                bubble = false;
                if (wasCancelled) {
                    handlePosition.finish(false);
                    if (dragNotified) {
                        dragNotified = false;
                        listener.onDragCancelled();
                    }
                    selected = AlbumAgeNavigation.nearestDateIndex(dates, currentDate);
                    syncViewport();
                } else if (wasDragging && movedBeyondSlop) {
                    awaitingLocation = true;
                    lastY = y;
                    handlePosition.moveWithin(y, trackTop(), trackBottom());
                    previewDrag(false);
                    handlePosition.finish(true);
                    dragNotified = false;
                    if (selected >= 0 && !days.isEmpty()) {
                        awaitingLocation = true;
                        listener.onDragFinished(days.get(selected).date);
                    } else {
                        listener.onDragCancelled();
                        Toast.makeText(
                                        getContext(),
                                        loading
                                                ? "日期还在准备，请稍后再试"
                                                : !error.isEmpty()
                                                        ? "暂时无法定位，点击箭头可以重试"
                                                        : "还没有可定位的照片",
                                        Toast.LENGTH_SHORT)
                                .show();
                    }
                } else {
                    handlePosition.finish(false);
                    if (dragNotified) {
                        dragNotified = false;
                        listener.onDragCancelled();
                    }
                    syncViewport();
                    if (!wasDragging) performClick();
                }
                refresh();
                return true;
            }
            return true;
        }

        @Override
        public boolean performClick() {
            super.performClick();
            openDrawer();
            return true;
        }

        @Override
        public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setClassName(Button.class.getName());
            info.setClickable(true);
            int top = Math.max(0, Math.round(centerY()) - dp(24));
            int bottom = Math.min(getHeight(), Math.round(centerY()) + dp(24));
            info.setBoundsInParent(
                    new Rect(getLeft(), getTop() + top, getRight(), getTop() + bottom));
            int[] screen = new int[2];
            getLocationOnScreen(screen);
            info.setBoundsInScreen(
                    new Rect(
                            screen[0],
                            screen[1] + top,
                            screen[0] + getWidth(),
                            screen[1] + bottom));
            info.addAction(
                    new AccessibilityNodeInfo.AccessibilityAction(
                            AccessibilityNodeInfo.ACTION_CLICK, "打开年龄与日期列表"));
        }
    }
}
