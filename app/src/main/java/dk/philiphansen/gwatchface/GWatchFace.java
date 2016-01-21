/*
 * Copyright (C) 2014 The Android Open Source Project
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

package dk.philiphansen.gwatchface;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.VectorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.Time;
import android.util.Log;
import android.view.Gravity;
import android.view.SurfaceHolder;

import java.lang.ref.WeakReference;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Analog watch face with a ticking second hand. In ambient mode, the second hand isn't shown. On
 * devices with low-bit ambient mode, the hands are drawn without anti-aliasing in ambient mode.
 *
 *  With my own personal design twist!
 */
public class GWatchFace extends CanvasWatchFaceService {
    /**
     * Update rate in milliseconds for interactive mode. We update once a second to advance the
     * second hand.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<GWatchFace.Engine> mWeakReference;

        public EngineHandler(GWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            GWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine {
        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Calendar mCalendar;
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getTimeZone(intent.getStringExtra("time-zone")));
                mCalendar.setTimeInMillis(System.currentTimeMillis());
            }
        };
        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        /**
         * Whether the display supports burn in protection in ambient mode. When true, we want
         * to ensure that we draw as little as nessesarry in our ambient watch face.
         */
        boolean mBurnInProtection;

        boolean mAmbient;

        Paint mBackgroundPaint;
        Paint mAmbientBackgroundPaint;

        Paint mAmbientPaint;
        Paint mHourHandPaint;
        Paint mMinuteHandPaint;
        Paint mSecondHandPaint;
        Paint mDotPaint;

        Drawable mBackgroundVector;
        Drawable mAmbientBackgroundVector;
        Drawable mAmbientBurnInBackgroundVector;

