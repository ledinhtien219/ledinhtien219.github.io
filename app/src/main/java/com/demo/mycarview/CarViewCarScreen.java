package com.demo.mycarview;

import android.Manifest;
import android.content.pm.PackageManager;

import androidx.annotation.NonNull;
import androidx.car.app.CarContext;
import androidx.car.app.Screen;
import androidx.car.app.model.Template;
import androidx.car.app.model.PlaceListMapTemplate;

/**
 * CarView Drive now delegates the map to the Android Auto host.
 *
 * This removes the old fake app-owned road surface. The host renders the actual
 * map and current-location layer, while the separate SpeedBubbleService remains
 * responsible for the draggable speed overlay on devices/setups where the phone
 * overlay is visible over the projected UI.
 */
public class CarViewCarScreen extends Screen {

    public CarViewCarScreen(@NonNull CarContext carContext) {
        super(carContext);
    }

    @NonNull
    @Override
    public Template onGetTemplate() {
        boolean hasLocation = getCarContext().checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || getCarContext().checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;

        // With no title/header/list, Android Auto gives almost the whole screen to
        // its host-rendered map. Current location is shown whenever permission is available.
        return new PlaceListMapTemplate.Builder()
                .setCurrentLocationEnabled(hasLocation)
                .build();
    }
}
