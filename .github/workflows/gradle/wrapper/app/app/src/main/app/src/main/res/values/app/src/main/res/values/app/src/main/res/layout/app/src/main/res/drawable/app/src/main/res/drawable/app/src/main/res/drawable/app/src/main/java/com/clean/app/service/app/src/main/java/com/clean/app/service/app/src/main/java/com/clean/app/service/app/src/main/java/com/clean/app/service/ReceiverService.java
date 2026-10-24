package com.clean.app.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.net.wifi.WifiManager;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.clean.app.R;
import com.clean.app.ui.MainActivity;

import java.io.PrintWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;

public class ReceiverService extends Service {

    private static final String TAG = "ReceiverService";
    private static final String CHANNEL_ID = "CleanReceiver";

    private static final int INITIAL_RETRY_MS = 3000;
    private static final int MAX_RETRY_MS = 30000;
    private static final int JITTER_BUFFER_PACKETS = 5;
    private static final int MAX_BUFFER_PACKETS = 25;

    private boolean isRunning = false;
    private String serverIp = "";
    private int serverUdpPort;
    private int serverTcpPort;

    private DatagramSocket udpSocket;
    private Socket tcpSocket;
    private AudioTrack audioTrack;
    private ExecutorService executor;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    private LinkedBlockingQueue<byte[]> jitterBuffer;

