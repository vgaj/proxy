package com.github.vgaj.proxy;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity implements MobileProxy.ConnectionListener {

    private static final String PREFS_NAME = "ProxyPrefs";
    private static final String KEY_HOST = "server_host";
    private static final String KEY_PORT = "server_port";
    private static final String KEY_AUTH_CODE = "auth_code";

    private EditText etServerHost;
    private EditText etServerPort;
    private EditText etAuthCode;
    private Button btnStart;
    private Button btnStop;
    private Button btnClearLog;
    private Button btnHelp;
    private TextView tvStatus;
    private TextView tvLog;
    private ScrollView svLog;
    private MobileProxy proxy;
    private Thread proxyThread;
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        etServerHost = findViewById(R.id.etServerHost);
        etServerPort = findViewById(R.id.etServerPort);
        etAuthCode = findViewById(R.id.etAuthCode);
        btnStart = findViewById(R.id.btnStart);
        btnStop = findViewById(R.id.btnStop);
        btnClearLog = findViewById(R.id.btnClearLog);
        btnHelp = findViewById(R.id.btnHelp);
        tvStatus = findViewById(R.id.tvStatus);
        tvLog = findViewById(R.id.tvLog);
        svLog = findViewById(R.id.svLog);

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
        btnClearLog.setOnClickListener(v -> tvLog.setText(""));
        btnHelp.setOnClickListener(v -> showHelp());
    }

    private void validateInputs() {
        String host = etServerHost.getText().toString();
        String port = etServerPort.getText().toString();
        String authCode = etAuthCode.getText().toString();

        boolean hostValid = !TextUtils.isEmpty(host);
        boolean portValid = !TextUtils.isEmpty(port);
        boolean authCodeValid = authCode.length() >= 4;

        btnStart.setEnabled(hostValid && portValid && authCodeValid);
    }

    private void loadPreferences() {
        SharedPreferences settings = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String host = settings.getString(KEY_HOST, "");
        int port = settings.getInt(KEY_PORT, 9999);
        String authCode = settings.getString(KEY_AUTH_CODE, "5678");

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
        proxy.setConnectionListener(this);
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

    private int calculateMaxLogLines() {
        int scrollViewHeight = svLog.getHeight() - svLog.getPaddingTop() - svLog.getPaddingBottom();
        if (scrollViewHeight <= 0) {
            return 20; // Fallback before layout
        }
        int lineHeight = tvLog.getLineHeight();
        if (lineHeight <= 0) {
            return 20; // Fallback
        }
        return Math.max(5, scrollViewHeight / lineHeight);
    }

    private void appendLog(String message) {
        runOnUiThread(() -> {
            String timestamp = timeFormat.format(new Date());
            String logEntry = "[" + timestamp + "] " + message;
            String currentText = tvLog.getText().toString();
            String[] lines = currentText.isEmpty() ? new String[0] : currentText.split("\n");

            StringBuilder sb = new StringBuilder();
            int maxLines = calculateMaxLogLines();
            int startIndex = Math.max(0, lines.length - maxLines + 1);
            for (int i = startIndex; i < lines.length; i++) {
                sb.append(lines[i]).append("\n");
            }
            sb.append(logEntry);

            tvLog.setText(sb.toString());
            svLog.post(() -> svLog.fullScroll(ScrollView.FOCUS_DOWN));
        });
    }

    @Override
    public void onRequestReceived(String request) {
        String firstLine = request != null ? request.split("\r\n")[0] : null;
        appendLog("REQUESTED: " + firstLine);
    }

    @Override
    public void onConnectionSuccess(String host, int port) {
        appendLog("CONNECTED: " + host + ":" + port);
    }

    @Override
    public void onConnectionFailure(String host, int port, String request, String error) {
        String firstLine = request.split("\r\n")[0];
        appendLog("FAILED: " + host + ":" + port + " - " + error + "\n    Request: " + firstLine);
    }

    @Override
    public void onServerConnectionFailed(String error, int retryMinutes) {
        appendLog("SERVER ERROR: " + error + " - retrying in "
                + retryMinutes + (retryMinutes == 1 ? " minute" : " minutes")
        );
    }

    @Override
    public void onServerConnectionAttempt(String serverHost, int serverPort) {
        appendLog("CONNECTING TO SERVER: " + serverHost + ":" + serverPort);
    }

    @Override
    public void onMaxConnectionsReached(int max) {
        appendLog("MAX CONNECTIONS: " + max + " reached, not creating more");
    }

    private void showHelp() {
        String helpText =
            "This app allows you to route a desktop browser's traffic through this phone.\n\n" +
            "1. Run the Proxy Server on a home machine (replace XXXX with your chosen password):\n\n" +
            "docker run -d -p 8888:8888 -p 9999:9999 -e AUTH_CODE=XXXX registry.gitlab.com/viru7/proxy:latest\n\n" +
            "2. On the home router, ensure port 9999 is forwarded to the server.\n\n" +
            "3. Configure this app. Enter the server's public IP address, port (9999), and password, then tap Start.\n\n" +
            "4. Configure your browser. Set the browser's proxy to the server's IP address and port 8888.";

        new AlertDialog.Builder(this)
            .setTitle("Setup")
            .setMessage(helpText)
            .setPositiveButton("OK", null)
            .show();
    }

}
