package com.electro.gsms.services;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import androidx.annotation.NonNull;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;


/**
 * DeviceIdentifierManager - Manages generation, storage, and retrieval of unique device identifiers.
 *
 * <p><b>Features:</b></p>
 * <ul>
 *   <li>Thread-safe lazy initialization with double-checked locking</li>
 *   <li>Dual-layer persistence (Encrypted SharedPreferences + Backup File)</li>
 *   <li>Automatic fallback cascade on storage failures</li>
 *   <li>Listener pattern for async initialization notifications</li>
 *   <li>Hardware-backed encryption via AndroidX Security library</li>
 * </ul>
 *
 * <p><b>Persistence Strategy:</b></p>
 * <ol>
 *   <li><b>Primary:</b> EncryptedSharedPreferences (AES256-GCM, hardware-backed)</li>
 *   <li><b>Backup:</b> Internal file with XOR obfuscation</li>
 *   <li><b>Emergency:</b> Timestamp-based fallback if both fail</li>
 * </ol>
 *
 * <p><b>Thread Safety:</b></p>
 * <ul>
 *   <li>All public methods are thread-safe</li>
 *   <li>Listeners notified on main thread</li>
 *   <li>Volatile state flags for memory visibility</li>
 *   <li>Synchronized critical sections</li>
 * </ul>
 *
 * <p><b>Lifecycle:</b></p>
 * <ul>
 *   <li>Lazy initialization on first {@link #getDeviceId()} call</li>
 *   <li>No active resources requiring disposal</li>
 *   <li>Listeners can be cleared via {@link #clearAllListeners()}</li>
 * </ul>
 *
 * <p><b>Example Usage:</b></p>
 * <pre>
 * // Basic usage
 * DeviceIdentifierManager manager = new DeviceIdentifierManager(context);
 * String deviceId = manager.getDeviceId();
 *
 * // With listener pattern
 * manager.addListener(new IdentifierListener() {
 *     {@literal @}Override
 *     public void onIdentifierReady(String deviceId) {
 *         // Use device ID
 *     }
 *
 *     {@literal @}Override
 *     public void onIdentifierError(String error) {
 *         // Handle error
 *     }
 * });
 * </pre>
 *
 * @see androidx.security.crypto.EncryptedSharedPreferences
 * @see androidx.security.crypto.MasterKey
 */

// =====================================================
// CONFIGURATION CONSTANTS
// =====================================================
public class DeviceIdentifierManager {

    private static final String TAG = "DeviceIdentifierManager";
    private static final String PREFS_NAME = "device_identity_prefs";
    private static final String KEY_DEVICE_ID = "device_uuid";
    private static final String BACKUP_FILE_NAME = "device_id.dat";

    // =====================================================
    // THREAD-SAFE COMPONENTS
    // =====================================================
    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // =====================================================
    // LISTENER MANAGEMENT
    // =====================================================
    private final CopyOnWriteArrayList<IdentifierListener> listeners =
            new CopyOnWriteArrayList<>();

    // =====================================================
    // VOLATILE STATE MANAGEMENT
    // =====================================================
    private volatile String cachedDeviceId = null;
    private volatile boolean isInitialized = false;

    // =====================================================
    // CONSTRUCTOR
    // =====================================================
    public DeviceIdentifierManager(@NonNull Context context) {
        this.context = context.getApplicationContext();
        Log.d(TAG, "DeviceIdentifierManager initialized");
    }

    // =====================================================
    // LAZY INITIALIZATION AND THREAD SAFETY
    // =====================================================
    private void ensureInitialized() {
        if (isInitialized) return;
        synchronized (this) {
            if (isInitialized) return;
            try {
                cachedDeviceId = loadOrGenerateDeviceId();
                isInitialized = true;
                Log.d(TAG, "Device ID initialized successfully");
                notifyListeners(cachedDeviceId, null);
            } catch (Exception e) {
                Log.e(TAG, "Failed to initialize device ID", e);
                notifyListeners(null, "Initialization failed: " + e.getMessage());
            }
        }
    }

    // =====================================================
    // LISTENER INTERFACE
    // =====================================================

    /**
     * Interface for receiving device identifier state updates.
     * All callbacks are delivered on the main thread.
     */
    public interface IdentifierListener {
        /**
         * Called when the device identifier is successfully loaded or generated.
         *
         * @param deviceId The persistent device identifier (never null)
         */
        void onIdentifierReady(String deviceId);

        /**
         * Called when an error occurs during identifier generation or loading.
         *
         * @param error Description of the error that occurred (never null)
         */
        void onIdentifierError(String error);
    }

