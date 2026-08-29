package com.jargo.daemon;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.net.TrafficStats;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
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
    private Button btnAddCluster, btnInjectRules;
    
    private TextView tvTemp, tvRx, tvTx, tvPingLocal, tvPingGlobal;
    private long lastRxBytes = 0, lastTxBytes = 0;
    private Handler telemetryHandler = new Handler(Looper.getMainLooper());
    private Runnable telemetryRunnable;
    
    private SharedPreferences prefs;
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
        
        prefs = getSharedPreferences("ClusterMatrix", MODE_PRIVATE);
        loadClusterList();

        viewHome = findViewById(R.id.viewHome);
        viewClusters = findViewById(R.id.viewClusters);
        viewSettings = findViewById(R.id.viewSettings);
        
        navHome = findViewById(R.id.navHome);
        navClusters = findViewById(R.id.navClusters);
        navSettings = findViewById(R.id.navSettings);
        
        clusterContainer = findViewById(R.id.clusterContainer);
        btnAddCluster = findViewById(R.id.btnAddCluster);
        btnInjectRules = findViewById(R.id.btnInjectRules);

        tvTemp = findViewById(R.id.tvTemp);
        tvRx = findViewById(R.id.tvRx);
        tvTx = findViewById(R.id.tvTx);
        tvPingLocal = findViewById(R.id.tvPingLocal);
        tvPingGlobal = findViewById(R.id.tvPingGlobal);
        
        lastRxBytes = TrafficStats.getTotalRxBytes();
        lastTxBytes = TrafficStats.getTotalTxBytes();

        setupNavigation();
        renderDynamicClusters();
        startTelemetryEngine();

        btnAddCluster.setOnClickListener(v -> promptNewCluster());
        btnInjectRules.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("*/*");
            startActivityForResult(intent, 2);
        });

        // 🔴 KUNCI ARSITEKTUR: Meminta izin Storage saat aplikasi dibuka
        if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE, android.Manifest.permission.READ_EXTERNAL_STORAGE}, 102);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(logReceiver, new IntentFilter("DAEMON_LOG"), Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(logReceiver, new IntentFilter("DAEMON_LOG"));
        }
    }

    private String getFileName(Uri uri) {
        String result = null;
        if (uri.getScheme().equals("content")) {
            try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (index >= 0) result = cursor.getString(index);
                }
            }
        }
        if (result == null) {
            result = uri.getPath();
            int cut = result.lastIndexOf('/');
            if (cut != -1) result = result.substring(cut + 1);
        }
        return result;
    }

    private void startTelemetryEngine() {
        telemetryRunnable = new Runnable() {
            @Override
            public void run() {
                if (viewHome.getVisibility() == View.VISIBLE) {
                    long currentRx = TrafficStats.getTotalRxBytes();
                    long currentTx = TrafficStats.getTotalTxBytes();
                    tvRx.setText(formatSpeed(currentRx - lastRxBytes));
                    tvTx.setText(formatSpeed(currentTx - lastTxBytes));
                    lastRxBytes = currentRx;
                    lastTxBytes = currentTx;

                    Intent intent = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                    if (intent != null) {
                        float temp = ((float) intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)) / 10;
                        tvTemp.setText(temp + " °C");
                    }
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

    private void executePingMetrics() {
        new Thread(() -> {
            String local = getPing("127.0.0.1");
            String global = getPing("8.8.8.8");
            runOnUiThread(() -> {
                if (tvPingLocal != null) tvPingLocal.setText(local);
                if (tvPingGlobal != null) tvPingGlobal.setText(global);
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

        navHome.setTextColor(Color.parseColor(index == 0 ? "#38BDF8" : "#94A3B8"));
        navClusters.setTextColor(Color.parseColor(index == 1 ? "#38BDF8" : "#94A3B8"));
        navSettings.setTextColor(Color.parseColor(index == 2 ? "#38BDF8" : "#94A3B8"));
    }

    private void loadClusterList() {
        String saved = prefs.getString("CLUSTER_LIST", "");
        if (!saved.isEmpty()) clusterList = new ArrayList<>(Arrays.asList(saved.split(",")));
    }

    private void saveClusterList() {
        prefs.edit().putString("CLUSTER_LIST", String.join(",", clusterList)).apply();
    }

    private void promptNewCluster() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Create New Cluster");
        final EditText input = new EditText(this);
        input.setHint("e.g. CORE_PROXY");
        builder.setView(input);
        builder.setPositiveButton("CREATE", (dialog, which) -> {
            String name = input.getText().toString().trim().toUpperCase();
            if (!name.isEmpty() && !clusterList.contains(name)) {
                clusterList.add(name);
                saveClusterList();
                renderDynamicClusters();
            }
        });
        builder.setNegativeButton("CANCEL", (dialog, which) -> dialog.cancel());
        builder.show();
    }

    private void renderDynamicClusters() {
        int childCount = clusterContainer.getChildCount();
        for (int i = childCount - 1; i >= 2; i--) clusterContainer.removeViewAt(i);

        for (String clusterName : clusterList) {
            View card = getLayoutInflater().inflate(R.layout.item_cluster, clusterContainer, false);
            
            TextView tvName = card.findViewById(R.id.tvClusterName);
            TextView tvStatus = card.findViewById(R.id.tvClusterStatus);
            Button btnInject = card.findViewById(R.id.btnClusterInject);
            Button btnStart = card.findViewById(R.id.btnClusterStart);
            Button btnStop = card.findViewById(R.id.btnClusterStop);
            Button btnLogs = card.findViewById(R.id.btnClusterLogs);
            Button btnDelete = card.findViewById(R.id.btnClusterDelete);

            tvName.setText(clusterName);
            String bins = prefs.getString(clusterName, "");
            int count = bins.isEmpty() ? 0 : bins.split(",").length;
            tvStatus.setText(count + " Binaries Injected");

            btnDelete.setOnClickListener(v -> {
                new AlertDialog.Builder(this)
                    .setTitle("Pemusnahan Klaster")
                    .setMessage("Hentikan dan hapus klaster " + clusterName + " secara permanen?")
                    .setPositiveButton("HAPUS", (dialog, which) -> {
                        Intent intent = new Intent(this, DaemonService.class);
                        intent.setAction("STOP_CLUSTER");
                        intent.putExtra("CLUSTER", clusterName);
                        startService(intent);
                        
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
                else startService(intent);
            });

            btnStop.setOnClickListener(v -> {
                Intent intent = new Intent(this, DaemonService.class);
                intent.setAction("STOP_CLUSTER");
                intent.putExtra("CLUSTER", clusterName);
                startService(intent);
            });

            btnLogs.setOnClickListener(v -> openInteractiveShellDialog(clusterName));

            clusterContainer.addView(card);
        }
    }

    // 🔴 KUNCI ARSITEKTUR: Terminal Interaktif (Interactive Cluster Shell)
    private void openInteractiveShellDialog(String clusterName) {
        activeLogCluster = clusterName;
        AlertDialog.Builder builder = new AlertDialog.Builder(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        
        LinearLayout mainLayout = new LinearLayout(this);
        mainLayout.setOrientation(LinearLayout.VERTICAL);
        mainLayout.setBackgroundColor(Color.parseColor("#0F172A"));
        mainLayout.setPadding(24, 24, 24, 24);

        ScrollView scroll = new ScrollView(this);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f);
        scroll.setLayoutParams(scrollParams);
        
        tvActiveLog = new TextView(this);
        tvActiveLog.setTextColor(Color.parseColor("#4ADE80")); 
        tvActiveLog.setTextSize(12f);
        tvActiveLog.setTypeface(android.graphics.Typeface.MONOSPACE);
        
        if (logsMap.containsKey(clusterName)) {
            tvActiveLog.setText(logsMap.get(clusterName).toString());
        } else {
            tvActiveLog.setText("> Terminal Interaktif untuk klaster " + clusterName + " siap.\n> Path: " + getFilesDir().getAbsolutePath() + "\n");
        }
        scroll.addView(tvActiveLog);

        // Baris Input Shell
        LinearLayout inputLayout = new LinearLayout(this);
        inputLayout.setOrientation(LinearLayout.HORIZONTAL);
        inputLayout.setPadding(0, 16, 0, 0);

        EditText inputCmd = new EditText(this);
        inputCmd.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
        inputCmd.setHint("sh: ls -la atau cp /sdcard/...");
        inputCmd.setHintTextColor(Color.parseColor("#475569"));
        inputCmd.setTextColor(Color.WHITE);
        inputCmd.setTextSize(12f);
        inputCmd.setTypeface(android.graphics.Typeface.MONOSPACE);

        Button btnSend = new Button(this);
        btnSend.setText("EXEC");
        btnSend.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#334155")));
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
        btnClose.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#7F1D1D")));
        btnClose.setTextColor(Color.WHITE);
        btnClose.setOnClickListener(v -> {
            activeLogCluster = ""; 
            tvActiveLog = null;
            builder.create().dismiss(); // Akan di-handle oleh show() nanti
        });

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

    // 🔴 KUNCI ARSITEKTUR: Pengeksekusi Perintah Linux Mentah
    private void executeRawShell(String clusterName, String cmd) {
        String logHeader = "\n\n" + clusterName + "@daemon:~$ " + cmd;
        tvActiveLog.append(logHeader);
        if (!logsMap.containsKey(clusterName)) logsMap.put(clusterName, new StringBuilder());
        logsMap.get(clusterName).append(logHeader);

        new Thread(() -> {
            try {
                // Jalankan bash sh di direktori privat aplikasi
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
            // Auto scroll ke bawah (simulasi terminal sungguhan)
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
        if (resultCode == RESULT_OK && data != null) {
            if (requestCode == 1) {
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
            else if (requestCode == 2) {
                try {
                    String fileName = getFileName(data.getData());
                    if (fileName == null || fileName.isEmpty()) {
                        fileName = "rules_" + System.currentTimeMillis() + ".txt";
                    }

                    File blocklistsDir = new File(getFilesDir(), "blocklists");
                    if (!blocklistsDir.exists()) blocklistsDir.mkdirs();
                    
                    File destFile = new File(blocklistsDir, fileName);
                    InputStream in = getContentResolver().openInputStream(data.getData());
                    FileOutputStream out = new FileOutputStream(destFile);
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
                    in.close(); out.close();

                    new AlertDialog.Builder(this)
                        .setTitle("Injeksi Berhasil")
                        .setMessage("File berhasil disuntikkan sebagai: \n./blocklists/" + fileName)
                        .setPositiveButton("OK", null)
                        .show();
                } catch (Exception e) {
                    new AlertDialog.Builder(this).setMessage("Error: " + e.getMessage()).show();
                }
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        telemetryHandler.removeCallbacks(telemetryRunnable);
        unregisterReceiver(logReceiver);
    }
}
