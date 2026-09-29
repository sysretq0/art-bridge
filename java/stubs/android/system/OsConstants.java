package android.system;

/**
 * Compile-only stub for android.system.OsConstants.
 */
public final class OsConstants {
    private OsConstants() {}

    public static final int AF_UNIX = 1;
    public static final int SOCK_STREAM = 1;
    public static final int SOCK_DGRAM = 2;
    public static final int SOCK_SEQPACKET = 5;

    public static final int SOL_SOCKET = 1;
    public static final int SO_PEERCRED = 17;
    public static final int SO_RCVBUF = 8;
    public static final int SO_SNDBUF = 7;
    public static final int SO_RCVTIMEO = 20;
    public static final int SO_SNDTIMEO = 21;

    public static final int O_RDONLY = 0;
    public static final int O_WRONLY = 1;
    public static final int O_RDWR = 2;
    public static final int O_NONBLOCK = 04000;
    public static final int O_CLOEXEC = 02000000;

    public static final int EINTR = 4;
    public static final int EPIPE = 32;
    public static final int ECONNRESET = 104;
    public static final int EAGAIN = 11;
    public static final int EWOULDBLOCK = 11;
}