    @Override
    public void onCreate() {
        super.onCreate();
        executor = Executors.newCachedThreadPool();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            android.content.SharedPreferences p = getSharedPreferences("Clean", MODE_PRIVATE);
            serverIp = p.getString("server_ip", "");
            serverUdpPort = Integer.parseInt(p.getString("port", "9999"));
            serverTcpPort = serverUdpPort + 1;
            if ("receiver".equals(p.getString("mode", "")) && !serverIp.isEmpty() && !isRunning)
                startReceiving();
            return START_STICKY;
        }
        if ("START".equals(intent.getAction())) {
            serverIp = intent.getStringExtra("server_ip");
            serverUdpPort = intent.getIntExtra("port", 9999);
            serverTcpPort = serverUdpPort + 1;
            if (!isRunning) startReceiving();
        } else if ("STOP".equals(intent.getAction())) {
            stopReceiving();
            stopSelf();
        }
        return START_STICKY;
    }

    private void startReceiving() {
        isRunning = true;
        acquireLocks();
        jitterBuffer = new LinkedBlockingQueue<>(MAX_BUFFER_PACKETS);
        startForeground(2, buildNotification("Connecting to " + serverIp));
        executor.execute(this::runControlAndUdp);
        executor.execute(this::runAudioPlayback);
    }

    private void runControlAndUdp() {
        int retryDelay = INITIAL_RETRY_MS;

        while (isRunning) {
            try {
                udpSocket = new DatagramSocket();
                udpSocket.setReceiveBufferSize(128 * 1024);
                udpSocket.setSoTimeout(3000);
                int myUdpPort = udpSocket.getLocalPort();

                tcpSocket = new Socket(serverIp, serverTcpPort);
                tcpSocket.setTcpNoDelay(true);
                tcpSocket.setKeepAlive(true);
                PrintWriter writer = new PrintWriter(tcpSocket.getOutputStream(), true);
                writer.println("REGISTER:" + myUdpPort);

                retryDelay = INITIAL_RETRY_MS;
                updateNotification("Live from " + serverIp);

                final Socket tcpRef = tcpSocket;
                executor.execute(() -> {
                    try {
                        PrintWriter w = new PrintWriter(tcpRef.getOutputStream(), true);
                        while (!tcpRef.isClosed() && isRunning) {
                            Thread.sleep(5000);
                            w.println("PING");
                        }
                    } catch (Exception ignored) {}
                });

                byte[] buf = new byte[4 + SenderService.PACKET_BYTES];
                int lastSeq = -1;

                while (isRunning) {
                    DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                    try {
                        udpSocket.receive(pkt);
                    } catch (java.net.SocketTimeoutException e) {
                        break;
                    }

                    if (pkt.getLength() < 4 + SenderService.PACKET_BYTES) continue;

                    int seq = ((buf[0] & 0xFF) << 24) | ((buf[1] & 0xFF) << 16)
                            | ((buf[2] & 0xFF) << 8) | (buf[3] & 0xFF);
                    lastSeq = seq;

                    byte[] audio = Arrays.copyOfRange(buf, 4, 4 + SenderService.PACKET_BYTES);

                    if (jitterBuffer.size() >= MAX_BUFFER_PACKETS) {
                        jitterBuffer.poll();
                    }
                    jitterBuffer.offer(audio);
                }

            } catch (Exception e) {
                Log.e(TAG, "Connection error: " + e.getMessage());
            } finally {
                closeConnections();
                jitterBuffer.clear();
            }

            if (isRunning) {
                updateNotification("Retrying in " + (retryDelay / 1000) + "s...");
                try { Thread.sleep(retryDelay); } catch (InterruptedException ie) { break; }
                retryDelay = Math.min(retryDelay * 2, MAX_RETRY_MS);
            }
        }
    }

    private void runAudioPlayback() {
        int minBuf = AudioTrack.getMinBufferSize(
            SenderService.SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            SenderService.AUDIO_FORMAT
        );
        int trackBuf = Math.max(minBuf, SenderService.PACKET_BYTES * 4);
        byte[] silence = new byte[SenderService.PACKET_BYTES];

        while (isRunning) {
            try {
                audioTrack = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                    .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(SenderService.SAMPLE_RATE)
                        .setEncoding(SenderService.AUDIO_FORMAT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                    .setBufferSizeInBytes(trackBuf)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setSessionId(AudioManager.AUDIO_SESSION_ID_GENERATE)
                    .build();

                audioTrack.play();

                while (isRunning && jitterBuffer.size() < JITTER_BUFFER_PACKETS) {
                    Thread.sleep(5);
                }

                while (isRunning) {
                    byte[] audio = jitterBuffer.poll();
                    if (audio != null) {
                        audioTrack.write(audio, 0, audio.length);
                    } else {
                        audioTrack.write(silence, 0, silence.length);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "AudioTrack error: " + e.getMessage());
                try { Thread.sleep(500); } catch (InterruptedException ie) { break; }
            } finally {
                if (audioTrack != null) {
                    try { audioTrack.stop(); audioTrack.release(); } catch (Exception ignored) {}
                    audioTrack = null;
                }
            }
        }
    }

    private void closeConnections() {
        try { if (udpSocket != null) udpSocket.close(); } catch (Exception ignored) {}
        try { if (tcpSocket != null) tcpSocket.close(); } catch (Exception ignored) {}
        udpSocket = null;
        tcpSocket = null;
    }

    private void stopReceiving() {
        isRunning = false;
        closeConnections();
        if (jitterBuffer != null) jitterBuffer.clear();
        if (audioTrack != null) {
            try { audioTrack.stop(); audioTrack.release(); } catch (Exception ignored) {}
            audioTrack = null;
        }
        releaseLocks();
    }

    private void acquireLocks() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Clean:ReceiverLock");
        wakeLock.acquire();
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Clean:ReceiverWifi");
        wifiLock.acquire();
    }

    private void releaseLocks() {
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) {}
        try { if (wifiLock != null && wifiLock.isHeld()) wifiLock.release(); } catch (Exception ignored) {}
    }

    private Notification buildNotification(String text) {
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Receiver", NotificationManager.IMPORTANCE_LOW);
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
        PendingIntent pi = PendingIntent.getActivity(this, 0,
            new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Clean Receiver")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_headset)
            .setContentIntent(pi)
            .setOngoing(true).build();
    }

    private void updateNotification(String text) {
        Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Clean Receiver")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_headset)
            .setOngoing(true).build();
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(2, n);
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        stopReceiving();
        if (executor != null) executor.shutdownNow();
        super.onDestroy();
    }
}
