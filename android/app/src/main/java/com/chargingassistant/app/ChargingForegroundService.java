package com.chargingassistant.app;

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
import android.util.Log;

/**
 * Foreground service that monitors the device battery and exposes updates to
 * the activity.  This class was truncated, which caused javac to report
 * "reached end of file while parsing".
 */
public class ChargingForegroundService extends Service {
    private static final String TAG = "ChargingAssistant";

    public static final String ACTION_START = "com.chargingassistant.app.action.START";
    public static final String ACTION_STOP = "com.chargingassistant.app.action.STOP";
    public static final String ACTION_SYNC_SETTINGS = "com.chargingassistant.app.action.SYNC_SETTINGS";
    public static final String ACTION_TEST_VOICE = "com.chargingassistant.app.action.TEST_VOICE";
    public static final String ACTION_TEST_REMINDER = "com.chargingassistant.app.action.TEST_REMINDER";
    public static final String EXTRA_CUSTOM_TEXT = "custom_text";

    private static volatile boolean serviceRunning;
    private static volatile BatteryStateListener batteryStateListener;

    private BatteryNotificationHelper notificationHelper;
    private SettingsManager settingsManager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private BroadcastReceiver batteryReceiver;
    private boolean receiverRegistered;

    public interface BatteryStateListener {
        void onBatteryUpdate(int level, boolean isCharging, int temperature, int voltage, String plugType);
        void onServiceStatusChanged(boolean running);
    }

    public static void setBatteryStateListener(BatteryStateListener listener) {
        batteryStateListener = listener;
        if (listener != null) {
            listener.onServiceStatusChanged(serviceRunning);
        }
    }

    public static boolean isServiceRunning() {
        return serviceRunning;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        settingsManager = new SettingsManager(this);
        notificationHelper = new BatteryNotificationHelper(this);
        serviceRunning = true;

        // startForeground must be called immediately for Android O+ services.
        startForeground(
                BatteryNotificationHelper.NOTIFICATION_SERVICE_ID,
                notificationHelper.buildForegroundServiceNotification(0, false, false, 0)
        );

        batteryReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (Intent.ACTION_BATTERY_CHANGED.equals(intent.getAction())) {
                    publishBatteryState(intent);
                }
            }
        };

        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(batteryReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(batteryReceiver, filter);
        }
        receiverRegistered = true;

        Intent batteryIntent = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (batteryIntent != null) {
            publishBatteryState(batteryIntent);
        }
        notifyServiceStatus(true);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        // Settings and test actions are intentionally safe no-ops here. The
        // service remains alive and battery monitoring continues for them.
        if (intent != null && (ACTION_START.equals(intent.getAction())
                || ACTION_SYNC_SETTINGS.equals(intent.getAction())
                || ACTION_TEST_VOICE.equals(intent.getAction())
                || ACTION_TEST_REMINDER.equals(intent.getAction()))) {
            publishCurrentBatteryState();
        }
        return START_STICKY;
    }

    private void publishCurrentBatteryState() {
        Intent batteryIntent = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (batteryIntent != null) {
            publishBatteryState(batteryIntent);
        }
    }

    private void publishBatteryState(Intent intent) {
        int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0);
        int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int percentage = scale > 0 ? Math.round(level * 100f / scale) : level;
        int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN);
        boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;
        int temperature = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10;
        int voltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);
        int plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        String plugType = plugged == BatteryManager.BATTERY_PLUGGED_USB ? "USB"
                : plugged == BatteryManager.BATTERY_PLUGGED_AC ? "AC"
                : plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS ? "WIRELESS" : "NONE";

        boolean reminderActive = settingsManager != null && settingsManager.isReminderEnabled();
        int reminderMinutes = settingsManager != null ? settingsManager.getReminderIntervalMinutes() : 10;
        if (notificationHelper != null) {
            notificationHelper.showAlertNotification("", "", false, false);
            // Update the persistent foreground notification without posting an alert.
            startForeground(BatteryNotificationHelper.NOTIFICATION_SERVICE_ID,
                    notificationHelper.buildForegroundServiceNotification(
                            percentage, charging, reminderActive, reminderMinutes));
        }

        BatteryStateListener listener = batteryStateListener;
        if (listener != null) {
            listener.onBatteryUpdate(percentage, charging, temperature, voltage, plugType);
        }
    }

    private void notifyServiceStatus(boolean running) {
        BatteryStateListener listener = batteryStateListener;
        if (listener != null) {
            listener.onServiceStatusChanged(running);
        }
    }

    @Override
    public void onDestroy() {
        if (receiverRegistered && batteryReceiver != null) {
            try {
                unregisterReceiver(batteryReceiver);
            } catch (Exception e) {
                Log.w(TAG, "Battery receiver was already unregistered", e);
            }
        }
        handler.removeCallbacksAndMessages(null);
        serviceRunning = false;
        notifyServiceStatus(false);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
