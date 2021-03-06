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

package com.miproducts.miwatch;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;
import android.view.WindowManager;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;
import com.miproducts.miwatch.hud.HudView;
import com.miproducts.miwatch.mods.DateMod;
import com.miproducts.miwatch.utilities.Consts;
import com.miproducts.miwatch.utilities.ConverterUtil;
import com.miproducts.miwatch.utilities.ModPositionFunctions;
import com.miproducts.miwatch.utilities.SettingsManager;

import java.util.Calendar;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

//TODO begin to work back on customizing application. start with setting boolean for when we are in the new activity and when we are in the old.
// it will continue to run in the background and get the data, but it wont post it to the datalayer
// so if we just send it through a message maybe then it will actually send it and our watchface then can receive it.
//http://android-wear-docs.readthedocs.org/en/latest/sync.html

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class MiDigitalWatchFace extends CanvasWatchFaceService {
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);
    // whether we are on a round device or not for positions. (matters for the mods it seems)
    private boolean isRound = false;
    Engine engine;
    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }




    public class Engine extends CanvasWatchFaceService.Engine{
        static final int MSG_UPDATE_TIME = 0;

        @Override
        public void onPeekCardPositionUpdate(Rect rect) {
            super.onPeekCardPositionUpdate(rect);
            log("peekcardMoved");
            if (rect.top == 0) {
                log("peaking = false");
                isPeakCardPeaking = false;
                addHudView();
                // invalidate();
            } else {
                log("peaking = true");
                removeHudView();
                isPeakCardPeaking = true;
            }
        }

        /**
         * Handler to update the time periodically in interactive mode.
         */
        final Handler mUpdateTimeHandler = new Handler() {
            @Override
            public void handleMessage(Message message) {
                switch (message.what) {
                    case MSG_UPDATE_TIME:
                        invalidate();
                        if (shouldTimerBeRunning()) {
                            long timeMs = System.currentTimeMillis();
                            long delayMs = INTERACTIVE_UPDATE_RATE_MS
                                    - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                            mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
                        }
                        break;
                }
            }
        };

        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mDayTimeDateCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            }
        };

        // It is the MiDigitalWatchFaceConfigListenerService who sends out a broadcast to this, after it
        // stores the degrees in the @SettingsManager Preference.
        final BroadcastReceiver brDegreeRefresh = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (isHudDisplaying && !isPeakCardPeaking) {
                    log("refreshing Degrees from MiDigitalWatchfaceBroadcast");
                    // this will eventually tell Degree to grab its degrees from the Preference (@SettingsManager)
                    mHudView.resetTemp();
                }
            }
        };


        private Context mContext;
        private Resources resources;
        boolean mRegisteredTimeZoneReceiver = false;

        // Paint for the watch face
        Paint mBackgroundPaint;
        Paint mDigitalPaint;
        Paint mDigitalDayOfWeekPaint;
        Paint mDigitalDayOfMonthPaint;

        boolean mAmbient;

        // the calendar object to get date, time, day
        Calendar mDayTimeDateCalendar;

        float yPositionForTime;
        float xPositionForTime;

        // get the day of week and day of month
        String[] dayOfWeekAndDayOfMonth;
        String dayOfWeek;
        String dayOfMonth;


        WindowManager mWindowManager;
        int displayWidth;
        int displayHeight;
        HudView mHudView;

        boolean isHudDisplaying = false;
        boolean isPeakCardPeaking = false;
        // Date
        DateMod mDateMod;

        SettingsManager settingsManager;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        SettingsManager sm = new SettingsManager(getApplicationContext());

        GoogleApiClient mGoogleApiClient;

        public int getWidth() {
            return displayWidth;
        }

        public int getHeight() {
            return displayHeight;
        }

        public HudView getHud(){
            return mHudView;
        }
        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(MiDigitalWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .build());


            mGoogleApiClient = new GoogleApiClient.Builder(getApplicationContext())
                    .addConnectionCallbacks(new GoogleApiClient.ConnectionCallbacks() {
                        @Override
                        public void onConnected(Bundle bundle) {

                        }

                        @Override
                        public void onConnectionSuspended(int i) {

                        }
                    })
                    .addOnConnectionFailedListener(new GoogleApiClient.OnConnectionFailedListener() {
                        @Override
                        public void onConnectionFailed(ConnectionResult connectionResult) {

                        }
                    })
                    .addApi(Wearable.API)
                    .build();

            resources = MiDigitalWatchFace.this.getResources();
            mContext = getApplicationContext();
            mWindowManager = (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);
            displayWidth = mWindowManager.getDefaultDisplay().getWidth();
            displayHeight = mWindowManager.getDefaultDisplay().getHeight();

            settingsManager = new SettingsManager(mContext);


            setPositionForWatchfaceObjects();
            setPaintForWatchFaceObjects(resources);
            setCalendar();


        }

        public float getTimeXPos(){
            return xPositionForTime;
        }

        private void setPositionForWatchfaceObjects() {
            // Time
            xPositionForTime = ModPositionFunctions.getLeftTimerPosition(getWallpaperDesiredMinimumWidth());

            SettingsManager sm = new SettingsManager(mContext);
            sm.writeToPreferences(SettingsManager.DIGITAL_TIME_X, (int)xPositionForTime);

            yPositionForTime = ModPositionFunctions.getTopTimerPosition(getWallpaperDesiredMinimumHeight());
        }

        private void setCalendar() {
            mDayTimeDateCalendar = Calendar.getInstance();
            mDayTimeDateCalendar.setTimeZone(TimeZone.getDefault());
            dayOfWeekAndDayOfMonth = mDayTimeDateCalendar.getTime().toString().split(" ");
            dayOfWeek = dayOfWeekAndDayOfMonth[Consts.CALENDAR_DAY_OF_WEEK];
            dayOfMonth = dayOfWeekAndDayOfMonth[Consts.CALENDAR_DAY_OF_MONTH];

        }


        private void setPaintForWatchFaceObjects(Resources resources) {
            // Background
            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.blackish));
            // Digital Time
            mDigitalPaint = new Paint();
            mDigitalPaint = createTextPaint(resources.getColor(R.color.digital_time_blue));
            // The Date
            mDigitalDayOfMonthPaint = new Paint();
            mDigitalDayOfMonthPaint = createTextPaint(resources.getColor(R.color.white));
            // The Day
            mDigitalDayOfWeekPaint = new Paint();
            mDigitalDayOfWeekPaint = createTextPaint(resources.getColor(R.color.digital_time_blue));

        }

        /**
         * Tell us if this is a round device or a square.
         * @param round
         */
        private void createHud(boolean round) {
            mHudView = new HudView(getApplicationContext(), round, this);
            mHudView.setParams();
            addHudView();
        }

        private void addHudView() {

            if (isHudDisplaying || isPeakCardPeaking) {
                log("Hud View not added");
                return;
            }
            log("Hud View added");
            isHudDisplaying = true;
            mWindowManager.addView(mHudView, mHudView.getParams());
            mHudView.justAdded();
            invalidate();

        }


        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
                mGoogleApiClient.disconnect();
            }
            super.onDestroy();
        }


        private Paint createTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                if (mGoogleApiClient != null && !mGoogleApiClient.isConnected()) {
                    mGoogleApiClient.connect();
                }
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                setCalendar();
                // reset DateMod so its fresh.
                addHudView();
                if (mHudView.isEventModActive()) {
                    mHudView.initEventSyncTask();
                }
                //retrieveSettingsManagerColor();

                /**
                 *  determine if we need to remove hud (if we are viewing {@Link MiDigitalWatchFaceConfiguration} Screen)
                 */
                retrieveSettingsManagerHud();
            } else {
                if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
                    //mGoogleApiClient.disconnect();
                }
                unregisterReceiver();
                removeHudView();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        // only time this will be false is if the MiDigitalWatchFaceConfiguration has been called, to reverse we will need to
        // provoke a onVisibilityChange
        public void retrieveSettingsManagerHud() {
            if(settingsManager.getHudRemove()){
                removeHudView();
            }
        }

        private void retrieveSettingsManagerColor() {
            log("color going to be " + settingsManager.getMainColor());
        }

        public void removeHudView() {
            if (!isHudDisplaying) {
                log("Hud view not removed");
                return;
            }
            log("Hud View removed");
            mHudView.cancelRefreshDegree();
            mWindowManager.removeView(mHudView);
            isHudDisplaying = false;


        }

        private void registerReceiver() {
            IntentFilter filter2 = new IntentFilter(Consts.BROADCAST_DEGREE);
            MiDigitalWatchFace.this.registerReceiver(brDegreeRefresh, filter2);

            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            MiDigitalWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);

        }

        private void unregisterReceiver() {
            MiDigitalWatchFace.this.unregisterReceiver(brDegreeRefresh);

            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            MiDigitalWatchFace.this.unregisterReceiver(mTimeZoneReceiver);


        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);
            // Load resources that have alternate values for round watches.
            Resources resources = MiDigitalWatchFace.this.getResources();



            isRound = insets.isRound();

            float mDigitalTimeSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);
            float mDigitalDayOfWeekSize = resources.getDimension(isRound ?
                    R.dimen.digital_dayOfMonth_size_round : R.dimen.digital_dayOfMonth_size);


            mDigitalPaint.setTextSize(mDigitalTimeSize);
            mDigitalDayOfMonthPaint.setTextSize(mDigitalDayOfWeekSize);
            mDigitalDayOfMonthPaint.setTextAlign(Paint.Align.CENTER);
            mDigitalDayOfWeekPaint.setTextSize(mDigitalDayOfWeekSize);
            mDigitalDayOfWeekPaint.setTextAlign(Paint.Align.CENTER);
            createHud(insets.isRound());

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
                    mDigitalPaint.setAntiAlias(!inAmbientMode);
                    mDigitalDayOfMonthPaint.setAntiAlias(!inAmbientMode);
                    mDigitalDayOfWeekPaint.setAntiAlias(!inAmbientMode);

                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {

            mDayTimeDateCalendar.setTimeInMillis(System.currentTimeMillis());
            // Draw the background.
            canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);

            // Draw H:MM in ambient mode or H:MM:SS in interactive mode.
            int hour = mDayTimeDateCalendar.get(Calendar.HOUR);
            int minute = mDayTimeDateCalendar.get(Calendar.MINUTE);


            canvas.drawText(ConverterUtil.normalizeHour(hour) + ":" + ConverterUtil.normalizeMinute(minute), xPositionForTime, yPositionForTime, mDigitalPaint);

            if(mDateMod == null){
                // make new object
                mDateMod = new DateMod(mContext, MiDigitalWatchFace.this, dayOfMonth, dayOfWeek);
            }else {
                // just pump new values here, incase they changed.
                mDateMod.updateDate(dayOfMonth, dayOfWeek);
                mDateMod.draw(canvas);
            }


            if (isHudDisplaying && !isPeakCardPeaking)
                mHudView.draw(canvas);

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

        public void log(String s) {
            Log.d("MiDgitalWatchFace", s);
        }


        private void updateCount(int y) {
            log("grabbed this update: " + y);
        }

        private static final String COUNT_KEY = "com.miproducts.miwatch";

        private final static String TAG = "MiDigitalWatchFace";

        /**
         * Called from HudView, who responded by a call from DegreeMod.
         * ATM we have this send out this data to a node, it will be picked up by the phone,
         * the phone will make the call to refresh the stuff, atm I think We will have the phone be
         * required to have the app up and visible. But we can change that later perhaps.
         * @param dataMap - its just a true value bundled in, we will change it to false when job is done.
         */
        //TODO read desc and maybe we change that phone requirement.
        public void refreshDegrees(DataMap dataMap) {
            log("sending to Thread now " );
            //Requires a new thread to avoid blocking the UI
            new SendToDataLayerThread(Consts.WEARABLE_TO_PHONE_PATH, dataMap).start();
        }




        class SendToDataLayerThread extends Thread {
            String path;
            DataMap dataMap;
            String testmsg = "degree";

            // Constructor for sending data objects to the data layer
            SendToDataLayerThread(String p, DataMap data) {
                path = p;
                dataMap = data;
            }

            public void run() {
                    // Construct a DataRequest and send over the data layer
                    PutDataMapRequest putDMR = PutDataMapRequest.create(path);
                    putDMR.getDataMap().putAll(dataMap);
                    PutDataRequest request = putDMR.asPutDataRequest();
                    Wearable.DataApi.putDataItem(mGoogleApiClient,request).await();



            }
        }


    }


}
