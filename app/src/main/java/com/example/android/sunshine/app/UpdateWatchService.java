package com.example.android.sunshine.app;

import android.app.IntentService;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;

import com.example.android.sunshine.app.data.WeatherContract;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.Wearable;


public class UpdateWatchService extends IntentService implements
        GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {

    private static final String KEY_WEATHER_PATH = "/weather";
    private static final String KEY_WEATHER_ID = "WEATHER_ID";
    private static final String KEY_TEMP_MAX = "MAX";
    private static final String KEY_TEMP_MIN = "MIN";
    private GoogleApiClient mGoogleApiClient;

    public UpdateWatchService() {
        super("UpdateWatchService");
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        mGoogleApiClient = new GoogleApiClient.Builder(UpdateWatchService.this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(Wearable.API)
                .build();

        mGoogleApiClient.connect();
    }

    @Override
    public void onConnected(@Nullable Bundle bundle) {
        Uri weatherUri = WeatherContract.WeatherEntry
                .buildWeatherLocationWithDate(Utility.getPreferredLocation(this), System.currentTimeMillis());
        Cursor weatherCursor = getContentResolver().query(weatherUri,
                new String[]{WeatherContract.WeatherEntry.COLUMN_WEATHER_ID,
                        WeatherContract.WeatherEntry.COLUMN_MAX_TEMP,
                        WeatherContract.WeatherEntry.COLUMN_MIN_TEMP
                }, null, null, null);
        if (weatherCursor.moveToFirst()) {
            int weatherId = weatherCursor.getInt(weatherCursor.getColumnIndex(
                    WeatherContract.WeatherEntry.COLUMN_WEATHER_ID));
            String tempMax = Utility.formatTemperature(this, weatherCursor.getDouble(
                    weatherCursor.getColumnIndex(WeatherContract.WeatherEntry.COLUMN_MAX_TEMP)));
            String tempMin = Utility.formatTemperature(this, weatherCursor.getDouble(
                    weatherCursor.getColumnIndex(WeatherContract.WeatherEntry.COLUMN_MIN_TEMP)));

            final PutDataMapRequest weatherMapRequest = PutDataMapRequest.create(KEY_WEATHER_PATH);
            weatherMapRequest.getDataMap().putInt(KEY_WEATHER_ID, weatherId);
            weatherMapRequest.getDataMap().putString(KEY_TEMP_MAX, tempMax);
            weatherMapRequest.getDataMap().putString(KEY_TEMP_MIN, tempMin);
            weatherMapRequest.getDataMap().putLong("Time", System.currentTimeMillis());
            Wearable.DataApi.putDataItem(mGoogleApiClient, weatherMapRequest.asPutDataRequest())
                    .setResultCallback(new ResultCallback<DataApi.DataItemResult>() {
                        @Override
                        public void onResult(@NonNull DataApi.DataItemResult dataItemResult) {
                            Log.d("@@@@", "onResult: " + dataItemResult.toString());
                        }
                    });
        }
        weatherCursor.close();

    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
            mGoogleApiClient.disconnect();
        }
    }

    @Override
    public void onConnectionSuspended(int i) {

    }

    @Override
    public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {

    }
}