    // =====================================================
    // LOAD/GENERATION ORCHESTRATION (FALLBACK CASCADE)
    // =====================================================
    private String loadOrGenerateDeviceId() {
        String deviceId;

        deviceId = loadFromEncryptedPrefs();
        if (isValidDeviceId(deviceId)) {
            Log.d(TAG, "Using device ID from encrypted shared preferences");
            ensureBackupSync(deviceId);
            return deviceId;
        }

        deviceId = loadFromBackupFile();
        if (isValidDeviceId(deviceId)) {
            Log.d(TAG, "Using device ID from backup file");
            saveToEncryptedPrefs(deviceId);
            return deviceId;
        }

        deviceId = generateNewDeviceId();
        Log.i(TAG, "Generated new device ID (first installation)");
        persistDeviceId(deviceId);
        return deviceId;
    }

    // =====================================================
    // BACKUP SYNCHRONIZATION
    // =====================================================
    private void ensureBackupSync(String deviceId) {
        try {
            String backupId = loadFromBackupFile();
            if (!deviceId.equals(backupId)) {
                Log.d(TAG, "Syncing backup file with current device ID");
                saveToBackupFile(deviceId);
            }
        } catch (Exception e) {
            Log.w(TAG, "Backup synchronization failed (non-critical)", e);
        }
    }

    // =====================================================
    // GENERATION AND VALIDATION
    // =====================================================
    private String generateNewDeviceId() {
        try {
            String uuid = UUID.randomUUID().toString();
            long timestamp = System.currentTimeMillis();
            return uuid + "-" + timestamp;
        } catch (Exception e) {
            Log.e(TAG, "Failed to generate UUID, using fallback", e);
            return "fallback-" + System.currentTimeMillis();
        }
    }

    private boolean isValidDeviceId(String deviceId) {
        return deviceId != null && !deviceId.trim().isEmpty()
                && deviceId.length() >= 36 && deviceId.contains("-");
    }

