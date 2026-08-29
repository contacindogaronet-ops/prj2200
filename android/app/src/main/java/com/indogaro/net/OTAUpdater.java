package com.indogaro.net;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
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
    private static final String CURRENT_VERSION = "v3.0"; 
    private static final String GITHUB_API = "https://api.github.com/repos/contacindogaronet-ops/prj2200/releases/latest";

    public OTAUpdater(Activity activity) {
        this.activity = activity;
    }

    public void check() {
        new Thread(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(GITHUB_API).openConnection();
                conn.setRequestProperty("User-Agent", "Indogo-OTA-Engine");
                conn.setConnectTimeout(10000);
                
                if (conn.getResponseCode() != 200) {
                    throw new Exception("GitHub API menolak koneksi (Rate Limit / Not Found)");
                }
                
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) response.append(line);
                reader.close();

                JSONObject json = new JSONObject(response.toString());
                String latestVersion = json.getString("tag_name");
                String changelog = json.getString("body");
                JSONArray assets = json.getJSONArray("assets");
                
                if (assets.length() == 0) {
                    throw new Exception("Release ditemukan, tetapi tidak ada file APK di dalamnya.");
                }
                
                String downloadUrl = assets.getJSONObject(0).getString("browser_download_url");

                new Handler(Looper.getMainLooper()).post(() -> {
                    if (!CURRENT_VERSION.equals(latestVersion)) {
                        showUpdateDialog(latestVersion, changelog, downloadUrl);
                    } else {
                        new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                            .setTitle("Sistem Optimal")
                            .setMessage("Indogo sudah berada di versi terbaru (" + CURRENT_VERSION + ").")
                            .setPositiveButton("OK", null).show();
                    }
                });
            } catch (Exception e) {
                new Handler(Looper.getMainLooper()).post(() -> 
                    new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle("OTA Engine Error")
                        .setMessage(e.getMessage())
                        .setPositiveButton("OK", null).show()
                );
            }
        }).start();
    }

    private void showUpdateDialog(String version, String changelog, String downloadUrl) {
        AlertDialog.Builder builder = new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert);
        View view = LayoutInflater.from(activity).inflate(R.layout.dialog_update, null);
        builder.setView(view);
        AlertDialog dialog = builder.create();

        TextView tvVersion = view.findViewById(R.id.tvUpdateVersion);
        TextView tvChangelog = view.findViewById(R.id.tvUpdateChangelog);
        TextView tvProgressText = view.findViewById(R.id.tvProgressText);
        ProgressBar progressBar = view.findViewById(R.id.progressBar);
        LinearLayout layoutProgress = view.findViewById(R.id.layoutProgress);
        Button btnCancel = view.findViewById(R.id.btnUpdateCancel);
        Button btnAction = view.findViewById(R.id.btnUpdateAction);

        tvVersion.setText(version);
        tvChangelog.setText(changelog);

        btnCancel.setOnClickListener(v -> dialog.dismiss());
        btnAction.setOnClickListener(v -> {
            btnAction.setEnabled(false);
            btnCancel.setEnabled(false);
            layoutProgress.setVisibility(View.VISIBLE);
            downloadAndInstall(downloadUrl, progressBar, tvProgressText, dialog);
        });

        dialog.show();
    }

    // 🔴 KUNCI ARSITEKTUR: Penanganan Ekstraksi Redirect Lintas Domain
    private void downloadAndInstall(String urlString, ProgressBar progressBar, TextView tvProgressText, AlertDialog dialog) {
        new Thread(() -> {
            try {
                URL url = new URL(urlString);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setInstanceFollowRedirects(false); // Kita akan lacak manual
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                conn.connect();

                int status = conn.getResponseCode();
                // Jika GitHub mengalihkan ke server S3 AWS
                if (status == HttpURLConnection.HTTP_MOVED_TEMP || status == HttpURLConnection.HTTP_MOVED_PERM || status == HttpURLConnection.HTTP_SEE_OTHER) {
                    String redirectUrl = conn.getHeaderField("Location");
                    url = new URL(redirectUrl);
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(15000);
                    conn.connect();
                }

                if (conn.getResponseCode() != 200) {
                    throw new Exception("Gagal terhubung ke server unduhan.");
                }

                int fileLength = conn.getContentLength();

                File downloadDir = new File(activity.getExternalFilesDir(null), "Download");
                if (!downloadDir.exists()) downloadDir.mkdirs();
                File outputFile = new File(downloadDir, "indogo-update.apk");
                
                // Hapus file lama jika ada agar tidak korup
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
                            tvProgressText.setText("Downloading: " + progress + "%");
                        });
                    }
                }
                output.flush(); output.close(); input.close();

                handler.post(() -> {
                    dialog.dismiss();
                    installApk(outputFile);
                });
            } catch (Exception e) {
                new Handler(Looper.getMainLooper()).post(() -> tvProgressText.setText("Failed: " + e.getMessage()));
            }
        }).start();
    }

    private void installApk(File apkFile) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        Uri apkUri = FileProvider.getUriForFile(activity, "com.indogaro.net.provider", apkFile);
        intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activity.startActivity(intent);
    }
}
