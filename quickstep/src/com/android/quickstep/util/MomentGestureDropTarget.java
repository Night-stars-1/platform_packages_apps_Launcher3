/*
 * Copyright (C) 2026 The uwuAOSP Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.quickstep.util;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.view.WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL;

import static com.android.launcher3.util.Executors.UI_HELPER_EXECUTOR;

import android.app.TaskInfo;
import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Insets;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.hardware.display.DisplayManager;
import android.provider.Settings;
import android.util.Log;
import android.util.SparseArray;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.IWindowManager;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;
import android.view.WindowMetrics;

import androidx.annotation.Nullable;
import androidx.annotation.UiThread;

import com.android.launcher3.R;
import com.android.launcher3.Utilities;
import com.android.launcher3.util.VibratorWrapper;
import com.android.quickstep.GestureState;
import com.android.quickstep.TopTaskTracker.CachedTaskInfo;

import java.lang.reflect.Method;

/**
 * MIUI-style Moment gesture: while swiping a fullscreen app up from the nav bar, shows a drop
 * target in the top-right corner. Releasing the finger inside it converts the task into a Moment
 * floating window. Only active when {@code moment_enabled != 0} and
 * {@code moment_gesture_style == 1}.
 */
public final class MomentGestureDropTarget {

    private static final String TAG = "MomentGestureDropTarget";

    private static final String KEY_MOMENT_ENABLED = "moment_enabled";
    private static final String KEY_MOMENT_GESTURE_STYLE = "moment_gesture_style";
    private static final int MOMENT_GESTURE_STYLE_MIUI = 1;

    private static final int TARGET_WIDTH_DP = 120;
    private static final int TARGET_HEIGHT_DP = 160;
    private static final int TARGET_MARGIN_DP = 16;
    // Extra space around the target inside the window so the highlight scale is not clipped.
    private static final int WINDOW_PADDING_DP = 8;
    // The hit region extends this much beyond the target towards the screen center.
    private static final int HIT_SLOP_DP = 24;
    private static final float HIGHLIGHT_SCALE = 1.06f;
    private static final long FADE_DURATION_MS = 150;
    private static final long HIGHLIGHT_DURATION_MS = 120;

    // Window contexts are expensive to create (they register a window token), keep one per
    // display for the lifetime of the process.
    private static final SparseArray<Context> sWindowContexts = new SparseArray<>();

    private final Context mWindowContext;
    private final WindowManager mWindowManager;
    private final int mTaskId;
    private final Rect mHitRect = new Rect();
    private final TargetView mView;
    private boolean mAttached;
    private boolean mHighlighted;

    private MomentGestureDropTarget(Context windowContext, int taskId) {
        mWindowContext = windowContext;
        mWindowManager = windowContext.getSystemService(WindowManager.class);
        mTaskId = taskId;
        mView = new TargetView(windowContext);
    }

    /**
     * Shows the drop target for the given gesture if the feature is enabled and the gesture's
     * running task is a fullscreen standard task.
     *
     * @return the shown target, or {@code null} if the feature does not apply to this gesture.
     */
    @UiThread
    @Nullable
    public static MomentGestureDropTarget maybeShow(Context context, GestureState gestureState) {
        if (gestureState.isTrackpadGesture() || !isFeatureEnabled(context)) {
            return null;
        }
        CachedTaskInfo runningTask = gestureState.getRunningTask();
        TaskInfo baseTask = runningTask != null ? runningTask.getLegacyBaseTask() : null;
        int taskId = gestureState.getTopRunningTaskId();
        if (baseTask == null || baseTask.taskId != taskId
                || baseTask.getWindowingMode() != WINDOWING_MODE_FULLSCREEN
                || baseTask.getActivityType() != ACTIVITY_TYPE_STANDARD) {
            return null;
        }
        Context windowContext = getWindowContext(context, gestureState.getDisplayId());
        if (windowContext == null) {
            return null;
        }
        MomentGestureDropTarget target = new MomentGestureDropTarget(windowContext, taskId);
        target.attach();
        return target;
    }

