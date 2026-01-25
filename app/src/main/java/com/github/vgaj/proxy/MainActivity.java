package com.github.vgaj.proxy;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
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
    private static final String KEY_AUTH_CODE = "auth_code";

    private EditText etServerHost;
    private EditText etServerPort;
    private EditText etAuthCode;
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
        etAuthCode = findViewById(R.id.etAuthCode);
        btnStart = findViewById(R.id.btnStart);
        btnStop = findViewById(R.id.btnStop);
        tvStatus = findViewById(R.id.tvStatus);

        loadPreferences();

        TextWatcher validationWatcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                validateInputs();
            }
        };

        etServerHost.addTextChangedListener(validationWatcher);
        etServerPort.addTextChangedListener(validationWatcher);
        etAuthCode.addTextChangedListener(validationWatcher);

        validateInputs();

        btnStart.setOnClickListener(v -> startProxy());
        btnStop.setOnClickListener(v -> stopProxy());
    }

    private void validateInputs() {
        String host = etServerHost.getText().toString();
        String port = etServerPort.getText().toString();
        String authCode = etAuthCode.getText().toString();

        boolean hostValid = !TextUtils.isEmpty(host);
        boolean portValid = !TextUtils.isEmpty(port);
        boolean authCodeValid = authCode.length() == 9;

        btnStart.setEnabled(hostValid && portValid && authCodeValid);
    }

    private void loadPreferences() {
        SharedPreferences settings = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String host = settings.getString(KEY_HOST, "");
        int port = settings.getInt(KEY_PORT, 9999);
        String authCode = settings.getString(KEY_AUTH_CODE, "123456789");

        etServerHost.setText(host);
        etServerPort.setText(String.valueOf(port));
        etAuthCode.setText(authCode);
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
        editor.putString(KEY_AUTH_CODE, etAuthCode.getText().toString());
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

        String authCode = etAuthCode.getText().toString();
        proxy = new MobileProxy(host, port, authCode);
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
            btnStop.setEnabled(isRunning);
            etServerHost.setEnabled(!isRunning);
            etServerPort.setEnabled(!isRunning);
            etAuthCode.setEnabled(!isRunning);
            tvStatus.setText("Status: " + (isRunning ? "Running" : "Stopped"));
            if (isRunning) {
                btnStart.setEnabled(false);
            } else {
                validateInputs();
            }
        });
    }
}
