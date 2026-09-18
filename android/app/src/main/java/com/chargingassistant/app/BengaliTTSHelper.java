package com.chargingassistant.app;

import android.content.Context;
import android.media.AudioAttributes;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;

public class BengaliTTSHelper implements TextToSpeech.OnInitListener {
    private static final String TAG = "ChargingAssistant";
    private static final int MAX_INIT_RETRIES = 2;

    private final Context context;
    private TextToSpeech tts;
    private volatile boolean isInitialized = false;
    private volatile boolean isInitializing = false;
    private boolean isBengaliSupported = false;
    private int initRetryCount = 0;
    private PowerManager.WakeLock wakeLock;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private static class SpeechRequest {
        final String text;
        final OnSpeechCompleteListener listener;

        SpeechRequest(String text, OnSpeechCompleteListener listener) {
            this.text = text;
            this.listener = listener;
        }
    }

    private final ConcurrentLinkedQueue<SpeechRequest> speechQueue = new ConcurrentLinkedQueue<>();

    public interface OnSpeechCompleteListener {
        void onComplete();
        void onError(String error);
    }

    public BengaliTTSHelper(Context context) {
        this.context = context.getApplicationContext();
        initWakeLock();
        initTTS();
    }

    private void initWakeLock() {
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ChargingAssistant:TTSWakeLock");
                wakeLock.setReferenceCounted(false);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to create partial wake lock", e);
        }
    }

    private synchronized void initTTS() {
        if (isInitialized || isInitializing) return;
        isInitializing = true;
        Log.d(TAG, "Initializing Native Android TextToSpeech engine (attempt " + (initRetryCount + 1) + "/" + (MAX_INIT_RETRIES + 1) + ")...");

        try {
            this.tts = new TextToSpeech(context, this);
        } catch (Exception e) {
            Log.e(TAG, "Exception creating TextToSpeech instance", e);
            isInitializing = false;
            handleInitFailure();
        }
    }

    @Override
    public void onInit(int status) {
        isInitializing = false;
        if (status == TextToSpeech.SUCCESS) {
            Log.d(TAG, "Native TextToSpeech engine initialized successfully");
            initRetryCount = 0;

            // Setup audio attributes optimized for accessibility and lockscreen announcements
            try {
                AudioAttributes audioAttributes = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build();
                tts.setAudioAttributes(audioAttributes);
            } catch (Exception e) {
                Log.w(TAG, "Could not set custom AudioAttributes on TTS", e);
            }

            // Priority order for Bengali locale:
            // 1. Bengali (Bangladesh) - bn-BD
            // 2. Bengali (India) - bn-IN
            // 3. Bengali generic - bn
            Locale[] bengaliLocales = new Locale[]{
                    new Locale("bn", "BD"),
                    new Locale("bn", "IN"),
                    new Locale("bn")
            };

            boolean langSet = false;
            for (Locale loc : bengaliLocales) {
                try {
                    int res = tts.isLanguageAvailable(loc);
                    if (res >= TextToSpeech.LANG_AVAILABLE) {
                        tts.setLanguage(loc);
                        isBengaliSupported = true;
                        langSet = true;
                        Log.d(TAG, "Native TTS: Successfully configured locale " + loc.toLanguageTag());
                        break;
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Error checking locale " + loc, e);
                }
            }

            if (!langSet) {
                try {
                    Locale defaultBn = new Locale("bn", "BD");
                    int res = tts.setLanguage(defaultBn);
                    if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                        Log.w(TAG, "Bengali speech data is not installed on this device TTS. Falling back to default system voice.");
                        isBengaliSupported = false;
                    } else {
                        isBengaliSupported = true;
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Fallback locale set error", e);
                }
            }

            try {
                tts.setSpeechRate(0.95f);
                tts.setPitch(1.0f);
            } catch (Exception ignored) {
            }

            isInitialized = true;
            drainQueue();
        } else {
            Log.e(TAG, "TextToSpeech onInit failed with status code: " + status);
            isInitialized = false;
            handleInitFailure();
        }
    }

    private void handleInitFailure() {
        if (initRetryCount < MAX_INIT_RETRIES) {
            initRetryCount++;
            Log.d(TAG, "Scheduling TTS retry #" + initRetryCount + " in 1500ms");
            mainHandler.postDelayed(this::initTTS, 1500);
        } else {
            Log.e(TAG, "Max TTS retries reached. Clearing speech queue without hanging.");
            while (!speechQueue.isEmpty()) {
                SpeechRequest req = speechQueue.poll();
                if (req != null && req.listener != null) {
                    req.listener.onError("TTS initialization failed after retries");
                }
            }
        }
    }

    private void drainQueue() {
        mainHandler.post(() -> {
            while (!speechQueue.isEmpty() && isInitialized && tts != null) {
                SpeechRequest req = speechQueue.poll();
                if (req != null) {
                    Log.d(TAG, "Executing queued speech request: [" + req.text + "]");
                    executeSpeak(req.text, req.listener);
                }
            }
        });
    }

    public static String toBengaliDigits(int number) {
        String s = String.valueOf(number);
        char[] bnDigits = new char[]{'০', '১', '২', '৩', '৪', '৫', '৬', '৭', '৮', '৯'};
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c >= '0' && c <= '9') {
                sb.append(bnDigits[c - '0']);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    public static String replaceEnglishDigitsWithBengali(String text) {
        if (text == null) return "";
        char[] bnDigits = new char[]{'০', '১', '২', '৩', '৪', '৫', '৬', '৭', '৮', '৯'};
        StringBuilder sb = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (c >= '0' && c <= '9') {
                sb.append(bnDigits[c - '0']);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    public boolean isReady() {
        return isInitialized && tts != null;
    }

    public boolean isBengaliSupported() {
        return isBengaliSupported;
    }

    /**
     * Speaks text using Native Android TextToSpeech.
     * If TTS engine is not initialized yet, speech is placed in queue instead of dropping.
     * Partial WakeLock is acquired during playback to prevent CPU sleep on lockscreen.
     */
    public synchronized void speak(String text, final OnSpeechCompleteListener listener) {
        if (text == null || text.trim().isEmpty()) {
            if (listener != null) listener.onComplete();
            return;
        }

        final String formattedText = replaceEnglishDigitsWithBengali(text.trim());

        if (!isInitialized || tts == null) {
            Log.d(TAG, "TTS not ready yet. Queuing request: [" + formattedText + "]");
            speechQueue.offer(new SpeechRequest(formattedText, listener));
            if (!isInitializing) {
                initTTS();
            }
            return;
        }

        executeSpeak(formattedText, listener);
    }

    private synchronized void executeSpeak(final String formattedText, final OnSpeechCompleteListener listener) {
        final String utteranceId = "utterance_" + System.currentTimeMillis();
        Log.d(TAG, "Native TTS executing speech: [" + formattedText + "]");

        acquireWakeLock(12000); // 12 seconds max duration for short announcements

        try {
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String id) {
                    Log.d(TAG, "TTS utterance started: " + id);
                }

                @Override
                public void onDone(String id) {
                    Log.d(TAG, "TTS utterance finished successfully: " + id);
                    releaseWakeLock();
                    if (listener != null) {
                        mainHandler.post(listener::onComplete);
                    }
                }

                @Override
                public void onError(String id) {
                    Log.e(TAG, "TTS utterance error on utteranceId: " + id);
                    releaseWakeLock();
                    if (listener != null) {
                        mainHandler.post(() -> listener.onError("TTS error during speech"));
                    }
                }
            });

            Bundle params = new Bundle();
            params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId);

            int result = tts.speak(formattedText, TextToSpeech.QUEUE_FLUSH, params, utteranceId);
            if (result != TextToSpeech.SUCCESS) {
                Log.e(TAG, "tts.speak returned error code: " + result);
                releaseWakeLock();
                if (listener != null) {
                    listener.onError("TTS speak failed with code " + result);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Exception during tts.speak", e);
            releaseWakeLock();
            if (listener != null) {
                listener.onError(e.getMessage());
            }
        }
    }

    public void stop() {
        speechQueue.clear();
        if (tts != null) {
            try {
                tts.stop();
            } catch (Exception e) {
                Log.e(TAG, "Error stopping TTS", e);
            }
        }
        releaseWakeLock();
    }

    private void acquireWakeLock(long timeoutMs) {
        try {
            if (wakeLock != null && !wakeLock.isHeld()) {
                wakeLock.acquire(timeoutMs);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error acquiring wake lock", e);
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error releasing wake lock", e);
        }
    }

    public void shutdown() {
        stop();
        if (tts != null) {
            try {
                tts.shutdown();
            } catch (Exception e) {
                Log.e(TAG, "Error shutting down TTS", e);
            }
            tts = null;
        }
        isInitialized = false;
    }
}
