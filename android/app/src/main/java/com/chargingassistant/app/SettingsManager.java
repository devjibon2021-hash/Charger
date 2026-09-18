package com.chargingassistant.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

public class SettingsManager {
    private static final String TAG = "ChargingAssistant";
    private static final String PREF_NAME = "charging_assistant_prefs";

    public static final String KEY_SERVICE_ENABLED = "service_enabled";
    public static final String KEY_TARGET_PERCENTAGE = "target_percentage";
    public static final String KEY_REMINDER_ENABLED = "repeat_reminder_enabled";
    public static final String KEY_REMINDER_INTERVAL_MINUTES = "reminder_interval_minutes";
    public static final String KEY_CUSTOM_VOICE_TEXT = "custom_voice_text";
    public static final String KEY_SOUND_ENABLED = "sound_enabled";
    public static final String KEY_VIBRATION_ENABLED = "vibration_enabled";
    public static final String KEY_VOICE_ENABLED = "voice_enabled";
    public static final String KEY_CUSTOM_PHRASES_JSON = "custom_phrases_json";
    public static final String KEY_TEMPERATURE_THRESHOLD = "temperature_threshold";

    private final SharedPreferences prefs;

    public SettingsManager(Context context) {
        this.prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public boolean isServiceEnabled() {
        return prefs.getBoolean(KEY_SERVICE_ENABLED, true);
    }

    public void setServiceEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_SERVICE_ENABLED, enabled).apply();
    }

    public int getTargetPercentage() {
        return prefs.getInt(KEY_TARGET_PERCENTAGE, 90);
    }

    public void setTargetPercentage(int pct) {
        prefs.edit().putInt(KEY_TARGET_PERCENTAGE, pct).apply();
    }

    public boolean isReminderEnabled() {
        return prefs.getBoolean(KEY_REMINDER_ENABLED, true);
    }

    public void setReminderEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_REMINDER_ENABLED, enabled).apply();
    }

    public int getReminderIntervalMinutes() {
        int interval = prefs.getInt(KEY_REMINDER_INTERVAL_MINUTES, 10);
        return interval > 0 ? interval : 10;
    }

    public void setReminderIntervalMinutes(int minutes) {
        if (minutes <= 0) minutes = 10;
        prefs.edit().putInt(KEY_REMINDER_INTERVAL_MINUTES, minutes).apply();
    }

    public String getCustomVoiceText() {
        return prefs.getString(KEY_CUSTOM_VOICE_TEXT, "").trim();
    }

    public void setCustomVoiceText(String text) {
        prefs.edit().putString(KEY_CUSTOM_VOICE_TEXT, text != null ? text.trim() : "").apply();
    }

    public boolean isSoundEnabled() {
        return prefs.getBoolean(KEY_SOUND_ENABLED, true);
    }

    public boolean isVibrationEnabled() {
        return prefs.getBoolean(KEY_VIBRATION_ENABLED, true);
    }

    public boolean isVoiceEnabled() {
        return prefs.getBoolean(KEY_VOICE_ENABLED, true);
    }

    public int getTemperatureThreshold() {
        return prefs.getInt(KEY_TEMPERATURE_THRESHOLD, 45);
    }

    public String getCustomPhraseForEvent(String eventKey, String defaultText) {
        try {
            String phrasesJson = prefs.getString(KEY_CUSTOM_PHRASES_JSON, "");
            if (phrasesJson != null && !phrasesJson.isEmpty()) {
                JSONObject obj = new JSONObject(phrasesJson);
                if (obj.has(eventKey)) {
                    String custom = obj.optString(eventKey, "").trim();
                    if (!custom.isEmpty()) {
                        return custom;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error parsing custom phrases json", e);
        }

        // Check if main custom voice text is set (written from Voice Writer)
        String mainCustom = getCustomVoiceText();
        if ((eventKey.equals("target_reached") || eventKey.equals("reminder")) && !mainCustom.isEmpty()) {
            return mainCustom;
        }

        return defaultText;
    }

    /**
     * Synchronizes settings from React UI JSON string into Android SharedPreferences
     */
    public boolean syncFromJson(String jsonString) {
        if (jsonString == null || jsonString.isEmpty()) return false;
        try {
            JSONObject obj = new JSONObject(jsonString);
            SharedPreferences.Editor editor = prefs.edit();

            if (obj.has("serviceEnabled")) {
                editor.putBoolean(KEY_SERVICE_ENABLED, obj.getBoolean("serviceEnabled"));
            }

            if (obj.has("targetBatteryLevel")) {
                int target = obj.getInt("targetBatteryLevel");
                if (target == 0 && obj.has("customTargetLevel")) {
                    target = obj.getInt("customTargetLevel");
                }
                editor.putInt(KEY_TARGET_PERCENTAGE, target > 0 ? target : 90);
            }

            if (obj.has("repeatReminderEnabled")) {
                editor.putBoolean(KEY_REMINDER_ENABLED, obj.getBoolean("repeatReminderEnabled"));
            }

            if (obj.has("reminderIntervalMinutes")) {
                int interval = obj.getInt("reminderIntervalMinutes");
                if (interval == 0 && obj.has("customIntervalMinutes")) {
                    interval = obj.getInt("customIntervalMinutes");
                }
                if (interval > 0) {
                    editor.putInt(KEY_REMINDER_INTERVAL_MINUTES, interval);
                }
            }

            if (obj.has("soundEnabled")) {
                editor.putBoolean(KEY_SOUND_ENABLED, obj.getBoolean("soundEnabled"));
            }

            if (obj.has("vibrationEnabled")) {
                editor.putBoolean(KEY_VIBRATION_ENABLED, obj.getBoolean("vibrationEnabled"));
            }

            if (obj.has("bengaliVoiceEnabled")) {
                editor.putBoolean(KEY_VOICE_ENABLED, obj.getBoolean("bengaliVoiceEnabled"));
            }

            if (obj.has("temperatureThreshold")) {
                editor.putInt(KEY_TEMPERATURE_THRESHOLD, obj.getInt("temperatureThreshold"));
            }

            // Parse customPhrases
            if (obj.has("customPhrases")) {
                JSONObject phrasesObj = obj.getJSONObject("customPhrases");
                editor.putString(KEY_CUSTOM_PHRASES_JSON, phrasesObj.toString());

                if (phrasesObj.has("customBengaliText")) {
                    String customText = phrasesObj.optString("customBengaliText", "").trim();
                    if (!customText.isEmpty()) {
                        editor.putString(KEY_CUSTOM_VOICE_TEXT, customText);
                    }
                } else if (phrasesObj.has("target_reached")) {
                    String targetText = phrasesObj.optString("target_reached", "").trim();
                    if (!targetText.isEmpty()) {
                        editor.putString(KEY_CUSTOM_VOICE_TEXT, targetText);
                    }
                }
            }

            editor.apply();
            Log.d(TAG, "Settings synced successfully to Native SharedPreferences");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse settings JSON: " + jsonString, e);
            return false;
        }
    }

    public String toJson() {
        try {
            JSONObject obj = new JSONObject();
            obj.put("serviceEnabled", isServiceEnabled());
            obj.put("targetBatteryLevel", getTargetPercentage());
            obj.put("repeatReminderEnabled", isReminderEnabled());
            obj.put("reminderIntervalMinutes", getReminderIntervalMinutes());
            obj.put("soundEnabled", isSoundEnabled());
            obj.put("vibrationEnabled", isVibrationEnabled());
            obj.put("bengaliVoiceEnabled", isVoiceEnabled());
            obj.put("customVoiceText", getCustomVoiceText());
            obj.put("temperatureThreshold", getTemperatureThreshold());
            return obj.toString();
        } catch (Exception e) {
            return "{}";
        }
    }
}
