package com.isi.restpos;

import androidx.appcompat.app.AppCompatActivity;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.widget.Toast;

public class MainActivity extends AppCompatActivity {
    private static final String VAR = "MainActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Log.d(VAR, "Iniciando MainActivity...");

        // Solo mostrar toast de confirmación
        Toast.makeText(this, "Servicio iniciado correctamente", Toast.LENGTH_SHORT).show();

        // Iniciar el servicio de impresión en segundo plano
        Intent serviceIntent = new Intent(this, PrintService.class);

        // Compatibilidad con versiones anteriores a Android 8.0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        new Handler().postDelayed(() -> {
            Log.d(VAR, "Cerrando actividad, el servicio continúa en segundo plano");
            finish();
        }, 1000); // Solo 1 segundo para que aparezca el toast

        // Cerrar la actividad inmediatamente - el servicio sigue corriendo
        new Handler().postDelayed(() -> {
            Log.d(VAR, "Cerrando actividad, el servicio continúa en segundo plano");
            finish();
        }, 1000); // Solo 1 segundo para que aparezca el toast
    }
}