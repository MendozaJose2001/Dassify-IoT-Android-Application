package com.electro.gsms.services;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * TargetResolverHelper
 * This class handles the asynchronous resolution of target hosts specified in a JSON file.
 * It maintains a map of resolved InetAddress -> Set of ports and notifies registered listeners
 * about updates and availability changes on the main thread.
 * Key responsibilities:
 * - Periodically resolve hosts from "udp_targets.json" into InetAddress objects.
 * - Maintain an internal thread-safe mapping of IP addresses to ports.
 * - Provide a listener-based interface to notify consumers on the main thread about updates.
 * Design Highlights:
 * 1) The internal map is private; consumers must register a listener to get updates.
 *    This ensures a single source of truth and avoids unsafe external access.
 * 2) All listener callbacks are posted to the main thread to simplify UI updates.
 * 3) Periodic resolution uses a dedicated Runnable reference to allow precise removal on stop().
 * 4) ExecutorService is lazily created in start() and can be safely stopped/recreated.
 * 5) requestResolveNow() allows immediate resolution outside the regular interval.
 * Original behaviors like JSON parsing, validation, and API23 compatibility are preserved.
 */
public class TargetResolverManager {

    private static final String TAG = "TargetResolverHelper";

    // Regular expression patterns for validating IP addresses and hostnames
    private static final Pattern IPV4_PATTERN = Pattern.compile(
            "^(([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])\\.){3}" +
                    "([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])$"
    );

    private static final Pattern IPV6_PATTERN = Pattern.compile(
            "^([0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}$|" +
                    "^([0-9a-fA-F]{1,4}:){1,7}:$|" +
                    "^:([0-9a-fA-F]{1,4}:){1,7}$|" +
                    "^([0-9a-fA-F]{1,4}:){1,6}:[0-9a-fA-F]{1,4}$|" +
                    "^([0-9a-fA-F]{1,4}:){1,5}(:[0-9a-fA-F]{1,4}){1,2}$|" +
                    "^([0-9a-fA-F]{1,4}:){1,4}(:[0-9a-fA-F]{1,4}){1,3}$|" +
                    "^([0-9a-fA-F]{1,4}:){1,3}(:[0-9a-fA-F]{1,4}){1,4}$|" +
                    "^([0-9a-fA-F]{1,4}:){1,2}(:[0-9a-fA-F]{1,4}){1,5}$|" +
                    "^[0-9a-fA-F]{1,4}:(:[0-9a-fA-F]{1,4}){1,6}$|" +
                    "^:(:[0-9a-fA-F]{1,4}){1,7}|:$|" +
                    "^([0-9a-fA-F]{1,4}:){6}" + IPV4_PATTERN.pattern() + "$"
    );

