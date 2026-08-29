package com.jargo.daemon;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
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
    private final Map<String, Boolean> stopFlags = new HashMap<>(); // 🔴 Bendera untuk membedakan Crash vs Tombol STOP
    private int runningCount = 0;
    private SharedPreferences settingsPrefs;

    @Override
    public void onCreate() {
        super.onCreate();
        settingsPrefs = getSharedPreferences("DaemonSettings", Context.MODE_PRIVATE);
        
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
                stopFlags.put(cluster, false); // Matikan bendera stop
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
            boolean keepRunning = true;
            
            while (keepRunning) {
                // Mengecek bendera stop manual dari user
                if (stopFlags.getOrDefault(cluster, false)) {
                    break; 
                }
                
                try {
                    ProcessBuilder pb = new ProcessBuilder(binFile.getAbsolutePath());
                    pb.directory(getFilesDir());
                    pb.redirectErrorStream(true);
                    
                    // 🔴 KUNCI ARSITEKTUR: Menyuntikkan Aturan Aggressive RAM
                    Map<String, String> env = pb.environment();
                    if (settingsPrefs.getBoolean("AGGRESSIVE_RAM", false)) {
                        env.put("GOGC", "off"); // Matikan Garbage Collector Golang (Agresif makan RAM, CPU Ringan)
                        env.put("GOMAXPROCS", String.valueOf(Runtime.getRuntime().availableProcessors()));
                    } else {
                        env.put("GOMEMLIMIT", "128MiB"); // Paksa limit RAM rendah
                    }
                    
                    Process process = pb.start();
                    activeProcesses.put(processKey, process);
                    
                    broadcastLog(cluster, "🚀 Mengeksekusi biner: " + binName);
                    BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        broadcastLog(cluster, "[" + binName + "] " + line);
                    }
                    
                    process.waitFor(); // ⬅️ Mesin akan tertahan di sini sampai bot mati/crash
                    activeProcesses.remove(processKey);
                    
                    // Logika Post-Mortem (Setelah Bot Mati)
                    if (stopFlags.getOrDefault(cluster, false)) {
                        keepRunning = false;
                        broadcastLog(cluster, "💀 Biner dihentikan oleh User: " + binName);
                    } else if (settingsPrefs.getBoolean("AUTO_RESTART", true)) {
                        broadcastLog(cluster, "♻️ [CRASH DETECTED] Menginisiasi Auto-Restart dalam 3 detik...");
                        Thread.sleep(3000);
                    } else {
                        keepRunning = false;
                        broadcastLog(cluster, "💀 Biner mati secara tidak wajar: " + binName);
                    }
                    
                } catch (Exception e) {
                    broadcastLog(cluster, "🛑 Crash Executor: " + e.getMessage());
                    keepRunning = false;
                }
            }
        }).start();
    }

    private void killCluster(String cluster) {
        stopFlags.put(cluster, true); // 🔴 Nyalakan bendera stop manual
        broadcastLog(cluster, "⚠️ Menerima sinyal pemusnahan massal...");
        for (Map.Entry<String, Process> entry : activeProcesses.entrySet()) {
            if (entry.getKey().startsWith(cluster + "_")) {
                entry.getValue().destroy();
                broadcastLog(cluster, "🔪 Membunuh proses: " + entry.getKey());
            }
        }
    }

    private void broadcastLog(String cluster, String msg) {
        Intent intent = new Intent("DAEMON_LOG");
        intent.putExtra("cluster", cluster);
        intent.putExtra("log", msg);
        sendBroadcast(intent);
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
