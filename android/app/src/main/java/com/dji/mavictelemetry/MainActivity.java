package com.dji.mavictelemetry;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.PowerManager;
import android.provider.Settings;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import dji.common.error.DJIError;
import dji.common.error.DJISDKError;
import dji.sdk.base.BaseComponent;
import dji.sdk.base.BaseProduct;
import dji.sdk.sdkmanager.DJISDKInitEvent;
import dji.sdk.sdkmanager.DJISDKManager;

public class MainActivity extends AppCompatActivity implements TelemetryStreamer.Listener {
    private static final String TAG = "MainActivity";
    private static final int REQ_PERMS = 1;
    private static final String[] PERMS = {
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.READ_PHONE_STATE,
    };

    private static final AtomicBoolean registering = new AtomicBoolean(false);
    private TelemetryStreamer streamer;
    private BaseProduct currentProduct;

    private TextView txtStatus, txtTelemetry;
    private EditText edtHost, edtPort;
    private Button btnToggle;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        txtStatus = findViewById(R.id.txtStatus);
        txtTelemetry = findViewById(R.id.txtTelemetry);
        edtHost = findViewById(R.id.edtHost);
        edtPort = findViewById(R.id.edtPort);
        btnToggle = findViewById(R.id.btnToggle);

        prefs = getSharedPreferences("cfg", MODE_PRIVATE);
        edtHost.setText(prefs.getString("host", ""));
        edtPort.setText(prefs.getString("port", "14550"));

        streamer = MApplication.streamer(this);
        streamer.setListener(this);
        if (streamer.isSending()) btnToggle.setText("Parar envio");
        btnToggle.setOnClickListener(v -> toggleStreaming());

        requestPermissionsThenRegister();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // O envio pode ter sido parado pela notificação.
        btnToggle.setText(streamer.isSending() ? "Parar envio" : "Iniciar envio");
    }

    @Override
    protected void onDestroy() {
        streamer.setListener(null);
        // O envio continua em segundo plano (TelemetryService); só o botão/notificação o param.
        super.onDestroy();
    }

    /** Sem isso, muitos celulares limitam a rede/CPU do app em segundo plano. */
    private void requestBatteryExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm == null || pm.isIgnoringBatteryOptimizations(getPackageName())) return;
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception ignored) {
        }
    }

    private void toggleStreaming() {
        if (streamer.isSending()) {
            streamer.stop();
            stopService(new Intent(this, TelemetryService.class));
            btnToggle.setText("Iniciar envio");
            return;
        }
        String host = edtHost.getText().toString().trim();
        int port;
        try {
            port = Integer.parseInt(edtPort.getText().toString().trim());
        } catch (NumberFormatException e) {
            toast("Porta inválida");
            return;
        }
        if (host.isEmpty()) {
            toast("Informe o IP do PC");
            return;
        }
        prefs.edit().putString("host", host).putString("port", String.valueOf(port)).apply();
        streamer.start(host, port);
        ContextCompat.startForegroundService(this, new Intent(this, TelemetryService.class));
        requestBatteryExemption();
        btnToggle.setText("Parar envio");
    }

    // ---- permissões / registro do SDK ----

    private void requestPermissionsThenRegister() {
        List<String> missing = new ArrayList<>();
        for (String p : PERMS) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) missing.add(p);
        }
        if (!missing.isEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            ActivityCompat.requestPermissions(this, missing.toArray(new String[0]), REQ_PERMS);
        } else {
            registerSdk();
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        registerSdk();
    }

    private void registerSdk() {
        if (!registering.compareAndSet(false, true)) {
            // Activity recriada: o SDK já foi registrado neste processo.
            BaseProduct p = DJISDKManager.getInstance().getProduct();
            if (p != null) onProduct(p);
            return;
        }
        setStatus("Registrando SDK (precisa de internet na 1ª vez)...");

        DJISDKManager.getInstance().registerApp(getApplicationContext(), new DJISDKManager.SDKManagerCallback() {
            @Override
            public void onRegister(DJIError error) {
                if (error == DJISDKError.REGISTRATION_SUCCESS) {
                    setStatus("SDK registrado. Conecte o controle remoto por USB.");
                    DJISDKManager.getInstance().startConnectionToProduct();
                } else {
                    registering.set(false);
                    setStatus("Falha no registro: " + error.getDescription());
                    Log.e(TAG, "register: " + error.getDescription());
                }
            }

            @Override
            public void onProductDisconnect() {
                if (currentProduct != null) streamer.detach(currentProduct);
                currentProduct = null;
                setStatus("Aeronave desconectada");
            }

            @Override
            public void onProductConnect(BaseProduct product) {
                onProduct(product);
            }

            @Override
            public void onProductChanged(BaseProduct product) {
                onProduct(product);
            }

            @Override
            public void onComponentChange(BaseProduct.ComponentKey key, BaseComponent oldC, BaseComponent newC) {
                // Componentes (FC, bateria, gimbal) podem conectar depois do produto.
                if (newC != null && currentProduct != null) streamer.attach(currentProduct);
            }

            @Override
            public void onInitProcess(DJISDKInitEvent event, int totalProcess) {
            }

            @Override
            public void onDatabaseDownloadProgress(long current, long total) {
            }
        });
    }

    private void onProduct(BaseProduct product) {
        if (product == null) return;
        currentProduct = product;
        streamer.attach(product);
        String model = product.getModel() != null ? product.getModel().getDisplayName() : "?";
        setStatus("Conectado: " + model);
    }

    // ---- TelemetryStreamer.Listener ----

    @Override
    public void onSnapshot(String json) {
        runOnUiThread(() -> txtTelemetry.setText(json.replace(",", ",\n")));
    }

    @Override
    public void onError(String message) {
        setStatus(message);
    }

    private void setStatus(String s) {
        runOnUiThread(() -> txtStatus.setText(s));
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
