package android.system;

import java.io.FileDescriptor;
import java.io.InterruptedIOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;

/**
 * Compile-only stub for android.system.Os.
 */
public final class Os {
    private Os() {}

    public static FileDescriptor socket(int domain, int type, int protocol) throws ErrnoException {
        return null;
    }

    public static void bind(FileDescriptor fd, SocketAddress address) throws ErrnoException, SocketException {
    }

    public static void listen(FileDescriptor fd, int backlog) throws ErrnoException {
    }

    // API 26-28 signature (SocketAddress overload was added in API 29);
    // callers must pass (InetSocketAddress) null so the DEX links on all API levels.
    public static FileDescriptor accept(FileDescriptor fd, InetSocketAddress peerAddress) throws ErrnoException, SocketException {
        return null;
    }

    public static void connect(FileDescriptor fd, SocketAddress address) throws ErrnoException, SocketException {
    }

    public static int read(FileDescriptor fd, ByteBuffer buffer) throws ErrnoException, InterruptedIOException {
        return 0;
    }

    public static int read(FileDescriptor fd, byte[] bytes, int byteOffset, int byteCount) throws ErrnoException, InterruptedIOException {
        return 0;
    }

    public static int write(FileDescriptor fd, ByteBuffer buffer) throws ErrnoException, InterruptedIOException {
        return 0;
    }

    public static int write(FileDescriptor fd, byte[] bytes, int byteOffset, int byteCount) throws ErrnoException, InterruptedIOException {
        return 0;
    }

    public static void close(FileDescriptor fd) throws ErrnoException {
    }

    public static int getuid() {
        return 0;
    }

    public static int getpid() {
        return 0;
    }

    public static void setsockoptInt(FileDescriptor fd, int level, int option, int value) throws ErrnoException {
    }

    public static StructUcred getsockoptUcred(FileDescriptor fd, int level, int option) throws ErrnoException {
        return new StructUcred(0, 0, 0);
    }
}
