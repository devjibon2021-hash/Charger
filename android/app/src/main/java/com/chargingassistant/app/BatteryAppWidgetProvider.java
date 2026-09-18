package com.chargingassistant.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.BatteryManager;
import android.os.Build;
import android.util.Log;
import android.widget.RemoteViews;

import androidx.core.content.ContextCompat;

public class BatteryAppWidgetProvider extends AppWidgetProvider {
    private static final String TAG = "ChargingAssistant";
    public static final String ACTION_WIDGET_TEST_VOICE = "com.chargingassistant.app.action.WIDGET_TEST_VOICE";

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        super.onUpdate(context, appWidgetManager, appWidgetIds);
        Log.d(TAG, "BatteryAppWidgetProvider onUpdate called for " + appWidgetIds.length + " widgets");

        // Fetch current sticky battery state
        int batteryLevel = 50;
        boolean isCharging = false;
        int temperature = 0;
        String plugType = "none";

        try {
            Intent sticky = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (sticky != null) {
                int level = sticky.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = sticky.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                int status = sticky.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                int plugged = sticky.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
                temperature = sticky.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10;

                if (level >= 0 && scale > 0) {
                    batteryLevel = Math.round((level / (float) scale) * 100);
                }

                isCharging = (status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL ||
                        plugged > 0);

                if (plugged == BatteryManager.BATTERY_PLUGGED_AC) plugType = "AC";
                else if (plugged == BatteryManager.BATTERY_PLUGGED_USB) plugType = "USB";
                else if (plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS) plugType = "Wireless";
            }
        } catch (Exception e) {
            Log.e(TAG, "Error fetching battery state in widget onUpdate", e);
        }

        SettingsManager settingsManager = new SettingsManager(context);
        String reminderInfo = "টার্গেট: " + BengaliTTSHelper.toBengaliDigits(settingsManager.getTargetPercentage()) +
                "% • রিমাইন্ডার: " + BengaliTTSHelper.toBengaliDigits(settingsManager.getReminderIntervalMinutes()) + " মিনিট";

        updateAllWidgets(context, batteryLevel, isCharging, temperature, plugType, reminderInfo);
    }

    public static void updateAllWidgets(Context context, int batteryLevel, boolean isCharging,
                                        int temperature, String plugType, String reminderInfo) {
        try {
            AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(context);
            ComponentName thisWidget = new ComponentName(context, BatteryAppWidgetProvider.class);
            int[] appWidgetIds = appWidgetManager.getAppWidgetIds(thisWidget);

            if (appWidgetIds == null || appWidgetIds.length == 0) {
                return;
            }

            Log.d(TAG, "Updating " + appWidgetIds.length + " battery widgets. Level: " + batteryLevel + "%, Charging: " + isCharging);

            String levelBn = BengaliTTSHelper.toBengaliDigits(batteryLevel) + "%";
            String statusText;
            if (isCharging) {
                String plugBn = "AC".equalsIgnoreCase(plugType) ? " (AC প্লাগ)" :
                               "USB".equalsIgnoreCase(plugType) ? " (USB প্লাগ)" : "";
                statusText = "চার্জ হচ্ছে ⚡" + plugBn;
            } else {
                statusText = "চার্জার সংযোগ নেই";
            }

            int pFlags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                pFlags |= PendingIntent.FLAG_IMMUTABLE;
            }

            // Click action to open MainActivity
            Intent openAppIntent = new Intent(context, MainActivity.class);
            openAppIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent openAppPending = PendingIntent.getActivity(context, 0, openAppIntent, pFlags);

            // Click action for quick voice test button
            Intent testVoiceIntent = new Intent(context, BatteryAppWidgetProvider.class);
            testVoiceIntent.setAction(ACTION_WIDGET_TEST_VOICE);
            PendingIntent testVoicePending = PendingIntent.getBroadcast(context, 1, testVoiceIntent, pFlags);

            for (int appWidgetId : appWidgetIds) {
                RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_battery_assistant);

                views.setTextViewText(R.id.widget_battery_percent, levelBn);
                views.setTextViewText(R.id.widget_status_text, statusText);
                views.setTextViewText(R.id.widget_sub_status, reminderInfo != null ? reminderInfo : "সার্বক্ষণিক মনিটরিং সক্রিয়");
                views.setProgressBar(R.id.widget_battery_progress, 100, Math.max(0, Math.min(100, batteryLevel)), false);

                if (isCharging) {
                    views.setTextViewText(R.id.widget_badge, "চার্জ হচ্ছে ⚡");
                    views.setTextColor(R.id.widget_badge, Color.parseColor("#34D399"));
                    views.setTextColor(R.id.widget_battery_percent, Color.parseColor("#10B981"));
                } else {
                    views.setTextViewText(R.id.widget_badge, "সক্রিয়");
                    views.setTextColor(R.id.widget_badge, Color.parseColor("#9CA3AF"));
                    views.setTextColor(R.id.widget_battery_percent, Color.parseColor("#FFFFFF"));
                }

                views.setTextViewText(R.id.widget_info_text, "স্ক্রিন অফেও ভয়েস ও অ্যালার্ট সক্রিয়");

                // Set Click Listeners
                views.setOnClickPendingIntent(R.id.widget_root, openAppPending);
                views.setOnClickPendingIntent(R.id.widget_btn_test_voice, testVoicePending);

                appWidgetManager.updateAppWidget(appWidgetId, views);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error updating battery widgets", e);
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (intent == null || intent.getAction() == null) return;

        String action = intent.getAction();
        Log.d(TAG, "BatteryAppWidgetProvider onReceive: " + action);

        if (ACTION_WIDGET_TEST_VOICE.equals(action)) {
            // Trigger voice announcement of current battery level
            try {
                Intent serviceIntent = new Intent(context, ChargingForegroundService.class);
                serviceIntent.setAction(ChargingForegroundService.ACTION_TEST_VOICE);
                serviceIntent.putExtra(ChargingForegroundService.EXTRA_CUSTOM_TEXT, "উইজেট সক্রিয় আছে। চার্জিং সহকারী ব্যাকগ্রাউন্ডে কাজ করছে।");
                ContextCompat.startForegroundService(context, serviceIntent);
            } catch (Exception e) {
                Log.e(TAG, "Error starting voice test from widget", e);
                BengaliTTSHelper tts = new BengaliTTSHelper(context);
                tts.speak("চার্জিং সহকারী উইজেট সক্রিয় আছে।", null);
            }
        }
    }
}
