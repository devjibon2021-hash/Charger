package com.chargingassistant.app;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import androidx.core.content.ContextCompat;

public class ChargingForegroundService extends Service {
    private static final String TAG = "ChargingAssistant";

    public static final String ACTION_START = "com.chargingassistant.app.action.START";
    public static final String ACTION_STOP = "com.chargingassistant.app.action.STOP";
    public static final String ACTION_SYNC_SETTINGS = "com.chargingassistant.app.action.SYNC_SETTINGS";
    public static final String ACTION_TEST_VOICE = "com.chargingassistant.app.action.TEST_VOICE";
    public static final String ACTION_TEST_REMINDER = "com.chargingassistant.app.action.TEST_REMINDER";
    public static final String ACTION_REMINDER_ALARM = "com.chargingassistant.app.action.REMINDER_ALARM";

    public static final String EXTRA_CUSTOM_TEXT = "extra_custom_text";

    private static volatile boolean isRunning = false;
    private static BatteryStateListener batteryStateListener;

    public interface BatteryStateListener {
        void onBatteryUpdate(int level, boolean isCharging, int temperature, int voltage, String plugType);
        void onServiceStatusChanged(boolean isRunning);
    }

    public static void setBatteryStateListener(BatteryStateListener listener) {
        batteryStateListener = listener;
        if (batteryStateListener != null) {
            batteryStateListener.onServiceStatusChanged(isRunning);
        }
    }

    public static boolean isServiceRunning() {
        return isRunning;
    }

    private SettingsManager settingsManager;
    private BengaliTTSHelper ttsHelper;
    private BatteryNotificationHelper notificationHelper;
    private AlarmManager alarmManager;
    private PowerManager.WakeLock screenOffWakeLock;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private int currentBatteryLevel = -1;
    private boolean isCurrentlyCharging = false;
    private int currentTemperature = 0;
    private int currentVoltage = 0;
    private String currentPlugType = "none";
    private boolean hasAlertedTarget = false;
    private boolean hasAlertedTemp = false;
    private boolean userRequestedStop = false;

    private long lastPowerConnectedTimestamp = 0;
    private long lastPowerDisconnectedTimestamp = 0;

