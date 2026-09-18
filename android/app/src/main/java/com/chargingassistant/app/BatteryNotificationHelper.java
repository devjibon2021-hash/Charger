package com.chargingassistant.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;

public class BatteryNotificationHelper {
    private static final String TAG = "ChargingAssistant";

    public static final String CHANNEL_SERVICE = "charging_assistant_fg_service";
    public static final String CHANNEL_ALERTS = "charging_assistant_alerts";

    public static final int NOTIFICATION_SERVICE_ID = 1001;
    public static final int NOTIFICATION_ALERT_ID = 1002;

    private final Context context;
    private final NotificationManager notificationManager;

    public BatteryNotificationHelper(Context context) {
        this.context = context.getApplicationContext();
        this.notificationManager = (NotificationManager) this.context.getSystemService(Context.NOTIFICATION_SERVICE);
        createNotificationChannels();
    }

    private void createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && notificationManager != null) {
            // 1. Persistent Service Channel (Low importance, silent ongoing monitoring notification)
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_SERVICE,
                    "চার্জিং সহকারী স্ট্যাটাস (Background Service)",
                    NotificationManager.IMPORTANCE_LOW
            );
            serviceChannel.setDescription("চার্জিং চলাকালীন ব্যাকগ্রাউন্ড মনিটরিং ও রিমাইন্ডার সক্রিয় রাখার জন্য");
            serviceChannel.setShowBadge(false);
            notificationManager.createNotificationChannel(serviceChannel);

            // 2. Alert & Reminder Channel (High importance for target reached & repeating reminders)
            NotificationChannel alertChannel = new NotificationChannel(
                    CHANNEL_ALERTS,
                    "চার্জিং অ্যালার্ট ও ভয়েস রিমাইন্ডার (Alerts)",
                    NotificationManager.IMPORTANCE_HIGH
            );
            alertChannel.setDescription("টার্গেট ব্যাটারি চার্জ ও ১০/১৫ মিনিট রিমাইন্ডার সতর্কতা");
            alertChannel.enableVibration(true);
            alertChannel.setVibrationPattern(new long[]{0, 300, 200, 300});
            alertChannel.enableLights(true);
            alertChannel.setLightColor(Color.GREEN);

            Uri defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            AudioAttributes audioAttributes = new AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .build();
            alertChannel.setSound(defaultSoundUri, audioAttributes);

            notificationManager.createNotificationChannel(alertChannel);
            Log.d(TAG, "Notification channels created successfully");
        }
    }

    public Notification buildForegroundServiceNotification(int batteryLevel, boolean isCharging, boolean isReminderActive, int reminderMins) {
        Intent notificationIntent = new Intent(context, MainActivity.class);
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context,
                0,
                notificationIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0)
        );

        String title = "চার্জিং সহকারী সক্রিয় ⚡";
        String levelBn = BengaliTTSHelper.toBengaliDigits(batteryLevel);
        String statusText;
        if (isCharging) {
            statusText = "ব্যাটারি: " + levelBn + "% (চার্জ হচ্ছে)";
            if (isReminderActive) {
                statusText += " • রিমাইন্ডার: প্রতি " + BengaliTTSHelper.toBengaliDigits(reminderMins) + " মিনিট";
            }
        } else {
            statusText = "ব্যাটারি: " + levelBn + "% (চার্জার সংযুক্ত নয়)";
        }

        return new NotificationCompat.Builder(context, CHANNEL_SERVICE)
                .setContentTitle(title)
                .setContentText(statusText)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setContentIntent(pendingIntent)
                .build();
    }

    public void showAlertNotification(String title, String message, boolean playSound, boolean vibrate) {
        if (vibrate) {
            triggerVibration();
        }

        if (notificationManager == null) return;

        // Check if notifications are disabled by user on Android 13+ or in system settings
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !notificationManager.areNotificationsEnabled()) {
            Log.d(TAG, "Notifications are disabled in system settings. Voice alert and vibration continue.");
            return;
        }

        Intent intent = new Intent(context, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context,
                1,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0)
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ALERTS)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(message))
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setContentIntent(pendingIntent);

        if (playSound) {
            Uri soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            builder.setSound(soundUri);
        } else {
            builder.setSound(null);
        }

        try {
            notificationManager.notify(NOTIFICATION_ALERT_ID, builder.build());
            Log.d(TAG, "Alert notification posted: " + title + " - " + message);
        } catch (SecurityException se) {
            Log.w(TAG, "POST_NOTIFICATIONS permission not granted. Notification suppressed safely.", se);
        } catch (Exception e) {
            Log.e(TAG, "Failed to post alert notification", e);
        }
    }

    public void triggerVibration() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                VibratorManager vibratorManager = (VibratorManager) context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                if (vibratorManager != null) {
                    Vibrator vibrator = vibratorManager.getDefaultVibrator();
                    vibrator.vibrate(VibrationEffect.createWaveform(new long[]{0, 300, 150, 300}, -1));
                }
            } else {
                Vibrator vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
                if (vibrator != null && vibrator.hasVibrator()) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vibrator.vibrate(VibrationEffect.createOneShot(400, VibrationEffect.DEFAULT_AMPLITUDE));
                    } else {
                        vibrator.vibrate(400);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error triggering vibration", e);
        }
    }
}
