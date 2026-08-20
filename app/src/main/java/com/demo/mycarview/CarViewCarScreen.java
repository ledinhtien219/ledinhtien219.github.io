package com.demo.mycarview;

import androidx.annotation.NonNull;
import androidx.car.app.CarContext;
import androidx.car.app.Screen;
import androidx.car.app.model.Action;
import androidx.car.app.model.MessageTemplate;
import androidx.car.app.model.Template;

/**
 * Crash-safe Android Auto entry screen for CarHUD.
 *
 * The previous PlaceListMapTemplate was built without a required item list on
 * some Android Auto hosts, which causes the host to reject the template and show
 * "app encountered an unexpected error". The actual HUD is provided by the
 * overlay service; this screen is deliberately minimal and valid on old/new hosts.
 */
public class CarViewCarScreen extends Screen {

    public CarViewCarScreen(@NonNull CarContext carContext) {
        super(carContext);
    }

    @NonNull
    @Override
    public Template onGetTemplate() {
        return new MessageTemplate.Builder(
                "CarHUD đang hoạt động. Mở Google Maps, Waze hoặc VIETMAP LIVE để dùng HUD nổi.")
                .setTitle("CarHUD")
                .setHeaderAction(Action.APP_ICON)
                .build();
    }
}
