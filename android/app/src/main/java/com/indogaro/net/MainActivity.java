package com.indogaro.net;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // ROOT CONTAINER
        LinearLayout rootLayout = new LinearLayout(this);
        rootLayout.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setGravity(Gravity.CENTER);
        rootLayout.setBackgroundColor(Color.parseColor("#121824"));
        rootLayout.setPadding(40, 60, 40, 60);

        // HEADER
        TextView txtTitle = new TextView(this);
        txtTitle.setText("INDOGO NETWORK MATRIX");
        txtTitle.setTextColor(Color.parseColor("#38BDF8"));
        txtTitle.setTextSize(22f);
        txtTitle.setTypeface(null, Typeface.BOLD);
        txtTitle.setGravity(Gravity.CENTER);
        txtTitle.setPadding(0, 0, 0, 20);
        rootLayout.addView(txtTitle);

        TextView txtSubtitle = new TextView(this);
        txtSubtitle.setText("Layer-3 TUN & Daemon Orchestrator");
        txtSubtitle.setTextColor(Color.parseColor("#94A3B8"));
        txtSubtitle.setTextSize(13f);
        txtSubtitle.setGravity(Gravity.CENTER);
        txtSubtitle.setPadding(0, 0, 0, 60);
        rootLayout.addView(txtSubtitle);

        // ACTION BUTTONS CONTAINER
        LinearLayout btnContainer = new LinearLayout(this);
        btnContainer.setOrientation(LinearLayout.VERTICAL);
        btnContainer.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams containerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        btnContainer.setLayoutParams(containerParams);

        // BUTTON: SETTINGS
        Button btnSettings = new Button(this);
        btnSettings.setText("⚙️ CORE ENGINE SETTINGS");
        btnSettings.setBackgroundColor(Color.parseColor("#1E293B"));
        btnSettings.setTextColor(Color.WHITE);
        LinearLayout.LayoutParams btnParams1 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        btnParams1.setMargins(0, 0, 0, 25);
        btnSettings.setLayoutParams(btnParams1);
        btnSettings.setOnClickListener(v -> showIndogoSettings());
        btnContainer.addView(btnSettings);

        // BUTTON: CHECK FOR UPDATES
        Button btnUpdate = new Button(this);
        btnUpdate.setText("🔄 CHECK FOR UPDATES");
        btnUpdate.setBackgroundColor(Color.parseColor("#0284C7"));
        btnUpdate.setTextColor(Color.WHITE);
        LinearLayout.LayoutParams btnParams2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        btnUpdate.setLayoutParams(btnParams2);
        btnUpdate.setOnClickListener(v -> new OTAUpdater(this).check(true));
        btnContainer.addView(btnUpdate);

        rootLayout.addView(btnContainer);

        setContentView(rootLayout);
    }

    private void showIndogoSettings() {
        SharedPreferences prefs = getSharedPreferences("IndogoPrefs", MODE_PRIVATE);
        ScrollView scrollView = new ScrollView(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 40, 50, 40);
        scrollView.addView(layout);

        TextView txtCore = new TextView(this);
        txtCore.setText("Core Settings");
        txtCore.setTextColor(Color.parseColor("#FF9800"));
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

        TextView txtVpn = new TextView(this);
        txtVpn.setText("VPN Settings");
        txtVpn.setTextColor(Color.parseColor("#FF9800"));
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

        TextView txtAdv = new TextView(this);
        txtAdv.setText("Advanced Engine (Bahaya)");
        txtAdv.setTextColor(Color.parseColor("#E53935"));
        txtAdv.setPadding(0, 40, 0, 10);
        layout.addView(txtAdv);

        CheckBox chkKill53 = new CheckBox(this);
        chkKill53.setText("KILL Port 53 (Bypass DNS dari VPN)");
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
                                .putBoolean("kill_53", chkKill53.isChecked())
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
