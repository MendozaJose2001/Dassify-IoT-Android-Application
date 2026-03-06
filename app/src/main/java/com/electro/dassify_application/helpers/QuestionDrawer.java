package com.electro.dassify_application.helpers;

import android.content.Context;
import android.location.Location;
import android.net.Network;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.widget.SwitchCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.core.view.GravityCompat;

import com.electro.dassify_application.R;
import com.electro.dassify_application.services.AccelerometerManager;
import com.electro.dassify_application.services.GPSManager;
import com.electro.dassify_application.services.NetworkManager;
import com.electro.dassify_application.services.TargetResolverManager;

import java.net.InetAddress;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;


/**
 * QuestionDrawer - System status and testing controls drawer.
 *
 * <p><b>Features:</b></p>
 * <ul>
 *   <li>Real-time status indicators (GPS, Network, Targets, Accelerometer)</li>
 *   <li>Dynamic targets list display</li>
 *   <li>Accelerometer testing toggle (enable/disable sensor)</li>
 *   <li>Operating mode display (ACTIVE/STANDBY)</li>
 * </ul>
 */
public class QuestionDrawer implements GPSManager.LocationListenerExternal {

    // ═══════════════════════════════════════════════════════
    // UI Components
    // ═══════════════════════════════════════════════════════

    private final TextView gpsIndicatorText;
    private final TextView networkIndicatorText;
    private final TextView targetsIndicatorText;
    private final TextView accelIndicatorText;
    private final TextView accelModeText;

    private final Animation blinkAnimation;

    // ═══════════════════════════════════════════════════════
    // State Tracking
    // ═══════════════════════════════════════════════════════

    private boolean lastGpsState = false;
    private boolean lastNetworkState = false;
    private boolean lastTargetsState = false;
    private boolean lastAccelState = false;

    // ═══════════════════════════════════════════════════════
    // Service Managers
    // ═══════════════════════════════════════════════════════

    private final GPSManager gpsManager;
    private final NetworkManager networkManager;
    private final TargetResolverManager resolver;
    private final AccelerometerManager accelManager;

    private final LinearLayout targetsListContainer;

    // ═══════════════════════════════════════════════════════
    // Listeners
    // ═══════════════════════════════════════════════════════

    private final NetworkManager.NetworkListener networkListener;
    private final TargetResolverManager.TargetsListener targetsListener;
    @SuppressWarnings("FieldMayBeFinal")
    private AccelerometerManager.AccelStatisticsListener accelListener;  // Not final - may be null  @Ignore
    private final DrawerLayout drawerLayout;
    private final DrawerLayout.SimpleDrawerListener drawerListener;

    // Cache of the latest targets delivered by TargetResolverManager
    private volatile Map<InetAddress, Set<Integer>> lastKnownTargets = Collections.emptyMap();

    // ═══════════════════════════════════════════════════════
    // Callback for accelerometer toggle
    // ═══════════════════════════════════════════════════════

    /**
     * Callback interface for accelerometer state changes.
     * Allows external components (like GPSSender) to react to mode changes.
     */
    public interface AccelerometerToggleListener {
        void onAccelerometerToggled(boolean enabled);
    }

    private AccelerometerToggleListener accelToggleListener;