    private PendingIntent reminderPendingIntent;
    private final Runnable reminderRunnable = new Runnable() {
        @Override
        public void run() {
            onReminderFired();
        }
    };

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) return;
            String action = intent.getAction();

            if (Intent.ACTION_BATTERY_CHANGED.equals(action)) {
                handleBatteryChanged(intent);
            } else if (Intent.ACTION_POWER_CONNECTED.equals(action)) {
                handlePowerConnected();
            } else if (Intent.ACTION_POWER_DISCONNECTED.equals(action)) {
                handlePowerDisconnected();
            } else if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                Log.d(TAG, "Screen turned off. Ensuring CPU stay-alive while charging for continuous voice alerts.");
                if (isCurrentlyCharging && settingsManager.isServiceEnabled()) {
                    acquireScreenOffWakeLock();
                }
            } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                Log.d(TAG, "Screen turned on.");
                releaseScreenOffWakeLock();
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        isRunning = true;
        userRequestedStop = false;
        Log.d(TAG, "ChargingForegroundService onCreate: Starting Service");

        settingsManager = new SettingsManager(this);
        ttsHelper = new BengaliTTSHelper(this);
        notificationHelper = new BatteryNotificationHelper(this);
        alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            screenOffWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ChargingAssistant:ScreenOffWakeLock");
            screenOffWakeLock.setReferenceCounted(false);
        }

        // 1. Read sticky battery intent immediately to populate state before building notification
        Intent stickyIntent = null;
        try {
            stickyIntent = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        } catch (Exception e) {
            Log.e(TAG, "Error reading sticky battery intent", e);
        }

        if (stickyIntent != null) {
            int level = stickyIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = stickyIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            int status = stickyIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            int plugged = stickyIntent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
            currentTemperature = stickyIntent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10;
            currentVoltage = stickyIntent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);

            if (level >= 0 && scale > 0) {
                currentBatteryLevel = Math.round((level / (float) scale) * 100);
            } else {
                currentBatteryLevel = 50;
            }

            isCurrentlyCharging = (status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL ||
                    plugged > 0);

            if (plugged == BatteryManager.BATTERY_PLUGGED_AC) currentPlugType = "AC";
            else if (plugged == BatteryManager.BATTERY_PLUGGED_USB) currentPlugType = "USB";
            else if (plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS) currentPlugType = "Wireless";
            else currentPlugType = "none";

            Log.d(TAG, "Sticky battery status read on service create: level=" + currentBatteryLevel +
                    "%, charging=" + isCurrentlyCharging + ", plug=" + currentPlugType);
        } else {
            currentBatteryLevel = 50;
            isCurrentlyCharging = false;
        }

        // 2. Start Foreground immediately with initial status notification
        int reminderMins = settingsManager.getReminderIntervalMinutes();
        boolean isRemActive = isCurrentlyCharging && settingsManager.isReminderEnabled();
        Notification notif = notificationHelper.buildForegroundServiceNotification(
                currentBatteryLevel, isCurrentlyCharging, isRemActive, reminderMins
        );

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                    BatteryNotificationHelper.NOTIFICATION_SERVICE_ID,
                    notif,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            );
        } else {
            startForeground(
                    BatteryNotificationHelper.NOTIFICATION_SERVICE_ID,
                    notif
            );
        }

        // 3. Register battery broadcast receiver with RECEIVER_NOT_EXPORTED on Android 13+
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(Intent.ACTION_POWER_CONNECTED);
        filter.addAction(Intent.ACTION_POWER_DISCONNECTED);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(batteryReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(batteryReceiver, filter);
        }

        // 4. If already charging on launch, ensure reminder cycle is initialized without duplicate speech
        if (isCurrentlyCharging && settingsManager.isServiceEnabled() && settingsManager.isReminderEnabled()) {
            scheduleRepeatingReminder();
            acquireScreenOffWakeLock();
        }

        // 5. Sync Home Screen AppWidget
        syncWidgetState();

        if (batteryStateListener != null) {
            batteryStateListener.onServiceStatusChanged(true);
            batteryStateListener.onBatteryUpdate(currentBatteryLevel, isCurrentlyCharging, currentTemperature, currentVoltage, currentPlugType);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            String action = intent.getAction();
            Log.d(TAG, "ChargingForegroundService onStartCommand action: " + action);

            if (ACTION_STOP.equals(action)) {
                userRequestedStop = true;
                stopSelf();
                return START_NOT_STICKY;
            } else if (ACTION_SYNC_SETTINGS.equals(action)) {
                Log.d(TAG, "Settings synced in running service");
                updateForegroundNotification();
                if (isCurrentlyCharging && settingsManager.isReminderEnabled()) {
                    scheduleRepeatingReminder();
                } else if (!isCurrentlyCharging || !settingsManager.isReminderEnabled()) {
                    cancelRepeatingReminder();
                }
            } else if (ACTION_TEST_VOICE.equals(action)) {
                String testText = intent.getStringExtra(EXTRA_CUSTOM_TEXT);
                handleTestVoice(testText);
            } else if (ACTION_TEST_REMINDER.equals(action)) {
                handleTestReminder();
            } else if (ACTION_REMINDER_ALARM.equals(action)) {
                onReminderFired();
            }
        } else {
            Log.d(TAG, "ChargingForegroundService restarted by Android system (START_STICKY)");
            updateForegroundNotification();
            if (isCurrentlyCharging && settingsManager.isReminderEnabled()) {
                scheduleRepeatingReminder();
            }
        }

        return START_STICKY;
    }

    private void handleBatteryChanged(Intent intent) {
        int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        int plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
        int temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10;
        int voltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);

        int pct = currentBatteryLevel;
        if (level >= 0 && scale > 0) {
            pct = Math.round((level / (float) scale) * 100);
        }

        boolean charging = (status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL ||
                plugged > 0);

        String plugStr = "none";
        if (plugged == BatteryManager.BATTERY_PLUGGED_AC) plugStr = "AC";
        else if (plugged == BatteryManager.BATTERY_PLUGGED_USB) plugStr = "USB";
        else if (plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS) plugStr = "Wireless";

        boolean chargingStateChanged = (charging != isCurrentlyCharging);
        currentBatteryLevel = pct;
        isCurrentlyCharging = charging;
        currentTemperature = temp;
        currentVoltage = voltage;
        currentPlugType = plugStr;

        Log.d(TAG, "Battery Changed: Level=" + pct + "%, Charging=" + charging + ", Temp=" + temp + "°C, Plug=" + plugStr);

        updateForegroundNotification();

        if (batteryStateListener != null) {
            batteryStateListener.onBatteryUpdate(pct, charging, temp, voltage, plugStr);
        }

        if (chargingStateChanged) {
            if (charging) {
                handlePowerConnected();
            } else {
                handlePowerDisconnected();
            }
        }

        // Target reached alert (e.g. 80% or 100%)
        int targetPct = settingsManager.getTargetPercentage();
        if (charging && pct >= targetPct && !hasAlertedTarget && settingsManager.isServiceEnabled()) {
            hasAlertedTarget = true;
            triggerTargetReachedAlert(pct, targetPct);
        }

        // Temperature warning alert
        int tempThreshold = settingsManager.getTemperatureThreshold();
        if (temp >= tempThreshold && !hasAlertedTemp && settingsManager.isServiceEnabled()) {
            hasAlertedTemp = true;
            triggerTemperatureAlert(temp);
        } else if (temp < tempThreshold - 2) {
            hasAlertedTemp = false;
        }
    }

    private void handlePowerConnected() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastPowerConnectedTimestamp < 1500) {
            Log.d(TAG, "Duplicate power connected event suppressed");
            return;
        }
        lastPowerConnectedTimestamp = now;

        Log.d(TAG, "Charger Connected: Power Connected Detected");
        isCurrentlyCharging = true;
        hasAlertedTarget = false;

        // Charger connected Voice Alert
        if (settingsManager.isServiceEnabled()) {
            int level = currentBatteryLevel > 0 ? currentBatteryLevel : 50;
            String levelBn = BengaliTTSHelper.toBengaliDigits(level);
            String defaultPhrase = "চার্জার সংযোগ করা হয়েছে।";
            String voiceText = settingsManager.getCustomPhraseForEvent("charger_connected", defaultPhrase);
            if (voiceText.contains("{percent}")) {
                voiceText = voiceText.replace("{percent}", levelBn + "%");
            }

            boolean isVoiceOn = settingsManager.isVoiceEnabled();
            if (isVoiceOn) {
                ttsHelper.speak(voiceText, null);
            }

            // If voice is on, suppress sound on notification so speech is not interrupted
            notificationHelper.showAlertNotification(
                    "🔌 চার্জার সংযোগ",
                    "চার্জার সংযোগ করা হয়েছে। ব্যাটারি: " + levelBn + "%",
                    !isVoiceOn && settingsManager.isSoundEnabled(),
                    settingsManager.isVibrationEnabled()
            );
        }

        // Start repeating reminder while charging
        if (settingsManager.isReminderEnabled() && settingsManager.isServiceEnabled()) {
            scheduleRepeatingReminder();
        }

        // Maintain wake lock if screen is off so monitoring continues
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null && !pm.isInteractive()) {
            acquireScreenOffWakeLock();
        }

        updateForegroundNotification();
    }

    private void handlePowerDisconnected() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastPowerDisconnectedTimestamp < 1500) {
            Log.d(TAG, "Duplicate power disconnected event suppressed");
            return;
        }
        lastPowerDisconnectedTimestamp = now;

        Log.d(TAG, "Charger Disconnected: Power Removed Detected");
        isCurrentlyCharging = false;
        hasAlertedTarget = false;

        cancelRepeatingReminder();
        releaseScreenOffWakeLock();

        if (settingsManager.isServiceEnabled()) {
            int level = currentBatteryLevel > 0 ? currentBatteryLevel : 50;
            String levelBn = BengaliTTSHelper.toBengaliDigits(level);
            String defaultPhrase = "চার্জার খুলে ফেলা হয়েছে।";
            String voiceText = settingsManager.getCustomPhraseForEvent("charger_removed", defaultPhrase);
            if (voiceText.contains("{percent}")) {
                voiceText = voiceText.replace("{percent}", levelBn + "%");
            }

            boolean isVoiceOn = settingsManager.isVoiceEnabled();
            if (isVoiceOn) {
                ttsHelper.speak(voiceText, null);
            }

            notificationHelper.showAlertNotification(
                    "⚡ চার্জার বিচ্ছিন্ন",
                    "চার্জার খুলে ফেলা হয়েছে। বর্তমান চার্জ: " + levelBn + "%",
                    !isVoiceOn && settingsManager.isSoundEnabled(),
                    settingsManager.isVibrationEnabled()
            );
        }

        updateForegroundNotification();
    }

    private void triggerTargetReachedAlert(int currentPct, int targetPct) {
        Log.d(TAG, "Target Battery Reached: Current=" + currentPct + "%, Target=" + targetPct + "%");

        String levelBn = BengaliTTSHelper.toBengaliDigits(currentPct);
        String defaultPhrase = (currentPct >= 100)
                ? "চার্জ সম্পূর্ণ হয়েছে। চার্জার খুলে দিন।"
                : "আপনার ফোনের চার্জ " + levelBn + " শতাংশ হয়েছে। চার্জার খুলে নেওয়ার সময় হয়েছে।";

        String voiceText = settingsManager.getCustomPhraseForEvent("target_reached", defaultPhrase);
        if (voiceText.contains("{percent}")) {
            voiceText = voiceText.replace("{percent}", levelBn + "%");
        }

        boolean isVoiceOn = settingsManager.isVoiceEnabled();
        if (isVoiceOn) {
            ttsHelper.speak(voiceText, null);
        }

        notificationHelper.showAlertNotification(
                (currentPct >= 100) ? "🎉 চার্জ সম্পূর্ণ (১০০%)" : "🎯 লক্ষ্যমাত্রা চার্জ সম্পন্ন (" + levelBn + "%)",
                voiceText,
                !isVoiceOn && settingsManager.isSoundEnabled(),
                settingsManager.isVibrationEnabled()
        );
    }

    private void triggerTemperatureAlert(int temp) {
        Log.d(TAG, "Temperature Alert Triggered: " + temp + "°C");

        String tempBn = BengaliTTSHelper.toBengaliDigits(temp);
        String defaultPhrase = "সতর্কতা: আপনার ফোনের ব্যাটারির তাপমাত্রা বৃদ্ধি পেয়ে " + tempBn + " ডিগ্রি হয়েছে। চার্জার খুলে রাখুন।";
        String voiceText = settingsManager.getCustomPhraseForEvent("temperature_warning", defaultPhrase);
        if (voiceText.contains("{temp}")) {
            voiceText = voiceText.replace("{temp}", tempBn + "°C");
        }

        boolean isVoiceOn = settingsManager.isVoiceEnabled();
        if (isVoiceOn) {
            ttsHelper.speak(voiceText, null);
        }

        notificationHelper.showAlertNotification(
                "🔥 উচ্চ ব্যাটারি তাপমাত্রা (" + tempBn + "°C)",
                voiceText,
                !isVoiceOn && settingsManager.isSoundEnabled(),
                settingsManager.isVibrationEnabled()
        );
    }

    private void updateForegroundNotification() {
        if (!isRunning) return;
        int level = currentBatteryLevel > 0 ? currentBatteryLevel : 50;
        int reminderMins = settingsManager.getReminderIntervalMinutes();
        boolean isRemActive = isCurrentlyCharging && settingsManager.isReminderEnabled();

        Notification notif = notificationHelper.buildForegroundServiceNotification(
                level, isCurrentlyCharging, isRemActive, reminderMins
        );

        android.app.NotificationManager nm = (android.app.NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            try {
                nm.notify(BatteryNotificationHelper.NOTIFICATION_SERVICE_ID, notif);
            } catch (Exception e) {
                Log.e(TAG, "Failed to update foreground notification", e);
            }
        }

        syncWidgetState();
    }

    private void syncWidgetState() {
        try {
            int level = currentBatteryLevel > 0 ? currentBatteryLevel : 50;
            String reminderInfo = "টার্গেট: " + BengaliTTSHelper.toBengaliDigits(settingsManager.getTargetPercentage()) +
                    "% • রিমাইন্ডার: " + BengaliTTSHelper.toBengaliDigits(settingsManager.getReminderIntervalMinutes()) + " মিনিট";
            BatteryAppWidgetProvider.updateAllWidgets(
                    this,
                    level,
                    isCurrentlyCharging,
                    currentTemperature,
                    currentPlugType,
                    reminderInfo
            );
        } catch (Exception e) {
            Log.e(TAG, "Error syncing widget state", e);
        }
    }

    private void acquireScreenOffWakeLock() {
        try {
            if (screenOffWakeLock != null && !screenOffWakeLock.isHeld()) {
                screenOffWakeLock.acquire(15 * 60 * 1000L); // 15-minute safety timeout
                Log.d(TAG, "Screen-off wake lock acquired to ensure continuous charging monitoring");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error acquiring screen off wake lock", e);
        }
    }

    private void releaseScreenOffWakeLock() {
        try {
            if (screenOffWakeLock != null && screenOffWakeLock.isHeld()) {
                screenOffWakeLock.release();
                Log.d(TAG, "Screen-off wake lock released");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error releasing screen off wake lock", e);
        }
    }

    private synchronized void scheduleRepeatingReminder() {
        cancelRepeatingReminder();

        if (!isCurrentlyCharging || !settingsManager.isServiceEnabled() || !settingsManager.isReminderEnabled()) {
            return;
        }

        int intervalMins = settingsManager.getReminderIntervalMinutes();
        if (intervalMins < 1) intervalMins = 10;
        long intervalMillis = intervalMins * 60 * 1000L;
        long triggerAtMillis = SystemClock.elapsedRealtime() + intervalMillis;

        Log.d(TAG, "Scheduling repeating reminder in " + intervalMins + " minutes (" + intervalMillis + "ms)");

        handler.postDelayed(reminderRunnable, intervalMillis);

        try {
            Intent alarmIntent = new Intent(this, ChargingForegroundService.class);
            alarmIntent.setAction(ACTION_REMINDER_ALARM);

            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }

            reminderPendingIntent = PendingIntent.getService(this, 1002, alarmIntent, flags);
            if (alarmManager != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(
                            AlarmManager.ELAPSED_REALTIME_WAKEUP,
                            triggerAtMillis,
                            reminderPendingIntent
                    );
                } else {
                    alarmManager.set(
                            AlarmManager.ELAPSED_REALTIME_WAKEUP,
                            triggerAtMillis,
                            reminderPendingIntent
                    );
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error setting AlarmManager for reminder", e);
        }
    }

    private synchronized void cancelRepeatingReminder() {
        handler.removeCallbacks(reminderRunnable);
        if (reminderPendingIntent != null && alarmManager != null) {
            try {
                alarmManager.cancel(reminderPendingIntent);
                reminderPendingIntent = null;
            } catch (Exception e) {
                Log.e(TAG, "Error cancelling reminder alarm", e);
            }
        }
    }

    private synchronized void onReminderFired() {
        Log.d(TAG, "Repeating Reminder Fired: IsCharging=" + isCurrentlyCharging + ", ServiceEnabled=" + settingsManager.isServiceEnabled());

        if (!isCurrentlyCharging) {
            Log.d(TAG, "Phone is not charging. Discarding reminder.");
            cancelRepeatingReminder();
            return;
        }

        if (!settingsManager.isServiceEnabled() || !settingsManager.isReminderEnabled()) {
            Log.d(TAG, "Reminder is disabled in settings. Skipping.");
            return;
        }

        int currentLevel = currentBatteryLevel > 0 ? currentBatteryLevel : 50;
        String levelBn = BengaliTTSHelper.toBengaliDigits(currentLevel);
        String defaultReminder = "বর্তমানে আপনার ফোনের ব্যাটারি " + levelBn + " শতাংশ চার্জ হয়েছে। চার্জিং অব্যাহত রয়েছে।";
        String customPhrase = settingsManager.getCustomPhraseForEvent("reminder", "");

        String voiceText;
        if (customPhrase != null && !customPhrase.trim().isEmpty()) {
            if (customPhrase.contains("{percent}")) {
                voiceText = customPhrase.replace("{percent}", levelBn + "%");
            } else {
                voiceText = "বর্তমানে আপনার ফোনের ব্যাটারি " + levelBn + " শতাংশ চার্জ হয়েছে। " + customPhrase.trim();
            }
        } else {
            voiceText = defaultReminder;
        }

        Log.d(TAG, "Repeating reminder voice alert text: [" + voiceText + "]");

        boolean isVoiceOn = settingsManager.isVoiceEnabled();
        if (isVoiceOn) {
            ttsHelper.speak(voiceText, null);
        }

        notificationHelper.showAlertNotification(
                "⏰ চার্জিং রিমাইন্ডার (" + BengaliTTSHelper.toBengaliDigits(settingsManager.getReminderIntervalMinutes()) + " মিনিট)",
                "ফোন এখনও চার্জ হচ্ছে (বর্তমান চার্জ: " + levelBn + "%)।",
                !isVoiceOn && settingsManager.isSoundEnabled(),
                settingsManager.isVibrationEnabled()
        );

        scheduleRepeatingReminder();
    }

    private void handleTestVoice(String customText) {
        String textToSpeak = customText;
        if (textToSpeak == null || textToSpeak.trim().isEmpty()) {
            textToSpeak = settingsManager.getCustomVoiceText();
        }
        if (textToSpeak == null || textToSpeak.trim().isEmpty()) {
            textToSpeak = "এটি চার্জিং সহকারীর বাংলা ভয়েস পরীক্ষা।";
        }

        Log.d(TAG, "Testing Voice Alert with text: [" + textToSpeak + "]");
        ttsHelper.speak(textToSpeak, null);
    }

    private void handleTestReminder() {
        Log.d(TAG, "Immediate Test Reminder Triggered");

        String defaultReminder = "আপনার ফোন চার্জ হচ্ছে। এটি একটি পরীক্ষামূলক রিমাইন্ডার।";
        String voiceText = settingsManager.getCustomPhraseForEvent("reminder", defaultReminder);

        if (settingsManager.isVoiceEnabled()) {
            ttsHelper.speak(voiceText, null);
        }

        notificationHelper.showAlertNotification(
                "⏰ পরীক্ষামূলক রিমাইন্ডার",
                "রিমাইন্ডার ভয়েস ও অ্যালার্ট সফলভাবে পরীক্ষা করা হয়েছে।",
                !settingsManager.isVoiceEnabled() && settingsManager.isSoundEnabled(),
                settingsManager.isVibrationEnabled()
        );
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        Log.d(TAG, "onTaskRemoved: Activity swiped from recents. Foreground Service remains active (START_STICKY).");
        updateForegroundNotification();
        if (settingsManager != null && settingsManager.isServiceEnabled()) {
            scheduleServiceRestart();
        }
    }

    private void scheduleServiceRestart() {
        try {
            Intent restartServiceIntent = new Intent(getApplicationContext(), ChargingForegroundService.class);
            restartServiceIntent.setAction(ACTION_START);
            int flags = PendingIntent.FLAG_ONE_SHOT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent restartPendingIntent = PendingIntent.getService(
                    getApplicationContext(),
                    1003,
                    restartServiceIntent,
                    flags
            );
            if (alarmManager != null) {
                alarmManager.set(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        SystemClock.elapsedRealtime() + 2000,
                        restartPendingIntent
                );
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to schedule service restart", e);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isRunning = false;
        Log.d(TAG, "ChargingForegroundService onDestroy: Stopping Service");

        try {
            unregisterReceiver(batteryReceiver);
        } catch (Exception e) {
            Log.e(TAG, "Error unregistering battery receiver", e);
        }

        cancelRepeatingReminder();
        releaseScreenOffWakeLock();

        if (ttsHelper != null) {
            ttsHelper.shutdown();
        }

        if (batteryStateListener != null) {
            batteryStateListener.onServiceStatusChanged(false);
        }

        if (!userRequestedStop && settingsManager != null && settingsManager.isServiceEnabled()) {
            Log.w(TAG, "Service killed unexpectedly by OS. Scheduling automatic restart...");
            scheduleServiceRestart();
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
