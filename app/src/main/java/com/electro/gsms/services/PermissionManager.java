package com.electro.gsms.services;

import android.app.Activity;
import android.content.pm.PackageManager;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.annotation.NonNull;

/**
 * PermissionManager handles checking and requesting
 * runtime permissions required by the app.
 * It notifies the caller (Activity) via a callback interface
 * whether all permissions were granted or if any were denied.
 */
public class PermissionManager {

    /**
     * Interface that the hosting Activity must implement
     * to receive callbacks about permission results.
     */
    public interface PermissionCallback {
        void onAllPermissionsGranted(); // Called if ALL requested permissions are granted
        void onPermissionsDenied();     // Called if at least one permission is denied
    }

    // --- Fields ---
    private final Activity activity;           // The Activity requesting permissions
    private final PermissionCallback callback; // Callback to notify the Activity
    private static final int PERMISSION_REQUEST_CODE = 100;

    // --- Required permissions for the app ---
    // Only ACCESS_FINE_LOCATION is required (GPS via satellite).
    // Coarse location and SMS permissions are no longer needed.
    // Future network-related permissions (e.g., INTERNET) can be added if required.
    private final String[] requiredPermissions = new String[]{
            android.Manifest.permission.ACCESS_FINE_LOCATION
    };

    /**
     * Constructs the PermissionManager with Activity context
     * and the callback to notify about permission results.
     *
     * @param activity The Activity requesting permissions
     * @param callback The callback interface implementation
     */
    public PermissionManager(Activity activity, PermissionCallback callback) {
        this.activity = activity;
        this.callback = callback;
    }

    /**
     * Checks if all required permissions have already been granted.
     *
     * @return true if ALL permissions are granted, false otherwise
     */
    private boolean hasAllPermissions() {
        for (String permission : requiredPermissions) {
            if (ContextCompat.checkSelfPermission(activity, permission)
                    != PackageManager.PERMISSION_GRANTED) {
                return false; // Found a permission not granted
            }
        }
        return true; // All permissions are granted
    }

    /**
     * Checks current permission status and requests
     * missing permissions if necessary.
     * If all permissions are granted, immediately notifies the callback.
     */
    public void checkAndRequestPermissions() {
        if (!hasAllPermissions()) {
            // --- Request all required permissions at once ---
            ActivityCompat.requestPermissions(activity, requiredPermissions, PERMISSION_REQUEST_CODE);
        } else {
            // --- All permissions already granted; notify immediately ---
            callback.onAllPermissionsGranted();
        }
    }

    /**
     * Processes the results of the permission request.
     * Should be called from the Activity's onRequestPermissionsResult method.
     *
     * @param requestCode  The request code passed in the permission request
     * @param permissions  The requested permissions
     * @param grantResults The results for each permission (granted or denied)
     */
    public void handlePermissionsResult(int requestCode,
                                        @NonNull String[] permissions,
                                        @NonNull int[] grantResults) {
        if (requestCode == PERMISSION_REQUEST_CODE) {

            // --- Defensive check: if no results, treat as denied ---
            if (grantResults.length == 0) {
                callback.onPermissionsDenied();
                return;
            }

            // --- Check each permission result ---
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    // At least one permission denied → notify callback
                    callback.onPermissionsDenied();
                    return;
                }
            }

            // --- All permissions granted → notify callback ---
            callback.onAllPermissionsGranted();
        }
    }
}
