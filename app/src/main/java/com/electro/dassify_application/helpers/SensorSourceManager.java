package com.electro.dassify_application.helpers;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import com.electro.dassify_application.services.AccelerometerManager;

import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicReference;

/**
 * SensorSourceManager - Unified manager for dual accelerometer sources.
 *
 * <p><b>Operating Modes:</b></p>
 * <ul>
 *   <li>INTERNAL: Uses phone's built-in accelerometer (AccelerometerManager)</li>
 *   <li>EXTERNAL: Uses ESP32 + MPU6050 via Bluetooth (BluetoothHelper)</li>
 * </ul>
 *
 * <p><b>Features:</b></p>
 * <ul>
 *   <li>Hot-swap between sources without app restart</li>
 *   <li>Unified listener interface (both sources emit AccelStatistics)</li>
 *   <li>Automatic lifecycle management</li>
 *   <li>Thread-safe source switching</li>
 * </ul>
 *
 * <p><b>Architecture:</b></p>
 * Acts as a lightweight wrapper that delegates to either AccelerometerManager
 * or BluetoothHelper, presenting a unified interface to consumers (GPSSender).
 */
public class SensorSourceManager {

    private static final String TAG = "SensorSourceManager";

    // ═══════════════════════════════════════════════════════
    // Source Types
    // ═══════════════════════════════════════════════════════

    /**
     * Available sensor sources.
     */
    public enum SourceType {
        INTERNAL,  // Phone's built-in accelerometer
        EXTERNAL   // ESP32 + MPU6050 via Bluetooth
    }

    // ═══════════════════════════════════════════════════════
    // Listener Interface
    // ═══════════════════════════════════════════════════════

    /**
     * Unified listener for sensor data from any source.
     */
    public interface SourceListener {
        /**
         * Called when accelerometer data is received from current source.
         *
         * @param stats Accelerometer statistics
         * @param source Source that generated the data (INTERNAL or EXTERNAL)
         */
        void onAccelDataReceived(AccelerometerManager.AccelStatistics stats, SourceType source);

        /**
         * Called when availability of current source changes.
         *
         * @param source Source type
         * @param available true if source is ready and operational
         */
        void onSourceAvailabilityChanged(SourceType source, boolean available);
    }

    // ═══════════════════════════════════════════════════════
    // State Management
    // ═══════════════════════════════════════════════════════

    private final AtomicReference<SourceType> currentSource = new AtomicReference<>(SourceType.INTERNAL);
    private volatile boolean running = false;
    private volatile AccelerometerManager.AccelStatistics lastAccelPacket = null;

    // ═══════════════════════════════════════════════════════
    // Dependencies
    // ═══════════════════════════════════════════════════════

    private final Context context;
    private final AccelerometerManager accelManager;
    private final BluetoothHelper bluetoothHelper;

    private final CopyOnWriteArraySet<SourceListener> listeners = new CopyOnWriteArraySet<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ═══════════════════════════════════════════════════════
    // Constructor
    // ═══════════════════════════════════════════════════════

    /**
     * Constructs SensorSourceManager with both accelerometer sources.
     *
     * @param context Android context
     */
    public SensorSourceManager(Context context) {
        this.context = context.getApplicationContext();

        // Initialize both helpers
        this.accelManager = new AccelerometerManager(this.context);
        this.bluetoothHelper = new BluetoothHelper();

        // Register internal listeners
        setupInternalListeners();

        Log.d(TAG, "SensorSourceManager initialized (default source: INTERNAL)");
    }

    // ═══════════════════════════════════════════════════════
    // Internal Listener Setup
    // ═══════════════════════════════════════════════════════

    private void setupInternalListeners() {
        // ═══════════════════════════════════════════════════════
        // AccelerometerManager listener (INTERNAL source)
        // ═══════════════════════════════════════════════════════
        accelManager.addListener(new AccelerometerManager.AccelStatisticsListener() {
            @Override
            public void onStatisticsComputed(AccelerometerManager.AccelStatistics statistics) {
                if (currentSource.get() == SourceType.INTERNAL) {
                    lastAccelPacket = statistics;
                    notifyAccelData(statistics, SourceType.INTERNAL);
                }
            }

            @Override
            public void onAvailabilityChanged(boolean available) {
                if (currentSource.get() == SourceType.INTERNAL) {
                    notifyAvailability(SourceType.INTERNAL, available);
                }
            }
        });

        // ═══════════════════════════════════════════════════════
        // BluetoothHelper listener (EXTERNAL source)
        // ═══════════════════════════════════════════════════════
        bluetoothHelper.addListener(new BluetoothHelper.BluetoothListener() {
            @Override
            public void onBluetoothAvailabilityChanged(boolean available) {
                // Informational only
                Log.d(TAG, "Bluetooth availability: " + available);
            }

            @Override
            public void onConnectionStateChanged(boolean connected) {
                if (currentSource.get() == SourceType.EXTERNAL) {
                    notifyAvailability(SourceType.EXTERNAL, connected);
                }
            }

            @Override
            public void onAccelDataReceived(AccelPacket packet) {
                if (currentSource.get() == SourceType.EXTERNAL && packet.isValid()) {
                    AccelerometerManager.AccelStatistics stats = packet.toAccelStatistics();
                    lastAccelPacket = stats;
                    notifyAccelData(stats, SourceType.EXTERNAL);
                }
            }

            @Override
            public void onError(String errorMessage) {
                Log.e(TAG, "Bluetooth error: " + errorMessage);
            }
        });
    }

