package com.electro.dassify_application.services;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * NetworkManager — Monitors cellular network connectivity changes.
 * Features:
 * - Configurable debounce to filter rapid consecutive duplicate events.
 * - Smart debounce: only filters duplicate consecutive events of the same type.
 * - All notifications on main thread for UI safety.
 * - Try-catch protection for all listener notifications.
 * - Immediate state notification for new listeners.
 * - Memory leak prevention via ApplicationContext.
 * - Safe start/stop and null checks for extreme edge cases.
 *
 * ✅ TESTING MODE: Can allow WiFi temporarily via setTestingMode(true)
 */
public class NetworkManager {

    private static final String TAG = "NetworkManager";
    private static final long DEFAULT_DEBOUNCE_MS = 1000L;

    // ═══════════════════════════════════════════════════════════════════════════════════════
    // ✅ NUEVO: Flag para habilitar WiFi en testing (default: false = solo cellular)
    // ═══════════════════════════════════════════════════════════════════════════════════════
    private static volatile boolean ALLOW_WIFI_FOR_TESTING = false;

    /**
     * Interface for monitoring network connectivity state changes
     */
    public interface NetworkListener {
        /**
         * Called when a cellular network with internet becomes available
         * @param network The available network
         */
        void onNetworkAvailable(Network network);

        /**
         * Called when network becomes unavailable or loses internet connectivity
         */
        void onNetworkUnavailable();
    }

    private final ConnectivityManager connectivityManager;
    private final CopyOnWriteArrayList<NetworkListener> listeners = new CopyOnWriteArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final long debounceMs;

    private volatile boolean networkAvailable = false;
    private volatile Network activeNetwork = null;
    private volatile boolean monitoring = false;

    private long lastAvailableChange = 0;
    private long lastLostChange = 0;
    private ConnectivityManager.NetworkCallback networkCallback;

    // ---------------------------------------------------------------
    // Constructors
    // ---------------------------------------------------------------

    @SuppressLint("MissingPermission")
    public NetworkManager(@NonNull Context context) {
        this(context, DEFAULT_DEBOUNCE_MS);
    }

    @SuppressLint("MissingPermission")
    public NetworkManager(@NonNull Context context, long debounceMs) {
        Context appContext = context.getApplicationContext();
        this.debounceMs = Math.max(0, debounceMs);

        ConnectivityManager cm =
                (ConnectivityManager) appContext.getSystemService(Context.CONNECTIVITY_SERVICE);

        if (cm == null) {
            Log.e(TAG, "ConnectivityManager is null — monitoring disabled.");
            this.connectivityManager = null;
            return;
        }

        this.connectivityManager = cm;
        initNetworkCallback();
        startMonitoring();
    }

    // ---------------------------------------------------------------
    // Initialization
    // ---------------------------------------------------------------

