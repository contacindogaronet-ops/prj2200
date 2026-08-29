package com.indogaro.net;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.core.content.FileProvider;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class OTAUpdater {
    private Activity activity;
    private static final String GITHUB_API = "https://api.github.com/repos/contacindogaronet-ops/prj2200/releases/latest";
    private static final long AUTO_CHECK_COOLDOWN_MS = 4 * 60 * 60 * 1000; 

    public OTAUpdater(Activity activity) {
        this.activity = activity;
    }

    public void check(boolean isManual) {
        SharedPreferences prefs = activity.getSharedPreferences("DaemonSettings", Context.MODE_PRIVATE);
        long lastCheck = prefs.getLong("LAST_OTA_CHECK", 0);
        long now = System.currentTimeMillis();

        if (!isManual && (now - lastCheck < AUTO_CHECK_COOLDOWN_MS)) return;

        new Thread(() -> {
            try {
                prefs.edit().putLong("LAST_OTA_CHECK", now).apply();
                String currentVersion = "v" + activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionName;

                HttpURLConnection conn = (HttpURLConnection) new URL(GITHUB_API).openConnection();
                conn.setRequestProperty("User-Agent", "Indogo-OTA-Engine");
                conn.setConnectTimeout(10000);
                
                if (conn.getResponseCode() != 200) throw new Exception("GitHub API menolak koneksi.");
                
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) response.append(line);
                reader.close();

                JSONObject json = new JSONObject(response.toString());
                String latestVersion = json.getString("tag_name");
                String changelog = json.getString("body");
                String publishDate = json.getString("published_at").split("T")[0];
                JSONArray assets = json.getJSONArray("assets");
                
                if (assets.length() == 0) throw new Exception("Tidak ada file APK pada release ini.");
                String downloadUrl = assets.getJSONObject(0).getString("browser_download_url");

                new Handler(Looper.getMainLooper()).post(() -> {
                    boolean isUpdateAvailable = !currentVersion.equals(latestVersion);
                    if (isUpdateAvailable) {
                        showBottomSheet(true, currentVersion, latestVersion, changelog, publishDate, downloadUrl);
                    } else if (isManual) {
                        // 🔴 KUNCI ARSITEKTUR: Teruskan downloadUrl ke panel meskipun sistem optimal
                        showBottomSheet(false, currentVersion, latestVersion, changelog, publishDate, downloadUrl);
                    }
                });
            } catch (Exception e) {
                if (isManual) {
                    new Handler(Looper.getMainLooper()).post(() -> 
                        showBottomSheet(false, "ERROR", "ERROR", e.getMessage(), "--", null)
                    );
                }
            }
        }).start();
    }

    private void showBottomSheet(boolean isUpdateAvailable, String currentVersion, String latestVersion, String changelog, String date, String downloadUrl) {
        Dialog bottomDialog = new Dialog(activity, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar);
        View view = LayoutInflater.from(activity).inflate(R.layout.dialog_update_bottom, null);
        bottomDialog.setContentView(view);
        
        Window window = bottomDialog.getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setGravity(Gravity.BOTTOM);
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        TextView tvIcon = view.findViewById(R.id.tvUpdateIcon);
        TextView tvTitle = view.findViewById(R.id.tvUpdateTitle);
        TextView tvVersion = view.findViewById(R.id.tvVersionInfo);
        TextView tvDate = view.findViewById(R.id.tvDateInfo);
        TextView tvChangelog = view.findViewById(R.id.tvUpdateChangelog);
        ScrollView scrollChangelog = view.findViewById(R.id.scrollChangelog);
        LinearLayout layoutProgress = view.findViewById(R.id.layoutProgress);
        ProgressBar progressBar = view.findViewById(R.id.progressBar);
        TextView tvProgressText = view.findViewById(R.id.tvProgressText);
        
        // Deklarasi tombol ganda
        Button btnCancel = view.findViewById(R.id.btnUpdateCancel);
        Button btnAction = view.findViewById(R.id.btnUpdateAction);

        tvDate.setText("Tanggal Rilis: " + date);

        if (isUpdateAvailable) {
            tvIcon.setText("🚀");
            tvTitle.setText("PEMBARUAN TERSEDIA");
            tvTitle.setTextColor(Color.parseColor("#3B82F6"));
            tvVersion.setText("Versi Saat Ini: " + currentVersion + "\nVersi Terbaru: " + latestVersion);
            tvChangelog.setText(changelog);
            
            btnCancel.setText("NANTI");
            btnAction.setText("UNDUH & INSTALL");
            btnAction.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#3B82F6")));
        } else {
            // 🔴 KUNCI ARSITEKTUR: Override State (Paksa Instal)
            tvIcon.setText("✅");
            tvTitle.setText("SISTEM OPTIMAL");
            tvTitle.setTextColor(Color.parseColor("#34C759"));
            tvVersion.setText("Versi Saat Ini: " + currentVersion + " (Mutakhir)");
            scrollChangelog.setVisibility(View.GONE);
            
            btnCancel.setText("TUTUP");
            btnAction.setText("PAKSA INSTAL");
            // Warna merah gelap untuk indikasi "Tindakan Paksa"
            btnAction.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#991B1B"))); 
            
            if (downloadUrl == null) {
                btnAction.setVisibility(View.GONE); // Sembunyikan jika error server
            }
        }

        btnCancel.setOnClickListener(v -> bottomDialog.dismiss());
        
        if (downloadUrl != null) {
            btnAction.setOnClickListener(v -> {
                btnAction.setEnabled(false);
                btnCancel.setEnabled(false);
                btnAction.setText("MEMPROSES...");
                layoutProgress.setVisibility(View.VISIBLE);
                downloadAndInstall(downloadUrl, progressBar, tvProgressText, bottomDialog);
            });
        }

        bottomDialog.show();
    }

    private void downloadAndInstall(String urlString, ProgressBar progressBar, TextView tvProgressText, Dialog dialog) {
        new Thread(() -> {
            try {
                URL url = new URL(urlString);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setInstanceFollowRedirects(false); 
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                conn.connect();

                int status = conn.getResponseCode();
                if (status == HttpURLConnection.HTTP_MOVED_TEMP || status == HttpURLConnection.HTTP_MOVED_PERM || status == HttpURLConnection.HTTP_SEE_OTHER) {
                    String redirectUrl = conn.getHeaderField("Location");
                    url = new URL(redirectUrl);
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(15000);
                    conn.connect();
                }

                if (conn.getResponseCode() != 200) throw new Exception("Gagal terhubung ke server unduhan.");
                int fileLength = conn.getContentLength();

                File downloadDir = new File(activity.getExternalFilesDir(null), "Download");
                if (!downloadDir.exists()) downloadDir.mkdirs();
                File outputFile = new File(downloadDir, "indogo-update.apk");
                
                if (outputFile.exists()) outputFile.delete(); 

                InputStream input = conn.getInputStream();
                FileOutputStream output = new FileOutputStream(outputFile);

                byte[] data = new byte[4096];
                long total = 0;
                int count;
                Handler handler = new Handler(Looper.getMainLooper());

                while ((count = input.read(data)) != -1) {
                    total += count;
                    output.write(data, 0, count);
                    if (fileLength > 0) {
                        int progress = (int) (total * 100 / fileLength);
                        handler.post(() -> {
                            progressBar.setProgress(progress);
                            tvProgressText.setText("Mengunduh: " + progress + "%");
                        });
                    }
                }
                output.flush(); output.close(); input.close();

                handler.post(() -> {
                    dialog.dismiss();
                    installApk(outputFile);
                });
            } catch (Exception e) {
                new Handler(Looper.getMainLooper()).post(() -> tvProgressText.setText("Gagal: " + e.getMessage()));
            }
        }).start();
    }

    private void installApk(File apkFile) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!activity.getPackageManager().canRequestPackageInstalls()) {
                Intent permIntent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
                permIntent.setData(Uri.parse("package:" + activity.getPackageName()));
                activity.startActivity(permIntent);
                return;
            }
        }
        
        Intent intent = new Intent(Intent.ACTION_VIEW);
        Uri apkUri = FileProvider.getUriForFile(activity, "com.indogaro.net.provider", apkFile);
        intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activity.startActivity(intent);
    }
}
