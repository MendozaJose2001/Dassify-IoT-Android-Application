package com.electro.dassify_application.services;

import android.content.Context;
import android.content.pm.PackageManager;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

import com.electro.dassify_application.helpers.PermissionManager;

import java.util.ArrayList;
import java.util.List;

/**
 * PermissionWatcher
 * Observes runtime permission state for all PermissionManager.REQUIRED_PERMISSIONS.
 * Detects if any permission is revoked while the lifecycle owner resumes.
 * Useful for Android 14+ where users can revoke permissions from Settings
 * even while the app is running.
 */
public class PermissionWatcher implements DefaultLifecycleObserver {

    /**
     * Callback interface for handling permission revocation events
     */
    public interface PermissionRevokedCallback {
        /**
         * Called when one or more permissions have been revoked
         * @param revokedPermissions List of permissions that were revoked
         */
        void onPermissionsRevoked(@NonNull List<String> revokedPermissions);
    }

    private final Context context;
    private final LifecycleOwner lifecycleOwner;
    private final PermissionRevokedCallback callback;
    private boolean isWatching = false;

    /**
     * Constructs a PermissionWatcher instance
     * @param context The application context for permission checks
     * @param lifecycleOwner The lifecycle owner to observe for resume events
     * @param callback Callback to invoke when permissions are revoked
     */
    public PermissionWatcher(@NonNull Context context,
                             @NonNull LifecycleOwner lifecycleOwner,
                             @NonNull PermissionRevokedCallback callback) {
        this.context = context.getApplicationContext(); // Use application context for safety
        this.lifecycleOwner = lifecycleOwner;
        this.callback = callback;
    }

    /**
     * Start watching permissions by attaching to lifecycle
     * Ensures the watcher is only registered once
     */
    public void startWatching() {
        if (!isWatching) {
            lifecycleOwner.getLifecycle().addObserver(this);
            isWatching = true;
        }
    }

    /**
     * Stop watching permissions and clean up lifecycle observation
     */
    public void stopWatching() {
        if (isWatching) {
            lifecycleOwner.getLifecycle().removeObserver(this);
            isWatching = false;
        }
    }

    /**
     * Check permission status when the lifecycle owner resumes
     * Detects if any permissions were revoked while the app was in background
     */
    @Override
    public void onResume(@NonNull LifecycleOwner owner) {
        List<String> revoked = new ArrayList<>();

        // Check each required permission for revocation
        for (String permission : PermissionManager.REQUIRED_PERMISSIONS) {
            boolean granted = ContextCompat.checkSelfPermission(context, permission)
                    == PackageManager.PERMISSION_GRANTED;
            if (!granted) {
                revoked.add(permission);
            }
        }

        // Notify callback if any permissions were revoked
        if (!revoked.isEmpty()) {
            callback.onPermissionsRevoked(revoked);
        }
    }
}