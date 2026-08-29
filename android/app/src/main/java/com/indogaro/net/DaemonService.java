package com.indogaro.net;

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
import android.os.PowerManager;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class DaemonService extends Service {
    private final Map<String, Process> activeProcesses = new HashMap<>();
    private final Map<String, Boolean> stopFlags = new HashMap<>(); 
    private SharedPreferences settingsPrefs;
    private SharedPreferences clusterPrefs;
    private PowerManager.WakeLock wakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
        settingsPrefs = getSharedPreferences("DaemonSettings", Context.MODE_PRIVATE);
        clusterPrefs = getSharedPreferences("ClusterMatrix", Context.MODE_PRIVATE);
        
        if (settingsPrefs.getBoolean("WAKELOCK", true)) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Indogo::CpuWakeLock");
            wakeLock.acquire(); 
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel("DAEMON", "Daemon", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
        Notification notif = new Notification.Builder(this, "DAEMON").setContentTitle("Indogo Matrix").setContentText("Orchestrator Standby...").setSmallIcon(android.R.drawable.ic_media_play).build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(1, notif);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            
            if ("AUTO_IGNITION".equals(action)) {
                // 🔴 FITUR: Boot Auto-Ignition
                Set<String> activeClusters = clusterPrefs.getStringSet("ACTIVE_CLUSTERS", new HashSet<>());
                for (String cluster : activeClusters) {
                    String bins = clusterPrefs.getString(cluster, "");
                    if (!bins.isEmpty()) {
                        stopFlags.put(cluster, false);
                        for (String bin : bins.split(",")) executeBinary(cluster, bin);
                    }
                }
            } else if ("START_CLUSTER".equals(action)) {
                String cluster = intent.getStringExtra("CLUSTER");
                String bins = intent.getStringExtra("BINS"); 
                if (bins != null && !bins.isEmpty()) {
                    stopFlags.put(cluster, false); 
                    saveClusterState(cluster, true); // Simpan state aktif
                    for (String bin : bins.split(",")) executeBinary(cluster, bin);
                }
            } else if ("STOP_CLUSTER".equals(action)) {
                String cluster = intent.getStringExtra("CLUSTER");
                killCluster(cluster);
                saveClusterState(cluster, false); // Simpan state mati
            } else if ("PANIC_KILL_ALL".equals(action)) {
                // 🔴 FITUR: Zombie Annihilator (Panic Button)
                annihilateZombies();
            }
        }
        return START_STICKY;
    }

    private void saveClusterState(String cluster, boolean isActive) {
        Set<String> activeSet = new HashSet<>(clusterPrefs.getStringSet("ACTIVE_CLUSTERS", new HashSet<>()));
        if (isActive) activeSet.add(cluster);
        else activeSet.remove(cluster);
        clusterPrefs.edit().putStringSet("ACTIVE_CLUSTERS", activeSet).apply();
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
                if (stopFlags.getOrDefault(cluster, false)) break; 
                
                try {
                    ProcessBuilder pb = new ProcessBuilder(binFile.getAbsolutePath());
                    pb.directory(getFilesDir());
                    pb.redirectErrorStream(true);
                    
                    Map<String, String> env = pb.environment();
                    if (settingsPrefs.getBoolean("LOW_LATENCY", false)) {
                        env.put("GOGC", "500");
                        env.put("GOMAXPROCS", "2"); 
                    } else {
                        env.put("GOMEMLIMIT", "128MiB"); 
                    }
                    
                    Process process = pb.start();
                    activeProcesses.put(processKey, process);
                    
                    broadcastLog(cluster, "🚀 Mengeksekusi biner: " + binName);
                    BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                    String line;
                    while ((line = reader.readLine()) != null) broadcastLog(cluster, "[" + binName + "] " + line);
                    
                    process.waitFor(); 
                    activeProcesses.remove(processKey);
                    
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
        stopFlags.put(cluster, true); 
        broadcastLog(cluster, "⚠️ Menerima sinyal pemusnahan...");
        for (Map.Entry<String, Process> entry : activeProcesses.entrySet()) {
            if (entry.getKey().startsWith(cluster + "_")) {
                entry.getValue().destroy();
                broadcastLog(cluster, "🔪 Membunuh proses: " + entry.getKey());
            }
        }
    }

    // 🔴 KUNCI ARSITEKTUR: Algoritma Pembantai Zombie Process
    private void annihilateZombies() {
        for (String cluster : clusterPrefs.getString("CLUSTER_LIST", "").split(",")) {
            stopFlags.put(cluster, true);
            saveClusterState(cluster, false);
        }
        activeProcesses.clear(); // Bersihkan referensi Java
        
        new Thread(() -> {
            try {
                // Tembak brutal level Kernel Linux: Cari proses di direktori kita dan paksa mati
                String appDir = getFilesDir().getAbsolutePath();
                String killCmd = "ps -A | grep '" + appDir + "' | awk '{print $2}' | xargs kill -9";
                Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", killCmd});
                p.waitFor();
            } catch (Exception e) {}
        }).start();
    }

    private void broadcastLog(String cluster, String msg) {
        Intent intent = new Intent("DAEMON_LOG");
        intent.putExtra("cluster", cluster);
        intent.putExtra("log", msg);
        sendBroadcast(intent);
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }
}
