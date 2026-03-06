package com.electro.dassify_application.helpers;  // ← Corregido (antes: package com.electro.dassify_application;)

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * BluetoothHelper - Manages Bluetooth Classic connection to ESP32.
 *
 * <p><b>Features:</b></p>
 * <ul>
 *   <li>Automatic device discovery (searches for "ESP32_Accel_Monitor")</li>
 *   <li>Time synchronization (sends Android timestamp to ESP32)</li>
 *   <li>Continuous packet reception (54-byte AccelPacket every 5s)</li>
 *   <li>Automatic reconnection (3 attempts with 5s delay)</li>
 * </ul>
 *
 * <p><b>Usage:</b></p>
 * <pre>
 * BluetoothHelper helper = new BluetoothHelper(context);
 * helper.addListener(listener);
 * helper.start();  // Connect to ESP32
 * ...
 * helper.stop();   // Disconnect
 * </pre>
 */
public class BluetoothHelper {

    private static final String TAG = "BluetoothHelper";

    // ═══════════════════════════════════════════════════════
    // Configuration
    // ═══════════════════════════════════════════════════════

    private static final String ESP32_DEVICE_NAME = "ESP32_Accel_Monitor";
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final int MAX_RECONNECT_ATTEMPTS = 3;
    private static final int RECONNECT_DELAY_MS = 5000;
    private static final int PACKET_SIZE = 54;

    // ═══════════════════════════════════════════════════════
    // Listener Interface
    // ═══════════════════════════════════════════════════════

    public interface BluetoothListener {
        /**
         * Called when Bluetooth device pairing status changes.
         */
        void onBluetoothAvailabilityChanged(boolean available);

        /**
         * Called when connection state changes.
         */
        void onConnectionStateChanged(boolean connected);

        /**
         * Called when accelerometer packet is received from ESP32.
         */
        void onAccelDataReceived(AccelPacket packet);

        /**
         * Called on errors.
         */
        void onError(String errorMessage);
    }

    // ═══════════════════════════════════════════════════════
    // State Management
    // ═══════════════════════════════════════════════════════

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean connected = new AtomicBoolean(false);

    private BluetoothSocket socket;
    private InputStream inputStream;
    private OutputStream outputStream;

    private Thread receiveThread;
    private ExecutorService executor;

    // ═══════════════════════════════════════════════════════
    // Dependencies
    // ═══════════════════════════════════════════════════════

    private final BluetoothAdapter bluetoothAdapter;
    private final Set<BluetoothListener> listeners = new CopyOnWriteArraySet<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ═══════════════════════════════════════════════════════
    // Constructor
    // ═══════════════════════════════════════════════════════

    public BluetoothHelper() {
        this.bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        if (bluetoothAdapter == null) {
            Log.e(TAG, "Bluetooth not supported on this device");
        }
    }

    // ═══════════════════════════════════════════════════════
    // Public API
    // ═══════════════════════════════════════════════════════

    public void addListener(BluetoothListener listener) {
        listeners.add(listener);
    }

    public void removeListener(BluetoothListener listener) {
        listeners.remove(listener);
    }

    public boolean isConnected() {
        return connected.get();
    }

