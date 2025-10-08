package com.electro.gsms.services;

import android.annotation.SuppressLint;
import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.util.Log;

import androidx.annotation.NonNull;

import java.util.concurrent.CopyOnWriteArrayList;
import android.os.Handler;
import android.os.Looper;

/**
 * GPSManager — Monitors GPS location updates and satellite signal availability.
 * Features:
 * - All notifications on main thread for UI safety
 * - Try-catch protection for all listener notifications
 * - Immediate state notification for new listeners
 * - Timeout detection for signal loss
 * - Memory leak prevention via ApplicationContext
 * - Safe start/stop and resource cleanup
 */
public class GPSManager {

    private static final String TAG = "GPSManager";
    private static final long UPDATE_INTERVAL_MS = 1000;
    private static final float MIN_DISTANCE_METERS = 0f;
    private static final long GPS_TIMEOUT_MS = 1500;

    /** Interface for external location listeners */
    public interface LocationListenerExternal {
        /**
         * Called when a new location is received from GPS
         * @param location The new location data
         */
        void onNewLocation(Location location);

        /**
         * Called when GPS satellite signal availability changes
         * @param available true if GPS signal is available, false if lost
         */
        void onGPSAvailabilityChanged(boolean available);
    }

    private final LocationManager locationManager;
    private final CopyOnWriteArrayList<LocationListenerExternal> externalListeners = new CopyOnWriteArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private volatile boolean gpsAvailable = false; // true = active satellite signal
    private volatile boolean isRunning = false;
    private volatile Location lastLocation = null;

    private LocationListener locationListener;
    private Runnable timeoutRunnable;

    // ---------------------------------------------------------------
    // Constructor
    // ---------------------------------------------------------------

    public GPSManager(@NonNull Context context) {
        Context appContext = context.getApplicationContext();
        locationManager = (LocationManager) appContext.getSystemService(Context.LOCATION_SERVICE);

        if (locationManager == null) {
            Log.e(TAG, "LocationManager is null — GPS monitoring disabled.");
        } else {
            Log.d(TAG, "GPSManager initialized");
        }
    }

    // ---------------------------------------------------------------
    // Lifecycle Management
    // ---------------------------------------------------------------

