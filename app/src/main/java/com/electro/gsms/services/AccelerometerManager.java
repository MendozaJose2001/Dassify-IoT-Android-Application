package com.electro.gsms.services;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * AccelerometerManager - Manages accelerometer sensor readings and statistical analysis.
 *
 * <p><b>Features:</b></p>
 * <ul>
 *   <li>Reads accelerometer at 50 Hz (SENSOR_DELAY_GAME)</li>
 *   <li>Applies Butterworth high-pass filter (2nd order, fc=3.8Hz) to remove gravity</li>
 *   <li>Computes statistics every 5 seconds (250 samples)</li>
 *   <li>Thread-safe listener notifications on main thread</li>
 *   <li>Proper lifecycle management (start/stop)</li>
 * </ul>
 *
 * <p><b>Filter Implementation:</b></p>
 * <ul>
 *   <li>Type: Butterworth High-Pass 2nd order (approximates FIR-200 from paper)</li>
 *   <li>Cutoff frequency: 3.8 Hz (from research paper)</li>
 *   <li>Removes gravity (DC) and low-frequency vehicle motion (<3.8Hz)</li>
 *   <li>Preserves impact events and vibrations (>3.8Hz)</li>
 * </ul>
 *
 * <p><b>Statistical Metrics (per 5s window):</b></p>
 * <ul>
 *   <li>RMS (Root Mean Square) per axis and magnitude</li>
 *   <li>Maximum absolute acceleration per axis and magnitude</li>
 *   <li>Peak count (samples exceeding 1.5g threshold)</li>
 *   <li>Sample count for validation</li>
 * </ul>
 *
 * <p><b>Usage Example:</b></p>
 * <pre>
 * AccelerometerManager accelManager = new AccelerometerManager(context);
 * accelManager.addListener(new AccelStatisticsListener() {
 *     {@literal @}Override
 *     public void onStatisticsComputed(AccelStatistics stats) {
 *         Log.d(TAG, "RMS: " + stats.rmsMagnitude + "g");
 *     }
 *
 *     {@literal @}Override
 *     public void onAvailabilityChanged(boolean available) {
 *         Log.d(TAG, "Accelerometer: " + available);
 *     }
 * });
 * accelManager.start();
 * </pre>
 */
@SuppressWarnings("unused")
public class AccelerometerManager implements SensorEventListener {

    private static final String TAG = "AccelerometerManager";

    // =====================================================
    // CONFIGURATION CONSTANTS (Based on Research Paper)
    // =====================================================
    private static final float SAMPLE_RATE_HZ = 50.0f;
    private static final float CUTOFF_FREQ_HZ = 3.8f;   // From research paper
    private static final int SAMPLES_PER_WINDOW = 250;  // 5 seconds × 50 Hz
    private static final float PEAK_THRESHOLD_G = 1.5f; // Configurable threshold
    private static final float STANDARD_GRAVITY = 9.80665f; // m/s²

    // =====================================================
    // ANDROID SENSOR COMPONENTS
    // =====================================================
    private final SensorManager sensorManager;
    private final Sensor accelerometer;
    private final Handler mainHandler;

    // =====================================================
    // PROCESSING COMPONENTS
    // =====================================================
    private final ButterworthHighPass2 filterX;
    private final ButterworthHighPass2 filterY;
    private final ButterworthHighPass2 filterZ;
    private final StatisticsCalculator calculator;

    // =====================================================
    // LISTENER MANAGEMENT
    // =====================================================
    private final CopyOnWriteArrayList<AccelStatisticsListener> listeners;

    // =====================================================
    // STATE MANAGEMENT
    // =====================================================
    private volatile boolean isRunning = false;
    private final boolean sensorAvailable;  // Immutable after construction

    // =====================================================
    // LISTENER INTERFACE
    // =====================================================

    /**
     * Interface for receiving accelerometer statistics updates.
     * All callbacks are delivered on the main thread.
     */
    public interface AccelStatisticsListener {
        /**
         * Called when new statistics are computed (every 5 seconds).
         * Always called on main thread.
         *
         * @param statistics The computed statistics for the completed window
         */
        void onStatisticsComputed(AccelStatistics statistics);

        /**
         * Called when accelerometer availability changes.
         * Always called on main thread.
         *
         * @param available true if accelerometer is running, false otherwise
         */
        void onAvailabilityChanged(boolean available);
    }

    // =====================================================
    // INNER CLASS: AccelStatistics (Immutable Data Structure)
    // =====================================================