    /**
     * Constructs QuestionDrawer with system status monitoring and testing controls.
     *
     * @param drawerView Root view of the drawer layout
     * @param context Android context
     * @param drawerLayout Drawer layout container
     * @param gpsManager GPS manager
     * @param networkManager Network manager
     * @param resolver Target resolver manager
     * @param accelManager Accelerometer manager (can be null)
     */
    public QuestionDrawer(View drawerView,
                          Context context,
                          DrawerLayout drawerLayout,
                          GPSManager gpsManager,
                          NetworkManager networkManager,
                          TargetResolverManager resolver,
                          AccelerometerManager accelManager) {

        this.drawerLayout = drawerLayout;
        this.gpsManager = gpsManager;
        this.networkManager = networkManager;
        this.resolver = resolver;
        this.accelManager = accelManager;

        // ═══════════════════════════════════════════════════════
        // Initialize UI components
        // ═══════════════════════════════════════════════════════

        gpsIndicatorText = drawerView.findViewById(R.id.gps_indicator_text);
        networkIndicatorText = drawerView.findViewById(R.id.network_indicator_text);
        targetsIndicatorText = drawerView.findViewById(R.id.targets_indicator_text);
        accelIndicatorText = drawerView.findViewById(R.id.accel_indicator_text);
        accelModeText = drawerView.findViewById(R.id.accel_mode_text);
        SwitchCompat accelSwitch = drawerView.findViewById(R.id.switch_accelerometer);

        blinkAnimation = AnimationUtils.loadAnimation(context, R.anim.blink);
        targetsListContainer = drawerView.findViewById(R.id.targets_list_container);

        // ═══════════════════════════════════════════════════════
        // Setup GPS listener
        // ═══════════════════════════════════════════════════════

        this.gpsManager.addLocationListener(this);

        // ═══════════════════════════════════════════════════════
        // Setup Network listener
        // ═══════════════════════════════════════════════════════

        networkListener = new NetworkManager.NetworkListener() {
            @Override
            public void onNetworkAvailable(Network network) {
                targetsListContainer.post(() -> updateNetworkIndicator(true));
            }

            @Override
            public void onNetworkUnavailable() {
                targetsListContainer.post(() -> updateNetworkIndicator(false));
            }
        };
        this.networkManager.addListener(networkListener);

        // ═══════════════════════════════════════════════════════
        // Setup Targets listener
        // ═══════════════════════════════════════════════════════

        targetsListener = new TargetResolverManager.TargetsListener() {
            @Override
            public void onTargetsUpdated(Map<InetAddress, Set<Integer>> targets) {
                lastKnownTargets = new HashMap<>(targets);
                targetsListContainer.post(() -> updateTargetsList(targets));
                updateTargetsIndicator(!targets.isEmpty());
            }

            @Override
            public void onAvailabilityChanged(boolean available) {
                targetsIndicatorText.post(() -> updateTargetsIndicator(available));
            }
        };
        this.resolver.addTargetsListener(targetsListener);

        // ═══════════════════════════════════════════════════════
        // Setup Accelerometer listener
        // ═══════════════════════════════════════════════════════

        if (accelManager != null) {
            accelListener = new AccelerometerManager.AccelStatisticsListener() {
                @Override
                public void onStatisticsComputed(AccelerometerManager.AccelStatistics statistics) {
                    // Update mode display when window completes
                    accelModeText.post(() ->
                            accelModeText.setText(R.string.mode_standby)
                    );
                }

                @Override
                public void onAvailabilityChanged(boolean available) {
                    accelIndicatorText.post(() -> {
                        updateAccelIndicator(available);
                        updateModeDisplay(available);
                    });
                }
            };
            accelManager.addListener(accelListener);

            // Setup switch listener
            accelSwitch.setOnCheckedChangeListener((buttonView, isChecked) ->
                    toggleAccelerometer(isChecked)
            );

            // Set initial state
            boolean initiallyAvailable = accelManager.isSensorAvailable() &&
                    accelManager.isRunning();
            updateAccelIndicator(initiallyAvailable);
            updateModeDisplay(initiallyAvailable);
            accelSwitch.setChecked(initiallyAvailable);

        } else {
            // No accelerometer manager - disable controls
            accelListener = null;
            if (accelIndicatorText != null) accelIndicatorText.setVisibility(View.GONE);
            if (accelModeText != null) accelModeText.setVisibility(View.GONE);
            if (accelSwitch != null) accelSwitch.setVisibility(View.GONE);
        }

        // ═══════════════════════════════════════════════════════
        // Setup Drawer listener
        // ═══════════════════════════════════════════════════════

        drawerListener = new DrawerLayout.SimpleDrawerListener() {
            @Override
            public void onDrawerOpened(View dv) {
                Map<InetAddress, Set<Integer>> snapshot;
                synchronized (QuestionDrawer.this) {
                    snapshot = new HashMap<>(lastKnownTargets);
                }
                targetsListContainer.post(() -> updateTargetsList(snapshot));
            }
        };
        this.drawerLayout.addDrawerListener(drawerListener);
    }

