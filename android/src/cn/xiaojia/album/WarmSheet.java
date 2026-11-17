package cn.xiaojia.album;

import android.app.Activity;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** A content-sized warm sheet with a scrolling body and reachable actions above the keyboard. */
final class WarmSheet {
    private static final java.util.WeakHashMap<
                    Activity, java.util.ArrayList<java.lang.ref.WeakReference<WarmSheet>>>
            OPEN = new java.util.WeakHashMap<>();
    final Dialog dialog;
    final LinearLayout body, footer;
    private final Activity activity;
    private final View close;
    private Runnable closeAction;
    private boolean cancelable = true;
    private final java.util.ArrayList<Runnable> dismissActions = new java.util.ArrayList<>();
    private LinearLayout pageRoot, scrollContent, heading;
    private ScrollView scroller;
    private View handle;
    private boolean compact;

    WarmSheet(Activity activity, String title) {
        this.activity = activity;
        dialog =
                new Dialog(activity) {
                    @Override
                    public void onBackPressed() {
                        requestClose();
                    }
                };
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root =
                new LinearLayout(activity) {
                    @Override
                    protected void onMeasure(int width, int height) {
                        Rect visible = new Rect();
                        activity.getWindow().getDecorView().getWindowVisibleDisplayFrame(visible);
                        int maximum =
                                Math.round(
                                        activity.getResources().getDisplayMetrics().heightPixels
                                                * .92f);
                        if (visible.height() > 0)
                            maximum = Math.min(maximum, visible.height() - dp(12));
                        if (MeasureSpec.getMode(height) != MeasureSpec.UNSPECIFIED)
                            maximum = Math.min(maximum, MeasureSpec.getSize(height));
                        maximum = Math.max(0, maximum);
                        if (heading != null && footer != null) {
                            int availableWidth =
                                    MeasureSpec.makeMeasureSpec(
                                            MeasureSpec.getSize(width), MeasureSpec.EXACTLY);
                            heading.measure(
                                    availableWidth,
                                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                            footer.measure(
                                    availableWidth,
                                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                            compactLayout(
                                    maximum
                                            < heading.getMeasuredHeight()
                                                    + footer.getMeasuredHeight()
                                                    + dp(140));
                        }
                        super.onMeasure(
                                width, MeasureSpec.makeMeasureSpec(maximum, MeasureSpec.AT_MOST));
                    }
                };
        pageRoot = root;
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(Ui.round(Ui.BG, 24, activity));
        root.setPadding(0, dp(10), 0, dp(12));
        handle = new View(activity);
        handle.setBackground(Ui.round(Ui.LINE, 2, activity));
        handle.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams handleParams = new LinearLayout.LayoutParams(dp(30), dp(4));
        handleParams.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(handle, handleParams);
        heading = Ui.row(activity);
        heading.setPadding(dp(20), dp(6), dp(6), dp(4));
        TextView label = Ui.text(activity, title, 20, Ui.INK);
        label.setTypeface(null, Typeface.BOLD);
        heading.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        FrameLayout closeButton = new FrameLayout(activity);
        closeButton.addView(
                new Ui.Icon(activity, "close", Ui.MUTED),
                new FrameLayout.LayoutParams(dp(21), dp(21), Gravity.CENTER));
        closeButton.setContentDescription("关闭");
        closeButton.setFocusable(true);
        closeButton.setBackground(Ui.ripple(Ui.BG, 24, activity));
        closeButton.setOnClickListener(v -> requestClose());
        close = closeButton;
        heading.addView(close, new LinearLayout.LayoutParams(dp(48), dp(48)));
        root.addView(heading, new LinearLayout.LayoutParams(-1, -2));
        ScrollView scroll = new ScrollView(activity);
        scroller = scroll;
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        body = Ui.column(activity);
        body.setPadding(dp(20), dp(4), dp(20), dp(6));
        scrollContent = Ui.column(activity);
        scrollContent.addView(body, new LinearLayout.LayoutParams(-1, -2));
        scroll.addView(scrollContent, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, -2, 1));
        footer = Ui.column(activity);
        footer.setPadding(dp(20), 0, dp(20), 0);
        root.addView(footer, new LinearLayout.LayoutParams(-1, -2));
        dialog.setContentView(root);
        dialog.setOnDismissListener(
                d -> {
                    java.util.ArrayList<java.lang.ref.WeakReference<WarmSheet>> open =
                            OPEN.get(activity);
                    if (open != null) {
                        open.removeIf(
                                reference -> reference.get() == null || reference.get() == this);
                        if (open.isEmpty()) OPEN.remove(activity);
                    }
                    for (Runnable action : new java.util.ArrayList<>(dismissActions)) action.run();
                });
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnKeyListener(
                (d, code, event) -> {
                    if (code != KeyEvent.KEYCODE_BACK) return false;
                    if (event.getAction() == KeyEvent.ACTION_UP) requestClose();
                    return true;
                });
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(.38f);
            window.setGravity(Gravity.BOTTOM);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            window.setNavigationBarColor(Ui.BG);
            window.setStatusBarColor(Color.TRANSPARENT);
        }
    }

