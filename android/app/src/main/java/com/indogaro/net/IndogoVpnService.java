package com.indogaro.net;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.net.ProxyInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;

public class IndogoVpnService extends VpnService {
    private ParcelFileDescriptor vpnInterface = null;
    private boolean isRunning = false;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if ("START_VPN".equals(action)) {
                String cluster = intent.getStringExtra("CLUSTER");
                String host = intent.getStringExtra("HOST");
                int port = intent.getIntExtra("PORT", 10808);
                String proto = intent.getStringExtra("PROTO");
                startVpnTunnel(cluster, host, port, proto);
            } else if ("STOP_VPN".equals(action)) {
                stopVpnTunnel();
            }
        }
        return START_STICKY;
    }

    private void startVpnTunnel(String cluster, String host, int port, String proto) {
        if (vpnInterface != null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel("VPN_GATEWAY", "Indogo VPN Gateway", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
        Notification notif = new Notification.Builder(this, "VPN_GATEWAY")
                .setContentTitle("Indogo VPN Gateway Active")
                .setContentText("Kernel Routing via OS Proxy @" + host + ":" + port)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .build();
        startForeground(2, notif);

        try {
            Builder builder = new Builder();
            builder.setSession("Indogo-" + cluster);
            builder.setMtu(1500);
            
            builder.addAddress("172.19.0.1", 24);
            builder.addRoute("0.0.0.0", 0);
            builder.addDnsServer("8.8.8.8");
            builder.addDnsServer("1.1.1.1");

            // 🔴 KUNCI ARSITEKTUR 1: Anti-Looping (Mem-bypass aplikasi Indogo sendiri)
            try { builder.addDisallowedApplication(getPackageName()); } catch (Exception e) {}

            // 🔴 KUNCI ARSITEKTUR 2: Delegasi OS-Level TCP/IP Translation
            // Kernel Android yang akan mengubah Raw IP Packet menjadi koneksi Proxy, 
            // menghilangkan kebutuhan Tun2Socks manual di layer Java.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                builder.setHttpProxy(ProxyInfo.buildDirectProxy(host, port));
            }

            vpnInterface = builder.establish();
            isRunning = true;

            broadcastLog(cluster, "🛡️ [VPN GATEWAY] OS-Level Tunnel Established -> " + host + ":" + port);
            broadcastLog(cluster, "🔒 [ANTI-LOOP] com.indogaro.net sukses di-bypass oleh Kernel Android.");
            broadcastLog(cluster, "⚠️ [PERINGATAN ARSITEK] Kernel mem-parsing traffic ini sebagai HTTP Proxy.");

        } catch (Exception e) {
            broadcastLog(cluster, "🛑 [VPN Error] " + e.getMessage());
        }
    }

    private void stopVpnTunnel() {
        isRunning = false;
        try {
            if (vpnInterface != null) {
                vpnInterface.close();
                vpnInterface = null;
            }
        } catch (Exception e) {}
        stopForeground(true);
        stopSelf();
    }

    private void broadcastLog(String cluster, String msg) {
        Intent intent = new Intent("DAEMON_LOG");
        intent.putExtra("cluster", cluster);
        intent.putExtra("log", msg);
        sendBroadcast(intent);
    }

    @Override
    public void onDestroy() {
        stopVpnTunnel();
        super.onDestroy();
    }
}
