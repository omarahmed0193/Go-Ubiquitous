package com.example.android.sunshine.app;

import android.content.Intent;

import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;

public class WearableService extends WearableListenerService {
    private static final String WEATHER_PATH = "/weather";

    public WearableService() {
    }

    @Override
    public void onMessageReceived(MessageEvent messageEvent) {
        super.onMessageReceived(messageEvent);
        if (messageEvent.getPath().equals(WEATHER_PATH)) {
            startService(new Intent(this, UpdateWatchService.class));
        }
    }
}