    /**
     * Immutable data structure containing accelerometer statistics for a 5-second window.
     * Thread-safe snapshot of computed metrics.
     */
    public static class AccelStatistics {
        // Timestamps
        public final long timestampStart;    // Millis since epoch
        public final long timestampEnd;

        // RMS (Root Mean Square) per axis (in g's)
        public final float rmsX;
        public final float rmsY;
        public final float rmsZ;
        public final float rmsMagnitude;     // Vectorial RMS

        // Maximum absolute values (in g's)
        public final float maxX;
        public final float maxY;
        public final float maxZ;
        public final float maxMagnitude;

        // Counters
        public final int peakCount;          // Samples exceeding threshold
        public final int sampleCount;        // Total samples in window

        // Validation flags
        public final byte flags;             // Bit flags for validation

        /**
         * Flag bit: Window incomplete (fewer samples than expected)
         */
        public static final byte FLAG_INCOMPLETE_WINDOW = 0x01;

        /**
         * Flag bit: Timing error (window duration not ~5 seconds)
         */
        public static final byte FLAG_TIMING_ERROR = 0x04;

        /**
         * Constructs an AccelStatistics object with validation.
         *
         * @throws IllegalArgumentException if timestamps are invalid
         */
        public AccelStatistics(long tsStart, long tsEnd,
                               float rmsX, float rmsY, float rmsZ, float rmsMag,
                               float maxX, float maxY, float maxZ, float maxMag,
                               int peakCount, int sampleCount) {
            // Validate timestamps
            if (tsEnd <= tsStart) {
                throw new IllegalArgumentException("Invalid timestamps: end must be after start");
            }

            this.timestampStart = tsStart;
            this.timestampEnd = tsEnd;

            this.rmsX = rmsX;
            this.rmsY = rmsY;
            this.rmsZ = rmsZ;
            this.rmsMagnitude = rmsMag;

            this.maxX = maxX;
            this.maxY = maxY;
            this.maxZ = maxZ;
            this.maxMagnitude = maxMag;

            this.peakCount = peakCount;
            this.sampleCount = sampleCount;

            // Auto-calculate validation flags
            this.flags = calculateFlags(tsStart, tsEnd, sampleCount);
        }

        /**
         * Calculates validation flags based on window characteristics.
         */
        private static byte calculateFlags(long tsStart, long tsEnd, int sampleCount) {
            byte flags = 0;

            // Check for incomplete window (less than 80% of expected samples)
            if (sampleCount < SAMPLES_PER_WINDOW * 0.8) {
                flags |= FLAG_INCOMPLETE_WINDOW;
            }

            // Check for timing error (window duration not ~5 seconds ±500ms)
            long duration = tsEnd - tsStart;
            if (Math.abs(duration - 5000) > 500) {
                flags |= FLAG_TIMING_ERROR;
            }

            return flags;
        }

        /**
         * Checks if the window has any validation issues.
         *
         * @return true if flags indicate problems, false if data is valid
         */
        public boolean hasValidationIssues() {
            return flags != 0;
        }

        @NonNull
        @Override
        public String toString() {
            return String.format(Locale.US,
                    "AccelStats[%d-%d] RMS: X=%.3f Y=%.3f Z=%.3f Mag=%.3f | " +
                            "MAX: X=%.3f Y=%.3f Z=%.3f Mag=%.3f | " +
                            "Peaks=%d Samples=%d Flags=0x%02X",
                    timestampStart, timestampEnd,
                    rmsX, rmsY, rmsZ, rmsMagnitude,
                    maxX, maxY, maxZ, maxMagnitude,
                    peakCount, sampleCount, flags);
        }
    }

    // =====================================================
    // INNER CLASS: ButterworthHighPass2 (2nd Order Filter)
    // =====================================================

    /**
     * Butterworth High-Pass Filter (2nd order).
     * Approximates FIR-200 filter from research paper with efficient IIR implementation.
     *
     * <p><b>Characteristics:</b></p>
     * <ul>
     *   <li>Type: High-pass (removes DC component and low frequencies)</li>
     *   <li>Order: 2nd (better approximation than 1st order)</li>
     *   <li>Cutoff: 3.8 Hz (from research paper)</li>
     *   <li>Q: 1/√2 (Butterworth - maximally flat passband)</li>
     * </ul>
     *
     * <p><b>Design rationale:</b></p>
     * <ul>
     *   <li>Removes gravity (0 Hz / DC component)</li>
     *   <li>Attenuates slow vehicle motion (<3.8 Hz)</li>
     *   <li>Preserves impact events and vibrations (>3.8 Hz)</li>
     * </ul>
     *
     * <p><b>Filter equation:</b></p>
     * <pre>
     * y[n] = b0*x[n] + b1*x[n-1] + b2*x[n-2] - a1*y[n-1] - a2*y[n-2]
     * </pre>
     */
    private static class ButterworthHighPass2 {
        // Filter coefficients (calculated in constructor)
        private final float b0, b1, b2;  // Feedforward coefficients
        private final float a1, a2;      // Feedback coefficients