    private static boolean isFeatureEnabled(Context context) {
        ContentResolver resolver = context.getContentResolver();
        return Settings.Secure.getInt(resolver, KEY_MOMENT_ENABLED, 0) != 0
                && Settings.Secure.getInt(resolver, KEY_MOMENT_GESTURE_STYLE, 0)
                        == MOMENT_GESTURE_STYLE_MIUI;
    }

    @Nullable
    private static Context getWindowContext(Context context, int displayId) {
        Context windowContext = sWindowContexts.get(displayId);
        if (windowContext != null) {
            return windowContext;
        }
        Context appContext = context.getApplicationContext();
        Display display = appContext.getSystemService(DisplayManager.class).getDisplay(displayId);
        if (display == null) {
            return null;
        }
        windowContext = appContext.createWindowContext(display, TYPE_NAVIGATION_BAR_PANEL, null);
        sWindowContexts.put(displayId, windowContext);
        return windowContext;
    }

    /** Task that will be converted when the gesture is released inside the target. */
    public int getTaskId() {
        return mTaskId;
    }

    /** Whether the last reported touch position is inside the target. */
    public boolean isHighlighted() {
        return mHighlighted;
    }

    /**
     * Updates the highlight state for a touch at the given display coordinates, vibrating once
     * when the touch enters the target.
     */
    @UiThread
    public void updateTouch(float x, float y) {
        boolean inside = mAttached && mHitRect.contains((int) x, (int) y);
        if (inside == mHighlighted) {
            return;
        }
        mHighlighted = inside;
        mView.setHighlighted(inside);
        if (inside) {
            VibratorWrapper.INSTANCE.get(mWindowContext).vibrate(VibratorWrapper.EFFECT_CLICK);
        }
    }

    /** Fades out and removes the target window. Safe to call multiple times. */
    @UiThread
    public void dismiss() {
        if (!mAttached) {
            return;
        }
        mAttached = false;
        mHighlighted = false;
        mView.animate().cancel();
        mView.animate()
                .alpha(0f)
                .setDuration(FADE_DURATION_MS)
                .withEndAction(() -> {
                    // The parent (ViewRootImpl) is assigned in addView, before the first layout.
                    if (mView.getParent() != null) {
                        mWindowManager.removeViewImmediate(mView);
                    }
                })
                .start();
    }

    private void attach() {
        WindowMetrics metrics = mWindowManager.getCurrentWindowMetrics();
        Rect bounds = metrics.getBounds();
        Insets insets = metrics.getWindowInsets().getInsetsIgnoringVisibility(
                WindowInsets.Type.statusBars() | WindowInsets.Type.displayCutout()
                        | WindowInsets.Type.navigationBars());
        int targetWidth = dpToPx(TARGET_WIDTH_DP);
        int targetHeight = dpToPx(TARGET_HEIGHT_DP);
        int margin = dpToPx(TARGET_MARGIN_DP);
        int padding = dpToPx(WINDOW_PADDING_DP);
        int hitSlop = dpToPx(HIT_SLOP_DP);

        int right = bounds.width() - insets.right - margin;
        int top = insets.top + margin;
        mHitRect.set(right - targetWidth - hitSlop, 0, bounds.width(),
                top + targetHeight + hitSlop);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                targetWidth + 2 * padding,
                targetHeight + 2 * padding,
                TYPE_NAVIGATION_BAR_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.RIGHT;
        lp.x = bounds.width() - right - padding;
        lp.y = top - padding;
        lp.windowAnimations = 0;
        lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        lp.setFitInsetsTypes(0);
        lp.setTitle(TAG);

        mView.setPadding(padding, padding, padding, padding);
        mView.setAlpha(0f);
        try {
            mWindowManager.addView(mView, lp);
        } catch (RuntimeException e) {
            Log.w(TAG, "Failed to add drop target window", e);
            return;
        }
        mAttached = true;
        mView.animate().alpha(1f).setDuration(FADE_DURATION_MS).start();
    }

