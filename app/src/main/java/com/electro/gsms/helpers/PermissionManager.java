package com.electro.gsms.helpers;

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
     * Callback interface for permission request results
     */
    public interface PermissionCallback {
        /**
         * Called when all requested permissions have been granted
         */
        void onAllPermissionsGranted();

        /**
         * Called when one or more permissions have been denied
         */
        void onPermissionsDenied();
    }

    private final Activity activity;           // Host activity for permission requests
    private final PermissionCallback callback; // Callback to notify permission results
    private static final int PERMISSION_REQUEST_CODE = 100; // Unique request code

    // --- Required permissions for the app ---
    // 🔹 Now public and accessible from other components
    public static final String[] REQUIRED_PERMISSIONS = new String[]{
            android.Manifest.permission.ACCESS_FINE_LOCATION
    };

    /**
     * Constructs a PermissionManager instance
     * @param activity The activity that will host permission dialogs
     * @param callback Callback to receive permission grant/deny results
     */
    public PermissionManager(Activity activity, PermissionCallback callback) {
        this.activity = activity;
        this.callback = callback;
    }

    /**
     * Checks if all required permissions have already been granted.
     * @param activity The activity context to check permissions against
     * @return true if all permissions are granted, false otherwise
     */
    public static boolean hasAllPermissions(Activity activity) {
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(activity, permission)
                    != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    /**
     * Checks current permission status and requests
     * missing permissions if necessary.
     * If all permissions are already granted, immediately calls onAllPermissionsGranted()
     */
    public void checkAndRequestPermissions() {
        if (!hasAllPermissions(activity)) {
            // Request missing permissions from the user
            ActivityCompat.requestPermissions(activity, REQUIRED_PERMISSIONS, PERMISSION_REQUEST_CODE);
        } else {
            // All permissions already granted - notify immediately
            callback.onAllPermissionsGranted();
        }
    }

    /**
     * Handles the result of permission requests from Activity.onRequestPermissionsResult()
     * @param requestCode The request code passed to requestPermissions()
     * @param permissions The requested permissions
     * @param grantResults The grant results for the corresponding permissions
     */
    public void handlePermissionsResult(int requestCode,
                                        @NonNull String[] permissions,
                                        @NonNull int[] grantResults) {
        // Verify this is our permission request
        if (requestCode == PERMISSION_REQUEST_CODE) {
            // Check if user cancelled the permission dialog
            if (grantResults.length == 0) {
                callback.onPermissionsDenied();
                return;
            }

            // Check each permission result
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    callback.onPermissionsDenied();
                    return;
                }
            }

            // All permissions were granted
            callback.onAllPermissionsGranted();
        }
    }
}