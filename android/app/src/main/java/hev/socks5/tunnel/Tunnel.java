package hev.socks5.tunnel;

public class Tunnel {
    static { 
        System.loadLibrary("hev-socks5-tunnel"); 
    }
    // 🔴 KOREKSI ARSITEKTUR MUTLAK: String (Path) dulu, baru int (FD)
    public native static void TunnelMain(String configPath, int tunFd);
    public native static void TunnelQuit();
}
