package com.indogaro.net;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.TrafficStats;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
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
    private Button btnPanic, btnCheckUpdate;
    private Switch switchAutoRestart, switchWakelock, switchLowLatency;
    
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

    // VPN Pending Execution Cache
    private String pendingVpnCluster = "";
    private String pendingVpnBins = "";

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
        myUid = android.os.Process.myUid();
        
        prefs = getSharedPreferences("ClusterMatrix", MODE_PRIVATE);
        settingsPrefs = getSharedPreferences("DaemonSettings", MODE_PRIVATE);
        loadClusterList();

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
        btnCheckUpdate = findViewById(R.id.btnCheckUpdate);
        
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
        
        graphRam.setLineColor("#FF9F0A"); 
        graphRx.setLineColor("#34C759");  
        graphTx.setLineColor("#5E5CE6");  

        long uidRx = TrafficStats.getUidRxBytes(myUid);
        long uidTx = TrafficStats.getUidTxBytes(myUid);
        lastRxBytes = (uidRx == TrafficStats.UNSUPPORTED) ? 0 : uidRx;
        lastTxBytes = (uidTx == TrafficStats.UNSUPPORTED) ? 0 : uidTx;

        setupNavigation();
        renderDynamicClusters();
        startTelemetryEngine();

        btnAddCluster.setOnClickListener(v -> showCreateClusterModal());
        btnCheckUpdate.setOnClickListener(v -> new OTAUpdater(this).check(true));
        
        btnPanic.setOnClickListener(v -> {
            Intent panicIntent = new Intent(this, DaemonService.class);
            panicIntent.setAction("PANIC_KILL_ALL");
            startService(panicIntent);

            Intent vpnStopIntent = new Intent(this, IndogoVpnService.class);
            vpnStopIntent.setAction("STOP_VPN");
            startService(vpnStopIntent);
            
            new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("PANIC INITIATED")
                .setMessage("Semua proses biner dan VPN Gateway telah dimatikan secara total.")
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
                    long currentRx = TrafficStats.getUidRxBytes(myUid);
                    long currentTx = TrafficStats.getUidTxBytes(myUid);
                    
                    if (currentRx != TrafficStats.UNSUPPORTED) {
                        long diffRx = currentRx - lastRxBytes;
                        long diffTx = currentTx - lastTxBytes;
                        
                        tvRx.setText(formatSpeed(diffRx));
                        tvTx.setText(formatSpeed(diffTx));
                        
                        graphRx.addDataPoint(diffRx);
                        graphTx.addDataPoint(diffTx);
                        
                        tvTotalData.setText(formatDataSize(currentRx + currentTx));
                        
                        lastRxBytes = currentRx;
                        lastTxBytes = currentTx;
                    }

                    long uptimeMillis = System.currentTimeMillis() - appStartTime;
                    int seconds = (int) (uptimeMillis / 1000) % 60;
                    int minutes = (int) ((uptimeMillis / (1000 * 60)) % 60);
                    int hours = (int) ((uptimeMillis / (1000 * 60 * 60)) % 24);
                    tvUptime.setText(String.format(Locale.US, "UPTIME: %02d:%02d:%02d", hours, minutes, seconds));

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

    // 🔴 KUNCI ARSITEKTUR: Modal Pembuatan Klaster Modern & Interaktif
    private void showCreateClusterModal() {
        Dialog dialog = new Dialog(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar);
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_create_cluster, null);
        dialog.setContentView(view);

        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setGravity(Gravity.CENTER);
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        EditText inputName = view.findViewById(R.id.inputClusterName);
        TextView btnTypeDaemon = view.findViewById(R.id.btnTypeDaemon);
        TextView btnTypeVpn = view.findViewById(R.id.btnTypeVpn);
        LinearLayout layoutVpnConfig = view.findViewById(R.id.layoutVpnConfig);
        TextView btnProtoSocks = view.findViewById(R.id.btnProtoSocks);
        TextView btnProtoHttp = view.findViewById(R.id.btnProtoHttp);
        EditText inputHost = view.findViewById(R.id.inputProxyHost);
        EditText inputPort = view.findViewById(R.id.inputProxyPort);
        Button btnCancel = view.findViewById(R.id.btnModalCancel);
        Button btnCreate = view.findViewById(R.id.btnModalCreate);

        final String[] selectedType = {"DAEMON"};
        final String[] selectedProto = {"SOCKS5"};

        btnTypeDaemon.setOnClickListener(v -> {
            selectedType[0] = "DAEMON";
            btnTypeDaemon.setBackgroundResource(R.drawable.bg_btn_primary);
            btnTypeDaemon.setTextColor(Color.WHITE);
            btnTypeVpn.setBackground(null);
            btnTypeVpn.setTextColor(Color.parseColor("#8E8E93"));
            layoutVpnConfig.setVisibility(View.GONE);
        });

        btnTypeVpn.setOnClickListener(v -> {
            selectedType[0] = "VPN";
            btnTypeVpn.setBackgroundResource(R.drawable.bg_btn_primary);
            btnTypeVpn.setTextColor(Color.WHITE);
            btnTypeDaemon.setBackground(null);
            btnTypeDaemon.setTextColor(Color.parseColor("#8E8E93"));
            layoutVpnConfig.setVisibility(View.VISIBLE);
        });

        btnProtoSocks.setOnClickListener(v -> {
            selectedProto[0] = "SOCKS5";
            btnProtoSocks.setBackgroundResource(R.drawable.bg_btn_primary);
            btnProtoSocks.setTextColor(Color.WHITE);
            btnProtoHttp.setBackground(null);
            btnProtoHttp.setTextColor(Color.parseColor("#8E8E93"));
        });

        btnProtoHttp.setOnClickListener(v -> {
            selectedProto[0] = "HTTP";
            btnProtoHttp.setBackgroundResource(R.drawable.bg_btn_primary);
            btnProtoHttp.setTextColor(Color.WHITE);
            btnProtoSocks.setBackground(null);
            btnProtoSocks.setTextColor(Color.parseColor("#8E8E93"));
        });

        btnCancel.setOnClickListener(v -> dialog.dismiss());

        btnCreate.setOnClickListener(v -> {
            String name = inputName.getText().toString().trim().toUpperCase().replaceAll("[^A-Z0-9_]", "_");
            if (name.isEmpty()) return;

            if (!clusterList.contains(name)) {
                clusterList.add(name);
                saveClusterList();

                // Simpan metadata klaster
                prefs.edit().putString(name + "_TYPE", selectedType[0]).apply();
                if ("VPN".equals(selectedType[0])) {
                    prefs.edit().putString(name + "_HOST", inputHost.getText().toString().trim()).apply();
                    int port = 10808;
                    try { port = Integer.parseInt(inputPort.getText().toString().trim()); } catch (Exception e) {}
                    prefs.edit().putInt(name + "_PORT", port).apply();
                    prefs.edit().putString(name + "_PROTO", selectedProto[0]).apply();
                }

                renderDynamicClusters();
                dialog.dismiss();
            }
        });

        dialog.show();
    }

    private void renderDynamicClusters() {
        int childCount = clusterContainer.getChildCount();
        for (int i = childCount - 1; i >= 1; i--) clusterContainer.removeViewAt(i); 

        for (String clusterName : clusterList) {
            View card = getLayoutInflater().inflate(R.layout.item_cluster, clusterContainer, false);
            
            TextView tvName = card.findViewById(R.id.tvClusterName);
            TextView tvBadge = card.findViewById(R.id.tvClusterBadge);
            TextView tvStatus = card.findViewById(R.id.tvClusterStatus);
            TextView btnInject = card.findViewById(R.id.btnClusterInject);
            TextView btnStart = card.findViewById(R.id.btnClusterStart);
            TextView btnBins = card.findViewById(R.id.btnClusterBins);
            TextView btnStop = card.findViewById(R.id.btnClusterStop);
            Button btnLogs = card.findViewById(R.id.btnClusterLogs);
            Button btnDelete = card.findViewById(R.id.btnClusterDelete);

            tvName.setText(clusterName);
            String type = prefs.getString(clusterName + "_TYPE", "DAEMON");
            
            if ("VPN".equals(type)) {
                String proto = prefs.getString(clusterName + "_PROTO", "SOCKS5");
                int port = prefs.getInt(clusterName + "_PORT", 10808);
                tvBadge.setText("🛡️ VPN GATEWAY (" + proto + " :" + port + ")");
                tvBadge.setTextColor(Color.parseColor("#34C759"));
            } else {
                tvBadge.setText("⚡ BOT DAEMON PROCESS");
                tvBadge.setTextColor(Color.parseColor("#38BDF8"));
            }

            String bins = prefs.getString(clusterName, "");
            int count = bins.isEmpty() ? 0 : bins.split(",").length;
            tvStatus.setText(count + " BINARIES READY");

            btnDelete.setOnClickListener(v -> {
                new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Delete Node")
                    .setMessage("Hancurkan cluster " + clusterName + " secara permanen?")
                    .setPositiveButton("HAPUS", (dialog, which) -> {
                        stopClusterExecution(clusterName);
                        clusterList.remove(clusterName);
                        saveClusterList();
                        prefs.edit().remove(clusterName).remove(clusterName + "_TYPE").apply();
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

            btnStart.setOnClickListener(v -> startClusterExecution(clusterName, bins, type));
            btnBins.setOnClickListener(v -> showManageBinsModal(clusterName));
            btnStop.setOnClickListener(v -> stopClusterExecution(clusterName));
            btnLogs.setOnClickListener(v -> openInteractiveShellDialog(clusterName));

            clusterContainer.addView(card);
        }
    }

    private void startClusterExecution(String clusterName, String bins, String type) {
        if ("VPN".equals(type)) {
            // Cek Izin OS VPN Terlebih Dahulu
            Intent vpnPrepareIntent = VpnService.prepare(this);
            if (vpnPrepareIntent != null) {
                pendingVpnCluster = clusterName;
                pendingVpnBins = bins;
                startActivityForResult(vpnPrepareIntent, 1001);
                return;
            }
            executeVpnAndDaemon(clusterName, bins);
        } else {
            if (bins.isEmpty()) return;
            Intent intent = new Intent(this, DaemonService.class);
            intent.setAction("START_CLUSTER");
            intent.putExtra("CLUSTER", clusterName);
            intent.putExtra("BINS", bins);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent);
            else startService(intent);
        }
    }

    private void executeVpnAndDaemon(String clusterName, String bins) {
        // 1. Eksekusi Biner (Jika Diinjeksi)
        if (!bins.isEmpty()) {
            Intent daemonIntent = new Intent(this, DaemonService.class);
            daemonIntent.setAction("START_CLUSTER");
            daemonIntent.putExtra("CLUSTER", clusterName);
            daemonIntent.putExtra("BINS", bins);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(daemonIntent);
            else startService(daemonIntent);
        }

        // 2. Eksekusi VPN Routing Service
        String host = prefs.getString(clusterName + "_HOST", "127.0.0.1");
        int port = prefs.getInt(clusterName + "_PORT", 10808);
        String proto = prefs.getString(clusterName + "_PROTO", "SOCKS5");

        Intent vpnIntent = new Intent(this, IndogoVpnService.class);
        vpnIntent.setAction("START_VPN");
        vpnIntent.putExtra("CLUSTER", clusterName);
        vpnIntent.putExtra("HOST", host);
        vpnIntent.putExtra("PORT", port);
        vpnIntent.putExtra("PROTO", proto);
        startService(vpnIntent);
    }

    private void stopClusterExecution(String clusterName) {
        Intent intent = new Intent(this, DaemonService.class);
        intent.setAction("STOP_CLUSTER");
        intent.putExtra("CLUSTER", clusterName);
        startService(intent);

        String type = prefs.getString(clusterName + "_TYPE", "DAEMON");
        if ("VPN".equals(type)) {
            Intent vpnStopIntent = new Intent(this, IndogoVpnService.class);
            vpnStopIntent.setAction("STOP_VPN");
            startService(vpnStopIntent);
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
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == RESULT_OK) {
            if (requestCode == 1 && data != null) {
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
            } else if (requestCode == 1001) {
                // Izin VPN Disetujui oleh User
                if (!pendingVpnCluster.isEmpty()) {
                    executeVpnAndDaemon(pendingVpnCluster, pendingVpnBins);
                    pendingVpnCluster = "";
                    pendingVpnBins = "";
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
    // 🔴 KUNCI ARSITEKTUR: Bin Manager Logic
    private void showManageBinsModal(String clusterName) {
        Dialog dialog = new Dialog(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar);
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_manage_bins, null);
        dialog.setContentView(view);
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setGravity(android.view.Gravity.CENTER);
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        }

        TextView tvSubtitle = view.findViewById(R.id.tvManageBinSubtitle);
        tvSubtitle.setText("TARGET NODE: " + clusterName);
        LinearLayout container = view.findViewById(R.id.containerBinList);
        Button btnClose = view.findViewById(R.id.btnManageBinsClose);

        Runnable refreshList = new Runnable() {
            @Override
            public void run() {
                container.removeAllViews();
                String binsStr = prefs.getString(clusterName, "");
                if (binsStr.isEmpty()) {
                    TextView empty = new TextView(MainActivity.this);
                    empty.setText("Node ini kosong. Silakan INJECT biner.");
                    empty.setTextColor(android.graphics.Color.parseColor("#475569"));
                    container.addView(empty);
                    return;
                }
                String[] bins = binsStr.split(",");
                for (String bin : bins) {
                    if (bin.trim().isEmpty()) continue;
                    LinearLayout row = new LinearLayout(MainActivity.this);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setPadding(0, 24, 0, 24);
                    row.setGravity(android.view.Gravity.CENTER_VERTICAL);

                    TextView name = new TextView(MainActivity.this);
                    name.setText(bin);
                    name.setTextColor(android.graphics.Color.WHITE);
                    name.setTextSize(13f);
                    name.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));

                    Button btnDel = new Button(MainActivity.this);
                    btnDel.setText("HAPUS");
                    btnDel.setTextSize(11f);
                    btnDel.setBackgroundTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#991B1B")));
                    btnDel.setTextColor(android.graphics.Color.WHITE);
                    btnDel.setLayoutParams(new LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 100));
                    
                    btnDel.setOnClickListener(v -> {
                        new java.io.File(getFilesDir(), bin).delete();
                        java.util.List<String> list = new java.util.ArrayList<>(java.util.Arrays.asList(prefs.getString(clusterName, "").split(",")));
                        list.remove(bin);
                        prefs.edit().putString(clusterName, String.join(",", list)).apply();
                        renderDynamicClusters();
                        this.run(); 
                    });

                    row.addView(name);
                    row.addView(btnDel);
                    container.addView(row);
                }
            }
        };
        refreshList.run();
        btnClose.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    // 🔴 KUNCI ARSITEKTUR: Injeksi Floating Button (Bypass XML Layout)
    private boolean isSettingsBtnAdded = false;

    @Override
    protected void onStart() {
        super.onStart();
        if (!isSettingsBtnAdded) {
            android.widget.FrameLayout.LayoutParams params = new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            );
            params.gravity = android.view.Gravity.TOP | android.view.Gravity.END;
            params.setMargins(0, 150, 40, 0);

            android.widget.Button btn = new android.widget.Button(this);
            btn.setText("⚙️ SETTINGS");
            btn.setBackgroundColor(android.graphics.Color.parseColor("#D84315")); // Deep Orange
            btn.setTextColor(android.graphics.Color.WHITE);
            btn.setElevation(20f);
            btn.setOnClickListener(v -> showIndogoSettings());

            addContentView(btn, params);
            isSettingsBtnAdded = true;
        }
    }

    private void showIndogoSettings() {
        android.content.SharedPreferences prefs = getSharedPreferences("IndogoPrefs", MODE_PRIVATE);
        android.widget.ScrollView scrollView = new android.widget.ScrollView(this);
        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setPadding(50, 40, 50, 40);
        scrollView.addView(layout);

        android.widget.TextView txtCore = new android.widget.TextView(this);
        txtCore.setText("Core Settings");
        txtCore.setTextColor(android.graphics.Color.parseColor("#FF9800"));
        txtCore.setPadding(0, 0, 0, 10);
        layout.addView(txtCore);

        android.widget.CheckBox chkSniffing = new android.widget.CheckBox(this);
        chkSniffing.setText("Enable Sniffing (Layer 7 TLS)");
        chkSniffing.setChecked(prefs.getBoolean("sniffing", true));
        layout.addView(chkSniffing);

        android.widget.CheckBox chkUdp = new android.widget.CheckBox(this);
        chkUdp.setText("SOCKS5 UDP");
        chkUdp.setChecked(prefs.getBoolean("udp", true));
        layout.addView(chkUdp);

        android.widget.TextView txtVpn = new android.widget.TextView(this);
        txtVpn.setText("VPN Settings");
        txtVpn.setTextColor(android.graphics.Color.parseColor("#FF9800"));
        txtVpn.setPadding(0, 40, 0, 10);
        layout.addView(txtVpn);

        android.widget.CheckBox chkIpv6 = new android.widget.CheckBox(this);
        chkIpv6.setText("Enable IPv6");
        chkIpv6.setChecked(prefs.getBoolean("ipv6", true));
        layout.addView(chkIpv6);

        android.widget.CheckBox chkLocalDns = new android.widget.CheckBox(this);
        chkLocalDns.setText("Enable local DNS (127.0.0.1)");
        chkLocalDns.setChecked(prefs.getBoolean("local_dns", true));
        layout.addView(chkLocalDns);

        android.widget.CheckBox chkFakeDns = new android.widget.CheckBox(this);
        chkFakeDns.setText("Enable fake DNS (Domain -> Proxy)");
        chkFakeDns.setChecked(prefs.getBoolean("fakedns", false));
        layout.addView(chkFakeDns);

        android.widget.CheckBox chkBypassLan = new android.widget.CheckBox(this);
        chkBypassLan.setText("Bypass LAN");
        chkBypassLan.setChecked(prefs.getBoolean("bypass_lan", false));
        layout.addView(chkBypassLan);

        android.widget.EditText edtDns = new android.widget.EditText(this);
        edtDns.setHint("VPN DNS (only IPv4/v6)");
        edtDns.setText(prefs.getString("dns", "1.1.1.1"));
        layout.addView(edtDns);

        android.widget.EditText edtMtu = new android.widget.EditText(this);
        edtMtu.setHint("VPN MTU (default 1500)");
        edtMtu.setText(String.valueOf(prefs.getInt("mtu", 1500)));
        layout.addView(edtMtu);

        new android.app.AlertDialog.Builder(this)
            .setTitle("V2Ray Clone Settings")
            .setView(scrollView)
            .setPositiveButton("SIMPAN", (dialog, which) -> {
                try {
                    prefs.edit()
                        .putBoolean("sniffing", chkSniffing.isChecked())
                        .putBoolean("udp", chkUdp.isChecked())
                        .putBoolean("ipv6", chkIpv6.isChecked())
                        .putBoolean("local_dns", chkLocalDns.isChecked())
                        .putBoolean("fakedns", chkFakeDns.isChecked())
                        .putBoolean("bypass_lan", chkBypassLan.isChecked())
                        .putInt("mtu", Integer.parseInt(edtMtu.getText().toString().trim()))
                        .putString("dns", edtDns.getText().toString().trim())
                        .apply();
                    android.widget.Toast.makeText(this, "Disimpan! Matikan dan nyalakan ulang VPN.", android.widget.Toast.LENGTH_LONG).show();
                } catch (Exception e) {
                    android.widget.Toast.makeText(this, "Gagal: Format Salah!", android.widget.Toast.LENGTH_SHORT).show();
                }
            })
            .setNegativeButton("BATAL", null)
            .show();
    }
}
