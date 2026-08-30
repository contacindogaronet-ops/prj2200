package com.indogaro.net;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.TrafficStats;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.graphics.drawable.Icon;
import java.io.File;
import java.io.FileOutputStream;

public class IndogoVpnService extends VpnService {
    private ParcelFileDescriptor vpnInterface = null;
    private int nativeFd = -1;
    private boolean isRunning = false;
    private NotificationManager notifManager;
    private Notification.Builder notifBuilder;
    private String currentCluster = "UNKNOWN";

    private long lastRx = 0;
    private long lastTx = 0;
    private Handler speedHandler = new Handler(Looper.getMainLooper());

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if ("START_VPN".equals(action)) {
                currentCluster = intent.getStringExtra("CLUSTER");
                String host = intent.getStringExtra("HOST");
                int port = intent.getIntExtra("PORT", 10808);
                String proto = intent.getStringExtra("PROTO");
                startVpnTunnel(currentCluster, host, port, proto);
            } else if ("STOP_VPN".equals(action)) {
                stopVpnTunnel();
            } else if ("SWITCH_CLUSTER".equals(action)) {
                broadcastLog("SYSTEM", "🔄 Permintaan Switch Cluster dari Notifikasi dikirim...");
                Intent switchIntent = new Intent("SWITCH_CLUSTER_ACTION");
                sendBroadcast(switchIntent);
            }
        }
        return START_STICKY;
    }

    private void startVpnTunnel(String cluster, String host, int port, String proto) {
        if (vpnInterface != null) return;
        isRunning = true;
        notifManager = getSystemService(NotificationManager.class);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel("VPN_GATEWAY", "Indogo Enterprise", NotificationManager.IMPORTANCE_LOW);
            notifManager.createNotificationChannel(channel);
        }
        
        try {
            Builder builder = new Builder();
            builder.setSession("Indogo-" + cluster);
            builder.setMtu(1500);
            builder.addAddress("172.19.0.1", 24);
            builder.addRoute("0.0.0.0", 0);
            builder.addDnsServer("8.8.8.8");
            builder.addDnsServer("1.1.1.1");
            try { builder.addDisallowedApplication(getPackageName()); } catch (Exception e) {}
            vpnInterface = builder.establish();

            nativeFd = vpnInterface.getFd();
            if (nativeFd == -1) throw new Exception("Kernel menolak memberikan FD.");

            // 🔴 PERINGATAN ARSITEKTUR: Gunakan 'udp: tcp' agar DNS request dari OS dilewatkan via TCP jika Golang tidak support UDP
            File configFile = new File(getFilesDir(), "tun2socks.yml");
            String yaml = "tunnel:\n  mtu: 1500\n  ipv4: true\n  ipv6: false\nsocks5:\n  address: " + host + "\n  port: " + port + "\n  udp: 'tcp'\n";
            FileOutputStream fos = new FileOutputStream(configFile);
            fos.write(yaml.getBytes());
            fos.flush(); fos.getFD().sync(); fos.close();

            setupNotification();
            startForeground(2, notifBuilder.build());
            startSpeedometer();

            broadcastLog(cluster, "🛡️ [VPN GATEWAY] OS Tunnel Active. Engine: hev.sockstun.TProxyService");

            new Thread(() -> {
                try {
                    hev.sockstun.TProxyService.TProxyStartService(configFile.getAbsolutePath(), nativeFd);
                } catch (Throwable t) {
                    broadcastLog(cluster, "🛑 CRASH INTERCEPTED: " + t.getMessage());
                }
            }).start();

        } catch (Exception e) {
            broadcastLog(cluster, "🛑 [VPN Error] " + e.getMessage());
            stopVpnTunnel();
        }
    }

    private void setupNotification() {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
        
        Intent stopIntent = new Intent(this, IndogoVpnService.class);
        stopIntent.setAction("STOP_VPN");
        PendingIntent pStop = PendingIntent.getService(this, 0, stopIntent, flags);

        Intent switchIntent = new Intent(this, IndogoVpnService.class);
        switchIntent.setAction("SWITCH_CLUSTER");
        PendingIntent pSwitch = PendingIntent.getService(this, 1, switchIntent, flags);

        Notification.Action actionStop = new Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "DISCONNECT", pStop).build();
        Notification.Action actionSwitch = new Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_menu_directions), "SWITCH", pSwitch).build();

        notifBuilder = new Notification.Builder(this, "VPN_GATEWAY")
                .setContentTitle("Indogo VPN: " + currentCluster)
                .setContentText("Koneksi aman. Menghitung kecepatan...")
                .setSmallIcon(android.R.drawable.ic_secure)
                .setOnlyAlertOnce(true)
                .addAction(actionStop)
                .addAction(actionSwitch);
    }

    private void startSpeedometer() {
        lastRx = TrafficStats.getUidRxBytes(android.os.Process.myUid());
        lastTx = TrafficStats.getUidTxBytes(android.os.Process.myUid());
        
        speedHandler.post(new Runnable() {
            @Override
            public void run() {
                if (!isRunning) return;
                long currentRx = TrafficStats.getUidRxBytes(android.os.Process.myUid());
                long currentTx = TrafficStats.getUidTxBytes(android.os.Process.myUid());
                
                long rxDiff = currentRx - lastRx;
                long txDiff = currentTx - lastTx;
                
                lastRx = currentRx;
                lastTx = currentTx;
                
                String speedText = "↓ " + formatSpeed(rxDiff) + "  |  ↑ " + formatSpeed(txDiff);
                notifBuilder.setContentText(speedText);
                notifManager.notify(2, notifBuilder.build());
                
                speedHandler.postDelayed(this, 1000);
            }
        });
    }

    private String formatSpeed(long bytes) {
        if (bytes < 1024) return bytes + " B/s";
        if (bytes < 1048576) return (bytes / 1024) + " KB/s";
        return String.format("%.2f MB/s", bytes / 1048576.0);
    }

    private void stopVpnTunnel() {
        isRunning = false;
        speedHandler.removeCallbacksAndMessages(null);
        new Thread(() -> {
            try { hev.sockstun.TProxyService.TProxyStopService(); } catch (Throwable t) {}
        }).start();

        try {
            if (vpnInterface != null) {
                vpnInterface.close(); vpnInterface = null;
            }
        } catch (Exception e) {}
        stopForeground(true);
        stopSelf();
    }

    private void broadcastLog(String cluster, String msg) {
        Intent intent = new Intent("DAEMON_LOG");
        intent.putExtra("cluster", cluster); intent.putExtra("log", msg);
        sendBroadcast(intent);
    }

    @Override
    public void onDestroy() {
        stopVpnTunnel();
        super.onDestroy();
    }
}
