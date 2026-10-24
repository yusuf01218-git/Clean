package com.clean.app.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.util.Log;

import androidx.core.content.ContextCompat;

public class NetworkReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
        boolean isConnected = activeNetwork != null && activeNetwork.isConnected();

        Log.d("NetworkReceiver", "Network changed. Connected: " + isConnected);

        if (isConnected) {
            SharedPreferences prefs = context.getSharedPreferences("Clean", Context.MODE_PRIVATE);
            String mode = prefs.getString("mode", "");
            String port = prefs.getString("port", "9999");
            String serverIp = prefs.getString("server_ip", "");
            boolean wasRunning = prefs.getBoolean("was_running", false);

            if (!wasRunning) return;

            if ("receiver".equals(mode) && !serverIp.isEmpty()) {
                Intent serviceIntent = new Intent(context, ReceiverService.class);
                serviceIntent.setAction("START");
                serviceIntent.putExtra("server_ip", serverIp);
                serviceIntent.putExtra("port", Integer.parseInt(port));
                ContextCompat.startForegroundService(context, serviceIntent);
            }
        }
    }
}