    /**
     * Initializes the network callback to monitor connectivity changes
     *
     * ✅ MODIFICADO: Soporta WiFi cuando ALLOW_WIFI_FOR_TESTING = true
     */
    private void initNetworkCallback() {
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(@NonNull Network network) {
                long now = System.currentTimeMillis();
                // Debounce duplicate available events
                if (networkAvailable && (now - lastAvailableChange < debounceMs)) {
                    Log.d(TAG, "Debounced duplicate onAvailable()");
                    return;
                }
                lastAvailableChange = now;

                NetworkCapabilities caps = connectivityManager.getNetworkCapabilities(network);
                if (caps == null) {
                    Log.w(TAG, "Network capabilities null for: " + network);
                    return;
                }

                // ═══════════════════════════════════════════════════════════════════════════
                // ✅ MODIFICADO: Condicional para WiFi testing
                // ═══════════════════════════════════════════════════════════════════════════
                boolean hasInternet;
                String transportType;

                if (ALLOW_WIFI_FOR_TESTING) {
                    // ✅ TESTING MODE: Permite WiFi o Cellular
                    hasInternet =
                            (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) &&
                                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);

                    transportType = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ?
                            "WiFi" : "Cellular";

                    Log.d(TAG, "🔧 Testing mode: Network via " + transportType);
                } else {
                    // ❌ PRODUCTION MODE: Solo Cellular
                    hasInternet = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);

                    transportType = "Cellular";

                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        Log.d(TAG, "Production mode: Ignoring WiFi network");
                    }
                }

                networkAvailable = hasInternet;
                activeNetwork = hasInternet ? network : null;

                Log.d(TAG, String.format("Network available: %s | Transport: %s | hasInternet=%b | listeners=%d",
                        network, transportType, hasInternet, listeners.size()));

                notifyListeners(hasInternet, network);
            }

            @Override
            public void onLost(@NonNull Network network) {
                long now = System.currentTimeMillis();
                // Debounce duplicate lost events
                if (!networkAvailable && (now - lastLostChange < debounceMs)) {
                    Log.d(TAG, "Debounced duplicate onLost()");
                    return;
                }
                lastLostChange = now;

                // Only process if the lost network is the active one
                if (activeNetwork != null && activeNetwork.equals(network)) {
                    networkAvailable = false;
                    activeNetwork = null;

                    Log.d(TAG, "Network lost: " + network + " | listeners=" + listeners.size());

                    notifyListeners(false, null);
                }
            }
        };
    }

    /**
     * Starts monitoring network connectivity changes
     *
     * ✅ MODIFICADO: Registra WiFi cuando ALLOW_WIFI_FOR_TESTING = true
     */
    @SuppressLint("MissingPermission")
    private synchronized void startMonitoring() {
        if (connectivityManager == null || monitoring) return;

        try {
            // ═══════════════════════════════════════════════════════════════════════════
            // ✅ MODIFICADO: NetworkRequest adaptativo
            // ═══════════════════════════════════════════════════════════════════════════
            NetworkRequest.Builder builder = new NetworkRequest.Builder();

            if (ALLOW_WIFI_FOR_TESTING) {
                // ✅ TESTING MODE: Monitorear WiFi + Cellular
                builder.addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR);

                Log.i(TAG, "🔧 Network monitoring started: WiFi + Cellular (TESTING MODE)");
            } else {
                // ❌ PRODUCTION MODE: Solo Cellular
                builder.addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR);

                Log.i(TAG, "Network monitoring started: Cellular only (PRODUCTION MODE)");
            }

            NetworkRequest request = builder
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build();

            connectivityManager.registerNetworkCallback(request, networkCallback);
            monitoring = true;

            // Capture initial network state
            captureInitialState();

        } catch (Exception e) {
            Log.e(TAG, "Error starting network monitoring", e);
        }
    }

    /**
     * Captures the initial network state when monitoring starts
     */
    private void captureInitialState() {
        try {
            Network initialNetwork = connectivityManager.getActiveNetwork();
            if (initialNetwork != null) {
                NetworkCapabilities caps = connectivityManager.getNetworkCapabilities(initialNetwork);

                boolean hasInternet;

                if (ALLOW_WIFI_FOR_TESTING) {
                    hasInternet = caps != null &&
                            (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) &&
                            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
                } else {
                    hasInternet = caps != null &&
                            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
                }

                if (hasInternet) {
                    activeNetwork = initialNetwork;
                    networkAvailable = true;

                    String transport = caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ?
                            "WiFi" : "Cellular";
                    Log.d(TAG, "Initial network detected: " + initialNetwork + " (" + transport + ")");

                    // Only notify if there are active listeners
                    if (!listeners.isEmpty()) {
                        notifyListeners(true, initialNetwork);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error capturing initial state", e);
        }
    }

    // ---------------------------------------------------------------
    // Notification Handling
    // ---------------------------------------------------------------

    /**
     * Notifies all registered listeners of network state changes
     * @param available Whether network is available
     * @param network The network object (null if unavailable)
     */
    private void notifyListeners(boolean available, Network network) {
        if (listeners.isEmpty()) return;

        mainHandler.post(() -> {
            for (NetworkListener listener : listeners) {
                try {
                    if (available) {
                        listener.onNetworkAvailable(network);
                    } else {
                        listener.onNetworkUnavailable();
                    }
                } catch (Throwable t) {
                    Log.e(TAG, "Listener threw exception", t);
                }
            }
        });
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════
    // ✅ NUEVO: Método público para cambiar modo en runtime
    // ═══════════════════════════════════════════════════════════════════════════════════════

    /**
     * Enables or disables WiFi for testing purposes.
     *
     * ⚠️ WARNING: This requires restarting network monitoring to take effect.
     *
     * Usage:
     *   NetworkManager.setTestingMode(true);   // Enable WiFi
     *   networkManager.stopMonitoring();
     *   // Recreate NetworkManager or restart monitoring
     *
     * @param allowWifi true to allow WiFi (testing), false for cellular only (production)
     */
    public static void setTestingMode(boolean allowWifi) {
        boolean changed = (ALLOW_WIFI_FOR_TESTING != allowWifi);
        ALLOW_WIFI_FOR_TESTING = allowWifi;

        if (changed) {
            Log.w(TAG, String.format("🔧 Testing mode %s: WiFi %s",
                    allowWifi ? "ENABLED" : "DISABLED",
                    allowWifi ? "allowed" : "blocked"));
        }
    }

    /**
     * Gets current testing mode state.
     *
     * @return true if WiFi is allowed (testing mode), false if cellular only (production)
     */
    public static boolean isTestingMode() {
        return ALLOW_WIFI_FOR_TESTING;
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════

    // ---------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------

    /**
     * Stops network monitoring and cleans up resources
     */
    public synchronized void stopMonitoring() {
        if (!monitoring || connectivityManager == null || networkCallback == null) return;

        try {
            connectivityManager.unregisterNetworkCallback(networkCallback);
            monitoring = false;
            networkAvailable = false;
            activeNetwork = null;
            Log.d(TAG, "Network monitoring stopped");
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Callback already unregistered", e);
        } catch (Exception e) {
            Log.e(TAG, "Error stopping network monitoring", e);
        }
    }

    /**
     * Checks if network is currently available with internet access
     * @return true if cellular network with internet is available
     */
    @SuppressWarnings("unused")
    public boolean isNetworkAvailable() {
        return networkAvailable;
    }

    /**
     * Gets the currently active network
     * @return The active Network or null if unavailable
     */
    @SuppressWarnings("unused")
    public Network getActiveNetwork() {
        return activeNetwork;
    }

    /**
     * Checks if network monitoring is currently active
     * @return true if monitoring is enabled
     */
    @SuppressWarnings("unused")
    public boolean isMonitoring() {
        return monitoring;
    }

    /**
     * Adds a listener for network availability changes
     * @param listener The listener to add
     */
    public void addListener(NetworkListener listener) {
        if (listener == null) return;

        listeners.add(listener);

        // Immediate notification if network is already available
        if (networkAvailable && activeNetwork != null) {
            mainHandler.post(() -> {
                try {
                    listener.onNetworkAvailable(activeNetwork);
                } catch (Throwable t) {
                    Log.e(TAG, "Error in immediate notification", t);
                }
            });
        }
    }

    /**
     * Removes a network availability listener
     * @param listener The listener to remove
     */
    public void removeListener(NetworkListener listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    /**
     * Removes all registered network listeners
     */
    @SuppressWarnings("unused")
    public void clearAllListeners() {
        listeners.clear();
    }
}