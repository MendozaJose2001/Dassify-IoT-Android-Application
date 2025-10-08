package com.electro.gsms.helpers;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.electro.gsms.services.GPSManager;
import com.electro.gsms.services.NetworkManager;
import com.electro.gsms.services.TargetResolverManager;

import java.net.InetAddress;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * GPSSender
 * Handles periodic GPS sending via UDP/TCP.
 * Notifies listener about sending state and errors.
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

    private static final String TAG = "GPSSender";

    /**
     * Interface for receiving sender state updates and errors
     */
    public interface SenderStateListener {
        /**
         * Called when the sender state changes
         * @param state The new sender state
         */
        void onStateChanged(SenderState state);

        /**
         * Called when an error occurs during sending
         * @param message Error description
         */
        void onSendError(String message);
    }

    private final GPSManager gpsManager;
    private final NetworkManager networkManager;
    private final TargetResolverManager resolver;
    private final UDPHelper udpHelper;

    private final SenderStateListener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private static final long SEND_INTERVAL_MS = 5000;
    private static final long DEBOUNCE_STATE_MS = 500L; // Debounce 500ms for state notifications

    private final AtomicBoolean sending = new AtomicBoolean(false);
    private volatile boolean gpsAvailable = false;
    private volatile boolean networkAvailable = false;
    private volatile boolean targetsAvailable = false;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> sendingTask = null;

    private final Object stateLock = new Object();
    private volatile boolean disposed = false;
    private long lastStateNotificationTime = 0; // Debounce control

    /**
     * Constructs a GPSSender instance
     * @param gpsManager GPS manager for location data
     * @param networkManager Network manager for connectivity
     * @param resolver Target resolver for UDP destinations
     * @param udpHelper UDP helper for data transmission
     * @param listener Listener for state and error notifications
     */
    public GPSSender(GPSManager gpsManager,
                     NetworkManager networkManager,
                     TargetResolverManager resolver,
                     UDPHelper udpHelper,
                     SenderStateListener listener) {
        this.gpsManager = gpsManager;
        this.networkManager = networkManager;
        this.resolver = resolver;
        this.udpHelper = udpHelper;
        this.listener = listener;

        // Register listeners for dependency updates
        gpsManager.addLocationListener(gpsListener);
        networkManager.addListener(networkListener);
        resolver.addTargetsListener(targetsListener);

        captureInitialState();
        notifyState();
    }

    /**
     * Toggle sending on/off
     */
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

    /**
     * Start periodic sending
     */
    private void startSending() {
        if (sendingTask != null && !sendingTask.isCancelled()) {
            sendingTask.cancel(false);
        }

        sending.set(true);
        notifyState();

        sendingTask = scheduler.scheduleWithFixedDelay(
                this::sendOnce,
                0,
                SEND_INTERVAL_MS,
                TimeUnit.MILLISECONDS
        );

        Log.d(TAG, "Started sending");
    }

    /**
     * Stop sending
     */
    private void stopSending() {
        sending.set(false);

        if (sendingTask != null && !sendingTask.isCancelled()) {
            sendingTask.cancel(false);
            sendingTask = null;
        }

        Log.d(TAG, "Stopped sending");
        notifyState();
    }

    /**
     * Single send iteration
     */
    private void sendOnce() {
        if (!sending.get() || !gpsAvailable || !networkAvailable || !targetsAvailable) {
            if (sending.get()) stopSending();
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

    /**
     * Notify listener about current state (with debouncing)
     */
    private void notifyState() {
        long now = System.currentTimeMillis();

        // Debounce: only notify if >500ms passed since last notification
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

    /**
     * Captures initial state from services
     */
    private void captureInitialState() {
        try {
            synchronized (stateLock) {
                gpsAvailable = gpsManager.isGPSAvailable();
                networkAvailable = networkManager.isNetworkAvailable();

                Log.d(TAG, "Initial state: GPS=" + gpsAvailable
                        + ", Network=" + networkAvailable
                        + ", Targets=" + targetsAvailable);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error capturing initial state", e);
        }
    }

    /** Listeners for dependency updates */
    private final GPSManager.LocationListenerExternal gpsListener = new GPSManager.LocationListenerExternal() {
        @Override
        public void onNewLocation(android.location.Location location) {
            // Location updates are handled by the periodic sending task
        }

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

    // ---------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------

    /**
     * Checks if currently sending GPS data
     * @return true if actively sending
     */
    @SuppressWarnings("unused")
    public boolean isSending() {
        return sending.get();
    }

    /**
     * Checks if GPS service is available
     * @return true if GPS signal is available
     */
    @SuppressWarnings("unused")
    public boolean isGPSAvailable() {
        return gpsAvailable;
    }

    /**
     * Checks if cellular network is available
     * @return true if network is available
     */
    @SuppressWarnings("unused")
    public boolean isNetworkAvailable() {
        return networkAvailable;
    }

    /**
     * Checks if UDP targets are resolved and available
     * @return true if targets are available
     */
    @SuppressWarnings("unused")
    public boolean areTargetsAvailable() {
        return targetsAvailable;
    }

    /**
     * Checks if all dependencies are ready for sending
     * @return true if ready to start sending
     */
    @SuppressWarnings("unused")
    public boolean isReady() {
        return gpsAvailable && networkAvailable && targetsAvailable && !disposed;
    }

    /**
     * Checks if GPSSender has been disposed
     * @return true if disposed
     */
    @SuppressWarnings("unused")
    public boolean isDisposed() {
        return disposed;
    }

    /**
     * Dispose all listeners and scheduler
     * Cleans up resources and stops any active sending
     */
    public void dispose() {
        if (disposed) {
            Log.w(TAG, "Already disposed, ignoring");
            return;
        }
        disposed = true;

        Log.d(TAG, "Disposing GPSSender");
        stopSending();

        // Remove all listeners
        gpsManager.removeLocationListener(gpsListener);
        networkManager.removeListener(networkListener);
        resolver.removeTargetsListener(targetsListener);

        // Shutdown scheduler
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