package hev.socks5;

public class Tunnel {
    static { 
        System.loadLibrary("hev-socks5-tunnel"); 
    }
    // 🔴 KUNCI ARSITEKTUR: Namespace hev.socks5 dan parameter String -> int
    public native static void TunnelMain(String configPath, int tunFd);
    public native static void TunnelQuit();
}
