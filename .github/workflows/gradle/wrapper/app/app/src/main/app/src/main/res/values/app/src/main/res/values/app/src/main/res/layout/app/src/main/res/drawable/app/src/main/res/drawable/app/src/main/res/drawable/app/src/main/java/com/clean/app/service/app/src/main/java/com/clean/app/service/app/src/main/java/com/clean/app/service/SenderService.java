package com.clean.app.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.wifi.WifiManager;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.clean.app.R;
import com.clean.app.ui.MainActivity;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SenderService extends Service {

    private static final String TAG = "SenderService";
    private static final String CHANNEL_ID = "CleanSender";

    public static final int SAMPLE_RATE = 44100;
    public static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    public static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;

    public static final int PACKET_SAMPLES = 441;
    public static final int PACKET_BYTES = PACKET_SAMPLES * 2;

    private boolean isRunning = false;
    private int udpPort;
    private int tcpPort;

    private DatagramSocket udpSocket;
    private ServerSocket tcpControlSocket;
    private AudioRecord audioRecord;

    private final CopyOnWriteArrayList<ReceiverEntry> receivers = new CopyOnWriteArrayList<>();
    private ExecutorService executor;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    private static class ReceiverEntry {
        final InetAddress address;
        final int port;
        volatile long lastSeen;

        ReceiverEntry(InetAddress address, int port) {
            this.address = address;
            this.port = port;
            this.lastSeen = System.currentTimeMillis();
        }

        String key() { return address.getHostAddress() + ":" + port; }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        executor = Executors.newCachedThreadPool();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_STICKY;
        if ("START".equals(intent.getAction())) {
            udpPort = intent.getIntExtra("port", 9999);
            tcpPort = udpPort + 1;
            if (!isRunning) startSending();
        } else if ("STOP".equals(intent.getAction())) {
            stopSending();
            stopSelf();
        }
        return START_STICKY;
    }

    private void startSending() {
        isRunning = true;
        acquireLocks();
        startForeground(1, buildNotification("Ready on port " + udpPort));
        executor.execute(this::runTcpControlServer);
        executor.execute(this::runReceiverCleanup);
        executor.execute(this::captureAndSendAudio);
    }

    private void runTcpControlServer() {
        while (isRunning) {
            try {
                tcpControlSocket = new ServerSocket(tcpPort);
                tcpControlSocket.setSoTimeout(1000);
                while (isRunning) {
                    Socket client = null;
                    try { client = tcpControlSocket.accept(); }
                    catch (SocketTimeoutException e) { continue; }
                    final Socket c = client;
                    executor.execute(() -> handleControlClient(c));
                }
            } catch (Exception e) {
                if (isRunning) {
                    try { Thread.sleep(3000); } catch (InterruptedException ie) { break; }
                }
            } finally {
                try { if (tcpControlSocket != null) tcpControlSocket.close(); } catch (Exception ignored) {}
            }
        }
    }

    private void handleControlClient(Socket socket) {
        String entryKey = null;
        try {
            socket.setSoTimeout(15000);
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            String firstLine = reader.readLine();
            if (firstLine == null || !firstLine.startsWith("REGISTER:")) { socket.close(); return; }
            int receiverUdpPort = Integer.parseInt(firstLine.substring(9).trim());
            InetAddress addr = socket.getInetAddress();
            ReceiverEntry entry = new ReceiverEntry(addr, receiverUdpPort);
            entryKey = entry.key();
            boolean found = false;
            for (ReceiverEntry r : receivers) {
                if (r.key().equals(entryKey)) { r.lastSeen = System.currentTimeMillis(); found = true; break; }
            }
            if (!found) {
                receivers.add(entry);
                updateNotification("Streaming to " + receivers.size() + " receiver(s)");
            }
            String line;
            while ((line = reader.readLine()) != null) {
                if ("PING".equals(line.trim())) {
                    for (ReceiverEntry r : receivers) {
                        if (r.key().equals(entryKey)) { r.lastSeen = System.currentTimeMillis(); break; }
                    }
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "Control client gone: " + entryKey);
        } finally {
            try { socket.close(); } catch (Exception ignored) {}
        }
    }

    private void runReceiverCleanup() {
        while (isRunning) {
            try {
                Thread.sleep(5000);
                long now = System.currentTimeMillis();
                for (ReceiverEntry r : receivers) {
                    if (now - r.lastSeen > 20000) {
                        receivers.remove(r);
                        updateNotification("Streaming to " + receivers.size() + " receiver(s)");
                    }
                }
            } catch (InterruptedException e) { break; }
        }
    }

    private void captureAndSendAudio() {
        int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        int recordBuf = Math.max(minBuf, PACKET_BYTES * 8);
        byte[] audioBuffer = new byte[recordBuf];
        byte[] udpPayload = new byte[4 + PACKET_BYTES];
        int seqNum = 0;

        while (isRunning) {
            try {
                udpSocket = new DatagramSocket();
                udpSocket.setSendBufferSize(64 * 1024);
                audioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, recordBuf
                );
                if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                    Thread.sleep(2000);
                    continue;
                }
                audioRecord.startRecording();
                int accum = 0;
                while (isRunning) {
                    int toRead = Math.min(PACKET_BYTES - accum, recordBuf - accum);
                    int n = audioRecord.read(audioBuffer, accum, toRead);
                    if (n < 0) break;
                    accum += n;
                    if (accum >= PACKET_BYTES) {
                        udpPayload[0] = (byte)(seqNum >> 24);
                        udpPayload[1] = (byte)(seqNum >> 16);
                        udpPayload[2] = (byte)(seqNum >> 8);
                        udpPayload[3] = (byte)(seqNum);
                        System.arraycopy(audioBuffer, 0, udpPayload, 4, PACKET_BYTES);
                        seqNum++;
                        for (ReceiverEntry r : receivers) {
                            try {
                                DatagramPacket pkt = new DatagramPacket(
                                    udpPayload, udpPayload.length, r.address, r.port
                                );
                                udpSocket.send(pkt);
                            } catch (Exception e) {
                                Log.w(TAG, "Send fail to " + r.key());
                            }
                        }
                        accum = 0;
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Audio/UDP error: " + e.getMessage());
                try { Thread.sleep(2000); } catch (InterruptedException ie) { break; }
            } finally {
                if (audioRecord != null) {
                    try { audioRecord.stop(); audioRecord.release(); } catch (Exception ignored) {}
                    audioRecord = null;
                }
                if (udpSocket != null) { udpSocket.close(); udpSocket = null; }
            }
        }
    }

    private void stopSending() {
        isRunning = false;
        receivers.clear();
        try { if (udpSocket != null) udpSocket.close(); } catch (Exception ignored) {}
        try { if (tcpControlSocket != null) tcpControlSocket.close(); } catch (Exception ignored) {}
        if (audioRecord != null) {
            try { audioRecord.stop(); audioRecord.release(); } catch (Exception ignored) {}
            audioRecord = null;
        }
        releaseLocks();
    }

    private void acquireLocks() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Clean:SenderLock");
        wakeLock.acquire();
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Clean:SenderWifi");
        wifiLock.acquire();
    }

    private void releaseLocks() {
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) {}
        try { if (wifiLock != null && wifiLock.isHeld()) wifiLock.release(); } catch (Exception ignored) {}
    }

    private Notification buildNotification(String text) {
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Sender", NotificationManager.IMPORTANCE_LOW);
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
        PendingIntent pi = PendingIntent.getActivity(this, 0,
            new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Clean Sender")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(pi)
            .setOngoing(true).build();
    }

    private void updateNotification(String text) {
        Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Clean Sender")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_mic)
            .setOngoing(true).build();
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(1, n);
    }

    public static String getDeviceIp(Context context) {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (!addr.isLoopbackAddress() && addr.getHostAddress().contains("."))
                        return addr.getHostAddress();
                }
            }
        } catch (Exception ignored) {}
        return "Unknown";
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        stopSending();
        if (executor != null) executor.shutdownNow();
        super.onDestroy();
    }
}
