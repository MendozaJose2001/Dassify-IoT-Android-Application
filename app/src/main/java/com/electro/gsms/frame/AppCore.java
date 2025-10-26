package com.electro.gsms.frame;

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
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import com.electro.gsms.R;
import com.electro.gsms.helpers.GPSSender;
import com.electro.gsms.helpers.MiniMapController;
import com.electro.gsms.helpers.QuestionDrawer;
import com.electro.gsms.helpers.UDPHelper;
import com.electro.gsms.services.AccelerometerManager;
import com.electro.gsms.services.DeviceIdentifierManager;
import com.electro.gsms.services.GPSManager;
import com.electro.gsms.services.NetworkManager;
import com.electro.gsms.services.PermissionWatcher;
import com.electro.gsms.services.TargetResolverManager;

import java.net.InetAddress;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * AppCore - Main activity and central coordinator for GPS Android application.
 *
 * <p><b>Responsibilities:</b></p>
 * <ul>
 *   <li>GPS tracking and location updates</li>
 *   <li>Accelerometer monitoring for road quality analysis</li>
 *   <li>Network connectivity management</li>
 *   <li>UDP data transmission (GPS + Accelerometer)</li>
 *   <li>UI state management and user interactions</li>
 *   <li>Permission monitoring</li>
 * </ul>
 *
 * <p><b>Architecture:</b></p>
 * Implements Model-View-Controller pattern where AppCore acts as the controller,
 * coordinating between service managers (Model) and UI components (View).
 */
