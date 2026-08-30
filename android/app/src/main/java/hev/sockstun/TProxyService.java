package hev.sockstun;

public class TProxyService {
    static { 
        System.loadLibrary("hev-socks5-tunnel"); 
    }
    
    // 🔴 KUNCI ARSITEKTUR FINAL: Nama Class WAJIB TProxyService
    public native static void TunnelMain(String configPath, int tunFd);
    public native static void TunnelMain(int tunFd, String configPath);
    public native static void TunnelQuit();
}