        // Filter state (previous samples)
        private float x1, x2;  // Previous inputs
        private float y1, y2;  // Previous outputs
        private boolean initialized;

        /**
         * Constructs a Butterworth High-Pass filter (2nd order).
         * Uses bilinear transform for coefficient calculation.
         *
         * @param cutoffFreq Cutoff frequency in Hz (e.g., 3.8 Hz)
         * @param sampleRate Sample rate in Hz (e.g., 50 Hz)
         */
        public ButterworthHighPass2(float cutoffFreq, float sampleRate) {
            // Calculate normalized angular frequency
            double omega = 2.0 * Math.PI * cutoffFreq / sampleRate;
            double cosOmega = Math.cos(omega);
            double sinOmega = Math.sin(omega);

            // Q = 1/sqrt(2) for Butterworth (maximally flat response)
            double Q = 1.0 / Math.sqrt(2.0);
            double alpha = sinOmega / (2.0 * Q);

            // Normalization factor
            double a0 = 1.0 + alpha;

            // HIGH-PASS coefficients (numerator has pattern [1, -2, 1])
            this.b0 = (float) ((1.0 + cosOmega) / (2.0 * a0));
            this.b1 = (float) (-(1.0 + cosOmega) / a0);
            this.b2 = this.b0;

            // Feedback coefficients (denominator)
            this.a1 = (float) (-2.0 * cosOmega / a0);
            this.a2 = (float) ((1.0 - alpha) / a0);

            // Initialize state
            this.x1 = this.x2 = 0;
            this.y1 = this.y2 = 0;
            this.initialized = false;
        }

        /**
         * Processes one sample through the filter.
         * Implements Direct Form II Transposed structure.
         *
         * @param input Raw input value (m/s²)
         * @return Filtered output value (m/s²)
         */
        public float update(float input) {
            if (!initialized) {
                // Cold start: initialize state to avoid transient spike
                x1 = x2 = input;
                y1 = y2 = 0;  // High-pass output starts at zero
                initialized = true;
                return 0;
            }

            // Apply difference equation
            // y[n] = b0*x[n] + b1*x[n-1] + b2*x[n-2] - a1*y[n-1] - a2*y[n-2]
            float output = b0 * input + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;

            // Update state for next iteration
            x2 = x1;
            x1 = input;
            y2 = y1;
            y1 = output;

            return output;
        }

        /**
         * Resets filter state (call when pausing/resuming sensor).
         */
        public void reset() {
            x1 = x2 = y1 = y2 = 0;
            initialized = false;
        }
    }

    // =====================================================
    // INNER CLASS: StatisticsCalculator (5-second Windows)
    // =====================================================

    /**
     * Accumulates samples and computes statistics over 5-second windows.
     * Uses incremental accumulation for O(1) performance per sample.
     */
    private static class StatisticsCalculator {
        // Configuration
        private final int windowSize;
        private float peakThreshold;  // Made non-final for dynamic configuration

        // Accumulators for RMS (sum of squares)
        private float sumSqX;
        private float sumSqY;
        private float sumSqZ;

        // Trackers for maximum values
        private float maxX;
        private float maxY;
        private float maxZ;
        private float maxMagnitude;

        // Counters
        private int currentSampleCount;
        private int peakCount;

        // Timestamps
        private long windowStartTime;

        // State
        private boolean windowComplete;

        /**
         * Creates a statistics calculator for specified window size.
         *
         * @param windowSize Number of samples per window (e.g., 250)
         * @param peakThreshold Threshold in g's for peak detection (e.g., 1.5)
         */
        public StatisticsCalculator(int windowSize, float peakThreshold) {
            this.windowSize = windowSize;
            this.peakThreshold = peakThreshold;
            reset();
        }

        /**
         * Sets the peak detection threshold dynamically.
         * Useful for adjusting sensitivity based on vehicle type.
         *
         * @param thresholdG New threshold in g's
         */
        public synchronized void setPeakThreshold(float thresholdG) {
            this.peakThreshold = thresholdG;
        }

