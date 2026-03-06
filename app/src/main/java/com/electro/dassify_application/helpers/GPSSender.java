package com.electro.dassify_application.helpers;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.electro.dassify_application.services.AccelerometerManager;
import com.electro.dassify_application.services.GPSManager;
import com.electro.dassify_application.services.NetworkManager;
import com.electro.dassify_application.services.TargetResolverManager;

import java.net.InetAddress;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * GPSSender - Manages GPS data transmission with dual operating modes.
 *
 * <p><b>Operating Modes:</b></p>
 * <ul>
 *   <li>ACTIVE: Timer-based GPS-only sends (accelerometer unavailable)</li>
 *   <li>STANDBY: Event-driven GPS+Accel synchronized sends (accelerometer available)</li>
 * </ul>
 *
 * <p><b>Mode switching:</b></p>
 * Automatically switches between modes based on accelerometer availability.
 * In STANDBY, SensorSourceManager triggers sends via UDPHelper.
 * In ACTIVE, GPSSender sends GPS-only data every 5 seconds.
 *
 * <p><b>Dual Source Support:</b></p>
 * Works with both INTERNAL (phone sensors) and EXTERNAL (ESP32 + MPU6050) sources
 * through unified SensorSourceManager interface.
 */
public class GPSSender {

    /**
     * Represents the current state of the GPS sender
     */
    public enum SenderState {
        UNAVAILABLE,    // Missing GPS, network, or targets
        READY,          // All dependencies available but not sending
        SENDING         // Actively sending GPS data
    }

    // ═══════════════════════════════════════════════════════
    // Operating Mode Definition
    // ═══════════════════════════════════════════════════════

    /**
     * Operating modes for GPSSender
     */
    private enum OperatingMode {
        /**
         * ACTIVE: Timer-based sending (accelerometer not controlling sends).
         * GPSSender actively sends GPS-only data every 5 seconds.
         */
        ACTIVE,

        /**
         * STANDBY: Event-driven sending (accelerometer controlling sends).
         * GPSSender stands by while SensorSourceManager triggers sends.
         */
        STANDBY
    }

    private static final String TAG = "GPSSender";

    /**
     * Interface for receiving sender state updates and errors
     */
    public interface SenderStateListener {
        void onStateChanged(SenderState state);
        void onSendError(String message);
    }

    // ═══════════════════════════════════════════════════════
    // Dependencies
    // ═══════════════════════════════════════════════════════

    private final GPSManager gpsManager;
    private final NetworkManager networkManager;
    private final TargetResolverManager resolver;
    private final UDPHelper udpHelper;
    private final SensorSourceManager sensorSourceManager;  // Dual source manager

    private final SenderStateListener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ═══════════════════════════════════════════════════════
    // Configuration
    // ═══════════════════════════════════════════════════════

    private static final long SEND_INTERVAL_MS = 5000;
    private static final long DEBOUNCE_STATE_MS = 500L;

    // ═══════════════════════════════════════════════════════
    // State Management
    // ═══════════════════════════════════════════════════════

    private final AtomicBoolean sending = new AtomicBoolean(false);
    private volatile boolean gpsAvailable = false;
    private volatile boolean networkAvailable = false;
    private volatile boolean targetsAvailable = false;
    private volatile boolean accelAvailable = false;

    private volatile OperatingMode currentMode;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> sendingTask = null;

    private final Object stateLock = new Object();
    private volatile boolean disposed = false;
    private long lastStateNotificationTime = 0;

    // ═══════════════════════════════════════════════════════
    // Constructor
    // ═══════════════════════════════════════════════════════

    /**
     * Constructs GPSSender with unified sensor source manager.
     *
     * @param gpsManager GPS location provider
     * @param networkManager Network connectivity manager
     * @param resolver Target resolver for UDP destinations
     * @param udpHelper UDP transmission helper
     * @param sensorSourceManager Unified sensor manager (INTERNAL/EXTERNAL)
     * @param listener State change listener
     */
    public GPSSender(GPSManager gpsManager,
                     NetworkManager networkManager,
                     TargetResolverManager resolver,
                     UDPHelper udpHelper,
                     SensorSourceManager sensorSourceManager,
                     SenderStateListener listener) {

        this.gpsManager = gpsManager;
        this.networkManager = networkManager;
        this.resolver = resolver;
        this.udpHelper = udpHelper;
        this.sensorSourceManager = sensorSourceManager;
        this.listener = listener;

        gpsManager.addLocationListener(gpsListener);
        networkManager.addListener(networkListener);
        resolver.addTargetsListener(targetsListener);

        if (sensorSourceManager != null) {
            // ═══════════════════════════════════════════════════════
            // Register unified listener for dual source (INTERNAL/EXTERNAL)
            // Handles both mode switching and send authorization
            // ═══════════════════════════════════════════════════════

            sensorSourceManager.addListener(sourceListener);

            boolean sensorsAvailable = sensorSourceManager.isSensorAvailable();

            if (sensorsAvailable) {
                currentMode = OperatingMode.STANDBY;
                accelAvailable = true;
                Log.d(TAG, "🟢 GPSSender initialized in STANDBY mode (sensors available)");
            } else {
                currentMode = OperatingMode.ACTIVE;
                Log.d(TAG, "🟡 GPSSender initialized in ACTIVE mode (sensors not available)");
            }
        } else {
            currentMode = OperatingMode.ACTIVE;
            Log.d(TAG, "🟡 GPSSender initialized in ACTIVE mode (no sensor manager)");
        }

        captureInitialState();
        notifyState();
    }

