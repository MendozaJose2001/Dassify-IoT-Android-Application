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
import com.electro.gsms.services.GPSManager;
import com.electro.gsms.services.NetworkManager;
import com.electro.gsms.services.PermissionWatcher;
import com.electro.gsms.services.TargetResolverManager;
import com.electro.gsms.services.DeviceIdentifierManager; // Provides unique device identification

import java.net.InetAddress;
import java.util.Map;
import java.util.Set;

/**
 * AppCore - Main activity and central coordinator for GSMS Android application.
 * Manages GPS tracking, network connectivity, UDP data transmission, and UI components.
 * Implements location and sender state listeners for real-time updates.
 */
public class AppCore extends AppCompatActivity implements
        GPSManager.LocationListenerExternal,
        GPSSender.SenderStateListener {

    private static final String TAG = "AppCore";
    private static final long UI_UPDATE_DEBOUNCE_MS = 300L; // Debounce interval for UI updates

    // UI Components
    private Button sendButton;
    private DrawerLayout drawerLayout;
    private ImageButton btnQuestion, btnMore;

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
    private DeviceIdentifierManager identifierManager; // Manages device identification

    // --- Thread-safe availability states ---
    private volatile boolean gpsAvailable = false;
    private volatile boolean networkAvailable = false;
    private volatile boolean targetsAvailable = false;

    // --- UI update debouncing mechanism ---
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingStatusUpdate;

    /**
     * Initializes the activity, sets up UI components, and starts all service managers.
     * This is the main entry point for the application's core functionality.
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_appcore);

        initializeViews();
        blinkAnimation = AnimationUtils.loadAnimation(this, R.anim.blink);

        // --- Device Identifier Manager (must be initialized before UDPHelper) ---
        identifierManager = new DeviceIdentifierManager(this);

        // --- GPS Manager - Handles location tracking and updates ---
        gpsManager = new GPSManager(this);
        gpsManager.addLocationListener(this);
        gpsManager.start();

        // --- Network Manager - Monitors network connectivity changes ---
        networkManager = new NetworkManager(this);
        networkManager.addListener(networkListener);

        // --- Target Resolver - Discovers and manages communication targets ---
        resolver = new TargetResolverManager(this);
        resolver.addTargetsListener(targetsListener);
        resolver.start(2000); // Start with 2-second update interval

        // --- UDP Helper + MiniMap - Handles data transmission and map display ---
        udpHelper = new UDPHelper(
                gpsManager,
                resolver,
                networkManager,
                identifierManager  // Inject device identifier for message tagging
        );
        FrameLayout mapContainer = findViewById(R.id.map_container);
        new MiniMapController(this, mapContainer, gpsManager);

        // --- Question Drawer - Provides instructional content and help ---
        questionDrawer = new QuestionDrawer(
                findViewById(R.id.drawer_instructions),
                this,
                drawerLayout,
                gpsManager,
                networkManager,
                resolver
        );

        // --- GPS Sender - Manages transmission of GPS data ---
        gpsSender = new GPSSender(
                gpsManager,
                networkManager,
                resolver,
                udpHelper,
                this
        );

        setupListeners();

        // --- PermissionWatcher - Monitors runtime permission changes ---
        permissionWatcher = new PermissionWatcher(
                this,
                this,
                revokedPermissions -> runOnUiThread(() -> {
                    showTransientPopup("Permissions revoked: " + revokedPermissions);
                    // Redirect to MainActivity to re-request necessary permissions
                    Intent intent = new Intent(this, MainActivity.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                    finish();
                })
        );
        permissionWatcher.startWatching();
    }

    /**
     * Initializes UI components and sets up their initial states.
     * Configures the send button to be disabled by default.
     */
    private void initializeViews() {
        sendButton = findViewById(R.id.send_button);
        drawerLayout = findViewById(R.id.drawer_layout);
        btnQuestion = findViewById(R.id.btn_question);
        btnMore = findViewById(R.id.btn_more);

        // Initialize send button as disabled until system is ready
        sendButton.setEnabled(false);
        sendButton.setBackgroundColor(Color.parseColor("#808080")); // Gray color indicates disabled state
    }

    /**
     * Sets up click listeners for interactive UI elements.
     * Handles drawer navigation and GPS transmission toggling.
     */
    private void setupListeners() {
        // Open question drawer when question button is clicked
        btnQuestion.setOnClickListener(v -> questionDrawer.openDrawer(drawerLayout));

        // Open side drawer when more button is clicked
        btnMore.setOnClickListener(v -> drawerLayout.openDrawer(GravityCompat.END));

        // Handle send button click to toggle GPS data transmission
        sendButton.setOnClickListener(v -> {
            if (lastLocation == null) {
                showTransientPopup("No GPS data available to send!");
                return;
            }
            gpsSender.toggleSending();
        });
    }

    // --- UI Update Debouncing Mechanism ---

    /**
     * Schedules a status update with debouncing to prevent rapid UI refreshes.
     * Cancels any pending updates before scheduling a new one.
     */
    private void scheduleStatusUpdate() {
        if (pendingStatusUpdate != null) {
            mainHandler.removeCallbacks(pendingStatusUpdate);
        }
        pendingStatusUpdate = this::updateStatus;
        mainHandler.postDelayed(pendingStatusUpdate, UI_UPDATE_DEBOUNCE_MS);
    }

    /**
     * Updates the system status and logs current availability states.
     * Called after debounce delay to consolidate multiple rapid state changes.
     */
    private void updateStatus() {
        pendingStatusUpdate = null;
        Log.d(TAG, String.format("Status: GPS=%b, Network=%b, Targets=%b",
                gpsAvailable, networkAvailable, targetsAvailable));
    }

    // --- GPS Location Listener Implementation ---

    /**
     * Called when a new location is received from GPS.
     * Updates the last known location and marks GPS as available.
     *
     * @param location The new location data received
     */
    @Override
    public void onNewLocation(Location location) {
        lastLocation = location;
        gpsAvailable = true;
        scheduleStatusUpdate();
    }

    /**
     * Called when GPS availability changes (enabled/disabled).
     * Updates the GPS availability state and triggers UI refresh.
     *
     * @param available True if GPS is available, false otherwise
     */
    @Override
    public void onGPSAvailabilityChanged(boolean available) {
        gpsAvailable = available;
        scheduleStatusUpdate();
    }

    // --- Network Connectivity Listener ---

    private final NetworkManager.NetworkListener networkListener = new NetworkManager.NetworkListener() {
        /**
         * Called when network connectivity becomes available.
         * Updates network state and logs the available network.
         *
         * @param network The available network interface
         */
        @Override
        public void onNetworkAvailable(@NonNull Network network) {
            networkAvailable = true;
            scheduleStatusUpdate();
            Log.i(TAG, "Network available: " + network);
        }

        /**
         * Called when network connectivity is lost.
         * Updates network state and logs the unavailability.
         */
        @Override
        public void onNetworkUnavailable() {
            networkAvailable = false;
            scheduleStatusUpdate();
            Log.w(TAG, "Network unavailable");
        }
    };

    // --- Target Resolver Listener ---

    private final TargetResolverManager.TargetsListener targetsListener = new TargetResolverManager.TargetsListener() {
        /**
         * Called when the list of available targets is updated.
         * Determines target availability based on the updated target map.
         *
         * @param targets Map of InetAddress to port sets for available targets
         */
        @Override
        public void onTargetsUpdated(Map<InetAddress, Set<Integer>> targets) {
            targetsAvailable = (targets != null && !targets.isEmpty());
            scheduleStatusUpdate();
        }

        /**
         * Called when overall target availability changes.
         * Updates the targets availability state.
         *
         * @param available True if targets are available, false otherwise
         */
        @Override
        public void onAvailabilityChanged(boolean available) {
            targetsAvailable = available;
            scheduleStatusUpdate();
        }
    };

    // --- UI Utility Methods ---

    /**
     * Displays a transient popup message to the user.
     * Replaces any existing toast to prevent message stacking.
     *
     * @param message The message to display in the popup
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

    // --- GPS Sender State Listener Implementation ---

    /**
     * Called when the GPS sender state changes.
     * Updates the send button appearance and functionality accordingly.
     *
     * @param state The new state of the GPS sender
     */
    @Override
    public void onStateChanged(GPSSender.SenderState state) {
        runOnUiThread(() -> {
            sendButton.clearAnimation();
            switch (state) {
                case UNAVAILABLE:
                    sendButton.setEnabled(false);
                    sendButton.setBackgroundColor(Color.parseColor("#808080")); // Gray - disabled
                    break;
                case READY:
                    sendButton.setEnabled(true);
                    sendButton.setBackgroundColor(Color.parseColor("#00BF63")); // Green - ready
                    break;
                case SENDING:
                    sendButton.setEnabled(true);
                    sendButton.setBackgroundColor(Color.parseColor("#00BF63")); // Green - active
                    sendButton.startAnimation(blinkAnimation); // Blink animation during transmission
                    break;
            }
        });
    }

    /**
     * Called when an error occurs during GPS data transmission.
     * Displays the error message to the user via transient popup.
     *
     * @param errorMessage Description of the error that occurred
     */
    @Override
    public void onSendError(String errorMessage) {
        runOnUiThread(() -> showTransientPopup(errorMessage));
    }

    // --- Lifecycle Management ---

    /**
     * Performs orderly shutdown of all managers and services.
     * Ensures proper cleanup of resources to prevent memory leaks.
     */
    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "AppCore onDestroy - starting graceful shutdown");

        // Clean up pending UI updates
        if (pendingStatusUpdate != null) {
            mainHandler.removeCallbacks(pendingStatusUpdate);
            pendingStatusUpdate = null;
        }

        // Stop permission monitoring
        if (permissionWatcher != null) {
            permissionWatcher.stopWatching();
            permissionWatcher = null;
        }

        // Dispose of question drawer resources
        if (questionDrawer != null) {
            questionDrawer.dispose();
            questionDrawer = null;
        }

        // Stop GPS sender
        if (gpsSender != null) {
            gpsSender.dispose();
            gpsSender = null;
        }

        // Shutdown UDP helper and release dependencies
        if (udpHelper != null) {
            udpHelper.shutdown(gpsManager, resolver, networkManager);
            udpHelper = null;
        }

        // Stop target resolution
        if (resolver != null) {
            resolver.stop();
            resolver = null;
        }

        // Stop network monitoring
        if (networkManager != null) {
            networkManager.stopMonitoring();
            networkManager = null;
        }

        // Stop GPS tracking
        if (gpsManager != null) {
            gpsManager.stop();
            gpsManager = null;
        }

        // DeviceIdentifierManager doesn't require active cleanup
        if (identifierManager != null) {
            identifierManager = null;
        }

        Log.d(TAG, "AppCore destroyed - all resources cleaned up");
    }
}