        /**
         * Adds one sample to the current window (O(1) operation).
         *
         * @param fx Filtered acceleration X in g's
         * @param fy Filtered acceleration Y in g's
         * @param fz Filtered acceleration Z in g's
         */
        public void addSample(float fx, float fy, float fz) {
            // 1. Accumulate sum of squares for RMS
            sumSqX += fx * fx;
            sumSqY += fy * fy;
            sumSqZ += fz * fz;

            // 2. Update maximum absolute values
            float absFx = Math.abs(fx);
            float absFy = Math.abs(fy);
            float absFz = Math.abs(fz);

            if (absFx > maxX) maxX = absFx;
            if (absFy > maxY) maxY = absFy;
            if (absFz > maxZ) maxZ = absFz;

            // 3. Calculate vectorial magnitude of this sample
            float magnitude = (float) Math.sqrt(fx * fx + fy * fy + fz * fz);
            if (magnitude > maxMagnitude) {
                maxMagnitude = magnitude;
            }

            // 4. Count peaks exceeding threshold
            if (magnitude > peakThreshold) {
                peakCount++;
            }

            // 5. Increment sample counter
            currentSampleCount++;

            // 6. Check if window is complete
            if (currentSampleCount >= windowSize) {
                windowComplete = true;
            }
        }

        /**
         * Checks if the current window has enough samples.
         *
         * @return true if window is ready to be computed
         */
        public boolean isWindowComplete() {
            return windowComplete;
        }

        /**
         * Computes final statistics for the current window.
         *
         * @return Immutable AccelStatistics object
         * @throws IllegalStateException if no samples have been added
         */
        public AccelStatistics compute() {
            if (currentSampleCount == 0) {
                throw new IllegalStateException("Cannot compute: no samples in window");
            }

            // Calculate RMS per axis
            float rmsX = (float) Math.sqrt(sumSqX / currentSampleCount);
            float rmsY = (float) Math.sqrt(sumSqY / currentSampleCount);
            float rmsZ = (float) Math.sqrt(sumSqZ / currentSampleCount);

            // Calculate vectorial RMS magnitude
            float rmsMag = (float) Math.sqrt(
                    (sumSqX + sumSqY + sumSqZ) / currentSampleCount
            );

            long now = System.currentTimeMillis();

            return new AccelStatistics(
                    windowStartTime,
                    now,
                    rmsX, rmsY, rmsZ, rmsMag,
                    maxX, maxY, maxZ, maxMagnitude,
                    peakCount,
                    currentSampleCount
            );
        }

        /**
         * Resets calculator state to start a new window.
         */
        public void reset() {
            sumSqX = sumSqY = sumSqZ = 0;
            maxX = maxY = maxZ = maxMagnitude = 0;
            peakCount = 0;
            currentSampleCount = 0;
            windowStartTime = System.currentTimeMillis();
            windowComplete = false;
        }
    }

    // =====================================================
    // CONSTRUCTOR
    // =====================================================

    /**
     * Constructs an AccelerometerManager.
     *
     * @param context Application context (prevent memory leaks)
     */
    public AccelerometerManager(@NonNull Context context) {
        Context appContext = context.getApplicationContext();

        // Obtain SensorManager
        sensorManager = (SensorManager) appContext.getSystemService(
                Context.SENSOR_SERVICE
        );

        if (sensorManager == null) {
            Log.e(TAG, "SensorManager not available - accelerometer monitoring disabled");
            accelerometer = null;
            sensorAvailable = false;
        } else {
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            sensorAvailable = (accelerometer != null);

            if (!sensorAvailable) {
                Log.w(TAG, "No accelerometer sensor found on device");
            }
        }

        // Initialize Butterworth high-pass filters (one per axis)
        filterX = new ButterworthHighPass2(CUTOFF_FREQ_HZ, SAMPLE_RATE_HZ);
        filterY = new ButterworthHighPass2(CUTOFF_FREQ_HZ, SAMPLE_RATE_HZ);
        filterZ = new ButterworthHighPass2(CUTOFF_FREQ_HZ, SAMPLE_RATE_HZ);

        // Initialize statistics calculator
        calculator = new StatisticsCalculator(SAMPLES_PER_WINDOW, PEAK_THRESHOLD_G);

        // Handler for main thread callbacks
        mainHandler = new Handler(Looper.getMainLooper());

        // Thread-safe listener list
        listeners = new CopyOnWriteArrayList<>();

        Log.d(TAG, String.format(Locale.US,
                "AccelerometerManager initialized:\n" +
                        "  Filter: Butterworth High-Pass 2nd order\n" +
                        "  Cutoff: %.1f Hz (from research paper)\n" +
                        "  Sample rate: %.0f Hz\n" +
                        "  Window: %d samples (%.1fs)\n" +
                        "  Peak threshold: %.1fg",
                CUTOFF_FREQ_HZ, SAMPLE_RATE_HZ,
                SAMPLES_PER_WINDOW, SAMPLES_PER_WINDOW / SAMPLE_RATE_HZ,
                PEAK_THRESHOLD_G
        ));
    }

