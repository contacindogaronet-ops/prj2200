package com.indogaro.net;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileOutputStream;

public class IndogoVpnService extends VpnService {
    private ParcelFileDescriptor vpnInterface = null;

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

            // 🔴 KUNCI ARSITEKTUR: Ekstraksi Paksa Kernel File Descriptor (FD) via Reflection
            int fd = -1;
            try {
                java.lang.reflect.Field field = java.io.FileDescriptor.class.getDeclaredField("descriptor");
                field.setAccessible(true);
                fd = field.getInt(vpnInterface.getFileDescriptor());
            } catch (Exception e) {
                broadcastLog(cluster, "🛑 Fatal: Gagal mengekstrak Kernel FD.");
                return;
            }

            // Sintesis Konfigurasi L3 Tun2Socks
            File configFile = new File(getFilesDir(), "tun2socks.yml");
            String yaml = "tunnel:\n  mtu: 1500\n  ipv4: true\n  ipv6: false\nsocks5:\n  address: " + host + "\n  port: " + port + "\n  udp: 'udp'\n";
            FileOutputStream fos = new FileOutputStream(configFile);
            fos.write(yaml.getBytes());
            fos.close();

            Notification notif = new Notification.Builder(this, "VPN_GATEWAY")
                    .setContentTitle("Indogo VPN Gateway")
                    .setContentText("Tun2Socks Active @" + host + ":" + port)
                    .setSmallIcon(android.R.drawable.ic_lock_lock)
                    .build();
            startForeground(2, notif);

            broadcastLog(cluster, "🛡️ [VPN GATEWAY] OS-Level Tunnel Established.");
            broadcastLog(cluster, "🚀 [TUN2SOCKS] Mesin C++ (hev-socks5-tunnel) mengambil alih Kernel FD: " + fd);

            // Eksekusi Mesin JNI di Thread Terpisah (Karena bersifat Blocking)
            final int finalFd = fd;
            new Thread(() -> {
                try {
                    hev.socks5.tunnel.Tunnel.TunnelMain(finalFd, configFile.getAbsolutePath());
                } catch (Exception e) {
                    broadcastLog(cluster, "🛑 JNI Crash: " + e.getMessage());
                }
            }).start();

        } catch (Exception e) {
            broadcastLog(cluster, "🛑 [VPN Error] " + e.getMessage());
            stopVpnTunnel();
        }
    }

    private void stopVpnTunnel() {
        new Thread(() -> {
            try { hev.socks5.tunnel.Tunnel.TunnelQuit(); } catch (Exception e) {}
        }).start();

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