    /**
     * Starts receiving GPS location updates
     * Requires location permissions to be granted
     */
    @SuppressLint("MissingPermission")
    public synchronized void start() {
        if (isRunning || locationManager == null) return;

        initLocationListener();

        try {
            locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    UPDATE_INTERVAL_MS,
                    MIN_DISTANCE_METERS,
                    locationListener
            );

            isRunning = true;
            Log.d(TAG, "GPSManager started receiving updates");

            // Capture initial GPS state
            captureInitialState();

            // Start timeout monitoring (always, even with cached location)
            resetTimeout();

        } catch (SecurityException e) {
            Log.e(TAG, "Missing location permissions", e);
        } catch (Exception e) {
            Log.e(TAG, "Error starting GPS monitoring", e);
        }
    }

    /**
     * Stops GPS updates and cleans up resources
     */
    public synchronized void stop() {
        if (!isRunning || locationManager == null) return;

        try {
            if (locationListener != null) {
                locationManager.removeUpdates(locationListener);
                locationListener = null;
            }

            cancelTimeout();

            isRunning = false;
            gpsAvailable = false;
            lastLocation = null;

            Log.d(TAG, "GPSManager stopped receiving updates");

        } catch (Exception e) {
            Log.e(TAG, "Error stopping GPS monitoring", e);
        }
    }

    // ---------------------------------------------------------------
    // Initialization
    // ---------------------------------------------------------------

    /**
     * Initializes the location listener to handle GPS updates
     */
    private void initLocationListener() {
        locationListener = new LocationListener() {
            @Override
            public void onLocationChanged(@NonNull Location location) {
                lastLocation = location;

                // Always mark as available when receiving location updates
                gpsAvailable = true;

                // Notify new location
                notifyLocationUpdate(location);

                // Constantly notify availability
                notifyAvailabilityChanged(true);

                // Reset timeout on new location reception
                resetTimeout();
            }

            @Override
            public void onProviderEnabled(@NonNull String provider) {
                if (LocationManager.GPS_PROVIDER.equals(provider)) {
                    gpsAvailable = true;
                    notifyAvailabilityChanged(true);
                    Log.d(TAG, "GPS provider enabled");
                }
            }

            @Override
            public void onProviderDisabled(@NonNull String provider) {
                if (LocationManager.GPS_PROVIDER.equals(provider)) {
                    gpsAvailable = false;
                    lastLocation = null;
                    cancelTimeout();
                    notifyAvailabilityChanged(false);
                    Log.d(TAG, "GPS provider disabled");
                }
            }
        };
    }

    /**
     * Captures initial GPS state and last known location
     */
    @SuppressLint("MissingPermission")
    private void captureInitialState() {
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                Location lastKnown = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                if (lastKnown != null) {
                    lastLocation = lastKnown;
                    gpsAvailable = true;
                    Log.d(TAG, "Initial GPS state: available with last known location");
                    // timeout is always managed from start()
                }
            }
        } catch (SecurityException e) {
            Log.e(TAG, "Missing permissions for initial state", e);
        } catch (Exception e) {
            Log.e(TAG, "Error capturing initial GPS state", e);
        }
    }

    // ---------------------------------------------------------------
    // Timeout Handling
    // ---------------------------------------------------------------

    /**
     * Resets the GPS signal timeout timer
     */
    private void resetTimeout() {
        cancelTimeout();

        timeoutRunnable = () -> {
            if (gpsAvailable && isRunning) {
                gpsAvailable = false;
                Log.d(TAG, "GPS signal lost (timeout)");
                notifyAvailabilityChanged(false);
            }
        };

        mainHandler.postDelayed(timeoutRunnable, GPS_TIMEOUT_MS);
    }

    /**
     * Cancels the current timeout task
     */
    private void cancelTimeout() {
        if (timeoutRunnable != null) {
            mainHandler.removeCallbacks(timeoutRunnable);
            timeoutRunnable = null;
        }
    }

    // ---------------------------------------------------------------
    // Notification Handling
    // ---------------------------------------------------------------

    /**
     * Notifies all listeners of new location updates
     * @param location The new location data
     */
    private void notifyLocationUpdate(Location location) {
        if (externalListeners.isEmpty()) return;

        mainHandler.post(() -> {
            for (LocationListenerExternal listener : externalListeners) {
                try {
                    listener.onNewLocation(location);
                } catch (Throwable t) {
                    Log.e(TAG, "Listener threw exception on location update", t);
                }
            }
        });
    }

    /**
     * Notifies all listeners of GPS availability changes
     * @param available Whether GPS signal is available
     */
    private void notifyAvailabilityChanged(boolean available) {
        if (externalListeners.isEmpty()) return;

        mainHandler.post(() -> {
            for (LocationListenerExternal listener : externalListeners) {
                try {
                    listener.onGPSAvailabilityChanged(available);
                } catch (Throwable t) {
                    Log.e(TAG, "Listener threw exception on availability change", t);
                }
            }
        });
    }

    // ---------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------

    /**
     * Adds a location listener and provides immediate state notification
     * @param listener The listener to add
     */
    public void addLocationListener(LocationListenerExternal listener) {
        if (listener == null) return;

        externalListeners.add(listener);

        // Immediate notification of current state
        mainHandler.post(() -> {
            try {
                listener.onGPSAvailabilityChanged(gpsAvailable);

                if (gpsAvailable && lastLocation != null) {
                    listener.onNewLocation(lastLocation);
                }
            } catch (Throwable t) {
                Log.e(TAG, "Error in immediate notification", t);
            }
        });
    }

    /**
     * Removes a location listener
     * @param listener The listener to remove
     */
    public void removeLocationListener(LocationListenerExternal listener) {
        if (listener != null) {
            externalListeners.remove(listener);
        }
    }

    /**
     * Removes all registered location listeners
     */
    @SuppressWarnings("unused")
    public void clearAllListeners() {
        externalListeners.clear();
    }

    /**
     * Checks if GPS signal is currently available
     * @return true if GPS satellite signal is available
     */
    @SuppressWarnings("unused")
    public boolean isGPSAvailable() {
        return gpsAvailable;
    }

    /**
     * Gets the last known location
     * @return The last known Location or null if unavailable
     */
    @SuppressWarnings("unused")
    public Location getLastLocation() {
        return lastLocation;
    }

    /**
     * Checks if GPS monitoring is currently running
     * @return true if GPS updates are being received
     */
    @SuppressWarnings("unused")
    public boolean isRunning() {
        return isRunning;
    }
}