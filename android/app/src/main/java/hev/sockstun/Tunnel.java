package hev.sockstun;

public class Tunnel {
    static { 
        System.loadLibrary("hev-socks5-tunnel"); 
    }
    // 🔴 KUNCI ARSITEKTUR: Package name adalah hev.sockstun
    public native static void TunnelMain(String configPath, int tunFd);
    public native static void TunnelQuit();
}
