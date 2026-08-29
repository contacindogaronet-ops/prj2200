package hev.sockstun;

public class Tunnel {
    static { 
        System.loadLibrary("hev-socks5-tunnel"); 
    }
    
    // 🔴 KUNCI ARSITEKTUR: Dual-Signature Overloading
    // Jika Heiher mengubah urutan parameter di versi 8.0, Java akan menembak keduanya dengan aman.
    public native static void TunnelMain(String configPath, int tunFd);
    public native static void TunnelMain(int tunFd, String configPath);
    public native static void TunnelQuit();
}
