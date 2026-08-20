package com.demo.mycarview;

import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.car.app.Screen;
import androidx.car.app.Session;

/** Session for the Android Auto templated experience. */
public class CarViewCarSession extends Session {
    @NonNull
    @Override
    public Screen onCreateScreen(@NonNull Intent intent) {
        return new CarViewCarScreen(getCarContext());
    }
}
