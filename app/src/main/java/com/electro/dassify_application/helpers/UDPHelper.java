package com.electro.dassify_application.helpers;

import android.location.Location;
import android.net.Network;
import android.util.Log;

import androidx.annotation.NonNull;

import com.electro.dassify_application.services.AccelerometerManager;
import com.electro.dassify_application.services.TargetResolverManager;
import com.electro.dassify_application.services.GPSManager;
import com.electro.dassify_application.services.NetworkManager;
import com.electro.dassify_application.services.DeviceIdentifierManager;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * UDPHelper - Handles UDP transmission of GPS + Accelerometer data to dynamically resolved targets.
 *
 * <p><b>Integration Strategy:</b></p>
 * <ul>
 *   <li>Event-driven mode: Accelerometer triggers synchronized GPS + Accel sends (now handled by GPSSender)</li>
 *   <li>Fallback mode: GPS-only sends when accelerometer unavailable</li>
 *   <li>Temporal synchronization: GPS captured at moment of accel window completion</li>
 * </ul>
 *
 * <p><b>Architecture:</b></p>
 * Uses internal anonymous listeners to avoid method name collisions between interfaces.
 *
 */
public class UDPHelper implements
        GPSManager.LocationListenerExternal,
        NetworkManager.NetworkListener {

    private static final String TAG = "UDPHelper";
    private static final int MAX_UDP_PACKET_SIZE = 512;
    private static final int SOCKET_TIMEOUT_MS = 3000;

    // Custom thread factory for named daemon threads
    private static final ThreadFactory THREAD_FACTORY = new ThreadFactory() {
        private final AtomicInteger count = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "UDPHelper-Sender-" + count.getAndIncrement());
            t.setDaemon(true);
            return t;
        }
    };

    // ═══════════════════════════════════════════════════════
    // State Management
    // ═══════════════════════════════════════════════════════

    private volatile Location lastLocation = null;
    private volatile boolean networkAvailable = false;
    private volatile boolean targetsAvailable = false;
    private volatile Network activeNetwork = null;

    // ═══════════════════════════════════════════════════════
    // Thread-safe Target Management
    // ═══════════════════════════════════════════════════════

    private final Map<java.net.InetAddress, Set<Integer>> targetMap = new ConcurrentHashMap<>();
    private final Object targetLock = new Object();

    // ═══════════════════════════════════════════════════════
    // Execution Management
    // ═══════════════════════════════════════════════════════

    private final ExecutorService executor = Executors.newSingleThreadExecutor(THREAD_FACTORY);
    private volatile boolean disposed = false;

    // ═══════════════════════════════════════════════════════
    // Dependencies
    // ═══════════════════════════════════════════════════════

    private final DeviceIdentifierManager identifierManager;

    /**
     * NOTE: We keep a reference to accelManager for informational purposes only.
     * GPSSender is responsible for registering/removing accelerometer listeners and
     * for calling sendDataTriggeredByAccel(...) when authorized.
     */
    private AccelerometerManager accelManager;

    /**
     * Constructs UDPHelper and registers listeners for all data sources.
     *
     * @param gpsManager GPS manager for location data
     * @param resolver Target resolver for UDP destinations
     * @param networkManager Network manager for connectivity
     * @param identifierManager Device identifier for message tagging
     * @param accelManager Accelerometer manager (can be null for GPS-only mode).
     *                     NOTE: UDPHelper no longer registers itself as an accel listener.
     */
    public UDPHelper(GPSManager gpsManager,
                     TargetResolverManager resolver,
                     NetworkManager networkManager,
                     DeviceIdentifierManager identifierManager,
                     AccelerometerManager accelManager) {

        this.identifierManager = identifierManager;

        // Register GPS listener (implements interface directly)
        if (gpsManager != null) {
            gpsManager.addLocationListener(this);
        }

        // Register Network listener (implements interface directly)
        if (networkManager != null) {
            networkManager.addListener(this);
        }

        // ═══════════════════════════════════════════════════════
        // Register Target Resolver listener (internal to avoid collision)
        // ═══════════════════════════════════════════════════════
        if (resolver != null) {
            resolver.addTargetsListener(targetsListener);
        }

        // ═══════════════════════════════════════════════════════
        // Accelerometer integration
        // NOTE: UDPHelper no longer listens directly to accelerometer.
        // GPSSender acts as gateway and calls sendDataTriggeredByAccel()
        // when authorized. This ensures user control over data transmission.
        // ═══════════════════════════════════════════════════════
        if (accelManager != null) {
            // Store reference for potential future use (debugging, info, etc.)
            this.accelManager = accelManager;
            Log.d(TAG, "UDPHelper initialized with accelerometer support (controlled by GPSSender)");
        } else {
            this.accelManager = null;
            Log.d(TAG, "UDPHelper: No accelerometer manager (GPS-only mode)");
        }

        Log.d(TAG, "UDPHelper initialized with GPS + Accelerometer integration (accelerometer listener managed externally)");
    }

    // ═══════════════════════════════════════════════════════
    // GPS Location Listener Implementation (Direct)
    // ═══════════════════════════════════════════════════════

    @Override
    public void onNewLocation(Location location) {
        try {
            if (disposed) return;
            lastLocation = location;
        } catch (Throwable t) {
            Log.e(TAG, "Error processing new location update", t);
        }
    }

    @Override
    public void onGPSAvailabilityChanged(boolean available) {
        try {
            if (disposed) return;
            Log.d(TAG, "GPS availability changed: " + available);
        } catch (Throwable t) {
            Log.e(TAG, "Error processing GPS availability change", t);
        }
    }

    // ═══════════════════════════════════════════════════════
    // Network Manager Listener Implementation (Direct)
    // ═══════════════════════════════════════════════════════

    @Override
    public void onNetworkAvailable(@NonNull Network network) {
        try {
            if (disposed) return;
            networkAvailable = true;
            activeNetwork = network;
            Log.d(TAG, "Network available: " + network + " | targets=" + targetMap.size());
        } catch (Throwable t) {
            Log.e(TAG, "Error processing network availability", t);
        }
    }

    @Override
    public void onNetworkUnavailable() {
        try {
            if (disposed) return;
            networkAvailable = false;
            activeNetwork = null;
            Log.w(TAG, "Network lost. UDP sending paused.");
        } catch (Throwable t) {
            Log.e(TAG, "Error processing network unavailability", t);
        }
    }

    // ═══════════════════════════════════════════════════════
    // Target Resolver Listener (Internal - Avoids name collision)
    // ═══════════════════════════════════════════════════════

    private final TargetResolverManager.TargetsListener targetsListener =
            new TargetResolverManager.TargetsListener() {

                @Override
                public void onTargetsUpdated(Map<java.net.InetAddress, Set<Integer>> newMap) {
                    try {
                        if (disposed) return;
                        synchronized (targetLock) {
                            targetMap.clear();
                            for (Map.Entry<java.net.InetAddress, Set<Integer>> entry : newMap.entrySet()) {
                                targetMap.put(entry.getKey(), new HashSet<>(entry.getValue()));
                            }
                            targetsAvailable = !newMap.isEmpty();
                        }
                        Log.d(TAG, "Targets updated. Available=" + targetsAvailable + ", count=" + newMap.size());
                    } catch (Throwable t) {
                        Log.e(TAG, "Error processing targets update", t);
                    }
                }

                @Override
                public void onAvailabilityChanged(boolean available) {
                    try {
                        if (disposed) return;
                        targetsAvailable = available;
                        Log.d(TAG, "Targets availability changed: " + available);
                    } catch (Throwable t) {
                        Log.e(TAG, "Error processing targets availability change", t);
                    }
                }
            };

    // NOTE: The previously-existing accelListener has been REMOVED as part of migration.
    // GPSSender must register the AccelerometerManager.AccelStatisticsListener and call
    // UDPHelper.sendDataTriggeredByAccel(...) when appropriate.

    // ═══════════════════════════════════════════════════════
    // Synchronized Send (Event-Driven by Accelerometer)
    // ═══════════════════════════════════════════════════════

    /**
     * Sends GPS + Accelerometer data triggered by accel window completion.
     *
     * <p><b>IMPORTANT - AUTHORIZATION:</b></p>
     * This method assumes the CALLER (GPSSender) has already validated:
     * - User authorization (sending flag)
     * - Mode appropriateness (STANDBY mode)
     * - Service availability (GPS, network, targets)
     * This method only handles TECHNICAL validations and transmission.
     *
     * @param accelStats Just-completed accelerometer statistics
     */
    public void sendDataTriggeredByAccel(
            @NonNull AccelerometerManager.AccelStatistics accelStats) {

        // ═══════════════════════════════════════════════════════
        // Technical validations only (caller handles authorization)
        // ═══════════════════════════════════════════════════════

        if (disposed) {
            Log.w(TAG, "Cannot send: UDPHelper is disposed");
            return;
        }

        final Location currentLocation = lastLocation;

        if (currentLocation == null) {
            Log.w(TAG, "⚠️ GPS location not available for synchronized send");
            return;
        }

        if (!targetsAvailable || !networkAvailable || activeNetwork == null) {
            Log.w(TAG, "⚠️ Network/targets not available for synchronized send");
            return;
        }

        // ═══════════════════════════════════════════════════════
        // Validate accelerometer window quality
        // ═══════════════════════════════════════════════════════
        if (accelStats.flags != 0) {
            Log.w(TAG, String.format(Locale.US,
                    "⚠️ Accel window has validation issues (flags=0x%02X), sending anyway",
                    accelStats.flags
            ));
        }

        // Log synchronization info
        long gpsAge = System.currentTimeMillis() - currentLocation.getTime();
        Log.d(TAG, String.format(Locale.US,
                "📍🎯 Synchronized send: GPS (age=%dms) + Accel (end=%d)",
                gpsAge, accelStats.timestampEnd
        ));

        // Thread-safe snapshots for async execution
        final Map<java.net.InetAddress, Set<Integer>> targetsToSend;
        synchronized (targetLock) {
            targetsToSend = new HashMap<>();
            for (Map.Entry<java.net.InetAddress, Set<Integer>> entry : targetMap.entrySet()) {
                targetsToSend.put(entry.getKey(), new HashSet<>(entry.getValue()));
            }
        }

        final Network network = activeNetwork;

        // Execute send asynchronously
        executor.execute(() -> {
            DatagramSocket socket = null;
            try {
                final byte[] buffer = buildJSON(currentLocation, accelStats);

                if (buffer == null || buffer.length == 0) {
                    Log.w(TAG, "UDP payload is empty, skipping send");
                    return;
                }

                if (buffer.length > MAX_UDP_PACKET_SIZE) {
                    Log.e(TAG, String.format(Locale.US,
                            "⚠️ Payload too large: %d bytes (max %d)",
                            buffer.length, MAX_UDP_PACKET_SIZE
                    ));
                }

                socket = new DatagramSocket();
                socket.setSoTimeout(SOCKET_TIMEOUT_MS);
                network.bindSocket(socket);

                sendPackets(socket, targetsToSend, buffer);

                Log.d(TAG, String.format(Locale.US,
                        "✓ UDP sent (synchronized): %d bytes to %d target(s)",
                        buffer.length, targetsToSend.size()
                ));

            } catch (Exception e) {
                Log.e(TAG, "Error sending synchronized UDP data", e);
            } finally {
                if (socket != null && !socket.isClosed()) {
                    socket.close();
                }
            }
        });
    }

    // ═══════════════════════════════════════════════════════
    // Fallback Send (GPS-only mode)
    // ═══════════════════════════════════════════════════════

    /**
     * Sends GPS-only data (FALLBACK mode).
     * Called by GPSSender when accelerometer not available.
     *
     * @return true if send was initiated successfully
     */
    public boolean sendGPSData() {
        if (disposed) {
            Log.w(TAG, "Cannot send: UDPHelper is disposed");
            return false;
        }

        final Location currentLocation = lastLocation;

        if (currentLocation == null) {
            Log.w(TAG, "Cannot send UDP: GPS location not available");
            return false;
        }

        if (!targetsAvailable || !networkAvailable || activeNetwork == null) {
            Log.w(TAG, "Cannot send UDP: network or targets not available");
            return false;
        }

        Log.d(TAG, "📍 Fallback send: GPS only");

        // Thread-safe snapshots for async execution
        final Map<java.net.InetAddress, Set<Integer>> targetsToSend;
        synchronized (targetLock) {
            targetsToSend = new HashMap<>();
            for (Map.Entry<java.net.InetAddress, Set<Integer>> entry : targetMap.entrySet()) {
                targetsToSend.put(entry.getKey(), new HashSet<>(entry.getValue()));
            }
        }

        final Network network = activeNetwork;

        executor.execute(() -> {
            DatagramSocket socket = null;
            try {
                final byte[] buffer = buildJSON(currentLocation, null);

                if (buffer == null || buffer.length == 0) {
                    Log.w(TAG, "UDP payload is empty, skipping send");
                    return;
                }

                socket = new DatagramSocket();
                socket.setSoTimeout(SOCKET_TIMEOUT_MS);
                network.bindSocket(socket);

                sendPackets(socket, targetsToSend, buffer);

                Log.d(TAG, String.format(Locale.US,
                        "✓ UDP sent (fallback): %d bytes to %d target(s)",
                        buffer.length, targetsToSend.size()
                ));

            } catch (Exception e) {
                Log.e(TAG, "Error sending fallback UDP data", e);
            } finally {
                if (socket != null && !socket.isClosed()) {
                    socket.close();
                }
            }
        });

        return true;
    }

    // ═══════════════════════════════════════════════════════
    // JSON Payload Construction
    // ═══════════════════════════════════════════════════════

    /**
     * Constructs JSON payload from Location and optional AccelStatistics.
     *
     * <p><b>JSON with accel:</b></p>
     * <pre>
     * {
     *   "device_id": "...",
     *   "timestamp": &lt;GPS_TIME&gt;,
     *   "latitude": ...,
     *   "longitude": ...,
     *   "altitude": ...,
     *   "accuracy": ...,
     *   "accel": { ts_start, ts_end, rms, max, peaks_count, sample_count, flags }
     * }
     * </pre>
     *
     * <p><b>JSON without accel (original format):</b></p>
     * <pre>
     * {
     *   "device_id": "...",
     *   "timestamp": &lt;GPS_TIME&gt;,
     *   "latitude": ...,
     *   "longitude": ...,
     *   "altitude": ...,
     *   "accuracy": ...
     * }
     * </pre>
     *
     * @param location GPS location (never null)
     * @param accelStats Accelerometer stats (can be null)
     * @return UTF-8 encoded JSON, or null if fails
     */
    private byte[] buildJSON(Location location,
                             AccelerometerManager.AccelStatistics accelStats) {
        try {
            String deviceId = identifierManager != null
                    ? identifierManager.getDeviceId()
                    : "unknown";

            long timestamp = location.getTime();
            double latitude = location.getLatitude();
            double longitude = location.getLongitude();
            double altitude = location.hasAltitude() ? location.getAltitude() : 0.0;
            float accuracy = location.getAccuracy();

            String jsonStr;

            if (accelStats != null) {
                // GPS + Accelerometer
                jsonStr = String.format(Locale.US,
                        "{" +
                                "\"device_id\":\"%s\"," +
                                "\"timestamp\":%d," +
                                "\"latitude\":%f," +
                                "\"longitude\":%f," +
                                "\"altitude\":%f," +
                                "\"accuracy\":%f," +
                                "\"accel\":{" +
                                "\"ts_start\":%d," +
                                "\"ts_end\":%d," +
                                "\"rms\":{\"x\":%.4f,\"y\":%.4f,\"z\":%.4f,\"mag\":%.4f}," +
                                "\"max\":{\"x\":%.4f,\"y\":%.4f,\"z\":%.4f,\"mag\":%.4f}," +
                                "\"peaks_count\":%d," +
                                "\"sample_count\":%d," +
                                "\"flags\":%d" +
                                "}" +
                                "}",
                        deviceId, timestamp,
                        latitude, longitude, altitude, accuracy,
                        accelStats.timestampStart, accelStats.timestampEnd,
                        accelStats.rmsX, accelStats.rmsY, accelStats.rmsZ, accelStats.rmsMagnitude,
                        accelStats.maxX, accelStats.maxY, accelStats.maxZ, accelStats.maxMagnitude,
                        accelStats.peakCount, accelStats.sampleCount, accelStats.flags
                );
            } else {
                // GPS only (ORIGINAL FORMAT)
                jsonStr = String.format(Locale.US,
                        "{" +
                                "\"device_id\":\"%s\"," +
                                "\"timestamp\":%d," +
                                "\"latitude\":%f," +
                                "\"longitude\":%f," +
                                "\"altitude\":%f," +
                                "\"accuracy\":%f" +
                                "}",
                        deviceId, timestamp,
                        latitude, longitude, altitude, accuracy
                );
            }

            String finalJson = truncateMessage(jsonStr);
            byte[] jsonBytes = finalJson.getBytes(StandardCharsets.UTF_8);

            Log.d(TAG, String.format(Locale.US,
                    "JSON payload: %d bytes (%s)",
                    jsonBytes.length,
                    accelStats != null ? "GPS + Accel" : "GPS only"
            ));

            return jsonBytes;

        } catch (Exception e) {
            Log.e(TAG, "Failed to build JSON payload", e);
            return null;
        }
    }

    /**
     * Truncates message to fit within UDP packet size limits.
     */
    private String truncateMessage(String message) {
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= MAX_UDP_PACKET_SIZE) return message;

        int endIndex = MAX_UDP_PACKET_SIZE;
        while (endIndex > 0 && (bytes[endIndex] & 0xC0) == 0x80) {
            endIndex--;
        }

        String truncated = new String(bytes, 0, endIndex, StandardCharsets.UTF_8);
        Log.w(TAG, "JSON truncated from " + bytes.length + " to " + endIndex + " bytes");
        return truncated;
    }

    /**
     * Sends UDP packets to all target addresses and ports.
     */
    private void sendPackets(DatagramSocket socket,
                             Map<java.net.InetAddress, Set<Integer>> targets,
                             byte[] buffer) throws Exception {
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        for (Map.Entry<java.net.InetAddress, Set<Integer>> entry : targets.entrySet()) {
            java.net.InetAddress ip = entry.getKey();
            for (Integer port : entry.getValue()) {
                packet.setAddress(ip);
                packet.setPort(port);
                socket.send(packet);
            }
        }
    }

    // ═══════════════════════════════════════════════════════
    // Lifecycle Management
    // ═══════════════════════════════════════════════════════

    /**
     * Graceful shutdown of UDPHelper resources.
     */
    public void shutdown(GPSManager gpsManager,
                         TargetResolverManager resolver,
                         NetworkManager networkManager) {

        if (disposed) {
            Log.w(TAG, "Already disposed");
            return;
        }
        disposed = true;

        Log.d(TAG, "Disposing UDPHelper resources");

        // Remove listeners
        if (gpsManager != null) {
            gpsManager.removeLocationListener(this);
        }

        if (resolver != null) {
            resolver.removeTargetsListener(targetsListener);
        }

        if (networkManager != null) {
            networkManager.removeListener(this);
        }

        // No longer needs to remove listener (GPSSender handles it now)
        // accelManager reference kept only for informational purposes
        if (this.accelManager != null) {
            // Clear the stored reference to allow GC and to signal external ownership
            Log.d(TAG, "Accelerometer reference cleared (listener managed by GPSSender)");
            this.accelManager = null;
        }

        // Shutdown executor
        executor.shutdown();
        try {
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                Log.w(TAG, "Executor forced shutdown");
            } else {
                Log.d(TAG, "Executor shutdown gracefully");
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
            Log.e(TAG, "Executor shutdown interrupted", e);
        }
    }
}
