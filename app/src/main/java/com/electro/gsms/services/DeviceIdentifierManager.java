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
 * Provides secure persistence through encrypted shared preferences with backup fallback mechanisms.
 * Implements thread-safe lazy initialization and listener pattern for identifier availability.
 */
public class DeviceIdentifierManager {

    // ==============================
    // Configuration Constants
    // ==============================
    private static final String TAG = "DeviceIdentifierManager";
    private static final String PREFS_NAME = "device_identity_prefs";
    private static final String KEY_DEVICE_ID = "device_uuid";
    private static final String BACKUP_FILE_NAME = "device_id.dat";

    // ==============================
    // Thread-safe Components
    // ==============================
    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ==============================
    // Listener Management
    // ==============================
    private final CopyOnWriteArrayList<IdentifierListener> listeners =
            new CopyOnWriteArrayList<>();

    // ==============================
    // Volatile State Management
    // ==============================
    private volatile String cachedDeviceId = null;
    private volatile boolean isInitialized = false;

    // ==============================
    // Base Constructor
    // ==============================

    /**
     * Constructs a new DeviceIdentifierManager with application context.
     * Uses application context to prevent memory leaks.
     *
     * @param context The context used to access encrypted storage and files
     */
    public DeviceIdentifierManager(@NonNull Context context) {
        Context appContext = context.getApplicationContext(); // Memory leak prevention
        this.context = appContext;
        Log.d(TAG, "DeviceIdentifierManager initialized");
        // Lazy loading - ID is not loaded until first access
    }

    // ==============================
    // Lazy Initialization and Thread Safety
    // ==============================

    /**
     * Ensures the device identifier is initialized before access.
     * Implements double-checked locking for thread-safe lazy initialization.
     */
    private void ensureInitialized() {
        if (isInitialized) return;
        synchronized (this) {
            if (isInitialized) return; // Double-checked locking

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

    // ==============================
    // Listener Interface
    // ==============================

    /**
     * Interface for receiving device identifier state updates.
     * Notifications are delivered on the main thread.
     */
    public interface IdentifierListener {
        /**
         * Called when the device identifier is successfully loaded or generated.
         *
         * @param deviceId The persistent device identifier
         */
        void onIdentifierReady(String deviceId);

        /**
         * Called when an error occurs during identifier generation or loading.
         *
         * @param error Description of the error that occurred
         */
        void onIdentifierError(String error);
    }

    // ==============================
    // Load/Generation Orchestration (Fallback Cascade)
    // ==============================

    /**
     * Orchestrates the device identifier loading with fallback cascade.
     * Attempts sources in order: Encrypted Preferences -> Backup File -> New Generation.
     *
     * @return Valid device identifier from available source or newly generated
     */
    private String loadOrGenerateDeviceId() {
        String deviceId;

        // Step 1: Primary Source - Encrypted SharedPreferences
        deviceId = loadFromEncryptedPrefs();
        if (isValidDeviceId(deviceId)) {
            Log.d(TAG, "Using device ID from encrypted shared preferences");
            ensureBackupSync(deviceId);
            return deviceId;
        }

        // Step 2: Secondary Source - Backup File
        deviceId = loadFromBackupFile();
        if (isValidDeviceId(deviceId)) {
            Log.d(TAG, "Using device ID from backup file");
            saveToEncryptedPrefs(deviceId); // Restore to primary storage
            return deviceId;
        }

        // Step 3: Tertiary Source - Generate New Identifier
        deviceId = generateNewDeviceId();
        Log.i(TAG, "Generated new device ID (first installation)");
        saveToEncryptedPrefs(deviceId);
        saveToBackupFile(deviceId);
        return deviceId;
    }

    /**
     * Ensures backup file is synchronized with current device identifier.
     * Runs as non-critical operation - failures are logged but don't affect primary flow.
     *
     * @param deviceId The current device identifier to sync to backup
     */
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

    // ==============================
    // Generation and Validation
    // ==============================

    /**
     * Generates a new unique device identifier combining UUID and timestamp.
     * Includes fallback mechanism for UUID generation failures.
     *
     * @return Newly generated device identifier
     */
    private String generateNewDeviceId() {
        try {
            String uuid = UUID.randomUUID().toString();
            long timestamp = System.currentTimeMillis();
            String deviceId = uuid + "-" + timestamp;
            Log.d(TAG, "Generated new device ID: " +
                    deviceId.substring(0, Math.min(20, deviceId.length())) + "...");
            return deviceId;
        } catch (Exception e) {
            Log.e(TAG, "Failed to generate UUID, using timestamp fallback", e);
            return "fallback-" + System.currentTimeMillis();
        }
    }

    /**
     * Validates device identifier format and content.
     * Ensures identifier meets minimum requirements for uniqueness and format.
     *
     * @param deviceId The device identifier to validate
     * @return true if the identifier is valid, false otherwise
     */
    private boolean isValidDeviceId(String deviceId) {
        if (deviceId == null || deviceId.trim().isEmpty()) return false;
        if (deviceId.length() < 36) return false; // Minimum UUID length
        if (!deviceId.contains("-")) return false; // Basic format check
        return true;
    }

    // ==============================
    // Encrypted Persistence
    // ==============================

    /**
     * Loads device identifier from encrypted shared preferences.
     * Uses Android Security library for hardware-backed encryption.
     *
     * @return Device identifier from encrypted storage, or null if not available
     */
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
            String deviceId = prefs.getString(KEY_DEVICE_ID, null);
            if (deviceId != null) {
                Log.d(TAG, "Device ID successfully loaded from encrypted preferences");
            }
            return deviceId;
        } catch (Exception e) {
            Log.e(TAG, "Failed to load device ID from encrypted preferences", e);
            return null;
        }
    }

