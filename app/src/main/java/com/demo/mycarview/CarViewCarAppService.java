package com.demo.mycarview;

import android.content.pm.ApplicationInfo;

import androidx.annotation.NonNull;
import androidx.car.app.CarAppService;
import androidx.car.app.Session;
import androidx.car.app.validation.HostValidator;

/**
 * Android Auto templated entry point.
 *
 * This replaces the old MediaBrowser-only launcher entry. Android Auto binds to
 * this service and opens CarView Drive as its own templated car screen.
 */
public class CarViewCarAppService extends CarAppService {

    @NonNull
    @Override
    public HostValidator createHostValidator() {
        // Debug APKs are intentionally permissive so DHU / real-device testing works.
        // Release builds use the Android for Cars sample host allow-list.
        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR;
        }
        return new HostValidator.Builder(this)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build();
    }

    @NonNull
    @Override
    public Session onCreateSession() {
        return new CarViewCarSession();
    }
}
