package com.github.vgaj.proxy;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "ProxyPrefs";
    private static final String KEY_HOST = "server_host";
    private static final String KEY_PORT = "server_port";

    private EditText etServerHost;
    private EditText etServerPort;
    private Button btnStart;
    private Button btnStop;
    private TextView tvStatus;

    private MobileProxy proxy;
    private Thread proxyThread;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        etServerHost = findViewById(R.id.etServerHost);
        etServerPort = findViewById(R.id.etServerPort);
        btnStart = findViewById(R.id.btnStart);
        btnStop = findViewById(R.id.btnStop);
        tvStatus = findViewById(R.id.tvStatus);

        loadPreferences();

        btnStart.setOnClickListener(v -> startProxy());
        btnStop.setOnClickListener(v -> stopProxy());
    }

    private void loadPreferences() {
        SharedPreferences settings = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String host = settings.getString(KEY_HOST, "");
        int port = settings.getInt(KEY_PORT, 9999);

        etServerHost.setText(host);
        etServerPort.setText(String.valueOf(port));
    }

    private void savePreferences() {
        SharedPreferences settings = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        SharedPreferences.Editor editor = settings.edit();
        editor.putString(KEY_HOST, etServerHost.getText().toString());
        try {
            editor.putInt(KEY_PORT, Integer.parseInt(etServerPort.getText().toString()));
        } catch (NumberFormatException e) {
            // Ignore invalid port for saving
        }
        editor.apply();
    }

    @Override
    protected void onStop() {
        super.onStop();
        savePreferences();
    }

    private void startProxy() {
        String host = etServerHost.getText().toString();
        String portStr = etServerPort.getText().toString();

        if (TextUtils.isEmpty(host)) {
            etServerHost.setError("Host is required");
            return;
        }

        int port;
        try {
            port = Integer.parseInt(portStr);
        } catch (NumberFormatException e) {
            etServerPort.setError("Invalid port");
            return;
        }

        savePreferences();

        proxy = new MobileProxy(host, port);
        proxyThread = new Thread(proxy);
        proxyThread.start();

        updateUi(true);
    }

    private void stopProxy() {
        if (proxy != null) {
            proxy.stop();
            proxy = null;
        }
        if (proxyThread != null) {
            try {
                proxyThread.join(1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            proxyThread = null;
        }
        updateUi(false);
    }

    private void updateUi(boolean isRunning) {
        runOnUiThread(() -> {
            btnStart.setEnabled(!isRunning);
            btnStop.setEnabled(isRunning);
            etServerHost.setEnabled(!isRunning);
            etServerPort.setEnabled(!isRunning);
            tvStatus.setText("Status: " + (isRunning ? "Running" : "Stopped"));
        });
    }
}
