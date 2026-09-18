package com.chargingassistant.app;

import android.app.ForegroundServiceStartNotAllowedException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import androidx.core.content.ContextCompat;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "ChargingAssistant";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String action = intent.getAction();
        Log.d(TAG, "BootReceiver received action: " + action);

        if (Intent.ACTION_BOOT_COMPLETED.equals(action) ||
            "android.intent.action.QUICKBOOT_POWERON".equals(action) ||
            Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {

            SettingsManager settingsManager = new SettingsManager(context);
            if (settingsManager.isServiceEnabled()) {
                Log.d(TAG, "Boot completed: Initializing ChargingForegroundService after device restart");
                Intent serviceIntent = new Intent(context, ChargingForegroundService.class);
                serviceIntent.setAction(ChargingForegroundService.ACTION_START);

                try {
                    ContextCompat.startForegroundService(context, serviceIntent);
                    Log.d(TAG, "Foreground service started successfully on boot.");
                } catch (Throwable t) {
                    Log.w(TAG, "Direct foreground service start failed on boot due to OS restriction: " + t.getMessage());
                    // Fallback for strict OEM Android 12+ devices: show interactive notification
                    try {
                        BatteryNotificationHelper notificationHelper = new BatteryNotificationHelper(context);
                        notificationHelper.showAlertNotification(
                                "চার্জিং সহকারী প্রস্তুত ⚡",
                                "ডিভাইস চালু হয়েছে। ব্যাকগ্রাউন্ড চার্জিং মনিটর সচল রাখতে ট্যাপ করুন।",
                                false,
                                false
                        );
                    } catch (Exception e) {
                        Log.e(TAG, "Error posting fallback boot notification", e);
                    }
                }
            } else {
                Log.d(TAG, "Service is disabled in settings. Skipping start on boot.");
            }
        }
    }
}
