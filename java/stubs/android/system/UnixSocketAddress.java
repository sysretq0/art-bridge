package android.system;

import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * Compile-only stub for android.system.UnixSocketAddress (hidden in libcore).
 */
public final class UnixSocketAddress extends SocketAddress {
    private final byte[] sunPath;

    public UnixSocketAddress(byte[] sunPath) {
        this.sunPath = sunPath;
    }

    public static UnixSocketAddress createAbstract(String name) {
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        byte[] path = new byte[nameBytes.length + 1];
        path[0] = 0; // null byte marks abstract namespace in Linux AF_UNIX
        System.arraycopy(nameBytes, 0, path, 1, nameBytes.length);
        return new UnixSocketAddress(path);
    }

    public static UnixSocketAddress createFileSystem(String path) {
        byte[] bytes = path.getBytes(StandardCharsets.UTF_8);
        byte[] sunPath = new byte[bytes.length + 1];
        System.arraycopy(bytes, 0, sunPath, 0, bytes.length);
        return new UnixSocketAddress(sunPath);
    }

    public byte[] getSunPath() {
        return sunPath;
    }
}
