package com.clean.app.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.core.content.ContextCompat;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) ||
            Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {

            SharedPreferences prefs = context.getSharedPreferences("Clean", Context.MODE_PRIVATE);
            String mode = prefs.getString("mode", "");
            String port = prefs.getString("port", "9999");
            String serverIp = prefs.getString("server_ip", "");
            boolean wasRunning = prefs.getBoolean("was_running", false);

            if (!wasRunning) return;

            Log.d("BootReceiver", "Boot complete, restarting mode: " + mode);

            if ("sender".equals(mode)) {
                Intent serviceIntent = new Intent(context, SenderService.class);
                serviceIntent.setAction("START");
                serviceIntent.putExtra("port", Integer.parseInt(port));
                ContextCompat.startForegroundService(context, serviceIntent);
            } else if ("receiver".equals(mode) && !serverIp.isEmpty()) {
                Intent serviceIntent = new Intent(context, ReceiverService.class);
                serviceIntent.setAction("START");
                serviceIntent.putExtra("server_ip", serverIp);
                serviceIntent.putExtra("port", Integer.parseInt(port));
                ContextCompat.startForegroundService(context, serviceIntent);
            }
        }
    }
}
