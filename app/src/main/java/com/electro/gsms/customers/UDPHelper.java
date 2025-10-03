package com.electro.gsms.customers;

import android.location.Location;
import android.net.Network;
import android.util.Log;

import androidx.annotation.NonNull;

import com.electro.gsms.services.TargetResolverManager;
import com.electro.gsms.services.GPSManager;
import com.electro.gsms.services.NetworkManager;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * UDPHelper
 * This class handles sending GPS data via UDP to a set of pre-resolved targets.
 * It listens to updates from:
 *  - GPSHelper: to receive location updates and GPS availability changes.
 *  - TargetResolverHelper: to receive resolved IP addresses and ports.
 *  - NetworkManager: to track network availability and bind UDP sockets to the active network.
 * Design Highlights:
 * 1) Does not directly read the TargetResolverHelper map; updates only via listeners.
 * 2) Uses a single-threaded ExecutorService to send UDP packets asynchronously.
 * 3) Maintains previous state snapshots to avoid redundant sends and log noise.
 * 4) Enforces safety: checks that GPS, targets, and network are available before sending.
 * 5) Truncates JSON payloads to fit within UDP maximum packet size limits.
 */
public class UDPHelper implements GPSManager.LocationListenerExternal,
        TargetResolverManager.TargetsListener,
        NetworkManager.NetworkListener {

    private static final String TAG = "UDPHelper";

    private static final int MAX_UDP_PACKET_SIZE = 512;  // Max bytes per UDP packet
    private static final int SOCKET_TIMEOUT_MS = 3000;   // Socket timeout in milliseconds

    // Last known GPS location (thread-safe)
    private volatile Location lastLocation = null;

    // Thread-safe snapshot of resolved targets: InetAddress -> Set of ports
    private final Map<java.net.InetAddress, Set<Integer>> targetMap = new HashMap<>();

    // Availability flags
    private volatile boolean gpsAvailable = false;
    private volatile boolean targetsAvailable = false;
    private volatile boolean networkAvailable = false;
    private volatile Network activeNetwork = null;

    // Executor for asynchronous UDP sending
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    // Previous state to avoid redundant logging and processing
    private final Map<java.net.InetAddress, Set<Integer>> lastTargetMap = new HashMap<>();
    private boolean lastTargetsAvailable = false;

    /**
     * Constructor registers this helper as listener to GPSHelper, TargetResolverHelper, and NetworkManager.
     */
    public UDPHelper(GPSManager gpsManager,
                     TargetResolverManager resolver,
                     NetworkManager networkManager) {

        if (gpsManager != null) gpsManager.addLocationListener(this);

        if (resolver != null) {
            resolver.addTargetsListener(this);
        }

        if (networkManager != null) {
            networkManager.addListener(this);
            if (networkManager.isNetworkAvailable()) {
                networkAvailable = true;
                activeNetwork = networkManager.getActiveNetwork();
            }
        }
    }

    // ---------------- GPSHelper callbacks ----------------

    @Override
    public void onNewLocation(Location location) {
        lastLocation = location; // Update last known GPS location
    }

    @Override
    public void onGPSAvailabilityChanged(boolean available) {
        gpsAvailable = available;
        Log.d(TAG, "GPS availability changed: " + available);
    }

    // ---------------- TargetResolverHelper callbacks ----------------

    @Override
    public void onTargetsUpdated(Map<java.net.InetAddress, Set<Integer>> newMap) {
        boolean changed = !mapsEqual(newMap, lastTargetMap);

        if (changed) {
            // Update current target map
            targetMap.clear();
            targetMap.putAll(newMap);

            // Update last target snapshot
            lastTargetMap.clear();
            for (Map.Entry<java.net.InetAddress, Set<Integer>> entry : newMap.entrySet()) {
                lastTargetMap.put(entry.getKey(), new HashSet<>(entry.getValue()));
            }

            targetsAvailable = !newMap.isEmpty();
            lastTargetsAvailable = targetsAvailable;

            Log.d(TAG, "Targets updated. Available=" + targetsAvailable + ", count=" + newMap.size());
        }
    }

    @Override
    public void onAvailabilityChanged(boolean available) {
        if (available != lastTargetsAvailable) {
            targetsAvailable = available;
            lastTargetsAvailable = available;
            Log.d(TAG, "Targets availability changed: " + available);
        }
    }

    // ---------------- NetworkManager callbacks ----------------

    @Override
    public void onNetworkAvailable(@NonNull Network network) {
        networkAvailable = true;
        activeNetwork = network;
        Log.d(TAG, "Network available: " + network + " | current targets=" + targetMap.size());
    }

    @Override
    public void onNetworkUnavailable() {
        networkAvailable = false;
        activeNetwork = null;
        Log.w(TAG, "Network lost. UDP sending paused.");
    }

    // ---------------- Public method for sending GPS ----------------

    /**
     * Sends the latest GPS location to all resolved targets via UDP.
     * Returns false if prerequisites are not met (GPS, network, targets).
     */
    public boolean sendGPSData() {
        if (lastLocation == null || !gpsAvailable || !targetsAvailable || !networkAvailable || activeNetwork == null) {
            Log.w(TAG, "Cannot send UDP: missing required data or network");
            return false;
        }

        if (targetMap.isEmpty()) {
            Log.w(TAG, "UDP send skipped: no targets available");
            return false;
        }

        // Capture snapshots for thread safety
        final Location locationToSend = lastLocation;
        final Map<java.net.InetAddress, Set<Integer>> targetsToSend = new HashMap<>();
        for (Map.Entry<java.net.InetAddress, Set<Integer>> entry : targetMap.entrySet()) {
            targetsToSend.put(entry.getKey(), new HashSet<>(entry.getValue()));
        }
        final Network network = activeNetwork;

        // Execute sending asynchronously
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

                network.bindSocket(socket); // Bind socket to the active network

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

    // ---------------- Internal helper methods ----------------

    /**
     * Build a JSON representation of the GPS location.
     */
    private byte[] buildJSON(Location location) {
        try {
            String jsonStr = String.format(java.util.Locale.US,
                    "{\"latitude\":%f,\"longitude\":%f,\"altitude\":%f,\"accuracy\":%f,\"timestamp\":%d}",
                    location.getLatitude(),
                    location.getLongitude(),
                    location.getAltitude(),
                    location.getAccuracy(),
                    location.getTime());
            return truncateMessage(jsonStr).getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.e(TAG, "Failed to build JSON for GPS", e);
            return null;
        }
    }

    /**
     * Truncate a string to fit within MAX_UDP_PACKET_SIZE bytes without splitting UTF-8 characters.
     */
    private String truncateMessage(String message) {
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= MAX_UDP_PACKET_SIZE) return message;

        int endIndex = MAX_UDP_PACKET_SIZE;
        while (endIndex > 0 && (bytes[endIndex] & 0xC0) == 0x80) endIndex--; // Avoid splitting multi-byte chars
        String truncated = new String(bytes, 0, endIndex, StandardCharsets.UTF_8);
        Log.w(TAG, "JSON truncated from " + bytes.length + " to " + endIndex + " bytes");
        return truncated;
    }

    /**
     * Send the UDP packet to all targets.
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

    /**
     * Compare two maps of InetAddress -> Set<Integer> for equality.
     */
    private boolean mapsEqual(Map<java.net.InetAddress, Set<Integer>> a,
                              Map<java.net.InetAddress, Set<Integer>> b) {
        if (a.size() != b.size()) return false;
        for (Map.Entry<java.net.InetAddress, Set<Integer>> entry : a.entrySet()) {
            Set<Integer> bPorts = b.get(entry.getKey());
            if (bPorts == null || !bPorts.equals(entry.getValue())) return false;
        }
        return true;
    }

    /**
     * Shutdown the helper: stops executor and removes all listeners.
     */
    public void shutdown(GPSManager gpsManager, TargetResolverManager resolver, NetworkManager networkManager) {
        executor.shutdownNow();
        if (gpsManager != null) gpsManager.removeLocationListener(this);
        if (resolver != null) resolver.removeTargetsListener(this);
        if (networkManager != null) networkManager.removeListener(this);
    }
}
