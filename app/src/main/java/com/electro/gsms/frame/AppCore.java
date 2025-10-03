package com.electro.gsms.frame;

import android.graphics.Color;
import android.location.Location;
import android.net.Network;
import android.os.Bundle;
import android.view.Gravity;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import com.electro.gsms.services.GPSManager;
import com.electro.gsms.customers.GPSSender;
import com.electro.gsms.customers.MiniMapController;
import com.electro.gsms.services.NetworkManager;
import com.electro.gsms.customers.QuestionDrawer;
import com.electro.gsms.R;
import com.electro.gsms.services.TargetResolverManager;
import com.electro.gsms.customers.UDPHelper;

import java.net.InetAddress;
import java.util.Map;
import java.util.Set;

/**
 * AppCore
 * Main activity responsible for the application UI.
 * Handles GPS updates, map display, and sending
 * GPS coordinates via UDP using GPSSenderController.
 */
public class AppCore extends AppCompatActivity implements GPSManager.LocationListenerExternal,
        GPSSender.SenderStateListener {

    private Button sendButton;
    private DrawerLayout drawerLayout;
    private ImageButton btnQuestion, btnMore;

    private Location lastLocation;
    private Toast currentToast;

    private QuestionDrawer questionDrawer;
    private GPSSender gpsSender;

    private Animation blinkAnimation;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_appcore);

        initializeViews();

        blinkAnimation = AnimationUtils.loadAnimation(this, R.anim.blink);

        GPSManager gpsManager = new GPSManager(this);
        gpsManager.addLocationListener(this);
        gpsManager.start();

        NetworkManager networkManager = new NetworkManager(this);
        networkManager.addListener(networkListener);

        TargetResolverManager resolver = new TargetResolverManager(this);
        resolver.addTargetsListener(targetsListener);
        resolver.start(2000);

        UDPHelper udpHelper = new UDPHelper(gpsManager, resolver, networkManager);

        FrameLayout mapContainer = findViewById(R.id.map_container);
        new MiniMapController(this, mapContainer, gpsManager);

        questionDrawer = new QuestionDrawer(
                findViewById(R.id.drawer_instructions),
                this,
                drawerLayout,
                gpsManager,
                networkManager,
                resolver
        );

        // --- GPSSenderController ahora solo con UDP ---
        gpsSender = new GPSSender(
                gpsManager,
                networkManager,
                resolver,
                udpHelper,
                this
        );

        setupListeners();
    }

    private void initializeViews() {
        sendButton = findViewById(R.id.send_button);
        drawerLayout = findViewById(R.id.drawer_layout);
        btnQuestion = findViewById(R.id.btn_question);
        btnMore = findViewById(R.id.btn_more);

        sendButton.setEnabled(false);
        sendButton.setBackgroundColor(Color.parseColor("#808080"));
    }

    private void setupListeners() {
        btnQuestion.setOnClickListener(v -> questionDrawer.openDrawer(drawerLayout));
        btnMore.setOnClickListener(v -> drawerLayout.openDrawer(GravityCompat.END));

        sendButton.setOnClickListener(v -> {
            if (lastLocation == null) {
                showTransientPopup("No GPS data available to send!");
                return;
            }
            gpsSender.toggleSending();
        });
    }

    @Override
    public void onNewLocation(Location location) {
        lastLocation = location;
    }

    @Override
    public void onGPSAvailabilityChanged(boolean available) { }

    private final NetworkManager.NetworkListener networkListener = new NetworkManager.NetworkListener() {
        @Override
        public void onNetworkAvailable(Network network) { }
        @Override
        public void onNetworkUnavailable() { }
    };

    private final TargetResolverManager.TargetsListener targetsListener = new TargetResolverManager.TargetsListener() {
        @Override
        public void onTargetsUpdated(Map<InetAddress, Set<Integer>> targets) { }
        @Override
        public void onAvailabilityChanged(boolean available) { }
    };

    public void showTransientPopup(String message) {
        if (currentToast != null) currentToast.cancel();

        TextView textView = new TextView(this);
        textView.setText(message);
        textView.setTextColor(Color.WHITE);
        textView.setBackground(ContextCompat.getDrawable(this, R.drawable.toast_background));
        textView.setPadding(12, 8, 12, 8);
        textView.setTextSize(16);
        textView.setGravity(Gravity.CENTER);
        textView.setMaxWidth((int) (getResources().getDisplayMetrics().widthPixels * 0.8));
        textView.setLineSpacing(0f, 1.2f);

        currentToast = new Toast(this);
        currentToast.setView(textView);
        currentToast.setGravity(Gravity.CENTER, 0, 0);
        currentToast.setDuration(Toast.LENGTH_SHORT);
        currentToast.show();
    }

    @Override
    public void onStateChanged(GPSSender.SenderState state) {
        runOnUiThread(() -> {
            sendButton.clearAnimation();
            switch (state) {
                case UNAVAILABLE:
                    sendButton.setEnabled(false);
                    sendButton.setBackgroundColor(Color.parseColor("#808080"));
                    break;
                case READY:
                    sendButton.setEnabled(true);
                    sendButton.setBackgroundColor(Color.parseColor("#00BF63"));
                    break;
                case SENDING:
                    sendButton.setEnabled(true);
                    sendButton.setBackgroundColor(Color.parseColor("#00BF63"));
                    sendButton.startAnimation(blinkAnimation);
                    break;
            }
        });
    }

    @Override
    public void onSendError(String errorMessage) {
        runOnUiThread(() -> showTransientPopup(errorMessage));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (questionDrawer != null) {
            questionDrawer.dispose();
            questionDrawer = null;
        }

        if (gpsSender != null) {
            gpsSender.dispose();
            gpsSender = null;
        }
    }
}
