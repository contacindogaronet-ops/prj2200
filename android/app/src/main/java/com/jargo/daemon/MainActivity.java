package com.jargo.daemon;

import android.app.Activity;
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
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public class MainActivity extends Activity {
    private View viewClusters, viewLogs;
    private TextView navClusters, navLogs, tvLogs;
    private TextView tvAlphaStatus, tvBetaStatus, tvGammaStatus;
    private SharedPreferences prefs;
    private String currentInjectCluster = "";

    // Pendengar Log dari DaemonService
    private final BroadcastReceiver logReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String msg = intent.getStringExtra("log");
            if (msg != null && tvLogs != null) {
                tvLogs.append("\n> " + msg);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        prefs = getSharedPreferences("ClusterMatrix", MODE_PRIVATE);

        viewClusters = findViewById(R.id.viewClusters);
        viewLogs = findViewById(R.id.viewLogs);
        navClusters = findViewById(R.id.navClusters);
        navLogs = findViewById(R.id.navLogs);
        tvLogs = findViewById(R.id.tvLogs);

        tvAlphaStatus = findViewById(R.id.tvAlphaStatus);
        tvBetaStatus = findViewById(R.id.tvBetaStatus);
        tvGammaStatus = findViewById(R.id.tvGammaStatus);

        setupNavigation();
        setupCluster("ALPHA", R.id.btnAlphaInject, R.id.btnAlphaStart, R.id.btnAlphaStop);
        setupCluster("BETA", R.id.btnBetaInject, R.id.btnBetaStart, R.id.btnBetaStop);
        setupCluster("GAMMA", R.id.btnGammaInject, R.id.btnGammaStart, R.id.btnGammaStop);

        updateClusterStatusUI();

        // Minta Izin Notifikasi Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 101);
        }

        // Daftarkan Pendengar Log
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(logReceiver, new IntentFilter("DAEMON_LOG"), Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(logReceiver, new IntentFilter("DAEMON_LOG"));
        }
    }

    private void setupNavigation() {
        navClusters.setOnClickListener(v -> {
            viewClusters.setVisibility(View.VISIBLE);
            viewLogs.setVisibility(View.GONE);
            navClusters.setTextColor(Color.parseColor("#00E676"));
            navLogs.setTextColor(Color.parseColor("#777777"));
        });
        navLogs.setOnClickListener(v -> {
            viewClusters.setVisibility(View.GONE);
            viewLogs.setVisibility(View.VISIBLE);
            navClusters.setTextColor(Color.parseColor("#777777"));
            navLogs.setTextColor(Color.parseColor("#FF3D00"));
        });
    }

    private void setupCluster(String clusterName, int injectId, int startId, int stopId) {
        findViewById(injectId).setOnClickListener(v -> {
            currentInjectCluster = clusterName;
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("*/*");
            startActivityForResult(intent, 1);
        });

        findViewById(startId).setOnClickListener(v -> {
            String bins = prefs.getString(clusterName, "");
            if (bins.isEmpty()) return;
            Intent intent = new Intent(this, DaemonService.class);
            intent.setAction("START_CLUSTER");
            intent.putExtra("CLUSTER", clusterName);
            intent.putExtra("BINS", bins);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent);
            else startService(intent);
            tvLogs.append("\n> [SYSTEM] Sinyal START dikirim ke " + clusterName);
        });

        findViewById(stopId).setOnClickListener(v -> {
            Intent intent = new Intent(this, DaemonService.class);
            intent.setAction("STOP_CLUSTER");
            intent.putExtra("CLUSTER", clusterName);
            startService(intent);
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == 1 && resultCode == RESULT_OK && data != null) {
            injectBinaryToCluster(data.getData());
        }
    }

    private void injectBinaryToCluster(Uri uri) {
        try {
            String bins = prefs.getString(currentInjectCluster, "");
            String[] binArray = bins.isEmpty() ? new String[0] : bins.split(",");
            if (binArray.length >= 5) {
                tvLogs.append("\n> [ERROR] Klaster " + currentInjectCluster + " PENUH (Maks 5 Biner).");
                return;
            }

            String binName = currentInjectCluster.toLowerCase() + "_bin_" + System.currentTimeMillis();
            File destFile = new File(getFilesDir(), binName);

            InputStream in = getContentResolver().openInputStream(uri);
            FileOutputStream out = new FileOutputStream(destFile);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            in.close(); out.close();

            String newBins = bins.isEmpty() ? binName : bins + "," + binName;
            prefs.edit().putString(currentInjectCluster, newBins).apply();
            
            updateClusterStatusUI();
            tvLogs.append("\n> [SYSTEM] Biner berhasil disuntikkan ke " + currentInjectCluster);
            
        } catch (Exception e) {
            tvLogs.append("\n> [ERROR] Gagal injeksi: " + e.getMessage());
        }
    }

    private void updateClusterStatusUI() {
        updateStatusText("ALPHA", tvAlphaStatus);
        updateStatusText("BETA", tvBetaStatus);
        updateStatusText("GAMMA", tvGammaStatus);
    }

    private void updateStatusText(String cluster, TextView tv) {
        String bins = prefs.getString(cluster, "");
        int count = bins.isEmpty() ? 0 : bins.split(",").length;
        tv.setText("[ " + count + " / 5 ] Binaries Injected");
        if (count == 5) tv.setTextColor(Color.parseColor("#FF3D00"));
        else tv.setTextColor(Color.parseColor("#888888"));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(logReceiver);
    }
}