    // =====================================================
    // LIFECYCLE MANAGEMENT
    // =====================================================

    /**
     * Starts accelerometer monitoring at ~50 Hz.
     * Requires accelerometer sensor to be available.
     */
    public synchronized void start() {
        if (isRunning) {
            Log.w(TAG, "Already running");
            return;
        }

        if (!sensorAvailable) {
            Log.e(TAG, "Cannot start: accelerometer sensor not available");
            notifyAvailabilityChanged(false);
            return;
        }

        // Register sensor listener with SENSOR_DELAY_GAME (~20ms, approximately 50 Hz)
        boolean registered = sensorManager.registerListener(
                this,
                accelerometer,
                SensorManager.SENSOR_DELAY_GAME
        );

        if (!registered) {
            Log.e(TAG, "Failed to register sensor listener");
            notifyAvailabilityChanged(false);
            return;
        }

        // Reset processing components for clean state
        filterX.reset();
        filterY.reset();
        filterZ.reset();
        calculator.reset();

        isRunning = true;
        Log.d(TAG, "Accelerometer monitoring started");
        notifyAvailabilityChanged(true);
    }

    /**
     * Stops accelerometer monitoring and releases sensor resources.
     */
    public synchronized void stop() {
        if (!isRunning) {
            Log.w(TAG, "Not running");
            return;
        }

        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }

