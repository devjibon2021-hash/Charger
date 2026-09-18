package com.chargingassistant.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.webkit.JavascriptInterface;

import androidx.core.content.ContextCompat;

public class ChargingAssistantBridge {
    private static final String TAG = "ChargingAssistant";

    private final Context context;
    private final SettingsManager settingsManager;
    private BengaliTTSHelper ttsHelper;

    public ChargingAssistantBridge(Context context) {
        this.context = context.getApplicationContext();
        this.settingsManager = new SettingsManager(this.context);
        this.ttsHelper = new BengaliTTSHelper(this.context);
    }

    @JavascriptInterface
    public boolean isNativeApp() {
        return true;
    }

    @JavascriptInterface
    public boolean isServiceRunning() {
        boolean running = ChargingForegroundService.isServiceRunning();
        Log.d(TAG, "Bridge: isServiceRunning queried by React: " + running);
        return running;
    }

    @JavascriptInterface
    public String getVoiceName() {
        return "Android Native Bengali TTS Engine";
    }

    @JavascriptInterface
    public boolean syncSettings(String jsonSettings) {
        Log.d(TAG, "Bridge: syncSettings called from React UI");
        boolean ok = settingsManager.syncFromJson(jsonSettings);
        if (ok) {
            Intent intent = new Intent(context, ChargingForegroundService.class);
            intent.setAction(ChargingForegroundService.ACTION_SYNC_SETTINGS);
            try {
                ContextCompat.startForegroundService(context, intent);
            } catch (Exception e) {
                Log.e(TAG, "Failed to send sync action to service", e);
            }
        }
        return ok;
    }

    @JavascriptInterface
    public String getSettings() {
        return settingsManager.toJson();
    }

    @JavascriptInterface
    public void speak(String text) {
        speakTTS(text);
    }

    @JavascriptInterface
    public void speakTTS(String text) {
        Log.d(TAG, "Bridge: speakTTS called with: [" + text + "]");
        if (ttsHelper == null) {
            ttsHelper = new BengaliTTSHelper(context);
        }
        ttsHelper.speak(text, null);
    }

    @JavascriptInterface
    public void stop() {
        stopTTS();
    }

    @JavascriptInterface
    public void stopTTS() {
        Log.d(TAG, "Bridge: stopTTS called");
        if (ttsHelper != null) {
            ttsHelper.stop();
        }
    }

    @JavascriptInterface
    public void testVoice(String customText) {
        Log.d(TAG, "Bridge: testVoice called with custom text: [" + customText + "]");
        Intent intent = new Intent(context, ChargingForegroundService.class);
        intent.setAction(ChargingForegroundService.ACTION_TEST_VOICE);
        if (customText != null && !customText.trim().isEmpty()) {
            intent.putExtra(ChargingForegroundService.EXTRA_CUSTOM_TEXT, customText.trim());
        }
        try {
            ContextCompat.startForegroundService(context, intent);
        } catch (Exception e) {
            Log.e(TAG, "Error starting test voice intent", e);
            if (ttsHelper != null) {
                ttsHelper.speak(customText, null);
            }
        }
    }

    @JavascriptInterface
    public void testReminder(int intervalMinutes, String customText) {
        Log.d(TAG, "Bridge: testReminder called. Interval: " + intervalMinutes + " mins");
        if (customText != null && !customText.trim().isEmpty()) {
            settingsManager.setCustomVoiceText(customText.trim());
        }
        if (intervalMinutes > 0) {
            settingsManager.setReminderIntervalMinutes(intervalMinutes);
        }

        Intent intent = new Intent(context, ChargingForegroundService.class);
        intent.setAction(ChargingForegroundService.ACTION_TEST_REMINDER);
        try {
            ContextCompat.startForegroundService(context, intent);
        } catch (Exception e) {
            Log.e(TAG, "Error starting test reminder intent", e);
        }
    }

    @JavascriptInterface
    public void startForegroundService() {
        Log.d(TAG, "Bridge: startForegroundService requested");
        Intent intent = new Intent(context, ChargingForegroundService.class);
        intent.setAction(ChargingForegroundService.ACTION_START);
        try {
            ContextCompat.startForegroundService(context, intent);
        } catch (Exception e) {
            Log.e(TAG, "Error starting service", e);
        }
    }

    @JavascriptInterface
    public void stopForegroundService() {
        Log.d(TAG, "Bridge: stopForegroundService requested");
        Intent intent = new Intent(context, ChargingForegroundService.class);
        intent.setAction(ChargingForegroundService.ACTION_STOP);
        try {
            context.startService(intent);
        } catch (Exception e) {
            Log.e(TAG, "Error stopping service", e);
        }
    }

    @JavascriptInterface
    public boolean isBatteryOptimizationIgnored() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                return pm.isIgnoringBatteryOptimizations(context.getPackageName());
            }
        }
        return true;
    }

    @JavascriptInterface
    public void requestBatteryOptimization() {
        openBatteryOptimizationSettings();
    }

    @JavascriptInterface
    public void openBatteryOptimizationSettings() {
        Log.d(TAG, "Bridge: openBatteryOptimizationSettings called");
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Intent intent = new Intent();
                String packageName = context.getPackageName();
                PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);

                if (pm != null && !pm.isIgnoringBatteryOptimizations(packageName)) {
                    intent.setAction(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    intent.setData(Uri.parse("package:" + packageName));
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(intent);
                } else {
                    // Open application details / battery settings page
                    intent.setAction(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    intent.setData(Uri.parse("package:" + packageName));
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(intent);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error opening battery optimization settings", e);
            try {
                Intent intent = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
            } catch (Exception ignored) {
            }
        }
    }

    @JavascriptInterface
    public boolean isPinWidgetSupported() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AppWidgetManager appWidgetManager = context.getSystemService(AppWidgetManager.class);
            if (appWidgetManager != null) {
                return appWidgetManager.isRequestPinAppWidgetSupported();
            }
        }
        return false;
    }

    @JavascriptInterface
    public boolean requestPinWidget() {
        Log.d(TAG, "Bridge: requestPinWidget called");
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                AppWidgetManager appWidgetManager = context.getSystemService(AppWidgetManager.class);
                if (appWidgetManager != null && appWidgetManager.isRequestPinAppWidgetSupported()) {
                    ComponentName myProvider = new ComponentName(context, BatteryAppWidgetProvider.class);
                    Intent pinnedWidgetCallbackIntent = new Intent(context, BatteryAppWidgetProvider.class);
                    PendingIntent successCallback = PendingIntent.getBroadcast(
                            context,
                            0,
                            pinnedWidgetCallbackIntent,
                            PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0)
                    );

                    boolean success = appWidgetManager.requestPinAppWidget(myProvider, null, successCallback);
                    Log.d(TAG, "requestPinAppWidget initiated: " + success);
                    return success;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error requesting pin app widget", e);
        }
        return false;
    }

    @JavascriptInterface
    public void logMessage(String tag, String message) {
        Log.d(tag != null ? tag : TAG, message != null ? message : "");
    }
}