    private int dpToPx(int dp) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp,
                mWindowContext.getResources().getDisplayMetrics()));
    }

    /**
     * Asks WindowManager to convert the task into a Moment floating window. The binder method is
     * added by the framework; it is invoked reflectively so this module builds against SDKs that
     * predate it.
     */
    public static void convertTaskToMoment(int taskId) {
        UI_HELPER_EXECUTOR.execute(() -> {
            IWindowManager wm = WindowManagerGlobal.getWindowManagerService();
            if (wm == null) {
                Log.w(TAG, "WindowManagerService unavailable, cannot convert task " + taskId);
                return;
            }
            try {
                Method method = wm.getClass().getMethod(
                        "convertTaskToMomentFromGesture", int.class);
                Object converted = method.invoke(wm, taskId);
                if (!Boolean.TRUE.equals(converted)) {
                    Log.w(TAG, "convertTaskToMomentFromGesture rejected task " + taskId);
                }
            } catch (NoSuchMethodException e) {
                Log.w(TAG, "convertTaskToMomentFromGesture not available", e);
            } catch (ReflectiveOperationException | RuntimeException e) {
                Log.w(TAG, "convertTaskToMomentFromGesture failed for task " + taskId, e);
            }
        });
    }

    private static final class TargetView extends View {

        private final Paint mFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF mRect = new RectF();
        private final Drawable mIcon;
        private final String mLabel;
        private final float mRadius;
        private final float mStrokeWidth;
        private final float mHighlightStrokeWidth;
        private final int mIconSize;
        private final int mLabelGap;
        private final int mFillAlpha;
        private final int mHighlightFillAlpha;

        TargetView(Context context) {
            super(context);
            boolean dark = Utilities.isDarkTheme(context);
            int fillColor = context.getColor(dark
                    ? android.R.color.system_accent2_800 : android.R.color.system_accent1_100);
            int accentColor = context.getColor(dark
                    ? android.R.color.system_accent1_200 : android.R.color.system_accent1_600);
            float density = context.getResources().getDisplayMetrics().density;
            mRadius = 24 * density;
            mStrokeWidth = 1.5f * density;
            mHighlightStrokeWidth = 3 * density;
            mIconSize = Math.round(40 * density);
            mLabelGap = Math.round(8 * density);
            mFillAlpha = 0xA6;
            mHighlightFillAlpha = 0xF0;

            mFillPaint.setColor(fillColor);
            mFillPaint.setAlpha(mFillAlpha);
            mStrokePaint.setStyle(Paint.Style.STROKE);
            mStrokePaint.setColor(accentColor);
            mStrokePaint.setStrokeWidth(mStrokeWidth);
            mTextPaint.setColor(accentColor);
            mTextPaint.setTextAlign(Paint.Align.CENTER);
            mTextPaint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14,
                    context.getResources().getDisplayMetrics()));

            mIcon = context.getDrawable(R.drawable.ic_moment_drop_target).mutate();
            mIcon.setTint(accentColor);
            mLabel = context.getString(R.string.moment_drop_target_label);
        }

        void setHighlighted(boolean highlighted) {
            mFillPaint.setAlpha(highlighted ? mHighlightFillAlpha : mFillAlpha);
            mStrokePaint.setStrokeWidth(highlighted ? mHighlightStrokeWidth : mStrokeWidth);
            invalidate();
            float scale = highlighted ? HIGHLIGHT_SCALE : 1f;
            animate().scaleX(scale).scaleY(scale).setDuration(HIGHLIGHT_DURATION_MS).start();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float halfStroke = mHighlightStrokeWidth / 2;
            mRect.set(getPaddingLeft() + halfStroke, getPaddingTop() + halfStroke,
                    getWidth() - getPaddingRight() - halfStroke,
                    getHeight() - getPaddingBottom() - halfStroke);
            canvas.drawRoundRect(mRect, mRadius, mRadius, mFillPaint);
            canvas.drawRoundRect(mRect, mRadius, mRadius, mStrokePaint);

            Paint.FontMetrics fm = mTextPaint.getFontMetrics();
            float textHeight = fm.descent - fm.ascent;
            float contentHeight = mIconSize + mLabelGap + textHeight;
            int iconLeft = Math.round(mRect.centerX() - mIconSize / 2f);
            int iconTop = Math.round(mRect.centerY() - contentHeight / 2);
            mIcon.setBounds(iconLeft, iconTop, iconLeft + mIconSize, iconTop + mIconSize);
            mIcon.draw(canvas);
            canvas.drawText(mLabel, mRect.centerX(),
                    iconTop + mIconSize + mLabelGap - fm.ascent, mTextPaint);
        }
    }
}
