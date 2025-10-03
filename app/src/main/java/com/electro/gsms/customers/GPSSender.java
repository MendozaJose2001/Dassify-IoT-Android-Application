package com.electro.gsms.customers;

import com.electro.gsms.services.GPSManager;
import com.electro.gsms.services.NetworkManager;
import com.electro.gsms.services.TargetResolverManager;

import java.net.InetAddress;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * GPSSenderController
 * Handles periodic GPS sending via UDP/TCP.
 * Notifies listener about sending state and errors.
 */

public class GPSSender {

    public enum SenderState {
        UNAVAILABLE, READY, SENDING
    }

    public interface SenderStateListener {
        void onStateChanged(SenderState state);
        void onSendError(String message);
    }

    private final GPSManager gpsManager;
    private final NetworkManager networkManager;
    private final TargetResolverManager resolver;
    private final UDPHelper udpHelper;

    private final SenderStateListener listener;

    private static final long SEND_INTERVAL_MS = 5000;

    private final AtomicBoolean sending = new AtomicBoolean(false);
    private volatile boolean gpsAvailable = false;
    private volatile boolean networkAvailable = false;
    private volatile boolean targetsAvailable = false;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

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

        notifyState();
    }

    /** Toggle sending on/off */
    public void toggleSending() {
        if (sending.get()) {
            stopSending();
        } else if (gpsAvailable && networkAvailable && targetsAvailable) {
            startSending();
        } else {
            if (listener != null) {
                listener.onSendError("Cannot start sending: check GPS, network, and targets availability");
            }
        }
    }

    /** Start periodic sending */
    private void startSending() {
        sending.set(true);
        notifyState();
        scheduler.scheduleWithFixedDelay(this::sendOnce, 0, SEND_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    /** Stop sending */
    private void stopSending() {
        sending.set(false);
        notifyState();
    }

    /** Single send iteration */
    private void sendOnce() {
        if (!sending.get() || !gpsAvailable || !networkAvailable || !targetsAvailable) {
            if (sending.get()) stopSending();
            if (!gpsAvailable || !networkAvailable || !targetsAvailable) {
                if (listener != null)
                    listener.onSendError("Sending stopped: a required service is unavailable");
            }
            return;
        }

        boolean udpSent = udpHelper.sendGPSData();

        if (!udpSent && listener != null) {
            listener.onSendError("UDP: Failed to send GPS data");
        }
    }

    /** Notify listener about current state */
    private void notifyState() {
        SenderState state;
        if (!gpsAvailable || !networkAvailable || !targetsAvailable) {
            state = SenderState.UNAVAILABLE;
        } else if (sending.get()) {
            state = SenderState.SENDING;
        } else {
            state = SenderState.READY;
        }

        if (listener != null) listener.onStateChanged(state);
    }

    /** Listeners for dependency updates */
    private final GPSManager.LocationListenerExternal gpsListener = new GPSManager.LocationListenerExternal() {
        @Override
        public void onNewLocation(android.location.Location location) { }

        @Override
        public void onGPSAvailabilityChanged(boolean available) {
            gpsAvailable = available;
            if (!gpsAvailable && sending.get()) stopSending();
            notifyState();
        }
    };

    private final NetworkManager.NetworkListener networkListener = new NetworkManager.NetworkListener() {
        @Override
        public void onNetworkAvailable(android.net.Network network) {
            networkAvailable = true;
            notifyState();
        }

        @Override
        public void onNetworkUnavailable() {
            networkAvailable = false;
            if (sending.get()) stopSending();
            notifyState();
        }
    };

    private final TargetResolverManager.TargetsListener targetsListener = new TargetResolverManager.TargetsListener() {
        @Override
        public void onTargetsUpdated(Map<InetAddress, Set<Integer>> targets) {
            targetsAvailable = targets != null && !targets.isEmpty();
            if (!targetsAvailable && sending.get()) stopSending();
            notifyState();
        }

        @Override
        public void onAvailabilityChanged(boolean available) {
            targetsAvailable = available;
            if (!targetsAvailable && sending.get()) stopSending();
            notifyState();
        }
    };

    /** Dispose all listeners and scheduler */
    public void dispose() {
        stopSending();
        gpsManager.removeLocationListener(gpsListener);
        networkManager.removeListener(networkListener);
        resolver.removeTargetsListener(targetsListener);
        scheduler.shutdownNow();
    }
}
