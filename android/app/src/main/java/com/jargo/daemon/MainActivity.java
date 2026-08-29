package com.jargo.daemon;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends Activity {
    private View viewHome, viewClusters, viewSettings;
    private TextView navHome, navClusters, navSettings;
    private LinearLayout clusterContainer;
    private Button btnAddCluster;
    
    private SharedPreferences prefs;
    private List<String> clusterList = new ArrayList<>();
    private Map<String, StringBuilder> logsMap = new HashMap<>(); // Database Log Dinamis
    
    private String currentInjectCluster = "";
    private String activeLogCluster = "";
    private TextView tvActiveLog = null; // Menyimpan referensi textview dialog log yang sedang terbuka

    // 🔴 KUNCI ARSITEKTUR: Penerima Log Spesifik Klaster dan Pembersih ANSI
    private final BroadcastReceiver logReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String cluster = intent.getStringExtra("cluster");
            String msg = intent.getStringExtra("log");
            if (cluster != null && msg != null) {
                // HAPUS KODE WARNA ANSI (Regex)
                msg = msg.replaceAll("\u001B\\[[;\\d]*m", "");
                
                if (!logsMap.containsKey(cluster)) logsMap.put(cluster, new StringBuilder());
                logsMap.get(cluster).append("\n> ").append(msg);
                
                // Jika dialog log klaster tersebut sedang dibuka, update secara real-time
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

        setupNavigation();
        renderDynamicClusters();

        btnAddCluster.setOnClickListener(v -> promptNewCluster());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(logReceiver, new IntentFilter("DAEMON_LOG"), Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(logReceiver, new IntentFilter("DAEMON_LOG"));
        }
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

        navHome.setTextColor(Color.parseColor(index == 0 ? "#00E676" : "#777777"));
        navClusters.setTextColor(Color.parseColor(index == 1 ? "#00E676" : "#777777"));
        navSettings.setTextColor(Color.parseColor(index == 2 ? "#00E676" : "#777777"));
    }

    private void loadClusterList() {
        String saved = prefs.getString("CLUSTER_LIST", "");
        if (!saved.isEmpty()) {
            clusterList = new ArrayList<>(Arrays.asList(saved.split(",")));
        } else {
            // Default cluster pertama kali buka
            clusterList.add("ALPHA");
            saveClusterList();
        }
    }

    private void saveClusterList() {
        prefs.edit().putString("CLUSTER_LIST", String.join(",", clusterList)).apply();
    }

    private void promptNewCluster() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Create New Cluster");
        final EditText input = new EditText(this);
        input.setHint("e.g. OMEGA");
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

    // 🔴 KUNCI ARSITEKTUR: Mencetak UI Kartu secara dinamis
    private void renderDynamicClusters() {
        // Hapus semua view lama kecuali judul dan tombol tambah
        int childCount = clusterContainer.getChildCount();
        for (int i = childCount - 1; i >= 2; i--) {
            clusterContainer.removeViewAt(i);
        }

        for (String clusterName : clusterList) {
            View card = getLayoutInflater().inflate(R.layout.item_cluster, clusterContainer, false);
            
            TextView tvName = card.findViewById(R.id.tvClusterName);
            TextView tvStatus = card.findViewById(R.id.tvClusterStatus);
            Button btnInject = card.findViewById(R.id.btnClusterInject);
            Button btnStart = card.findViewById(R.id.btnClusterStart);
            Button btnStop = card.findViewById(R.id.btnClusterStop);
            Button btnLogs = card.findViewById(R.id.btnClusterLogs);

            tvName.setText(clusterName);
            String bins = prefs.getString(clusterName, "");
            int count = bins.isEmpty() ? 0 : bins.split(",").length;
            tvStatus.setText(count + " Binaries Injected");

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

            btnLogs.setOnClickListener(v -> openLogDialog(clusterName));

            clusterContainer.addView(card);
        }
    }

    private void openLogDialog(String clusterName) {
        activeLogCluster = clusterName;
        AlertDialog.Builder builder = new AlertDialog.Builder(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        
        ScrollView scroll = new ScrollView(this);
        scroll.setPadding(24, 24, 24, 24);
        scroll.setBackgroundColor(Color.parseColor("#09090B"));
        
        tvActiveLog = new TextView(this);
        tvActiveLog.setTextColor(Color.parseColor("#00E676"));
        tvActiveLog.setTextSize(12f);
        tvActiveLog.setTypeface(android.graphics.Typeface.MONOSPACE);
        
        // Muat log lama
        if (logsMap.containsKey(clusterName)) {
            tvActiveLog.setText(logsMap.get(clusterName).toString());
        } else {
            tvActiveLog.setText("> Menunggu output dari klaster " + clusterName + "...");
        }
        
        scroll.addView(tvActiveLog);
        builder.setView(scroll);
        
        builder.setPositiveButton("CLOSE", (dialog, which) -> {
            activeLogCluster = "";
            tvActiveLog = null;
        });
        
        AlertDialog dialog = builder.create();
        dialog.show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == 1 && resultCode == RESULT_OK && data != null) {
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
                renderDynamicClusters(); // Render ulang untuk update teks jumlah
            } catch (Exception e) {}
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(logReceiver);
    }
}
