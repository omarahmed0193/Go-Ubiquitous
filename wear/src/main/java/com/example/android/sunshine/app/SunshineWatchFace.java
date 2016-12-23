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

package com.example.android.sunshine.app;

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
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.content.ContextCompat;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.WindowInsets;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.NodeApi;
import com.google.android.gms.wearable.Wearable;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class SunshineWatchFace extends CanvasWatchFaceService {
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
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
        private final WeakReference<Engine> mWeakReference;

        public EngineHandler(Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements DataApi.DataListener,
            GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {

        static final String KEY_WEATHER_PATH = "/weather";
        static final String KEY_WEATHER_ID = "WEATHER_ID";
        static final String KEY_TEMP_MAX = "MAX";
        static final String KEY_TEMP_MIN = "MIN";
        private static final String TAG = "@@@@";

        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        GoogleApiClient mGoogleApiClient;
        Paint mBackgroundPaint;
        Paint mTimeTextPaint;
        Paint mMaxTempTextPaint;
        Paint mMinTempTextPaint;
        Paint mDateTextPaint;
        boolean mAmbient;
        Calendar mCalendar;
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            }
        };
        float mXOffset;
        float mYOffset;
        float mExtraYOffset;
        float mExtraXOffset;
        private String mTempMax = "0°";
        private String mTempMin = "0°";
        private int mWeatherId;
        private Bitmap mWeatherIcon;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .setHotwordIndicatorGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL)
                    .setStatusBarGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL)
                    .build());

            mGoogleApiClient = new GoogleApiClient.Builder(SunshineWatchFace.this)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .addApi(Wearable.API)
                    .build();

            Resources resources = SunshineWatchFace.this.getResources();
            mYOffset = resources.getDimension(R.dimen.digital_time_y_offset);
            mExtraYOffset = resources.getDimension(R.dimen.digital_extra_y_offset);
            mExtraXOffset = resources.getDimension(R.dimen.digital_extra_x_offset);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(ContextCompat.getColor(SunshineWatchFace.this, R.color.background));

            mTimeTextPaint = createTextPaint(Color.WHITE, 255);
            mMaxTempTextPaint = createTextPaint(Color.WHITE, 255);
            mMinTempTextPaint = createTextPaint(Color.WHITE, 150);
            mDateTextPaint = createTextPaint(Color.WHITE, 150);

            mCalendar = Calendar.getInstance();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor, int alpha) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            paint.setAlpha(alpha);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                mGoogleApiClient.connect();
                registerReceiver();
                // Update time zone in case it changed while we weren't visible.
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            } else {
                if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
                    Wearable.DataApi.removeListener(mGoogleApiClient, this);
                    mGoogleApiClient.disconnect();
                }
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
            SunshineWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            SunshineWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = SunshineWatchFace.this.getResources();
            boolean isRound = insets.isRound();
            mXOffset = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);
            float timeTextSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);
            float tempTextSize = resources.getDimension(isRound
                    ? R.dimen.digital_temp_size_round : R.dimen.digital_temp_size);
            float dateTextSize = resources.getDimension(isRound
                    ? R.dimen.digital_date_size_round : R.dimen.digital_date_size);

            mTimeTextPaint.setTextSize(timeTextSize);
            mTimeTextPaint.setTextAlign(Paint.Align.CENTER);
            mMaxTempTextPaint.setTextSize(tempTextSize);
            mMaxTempTextPaint.setTextAlign(Paint.Align.CENTER);
            mMinTempTextPaint.setTextSize(tempTextSize);
            mMinTempTextPaint.setTextAlign(Paint.Align.CENTER);
            mDateTextPaint.setTextSize(dateTextSize);
            mDateTextPaint.setTextAlign(Paint.Align.CENTER);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
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
                    mTimeTextPaint.setAntiAlias(!inAmbientMode);
                    mMaxTempTextPaint.setAntiAlias(!inAmbientMode);
                    mMinTempTextPaint.setAntiAlias(!inAmbientMode);
                    mDateTextPaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        /**
         * Captures tap event (and tap type) and toggles the background color if the user finishes
         * a tap.
         */
        @Override
        public void onTapCommand(int tapType, int x, int y, long eventTime) {
            switch (tapType) {
                case TAP_TYPE_TOUCH:
                    // The user has started touching the screen.
                    break;
                case TAP_TYPE_TOUCH_CANCEL:
                    // The user has started a different gesture or otherwise cancelled the tap.
                    break;
                case TAP_TYPE_TAP:
                    // The user has completed the tap gesture.
                    // TODO: Add code to handle the tap gesture.
                    Toast.makeText(getApplicationContext(), R.string.message, Toast.LENGTH_SHORT)
                            .show();
                    break;
            }
            invalidate();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }


            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);

            String time = String.format(Locale.ENGLISH, "%d:%02d", mCalendar.get(Calendar.HOUR),
                    mCalendar.get(Calendar.MINUTE));
            String date = new SimpleDateFormat("EEE, MMM dd yyyy", Locale.ENGLISH).format(mCalendar.getTime());

            float centerX = bounds.centerX();
            float centerY = bounds.centerY();

            Rect textBounds = new Rect();
            mDateTextPaint.getTextBounds(date, 0, date.length(), textBounds);
            canvas.drawText(date, centerX, centerY + (textBounds.height() / 2), mDateTextPaint);
            canvas.drawText(time, centerX, centerY - (textBounds.height() / 2) - mExtraYOffset, mTimeTextPaint);
            canvas.drawText(mTempMax, centerX, centerY + textBounds.height() + mExtraYOffset * 2, mMaxTempTextPaint);
            canvas.drawText(mTempMin, centerX + mMaxTempTextPaint.measureText(mTempMax), centerY + textBounds.height() + mExtraYOffset * 2, mMinTempTextPaint);
            if (!mAmbient) {
                mWeatherIcon = getIconResourceForWeatherCondition(mWeatherId);
                canvas.drawBitmap(mWeatherIcon, centerX - mWeatherIcon.getWidth() / 2 - mMaxTempTextPaint.measureText(mTempMax), centerY + textBounds.height() + mExtraYOffset * 2 - mWeatherIcon.getHeight(), new Paint());
            }

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

        @Override
        public void onConnected(@Nullable Bundle bundle) {
            Wearable.DataApi.addListener(mGoogleApiClient, this);
            Wearable.NodeApi.getLocalNode(mGoogleApiClient).setResultCallback(new ResultCallback<NodeApi.GetLocalNodeResult>() {
                @Override
                public void onResult(@NonNull NodeApi.GetLocalNodeResult getLocalNodeResult) {
                    String nodeId = getLocalNodeResult.getNode().getId();
                    Wearable.MessageApi.sendMessage(mGoogleApiClient, nodeId, KEY_WEATHER_PATH, null);
                }
            });
        }

        @Override
        public void onConnectionSuspended(int i) {
        }

        @Override
        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
        }

        @Override
        public void onDataChanged(DataEventBuffer dataEventBuffer) {
            for (DataEvent event : dataEventBuffer) {
                if (event.getType() == DataEvent.TYPE_CHANGED) {
                    DataItem item = event.getDataItem();
                    if (item.getUri().getPath().equals(KEY_WEATHER_PATH)) {
                        DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
                        mTempMax = dataMap.getString(KEY_TEMP_MAX);
                        mTempMin = dataMap.getString(KEY_TEMP_MIN);
                        mWeatherId = dataMap.getInt(KEY_WEATHER_ID);

                    }
                } else if (event.getType() == DataEvent.TYPE_DELETED) {
                    // DataItem deleted
                }
            }

        }
    }

    private Bitmap getIconResourceForWeatherCondition(int weatherId) {
        int icRes;
        if (weatherId >= 200 && weatherId <= 232) {
            icRes = R.drawable.ic_storm;
        } else if (weatherId >= 300 && weatherId <= 321) {
            icRes = R.drawable.ic_light_rain;
        } else if (weatherId >= 500 && weatherId <= 504) {
            icRes = R.drawable.ic_rain;
        } else if (weatherId == 511) {
            icRes = R.drawable.ic_snow;
        } else if (weatherId >= 520 && weatherId <= 531) {
            icRes = R.drawable.ic_rain;
        } else if (weatherId >= 600 && weatherId <= 622) {
            icRes = R.drawable.ic_snow;
        } else if (weatherId >= 701 && weatherId <= 761) {
            icRes = R.drawable.ic_fog;
        } else if (weatherId == 761 || weatherId == 781) {
            icRes = R.drawable.ic_storm;
        } else if (weatherId == 800) {
            icRes = R.drawable.ic_clear;
        } else if (weatherId == 801) {
            icRes = R.drawable.ic_light_clouds;
        } else if (weatherId >= 802 && weatherId <= 804) {
            icRes = R.drawable.ic_cloudy;
        } else {
            icRes = R.drawable.ic_clear;
        }
        return Bitmap.createScaledBitmap(BitmapFactory
                .decodeResource(getResources(), icRes), 56, 56, true);
    }
}
