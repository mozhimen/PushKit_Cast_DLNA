package com.android.cast.dlna.demo;

import android.app.Application;

import com.android.cast.dlna.dmc.DLNACastManager;

public class CastApplication extends Application {
    public static final boolean testMode = false;

    @Override
    public void onCreate() {
        super.onCreate();
        DLNACastManager.getInstance().enableLog();
    }
}
