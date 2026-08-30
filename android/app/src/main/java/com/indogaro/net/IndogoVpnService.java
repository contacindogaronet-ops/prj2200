package com.indogaro.net;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.Icon;
import android.net.TrafficStats;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
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

    private void safeAddRoute(Builder builder, String ip, int prefix) {
        try { builder.addRoute(ip, prefix); } catch (Exception ignored) {}
    }

    private void safeAddDns(Builder builder, String dns) {
        try { builder.addDnsServer(dns); } catch (Exception ignored) {}
    }

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
            }
        }
        return START_STICKY;
    }

    private void startVpnTunnel(String cluster, String host, int port, String proto) {
        if (vpnInterface != null) return;
        isRunning = true;
        notifManager = getSystemService(NotificationManager.class);

        SharedPreferences prefs = getSharedPreferences("IndogoPrefs", MODE_PRIVATE);
        boolean enableSniffing = prefs.getBoolean("sniffing", true);
        boolean enableUdp = prefs.getBoolean("udp", true);
        boolean enableIPv6 = prefs.getBoolean("ipv6", true);
        boolean enableLocalDns = prefs.getBoolean("local_dns", true);
        boolean enableFakeDns = prefs.getBoolean("fakedns", false);
        boolean bypassLan = prefs.getBoolean("bypass_lan", false);
        int mtu = prefs.getInt("mtu", 1500);
        String dns = prefs.getString("dns", "1.1.1.1");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel("VPN_GATEWAY", "Indogo Enterprise", NotificationManager.IMPORTANCE_LOW);
            notifManager.createNotificationChannel(channel);
        }
        
        try {
            Builder builder = new Builder();
            builder.setSession("Indogo-" + cluster);
            builder.setMtu(mtu); 
            
            try { builder.addAddress("10.10.14.2", 24); } catch (Exception e) {}
            
            if (bypassLan) {
                safeAddRoute(builder, "1.0.0.0", 8); safeAddRoute(builder, "2.0.0.0", 7);
                safeAddRoute(builder, "4.0.0.0", 6); safeAddRoute(builder, "8.0.0.0", 7);
                safeAddRoute(builder, "11.0.0.0", 8); safeAddRoute(builder, "12.0.0.0", 6);
                safeAddRoute(builder, "16.0.0.0", 4); safeAddRoute(builder, "32.0.0.0", 3);
                safeAddRoute(builder, "64.0.0.0", 2); safeAddRoute(builder, "128.0.0.0", 3);
                safeAddRoute(builder, "160.0.0.0", 5); safeAddRoute(builder, "168.0.0.0", 6);
                safeAddRoute(builder, "172.0.0.0", 12); safeAddRoute(builder, "172.32.0.0", 11);
                safeAddRoute(builder, "172.64.0.0", 10); safeAddRoute(builder, "172.128.0.0", 9);
                safeAddRoute(builder, "173.0.0.0", 8); safeAddRoute(builder, "174.0.0.0", 7);
                safeAddRoute(builder, "176.0.0.0", 4); safeAddRoute(builder, "192.0.0.0", 9);
                safeAddRoute(builder, "192.128.0.0", 11); safeAddRoute(builder, "192.160.0.0", 13);
                safeAddRoute(builder, "192.169.0.0", 16); safeAddRoute(builder, "192.170.0.0", 15);
                safeAddRoute(builder, "192.172.0.0", 14); safeAddRoute(builder, "192.176.0.0", 12);
                safeAddRoute(builder, "192.192.0.0", 10); safeAddRoute(builder, "193.0.0.0", 8);
                safeAddRoute(builder, "194.0.0.0", 7); safeAddRoute(builder, "196.0.0.0", 6);
                safeAddRoute(builder, "200.0.0.0", 5); safeAddRoute(builder, "208.0.0.0", 4);
            } else {
                safeAddRoute(builder, "0.0.0.0", 0);
            }
            
            // 🔴 KUNCI DOMAIN FAKEDNS
            if (enableFakeDns) {
                safeAddDns(builder, "198.18.0.1"); 
                if (enableIPv6) safeAddDns(builder, "fc00::1");
            } else {
                String[] dnsList = dns.split(",");
                for (String d : dnsList) {
                    String cleanDns = d.trim();
                    if (!cleanDns.isEmpty()) safeAddDns(builder, cleanDns);
                }
                if (enableLocalDns) safeAddDns(builder, "127.0.0.1");
            }

            if (enableIPv6) {
                try {
                    builder.addAddress("fc00::2", 64);
                    builder.addRoute("::", 0); 
                } catch (Exception e) {}
            }

            // 🔴 SOLUSI MUTLAK ROUTING LOOP (ANTI SPAM)
            try { builder.addDisallowedApplication(getPackageName()); } catch (Exception e) {}
            try { builder.addDisallowedApplication("com.termux"); } catch (Exception e) {} 

            vpnInterface = builder.establish();
            nativeFd = vpnInterface.getFd();
            if (nativeFd == -1) throw new Exception("Kernel menolak FD.");

            // 🔴 YAML CONFIG BUILDER
            StringBuilder yamlBuilder = new StringBuilder();
            yamlBuilder.append("tunnel:\n  mtu: ").append(mtu).append("\n  ipv4: true\n  ipv6: ").append(enableIPv6).append("\n");
            
            // Jika Anda ingin UDP-Over-TCP ke 1 port (v2rayNG style), biarkan UI UDP OFF
            String udpMode = enableUdp ? "'udp'" : "'tcp'";
            yamlBuilder.append("socks5:\n  address: ").append(host).append("\n  port: ").append(port).append("\n  udp: ").append(udpMode).append("\n");
            
            if (enableFakeDns) {
                yamlBuilder.append("fakedns:\n  ipv4_range: 198.18.0.0/15\n");
                if (enableIPv6) { yamlBuilder.append("  ipv6_range: fc00::/18\n"); }
            }

            File configFile = new File(getFilesDir(), "tun2socks.yml");
            FileOutputStream fos = new FileOutputStream(configFile);
            fos.write(yamlBuilder.toString().getBytes());
            fos.flush(); fos.getFD().sync(); fos.close();

            setupNotification();
            startForeground(2, notifBuilder.build());
            startSpeedometer();

            broadcastLog(cluster, "🛡️ [VPN GATEWAY] Settings Applied | Termux Bypassed (Loop Fixed) | FakeDNS: " + enableFakeDns);

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
        Intent switchIntent = new Intent(this, MainActivity.class);
        switchIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pSwitch = PendingIntent.getActivity(this, 1, switchIntent, flags);

        Notification.Action actionSwitch = new Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_menu_directions), "SWITCH CLUSTER", pSwitch).build();
        Notification.Action actionStop = new Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "DISCONNECT", pStop).build();

        notifBuilder = new Notification.Builder(this, "VPN_GATEWAY")
                .setContentTitle("Indogo ➔ " + currentCluster)
                .setContentText("Routing aktif...")
                .setSmallIcon(android.R.drawable.ic_secure)
                .setOnlyAlertOnce(true)
                .addAction(actionSwitch)
                .addAction(actionStop);
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
                lastRx = currentRx; lastTx = currentTx;
                notifBuilder.setContentText("▼ " + formatSpeed(rxDiff) + "   |   ▲ " + formatSpeed(txDiff));
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
        try { if (vpnInterface != null) { vpnInterface.close(); vpnInterface = null; } } catch (Exception e) {}
        stopForeground(true);
        stopSelf();
    }

    private void broadcastLog(String cluster, String msg) {
        Intent intent = new Intent("DAEMON_LOG");
        intent.putExtra("cluster", cluster); intent.putExtra("log", msg);
        sendBroadcast(intent);
    }

    @Override
    public void onDestroy() { stopVpnTunnel(); super.onDestroy(); }
}
