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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ExecutionException;
import java.util.regex.Pattern;

/**
 * TargetResolverManager - Manages DNS resolution of UDP targets from JSON configuration.
 * Provides periodic target discovery with debouncing, thread-safe operations, and graceful shutdown.
 * Supports both IP addresses and hostnames with validation and timeout protection.
 */
public class TargetResolverManager {

    private static final String TAG = "TargetResolverHelper";

    // IPv4 validation pattern (supports 0.0.0.0 to 255.255.255.255)
    private static final Pattern IPV4_PATTERN = Pattern.compile(
            "^(([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])\\.){3}" +
                    "([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])$"
    );

    // IPv6 validation pattern (supports various IPv6 formats including compressed)
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

    // Hostname validation pattern (letters, numbers, dots, and hyphens)
    private static final Pattern HOSTNAME_PATTERN = Pattern.compile("^[a-zA-Z0-9.-]+$");

    // Core components
    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());

    // Target storage and management
    private final Map<InetAddress, Set<Integer>> resolvedTargetsMap = new HashMap<>();
    private ExecutorService executor = null;
    private final Set<TargetsListener> listeners = new CopyOnWriteArraySet<>();

    // State management
    private volatile boolean running = false;
    private volatile long intervalMs = 0L;
    private Runnable periodicRunnable = null;
    private final AtomicBoolean isResolving = new AtomicBoolean(false);
    private final List<JSONObject> jsonTargets = new ArrayList<>();
    private volatile boolean lastAvailability = false;

    // Configuration constants
    private static final int DNS_THREAD_POOL_SIZE = 4;
    private static final long DNS_TIMEOUT_MS = 2000L;

    // Debouncing configuration to prevent rapid state changes
    private static final long DEFAULT_DEBOUNCE_MS = 1000L;
    private long lastTargetsChangeTime = 0;
    private long lastAvailabilityChangeTime = 0;

    // Custom thread factory for named daemon threads
    private static final ThreadFactory THREAD_FACTORY = new ThreadFactory() {
        private final AtomicInteger count = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "TargetResolver-" + count.getAndIncrement());
            t.setDaemon(true); // Daemon threads won't prevent JVM shutdown
            return t;
        }
    };

    /**
     * Interface for receiving target resolution updates and availability changes.
     * Listeners are notified on the main thread.
     */
    public interface TargetsListener {
        /**
         * Called when the resolved targets map is updated with new addresses and ports.
         * Provides a snapshot of currently available targets.
         *
         * @param targets Thread-safe map of resolved InetAddresses to their port sets
         */
        void onTargetsUpdated(Map<InetAddress, Set<Integer>> targets);

        /**
         * Called when overall target availability changes (targets become available or unavailable).
         *
         * @param available true if at least one target is resolvable and available, false otherwise
         */
        void onAvailabilityChanged(boolean available);
    }

    /**
     * Constructs a TargetResolverManager and loads target configuration from JSON assets.
     * Initializes the target list but does not start resolution until start() is called.
     *
     * @param context The application context for accessing assets and resources
     */
    public TargetResolverManager(Context context) {
        this.context = context;
        try {
            loadJsonTargets();
        } catch (Exception e) {
            Log.e(TAG, "Failed to load targets, continuing with empty list", e);
        }
    }

    /**
     * Loads UDP targets configuration from assets/udp_targets.json.
     * Expected JSON format: {"targets": [{"ip": "hostname", "port": 1234}, ...]}
     *
     * @throws Exception if JSON parsing fails or file cannot be read
     */
    private void loadJsonTargets() throws Exception {
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
        }
    }

    /**
     * Adds a targets listener and provides immediate state notification with current targets.
     * New listeners receive immediate callbacks with the current state on the main thread.
     *
     * @param listener The listener to add for target updates
     */
    public void addTargetsListener(TargetsListener listener) {
        if (listener == null) return;
        listeners.add(listener);
        final Map<InetAddress, Set<Integer>> snapshot = copyResolvedMap();
        final boolean currentAvailability = !snapshot.isEmpty();
        lastAvailability = currentAvailability;

        // Provide immediate state notification to new listener on main thread
        handler.post(() -> {
            try {
                listener.onTargetsUpdated(snapshot);
            } catch (Throwable t) {
                Log.w(TAG, "Listener threw exception in onTargetsUpdated", t);
            }
            try {
                listener.onAvailabilityChanged(currentAvailability);
            } catch (Throwable t) {
                Log.w(TAG, "Listener threw exception in onAvailabilityChanged", t);
            }
        });
    }

    /**
     * Removes a targets listener from receiving further updates.
     *
     * @param listener The listener to remove
     */
    public void removeTargetsListener(TargetsListener listener) {
        if (listener == null) return;
        listeners.remove(listener);
    }

    /**
     * Creates a thread-safe deep copy of the resolved targets map.
     * Used to provide consistent snapshots to listeners.
     *
     * @return Copy of the current resolved targets map with immutable port sets
     */
    private synchronized Map<InetAddress, Set<Integer>> copyResolvedMap() {
        Map<InetAddress, Set<Integer>> copy = new HashMap<>();
        for (Map.Entry<InetAddress, Set<Integer>> entry : resolvedTargetsMap.entrySet()) {
            copy.put(entry.getKey(), new HashSet<>(entry.getValue()));
        }
        return copy;
    }

    /**
     * Starts periodic target resolution with the specified interval.
     * Initializes executor and begins the resolution loop if not already running.
     *
     * @param intervalMs Interval between resolution attempts in milliseconds
     */
    public synchronized void start(long intervalMs) {
        if (running) return;
        this.intervalMs = intervalMs;
        running = true;

        // Initialize executor if needed
        if (executor == null || executor.isShutdown() || executor.isTerminated()) {
            executor = Executors.newFixedThreadPool(DNS_THREAD_POOL_SIZE, THREAD_FACTORY);
        }

        // Set up periodic resolution task
        periodicRunnable = new Runnable() {
            @Override
            public void run() {
                if (!running) return;
                resolveTargetsAsync();
                handler.postDelayed(this, TargetResolverManager.this.intervalMs);
            }
        };
        handler.post(periodicRunnable);
    }

    /**
     * Performs graceful shutdown of target resolution.
     * Stops periodic resolution, cleans up executors, and preserves final state.
     */
    public synchronized void stop() {
        running = false;
        if (periodicRunnable != null) {
            handler.removeCallbacks(periodicRunnable);
            periodicRunnable = null;
        }
        if (executor != null && !executor.isShutdown()) {
            try {
                executor.shutdown();
                if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                Log.w(TAG, "Executor shutdown failed", e);
            }
            executor = null;
        }
    }

    /**
     * Requests immediate target resolution without waiting for the next periodic interval.
     * Useful for manual refresh requests or after network connectivity changes.
     */
    public void requestResolveNow() {
        if (!running) {
            Log.d(TAG, "requestResolveNow called but resolver is not running");
            return;
        }
        resolveTargetsAsync();
    }

    /**
     * Internal class representing a single DNS resolution task.
     * Tracks host, port, and the Future for the resolution operation.
     */
    private static class ResolutionTask {
        final String host;
        final int port;
        final Future<InetAddress[]> future;

        ResolutionTask(String host, int port, Future<InetAddress[]> future) {
            this.host = host;
            this.port = port;
            this.future = future;
        }
    }

    /**
     * Asynchronously resolves all configured targets with debouncing and timeout protection.
     * Uses a temporary executor for DNS resolution to prevent blocking the main executor.
     */
    private void resolveTargetsAsync() {
        if (!isResolving.compareAndSet(false, true)) {
            Log.d(TAG, "Resolution already in progress, skipping duplicate request");
            return;
        }

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

                // Temporary executor for DNS resolution tasks
                ExecutorService dnsExecutor = Executors.newCachedThreadPool(THREAD_FACTORY);

                try {
                    List<ResolutionTask> tasks = new ArrayList<>();

                    // Create resolution tasks for all valid targets
                    for (JSONObject target : jsonTargets) {
                        String host = target.getString("ip");
                        int port = target.getInt("port");

                        // Validate port range
                        if (port <= 0 || port > 65535) continue;

                        // Validate host format (IP or hostname)
                        if (isInvalidIP(host) && isInvalidHost(host)) continue;

                        Future<InetAddress[]> future = dnsExecutor.submit(() -> InetAddress.getAllByName(host));
                        tasks.add(new ResolutionTask(host, port, future));
                    }

                    // Process all resolution tasks with timeout protection
                    for (ResolutionTask task : tasks) {
                        try {
                            InetAddress[] addresses = task.future.get(DNS_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                            if (addresses != null && addresses.length > 0) {
                                for (InetAddress addr : addresses) {
                                    Set<Integer> ports = tempMap.get(addr);
                                    if (ports == null) {
                                        ports = new HashSet<>();
                                        tempMap.put(addr, ports);
                                    }
                                    ports.add(task.port);
                                }
                                if (addresses.length > 1) {
                                    Log.d(TAG, "Host " + task.host + " resolved to " + addresses.length + " addresses");
                                }
                            }
                        } catch (TimeoutException te) {
                            task.future.cancel(true);
                            Log.w(TAG, "DNS resolution timed out for host: " + task.host);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            Log.w(TAG, "Resolution thread interrupted for host: " + task.host);
                        } catch (ExecutionException ee) {
                            Log.w(TAG, "Failed to resolve host: " + task.host + " -> " + ee.getCause());
                        } catch (Exception ex) {
                            Log.w(TAG, "Unexpected error while resolving host: " + task.host + " -> " + ex.getMessage());
                        }
                    }

                    hasTargets = !tempMap.isEmpty();

                } catch (Exception e) {
                    Log.e(TAG, "Unexpected error during resolution process", e);
                    hasTargets = false;
                } finally {
                    // Cleanup temporary DNS executor
                    dnsExecutor.shutdown();
                    try {
                        if (!dnsExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
                            dnsExecutor.shutdownNow();
                        }
                    } catch (InterruptedException e) {
                        dnsExecutor.shutdownNow();
                        Thread.currentThread().interrupt();
                    }
                }

                // Update state and notify listeners with debouncing
                synchronized (TargetResolverManager.this) {
                    long now = System.currentTimeMillis();

                    // Check if targets list changed
                    boolean changedList = resolvedTargetsMap.size() != tempMap.size() ||
                            !resolvedTargetsMap.keySet().equals(tempMap.keySet());

                    if (changedList) {
                        resolvedTargetsMap.clear();
                        resolvedTargetsMap.putAll(tempMap);

                        // Apply debouncing for targets updates (minimize rapid changes)
                        if (now - lastTargetsChangeTime >= DEFAULT_DEBOUNCE_MS) {
                            lastTargetsChangeTime = now;

                            final Map<InetAddress, Set<Integer>> snapshot = copyResolvedMap();
                            handler.post(() -> {
                                for (TargetsListener listener : listeners) {
                                    try {
                                        listener.onTargetsUpdated(snapshot);
                                    } catch (Throwable t) {
                                        Log.w(TAG, "Listener threw exception in onTargetsUpdated", t);
                                    }
                                }
                            });
                        }
                    }

                    // Check if availability changed
                    if (hasTargets != lastAvailability) {
                        lastAvailability = hasTargets;

                        // Apply debouncing for availability changes
                        if (now - lastAvailabilityChangeTime >= DEFAULT_DEBOUNCE_MS) {
                            lastAvailabilityChangeTime = now;

                            final boolean availabilitySnapshot = hasTargets;
                            handler.post(() -> {
                                for (TargetsListener listener : listeners) {
                                    try {
                                        listener.onAvailabilityChanged(availabilitySnapshot);
                                    } catch (Throwable t) {
                                        Log.w(TAG, "Listener threw exception in onAvailabilityChanged", t);
                                    }
                                }
                            });
                        }
                    }
                }

                isResolving.set(false);
            });
        } catch (Exception e) {
            Log.e(TAG, "Failed to submit resolution task to executor", e);
            isResolving.set(false);
        }
    }

    /**
     * Validates if a string represents a valid IP address (IPv4 or IPv6).
     *
     * @param ip The IP address string to validate
     * @return true if the IP address format is invalid, false if valid
     */
    private boolean isInvalidIP(String ip) {
        return !(IPV4_PATTERN.matcher(ip).matches() || IPV6_PATTERN.matcher(ip).matches());
    }

    /**
     * Validates if a string represents a valid hostname format.
     * Checks for allowed characters (letters, numbers, dots, hyphens).
     *
     * @param host The hostname string to validate
     * @return true if the hostname format is invalid, false if valid
     */
    private boolean isInvalidHost(String host) {
        return !HOSTNAME_PATTERN.matcher(host).matches();
    }
}