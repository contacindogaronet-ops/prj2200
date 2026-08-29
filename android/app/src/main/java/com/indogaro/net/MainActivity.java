package com.indogaro.net;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.TrafficStats;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity {
    private View viewHome, viewClusters, viewSettings;
    private TextView navHome, navClusters, navSettings;
    private LinearLayout clusterContainer;
    private TextView btnAddCluster;
    private Button btnPanic;
    private Switch switchAutoRestart, switchWakelock, switchLowLatency;
    
    // Telemetry UI
    private TextView tvUptime, tvRam, tvRx, tvTx, tvTotalData, tvPingLocal;
    private TelemetryGraphView graphRam, graphRx, graphTx;
    
    private long lastRxBytes = 0, lastTxBytes = 0;
    private long appStartTime;
    private int myUid;
    private Handler telemetryHandler = new Handler(Looper.getMainLooper());
    private Runnable telemetryRunnable;
    
    private SharedPreferences prefs;
    private SharedPreferences settingsPrefs;
    private List<String> clusterList = new ArrayList<>();
    private Map<String, StringBuilder> logsMap = new HashMap<>(); 
    
    private String currentInjectCluster = "";
    private String activeLogCluster = "";
    private TextView tvActiveLog = null; 

    private final BroadcastReceiver logReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String cluster = intent.getStringExtra("cluster");
            String msg = intent.getStringExtra("log");
            if (cluster != null && msg != null) {
                msg = msg.replaceAll("\u001B\\[[;\\d]*m", "");
                if (!logsMap.containsKey(cluster)) logsMap.put(cluster, new StringBuilder());
                logsMap.get(cluster).append("\n> ").append(msg);
                if (cluster.equals(activeLogCluster) && tvActiveLog != null) {
                    tvActiveLog.append("\n> " + msg);
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        appStartTime = System.currentTimeMillis();
        myUid = android.os.Process.myUid(); // 🔴 Dapatkan UID Aplikasi ini
        
        prefs = getSharedPreferences("ClusterMatrix", MODE_PRIVATE);
        settingsPrefs = getSharedPreferences("DaemonSettings", MODE_PRIVATE);
        loadClusterList();
        
        // 🔴 KUNCI ARSITEKTUR: Eksekusi Ghost Polling OTA saat aplikasi dibuka
        new OTAUpdater(this).check(false);

        viewHome = findViewById(R.id.viewHome);
        viewClusters = findViewById(R.id.viewClusters);
        viewSettings = findViewById(R.id.viewSettings);
        
        navHome = findViewById(R.id.navHome);
        navClusters = findViewById(R.id.navClusters);
        navSettings = findViewById(R.id.navSettings);
        
        clusterContainer = findViewById(R.id.clusterContainer);
        btnAddCluster = findViewById(R.id.btnAddCluster);
        btnPanic = findViewById(R.id.btnPanic);
        
        switchAutoRestart = findViewById(R.id.switchAutoRestart);
        switchWakelock = findViewById(R.id.switchWakelock);
        switchLowLatency = findViewById(R.id.switchLowLatency);

        switchAutoRestart.setChecked(settingsPrefs.getBoolean("AUTO_RESTART", true));
        switchWakelock.setChecked(settingsPrefs.getBoolean("WAKELOCK", true));
        switchLowLatency.setChecked(settingsPrefs.getBoolean("LOW_LATENCY", false));
        
        switchAutoRestart.setOnCheckedChangeListener((btn, isChecked) -> settingsPrefs.edit().putBoolean("AUTO_RESTART", isChecked).apply());
        switchWakelock.setOnCheckedChangeListener((btn, isChecked) -> settingsPrefs.edit().putBoolean("WAKELOCK", isChecked).apply());
        switchLowLatency.setOnCheckedChangeListener((btn, isChecked) -> settingsPrefs.edit().putBoolean("LOW_LATENCY", isChecked).apply());

        tvUptime = findViewById(R.id.tvUptime);
        tvRam = findViewById(R.id.tvRam);
        tvRx = findViewById(R.id.tvRx);
        tvTx = findViewById(R.id.tvTx);
        tvTotalData = findViewById(R.id.tvTotalData);
        tvPingLocal = findViewById(R.id.tvPingLocal);
        
        graphRam = findViewById(R.id.graphRam);
        graphRx = findViewById(R.id.graphRx);
        graphTx = findViewById(R.id.graphTx);
        
        // Atur warna grafik
        graphRam.setLineColor("#FF9F0A"); // Orange
        graphRx.setLineColor("#34C759");  // Green
        graphTx.setLineColor("#5E5CE6");  // Purple

        // Inisialisasi Data Jaringan UID
        long uidRx = TrafficStats.getUidRxBytes(myUid);
        long uidTx = TrafficStats.getUidTxBytes(myUid);
        lastRxBytes = (uidRx == TrafficStats.UNSUPPORTED) ? 0 : uidRx;
        lastTxBytes = (uidTx == TrafficStats.UNSUPPORTED) ? 0 : uidTx;

        setupNavigation();
        renderDynamicClusters();
        startTelemetryEngine();

        btnAddCluster.setOnClickListener(v -> promptNewCluster());
        
        Button btnCheckUpdate = findViewById(R.id.btnCheckUpdate);
        btnCheckUpdate.setOnClickListener(v -> new OTAUpdater(this).check(true));

        btnPanic.setOnClickListener(v -> {
            Intent panicIntent = new Intent(this, DaemonService.class);
            panicIntent.setAction("PANIC_KILL_ALL");
            startService(panicIntent);
            /*
                Intent intent = new Intent(this, DaemonService.class);
                intent.setAction("STOP_CLUSTER");
                intent.putExtra("CLUSTER", cluster);
                startService(intent);*/
            }
            new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("PANIC INITIATED")
                .setMessage("Semua eksekusi proses telah dihentikan secara paksa.")
                .setPositiveButton("OK", null)
                .show();
        });

        if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE, android.Manifest.permission.READ_EXTERNAL_STORAGE}, 102);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(logReceiver, new IntentFilter("DAEMON_LOG"), Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(logReceiver, new IntentFilter("DAEMON_LOG"));
        }
    }

    private void startTelemetryEngine() {
        telemetryRunnable = new Runnable() {
            @Override
            public void run() {
                if (viewHome.getVisibility() == View.VISIBLE) {
                    
                    // 🔴 Dapatkan data jaringan KHUSUS aplikasi/bot ini
                    long currentRx = TrafficStats.getUidRxBytes(myUid);
                    long currentTx = TrafficStats.getUidTxBytes(myUid);
                    
                    if (currentRx != TrafficStats.UNSUPPORTED) {
                        long diffRx = currentRx - lastRxBytes;
                        long diffTx = currentTx - lastTxBytes;
                        
                        tvRx.setText(formatSpeed(diffRx));
                        tvTx.setText(formatSpeed(diffTx));
                        
                        // Push ke grafik
                        graphRx.addDataPoint(diffRx);
                        graphTx.addDataPoint(diffTx);
                        
                        // Kalkulasi Total Data Digunakan (RX + TX)
                        tvTotalData.setText(formatDataSize(currentRx + currentTx));
                        
                        lastRxBytes = currentRx;
                        lastTxBytes = currentTx;
                    }

                    long uptimeMillis = System.currentTimeMillis() - appStartTime;
                    int seconds = (int) (uptimeMillis / 1000) % 60;
                    int minutes = (int) ((uptimeMillis / (1000 * 60)) % 60);
                    int hours = (int) ((uptimeMillis / (1000 * 60 * 60)) % 24);
                    tvUptime.setText(String.format(Locale.US, "UPTIME: %02d:%02d:%02d", hours, minutes, seconds));

                    // RAM Usage
                    long usedMem = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024);
                    tvRam.setText(usedMem + " MB");
                    graphRam.addDataPoint(usedMem);

                    executePingMetrics();
                }
                telemetryHandler.postDelayed(this, 1000);
            }
        };
        telemetryHandler.post(telemetryRunnable);
    }

    private String formatSpeed(long bytes) {
        if (bytes < 1024) return bytes + " B/s";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB/s";
        return String.format(Locale.US, "%.2f MB/s", (float) bytes / (1024 * 1024));
    }
    
    private String formatDataSize(long bytes) {
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        if (bytes < 1024 * 1024 * 1024) return String.format(Locale.US, "%.2f MB", (float) bytes / (1024 * 1024));
        return String.format(Locale.US, "%.2f GB", (float) bytes / (1024 * 1024 * 1024));
    }

    private void executePingMetrics() {
        new Thread(() -> {
            String local = getPing("127.0.0.1");
            runOnUiThread(() -> {
                if (tvPingLocal != null) tvPingLocal.setText(local);
            });
        }).start();
    }

    private String getPing(String host) {
        try {
            Process p = Runtime.getRuntime().exec("ping -c 1 -W 1 " + host);
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("time=")) {
                    int start = line.indexOf("time=") + 5;
                    int end = line.indexOf(" ms", start);
                    return line.substring(start, end) + " ms";
                }
            }
            p.waitFor();
        } catch (Exception e) {}
        return "RTO";
    }

    private void setupNavigation() {
        navHome.setOnClickListener(v -> switchTab(0));
        navClusters.setOnClickListener(v -> switchTab(1));
        navSettings.setOnClickListener(v -> switchTab(2));
    }

    private void switchTab(int index) {
        viewHome.setVisibility(index == 0 ? View.VISIBLE : View.GONE);
        viewClusters.setVisibility(index == 1 ? View.VISIBLE : View.GONE);
        viewSettings.setVisibility(index == 2 ? View.VISIBLE : View.GONE);

        navHome.setTextColor(Color.parseColor(index == 0 ? "#FFFFFF" : "#8E8E93"));
        navClusters.setTextColor(Color.parseColor(index == 1 ? "#FFFFFF" : "#8E8E93"));
        navSettings.setTextColor(Color.parseColor(index == 2 ? "#FFFFFF" : "#8E8E93"));
    }

    private void loadClusterList() {
        String saved = prefs.getString("CLUSTER_LIST", "");
        if (!saved.isEmpty()) clusterList = new ArrayList<>(Arrays.asList(saved.split(",")));
    }

    private void saveClusterList() {
        prefs.edit().putString("CLUSTER_LIST", String.join(",", clusterList)).apply();
    }

    private void promptNewCluster() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert);
        builder.setTitle("Create Node");
        final EditText input = new EditText(this);
        input.setHint("e.g. WORKER_01");
        input.setTextColor(Color.WHITE);
        builder.setView(input);
        builder.setPositiveButton("CREATE", (dialog, which) -> {
            String name = input.getText().toString().trim().toUpperCase();
            if (!name.isEmpty() && !clusterList.contains(name)) {
                clusterList.add(name);
                saveClusterList();
                renderDynamicClusters();
            }
        });
        builder.setNegativeButton("CANCEL", null);
        builder.show();
    }

    private void renderDynamicClusters() {
        int childCount = clusterContainer.getChildCount();
        for (int i = childCount - 1; i >= 1; i--) clusterContainer.removeViewAt(i); 

        for (String clusterName : clusterList) {
            View card = getLayoutInflater().inflate(R.layout.item_cluster, clusterContainer, false);
            
            TextView tvName = card.findViewById(R.id.tvClusterName);
            TextView tvStatus = card.findViewById(R.id.tvClusterStatus);
            TextView btnInject = card.findViewById(R.id.btnClusterInject);
            TextView btnStart = card.findViewById(R.id.btnClusterStart);
            TextView btnStop = card.findViewById(R.id.btnClusterStop);
            Button btnLogs = card.findViewById(R.id.btnClusterLogs);
            Button btnDelete = card.findViewById(R.id.btnClusterDelete);

            tvName.setText(clusterName);
            String bins = prefs.getString(clusterName, "");
            int count = bins.isEmpty() ? 0 : bins.split(",").length;
            tvStatus.setText(count + " BINARIES");

            btnDelete.setOnClickListener(v -> {
                new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Delete Node")
                    .setMessage("Hancurkan cluster " + clusterName + " secara permanen?")
                    .setPositiveButton("HAPUS", (dialog, which) -> {
                        Intent intent = new Intent(this, DaemonService.class);
                        intent.setAction("STOP_CLUSTER");
                        intent.putExtra("CLUSTER", clusterName);
                        startService(intent);*/
                        
                        clusterList.remove(clusterName);
                        saveClusterList();
                        prefs.edit().remove(clusterName).apply();
                        logsMap.remove(clusterName);
                        renderDynamicClusters();
                    })
                    .setNegativeButton("BATAL", null)
                    .show();
            });

            btnInject.setOnClickListener(v -> {
                currentInjectCluster = clusterName;
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.setType("*/*");
                startActivityForResult(intent, 1);
            });

            btnStart.setOnClickListener(v -> {
                String currentBins = prefs.getString(clusterName, "");
                if (currentBins.isEmpty()) return;
                Intent intent = new Intent(this, DaemonService.class);
                intent.setAction("START_CLUSTER");
                intent.putExtra("CLUSTER", clusterName);
                intent.putExtra("BINS", currentBins);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent);
                else startService(intent);*/
            });

            btnStop.setOnClickListener(v -> {
                Intent intent = new Intent(this, DaemonService.class);
                intent.setAction("STOP_CLUSTER");
                intent.putExtra("CLUSTER", clusterName);
                startService(intent);*/
            });

            btnLogs.setOnClickListener(v -> openInteractiveShellDialog(clusterName));

            clusterContainer.addView(card);
        }
    }

    private void openInteractiveShellDialog(String clusterName) {
        activeLogCluster = clusterName;
        AlertDialog.Builder builder = new AlertDialog.Builder(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        
        LinearLayout mainLayout = new LinearLayout(this);
        mainLayout.setOrientation(LinearLayout.VERTICAL);
        mainLayout.setBackgroundColor(Color.parseColor("#000000"));
        mainLayout.setPadding(24, 24, 24, 24);

        ScrollView scroll = new ScrollView(this);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f);
        scroll.setLayoutParams(scrollParams);
        
        tvActiveLog = new TextView(this);
        tvActiveLog.setTextColor(Color.parseColor("#34C759"));
        tvActiveLog.setTextSize(12f);
        tvActiveLog.setTypeface(android.graphics.Typeface.MONOSPACE);
        
        if (logsMap.containsKey(clusterName)) {
            tvActiveLog.setText(logsMap.get(clusterName).toString());
        } else {
            tvActiveLog.setText("> Terminal Interaktif (Node: " + clusterName + ")\n> Path: " + getFilesDir().getAbsolutePath() + "\n");
        }
        scroll.addView(tvActiveLog);

        LinearLayout inputLayout = new LinearLayout(this);
        inputLayout.setOrientation(LinearLayout.HORIZONTAL);
        inputLayout.setPadding(0, 16, 0, 0);

        EditText inputCmd = new EditText(this);
        inputCmd.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
        inputCmd.setHint("sh: ls -la atau cp /sdcard/...");
        inputCmd.setHintTextColor(Color.parseColor("#3A3A3C"));
        inputCmd.setTextColor(Color.WHITE);
        inputCmd.setTextSize(12f);
        inputCmd.setTypeface(android.graphics.Typeface.MONOSPACE);

        Button btnSend = new Button(this);
        btnSend.setText("EXEC");
        btnSend.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#1C1C1E")));
        btnSend.setTextColor(Color.WHITE);

        btnSend.setOnClickListener(v -> {
            String cmd = inputCmd.getText().toString().trim();
            if (!cmd.isEmpty()) {
                inputCmd.setText("");
                executeRawShell(clusterName, cmd);
            }
        });

        Button btnClose = new Button(this);
        btnClose.setText("X");
        btnClose.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#1C1C1E")));
        btnClose.setTextColor(Color.parseColor("#FF453A"));
        
        inputLayout.addView(inputCmd);
        inputLayout.addView(btnSend);
        inputLayout.addView(btnClose);

        mainLayout.addView(scroll);
        mainLayout.addView(inputLayout);

        builder.setView(mainLayout);
        final AlertDialog dialog = builder.create();
        
        btnClose.setOnClickListener(v -> {
            activeLogCluster = ""; 
            tvActiveLog = null;
            dialog.dismiss(); 
        });
        
        dialog.show();
    }

    private void executeRawShell(String clusterName, String cmd) {
        String logHeader = "\n\n" + clusterName + "@indogo:~$ " + cmd;
        tvActiveLog.append(logHeader);
        if (!logsMap.containsKey(clusterName)) logsMap.put(clusterName, new StringBuilder());
        logsMap.get(clusterName).append(logHeader);

        new Thread(() -> {
            try {
                ProcessBuilder pb = new ProcessBuilder("sh", "-c", cmd);
                pb.directory(getFilesDir());
                pb.redirectErrorStream(true);
                Process p = pb.start();
                
                BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null) {
                    final String out = line;
                    runOnUiThread(() -> {
                        if (tvActiveLog != null && activeLogCluster.equals(clusterName)) {
                            tvActiveLog.append("\n" + out);
                        }
                        logsMap.get(clusterName).append("\n").append(out);
                    });
                }
                p.waitFor();
            } catch (Exception e) {
                runOnUiThread(() -> tvActiveLog.append("\n[Shell Error] " + e.getMessage()));
            }
            runOnUiThread(() -> {
                if (tvActiveLog != null) {
                    ScrollView sv = (ScrollView) tvActiveLog.getParent();
                    sv.post(() -> sv.fullScroll(View.FOCUS_DOWN));
                }
            });
        }).start();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode == RESULT_OK && data != null && requestCode == 1) {
            try {
                String bins = prefs.getString(currentInjectCluster, "");
                String binName = currentInjectCluster.toLowerCase() + "_bin_" + System.currentTimeMillis();
                File destFile = new File(getFilesDir(), binName);

                InputStream in = getContentResolver().openInputStream(data.getData());
                FileOutputStream out = new FileOutputStream(destFile);
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
                in.close(); out.close();

                String newBins = bins.isEmpty() ? binName : bins + "," + binName;
                prefs.edit().putString(currentInjectCluster, newBins).apply();
                renderDynamicClusters(); 
            } catch (Exception e) {}
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        telemetryHandler.removeCallbacks(telemetryRunnable);
        unregisterReceiver(logReceiver);
    }
}
