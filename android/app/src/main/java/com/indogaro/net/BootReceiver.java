package com.indogaro.net;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) || Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)) {

            // 🔴 KUNCI ARSITEKTUR: Dukungan FBE (File-Based Encryption) untuk Locked Boot
            Context storageContext = context;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                if (!context.isDeviceProtectedStorage()) {
                    storageContext = context.createDeviceProtectedStorageContext();
                }
            }

            SharedPreferences settingsPrefs = storageContext.getSharedPreferences("DaemonSettings", Context.MODE_PRIVATE);
            
            // Cek apakah Auto-Restart Engine diizinkan secara global
            if (settingsPrefs.getBoolean("AUTO_RESTART", true)) {
                Intent serviceIntent = new Intent(context, DaemonService.class);
                serviceIntent.setAction("AUTO_IGNITION");

                // 🔴 PENCEGAHAN CRASH: OEM Aggressive Background Kill & API 31+
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent);
                    } else {
                        context.startService(serviceIntent);
                    }
                } catch (Exception e) {
                    // Sistem operasi memblokir eksekusi background (Biasa terjadi di MIUI/ColorOS)
                }
            }
        }
    }
}