    private int dp(float value) {
        return Ui.dp(activity, value);
    }

    private void compactLayout(boolean value) {
        if (compact == value || scrollContent == null) return;
        View focused = pageRoot.findFocus();
        int selectionStart =
                focused instanceof android.widget.EditText
                        ? ((android.widget.EditText) focused).getSelectionStart()
                        : -1;
        int selectionEnd =
                focused instanceof android.widget.EditText
                        ? ((android.widget.EditText) focused).getSelectionEnd()
                        : -1;
        compact = value;
        if (value) {
            pageRoot.removeView(handle);
            pageRoot.removeView(heading);
            pageRoot.removeView(footer);
            scrollContent.addView(handle, 0);
            scrollContent.addView(heading, 1);
            scrollContent.addView(footer);
        } else {
            scrollContent.removeView(handle);
            scrollContent.removeView(heading);
            scrollContent.removeView(footer);
            pageRoot.addView(handle, 0);
            pageRoot.addView(heading, 1);
            pageRoot.addView(footer);
        }
        if (focused != null) {
            focused.requestFocus();
            if (focused instanceof android.widget.EditText
                    && selectionStart >= 0
                    && selectionEnd >= 0) {
                android.widget.EditText field = (android.widget.EditText) focused;
                int length = field.length();
                field.setSelection(
                        Math.min(selectionStart, length), Math.min(selectionEnd, length));
            }
            focused.post(
                    () -> {
                        if (focused.isAttachedToWindow() && focused.hasFocus())
                            focused.requestRectangleOnScreen(
                                    new Rect(0, 0, focused.getWidth(), focused.getHeight()), false);
                    });
        }
    }

    TextView primary(String label, Runnable click) {
        return action(label, true, click);
    }

    TextView secondary(String label, Runnable click) {
        return action(label, false, click);
    }

    private TextView action(String label, boolean primary, Runnable click) {
        TextView button = Ui.text(activity, label, 16, primary ? Color.WHITE : Ui.MUTED);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(50));
        button.setPadding(dp(12), dp(12), dp(12), dp(12));
        button.setTypeface(null, primary ? Typeface.BOLD : Typeface.NORMAL);
        button.setTextColor(
                new ColorStateList(
                        new int[][] {new int[] {-android.R.attr.state_enabled}, new int[] {}},
                        new int[] {Ui.MUTED, primary ? Color.WHITE : Ui.MUTED}));
        button.setBackground(primary ? Ui.button(activity, true) : Ui.ripple(Ui.BG, 12, activity));
        button.setFocusable(true);
        if (click != null) button.setOnClickListener(v -> click.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(primary ? 10 : 2);
        footer.addView(button, params);
        return button;
    }

    void onClose(Runnable action) {
        closeAction = action;
    }

    void onDismiss(Runnable action) {
        dismissActions.add(action);
    }

    void setCancelable(boolean value) {
        cancelable = value;
        dialog.setCancelable(value);
        dialog.setCanceledOnTouchOutside(false);
        close.setVisibility(value ? View.VISIBLE : View.INVISIBLE);
        close.setEnabled(value);
    }

    private void requestClose() {
        if (!cancelable) return;
        if (closeAction != null) closeAction.run();
        else dismiss();
    }

    void show() {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        if (!dialog.isShowing())
            OPEN.computeIfAbsent(activity, key -> new java.util.ArrayList<>())
                    .add(new java.lang.ref.WeakReference<>(this));
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) window.setLayout(-1, -2);
    }

    void dismiss() {
        dialog.dismiss();
    }

    boolean isShowing() {
        return dialog.isShowing();
    }

    static void dismissAll(Activity activity) {
        java.util.ArrayList<java.lang.ref.WeakReference<WarmSheet>> open = OPEN.remove(activity);
        if (open != null)
            for (java.lang.ref.WeakReference<WarmSheet> reference :
                    new java.util.ArrayList<>(open)) {
                WarmSheet sheet = reference.get();
                if (sheet != null) sheet.dismiss();
            }
    }
}