    // ═══════════════════════════════════════════════════════
    // Public API
    // ═══════════════════════════════════════════════════════

    public void addListener(SourceListener listener) {
        listeners.add(listener);
    }

    public void removeListener(SourceListener listener) {
        listeners.remove(listener);
    }

    public SourceType getCurrentSource() {
        return currentSource.get();
    }

    public AccelerometerManager.AccelStatistics getLastAccelPacket() {
        return lastAccelPacket;
    }

    public boolean isSensorAvailable() {
        SourceType source = currentSource.get();
        if (source == SourceType.INTERNAL) {
            return accelManager.isSensorAvailable() && accelManager.isRunning();
        } else {
            return bluetoothHelper.isConnected();
        }
    }

    public boolean isBluetoothAvailable() {
        return bluetoothHelper.isBluetoothAvailable();
    }

    /**
     * Get reference to BluetoothHelper (for UI status updates).
     */
    public BluetoothHelper getBluetoothHelper() {
        return bluetoothHelper;
    }

    /**
     * Get reference to AccelerometerManager (for toggle functionality).
     */
    public AccelerometerManager getAccelerometerManager() {
        return accelManager;
    }

    // ═══════════════════════════════════════════════════════
    // Source Switching (Hot-swap)
    // ═══════════════════════════════════════════════════════

    /**
     * Switch between accelerometer sources.
     * Performs hot-swap: stops previous source, starts new source.
     *
     * @param newSource Source to switch to (INTERNAL or EXTERNAL)
     */
    public void setSource(@NonNull SourceType newSource) {
        SourceType previousSource = currentSource.getAndSet(newSource);

        if (previousSource == newSource) {
            Log.d(TAG, "Already using source: " + newSource);
            return;
        }

        Log.i(TAG, String.format("🔄 Switching source: %s → %s", previousSource, newSource));

        // Stop previous source
        if (running) {
            stopHelper(previousSource);
        }

        // Start new source
        if (running) {
            startHelper(newSource);
        }

        // Notify availability of new source
        notifyAvailability(newSource, isSensorAvailable());
    }

    // ═══════════════════════════════════════════════════════
    // Lifecycle Management
    // ═══════════════════════════════════════════════════════

    /**
     * Start the current accelerometer source.
     */
    public void start() {
        if (running) {
            Log.w(TAG, "SensorSourceManager already running");
            return;
        }

        running = true;
        SourceType source = currentSource.get();

        Log.d(TAG, "Starting SensorSourceManager with source: " + source);
        startHelper(source);
    }

    /**
     * Stop the current accelerometer source.
     */
    public void stop() {
        if (!running) {
            return;
        }

        running = false;
        SourceType source = currentSource.get();

        Log.d(TAG, "Stopping SensorSourceManager");
        stopHelper(source);
    }

    /**
     * Start specific helper based on source type.
     */
    private void startHelper(SourceType source) {
        if (source == SourceType.INTERNAL) {
            Log.d(TAG, "Starting SensorHelper (internal sensors)");
            accelManager.start();
        } else {
            Log.d(TAG, "Starting BluetoothHelper (ESP32)");
            bluetoothHelper.start();
        }
    }

    /**
     * Stop specific helper based on source type.
     */
    private void stopHelper(SourceType source) {
        if (source == SourceType.INTERNAL) {
            Log.d(TAG, "Stopping SensorHelper");
            accelManager.stop();
        } else {
            Log.d(TAG, "Stopping BluetoothHelper");
            bluetoothHelper.stop();
        }
    }

    // ═══════════════════════════════════════════════════════
    // Listener Notifications
    // ═══════════════════════════════════════════════════════

    private void notifyAccelData(@NonNull AccelerometerManager.AccelStatistics stats,
                                 @NonNull SourceType source) {
        mainHandler.post(() -> {
            for (SourceListener listener : listeners) {
                try {
                    listener.onAccelDataReceived(stats, source);
                } catch (Exception e) {
                    Log.e(TAG, "Error in listener callback", e);
                }
            }
        });
    }

    private void notifyAvailability(@NonNull SourceType source, boolean available) {
        mainHandler.post(() -> {
            for (SourceListener listener : listeners) {
                try {
                    listener.onSourceAvailabilityChanged(source, available);
                } catch (Exception e) {
                    Log.e(TAG, "Error in listener callback", e);
                }
            }
        });
    }

    // ═══════════════════════════════════════════════════════
    // Cleanup
    // ═══════════════════════════════════════════════════════

    /**
     * Dispose of all resources.
     * Should be called in Activity.onDestroy().
     */
    public void dispose() {
        Log.d(TAG, "Disposing SensorSourceManager");

        stop();

        // Note: We don't dispose of the helpers themselves as they might be
        // used elsewhere. Just stop them.
        listeners.clear();
    }
}