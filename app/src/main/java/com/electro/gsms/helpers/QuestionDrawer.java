package com.electro.gsms.helpers;

import android.content.Context;
import android.location.Location;
import android.net.Network;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.drawerlayout.widget.DrawerLayout;
import androidx.core.view.GravityCompat;

import com.electro.gsms.R;
import com.electro.gsms.services.GPSManager;
import com.electro.gsms.services.NetworkManager;
import com.electro.gsms.services.TargetResolverManager;

import java.net.InetAddress;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * QuestionDrawerController
 * Updated to remove any direct dependency on resolver.getResolvedTargetsMap().
 * Now it caches the latest targets received through the TargetsListener and
 * uses that cache when the drawer is opened.
 * Ensures full listener-based model (consistent with TargetResolverHelper changes).
 */
public class QuestionDrawer implements GPSManager.LocationListenerExternal {

    private final TextView gpsIndicatorText;
    private final TextView networkIndicatorText;
    private final TextView targetsIndicatorText;

    private final Animation blinkAnimation;

    private boolean lastGpsState = false;
    private boolean lastNetworkState = false;
    private boolean lastTargetsState = false;

    private final GPSManager gpsManager;
    private final NetworkManager networkManager;
    private final TargetResolverManager resolver;

    private final LinearLayout targetsListContainer;

    private final NetworkManager.NetworkListener networkListener;
    private final TargetResolverManager.TargetsListener targetsListener;
    private final DrawerLayout drawerLayout;
    private final DrawerLayout.SimpleDrawerListener drawerListener;

    // Cache of the latest targets delivered by TargetResolverHelper
    private volatile Map<InetAddress, Set<Integer>> lastKnownTargets = Collections.emptyMap();

    public QuestionDrawer(View drawerView,
                          Context context,
                          DrawerLayout drawerLayout,
                          GPSManager gpsManager,
                          NetworkManager networkManager,
                          TargetResolverManager resolver) {

        this.drawerLayout = drawerLayout;

        gpsIndicatorText = drawerView.findViewById(R.id.gps_indicator_text);
        networkIndicatorText = drawerView.findViewById(R.id.network_indicator_text);
        targetsIndicatorText = drawerView.findViewById(R.id.targets_indicator_text);

        blinkAnimation = AnimationUtils.loadAnimation(context, R.anim.blink);

        targetsListContainer = drawerView.findViewById(R.id.targets_list_container);

        this.gpsManager = gpsManager;
        this.networkManager = networkManager;
        this.resolver = resolver;

        this.gpsManager.addLocationListener(this);

        // Network listener updates network indicator
        networkListener = new NetworkManager.NetworkListener() {
            @Override
            public void onNetworkAvailable(Network network) {
                targetsListContainer.post(() -> updateNetworkIndicator(true));
            }

            @Override
            public void onNetworkUnavailable() {
                targetsListContainer.post(() -> updateNetworkIndicator(false));
            }
        };
        this.networkManager.addListener(networkListener);

        // Targets listener updates both indicator and list
        targetsListener = new TargetResolverManager.TargetsListener() {
            @Override
            public void onTargetsUpdated(Map<InetAddress, Set<Integer>> targets) {
                // Cache the latest targets for later use (e.g. drawer opened)
                lastKnownTargets = new HashMap<>(targets);
                targetsListContainer.post(() -> updateTargetsList(targets));
                updateTargetsIndicator(!targets.isEmpty());
            }

            @Override
            public void onAvailabilityChanged(boolean available) {
                targetsIndicatorText.post(() -> updateTargetsIndicator(available));
            }
        };
        this.resolver.addTargetsListener(targetsListener);

        // Drawer listener now uses lastKnownTargets instead of direct resolver call
        drawerListener = new DrawerLayout.SimpleDrawerListener() {
            @Override
            public void onDrawerOpened(View dv) {
                Map<InetAddress, Set<Integer>> snapshot;
                synchronized (QuestionDrawer.this) {
                    snapshot = new HashMap<>(lastKnownTargets);
                }
                targetsListContainer.post(() -> updateTargetsList(snapshot));
            }
        };
        this.drawerLayout.addDrawerListener(drawerListener);
    }

    @Override
    public void onNewLocation(Location location) { /* Not used here */ }

