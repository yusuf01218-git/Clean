package com.clean.app.ui;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.clean.app.R;
import com.clean.app.service.ReceiverService;
import com.clean.app.service.SenderService;

public class MainActivity extends AppCompatActivity {

    private SharedPreferences prefs;
    private RadioGroup rgMode;
    private RadioButton rbSender, rbReceiver;
    private LinearLayout senderLayout, receiverLayout;
    private EditText etPort, etPortReceiver, etServerIp;
    private TextView tvStatus, tvMyIp;
    private Button btnToggle;
    private boolean isRunning = false;

    private final ActivityResultLauncher<String[]> permissionLauncher =
        registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
            boolean allGranted = true;
            for (boolean granted : result.values()) {
                if (!granted) { allGranted = false; break; }
            }
            if (allGranted) startAudioService();
            else Toast.makeText(this, "Permissions required!", Toast.LENGTH_LONG).show();
        });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("Clean", MODE_PRIVATE);

        rgMode = findViewById(R.id.rgMode);
        rbSender = findViewById(R.id.rbSender);
        rbReceiver = findViewById(R.id.rbReceiver);
        senderLayout = findViewById(R.id.senderLayout);
        receiverLayout = findViewById(R.id.receiverLayout);
        etPort = findViewById(R.id.etPort);
        etPortReceiver = findViewById(R.id.etPortReceiver);
        etServerIp = findViewById(R.id.etServerIp);
        tvStatus = findViewById(R.id.tvStatus);
        tvMyIp = findViewById(R.id.tvMyIp);
        btnToggle = findViewById(R.id.btnToggle);

        String savedMode = prefs.getString("mode", "sender");
        String savedPort = prefs.getString("port", "9999");
        etPort.setText(savedPort);
        etPortReceiver.setText(savedPort);
        etServerIp.setText(prefs.getString("server_ip", ""));

        if ("sender".equals(savedMode)) {
            rbSender.setChecked(true);
            senderLayout.setVisibility(View.VISIBLE);
            receiverLayout.setVisibility(View.GONE);
        } else {
            rbReceiver.setChecked(true);
            senderLayout.setVisibility(View.GONE);
            receiverLayout.setVisibility(View.VISIBLE);
        }

        rgMode.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.rbSender) {
                senderLayout.setVisibility(View.VISIBLE);
                receiverLayout.setVisibility(View.GONE);
            } else {
                senderLayout.setVisibility(View.GONE);
                receiverLayout.setVisibility(View.VISIBLE);
            }
        });

        String myIp = SenderService.getDeviceIp(this);
        tvMyIp.setText("My IP: " + myIp);

        btnToggle.setOnClickListener(v -> {
            if (isRunning) stopAudioService();
            else checkPermissionsAndStart();
        });

        requestBatteryOptimization();
    }

    private void checkPermissionsAndStart() {
        saveSettings();

        java.util.List<String> needed = new java.util.ArrayList<>();
        if (rbSender.isChecked()) needed.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS);
        }

        String[] toRequest = needed.stream()
            .filter(p -> ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED)
            .toArray(String[]::new);

        if (toRequest.length == 0) startAudioService();
        else permissionLauncher.launch(toRequest);
    }

    private void startAudioService() {
        String portStr = rbSender.isChecked()
            ? etPort.getText().toString().trim()
            : etPortReceiver.getText().toString().trim();

        if (portStr.isEmpty()) {
            Toast.makeText(this, "Port enter karo", Toast.LENGTH_SHORT).show();
            return;
        }
        int port = Integer.parseInt(portStr);

        Intent intent;
        if (rbSender.isChecked()) {
            intent = new Intent(this, SenderService.class);
            intent.putExtra("port", port);
            tvStatus.setText("Sending on port " + port);
        } else {
            String ip = etServerIp.getText().toString().trim();
            if (ip.isEmpty()) {
                Toast.makeText(this, "Phone A ka IP enter karo", Toast.LENGTH_SHORT).show();
                return;
            }
            intent = new Intent(this, ReceiverService.class);
            intent.putExtra("server_ip", ip);
            intent.putExtra("port", port);
            tvStatus.setText("Connecting to " + ip + "...");
        }
        intent.setAction("START");
        ContextCompat.startForegroundService(this, intent);

        prefs.edit().putBoolean("was_running", true).apply();

        isRunning = true;
        btnToggle.setText("STOP");
        btnToggle.setBackgroundColor(0xFFE53935);
    }

    private void stopAudioService() {
        Intent intent;
        if (rbSender.isChecked()) intent = new Intent(this, SenderService.class);
        else intent = new Intent(this, ReceiverService.class);
        intent.setAction("STOP");
        startService(intent);

        prefs.edit().putBoolean("was_running", false).apply();

        isRunning = false;
        btnToggle.setText("START");
        btnToggle.setBackgroundColor(0xFF43A047);
        tvStatus.setText("Stopped");
    }

    private void saveSettings() {
        String port = rbSender.isChecked()
            ? etPort.getText().toString().trim()
            : etPortReceiver.getText().toString().trim();

        prefs.edit()
            .putString("mode", rbSender.isChecked() ? "sender" : "receiver")
            .putString("port", port)
            .putString("server_ip", etServerIp.getText().toString().trim())
            .apply();
    }

    private void requestBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (!pm.isIgnoringBatteryOptimizations(getPackageName())) {
                try {
                    Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                } catch (Exception ignored) {}
            }
        }
    }
}