        Rect mCardBounds;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(GWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_SHORT)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setViewProtectionMode(WatchFaceStyle.PROTECT_HOTWORD_INDICATOR)
                    .setViewProtectionMode(WatchFaceStyle.PROTECT_STATUS_BAR)
                    .setShowUnreadCountIndicator(true)
                    .setStatusBarGravity(Gravity.CENTER_HORIZONTAL | Gravity.CENTER_VERTICAL)
                    .setHotwordIndicatorGravity(Gravity.CENTER_HORIZONTAL | Gravity.CENTER_VERTICAL)
                    .build());

            Resources resources = GWatchFace.this.getResources();

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.background));

            mAmbientBackgroundPaint = new Paint();
            mAmbientBackgroundPaint.setColor(resources.getColor(R.color.ambient_background));

            mAmbientPaint = configureHandPaint(new Paint(),
                    R.color.ambient_hands, R.dimen.hand_stroke);
            mHourHandPaint = configureHandPaint(new Paint(),
                    R.color.hour_hand, R.dimen.hand_stroke);
            mMinuteHandPaint = configureHandPaint(new Paint(),
                    R.color.minute_hand, R.dimen.hand_stroke);
            mSecondHandPaint = configureHandPaint(new Paint(),
                    R.color.second_hand, R.dimen.hand_stroke);
            mDotPaint = configureHandPaint(new Paint(),
                    R.color.dot_color, R.dimen.dot_stroke);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                mBackgroundVector = resources.getDrawable(R.drawable.g_logo, null);
                mAmbientBackgroundVector = resources.getDrawable(R.drawable.g_logo_dark, null);
                mAmbientBurnInBackgroundVector = resources.getDrawable(R.drawable.g_logo_outline, null);
            } else {
                mBackgroundVector = resources.getDrawable(R.drawable.g_logo);
                mAmbientBackgroundVector = resources.getDrawable(R.drawable.g_logo_dark);
                mAmbientBurnInBackgroundVector = resources.getDrawable(R.drawable.g_logo_outline);
            }

            mCalendar = new GregorianCalendar();
            mCardBounds = new Rect();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
            mBurnInProtection = properties.getBoolean(PROPERTY_BURN_IN_PROTECTION, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mAmbientPaint.setAntiAlias(!mAmbient);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        @Override
        public void onPeekCardPositionUpdate(Rect rect) {
            super.onPeekCardPositionUpdate(rect);
            mCardBounds.set(rect);
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            mCalendar.setTimeInMillis(System.currentTimeMillis());

            Rect vectorBounds = new Rect(bounds);
            vectorBounds.inset((int) (vectorBounds.width() * 0.1f), (int) (vectorBounds.height() * 0.1f));

            // Draw the background
            if (mAmbient) {
                canvas.drawPaint(mAmbientBackgroundPaint);

                if (mLowBitAmbient || mBurnInProtection) {
                    mAmbientBurnInBackgroundVector.setBounds(vectorBounds);
                    mAmbientBurnInBackgroundVector.draw(canvas);
                } else {
                    mAmbientBackgroundVector.setBounds(vectorBounds);
                    mAmbientBackgroundVector.draw(canvas);
                }
            } else {
                mBackgroundVector.setBounds(vectorBounds);

                canvas.drawPaint(mBackgroundPaint);
                mBackgroundVector.draw(canvas);
            }

            // Find the center. Ignore the window insets so that, on round watches with a
            // "chin", the watch face is centered on the entire screen, not just the usable
            // portion.
            float centerX = bounds.width() / 2f;
            float centerY = bounds.height() / 2f;

            float secLength = centerX * 0.25f;
            float minLength = secLength * 2f;
            float hourLength = secLength * 3f;

            float dotOffset = centerX * 0.9f;

            float secRot = mCalendar.get(Calendar.SECOND) * (360f / 60f);
            float minRot = mCalendar.get(Calendar.MINUTE) * (360f / 60f);
            float hourRot = ((mCalendar.get(Calendar.HOUR) + (mCalendar.get(Calendar.MINUTE) / 60f))
                    * (360f / 12f));

            canvas.save();
            if (!mAmbient || !mBurnInProtection) {
                for (int i = 0; i < 12; i++) {
                    canvas.rotate((360f / 12f), centerX, centerY);
                    if (mAmbient) {
                        canvas.drawPoint(centerX, centerY - dotOffset, mAmbientPaint);
                    } else {
                        canvas.drawPoint(centerX, centerY - dotOffset, mDotPaint);
                    }
                }
            }

            canvas.rotate(hourRot, centerX, centerY);
            if (mAmbient) {
                canvas.drawLine(centerX, 0, centerX, hourLength, mAmbientPaint);
            } else {
                canvas.drawLine(centerX, 0, centerX, hourLength, mHourHandPaint);
            }

            canvas.rotate(minRot - hourRot, centerX, centerY);
            if (mAmbient) {
                canvas.drawLine(centerX, 0, centerX, minLength, mAmbientPaint);
            } else {
                canvas.drawLine(centerX, 0, centerX, minLength, mMinuteHandPaint);
            }

            if (!mAmbient) {
                canvas.rotate(secRot - minRot, centerX, centerY);
                canvas.drawLine(centerX, 0, centerX, secLength, mSecondHandPaint);
            }
            canvas.restore();

            // Draw a box behind the notification card in the end, so that it covers everything.
            if (mAmbient) {
                canvas.drawRect(mCardBounds, mAmbientBackgroundPaint);
            }
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mCalendar.setTimeZone(TimeZone.getDefault());
                mCalendar.setTimeInMillis(System.currentTimeMillis());
            } else {
                unregisterReceiver();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            GWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            GWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        private Paint configureHandPaint(Paint paint, int color_id, int width_id) {
            Resources resources = GWatchFace.this.getResources();

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                paint.setColor(resources.getColor(color_id, null));
            } else {
                paint.setColor(resources.getColor(color_id));
            }

            paint.setStrokeWidth(resources.getDimension(width_id));
            paint.setAntiAlias(true);
            paint.setStrokeCap(Paint.Cap.ROUND);
            return paint;
        }
    }
}