    @Override
    public void onGPSAvailabilityChanged(boolean available) {
        gpsIndicatorText.post(() -> updateGpsIndicator(available));
    }

    // --- Indicator helpers ---
    private void updateGpsIndicator(boolean state) {
        if (state != lastGpsState) {
            gpsIndicatorText.clearAnimation();
            gpsIndicatorText.setCompoundDrawablesWithIntrinsicBounds(R.drawable.circle_yellow, 0, 0, 0);
            gpsIndicatorText.startAnimation(blinkAnimation);
        }

        gpsIndicatorText.postDelayed(() -> {
            gpsIndicatorText.clearAnimation();
            gpsIndicatorText.setCompoundDrawablesWithIntrinsicBounds(
                    state ? R.drawable.circle_green : R.drawable.circle_red, 0, 0, 0);
        }, blinkAnimation.getDuration() * (blinkAnimation.getRepeatCount() + 1));

        lastGpsState = state;
    }

    private void updateNetworkIndicator(boolean state) {
        if (state != lastNetworkState) {
            networkIndicatorText.clearAnimation();
            networkIndicatorText.setCompoundDrawablesWithIntrinsicBounds(R.drawable.circle_yellow, 0, 0, 0);
            networkIndicatorText.startAnimation(blinkAnimation);
        }

        networkIndicatorText.postDelayed(() -> {
            networkIndicatorText.clearAnimation();
            networkIndicatorText.setCompoundDrawablesWithIntrinsicBounds(
                    state ? R.drawable.circle_green : R.drawable.circle_red, 0, 0, 0);
        }, blinkAnimation.getDuration() * (blinkAnimation.getRepeatCount() + 1));

        lastNetworkState = state;
    }

    private void updateTargetsIndicator(boolean state) {
        if (state != lastTargetsState) {
            targetsIndicatorText.clearAnimation();
            targetsIndicatorText.setCompoundDrawablesWithIntrinsicBounds(R.drawable.circle_yellow, 0, 0, 0);
            targetsIndicatorText.startAnimation(blinkAnimation);
        }

        targetsIndicatorText.postDelayed(() -> {
            targetsIndicatorText.clearAnimation();
            targetsIndicatorText.setCompoundDrawablesWithIntrinsicBounds(
                    state ? R.drawable.circle_green : R.drawable.circle_red, 0, 0, 0);
        }, blinkAnimation.getDuration() * (blinkAnimation.getRepeatCount() + 1));

        lastTargetsState = state;
    }

    // --- Targets UI list ---
    private void updateTargetsList(Map<InetAddress, Set<Integer>> targets) {
        Context context = targetsListContainer.getContext();
        targetsListContainer.removeAllViews();

        for (Map.Entry<InetAddress, Set<Integer>> entry : targets.entrySet()) {
            InetAddress address = entry.getKey();
            Set<Integer> ports = entry.getValue();

            // Main IP line
            TextView ipTextView = new TextView(context);
            ipTextView.setText(address.getHostAddress());
            ipTextView.setTextColor(0xFFFFFFFF);
            ipTextView.setTextSize(16);
            ipTextView.setPadding(8, 8, 8, 4);
            targetsListContainer.addView(ipTextView);

            // Nested ports list
            if (ports != null) {
                for (Integer port : ports) {
                    TextView portTextView = new TextView(context);
                    portTextView.setText(context.getString(R.string.port_label, port));
                    portTextView.setTextColor(0xFFAAAAAA);
                    portTextView.setTextSize(14);
                    portTextView.setPadding(32, 2, 8, 2);
                    targetsListContainer.addView(portTextView);
                }
            }
        }
    }

    public void openDrawer(DrawerLayout drawerLayout) {
        drawerLayout.openDrawer(GravityCompat.START);
    }

    public void dispose() {
        try { gpsManager.removeLocationListener(this); } catch (Exception ignored) {}
        try { networkManager.removeListener(networkListener); } catch (Exception ignored) {}
        try { resolver.removeTargetsListener(targetsListener); } catch (Exception ignored) {}
        try { drawerLayout.removeDrawerListener(drawerListener); } catch (Exception ignored) {}

        gpsIndicatorText.clearAnimation();
        networkIndicatorText.clearAnimation();
        targetsIndicatorText.clearAnimation();
    }
}
