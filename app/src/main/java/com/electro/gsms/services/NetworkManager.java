package com.electro.gsms.services;

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
 * Optimized NetworkManager
 * - Really validates Internet capability before marking network as available
 * - Applies debounce for rapid network changes to avoid unnecessary reconnections
 */
public class NetworkManager {

    private static final String TAG = "NetworkManager";

    public interface NetworkListener {
        void onNetworkAvailable(Network network);
        void onNetworkUnavailable();
    }

    private final CopyOnWriteArrayList<NetworkListener> listeners = new CopyOnWriteArrayList<>();
    private Network activeNetwork = null;
    private boolean networkAvailable = false;
    private ConnectivityManager.NetworkCallback networkCallback;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // Internal debounce
    private static final long DEBOUNCE_MS = 1000; // 1s
    private long lastChangeTimestamp = 0;

    public NetworkManager(Context context) {
        ConnectivityManager connectivityManager =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);

        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .build();

        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(@NonNull Network network) {
                long now = System.currentTimeMillis();
                if (now - lastChangeTimestamp < DEBOUNCE_MS) return;
                lastChangeTimestamp = now;

                // Check real Internet capability
                NetworkCapabilities caps = connectivityManager.getNetworkCapabilities(network);
                boolean hasInternet = caps != null &&
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);

                networkAvailable = hasInternet;
                if (hasInternet) activeNetwork = network;
                else activeNetwork = null;

                Log.d(TAG, "Cellular network available: " + network + " | hasInternet=" + hasInternet);

                mainHandler.post(() -> {
                    for (NetworkListener listener : listeners) {
                        if (hasInternet) listener.onNetworkAvailable(network);
                        else listener.onNetworkUnavailable();
                    }
                });
            }

            @Override
            public void onLost(@NonNull Network network) {
                long now = System.currentTimeMillis();
                if (now - lastChangeTimestamp < DEBOUNCE_MS) return;
                lastChangeTimestamp = now;

                if (activeNetwork != null && activeNetwork.equals(network)) {
                    activeNetwork = null;
                    networkAvailable = false;
                    Log.d(TAG, "Cellular network lost: " + network);

                    mainHandler.post(() -> {
                        for (NetworkListener listener : listeners) {
                            listener.onNetworkUnavailable();
                        }
                    });
                }
            }
        };

        connectivityManager.registerNetworkCallback(request, networkCallback);

        // Initial state
        Network initialNetwork = connectivityManager.getActiveNetwork();
        if (initialNetwork != null) {
            NetworkCapabilities caps = connectivityManager.getNetworkCapabilities(initialNetwork);
            boolean hasInternet = caps != null &&
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
            if (hasInternet) {
                activeNetwork = initialNetwork;
                networkAvailable = true;
                mainHandler.post(() -> {
                    for (NetworkListener listener : listeners) {
                        listener.onNetworkAvailable(initialNetwork);
                    }
                });
            }
        }
    }

    public boolean isNetworkAvailable() {
        return networkAvailable;
    }

    public Network getActiveNetwork() {
        return activeNetwork;
    }

    public void addListener(NetworkListener listener) {
        if (listener != null) listeners.add(listener);
    }

    public void removeListener(NetworkListener listener) {
        if (listener != null) listeners.remove(listener);
    }

    public void stopMonitoring(Context context) {
        if (networkCallback != null) {
            ConnectivityManager connectivityManager =
                    (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            connectivityManager.unregisterNetworkCallback(networkCallback);
            networkCallback = null;
            Log.d(TAG, "Network monitoring stopped and callback unregistered");
        }
        activeNetwork = null;
        networkAvailable = false;
    }
}