public class AppCore extends AppCompatActivity implements
        GPSManager.LocationListenerExternal,
        GPSSender.SenderStateListener {

    private static final String TAG = "AppCore";
    private static final long UI_UPDATE_DEBOUNCE_MS = 300L;

    // ═══════════════════════════════════════════════════════
    // UI Components
    // ═══════════════════════════════════════════════════════
    private Button sendButton;
    private DrawerLayout drawerLayout;
    private ImageButton btnQuestion, btnMore;

    // ═══════════════════════════════════════════════════════
    // Data and State Management
    // ═══════════════════════════════════════════════════════
    private Location lastLocation;
    private Toast currentToast;

    // ═══════════════════════════════════════════════════════
    // Helper Classes
    // ═══════════════════════════════════════════════════════
    private QuestionDrawer questionDrawer;
    private GPSSender gpsSender;
    private UDPHelper udpHelper;

    // ═══════════════════════════════════════════════════════
    // Animation and Permission Management
    // ═══════════════════════════════════════════════════════
    private Animation blinkAnimation;
    private PermissionWatcher permissionWatcher;

    // ═══════════════════════════════════════════════════════
    // Service Managers
    // ═══════════════════════════════════════════════════════
    private GPSManager gpsManager;
    private NetworkManager networkManager;
    private TargetResolverManager resolver;
    private DeviceIdentifierManager identifierManager;
    private AccelerometerManager accelManager;

    // ═══════════════════════════════════════════════════════
    // Thread-safe Availability States
    // ═══════════════════════════════════════════════════════
    private volatile boolean gpsAvailable = false;
    private volatile boolean networkAvailable = false;
    private volatile boolean targetsAvailable = false;

    // ═══════════════════════════════════════════════════════
    // UI Update Debouncing
    // ═══════════════════════════════════════════════════════
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingStatusUpdate;

    // ═══════════════════════════════════════════════════════
    // Optional: Test/Validation Listener
    // ═══════════════════════════════════════════════════════
    private AccelerometerManager.AccelStatisticsListener testListener;

    /**
     * Initializes the activity, sets up UI components, and starts all service managers.
     * This is the main entry point for the application's core functionality.
     *
     * <p><b>Initialization Order (Dependencies):</b></p>
     * <ol>
     *   <li>DeviceIdentifierManager (no dependencies)</li>
     *   <li>GPSManager (no dependencies)</li>
     *   <li>AccelerometerManager (no dependencies)</li>
     *   <li>NetworkManager (no dependencies)</li>
     *   <li>TargetResolverManager (no dependencies)</li>
     *   <li>UDPHelper (depends on: GPS, Accel, Network, Resolver, Identifier)</li>
     *   <li>GPSSender (depends on: GPS, Network, Resolver, UDPHelper, Accel)</li>
     * </ol>
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_appcore);

        initializeViews();
        blinkAnimation = AnimationUtils.loadAnimation(this, R.anim.blink);

        // ═══════════════════════════════════════════════════════
        // Device Identifier Manager
        // ═══════════════════════════════════════════════════════
        identifierManager = new DeviceIdentifierManager(this);
        Log.d(TAG, "DeviceIdentifierManager initialized");

        // ═══════════════════════════════════════════════════════
        // GPS Manager - Location tracking
        // ═══════════════════════════════════════════════════════
        gpsManager = new GPSManager(this);
        gpsManager.addLocationListener(this);
        gpsManager.start();
        Log.d(TAG, "GPSManager started");

        // ═══════════════════════════════════════════════════════
        // Accelerometer Manager - Motion sensing
        // ═══════════════════════════════════════════════════════
        accelManager = new AccelerometerManager(this);
        accelManager.start();
        Log.d(TAG, "AccelerometerManager started");

        // ═══════════════════════════════════════════════════════
        // Network Manager - Connectivity monitoring
        // ═══════════════════════════════════════════════════════
        networkManager = new NetworkManager(this);
        networkManager.addListener(networkListener);
        Log.d(TAG, "NetworkManager initialized");

        // ═══════════════════════════════════════════════════════
        // Target Resolver - UDP destination discovery
        // ═══════════════════════════════════════════════════════
        resolver = new TargetResolverManager(this);
        resolver.addTargetsListener(targetsListener);
        resolver.start(2000);
        Log.d(TAG, "TargetResolverManager started");

        // ═══════════════════════════════════════════════════════
        // UDP Helper - Data transmission coordinator
        // Handles GPS-only and GPS+Accel synchronized sends
        // ═══════════════════════════════════════════════════════
        udpHelper = new UDPHelper(
                gpsManager,
                resolver,
                networkManager,
                identifierManager,
                accelManager
        );
        Log.d(TAG, "UDPHelper initialized");

        // ═══════════════════════════════════════════════════════
        // MiniMap Controller - Visual feedback
        // ═══════════════════════════════════════════════════════
        FrameLayout mapContainer = findViewById(R.id.map_container);
        new MiniMapController(this, mapContainer, gpsManager);
        Log.d(TAG, "MiniMapController initialized");

        // ═══════════════════════════════════════════════════════
        // Question Drawer - Help and instructions
        // ═══════════════════════════════════════════════════════
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

        // ═══════════════════════════════════════════════════════
        // GPS Sender - Dual-mode transmission controller
        // ACTIVE mode: Timer-based GPS-only (accel unavailable)
        // STANDBY mode: Event-driven GPS+Accel (accel available)
        // ═══════════════════════════════════════════════════════
        gpsSender = new GPSSender(
                gpsManager,
                networkManager,
                resolver,
                udpHelper,
                accelManager,
                this
        );
        Log.d(TAG, "GPSSender initialized");

        setupListeners();

        // ═══════════════════════════════════════════════════════
        // Permission Watcher - Runtime permission monitoring
        // ═══════════════════════════════════════════════════════
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

        // ═══════════════════════════════════════════════════════
        // OPTIONAL: Accelerometer validation logging
        // Uncomment to enable detailed accelerometer statistics
        // ═══════════════════════════════════════════════════════
        startAccelerometerTest();

        Log.i(TAG, "AppCore initialization complete");
    }

    /**
     * Initializes UI components and sets up their initial states.
     */
    private void initializeViews() {
        sendButton = findViewById(R.id.send_button);
        drawerLayout = findViewById(R.id.drawer_layout);
        btnQuestion = findViewById(R.id.btn_question);
        btnMore = findViewById(R.id.btn_more);

        sendButton.setEnabled(false);
        sendButton.setBackgroundColor(Color.parseColor("#808080"));
    }

    /**
     * Sets up click listeners for interactive UI elements.
     */
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
    }

    // ═══════════════════════════════════════════════════════
    // UI Update Debouncing Mechanism
    // ═══════════════════════════════════════════════════════

    /**
     * Schedules a debounced status update to prevent UI thrashing.
     * Multiple rapid state changes are coalesced into a single update.
     */
    private void scheduleStatusUpdate() {
        if (pendingStatusUpdate != null) {
            mainHandler.removeCallbacks(pendingStatusUpdate);
        }
        pendingStatusUpdate = this::updateStatus;
        mainHandler.postDelayed(pendingStatusUpdate, UI_UPDATE_DEBOUNCE_MS);
    }

    /**
     * Executes the actual status update after debounce period.
     */
    private void updateStatus() {
        pendingStatusUpdate = null;
        Log.d(TAG, String.format("Status: GPS=%b, Network=%b, Targets=%b",
                gpsAvailable, networkAvailable, targetsAvailable));
    }

    // ═══════════════════════════════════════════════════════
    // GPS Location Listener Implementation
    // ═══════════════════════════════════════════════════════

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

    // ═══════════════════════════════════════════════════════
    // Network Connectivity Listener
    // ═══════════════════════════════════════════════════════

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

    // ═══════════════════════════════════════════════════════
    // Target Resolver Listener
    // ═══════════════════════════════════════════════════════

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

    // ═══════════════════════════════════════════════════════
    // UI Utility Methods
    // ═══════════════════════════════════════════════════════

    /**
     * Displays a temporary popup message with custom styling.
     *
     * @param message Text to display
     */
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

    // ═══════════════════════════════════════════════════════
    // GPS Sender State Listener Implementation
    // ═══════════════════════════════════════════════════════

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

    // ═══════════════════════════════════════════════════════
    // OPTIONAL: Accelerometer Validation Tool
    // ═══════════════════════════════════════════════════════

    /**
     * Starts accelerometer validation logging for testing/debugging.
     *
     * <p><b>Purpose:</b></p>
     * <ul>
     *   <li>Validates accelerometer captures motion correctly</li>
     *   <li>Logs aggregated statistics every 5 seconds</li>
     *   <li>Monitors RMS, peaks, and maximum acceleration values</li>
     *   <li>Detects impacts (>2g) and sustained vibration (RMS >0.3g)</li>
     *   <li>Does NOT interfere with production listeners in UDPHelper/GPSSender</li>
     * </ul>
     *
     * <p><b>How to use:</b></p>
     * <ul>
     *   <li>Enable: Uncomment the call in onCreate()</li>
     *   <li>Disable: Comment out the call in onCreate()</li>
     *   <li>View logs: Filter logcat by "ACCEL_TEST"</li>
     * </ul>
     *
     * <p><b>Example output:</b></p>
     * <pre>
     * ACCEL_TEST: RMS: X=0.125g Y=0.087g Z=0.156g Mag=0.218g
     * ACCEL_TEST: MAX: X=0.845g Y=0.612g Z=1.234g Mag=1.567g
     * ACCEL_TEST: Peaks=12 Samples=250
     * </pre>
     */
    @SuppressWarnings("unused")
    private void startAccelerometerTest() {
        if (accelManager == null) {
            Log.w(TAG, "Cannot start accel test: accelManager is null");
            return;
        }

        testListener = new AccelerometerManager.AccelStatisticsListener() {
            @Override
            public void onStatisticsComputed(AccelerometerManager.AccelStatistics stats) {
                // Main statistics log
                Log.i("ACCEL_TEST", "═══════════════════════════════════════");
                Log.i("ACCEL_TEST", stats.toString());

                // Duration and validation
                Log.i("ACCEL_TEST", String.format(Locale.US,
                        "Duration: %.2fs | Samples: %d | Flags: 0x%02X",
                        (stats.timestampEnd - stats.timestampStart) / 1000.0,
                        stats.sampleCount,
                        stats.flags
                ));

                // Validation warnings
                if (stats.hasValidationIssues()) {
                    Log.w("ACCEL_TEST", "⚠️ Window has validation issues!");
                }

                // Event detection alerts
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

                // Detailed per-axis breakdown
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

        // Log sensor information
        Log.i("ACCEL_TEST", "═══════════════════════════════════════");
        Log.i("ACCEL_TEST", "ACCELEROMETER TEST STARTED");
        Log.i("ACCEL_TEST", "Sensor Info:");
        Log.i("ACCEL_TEST", accelManager.getSensorInfo());
        Log.i("ACCEL_TEST", "═══════════════════════════════════════");
        Log.i("ACCEL_TEST", "🚀 Shake device to see statistics!");
        Log.i("ACCEL_TEST", "📊 Statistics appear every 5 seconds");
        Log.i("ACCEL_TEST", "═══════════════════════════════════════");
    }

    // ═══════════════════════════════════════════════════════
    // Lifecycle Management
    // ═══════════════════════════════════════════════════════

    /**
     * Handles activity destruction with proper resource cleanup.
     *
     * <p><b>Cleanup Order (Reverse Dependencies):</b></p>
     * <ol>
     *   <li>UI updates (pending callbacks)</li>
     *   <li>Test listeners (if any)</li>
     *   <li>AccelerometerManager</li>
     *   <li>PermissionWatcher</li>
     *   <li>QuestionDrawer</li>
     *   <li>GPSSender</li>
     *   <li>UDPHelper</li>
     *   <li>TargetResolverManager</li>
     *   <li>NetworkManager</li>
     *   <li>GPSManager</li>
     *   <li>DeviceIdentifierManager</li>
     * </ol>
     */
    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "AppCore onDestroy - starting graceful shutdown");

        // ═══════════════════════════════════════════════════════
        // Clean up pending UI updates
        // ═══════════════════════════════════════════════════════
        if (pendingStatusUpdate != null) {
            mainHandler.removeCallbacks(pendingStatusUpdate);
            pendingStatusUpdate = null;
        }

        // ═══════════════════════════════════════════════════════
        // Stop accelerometer and remove test listener
        // ═══════════════════════════════════════════════════════
        if (accelManager != null) {
            // Remove test listener if it was added
            if (testListener != null) {
                accelManager.removeListener(testListener);
                testListener = null;
                Log.d(TAG, "Removed accelerometer test listener");
            }

            accelManager.stop();
            accelManager.clearAllListeners();
            accelManager = null;  // NOW it's safe to nullify
            Log.d(TAG, "AccelerometerManager stopped");
        }

        // ═══════════════════════════════════════════════════════
        // Stop permission monitoring
        // ═══════════════════════════════════════════════════════
        if (permissionWatcher != null) {
            permissionWatcher.stopWatching();
            permissionWatcher = null;
        }

        // ═══════════════════════════════════════════════════════
        // Dispose of question drawer resources
        // ═══════════════════════════════════════════════════════
        if (questionDrawer != null) {
            questionDrawer.dispose();
            questionDrawer = null;
        }

        // ═══════════════════════════════════════════════════════
        // Stop GPS sender
        // ═══════════════════════════════════════════════════════
        if (gpsSender != null) {
            gpsSender.dispose();
            gpsSender = null;
        }

        // ═══════════════════════════════════════════════════════
        // Shutdown UDP helper
        // NOTE: Accelerometer listeners are managed by GPSSender.
        // UDPHelper no longer needs accelerometer reference.
        // ═══════════════════════════════════════════════════════
        if (udpHelper != null) {
            udpHelper.shutdown(gpsManager, resolver, networkManager);
            udpHelper = null;
        }

        // ═══════════════════════════════════════════════════════
        // Stop target resolution
        // ═══════════════════════════════════════════════════════
        if (resolver != null) {
            resolver.stop();
            resolver = null;
        }

        // ═══════════════════════════════════════════════════════
        // Stop network monitoring
        // ═══════════════════════════════════════════════════════
        if (networkManager != null) {
            networkManager.stopMonitoring();
            networkManager = null;
        }

        // ═══════════════════════════════════════════════════════
        // Stop GPS tracking
        // ═══════════════════════════════════════════════════════
        if (gpsManager != null) {
            gpsManager.stop();
            gpsManager = null;
        }

        // ═══════════════════════════════════════════════════════
        // Clear remaining references
        // ═══════════════════════════════════════════════════════
        identifierManager = null;

        Log.d(TAG, "AppCore destroyed - all resources cleaned up");
    }
}