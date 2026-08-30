package hev.socks5;

public class Tunnel {
    static { 
        System.loadLibrary("hev-socks5-tunnel"); 
    }
    
    // 🔴 KUNCI ARSITEKTUR: Package name WAJIB hev.socks5, tidak boleh kurang/lebih 1 huruf pun
    public native static void TunnelMain(String configPath, int tunFd);
    public native static void TunnelQuit();
}
