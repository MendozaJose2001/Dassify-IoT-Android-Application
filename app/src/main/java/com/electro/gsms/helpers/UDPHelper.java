package com.electro.gsms.helpers;

import android.location.Location;
import android.net.Network;
import android.util.Log;

import androidx.annotation.NonNull;

import com.electro.gsms.services.TargetResolverManager;
import com.electro.gsms.services.GPSManager;
import com.electro.gsms.services.NetworkManager;
import com.electro.gsms.services.DeviceIdentifierManager;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
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
 * UDPHelper - Handles UDP transmission of GPS data to dynamically resolved targets.
 * Integrates GPS location data, network connectivity, target discovery, and device identification
 * to provide reliable UDP communication for GPS tracking data.
 * Implements listeners for GPS updates, target resolution, and network connectivity changes.
 */
public class UDPHelper implements
        GPSManager.LocationListenerExternal,
        TargetResolverManager.TargetsListener,
        NetworkManager.NetworkListener {

    private static final String TAG = "UDPHelper";
    private static final int MAX_UDP_PACKET_SIZE = 512; // Maximum UDP packet size in bytes
    private static final int SOCKET_TIMEOUT_MS = 3000; // Socket timeout in milliseconds

    // Custom thread factory for named daemon threads with sequential numbering
    private static final ThreadFactory THREAD_FACTORY = new ThreadFactory() {
        private final AtomicInteger count = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "UDPHelper-Sender-" + count.getAndIncrement());
            t.setDaemon(true); // Daemon threads won't prevent JVM shutdown
            return t;
        }
    };

    // Location and state management
    private volatile Location lastLocation = null;

    // Thread-safe data structures for concurrent access
    private final Map<java.net.InetAddress, Set<Integer>> targetMap = new ConcurrentHashMap<>();
    private final Object targetLock = new Object(); // Synchronization lock for target operations

    // System availability flags (volatile for thread visibility)
    private volatile boolean gpsAvailable = false;
    private volatile boolean targetsAvailable = false;
    private volatile boolean networkAvailable = false;
    private volatile Network activeNetwork = null;

    // Executor for asynchronous UDP transmission
    private final ExecutorService executor = Executors.newSingleThreadExecutor(THREAD_FACTORY);

    // Lifecycle management
    private volatile boolean disposed = false;

    // Device identification service
    private final DeviceIdentifierManager identifierManager;

    /**
     * Constructs a new UDPHelper and registers as listener for GPS, target resolution, and network events.
     *
     * @param gpsManager Provides GPS location data and availability updates
     * @param resolver Discovers and manages UDP target addresses and ports
     * @param networkManager Monitors network connectivity and provides active network bindings
     * @param identifierManager Provides unique device identification for message tagging
     */
    public UDPHelper(GPSManager gpsManager,
                     TargetResolverManager resolver,
                     NetworkManager networkManager,
                     DeviceIdentifierManager identifierManager) {

        this.identifierManager = identifierManager;

        // Register as listener for all relevant services
        if (gpsManager != null) gpsManager.addLocationListener(this);
        if (resolver != null) resolver.addTargetsListener(this);
        if (networkManager != null) networkManager.addListener(this);

        Log.d(TAG, "UDPHelper initialized with device identification");
    }

    // ---------------------------------------------------------------
    // GPS Location Listener Implementation
    // ---------------------------------------------------------------

    /**
     * Called when a new GPS location is available.
     * Updates the last known location for subsequent UDP transmissions.
     *
     * @param location The new location data received from GPS
     */
    @Override
    public void onNewLocation(Location location) {
        try {
            if (disposed) return;
            lastLocation = location;
        } catch (Throwable t) {
            Log.e(TAG, "Error processing new location update", t);
        }
    }

    /**
     * Called when GPS availability changes (enabled/disabled).
     * Updates internal GPS availability state.
     *
     * @param available True if GPS is available, false otherwise
     */
    @Override
    public void onGPSAvailabilityChanged(boolean available) {
        try {
            if (disposed) return;
            gpsAvailable = available;
            Log.d(TAG, "GPS availability changed: " + available);
        } catch (Throwable t) {
            Log.e(TAG, "Error processing GPS availability change", t);
        }
    }

    // ---------------------------------------------------------------
    // Target Resolver Listener Implementation
    // ---------------------------------------------------------------

    /**
     * Called when the target map is updated with new addresses and ports.
     * Creates a thread-safe copy of the target map for UDP transmission.
     *
     * @param newMap Updated map of InetAddress to port sets for UDP targets
     */
    @Override
    public void onTargetsUpdated(Map<java.net.InetAddress, Set<Integer>> newMap) {
        try {
            if (disposed) return;
            synchronized (targetLock) {
                targetMap.clear();
                // Create immutable copies of port sets for thread safety
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

    /**
     * Called when overall target availability changes.
     * Updates the targets availability state.
     *
     * @param available True if targets are available, false otherwise
     */
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

    // ---------------------------------------------------------------
    // Network Manager Listener Implementation
    // ---------------------------------------------------------------

    /**
     * Called when network connectivity becomes available.
     * Updates network state and stores the active network for socket binding.
     *
     * @param network The available network interface for UDP communication
     */
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

    /**
     * Called when network connectivity is lost.
     * Updates network state and clears the active network reference.
     */
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

    // ---------------------------------------------------------------
    // UDP Transmission Methods
    // ---------------------------------------------------------------

    /**
     * Initiates UDP transmission of current GPS data to all resolved targets.
     * Validates system state and availability before sending.
     *
     * @return true if transmission was initiated successfully, false if conditions aren't met
     */
    public boolean sendGPSData() {
        if (disposed) {
            Log.w(TAG, "Cannot send: UDPHelper is disposed");
            return false;
        }

        // Validate all required components are available
        if (lastLocation == null || !gpsAvailable || !targetsAvailable || !networkAvailable || activeNetwork == null) {
            Log.w(TAG, "Cannot send UDP: missing required data or network");
            return false;
        }

        final Location locationToSend = lastLocation;
        final Map<java.net.InetAddress, Set<Integer>> targetsToSend;

        // Create thread-safe copy of current targets
        synchronized (targetLock) {
            targetsToSend = new HashMap<>();
            for (Map.Entry<java.net.InetAddress, Set<Integer>> entry : targetMap.entrySet()) {
                targetsToSend.put(entry.getKey(), new HashSet<>(entry.getValue()));
            }
        }

        final Network network = activeNetwork;

        // Execute UDP transmission asynchronously
        executor.execute(() -> {
            DatagramSocket socket = null;
            try {
                final byte[] buffer = buildJSON(locationToSend);
                if (buffer == null || buffer.length == 0) {
                    Log.w(TAG, "UDP payload is empty, skipping send");
                    return;
                }

                socket = new DatagramSocket();
                socket.setSoTimeout(SOCKET_TIMEOUT_MS);
                network.bindSocket(socket); // Bind to the active network

                sendPackets(socket, targetsToSend, buffer);

            } catch (Exception e) {
                Log.e(TAG, "Error sending UDP data", e);
            } finally {
                if (socket != null && !socket.isClosed()) {
                    socket.close();
                    Log.d(TAG, "UDP socket closed safely");
                }
            }
        });

        return true;
    }

    // ---------------------------------------------------------------
    // Internal Helper Methods
    // ---------------------------------------------------------------

    /**
     * Constructs JSON payload from Location data including device identification.
     * Formats location data with device ID for unique identification at receiver.
     * Timestamp is converted to Unix epoch seconds for ISO 8601 compatibility.
     *
     * @param location The location data to include in JSON payload
     * @return UTF-8 encoded byte array of JSON payload, or null if construction fails
     */
    private byte[] buildJSON(Location location) {
        try {
            // Retrieve device ID from identifier manager, fallback to "unknown"
            String deviceId = identifierManager != null ? identifierManager.getDeviceId() : "unknown";
            long timestampSeconds = location.getTime() / 1000;

            // Construct JSON with device_id as primary identifier
            String jsonStr = String.format(java.util.Locale.US,
                    "{\"device_id\":\"%s\"," +
                            "\"latitude\":%f," +
                            "\"longitude\":%f," +
                            "\"altitude\":%f," +
                            "\"accuracy\":%f," +
                            "\"timestamp\":%d}",
                    deviceId,
                    location.getLatitude(),
                    location.getLongitude(),
                    location.getAltitude(),
                    location.getAccuracy(),
                    timestampSeconds);

            return truncateMessage(jsonStr).getBytes(StandardCharsets.UTF_8);

        } catch (Exception e) {
            Log.e(TAG, "Failed to build JSON for GPS data", e);
            return null;
        }
    }

    /**
     * Truncates message to fit within UDP packet size limits while preserving UTF-8 character boundaries.
     * Prevents packet fragmentation and ensures valid UTF-8 encoding.
     *
     * @param message The original message to potentially truncate
     * @return Truncated message that fits within MAX_UDP_PACKET_SIZE, preserving UTF-8 characters
     */
    private String truncateMessage(String message) {
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= MAX_UDP_PACKET_SIZE) return message;

        // Find safe truncation point that doesn't break UTF-8 multi-byte sequences
        int endIndex = MAX_UDP_PACKET_SIZE;
        while (endIndex > 0 && (bytes[endIndex] & 0xC0) == 0x80) {
            endIndex--; // Move back until start of UTF-8 character
        }

        String truncated = new String(bytes, 0, endIndex, StandardCharsets.UTF_8);
        Log.w(TAG, "JSON truncated from " + bytes.length + " to " + endIndex + " bytes");
        return truncated;
    }

    /**
     * Sends UDP packets to all target addresses and ports.
     * Iterates through target map and sends packet to each address:port combination.
     *
     * @param socket The DatagramSocket to use for transmission
     * @param targets Map of target addresses and their associated ports
     * @param buffer The data buffer to send in each packet
     * @throws Exception If socket transmission fails
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

    // ---------------------------------------------------------------
    // Lifecycle Management
    // ---------------------------------------------------------------

    /**
     * Performs graceful shutdown of UDPHelper resources.
     * Removes listeners, shuts down executor, and marks instance as disposed.
     * DeviceIdentifierManager requires no explicit cleanup.
     *
     * @param gpsManager GPS manager to remove location listener from
     * @param resolver Target resolver to remove targets listener from
     * @param networkManager Network manager to remove network listener from
     */
    public void shutdown(GPSManager gpsManager,
                         TargetResolverManager resolver,
                         NetworkManager networkManager) {
        if (disposed) {
            Log.w(TAG, "Already disposed, ignoring shutdown request");
            return;
        }
        disposed = true;

        Log.d(TAG, "Disposing UDPHelper resources");

        // Remove all listeners first to prevent callbacks during shutdown
        if (gpsManager != null) gpsManager.removeLocationListener(this);
        if (resolver != null) resolver.removeTargetsListener(this);
        if (networkManager != null) networkManager.removeListener(this);

        // Graceful executor shutdown with timeout
        executor.shutdown();
        try {
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                Log.w(TAG, "Executor forced shutdown after timeout");
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