    // ═══════════════════════════════════════════════════════
    // GPS Listener Implementation
    // ═══════════════════════════════════════════════════════

    @Override
    public void onNewLocation(Location location) { /* Not used here */ }

    @Override
    public void onGPSAvailabilityChanged(boolean available) {
        gpsIndicatorText.post(() -> updateGpsIndicator(available));
    }

    // ═══════════════════════════════════════════════════════
    // Accelerometer Toggle Control
    // ═══════════════════════════════════════════════════════

    /**
     * Sets a listener to be notified when accelerometer is toggled.
     * This is intended for future extensibility (e.g., notifying GPSSender).
     *
     * @param listener Callback for toggle events
     */
    @SuppressWarnings("unused")
    public void setAccelerometerToggleListener(AccelerometerToggleListener listener) {
        this.accelToggleListener = listener;
    }

    /**
     * Toggles accelerometer on/off for testing.
     *
     * @param enable true to start accelerometer, false to stop
     */
    private void toggleAccelerometer(boolean enable) {
        if (accelManager == null) return;

        if (enable) {
            accelManager.start();
            updateModeDisplay(true);
        } else {
            accelManager.stop();
            updateModeDisplay(false);
        }

        // Notify external listeners (e.g., GPSSender for mode switching)
        if (accelToggleListener != null) {
            accelToggleListener.onAccelerometerToggled(enable);
        }
    }

    /**
     * Updates the mode display text based on accelerometer state.
     *
     * @param accelAvailable true if accelerometer is running
     */
    private void updateModeDisplay(boolean accelAvailable) {
        if (accelModeText == null) return;

        if (accelAvailable) {
            accelModeText.setText(R.string.mode_standby);
            accelModeText.setTextColor(0xFF00BF63);  // Green
        } else {
            accelModeText.setText(R.string.mode_active);
            accelModeText.setTextColor(0xFFFFAA00);  // Orange
        }
    }

    // ═══════════════════════════════════════════════════════
    // Status Indicator Updates
    // ═══════════════════════════════════════════════════════

    private void updateGpsIndicator(boolean state) {
        if (state != lastGpsState) {
            gpsIndicatorText.clearAnimation();
            gpsIndicatorText.setCompoundDrawablesWithIntrinsicBounds(R.drawable.circle_yellow, 0, 0, 0);
            gpsIndicatorText.startAnimation(blinkAnimation);
        }

        gpsIndicatorText.postDelayed(() -> {
            gpsIndicatorText.clearAnimation();
            gpsIndicatorText.setCompoundDrawablesWithIntrinsicBounds(
                    state ? R.drawable.circle_green : R.drawable.circle_red, 0, 0, 0);
        }, blinkAnimation.getDuration() * (blinkAnimation.getRepeatCount() + 1));

        lastGpsState = state;
    }

    private void updateNetworkIndicator(boolean state) {
        if (state != lastNetworkState) {
            networkIndicatorText.clearAnimation();
            networkIndicatorText.setCompoundDrawablesWithIntrinsicBounds(R.drawable.circle_yellow, 0, 0, 0);
            networkIndicatorText.startAnimation(blinkAnimation);
        }

        networkIndicatorText.postDelayed(() -> {
            networkIndicatorText.clearAnimation();
            networkIndicatorText.setCompoundDrawablesWithIntrinsicBounds(
                    state ? R.drawable.circle_green : R.drawable.circle_red, 0, 0, 0);
        }, blinkAnimation.getDuration() * (blinkAnimation.getRepeatCount() + 1));

        lastNetworkState = state;
    }

