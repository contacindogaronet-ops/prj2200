package com.jargo.daemon;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;

public class DaemonService extends Service {
    private final Map<String, Process> activeProcesses = new HashMap<>();
    private int runningCount = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel("DAEMON", "Daemon", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
        Notification notif = new Notification.Builder(this, "DAEMON").setContentTitle("KUL Daemon").setContentText("Standby...").setSmallIcon(android.R.drawable.ic_media_play).build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(1, notif);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            String cluster = intent.getStringExtra("CLUSTER");
            String bins = intent.getStringExtra("BINS"); 

            if ("START_CLUSTER".equals(action) && bins != null && !bins.isEmpty()) {
                for (String bin : bins.split(",")) executeBinary(cluster, bin);
            } else if ("STOP_CLUSTER".equals(action)) {
                killCluster(cluster);
            }
        }
        return START_STICKY;
    }

    private void executeBinary(String cluster, String binName) {
        String processKey = cluster + "_" + binName;
        if (activeProcesses.containsKey(processKey)) return;

        File binFile = new File(getFilesDir(), binName);
        if (!binFile.exists()) return;

        try { Runtime.getRuntime().exec("chmod 777 " + binFile.getAbsolutePath()).waitFor(); } catch (Exception e) {}
        binFile.setExecutable(true, false);

        new Thread(() -> {
            try {
                ProcessBuilder pb = new ProcessBuilder(binFile.getAbsolutePath());
                pb.directory(getFilesDir());
                pb.redirectErrorStream(true);
                
                Process process = pb.start();
                activeProcesses.put(processKey, process);
                
                broadcastLog(cluster, "🚀 Menginisiasi biner: " + binName);
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null) {
                    broadcastLog(cluster, "[" + binName + "] " + line);
                }
                
                process.waitFor();
                activeProcesses.remove(processKey);
                broadcastLog(cluster, "💀 Biner berhenti: " + binName);
            } catch (Exception e) {
                broadcastLog(cluster, "🛑 Crash: " + e.getMessage());
            }
        }).start();
    }

    private void killCluster(String cluster) {
        broadcastLog(cluster, "⚠️ Menerima sinyal pemusnahan massal...");
        for (Map.Entry<String, Process> entry : activeProcesses.entrySet()) {
            if (entry.getKey().startsWith(cluster + "_")) {
                entry.getValue().destroy();
                broadcastLog(cluster, "🔪 Membunuh: " + entry.getKey());
            }
        }
    }

    // 🔴 KUNCI ARSITEKTUR: Mengirim ID Cluster secara terpisah ke Broadcast
    private void broadcastLog(String cluster, String msg) {
        Intent intent = new Intent("DAEMON_LOG");
        intent.putExtra("cluster", cluster);
        intent.putExtra("log", msg);
        sendBroadcast(intent);
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
