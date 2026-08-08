package com.legitcoconut.blueremind;

import android.app.Application;

import com.google.android.material.color.DynamicColors;

public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        // Android 12+ : repaint the app from the system wallpaper palette.
        // Below 12 this is a no-op and the Material3 baseline palette is used.
        DynamicColors.applyToActivitiesIfAvailable(this);
    }
}
