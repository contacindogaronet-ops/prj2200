package com.indogaro.net;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private boolean isNativeConfigHooked = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Biarkan WebView/React Native Anda memuat antarmuka default di sini
    }

    @Override
    protected void onStart() {
        super.onStart();
        final ViewGroup rootView = (ViewGroup) findViewById(android.R.id.content);
        rootView.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                if (!isNativeConfigHooked) {
                    isNativeConfigHooked = injectNativeConfigButton(rootView);
                }
            }
        });
    }

    private boolean injectNativeConfigButton(View view) {
        if (view instanceof Button) {
            Button btn = (Button) view;
            if (btn.getText().toString().toUpperCase().contains("CHECK FOR UPDATES")) {
                ViewGroup parent = (ViewGroup) btn.getParent();
                if (parent != null) {
                    for (int i = 0; i < parent.getChildCount(); i++) {
                        View child = parent.getChildAt(i);
                        if (child instanceof Button && ((Button) child).getText().toString().contains("⚙️ CORE ENGINE SETTINGS")) {
                            return true;
                        }
                    }
                    Button myBtn = new Button(this);
                    myBtn.setText("⚙️ CORE ENGINE SETTINGS");
                    myBtn.setBackgroundColor(android.graphics.Color.parseColor("#37474F"));
                    myBtn.setTextColor(android.graphics.Color.WHITE);
                    myBtn.setLayoutParams(btn.getLayoutParams());
                    myBtn.setOnClickListener(v -> showIndogoSettings());

                    parent.addView(myBtn, parent.indexOfChild(btn) + 1);
                    return true;
                }
            }
        } else if (view instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) view;
            for (int i = 0; i < vg.getChildCount(); i++) {
                if (injectNativeConfigButton(vg.getChildAt(i))) return true;
            }
        }
        return false;
    }

    private void showIndogoSettings() {
        SharedPreferences prefs = getSharedPreferences("IndogoPrefs", MODE_PRIVATE);
        ScrollView scrollView = new ScrollView(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 40, 50, 40);
        scrollView.addView(layout);

        // Kategori: CORE
        TextView txtCore = new TextView(this);
        txtCore.setText("Core Settings");
        txtCore.setTextColor(android.graphics.Color.parseColor("#FF9800"));
        txtCore.setPadding(0, 0, 0, 10);
        layout.addView(txtCore);

        CheckBox chkSniffing = new CheckBox(this);
        chkSniffing.setText("Enable Sniffing (Layer 7 TLS)");
        chkSniffing.setChecked(prefs.getBoolean("sniffing", true));
        layout.addView(chkSniffing);

        CheckBox chkUdp = new CheckBox(this);
        chkUdp.setText("SOCKS5 UDP (Matikan = TCP 2007 Only)");
        chkUdp.setChecked(prefs.getBoolean("udp", true));
        layout.addView(chkUdp);

        // Kategori: VPN
        TextView txtVpn = new TextView(this);
        txtVpn.setText("VPN Settings");
        txtVpn.setTextColor(android.graphics.Color.parseColor("#FF9800"));
        txtVpn.setPadding(0, 40, 0, 10);
        layout.addView(txtVpn);

        CheckBox chkIpv6 = new CheckBox(this);
        chkIpv6.setText("Enable IPv6 (Dual Stack)");
        chkIpv6.setChecked(prefs.getBoolean("ipv6", true));
        layout.addView(chkIpv6);

        CheckBox chkFakeDns = new CheckBox(this);
        chkFakeDns.setText("Enable FakeDNS (Wajib DNS Pribadi OFF)");
        chkFakeDns.setChecked(prefs.getBoolean("fakedns", false));
        layout.addView(chkFakeDns);

        CheckBox chkBypassLan = new CheckBox(this);
        chkBypassLan.setText("Bypass LAN (Exclude 192.168.x.x)");
        chkBypassLan.setChecked(prefs.getBoolean("bypass_lan", false));
        layout.addView(chkBypassLan);

        // 🔴 KATEGORI BARU: ADVANCED ENGINE
        TextView txtAdv = new TextView(this);
        txtAdv.setText("Advanced Engine (Bahaya)");
        txtAdv.setTextColor(android.graphics.Color.parseColor("#E53935")); // Merah Peringatan
        txtAdv.setPadding(0, 40, 0, 10);
        layout.addView(txtAdv);

        CheckBox chkKill53 = new CheckBox(this);
        chkKill53.setText("KILL Port 53 (Bypass DNS dari VPN / Anti-Spam Golang)");
        chkKill53.setChecked(prefs.getBoolean("kill_53", false));
        layout.addView(chkKill53);

        EditText edtDns = new EditText(this);
        edtDns.setHint("VPN DNS (default: 8.8.8.8, 1.1.1.1)");
        edtDns.setText(prefs.getString("dns", "8.8.8.8, 1.1.1.1"));
        layout.addView(edtDns);

        EditText edtMtu = new EditText(this);
        edtMtu.setHint("VPN MTU (default 1500)");
        edtMtu.setText(String.valueOf(prefs.getInt("mtu", 1500)));
        layout.addView(edtMtu);

        new AlertDialog.Builder(this)
                .setTitle("Engine Settings")
                .setView(scrollView)
                .setPositiveButton("SIMPAN", (dialog, which) -> {
                    try {
                        prefs.edit()
                                .putBoolean("sniffing", chkSniffing.isChecked())
                                .putBoolean("udp", chkUdp.isChecked())
                                .putBoolean("ipv6", chkIpv6.isChecked())
                                .putBoolean("fakedns", chkFakeDns.isChecked())
                                .putBoolean("bypass_lan", chkBypassLan.isChecked())
                                .putBoolean("kill_53", chkKill53.isChecked()) // Simpan Parameter Baru
                                .putInt("mtu", Integer.parseInt(edtMtu.getText().toString().trim()))
                                .putString("dns", edtDns.getText().toString().trim())
                                .apply();
                        Toast.makeText(this, "Tersimpan! Silakan Restart VPN.", Toast.LENGTH_LONG).show();
                    } catch (Exception e) {
                        Toast.makeText(this, "Format MTU Salah!", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("BATAL", null)
                .show();
    }
}
