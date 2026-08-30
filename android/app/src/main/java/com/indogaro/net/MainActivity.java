package com.indogaro.net;

import android.content.SharedPreferences;
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
        
        // 🔴 ARSITEKTUR NATIVE: Mencegah layar Blank
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(android.graphics.Color.parseColor("#0F172A"));

        TextView title = new TextView(this);
        title.setText("INDOGO ENGINE");
        title.setTextColor(android.graphics.Color.parseColor("#38BDF8"));
        title.setTextSize(24f);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding(0, 0, 0, 60);
        root.addView(title);

        Button btnConfig = new Button(this);
        btnConfig.setText("⚙️ CORE ENGINE SETTINGS");
        btnConfig.setBackgroundColor(android.graphics.Color.parseColor("#1E293B"));
        btnConfig.setTextColor(android.graphics.Color.WHITE);
        btnConfig.setOnClickListener(v -> showIndogoSettings());

        Button btnUpdate = new Button(this);
        btnUpdate.setText("🔄 CHECK FOR UPDATES");
        btnUpdate.setBackgroundColor(android.graphics.Color.parseColor("#0369A1"));
        btnUpdate.setTextColor(android.graphics.Color.WHITE);
        btnUpdate.setOnClickListener(v -> new OTAUpdater(this).check(true));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, 20, 0, 0);
        btnUpdate.setLayoutParams(params);
        btnConfig.setLayoutParams(params);

        root.addView(btnConfig);
        root.addView(btnUpdate);

        setContentView(root); // Render UI ke layar
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

        TextView txtAdv = new TextView(this);
        txtAdv.setText("Advanced Engine (Bahaya)");
        txtAdv.setTextColor(android.graphics.Color.parseColor("#E53935"));
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
