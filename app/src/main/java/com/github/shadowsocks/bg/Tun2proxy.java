package com.github.shadowsocks.bg;

/** JNI bridge exported by the pinned MIT-licensed tun2proxy Android build. */
public final class Tun2proxy {
    static {
        System.loadLibrary("tun2proxy");
    }

    private Tun2proxy() { }

    /** Runs the TUN packet engine. The file descriptor remains owned by VpnService. */
    public static native int run(String cliArguments, char tunMtu);

    /** Requests the currently running engine to stop. */
    public static native int stop();
}
