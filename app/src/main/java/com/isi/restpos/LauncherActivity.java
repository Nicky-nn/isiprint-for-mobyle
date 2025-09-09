package com.isi.restpos;

import android.app.Activity;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.os.Bundle;

public class LauncherActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Ocultar el ícono de la aplicación
        PackageManager p = getPackageManager();
        ComponentName componentName = new ComponentName(this, com.isi.restpos.LauncherActivity.class);
        p.setComponentEnabledSetting(componentName,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);

        // Iniciar la actividad principal
        startActivity(new android.content.Intent(this, MainActivity.class));

        // Finalizar esta actividad
        finish();
    }
}