    // ═══════════════════════════════════════════════════════
    // Public API
    // ═══════════════════════════════════════════════════════

    public void toggleSending() {
        if (disposed) {
            Log.w(TAG, "Cannot toggle: GPSSender is disposed");
            return;
        }

        if (sending.get()) {
            stopSending();
        } else if (gpsAvailable && networkAvailable && targetsAvailable) {
            startSending();
        } else {
            final String errorMsg = "Cannot start sending: check GPS, network, and targets availability";
            mainHandler.post(() -> {
                if (listener != null) {
                    listener.onSendError(errorMsg);
                }
            });
        }
    }

    private void startSending() {
        if (sendingTask != null && !sendingTask.isCancelled()) {
            sendingTask.cancel(false);
        }

        sending.set(true);
        notifyState();

        if (currentMode == OperatingMode.ACTIVE) {
            sendingTask = scheduler.scheduleWithFixedDelay(
                    this::sendOnce,
                    0,
                    SEND_INTERVAL_MS,
                    TimeUnit.MILLISECONDS
            );
            Log.d(TAG, "🟢 Started in ACTIVE mode (timer-based GPS-only, interval=" + SEND_INTERVAL_MS + "ms)");
        } else {
            Log.d(TAG, "🟢 Started in STANDBY mode (accel-driven GPS+Accel synchronized)");
        }
    }

    private void stopSending() {
        sending.set(false);

        if (sendingTask != null && !sendingTask.isCancelled()) {
            sendingTask.cancel(false);
            sendingTask = null;
        }

        Log.d(TAG, "🔴 Stopped sending");
        notifyState();
    }

    private void sendOnce() {
        if (currentMode != OperatingMode.ACTIVE) {
            return;
        }

        if (!sending.get() || !gpsAvailable || !networkAvailable || !targetsAvailable) {
            if (sending.get()) {
                stopSending();
            }
            if (!gpsAvailable || !networkAvailable || !targetsAvailable) {
                final String errorMsg = "Sending stopped: a required service is unavailable";
                mainHandler.post(() -> {
                    if (listener != null) {
                        listener.onSendError(errorMsg);
                    }
                });
            }
            return;
        }

        boolean udpSent = udpHelper.sendGPSData();

        if (!udpSent) {
            final String errorMsg = "UDP: Failed to send GPS data";
            mainHandler.post(() -> {
                if (listener != null) {
                    listener.onSendError(errorMsg);
                }
            });
        }
    }

    private void notifyState() {
        long now = System.currentTimeMillis();
        if (now - lastStateNotificationTime < DEBOUNCE_STATE_MS) {
            return;
        }
        lastStateNotificationTime = now;

        SenderState state;
        if (!gpsAvailable || !networkAvailable || !targetsAvailable) {
            state = SenderState.UNAVAILABLE;
        } else if (sending.get()) {
            state = SenderState.SENDING;
        } else {
            state = SenderState.READY;
        }

        final SenderState finalState = state;
        mainHandler.post(() -> {
            if (listener != null) {
                listener.onStateChanged(finalState);
            }
        });
    }

    private void captureInitialState() {
        try {
            synchronized (stateLock) {
                gpsAvailable = gpsManager.isGPSAvailable();
                networkAvailable = networkManager.isNetworkAvailable();

                Log.d(TAG, String.format("Initial state: GPS=%b, Network=%b, Targets=%b, Accel=%b, Mode=%s",
                        gpsAvailable, networkAvailable, targetsAvailable, accelAvailable, currentMode));
            }
        } catch (Exception e) {
            Log.e(TAG, "Error capturing initial state", e);
        }
    }

    // ═══════════════════════════════════════════════════════
    // Service Listeners
    // ═══════════════════════════════════════════════════════

    private final GPSManager.LocationListenerExternal gpsListener = new GPSManager.LocationListenerExternal() {
        @Override
        public void onNewLocation(android.location.Location location) {}

        @Override
        public void onGPSAvailabilityChanged(boolean available) {
            try {
                synchronized (stateLock) {
                    gpsAvailable = available;
                    if (!gpsAvailable && sending.get()) {
                        stopSending();
                    }
                }
                notifyState();
            } catch (Throwable t) {
                Log.e(TAG, "Error in GPS availability callback", t);
            }
        }
    };

    private final NetworkManager.NetworkListener networkListener = new NetworkManager.NetworkListener() {
        @Override
        public void onNetworkAvailable(android.net.Network network) {
            try {
                synchronized (stateLock) {
                    networkAvailable = true;
                }
                notifyState();
            } catch (Throwable t) {
                Log.e(TAG, "Error in network available callback", t);
            }
        }

        @Override
        public void onNetworkUnavailable() {
            try {
                synchronized (stateLock) {
                    networkAvailable = false;
                    if (sending.get()) {
                        stopSending();
                    }
                }
                notifyState();
            } catch (Throwable t) {
                Log.e(TAG, "Error in network unavailable callback", t);
            }
        }
    };