    /**
     * Saves device identifier to encrypted shared preferences.
     *
     * @param deviceId The device identifier to persist
     * @return true if save operation succeeded, false otherwise
     */
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
            boolean success = prefs.edit()
                    .putString(KEY_DEVICE_ID, deviceId)
                    .commit();
            if (success) Log.d(TAG, "Device ID saved to encrypted preferences");
            return success;
        } catch (Exception e) {
            Log.e(TAG, "Failed to save device ID to encrypted preferences", e);
            return false;
        }
    }

    /**
     * Creates master key for encrypted shared preferences.
     * Uses AES256_GCM scheme for optimal security.
     *
     * @return MasterKey instance for encrypted storage
     * @throws Exception If master key creation fails
     */
    @SuppressWarnings("deprecation")
    private MasterKey getMasterKey() throws Exception {
        return new MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build();
    }

    // ==============================
    // Internal File Backup
    // ==============================

    /**
     * Loads device identifier from backup file in internal storage.
     * Uses basic XOR encryption for lightweight protection.
     *
     * @return Device identifier from backup file, or null if not available
     */
    private String loadFromBackupFile() {
        File file = new File(context.getFilesDir(), BACKUP_FILE_NAME);
        if (!file.exists()) {
            Log.d(TAG, "Backup file does not exist");
            return null;
        }
        try (FileInputStream fis = new FileInputStream(file);
             InputStreamReader isr = new InputStreamReader(fis, StandardCharsets.UTF_8);
             BufferedReader reader = new BufferedReader(isr)) {
            String encrypted = reader.readLine();
            if (encrypted == null || encrypted.isEmpty()) return null;
            String deviceId = decryptBasic(encrypted);
            if (isValidDeviceId(deviceId)) {
                Log.d(TAG, "Device ID successfully loaded from backup file");
                return deviceId;
            }
            return null;
        } catch (Exception e) {
            Log.e(TAG, "Failed to load device ID from backup file", e);
            return null;
        }
    }

    /**
     * Saves device identifier to backup file in internal storage.
     * Uses basic XOR encryption for lightweight protection.
     *
     * @param deviceId The device identifier to backup
     * @return true if backup operation succeeded, false otherwise
     */
    private boolean saveToBackupFile(String deviceId) {
        File file = new File(context.getFilesDir(), BACKUP_FILE_NAME);
        try (FileOutputStream fos = new FileOutputStream(file);
             OutputStreamWriter osw = new OutputStreamWriter(fos, StandardCharsets.UTF_8);
             BufferedWriter writer = new BufferedWriter(osw)) {
            String encrypted = encryptBasic(deviceId);
            writer.write(encrypted);
            writer.flush();
            Log.d(TAG, "Device ID saved to backup file");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to save device ID to backup file", e);
            return false;
        }
    }

    /**
     * Basic XOR encryption for backup file protection.
     * Not cryptographically secure - provides lightweight obfuscation.
     *
     * @param data The plaintext data to encrypt
     * @return Base64 encoded encrypted string
     */
    private String encryptBasic(String data) {
        try {
            byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
            byte[] xored = new byte[bytes.length];
            for (int i = 0; i < bytes.length; i++) xored[i] = (byte) (bytes[i] ^ 0xAA);
            return Base64.encodeToString(xored, Base64.NO_WRAP);
        } catch (Exception e) {
            Log.e(TAG, "Basic encryption failed", e);
            return data; // Fallback to plaintext
        }
    }

    /**
     * Basic XOR decryption for backup file retrieval.
     *
     * @param encrypted The Base64 encoded encrypted string
     * @return Decrypted plaintext string
     */
    private String decryptBasic(String encrypted) {
        try {
            byte[] decoded = Base64.decode(encrypted, Base64.NO_WRAP);
            byte[] xored = new byte[decoded.length];
            for (int i = 0; i < decoded.length; i++) xored[i] = (byte) (decoded[i] ^ 0xAA);
            return new String(xored, StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.e(TAG, "Basic decryption failed", e);
            return encrypted; // Fallback to encrypted string
        }
    }

    // =====================================================
    // Public API and Listener Management
    // =====================================================

    /**
     * Retrieves the persistent device identifier.
     * Thread-safe with lazy initialization - triggers generation on first access.
     *
     * @return Device identifier (never null, includes emergency fallback)
     */
    public String getDeviceId() {
        ensureInitialized(); // Lazy initialization
        if (cachedDeviceId == null) {
            Log.e(TAG, "Device ID is null after initialization - using emergency fallback");
            return "emergency-" + System.currentTimeMillis();
        }
        return cachedDeviceId;
    }

    /**
     * Adds a listener for device identifier state changes.
     * Provides immediate notification if identifier is already available.
     *
     * @param listener The listener to receive identifier updates
     */
    @SuppressWarnings("unused") // Public API for future use
    public void addListener(IdentifierListener listener) {
        if (listener == null) return;
        listeners.add(listener);

        // Immediate notification if already initialized
        if (isInitialized && cachedDeviceId != null) {
            final String idSnapshot = cachedDeviceId;
            mainHandler.post(() -> {
                try {
                    listener.onIdentifierReady(idSnapshot);
                } catch (Throwable t) {
                    Log.e(TAG, "Listener threw exception during immediate notification", t);
                }
            });
        }
    }

    /**
     * Removes a listener from receiving further updates.
     *
     * @param listener The listener to remove
     */
    @SuppressWarnings("unused") // Public API for future use
    public void removeListener(IdentifierListener listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    /**
     * Notifies all registered listeners of identifier state changes.
     * Notifications are delivered on the main thread.
     *
     * @param deviceId The device identifier (null if error occurred)
     * @param error Error message (null if operation succeeded)
     */
    private void notifyListeners(String deviceId, String error) {
        if (listeners.isEmpty()) return;

        mainHandler.post(() -> {
            for (IdentifierListener listener : listeners) {
                try {
                    if (error != null) {
                        listener.onIdentifierError(error);
                    } else {
                        listener.onIdentifierReady(deviceId);
                    }
                } catch (Throwable t) {
                    Log.e(TAG, "Listener threw exception during notification", t);
                }
            }
        });
    }

    /**
     * Checks if the device identifier has been initialized.
     *
     * @return true if identifier is loaded/generated and ready, false otherwise
     */
    @SuppressWarnings("unused") // Public API for debugging
    public boolean isInitialized() {
        return isInitialized;
    }

    /**
     * Forces regeneration of device identifier creating a new device identity.
     * Use with caution - this changes the device's persistent identity.
     * Updates both primary and backup storage and notifies all listeners.
     */
    @SuppressWarnings("unused")
    public void regenerateDeviceId() {
        synchronized (this) {
            String newId = generateNewDeviceId();
            saveToEncryptedPrefs(newId);
            saveToBackupFile(newId);
            cachedDeviceId = newId;
            Log.w(TAG, "Device ID regenerated - device has new identity");
            notifyListeners(newId, null);
        }
    }
}