        isRunning = false;
        Log.d(TAG, "Accelerometer monitoring stopped");
        notifyAvailabilityChanged(false);
    }

    // =====================================================
    // SENSOR EVENT LISTENER IMPLEMENTATION
    // =====================================================

    /**
     * Called when new sensor data is available.
     * Executes on a system sensor thread (NOT main thread).
     *
     * <p><b>Processing pipeline:</b></p>
     * <ol>
     *   <li>Extract raw accelerometer values (m/s²)</li>
     *   <li>Apply Butterworth high-pass filter (removes gravity and <3.8Hz motion)</li>
     *   <li>Convert from m/s² to g's</li>
     *   <li>Feed to statistics calculator</li>
     *   <li>Notify listeners when window completes (every 5 seconds)</li>
     * </ol>
     */
    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_ACCELEROMETER) {
            return;  // Ignore other sensor types
        }

        // 1. Extract raw accelerometer values (in m/s²)
        float rawX = event.values[0];
        float rawY = event.values[1];
        float rawZ = event.values[2];

        // 2. Apply Butterworth high-pass filters (removes gravity + low frequencies)
        float filteredX = filterX.update(rawX);
        float filteredY = filterY.update(rawY);
        float filteredZ = filterZ.update(rawZ);

        // 3. Convert from m/s² to g's
        float gx = filteredX / STANDARD_GRAVITY;
        float gy = filteredY / STANDARD_GRAVITY;
        float gz = filteredZ / STANDARD_GRAVITY;

        // 4. Feed sample to statistics calculator
        calculator.addSample(gx, gy, gz);

        // 5. Check if window is complete (250 samples = 5 seconds)
        if (calculator.isWindowComplete()) {
            AccelStatistics stats = calculator.compute();
            calculator.reset();  // Start new window

            // 6. Notify listeners on main thread
            notifyStatistics(stats);
        }
    }

    /**
     * Called when sensor accuracy changes.
     */
    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        String accuracyStr;
        switch (accuracy) {
            case SensorManager.SENSOR_STATUS_ACCURACY_HIGH:
                accuracyStr = "HIGH";
                break;
            case SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM:
                accuracyStr = "MEDIUM";
                break;
            case SensorManager.SENSOR_STATUS_ACCURACY_LOW:
                accuracyStr = "LOW";
                break;
            case SensorManager.SENSOR_STATUS_UNRELIABLE:
                accuracyStr = "UNRELIABLE";
                Log.w(TAG, "Sensor accuracy is UNRELIABLE - data quality may be poor");
                break;
            default:
                accuracyStr = "UNKNOWN";
        }

        Log.d(TAG, "Sensor accuracy changed: " + accuracyStr);
    }

    // =====================================================
    // LISTENER MANAGEMENT
    // =====================================================

    /**
     * Adds a listener for accelerometer statistics updates.
     * Provides immediate notification of current availability state.
     *
     * @param listener The listener to add
     */
    public void addListener(AccelStatisticsListener listener) {
        if (listener == null) return;

        listeners.add(listener);

        // Provide immediate notification of current state
        final boolean currentAvailability = sensorAvailable && isRunning;
        mainHandler.post(() -> {
            try {
                listener.onAvailabilityChanged(currentAvailability);
            } catch (Throwable t) {
                Log.e(TAG, "Error in immediate availability notification", t);
            }
        });

        Log.d(TAG, "Listener added. Total listeners: " + listeners.size());
    }

    /**
     * Removes a listener from receiving updates.
     *
     * @param listener The listener to remove
     */
    public void removeListener(AccelStatisticsListener listener) {
        if (listener != null) {
            listeners.remove(listener);
            Log.d(TAG, "Listener removed. Total listeners: " + listeners.size());
        }
    }

    /**
     * Removes all registered listeners.
     */
    public void clearAllListeners() {
        int count = listeners.size();
        listeners.clear();
        Log.d(TAG, "Cleared " + count + " listener(s)");
    }

    // =====================================================
    // NOTIFICATION METHODS (Thread-Safe)
    // =====================================================

    /**
     * Notifies all listeners of new statistics (always on main thread).
     */
    private void notifyStatistics(AccelStatistics stats) {
        if (listeners.isEmpty()) return;

        mainHandler.post(() -> {
            for (AccelStatisticsListener listener : listeners) {
                try {
                    listener.onStatisticsComputed(stats);
                } catch (Throwable t) {
                    Log.e(TAG, "Listener threw exception in onStatisticsComputed", t);
                }
            }
        });
    }

    /**
     * Notifies all listeners of availability changes (always on main thread).
     */
    private void notifyAvailabilityChanged(boolean available) {
        if (listeners.isEmpty()) return;

        mainHandler.post(() -> {
            for (AccelStatisticsListener listener : listeners) {
                try {
                    listener.onAvailabilityChanged(available);
                } catch (Throwable t) {
                    Log.e(TAG, "Listener threw exception in onAvailabilityChanged", t);
                }
            }
        });
    }

    // =====================================================
    // PUBLIC API - State Queries
    // =====================================================

    /**
     * Checks if accelerometer sensor is available on device.
     *
     * @return true if sensor exists, false otherwise
     */
    public boolean isSensorAvailable() {
        return sensorAvailable;
    }

    /**
     * Checks if accelerometer monitoring is currently active.
     *
     * @return true if running, false otherwise
     */
    public boolean isRunning() {
        return isRunning;
    }

    /**
     * Gets detailed information about the accelerometer sensor.
     *
     * @return Formatted string with sensor specifications
     */
    public String getSensorInfo() {
        if (accelerometer == null) {
            return "No accelerometer available";
        }

        return String.format(Locale.US,
                "Sensor: %s\n" +
                        "Vendor: %s\n" +
                        "Version: %d\n" +
                        "Max Range: %.2f m/s²\n" +
                        "Resolution: %.6f m/s²\n" +
                        "Power: %.2f mA",
                accelerometer.getName(),
                accelerometer.getVendor(),
                accelerometer.getVersion(),
                accelerometer.getMaximumRange(),
                accelerometer.getResolution(),
                accelerometer.getPower()
        );
    }

    // =====================================================
    // PUBLIC API - Dynamic Configuration
    // =====================================================

    /**
     * Sets the peak detection threshold dynamically.
     * Useful for adjusting sensitivity based on vehicle type.
     *
     * @param thresholdG New threshold in g's (e.g., 1.5 for normal, 2.0 for less sensitive)
     */
    public void setPeakThreshold(float thresholdG) {
        calculator.setPeakThreshold(thresholdG);
        Log.d(TAG, String.format(Locale.US, "Peak threshold updated to %.2fg", thresholdG));
    }

    /**
     * Gets the current peak detection threshold.
     *
     * @return Current threshold in g's
     */
    public float getPeakThreshold() {
        return calculator.peakThreshold;
    }
}