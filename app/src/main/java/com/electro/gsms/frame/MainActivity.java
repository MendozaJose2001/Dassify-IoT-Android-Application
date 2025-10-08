package com.electro.gsms.frame;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.electro.gsms.R;
import com.electro.gsms.helpers.PermissionManager;

/**
 * MainActivity: Splash screen and entry point of the app.
 * Permission handling is delegated entirely to PermissionManager.
 */
public class MainActivity extends AppCompatActivity implements PermissionManager.PermissionCallback {

    private static final long SPLASH_DELAY = 2500; // 2.5 seconds splash delay

    private PermissionManager permissionManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // --- Splash logo animation ---
        ImageView splashLogo = findViewById(R.id.splash_logo);
        Animation splashAnimation = AnimationUtils.loadAnimation(this, R.anim.splash_animation);
        splashLogo.startAnimation(splashAnimation);

        // --- Initialize PermissionManager ---
        permissionManager = new PermissionManager(this, this);

        // --- Delay splash, then check/request permissions ---
        new Handler(Looper.getMainLooper()).postDelayed(
                permissionManager::checkAndRequestPermissions,
                SPLASH_DELAY
        );
    }

    // --- PermissionCallback implementation ---

    @Override
    public void onAllPermissionsGranted() {
        // All required permissions granted → start AppCore
        startAppCore();
    }

    @Override
    public void onPermissionsDenied() {
        // At least one permission denied → show message and close app
        Toast.makeText(this, "Permissions are required to run the app.", Toast.LENGTH_LONG).show();
        finish(); // Close app
    }

    // --- Helper method to start AppCore ---
    private void startAppCore() {
        Intent intent = new Intent(MainActivity.this, AppCore.class);
        startActivity(intent);
        finish(); // Close this activity so user can't return to splash
    }

    // --- Forward system permission results to PermissionManager ---
    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // Forward results to PermissionManager
        permissionManager.handlePermissionsResult(requestCode, permissions, grantResults);
    }
}

