package com.electro.dassify_application.helpers;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.location.Location;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.core.content.res.ResourcesCompat;

import com.electro.dassify_application.R;
import com.electro.dassify_application.services.GPSManager;

import org.osmdroid.api.IMapController;
import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;

import java.io.File;
import java.util.Locale;

/**
 * NonDraggableMapView
 * Subclass of MapView that overrides performClick to suppress accessibility warnings
 * caused by using setOnTouchListener directly on the MapView.
 */
class NonDraggableMapView extends MapView {
    public NonDraggableMapView(Context context) {
        super(context);
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }
}

/**
 * MiniMapController
 * Handles an OsmDroid map to display the latest GPS location.
 * The map always follows the user’s location, allows zoom, but disables manual scrolling.
 * Listens directly to GPSHelper updates.
 */
public class MiniMapController implements GPSManager.LocationListenerExternal {

    private static final String TAG = "MiniMapController";

    private final Context context;
    private final NonDraggableMapView mapView;
    private final IMapController mapController;
    private final Marker locationMarker;
    private GeoPoint lastLocation;

    private final View infoPanel;
    private final TextView leftText;
    private final TextView rightText;

    // Default fallback location (e.g., city center)
    private final GeoPoint defaultLocation = new GeoPoint(10.971354275011446, -74.7644252625715);

    public MiniMapController(Context context, FrameLayout container, GPSManager gpsManager) {
        this.context = context.getApplicationContext();

        // Setup osmdroid caching directories
        File osmBasePath = new File(context.getCacheDir(), "osmdroid");
        if (!osmBasePath.exists() && !osmBasePath.mkdirs()) {
            Log.w(TAG, "Failed to create osmdroid base path: " + osmBasePath.getAbsolutePath());
        }

        Configuration.getInstance().setUserAgentValue(context.getPackageName());
        Configuration.getInstance().setOsmdroidBasePath(osmBasePath);
        Configuration.getInstance().setOsmdroidTileCache(new File(osmBasePath, "tiles"));

        // Initialize the map view
        mapView = new NonDraggableMapView(this.context);
        mapView.setTileSource(TileSourceFactory.MAPNIK);
        mapView.setMultiTouchControls(true);

        // Disable manual map dragging but allow zoom gestures
        mapView.setOnTouchListener((v, event) -> {
            if (event.getPointerCount() == 1 && event.getAction() == MotionEvent.ACTION_MOVE) {
                v.performClick(); // Trigger accessibility click
                return true;      // Consume the drag event
            }
            return false;
        });

        // Add map to container
        container.addView(mapView);

        // Initialize map controller
        mapController = mapView.getController();
        mapController.setZoom(15.0);
        mapController.setCenter(defaultLocation);

        // Initialize marker for user location
        locationMarker = new Marker(mapView);
        Drawable redMarker = ResourcesCompat.getDrawable(context.getResources(), android.R.drawable.presence_busy, null);
        locationMarker.setIcon(redMarker);
        locationMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);

        // Inflate and setup info panel overlay
        infoPanel = LayoutInflater.from(context).inflate(R.layout.last_info_panel, container, false);
        leftText = infoPanel.findViewById(R.id.left_info);
        rightText = infoPanel.findViewById(R.id.right_info);
        container.addView(infoPanel);
        infoPanel.setVisibility(View.GONE);

        // Register as external GPS listener
        if (gpsManager != null) {
            gpsManager.addLocationListener(this);
        }
    }

    /** Callback for new GPS location */
    @Override
    public void onNewLocation(Location location) {
        updateLocation(location);
    }

    /** Callback for GPS availability changes */
    @Override
    public void onGPSAvailabilityChanged(boolean available) {
        Log.d(TAG, "GPS availability changed: " + (available ? "AVAILABLE" : "UNAVAILABLE"));
    }

    /**
     * Update the marker position on the map and move the map view to center
     * on the new location.
     */
    public void updateLocation(Location location) {
        if (location == null) return;

        lastLocation = new GeoPoint(location.getLatitude(), location.getLongitude());

        locationMarker.setPosition(lastLocation);
        if (!mapView.getOverlays().contains(locationMarker)) {
            mapView.getOverlays().add(locationMarker);
        }

        updateInfoPanel(location);

        mapController.animateTo(lastLocation);
        mapView.invalidate();
    }

    /** Update the GPS info panel with latest location, accuracy, and timestamp */
    private void updateInfoPanel(Location loc) {
        if (loc == null) return;

        leftText.setText(String.format(Locale.US,
                "Lat: %.6f\nLon: %.6f\nAlt: %.2f m",
                loc.getLatitude(),
                loc.getLongitude(),
                loc.hasAltitude() ? loc.getAltitude() : 0.0));

        rightText.setText(String.format(Locale.US,
                "Accu: %.1fm\nTime: %2$tT\n%2$td/%2$tm/%2$ty",
                loc.getAccuracy(),
                loc.getTime()));

        infoPanel.setVisibility(View.VISIBLE);
    }
}