    private final TargetResolverManager.TargetsListener targetsListener = new TargetResolverManager.TargetsListener() {
        @Override
        public void onTargetsUpdated(Map<InetAddress, Set<Integer>> targets) {
            try {
                synchronized (stateLock) {
                    targetsAvailable = targets != null && !targets.isEmpty();
                    if (!targetsAvailable && sending.get()) {
                        stopSending();
                    }
                }
                notifyState();
            } catch (Throwable t) {
                Log.e(TAG, "Error in targets updated callback", t);
            }
        }

        @Override
        public void onAvailabilityChanged(boolean available) {
            try {
                synchronized (stateLock) {
                    targetsAvailable = available;
                    if (!targetsAvailable && sending.get()) {
                        stopSending();
                    }
                }
                notifyState();
            } catch (Throwable t) {
                Log.e(TAG, "Error in targets availability callback", t);
            }
        }
    };

    // ═══════════════════════════════════════════════════════
    // Unified Sensor Source Listener
    // ═══════════════════════════════════════════════════════

    /**
     * Unified listener for SensorSourceManager (handles both INTERNAL and EXTERNAL).
     * Combines mode switching and send authorization in a single listener.
     */
    private final SensorSourceManager.SourceListener sourceListener =
            new SensorSourceManager.SourceListener() {
                @Override
                public void onAccelDataReceived(AccelerometerManager.AccelStatistics stats,
                                                SensorSourceManager.SourceType source) {
                    try {
                        // Log data received
                        if (currentMode == OperatingMode.STANDBY) {
                            Log.d(TAG, String.format("📊 Data received from %s source", source));
                        }

                        // ═══════════════════════════════════════════════════════
                        // STANDBY mode: Send authorization gateway
                        // ═══════════════════════════════════════════════════════
                        if (currentMode != OperatingMode.STANDBY) {
                            return;
                        }

                        if (!sending.get()) {
                            Log.d(TAG, "⏸️ Accel event ignored: sending not authorized by user");
                            return;
                        }

                        if (!gpsAvailable || !networkAvailable || !targetsAvailable) {
                            Log.w(TAG, "⚠️ Accel event ignored: required services not ready");

                            if (sending.get()) {
                                stopSending();
                                final String errorMsg = "Sending stopped: a required service is unavailable";
                                mainHandler.post(() -> {
                                    if (listener != null) {
                                        listener.onSendError(errorMsg);
                                    }
                                });
                            }
                            return;
                        }

                        Log.d(TAG, String.format("📊 STANDBY mode: Sending data from %s source", source));
                        udpHelper.sendDataTriggeredByAccel(stats);

                    } catch (Throwable t) {
                        Log.e(TAG, "Error processing sensor data", t);
                    }
                }

                @Override
                public void onSourceAvailabilityChanged(SensorSourceManager.SourceType source,
                                                        boolean available) {
                    try {
                        synchronized (stateLock) {
                            accelAvailable = available;

                            // ═══════════════════════════════════════════════════════
                            // Mode switching logic (ACTIVE ↔ STANDBY)
                            // ═══════════════════════════════════════════════════════
                            OperatingMode newMode = available ?
                                    OperatingMode.STANDBY : OperatingMode.ACTIVE;

                            if (newMode != currentMode) {
                                Log.i(TAG, String.format(
                                        "🔄 Mode switch: %s → %s (source: %s, available: %s)",
                                        currentMode, newMode, source, available
                                ));

                                currentMode = newMode;

                                if (sending.get()) {
                                    stopSending();
                                    startSending();
                                }
                            }

                            if (!available && sending.get()) {
                                Log.w(TAG, String.format("⚠️ %s source lost - switching to GPS-only", source));
                            }
                        }
                        notifyState();
                    } catch (Throwable t) {
                        Log.e(TAG, "Error in source availability callback", t);
                    }
                }
            };

    // ═══════════════════════════════════════════════════════
    // Lifecycle Management
    // ═══════════════════════════════════════════════════════

    public void dispose() {
        if (disposed) {
            Log.w(TAG, "Already disposed, ignoring");
            return;
        }
        disposed = true;

        Log.d(TAG, "🔴 Disposing GPSSender");
        stopSending();

        gpsManager.removeLocationListener(gpsListener);
        networkManager.removeListener(networkListener);
        resolver.removeTargetsListener(targetsListener);

        // ═══════════════════════════════════════════════════════
        // Remove unified source listener
        // ═══════════════════════════════════════════════════════
        if (sensorSourceManager != null) {
            sensorSourceManager.removeListener(sourceListener);
            Log.d(TAG, "Removed sensor source listener");
        }

        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
                Log.w(TAG, "Scheduler forced shutdown");
            } else {
                Log.d(TAG, "Scheduler shutdown gracefully");
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
            Log.e(TAG, "Scheduler shutdown interrupted", e);
        }
    }
}