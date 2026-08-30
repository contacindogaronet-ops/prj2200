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
    private int nativeFd = -1;

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

            nativeFd = vpnInterface.getFd();
            if (nativeFd == -1) throw new Exception("Kernel menolak memberikan FD.");

            // 🔴 KUNCI ARSITEKTUR: Format YAML Murni Sesuai Standar Hev-Socks5-Tunnel
            File configFile = new File(getFilesDir(), "tun2socks.yml");
            String yaml = "tunnel:\n  mtu: 1500\n  ipv4: true\n  ipv6: false\nsocks5:\n  address: " + host + "\n  port: " + port + "\n  udp: 'udp'\n";
            FileOutputStream fos = new FileOutputStream(configFile);
            fos.write(yaml.getBytes());
            fos.flush();
            fos.getFD().sync(); 
            fos.close();

            Notification notif = new Notification.Builder(this, "VPN_GATEWAY")
                    .setContentTitle("Indogo VPN Gateway")
                    .setContentText("Tun2Socks Active @" + host + ":" + port)
                    .setSmallIcon(android.R.drawable.ic_lock_lock)
                    .build();
            startForeground(2, notif);

            broadcastLog(cluster, "🛡️ [VPN GATEWAY] TUN FD (" + nativeFd + ") diamankan.");
            broadcastLog(cluster, "🚀 [TUN2SOCKS] Mengeksekusi C++ Engine (Namespace: hev.socks5)...");

            new Thread(() -> {
                try {
                    // Eksekusi Absolut. C++ akan menemukan FindClass("hev/socks5/Tunnel") dengan sukses.
                    hev.socks5.Tunnel.TunnelMain(configFile.getAbsolutePath(), nativeFd);
                } catch (Throwable t) {
                    broadcastLog(cluster, "🛑 CRASH INTERCEPTED: " + t.getMessage());
                }
            }).start();

        } catch (Exception e) {
            broadcastLog(cluster, "🛑 [VPN Error] " + e.getMessage());
            stopVpnTunnel();
        }
    }

    private void stopVpnTunnel() {
        new Thread(() -> {
            try { hev.socks5.Tunnel.TunnelQuit(); } catch (Throwable t) {}
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