    public boolean isBluetoothAvailable() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            return false;
        }
        return findESP32Device() != null;
    }

    /**
     * Start connection to ESP32.
     * Creates new executor if previous one was shut down.
     */
    public void start() {
        if (running.getAndSet(true)) {
            Log.w(TAG, "BluetoothHelper already running");
            return;
        }

        Log.d(TAG, "Starting BluetoothHelper...");

        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            notifyError("Bluetooth is disabled. Please enable it in settings.");
            notifyAvailability(false);
            running.set(false);
            return;
        }

        // Create new executor (previous one may have been shut down)
        if (executor == null || executor.isShutdown()) {
            executor = Executors.newCachedThreadPool();
            Log.d(TAG, "Created new ExecutorService");
        }

        // Start connection attempt in background
        executor.submit(this::attemptConnection);
    }

    /**
     * Stop connection and cleanup resources.
     */
    public void stop() {
        if (!running.getAndSet(false)) {
            return;  // Already stopped
        }

        Log.d(TAG, "Stopping BluetoothHelper...");

        disconnect();

        // Shutdown executor if exists
        if (executor != null && !executor.isShutdown()) {
            executor.shutdownNow();
            Log.d(TAG, "ExecutorService shut down");
        }

        notifyAvailability(false);
        notifyConnectionState(false);
    }

    // ═══════════════════════════════════════════════════════
    // Connection Management
    // ═══════════════════════════════════════════════════════

    private void attemptConnection() {
        for (int attempt = 1; attempt <= MAX_RECONNECT_ATTEMPTS; attempt++) {
            if (!running.get()) {
                Log.d(TAG, "Connection cancelled (not running)");
                return;
            }

            Log.d(TAG, String.format("Connection attempt %d/%d", attempt, MAX_RECONNECT_ATTEMPTS));

            try {
                BluetoothDevice device = findESP32Device();

                if (device == null) {
                    Log.w(TAG, "ESP32 device not found. Please pair it first.");
                    notifyError("ESP32 not found. Please pair 'ESP32_Accel_Monitor' in Bluetooth settings.");
                    notifyAvailability(false);
                    running.set(false);
                    return;
                }

                Log.d(TAG, "Found ESP32 device: " + device.getAddress());
                notifyAvailability(true);

                // Create RFCOMM socket
                socket = device.createRfcommSocketToServiceRecord(SPP_UUID);

                // Connect (blocking call)
                socket.connect();

                // Get streams
                inputStream = socket.getInputStream();
                outputStream = socket.getOutputStream();

                connected.set(true);
                notifyConnectionState(true);

                Log.d(TAG, "✓ Connected to ESP32!");

                // Send time synchronization
                sendTimeSynchronization();

                // Start receive loop
                startReceiveLoop();

                return;  // Success, exit retry loop

            } catch (IOException e) {
                Log.e(TAG, String.format("Connection attempt %d failed", attempt), e);

                disconnect();

                if (attempt < MAX_RECONNECT_ATTEMPTS) {
                    try {
                        Thread.sleep(RECONNECT_DELAY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        // All attempts failed
        Log.e(TAG, "Failed to connect after " + MAX_RECONNECT_ATTEMPTS + " attempts");
        notifyError("Unable to connect to ESP32 after " + MAX_RECONNECT_ATTEMPTS + " attempts");
        notifyConnectionState(false);
        running.set(false);
    }

    private void disconnect() {
        connected.set(false);

        if (receiveThread != null && receiveThread.isAlive()) {
            receiveThread.interrupt();
            receiveThread = null;
        }

        try { if (inputStream != null) inputStream.close(); } catch (IOException ignored) {}
        try { if (outputStream != null) outputStream.close(); } catch (IOException ignored) {}
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}

        inputStream = null;
        outputStream = null;
        socket = null;
    }

    private BluetoothDevice findESP32Device() {
        if (bluetoothAdapter == null) return null;

        try {
            Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();

            for (BluetoothDevice device : pairedDevices) {
                if (ESP32_DEVICE_NAME.equals(device.getName())) {
                    return device;
                }
            }
        } catch (SecurityException e) {
            Log.e(TAG, "Bluetooth permission not granted", e);
        }

        return null;
    }

    // ═══════════════════════════════════════════════════════
    // Time Synchronization
    // ═══════════════════════════════════════════════════════

    private void sendTimeSynchronization() {
        try {
            long currentTime = System.currentTimeMillis();

            // Send 8 bytes (uint64_t little-endian)
            ByteBuffer buffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
            buffer.putLong(currentTime);

            outputStream.write(buffer.array());
            outputStream.flush();

            Log.d(TAG, "✓ Time synchronized: " + currentTime);

        } catch (IOException e) {
            Log.e(TAG, "Failed to send time synchronization", e);
        }
    }

    // ═══════════════════════════════════════════════════════
    // Receive Loop
    // ═══════════════════════════════════════════════════════

    private void startReceiveLoop() {
        receiveThread = new Thread(() -> {
            Log.d(TAG, "Receive loop started");

            byte[] buffer = new byte[PACKET_SIZE];

            while (running.get() && connected.get() && !Thread.currentThread().isInterrupted()) {
                try {
                    // Read exactly 54 bytes (blocking)
                    int bytesRead = 0;
                    while (bytesRead < PACKET_SIZE) {
                        int read = inputStream.read(buffer, bytesRead, PACKET_SIZE - bytesRead);
                        if (read == -1) {
                            throw new IOException("End of stream reached");
                        }
                        bytesRead += read;
                    }

                    // Parse packet
                    AccelPacket packet = AccelPacket.fromBytes(buffer);

                    if (packet != null && packet.isValid()) {
                        notifyAccelData(packet);
                    } else {
                        Log.w(TAG, "Received invalid packet");
                    }

                } catch (IOException e) {
                    if (running.get() && connected.get()) {
                        Log.e(TAG, "Connection lost during receive", e);
                        connected.set(false);
                        notifyConnectionState(false);
                        notifyError("Connection lost to ESP32");

                        // Attempt reconnection
                        disconnect();
                        if (running.get()) {
                            executor.submit(this::attemptConnection);
                        }
                    }
                    break;
                }
            }

            Log.d(TAG, "Receive loop ended");
        }, "BluetoothReceiveThread");

        receiveThread.start();
    }

    // ═══════════════════════════════════════════════════════
    // Listener Notifications
    // ═══════════════════════════════════════════════════════

    private void notifyAvailability(boolean available) {
        mainHandler.post(() -> {
            for (BluetoothListener listener : listeners) {
                try {
                    listener.onBluetoothAvailabilityChanged(available);
                } catch (Exception e) {
                    Log.e(TAG, "Error in listener callback", e);
                }
            }
        });
    }

    private void notifyConnectionState(boolean connected) {
        mainHandler.post(() -> {
            for (BluetoothListener listener : listeners) {
                try {
                    listener.onConnectionStateChanged(connected);
                } catch (Exception e) {
                    Log.e(TAG, "Error in listener callback", e);
                }
            }
        });
    }

    private void notifyAccelData(@NonNull AccelPacket packet) {
        mainHandler.post(() -> {
            for (BluetoothListener listener : listeners) {
                try {
                    listener.onAccelDataReceived(packet);
                } catch (Exception e) {
                    Log.e(TAG, "Error in listener callback", e);
                }
            }
        });
    }

    private void notifyError(String message) {
        mainHandler.post(() -> {
            for (BluetoothListener listener : listeners) {
                try {
                    listener.onError(message);
                } catch (Exception e) {
                    Log.e(TAG, "Error in listener callback", e);
                }
            }
        });
    }
}