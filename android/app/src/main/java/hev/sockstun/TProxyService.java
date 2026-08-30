package hev.sockstun;

public class TProxyService {
    static { 
        System.loadLibrary("hev-socks5-tunnel"); 
    }
    
    // 🔴 KUNCI ARSITEKTUR FINAL: Nama Fungsi WAJIB TProxyStartService & TProxyStopService
    public native static void TProxyStartService(String configPath, int tunFd);
    public native static void TProxyStopService();
    public native static long[] TProxyGetStats();
}
