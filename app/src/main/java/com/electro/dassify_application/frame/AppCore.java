package com.electro.dassify_application.frame;

import android.content.Intent;
import android.graphics.Color;
import android.location.Location;
import android.net.Network;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import com.electro.dassify_application.R;
import com.electro.dassify_application.helpers.GPSSender;
import com.electro.dassify_application.helpers.MiniMapController;
import com.electro.dassify_application.helpers.QuestionDrawer;
import com.electro.dassify_application.helpers.SensorSourceManager;
import com.electro.dassify_application.helpers.UDPHelper;
import com.electro.dassify_application.services.AccelerometerManager;
import com.electro.dassify_application.services.DeviceIdentifierManager;
import com.electro.dassify_application.services.GPSManager;
import com.electro.dassify_application.services.NetworkManager;
import com.electro.dassify_application.services.PermissionWatcher;
import com.electro.dassify_application.services.TargetResolverManager;

import java.net.InetAddress;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * AppCore - Main activity and central coordinator for GPS Android application.
 */
public class AppCore extends AppCompatActivity implements
        GPSManager.LocationListenerExternal,
        GPSSender.SenderStateListener {

    private static final String TAG = "AppCore";
    private static final long UI_UPDATE_DEBOUNCE_MS = 300L;

    // UI Components
    private Button sendButton;
    private DrawerLayout drawerLayout;
    private ImageButton btnQuestion, btnMore;

    // Sensor source selection UI
    private RadioGroup sensorSourceRadioGroup;
    private RadioButton radioInternal;
    private RadioButton radioExternal;
    private TextView bluetoothStatusText;

    // ═══════════════════════════════════════════════════════════════════════════════════════
    // ✅ NUEVO: WiFi testing icon
    // ═══════════════════════════════════════════════════════════════════════════════════════
    private ImageView wifiTestingIcon;
    private int wifiIconClickCount = 0;  // Triple-tap detection
    private long lastWifiIconClickTime = 0;
    private static final long TRIPLE_TAP_TIMEOUT_MS = 800;  // 800ms window para 3 taps

    // Data and State Management
    private Location lastLocation;
    private Toast currentToast;

    // Helper Classes
    private QuestionDrawer questionDrawer;
    private GPSSender gpsSender;
    private UDPHelper udpHelper;

    // Animation and Permission Management
    private Animation blinkAnimation;
    private PermissionWatcher permissionWatcher;

    // Service Managers
    private GPSManager gpsManager;
    private NetworkManager networkManager;
    private TargetResolverManager resolver;
    private DeviceIdentifierManager identifierManager;
    private AccelerometerManager accelManager;
    private SensorSourceManager sensorSourceManager;  // Dual source manager (INTERNAL/EXTERNAL)

    // Thread-safe Availability States
    private volatile boolean gpsAvailable = false;
    private volatile boolean networkAvailable = false;
    private volatile boolean targetsAvailable = false;

    // UI Update Debouncing
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingStatusUpdate;

    // Optional: Test/Validation Listener
    private AccelerometerManager.AccelStatisticsListener testListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_appcore);

        initializeViews();
        blinkAnimation = AnimationUtils.loadAnimation(this, R.anim.blink);

        // Device Identifier Manager
        identifierManager = new DeviceIdentifierManager(this);
        Log.d(TAG, "DeviceIdentifierManager initialized");

        // GPS Manager - Location tracking
        gpsManager = new GPSManager(this);
        gpsManager.addLocationListener(this);
        gpsManager.start();
        Log.d(TAG, "GPSManager started");

        // Accelerometer Manager - Motion sensing (INTERNAL source)
        accelManager = new AccelerometerManager(this);
        accelManager.start();
        Log.d(TAG, "AccelerometerManager started");

        // Network Manager - Connectivity monitoring
        networkManager = new NetworkManager(this);
        networkManager.addListener(networkListener);
        Log.d(TAG, "NetworkManager initialized");

        // Target Resolver - UDP destination discovery
        resolver = new TargetResolverManager(this);
        resolver.addTargetsListener(targetsListener);
        resolver.start(2000);
        Log.d(TAG, "TargetResolverManager started");

        // Sensor Source Manager - Dual source coordinator
        sensorSourceManager = new SensorSourceManager(this);
        sensorSourceManager.start();  // Starts with INTERNAL by default
        Log.d(TAG, "SensorSourceManager initialized (default: INTERNAL)");

        // UDP Helper - Data transmission coordinator
        udpHelper = new UDPHelper(
                gpsManager,
                resolver,
                networkManager,
                identifierManager,
                accelManager
        );
        Log.d(TAG, "UDPHelper initialized");

        // MiniMap Controller - Visual feedback
        FrameLayout mapContainer = findViewById(R.id.map_container);
        new MiniMapController(this, mapContainer, gpsManager);
        Log.d(TAG, "MiniMapController initialized");

        // Question Drawer - Help and instructions
        questionDrawer = new QuestionDrawer(
                findViewById(R.id.drawer_instructions),
                this,
                drawerLayout,
                gpsManager,
                networkManager,
                resolver,
                accelManager
        );
        Log.d(TAG, "QuestionDrawer initialized");

        // GPSSender - Now uses SensorSourceManager for dual-source support
        gpsSender = new GPSSender(
                gpsManager,
                networkManager,
                resolver,
                udpHelper,
                sensorSourceManager,
                this
        );
        Log.d(TAG, "GPSSender initialized with SensorSourceManager");

        // Register Bluetooth listener to update UI status (if helper available)
        if (sensorSourceManager != null && sensorSourceManager.getBluetoothHelper() != null) {
            sensorSourceManager.getBluetoothHelper().addListener(new com.electro.dassify_application.helpers.BluetoothHelper.BluetoothListener() {
                @Override
                public void onBluetoothAvailabilityChanged(boolean available) {
                    // no-op
                }

                @Override
                public void onConnectionStateChanged(boolean connected) {
                    runOnUiThread(() -> {
                        updateBluetoothStatusText();
                        if (connected) showTransientPopup("ESP32 connected!");
                        else if (sensorSourceManager.getCurrentSource() == SensorSourceManager.SourceType.EXTERNAL)
                            showTransientPopup("ESP32 disconnected");
                    });
                }

                @Override
                public void onAccelDataReceived(com.electro.dassify_application.helpers.AccelPacket packet) {
                    // data handled by SensorSourceManager -> GPSSender
                }

                @Override
                public void onError(String errorMessage) {
                    runOnUiThread(() -> showTransientPopup("Bluetooth error: " + errorMessage));
                }
            });
        }

        setupListeners();

        // Permission Watcher - Runtime permission monitoring
        permissionWatcher = new PermissionWatcher(
                this,
                this,
                revokedPermissions -> runOnUiThread(() -> {
                    showTransientPopup("Permissions revoked: " + revokedPermissions);
                    Intent intent = new Intent(this, MainActivity.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                    finish();
                })
        );
        permissionWatcher.startWatching();
        Log.d(TAG, "PermissionWatcher started");

        // OPTIONAL: Accelerometer validation logging (uncomment call in onCreate() to enable)
        startAccelerometerTest();

        Log.i(TAG, "AppCore initialization complete");
    }

    private void initializeViews() {
        sendButton = findViewById(R.id.send_button);
        drawerLayout = findViewById(R.id.drawer_layout);
        btnQuestion = findViewById(R.id.btn_question);
        btnMore = findViewById(R.id.btn_more);

        // Sensor source UI (these IDs must exist in drawer_about.xml)
        sensorSourceRadioGroup = findViewById(R.id.sensor_source_radio_group);
        radioInternal = findViewById(R.id.radio_internal);
        radioExternal = findViewById(R.id.radio_external);
        bluetoothStatusText = findViewById(R.id.bluetooth_status_text);

        // ═══════════════════════════════════════════════════════════════════════════════════
        // ✅ NUEVO: Inicializar WiFi testing icon
        // ═══════════════════════════════════════════════════════════════════════════════════
        wifiTestingIcon = findViewById(R.id.wifi_testing_icon);

        sendButton.setEnabled(false);
        sendButton.setBackgroundColor(Color.parseColor("#808080"));

        // Initialize Bluetooth status UI based on current source
        updateBluetoothStatusText();

        // ✅ NUEVO: Actualizar estado visual del icono WiFi
        updateWifiTestingIcon();
    }

    private void setupListeners() {
        btnQuestion.setOnClickListener(v -> questionDrawer.openDrawer(drawerLayout));
        btnMore.setOnClickListener(v -> drawerLayout.openDrawer(GravityCompat.END));

        sendButton.setOnClickListener(v -> {
            if (lastLocation == null) {
                showTransientPopup("No GPS data available to send!");
                return;
            }
            gpsSender.toggleSending();
        });

        // RadioGroup listener — switch between internal/external sources
        if (sensorSourceRadioGroup != null && sensorSourceManager != null) {
            sensorSourceRadioGroup.setOnCheckedChangeListener((group, checkedId) -> {
                if (checkedId == R.id.radio_internal) {
                    sensorSourceManager.setSource(SensorSourceManager.SourceType.INTERNAL);
                    bluetoothStatusText.setVisibility(TextView.GONE);
                    showTransientPopup("Switched to Internal sensors");
                } else if (checkedId == R.id.radio_external) {
                    if (!sensorSourceManager.isBluetoothAvailable()) {
                        showTransientPopup("Please pair ESP32_Accel_Monitor in Bluetooth settings first");
                        radioInternal.setChecked(true);
                        return;
                    }
                    sensorSourceManager.setSource(SensorSourceManager.SourceType.EXTERNAL);
                    bluetoothStatusText.setVisibility(TextView.VISIBLE);
                    bluetoothStatusText.setText(R.string.bluetooth_status_connecting);
                    bluetoothStatusText.setTextColor(Color.parseColor("#FFA500")); // Orange
                    showTransientPopup("Connecting to ESP32...");
                }
                // update UI status after switching
                updateBluetoothStatusText();
            });

            // Set initial selection according to current source
            if (sensorSourceManager.getCurrentSource() == SensorSourceManager.SourceType.EXTERNAL) {
                radioExternal.setChecked(true);
            } else {
                radioInternal.setChecked(true);
            }
        }

        // ═══════════════════════════════════════════════════════════════════════════════════
        // ✅ NUEVO: WiFi Testing Icon Listener (Triple-tap para activar)
        // ═══════════════════════════════════════════════════════════════════════════════════
        if (wifiTestingIcon != null) {
            wifiTestingIcon.setOnClickListener(v -> {
                long now = System.currentTimeMillis();

                // Reset contador si pasa mucho tiempo entre taps
                if (now - lastWifiIconClickTime > TRIPLE_TAP_TIMEOUT_MS) {
                    wifiIconClickCount = 0;
                }

                lastWifiIconClickTime = now;
                wifiIconClickCount++;

                // Triple-tap detectado
                if (wifiIconClickCount >= 3) {
                    wifiIconClickCount = 0;  // Reset
                    toggleWifiTesting();
                }
            });
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════
    // ✅ NUEVO: Toggle WiFi Testing Mode
    // ═══════════════════════════════════════════════════════════════════════════════════════
    /**
     * Toggle WiFi testing mode (requires triple-tap on WiFi icon)
     * ⚠️ IMPORTANTE: Requiere recrear NetworkManager Y UDPHelper para aplicar cambios
     */
    private void toggleWifiTesting() {
        boolean currentMode = NetworkManager.isTestingMode();
        boolean newMode = !currentMode;

        Log.d(TAG, String.format("Toggling WiFi testing: %s → %s",
                currentMode ? "ON" : "OFF",
                newMode ? "ON" : "OFF"));

        // Cambiar modo globalmente
        NetworkManager.setTestingMode(newMode);

        // ═══════════════════════════════════════════════════════════════════════════════════
        // ✅ FIX: Dispose de UDPHelper ANTES de recrear NetworkManager
        // ═══════════════════════════════════════════════════════════════════════════════════
        if (udpHelper != null) {
            udpHelper.shutdown(gpsManager, resolver, networkManager);
            Log.d(TAG, "UDPHelper disposed before recreating NetworkManager");
        }

        // ═══════════════════════════════════════════════════════════════════════════════════
        // Recrear NetworkManager
        // ═══════════════════════════════════════════════════════════════════════════════════
        if (networkManager != null) {
            networkManager.stopMonitoring();

            // Recrear NetworkManager con nuevo modo
            networkManager = new NetworkManager(this);
            networkManager.addListener(networkListener);

            Log.i(TAG, String.format("🔧 WiFi testing mode %s",
                    newMode ? "ENABLED" : "DISABLED"));
        }

        // ═══════════════════════════════════════════════════════════════════════════════════
        // ✅ FIX: Recrear UDPHelper con nuevo NetworkManager
        // ═══════════════════════════════════════════════════════════════════════════════════
        udpHelper = new UDPHelper(
                gpsManager,
                resolver,
                networkManager,  // ← Nuevo NetworkManager
                identifierManager,
                accelManager
        );
        Log.d(TAG, "UDPHelper recreated with new NetworkManager");

        // ═══════════════════════════════════════════════════════════════════════════════════
        // ✅ FIX: Recrear GPSSender con nuevo UDPHelper
        // ═══════════════════════════════════════════════════════════════════════════════════
        if (gpsSender != null) {
            gpsSender.dispose();
        }

        gpsSender = new GPSSender(
                gpsManager,
                networkManager,  // ← Nuevo NetworkManager
                resolver,
                udpHelper,       // ← Nuevo UDPHelper
                sensorSourceManager,
                this
        );
        Log.d(TAG, "GPSSender recreated with new UDPHelper and NetworkManager");

        // ═══════════════════════════════════════════════════════════════════════════════════
        // Actualizar icono DESPUÉS de un pequeño delay
        // ═══════════════════════════════════════════════════════════════════════════════════
        runOnUiThread(() -> {
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                updateWifiTestingIcon();
                Log.d(TAG, "WiFi icon updated to reflect new mode: " +
                        (newMode ? "TESTING (green)" : "PRODUCTION (white)"));
            }, 100);
        });

        // Toast informativo
        String message = newMode ?
                "🔧 WiFi Testing ENABLED\n(Cellular + WiFi allowed)" :
                "🔒 WiFi Testing DISABLED\n(Cellular only - Production mode)";

        showTransientPopup(message);

        // Vibración corta como feedback
        android.os.Vibrator vibrator = (android.os.Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (vibrator != null && vibrator.hasVibrator()) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                vibrator.vibrate(android.os.VibrationEffect.createOneShot(
                        100, android.os.VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(100);
            }
        }
    }

    /**
     * Actualiza estado visual del icono WiFi según modo actual
     */
    private void updateWifiTestingIcon() {
        if (wifiTestingIcon == null) {
            Log.w(TAG, "wifiTestingIcon is null, cannot update");
            return;
        }

        boolean testingMode = NetworkManager.isTestingMode();

        Log.d(TAG, String.format("Updating WiFi icon: testingMode=%s", testingMode));

        if (testingMode) {
            // ✅ Modo testing: Icono más visible + tinte verde
            wifiTestingIcon.setAlpha(0.8f);
            wifiTestingIcon.setColorFilter(Color.parseColor("#00BF63"));  // Verde brillante
            Log.d(TAG, "WiFi icon set to GREEN (testing mode)");
        } else {
            // ❌ Modo producción: Icono casi invisible + blanco
            wifiTestingIcon.setAlpha(0.3f);
            wifiTestingIcon.setColorFilter(Color.parseColor("#FFFFFF"));  // Blanco
            Log.d(TAG, "WiFi icon set to WHITE (production mode)");
        }
    }

    private void scheduleStatusUpdate() {
        if (pendingStatusUpdate != null) {
            mainHandler.removeCallbacks(pendingStatusUpdate);
        }
        pendingStatusUpdate = this::updateStatus;
        mainHandler.postDelayed(pendingStatusUpdate, UI_UPDATE_DEBOUNCE_MS);
    }

    private void updateStatus() {
        pendingStatusUpdate = null;
        Log.d(TAG, String.format("Status: GPS=%b, Network=%b, Targets=%b",
                gpsAvailable, networkAvailable, targetsAvailable));
    }

    @Override
    public void onNewLocation(Location location) {
        lastLocation = location;
        gpsAvailable = true;
        scheduleStatusUpdate();
    }

    @Override
    public void onGPSAvailabilityChanged(boolean available) {
        gpsAvailable = available;
        scheduleStatusUpdate();
    }

    private final NetworkManager.NetworkListener networkListener = new NetworkManager.NetworkListener() {
        @Override
        public void onNetworkAvailable(@NonNull Network network) {
            networkAvailable = true;
            scheduleStatusUpdate();
            Log.i(TAG, "Network available: " + network);
        }

        @Override
        public void onNetworkUnavailable() {
            networkAvailable = false;
            scheduleStatusUpdate();
            Log.w(TAG, "Network unavailable");
        }
    };

    private final TargetResolverManager.TargetsListener targetsListener = new TargetResolverManager.TargetsListener() {
        @Override
        public void onTargetsUpdated(Map<InetAddress, Set<Integer>> targets) {
            targetsAvailable = (targets != null && !targets.isEmpty());
            scheduleStatusUpdate();
        }

        @Override
        public void onAvailabilityChanged(boolean available) {
            targetsAvailable = available;
            scheduleStatusUpdate();
        }
    };

    public void showTransientPopup(String message) {
        if (currentToast != null) currentToast.cancel();

        TextView textView = new TextView(this);
        textView.setText(message);
        textView.setTextColor(Color.WHITE);
        textView.setBackground(ContextCompat.getDrawable(this, R.drawable.toast_background));
        textView.setPadding(12, 8, 12, 8);
        textView.setTextSize(16);
        textView.setGravity(Gravity.CENTER);
        textView.setMaxWidth((int) (getResources().getDisplayMetrics().widthPixels * 0.8));
        textView.setLineSpacing(0f, 1.2f);

        currentToast = new Toast(this);
        currentToast.setView(textView);
        currentToast.setGravity(Gravity.CENTER, 0, 0);
        currentToast.setDuration(Toast.LENGTH_SHORT);
        currentToast.show();
    }

    @Override
    public void onStateChanged(GPSSender.SenderState state) {
        runOnUiThread(() -> {
            sendButton.clearAnimation();
            switch (state) {
                case UNAVAILABLE:
                    sendButton.setEnabled(false);
                    sendButton.setBackgroundColor(Color.parseColor("#808080"));
                    break;
                case READY:
                    sendButton.setEnabled(true);
                    sendButton.setBackgroundColor(Color.parseColor("#00BF63"));
                    break;
                case SENDING:
                    sendButton.setEnabled(true);
                    sendButton.setBackgroundColor(Color.parseColor("#00BF63"));
                    sendButton.startAnimation(blinkAnimation);
                    break;
            }
        });
    }

    @Override
    public void onSendError(String errorMessage) {
        runOnUiThread(() -> showTransientPopup(errorMessage));
    }

    @SuppressWarnings("unused")
    private void startAccelerometerTest() {
        if (accelManager == null) {
            Log.w(TAG, "Cannot start accel test: accelManager is null");
            return;
        }

        testListener = new AccelerometerManager.AccelStatisticsListener() {
            @Override
            public void onStatisticsComputed(AccelerometerManager.AccelStatistics stats) {
                Log.i("ACCEL_TEST", "═══════════════════════════════════════");
                Log.i("ACCEL_TEST", stats.toString());

                Log.i("ACCEL_TEST", String.format(Locale.US,
                        "Duration: %.2fs | Samples: %d | Flags: 0x%02X",
                        (stats.timestampEnd - stats.timestampStart) / 1000.0,
                        stats.sampleCount,
                        stats.flags
                ));

                if (stats.hasValidationIssues()) {
                    Log.w("ACCEL_TEST", "⚠️ Window has validation issues!");
                }

                if (stats.rmsMagnitude > 0.3f) {
                    Log.w("ACCEL_TEST", String.format(Locale.US,
                            "🚨 HIGH RMS: %.3fg (sustained motion detected)",
                            stats.rmsMagnitude
                    ));
                }

                if (stats.maxMagnitude > 2.0f) {
                    Log.e("ACCEL_TEST", String.format(Locale.US,
                            "💥 IMPACT DETECTED: %.3fg",
                            stats.maxMagnitude
                    ));
                }

                if (stats.peakCount > 10) {
                    Log.w("ACCEL_TEST", String.format(Locale.US,
                            "⚡ HIGH PEAK COUNT: %d (rough conditions)",
                            stats.peakCount
                    ));
                }

                Log.d("ACCEL_TEST", String.format(Locale.US,
                        "RMS per axis: X=%.3f Y=%.3f Z=%.3f",
                        stats.rmsX, stats.rmsY, stats.rmsZ
                ));
                Log.d("ACCEL_TEST", String.format(Locale.US,
                        "MAX per axis: X=%.3f Y=%.3f Z=%.3f",
                        stats.maxX, stats.maxY, stats.maxZ
                ));
            }

            @Override
            public void onAvailabilityChanged(boolean available) {
                Log.i("ACCEL_TEST",
                        "Accelerometer " + (available ? "✅ AVAILABLE" : "❌ UNAVAILABLE")
                );
            }
        };

        accelManager.addListener(testListener);

        Log.i("ACCEL_TEST", "═══════════════════════════════════════");
        Log.i("ACCEL_TEST", "ACCELEROMETER TEST STARTED");
        Log.i("ACCEL_TEST", "Sensor Info:");
        Log.i("ACCEL_TEST", accelManager.getSensorInfo());
        Log.i("ACCEL_TEST", "═══════════════════════════════════════");
        Log.i("ACCEL_TEST", "🚀 Shake device to see statistics!");
        Log.i("ACCEL_TEST", "📊 Statistics appear every 5 seconds");
        Log.i("ACCEL_TEST", "═══════════════════════════════════════");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "AppCore onDestroy - starting graceful shutdown");

        if (pendingStatusUpdate != null) {
            mainHandler.removeCallbacks(pendingStatusUpdate);
            pendingStatusUpdate = null;
        }

        if (sensorSourceManager != null) {
            sensorSourceManager.stop();
            sensorSourceManager.dispose();
            sensorSourceManager = null;
            Log.d(TAG, "SensorSourceManager stopped and disposed");
        }

        if (accelManager != null) {
            if (testListener != null) {
                accelManager.removeListener(testListener);
                testListener = null;
                Log.d(TAG, "Removed accelerometer test listener");
            }

            accelManager.stop();
            accelManager.clearAllListeners();
            accelManager = null;
            Log.d(TAG, "AccelerometerManager stopped");
        }

        if (permissionWatcher != null) {
            permissionWatcher.stopWatching();
            permissionWatcher = null;
        }

        if (questionDrawer != null) {
            questionDrawer.dispose();
            questionDrawer = null;
        }

        if (gpsSender != null) {
            gpsSender.dispose();
            gpsSender = null;
        }

        if (udpHelper != null) {
            udpHelper.shutdown(gpsManager, resolver, networkManager);
            udpHelper = null;
        }

        if (resolver != null) {
            resolver.stop();
            resolver = null;
        }

        if (networkManager != null) {
            networkManager.stopMonitoring();
            networkManager = null;
        }

        if (gpsManager != null) {
            gpsManager.stop();
            gpsManager = null;
        }

        identifierManager = null;

        Log.d(TAG, "AppCore destroyed - all resources cleaned up");
    }

    /**
     * Update Bluetooth status text based on current helper state and pairing.
     */
    private void updateBluetoothStatusText() {
        if (bluetoothStatusText == null || sensorSourceManager == null) return;

        com.electro.dassify_application.helpers.BluetoothHelper bt = sensorSourceManager.getBluetoothHelper();
        if (bt == null) {
            bluetoothStatusText.setVisibility(TextView.GONE);
            return;
        }

        if (bt.isConnected()) {
            bluetoothStatusText.setVisibility(TextView.VISIBLE);
            bluetoothStatusText.setText(R.string.bluetooth_status_connected);
            bluetoothStatusText.setTextColor(Color.parseColor("#00BF63")); // Green
        } else if (sensorSourceManager.isBluetoothAvailable()) {
            bluetoothStatusText.setVisibility(TextView.VISIBLE);
            bluetoothStatusText.setText(R.string.bluetooth_status_connecting);
            bluetoothStatusText.setTextColor(Color.parseColor("#FFA500")); // Orange
        } else {
            bluetoothStatusText.setVisibility(TextView.VISIBLE);
            bluetoothStatusText.setText(R.string.bluetooth_status_disconnected);
            bluetoothStatusText.setTextColor(Color.parseColor("#FF3131")); // Red
        }
    }
}