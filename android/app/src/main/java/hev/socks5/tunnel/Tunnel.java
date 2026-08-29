package hev.socks5.tunnel;

public class Tunnel {
    static { 
        System.loadLibrary("hev-socks5-tunnel"); 
    }
    public native static void TunnelMain(int fd, String configPath);
    public native static void TunnelQuit();
}