    private void updateTargetsIndicator(boolean state) {
        if (state != lastTargetsState) {
            targetsIndicatorText.clearAnimation();
            targetsIndicatorText.setCompoundDrawablesWithIntrinsicBounds(R.drawable.circle_yellow, 0, 0, 0);
            targetsIndicatorText.startAnimation(blinkAnimation);
        }

        targetsIndicatorText.postDelayed(() -> {
            targetsIndicatorText.clearAnimation();
            targetsIndicatorText.setCompoundDrawablesWithIntrinsicBounds(
                    state ? R.drawable.circle_green : R.drawable.circle_red, 0, 0, 0);
        }, blinkAnimation.getDuration() * (blinkAnimation.getRepeatCount() + 1));

        lastTargetsState = state;
    }

    /**
     * Updates accelerometer status indicator.
     *
     * @param state true if accelerometer is available and running
     */
    private void updateAccelIndicator(boolean state) {
        if (accelIndicatorText == null) return;

        if (state != lastAccelState) {
            accelIndicatorText.clearAnimation();
            accelIndicatorText.setCompoundDrawablesWithIntrinsicBounds(R.drawable.circle_yellow, 0, 0, 0);
            accelIndicatorText.startAnimation(blinkAnimation);
        }

        accelIndicatorText.postDelayed(() -> {
            accelIndicatorText.clearAnimation();
            accelIndicatorText.setCompoundDrawablesWithIntrinsicBounds(
                    state ? R.drawable.circle_green : R.drawable.circle_red, 0, 0, 0);
        }, blinkAnimation.getDuration() * (blinkAnimation.getRepeatCount() + 1));

        lastAccelState = state;
    }

    // ═══════════════════════════════════════════════════════
    // Targets List UI
    // ═══════════════════════════════════════════════════════

    private void updateTargetsList(Map<InetAddress, Set<Integer>> targets) {
        Context context = targetsListContainer.getContext();
        targetsListContainer.removeAllViews();

        for (Map.Entry<InetAddress, Set<Integer>> entry : targets.entrySet()) {
            InetAddress address = entry.getKey();
            Set<Integer> ports = entry.getValue();

            // Main IP line
            TextView ipTextView = new TextView(context);
            ipTextView.setText(address.getHostAddress());
            ipTextView.setTextColor(0xFFFFFFFF);
            ipTextView.setTextSize(16);
            ipTextView.setPadding(8, 8, 8, 4);
            targetsListContainer.addView(ipTextView);

            // Nested ports list
            if (ports != null) {
                for (Integer port : ports) {
                    TextView portTextView = new TextView(context);
                    portTextView.setText(context.getString(R.string.port_label, port));
                    portTextView.setTextColor(0xFFAAAAAA);
                    portTextView.setTextSize(14);
                    portTextView.setPadding(32, 2, 8, 2);
                    targetsListContainer.addView(portTextView);
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════
    // Public API
    // ═══════════════════════════════════════════════════════

    public void openDrawer(DrawerLayout drawerLayout) {
        drawerLayout.openDrawer(GravityCompat.START);
    }

    /**
     * Cleanup all listeners and resources.
     */
    public void dispose() {
        try { gpsManager.removeLocationListener(this); } catch (Exception ignored) {}
        try { networkManager.removeListener(networkListener); } catch (Exception ignored) {}
        try { resolver.removeTargetsListener(targetsListener); } catch (Exception ignored) {}
        try { drawerLayout.removeDrawerListener(drawerListener); } catch (Exception ignored) {}

        // Remove accelerometer listener
        if (accelManager != null && accelListener != null) {
            try { accelManager.removeListener(accelListener); } catch (Exception ignored) {}
        }

        gpsIndicatorText.clearAnimation();
        networkIndicatorText.clearAnimation();
        targetsIndicatorText.clearAnimation();
        if (accelIndicatorText != null) accelIndicatorText.clearAnimation();
    }
}