    private static final Pattern HOSTNAME_PATTERN = Pattern.compile("^[a-zA-Z0-9.-]+$");

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());

    // Thread-safe map of resolved targets: InetAddress -> Set of ports
    // All read/write access must be synchronized on "this"
    private final Map<InetAddress, Set<Integer>> resolvedTargetsMap = new HashMap<>();

    // ExecutorService used to resolve hosts asynchronously; lazily created to support stop/start cycles
    private ExecutorService executor = null;

    // Thread-safe set of listeners; CopyOnWriteArraySet allows safe iteration during concurrent modifications
    private final Set<TargetsListener> listeners = new CopyOnWriteArraySet<>();

    // Control flags for periodic resolution
    private volatile boolean running = false;
    private volatile long intervalMs = 0L;
    private Runnable periodicRunnable = null;

    // Atomic flag to prevent overlapping resolution tasks
    private final AtomicBoolean isResolving = new AtomicBoolean(false);

    // Cached JSON targets loaded from assets at construction
    private final List<JSONObject> jsonTargets = new ArrayList<>();

    // Last known availability state to prevent redundant notifications
    private volatile boolean lastAvailability = false;

    /**
     * Interface for consumers to receive updates.
     * All callbacks are guaranteed to be executed on the main thread.
     */
    public interface TargetsListener {
        void onTargetsUpdated(Map<InetAddress, Set<Integer>> targets);
        void onAvailabilityChanged(boolean available);
    }

    public TargetResolverManager(Context context) {
        this.context = context;
        loadJsonTargets(); // Load targets from JSON file into memory at construction
    }

    /**
     * Loads "udp_targets.json" from the assets folder into memory.
     * Preserves original behavior; logs errors if JSON cannot be loaded.
     */
    private void loadJsonTargets() {
        try (InputStream is = context.getAssets().open("udp_targets.json")) {
            StringBuilder sb = new StringBuilder();
            byte[] buffer = new byte[1024];
            int read;
            while ((read = is.read(buffer)) != -1) {
                sb.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
            }
            JSONArray targetsArray = new JSONObject(sb.toString()).getJSONArray("targets");
            for (int i = 0; i < targetsArray.length(); i++) {
                jsonTargets.add(targetsArray.getJSONObject(i));
            }
            Log.d(TAG, "Loaded " + jsonTargets.size() + " targets into memory");
        } catch (Exception e) {
            Log.e(TAG, "Failed to load udp_targets.json", e);
        }
    }

    /**
     * Registers a listener and immediately provides the current snapshot on the main thread.
     * Subsequent updates are delivered as they occur.
     */
    public void addTargetsListener(TargetsListener listener) {
        if (listener == null) return;

        listeners.add(listener);

        final Map<InetAddress, Set<Integer>> snapshot = copyResolvedMap();
        final boolean currentAvailability = !snapshot.isEmpty();
        lastAvailability = currentAvailability;

        // Ensure initial callbacks happen on main thread for safe UI updates
        handler.post(() -> {
            try {
                listener.onTargetsUpdated(snapshot);
            } catch (Throwable t) {
                Log.w(TAG, "Listener threw in onTargetsUpdated", t);
            }
            try {
                listener.onAvailabilityChanged(currentAvailability);
            } catch (Throwable t) {
                Log.w(TAG, "Listener threw in onAvailabilityChanged", t);
            }
        });
    }

    public void removeTargetsListener(TargetsListener listener) {
        if (listener == null) return;
        listeners.remove(listener);
    }

    /**
     * Private helper to create a deep copy of the resolved targets map.
     * Ensures that listeners receive a snapshot that cannot be modified externally.
     */
    private synchronized Map<InetAddress, Set<Integer>> copyResolvedMap() {
        Map<InetAddress, Set<Integer>> copy = new HashMap<>();
        for (Map.Entry<InetAddress, Set<Integer>> entry : resolvedTargetsMap.entrySet()) {
            copy.put(entry.getKey(), new HashSet<>(entry.getValue()));
        }
        return copy;
    }

    /**
     * Starts periodic resolution with the specified interval.
     * If already running, this method is a no-op.
     * ExecutorService is lazily created to support repeated stop/start cycles.
     */
    public synchronized void start(long intervalMs) {
        if (running) return;
        this.intervalMs = intervalMs;
        running = true;

        if (executor == null || executor.isShutdown() || executor.isTerminated()) {
            executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
        }

        periodicRunnable = new Runnable() {
            @Override
            public void run() {
                if (!running) return;
                resolveTargetsAsync();
                handler.postDelayed(this, TargetResolverManager.this.intervalMs);
            }
        };

        handler.post(periodicRunnable); // Schedule first run immediately
    }

    /**
     * Stops periodic resolution and shuts down the executor if running.
     * Only removes this class's periodic runnable to avoid affecting other Handler messages.
     */
    public synchronized void stop() {
        running = false;

        if (periodicRunnable != null) {
            handler.removeCallbacks(periodicRunnable);
            periodicRunnable = null;
        }

        if (executor != null && !executor.isShutdown()) {
            try {
                executor.shutdownNow();
            } catch (Exception e) {
                Log.w(TAG, "Executor shutdown failed", e);
            }
            executor = null; // allow lazy recreation in start()
        }
    }

    /**
     * Requests an immediate asynchronous resolution outside of the periodic schedule.
     * Does nothing if the resolver is not running.
     */
    public void requestResolveNow() {
        if (!running) {
            Log.d(TAG, "requestResolveNow called but resolver is not running");
            return;
        }
        resolveTargetsAsync();
    }

    /**
     * Asynchronously resolves all hosts using the executor service.
     * Ensures only one resolution runs at a time using an AtomicBoolean.
     */
    private void resolveTargetsAsync() {
        if (!isResolving.compareAndSet(false, true)) return;

        try {
            final ExecutorService localExecutor = this.executor;
            if (localExecutor == null || localExecutor.isShutdown()) {
                Log.w(TAG, "Executor is not available for resolving");
                isResolving.set(false);
                return;
            }

            localExecutor.submit(() -> {
                final Map<InetAddress, Set<Integer>> tempMap = new HashMap<>();
                boolean hasTargets;

                try {
                    List<Future<?>> futures = new ArrayList<>();

                    for (JSONObject target : jsonTargets) {
                        final String host = target.getString("ip");
                        final int port = target.getInt("port");

                        if (port <= 0 || port > 65535) continue;
                        if (isInvalidIP(host) && isInvalidHost(host)) continue;

                        futures.add(localExecutor.submit(() -> {
                            try {
                                InetAddress address = InetAddress.getByName(host);
                                synchronized (tempMap) {
                                    Set<Integer> ports = tempMap.get(address);
                                    if (ports == null) {
                                        ports = new HashSet<>();
                                        tempMap.put(address, ports);
                                    }
                                    ports.add(port);
                                }
                            } catch (Exception e) {
                                Log.w(TAG, "Failed to resolve host: " + host + " -> " + e.getMessage());
                            }
                        }));
                    }

                    for (Future<?> f : futures) {
                        try { f.get(); } catch (Exception ignored) {}
                    }

                    hasTargets = !tempMap.isEmpty();

                } catch (Exception e) {
                    Log.e(TAG, "Unexpected error during resolution", e);
                    hasTargets = false;
                }

                // Swap the map and notify listeners if changes occurred
                synchronized (TargetResolverManager.this) {
                    boolean changedList = !resolvedTargetsMap.equals(tempMap);

                    if (changedList) {
                        resolvedTargetsMap.clear();
                        resolvedTargetsMap.putAll(tempMap);

                        final Map<InetAddress, Set<Integer>> snapshot = copyResolvedMap();

                        handler.post(() -> {
                            for (TargetsListener listener : listeners) {
                                try { listener.onTargetsUpdated(snapshot); }
                                catch (Throwable t) { Log.w(TAG, "Listener threw in onTargetsUpdated", t); }
                            }
                        });
                    }

                    if (hasTargets != lastAvailability) {
                        lastAvailability = hasTargets;
                        final boolean availabilitySnapshot = hasTargets;
                        handler.post(() -> {
                            for (TargetsListener listener : listeners) {
                                try { listener.onAvailabilityChanged(availabilitySnapshot); }
                                catch (Throwable t) { Log.w(TAG, "Listener threw in onAvailabilityChanged", t); }
                            }
                        });
                    }
                }

                isResolving.set(false);
            });
        } catch (Exception e) {
            Log.e(TAG, "Failed to submit resolution task", e);
            isResolving.set(false);
        }
    }

    private boolean isInvalidIP(String ip) {
        return !(IPV4_PATTERN.matcher(ip).matches() || IPV6_PATTERN.matcher(ip).matches());
    }

    private boolean isInvalidHost(String host) {
        return !HOSTNAME_PATTERN.matcher(host).matches();
    }
}
