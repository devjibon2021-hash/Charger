package com.chargingassistant.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.Locale;

public class MainActivity extends AppCompatActivity implements ChargingForegroundService.BatteryStateListener {
    private static final String TAG = "ChargingAssistant";
    private static final int PERMISSION_REQUEST_CODE = 101;

    private WebView webView;
    private ChargingAssistantBridge nativeBridge;

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
        }

        webView = findViewById(R.id.webview);

        // Configure optimal WebView settings
        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setDatabaseEnabled(true);
        webSettings.setAllowFileAccess(true);
        webSettings.setAllowContentAccess(true);
        webSettings.setMediaPlaybackRequiresUserGesture(false);
        webSettings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        webSettings.setCacheMode(WebSettings.LOAD_DEFAULT);

        // Add Javascript Interface Bridge
        nativeBridge = new ChargingAssistantBridge(this);
        webView.addJavascriptInterface(nativeBridge, "ChargingAssistantNative");

        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                // Pre-set native flag so early JS logic can detect native Android container
                webView.evaluateJavascript("window.isAndroidNativeApp = true;", null);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                Log.d(TAG, "WebView page finished loading: " + url);
                // Inform React that Native Android environment is ready with verified service status
                boolean isRunning = ChargingForegroundService.isServiceRunning();
                String initScript = String.format(Locale.US,
                        "window.isAndroidNativeApp = true; " +
                        "window.dispatchEvent(new CustomEvent('nativeBridgeReady')); " +
                        "window.dispatchEvent(new CustomEvent('nativeServiceStatus', { detail: { isRunning: %b } }));",
                        isRunning
                );
                webView.evaluateJavascript(initScript, null);
            }
        });

        // Request Notification Permission on Android 13+
        requestNotificationPermission();

        // Start Native Foreground Service
        startForegroundService();

        // Register listener for native battery updates
        ChargingForegroundService.setBatteryStateListener(this);

        // Load application: try local asset dist if available, else load app URL
        String appUrl = "file:///android_asset/dist/index.html";
        try {
            getAssets().open("dist/index.html").close();
            webView.loadUrl(appUrl);
        } catch (Exception e) {
            String remoteUrl = "https://ais-dev-65n4szlilzab2m2yqvmoue-790463454183.europe-west2.run.app";
            Log.d(TAG, "Loading remote URL: " + remoteUrl);
            webView.loadUrl(remoteUrl);
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, PERMISSION_REQUEST_CODE);
            }
        }
    }

    private void startForegroundService() {
        Intent intent = new Intent(this, ChargingForegroundService.class);
        intent.setAction(ChargingForegroundService.ACTION_START);
        try {
            ContextCompat.startForegroundService(this, intent);
        } catch (Exception e) {
            Log.e(TAG, "Error starting foreground service from activity", e);
        }
    }

    @Override
    public void onBatteryUpdate(int level, boolean isCharging, int temperature, int voltage, String plugType) {
        runOnUiThread(() -> {
            if (webView != null) {
                String script = String.format(Locale.US,
                        "if (typeof window.onNativeBatteryUpdate === 'function') { " +
                        "  window.onNativeBatteryUpdate({ level: %d, isCharging: %b, temperature: %d, voltage: %d, plugType: '%s' }); " +
                        "} else { " +
                        "  window.dispatchEvent(new CustomEvent('nativeBatteryChanged', { detail: { level: %d, isCharging: %b, temperature: %d, voltage: %d, plugType: '%s' } })); " +
                        "}",
                        level, isCharging, temperature, voltage, plugType,
                        level, isCharging, temperature, voltage, plugType
                );
                webView.evaluateJavascript(script, null);
            }
        });
    }

    @Override
    public void onServiceStatusChanged(boolean running) {
        runOnUiThread(() -> {
            if (webView != null) {
                String script = String.format(Locale.US,
                        "window.dispatchEvent(new CustomEvent('nativeServiceStatus', { detail: { isRunning: %b } }));",
                        running
                );
                webView.evaluateJavascript(script, null);
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "POST_NOTIFICATIONS permission granted by user");
            } else {
                Log.w(TAG, "POST_NOTIFICATIONS permission denied by user. Voice alerts and service monitoring remain fully functional.");
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            // Keep background service and application state smoothly running when user backs out
            moveTaskToBack(true);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ChargingForegroundService.setBatteryStateListener(null);
    }
}
