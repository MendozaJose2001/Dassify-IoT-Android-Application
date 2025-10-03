package com.electro.gsms.services;

import android.annotation.SuppressLint;
import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import java.util.HashSet;
import java.util.Set;

/**
 * GPSHelper
 * Handles GPS location updates and notifies multiple external listeners.
 * Provides a timeout mechanism to detect GPS signal loss.
 */
public class GPSManager {

    private static final String TAG = "GPSHelper";
    private static final long GPS_TIMEOUT_MS = 1500; // Timeout to detect lost GPS (1.5 seconds)

    private final LocationManager locationManager;
    private LocationListener locationListener;
    private boolean isRunning = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean gpsAvailable = false;

    /** Interface for external listeners to receive GPS updates */
    public interface LocationListenerExternal {
        void onNewLocation(Location location);
        void onGPSAvailabilityChanged(boolean available);
    }

    /** Set of registered external listeners */
    private final Set<LocationListenerExternal> externalListeners = new HashSet<>();

    public GPSManager(Context context) {
        locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        Log.d(TAG, "GPSHelper initialized");
    }

    /** Add an external listener (does not replace existing ones) */
    public void addLocationListener(LocationListenerExternal listener) {
        if (listener != null) {
            externalListeners.add(listener);
        }
    }

    /** Remove an external listener */
    public void removeLocationListener(LocationListenerExternal listener) {
        if (listener != null) {
            externalListeners.remove(listener);
        }
    }

    /**
     * Start listening for GPS updates.
     * Requires location permissions granted.
     */
    @SuppressLint("MissingPermission")
    public void start() {
        if (isRunning) return;

        locationListener = new LocationListener() {
            @Override
            public void onLocationChanged(@NonNull Location location) {
                // Notify all external listeners with new location and availability
                for (LocationListenerExternal listener : externalListeners) {
                    listener.onNewLocation(location);
                    listener.onGPSAvailabilityChanged(true);
                }

                if (!gpsAvailable) gpsAvailable = true;

                // Reset timeout whenever a new location arrives
                handler.removeCallbacks(gpsTimeoutRunnable);
                handler.postDelayed(gpsTimeoutRunnable, GPS_TIMEOUT_MS);
            }

            @Override
            public void onProviderEnabled(@NonNull String provider) {}
            @Override
            public void onProviderDisabled(@NonNull String provider) {}
        };

        // Request location updates from GPS provider every 1 second
        locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1000, // interval in ms
                0,    // minimum distance in meters
                locationListener
        );

        // Start timeout to detect loss of GPS signal
        handler.postDelayed(gpsTimeoutRunnable, GPS_TIMEOUT_MS);
        isRunning = true;
        Log.d(TAG, "GPSHelper started background updates");
    }

    /** Stop listening for GPS updates and clear callbacks */
    public void stop() {
        if (locationListener != null) {
            locationManager.removeUpdates(locationListener);
            locationListener = null;
        }
        handler.removeCallbacks(gpsTimeoutRunnable);
        gpsAvailable = false;
        isRunning = false;
        Log.d(TAG, "GPSHelper stopped background updates");
    }

    /** Runnable to detect GPS signal loss when no updates are received within timeout */
    private final Runnable gpsTimeoutRunnable = () -> {
        if (gpsAvailable) {
            gpsAvailable = false;
            for (LocationListenerExternal listener : externalListeners) {
                listener.onGPSAvailabilityChanged(false);
            }
            Log.d(TAG, "GPS satellite reception lost");
        }
    };
}