    // =====================================================
    // ENCRYPTED PERSISTENCE (PRIMARY STORAGE)
    // =====================================================
    private String loadFromEncryptedPrefs() {
        try {
            @SuppressWarnings("deprecation")
            SharedPreferences prefs = EncryptedSharedPreferences.create(
                    context,
                    PREFS_NAME,
                    getMasterKey(),
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
            return prefs.getString(KEY_DEVICE_ID, null);
        } catch (Exception e) {
            Log.e(TAG, "Failed to load from encrypted preferences", e);
            return null;
        }
    }

    private boolean saveToEncryptedPrefs(String deviceId) {
        try {
            @SuppressWarnings("deprecation")
            SharedPreferences prefs = EncryptedSharedPreferences.create(
                    context,
                    PREFS_NAME,
                    getMasterKey(),
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
            return prefs.edit().putString(KEY_DEVICE_ID, deviceId).commit();
        } catch (Exception e) {
            Log.e(TAG, "Failed to save to encrypted preferences", e);
            return false;
        }
    }
    @SuppressWarnings("deprecation")
    private MasterKey getMasterKey() throws Exception {
        return new MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build();
    }

    // =====================================================
    // INTERNAL FILE BACKUP (SECONDARY STORAGE)
    // =====================================================
    private String loadFromBackupFile() {
        File file = new File(context.getFilesDir(), BACKUP_FILE_NAME);
        if (!file.exists()) return null;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String encrypted = reader.readLine();
            String deviceId = decryptBasic(encrypted);
            if (!isValidDeviceId(deviceId)) return null;
            return deviceId;
        } catch (Exception e) {
            Log.e(TAG, "Failed to load backup file", e);
            return null;
        }
    }

    private boolean saveToBackupFile(String deviceId) {
        File file = new File(context.getFilesDir(), BACKUP_FILE_NAME);
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(file), StandardCharsets.UTF_8))) {
            writer.write(encryptBasic(deviceId));
            writer.flush();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to save backup file", e);
            return false;
        }
    }

    // =====================================================
    // BASIC ENCRYPTION UTILITIES
    // =====================================================
    private String encryptBasic(String data) {
        try {
            byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
            for (int i = 0; i < bytes.length; i++){
                bytes[i] = (byte) (bytes[i] ^ 0xAA);
            }
            return Base64.encodeToString(bytes, Base64.NO_WRAP);
        } catch (Exception e) {
            return data;
        }
    }

    private String decryptBasic(String encrypted) {
        try {
            byte[] decoded = Base64.decode(encrypted, Base64.NO_WRAP);
            for (int i = 0; i < decoded.length; i++){
                decoded[i] = (byte) (decoded[i] ^ 0xAA);
            }
            return new String(decoded, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return encrypted;
        }
    }

    // =====================================================
    // PERSISTENCE RESULT HANDLING
    // =====================================================
    /**
     * Attempts to save device ID to both storages with comprehensive error handling.
     * Logs all failures with appropriate severity levels.
     *
     * <p><b>Storage hierarchy:</b></p>
     * <ul>
     *   <li><b>Primary (critical):</b> Encrypted SharedPreferences</li>
     *   <li><b>Backup (non-critical):</b> Internal file</li>
     * </ul>
     *
     * <p><b>Logging behavior:</b></p>
     * <ul>
     *   <li>ERROR level if both storages fail</li>
     *   <li>ERROR level if primary fails (even if backup succeeds)</li>
     *   <li>WARN level if only backup fails</li>
     * </ul>
     *
     * @param deviceId The device identifier to persist
     * @return true if saved to PRIMARY storage (encrypted prefs), false if primary failed.
     *         Note: Returns true even if backup fails, as backup is non-critical.
     */
    private boolean persistDeviceId(String deviceId) {
        boolean savedToPrefs = saveToEncryptedPrefs(deviceId);
        boolean savedToBackup = saveToBackupFile(deviceId);

        if (!savedToPrefs && !savedToBackup) {
            Log.e(TAG, "CRITICAL: Failed to persist device ID to ANY storage");
            return false;
        }
        if (!savedToPrefs) Log.e(TAG, "Failed to save to encrypted preferences (backup succeeded)");
        if (!savedToBackup) Log.w(TAG, "Failed to save to backup file (primary succeeded)");
        return savedToPrefs;
    }

    // =====================================================
    // PUBLIC API AND LISTENER MANAGEMENT
    // =====================================================
    public String getDeviceId() {
        ensureInitialized();
        if (cachedDeviceId == null) {
            String emergencyId = "emergency-" + System.currentTimeMillis();
            Log.e(TAG, "⚠️ Device ID null after init, using emergency fallback");
            notifyListeners(null, "Emergency fallback activated");
            return emergencyId;
        }
        return cachedDeviceId;
    }

    /**
     * Gets the cached device identifier without triggering initialization.
     * Returns null if not yet initialized.
     *
     * <p>Useful for checking availability without side effects:</p>
     * <pre>
     * String id = manager.getCachedDeviceId();
     * if (id != null) {
     *     // Use cached ID
     * } else {
     *     // ID not ready yet, use listener pattern
     * }
     * </pre>
     *
     * @return Cached device identifier or null if not initialized
     */

    @SuppressWarnings("unused")
    public String getCachedDeviceId() {
        return cachedDeviceId;
    }

    @SuppressWarnings("unused")
    public void addListener(IdentifierListener listener) {
        if (listener == null) return;
        listeners.add(listener);
        if (isInitialized && cachedDeviceId != null) {
            final String idSnapshot = cachedDeviceId;
            mainHandler.post(() -> listener.onIdentifierReady(idSnapshot));
        }
    }

    @SuppressWarnings("unused")
    public void removeListener(IdentifierListener listener) {
        if (listener != null) listeners.remove(listener);
    }

    /**
     * Removes all registered listeners.
     * Useful for cleanup during activity/fragment lifecycle to prevent memory leaks.
     */
    @SuppressWarnings("unused")
    public void clearAllListeners() {
        int count = listeners.size();
        listeners.clear();
        Log.d(TAG, "Cleared " + count + " listener(s)");
    }

    private void notifyListeners(String deviceId, String error) {
        if (listeners.isEmpty()) return;
        mainHandler.post(() -> {
            for (IdentifierListener listener : listeners) {
                try {
                    if (error != null) listener.onIdentifierError(error);
                    else listener.onIdentifierReady(deviceId);
                } catch (Throwable t) {
                    Log.e(TAG, "Listener threw exception during notification", t);
                }
            }
        });
    }

    @SuppressWarnings("unused")
    public boolean isInitialized() {
        return isInitialized;
    }

    /**
     * Forces regeneration of device identifier, creating a new device identity.
     *
     * <p><b>⚠️ WARNING: Use with extreme caution!</b></p>
     * <ul>
     *   <li>Changes the device's persistent identity permanently</li>
     *   <li>May break association with server-side tracking</li>
     *   <li>Cannot be undone</li>
     * </ul>
     *
     * <p><b>Valid use cases:</b></p>
     * <ul>
     *   <li>User explicitly requests "Reset Device ID" in settings</li>
     *   <li>Testing/debugging scenarios</li>
     *   <li>Privacy-focused "anonymization" feature</li>
     * </ul>
     *
     * <p>Updates both primary and backup storage and notifies all listeners.</p>
     *
     * @throws RuntimeException implicitly if storage is completely unavailable
     *                          (logged and notified to listeners)
     */

    @SuppressWarnings("unused")
    public synchronized void regenerateDeviceId() {
        String newId = generateNewDeviceId();
        boolean persisted = persistDeviceId(newId);
        if (!persisted) {
            Log.e(TAG, "CRITICAL: Failed to persist regenerated device ID");
            notifyListeners(null, "Failed to regenerate device ID - storage unavailable");
            return;
        }
        cachedDeviceId = newId;
        Log.w(TAG, "Device ID regenerated - device has new identity");
        notifyListeners(newId, null);
    }
}