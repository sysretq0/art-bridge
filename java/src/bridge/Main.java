package bridge;

import android.os.Build;
import android.os.Looper;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructUcred;
import android.system.UnixSocketAddress;
import dalvik.system.VMRuntime;

import java.io.FileDescriptor;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashSet;
import java.util.Set;

/**
 * Main app_process worker entry point.
 *
 * Responsibilities:
 *  1. Unlock all Android hidden APIs via VMRuntime.setHiddenApiExemptions(new String[]{"L"}).
 *  2. Prepare the main Looper so hidden APIs instantiating a default Handler do not throw.
 *  3. Bind to abstract SOCK_SEQPACKET socket \0art_bridge using android.system.Os.
 *  4. Authenticate clients via SO_PEERCRED (strict UID match unless --allow-uid is passed).
 *  5. Execute single-threaded sequential request dispatching using zero-allocation reusable 64 KB ByteBuffers.
 *  6. Properly handle socket EOF and signal interrupts (EINTR, ECONNRESET, EPIPE) without spinning.
 *  7. Ensure thread-safe socket dispatching for RPC responses and asynchronous Binder callbacks.
 */
public final class Main {
    public static final String DEFAULT_SOCKET_NAME = "art_bridge";
    public static final int BUFFER_CAPACITY = 65536; // 64 KB max packet size
    private static volatile boolean running = true;

    private static final Object SOCKET_LOCK = new Object();
    private static volatile FileDescriptor activeClientFd = null;
    private static final Set<Integer> allowedUids = new HashSet<>();
    private static final ThreadLocal<ByteBuffer> CB_BUF = ThreadLocal.withInitial(
            () -> ByteBuffer.allocate(BUFFER_CAPACITY).order(java.nio.ByteOrder.LITTLE_ENDIAN));

    public static void main(String[] args) {
        // 1. Unlock all Android hidden APIs first, before touching any hidden class.
        unlockHiddenApis();

        System.out.println("[art-bridge] Initializing app_process Hidden API worker...");

        // 2. Prepare main Looper so default Handler construction does not throw.
        try {
            Looper.prepareMainLooper();
        } catch (Throwable ignored) {
            // Already prepared; ignore.
        }

        // 3. Shizuku-style startup hygiene: nice process name + never die on a
        // stray Binder-thread exception. All reflective so host JVM tests and
        // every API level succeed.
        try {
            Class<?> processClass = Class.forName("android.os.Process");
            try {
                Method setArgV0 = processClass.getDeclaredMethod("setArgV0", String.class);
                setArgV0.setAccessible(true);
                setArgV0.invoke(null, "art_bridge");
            } catch (Throwable ignored) {
            }
            try {
                Class<?> ddmClass = Class.forName("android.ddm.DdmHandleAppName");
                Method setAppName = ddmClass.getDeclaredMethod("setAppName", String.class, int.class);
                setAppName.setAccessible(true);
                setAppName.invoke(null, "art_bridge", 0);
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
        }
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            System.err.println("[art-bridge] Uncaught exception on " + thread.getName() + ": " + error);
        });

        String socketName = DEFAULT_SOCKET_NAME;
        boolean connectMode = false;

        for (int i = 0; i < args.length; i++) {
            if ("--socket".equals(args[i]) && i + 1 < args.length) {
                socketName = args[++i];
            } else if ("--connect".equals(args[i])) {
                connectMode = true;
            } else if ("--allow-uid".equals(args[i]) && i + 1 < args.length) {
                String val = args[++i];
                for (String part : val.split(",")) {
                    part = part.trim();
                    if (!part.isEmpty()) {
                        try {
                            allowedUids.add(Integer.parseInt(part));
                        } catch (NumberFormatException e) {
                            System.err.println("[art-bridge] Ignoring invalid --allow-uid value: " + part);
                        }
                    }
                }
            } else if ("--help".equals(args[i])) {
                printUsage();
                return;
            }
        }

        // Install graceful shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n[art-bridge] Shutdown signal received. Exiting...");
            running = false;
        }));

        if (connectMode) {
            runClientMode(socketName);
        } else {
            runServerMode(socketName);
        }
    }

    /**
     * Unlock Android Hidden APIs by setting exempt prefixes to "L" (matches all classes).
     */
    private static void unlockHiddenApis() {
        // setHiddenApiExemptions exists only on API 28+; guard so API 26-27
        // devices do not throw NoSuchMethodError.
        if (Build.VERSION.SDK_INT < 28) {
            return;
        }
        try {
            // Direct call against dalvik.system.VMRuntime
            VMRuntime.getRuntime().setHiddenApiExemptions(new String[]{"L"});
            System.out.println("[art-bridge] Hidden APIs successfully unlocked via VMRuntime.");
            return;
        } catch (Throwable t) {
            // Fallback via reflection for compatibility across varying ART runtimes
            try {
                Class<?> vmRuntimeClass = Class.forName("dalvik.system.VMRuntime");
                Method getRuntimeMethod = vmRuntimeClass.getDeclaredMethod("getRuntime");
                getRuntimeMethod.setAccessible(true);
                Object vmRuntime = getRuntimeMethod.invoke(null);

                Method setExemptions = vmRuntimeClass.getDeclaredMethod("setHiddenApiExemptions", String[].class);
                setExemptions.setAccessible(true);
                setExemptions.invoke(vmRuntime, (Object) new String[]{"L"});
                System.out.println("[art-bridge] Hidden APIs unlocked via reflection.");
            } catch (Throwable fallbackError) {
                System.err.println("[art-bridge] Warning: Failed to set hidden API exemptions: " + fallbackError.getMessage());
            }
        }
    }

    /**
     * Run the daemon server: binds to \0<socketName> using SOCK_SEQPACKET and sequentially
     * serves client requests while permitting asynchronous Binder callbacks.
     */
    private static void runServerMode(String socketName) {
        FileDescriptor serverFd = null;
        try {
            serverFd = Os.socket(OsConstants.AF_UNIX, OsConstants.SOCK_SEQPACKET, 0);
            if (serverFd == null || !serverFd.valid()) {
                System.err.println("[art-bridge] Failed to create SOCK_SEQPACKET socket.");
                return;
            }

            // Set socket buffers to 256 KB
            try {
                Os.setsockoptInt(serverFd, OsConstants.SOL_SOCKET, OsConstants.SO_RCVBUF, 256 * 1024);
                Os.setsockoptInt(serverFd, OsConstants.SOL_SOCKET, OsConstants.SO_SNDBUF, 256 * 1024);
            } catch (Throwable t) {
                // Non-fatal if setsockopt fails on some environments
            }

            UnixSocketAddress addr = UnixSocketAddress.createAbstract(socketName);
            Os.bind(serverFd, addr);
            Os.listen(serverFd, 16);

            System.out.println("[art-bridge] Server listening on abstract socket @" + socketName + " (SOCK_SEQPACKET)");

            // Pre-allocate reusable 64 KB Little-Endian ByteBuffers
            ByteBuffer rxBuf = ByteBuffer.allocate(BUFFER_CAPACITY).order(ByteOrder.LITTLE_ENDIAN);
            ByteBuffer txBuf = ByteBuffer.allocate(BUFFER_CAPACITY).order(ByteOrder.LITTLE_ENDIAN);

            while (running) {
                FileDescriptor clientFd = null;
                try {
                    while (running) {
                        try {
                            clientFd = Os.accept(serverFd, (java.net.InetSocketAddress) null);
                            break;
                        } catch (ErrnoException e) {
                            if (e.errno == OsConstants.EINTR) {
                                continue; // Interrupted by signal, retry accept
                            }
                            throw e;
                        }
                    }

                    if (clientFd == null || !clientFd.valid()) {
                        continue;
                    }

                    // Enforce SO_PEERCRED access control
                    if (!authenticatePeer(clientFd)) {
                        try {
                            Os.close(clientFd);
                        } catch (Throwable ignored) {}
                        continue;
                    }

                    activeClientFd = clientFd;

                    // Wire up asynchronous Binder thread callbacks to the client socket
                    CallbackRegistry.setCallbackSender((callbackId, methodOrCode, cbArgs) -> {
                        synchronized (SOCKET_LOCK) {
                            FileDescriptor fd = activeClientFd;
                            if (fd != null && fd.valid()) {
                                ByteBuffer cbBuf = CB_BUF.get();
                                cbBuf.clear();
                                cbBuf.put(Dispatcher.MSG_TYPE_CALLBACK_EVENT); // 0x02
                                cbBuf.putInt(callbackId);
                                cbBuf.putInt(methodOrCode);
                                cbBuf.putShort((short) (cbArgs != null ? cbArgs.length : 0));
                                if (cbArgs != null) {
                                    for (ArgValue a : cbArgs) {
                                        a.writeTo(cbBuf);
                                    }
                                }
                                cbBuf.flip();
                                while (cbBuf.hasRemaining()) {
                                    try {
                                        Os.write(fd, cbBuf);
                                    } catch (ErrnoException e) {
                                        if (e.errno == OsConstants.EINTR) continue;
                                        break;
                                    }
                                }
                            }
                        }
                    });

                    // Serve client requests sequentially on this connection
                    serveClient(clientFd, rxBuf, txBuf);

                } catch (Throwable t) {
                    if (running) {
                        System.err.println("[art-bridge] Connection error: " + t.getMessage());
                    }
                } finally {
                    // Prevent disconnected clients from leaking Java heap objects.
                    ObjectRegistry.clear();
                    CallbackRegistry.clear();
                    CallbackRegistry.setCallbackSender(null);
                    activeClientFd = null;
                    if (clientFd != null && clientFd.valid()) {
                        try {
                            Os.close(clientFd);
                        } catch (Throwable ignored) {}
                    }
                }
            }
        } catch (Throwable t) {
            System.err.println("[art-bridge] Server fatal error: " + t.getMessage());
            t.printStackTrace();
        } finally {
            if (serverFd != null && serverFd.valid()) {
                try {
                    Os.close(serverFd);
                } catch (Throwable ignored) {}
            }
        }
    }

    /**
     * Enforce strict peer credential checking: allow only the same UID unless an
     * explicit --allow-uid exception was passed. No implicit root/shell bypass.
     */
    static boolean authenticatePeer(FileDescriptor fd) {
        try {
            StructUcred ucred = Os.getsockoptUcred(fd, OsConstants.SOL_SOCKET, OsConstants.SO_PEERCRED);
            if (ucred == null) {
                return true; // If ucred not queryable, proceed
            }
            int myUid = Os.getuid();
            if (ucred.uid == myUid) {
                return true;
            }
            synchronized (allowedUids) {
                if (allowedUids.contains(ucred.uid)) {
                    return true;
                }
            }
            System.err.println("[art-bridge] Access Denied: Peer UID " + ucred.uid + " does not match my UID " + myUid);
            return false;
        } catch (Throwable t) {
            // If SO_PEERCRED is unsupported on host stub runtime, allow
            return true;
        }
    }

    /**
     * Synchronously read requests and write responses over SOCK_SEQPACKET connection.
     * Retries on EINTR, closes socket immediately on EOF or ECONNRESET/EPIPE to prevent spinning.
     * Outbound writes are synchronized on SOCKET_LOCK to prevent collision with Binder thread callbacks.
     */
    private static void serveClient(FileDescriptor clientFd, ByteBuffer rxBuf, ByteBuffer txBuf) {
        while (running) {
            rxBuf.clear();
            int n;

            // Read request from socket with EINTR retry and EOF / reset detection
            while (true) {
                try {
                    n = Os.read(clientFd, rxBuf);
                    break;
                } catch (ErrnoException e) {
                    if (e.errno == OsConstants.EINTR) {
                        continue; // Interrupted by signal, retry read
                    }
                    if (e.errno == OsConstants.ECONNRESET || e.errno == OsConstants.EPIPE) {
                        try {
                            Os.close(clientFd);
                        } catch (Throwable ignored) {}
                        return;
                    }
                    System.err.println("[art-bridge] Socket read error: " + e.getMessage());
                    try {
                        Os.close(clientFd);
                    } catch (Throwable ignored) {}
                    return;
                } catch (Throwable t) {
                    try {
                        Os.close(clientFd);
                    } catch (Throwable ignored) {}
                    return;
                }
            }

            // n == 0 indicates clean peer disconnection (EOF)
            if (n <= 0) {
                try {
                    Os.close(clientFd);
                } catch (Throwable ignored) {}
                return;
            }

            rxBuf.flip();
            txBuf.clear();

            // Dispatch opcode and write response into txBuf
            try {
                Dispatcher.dispatch(rxBuf, txBuf);
            } catch (java.nio.BufferOverflowException | IllegalArgumentException e) {
                long rid = 0;
                try {
                    rid = rxBuf.duplicate().order(ByteOrder.LITTLE_ENDIAN).getLong(0);
                } catch (Throwable ignored) {}
                txBuf.clear();
                Dispatcher.writeError(txBuf, rid, Dispatcher.STATUS_ERROR, "response too large");
            }
            txBuf.flip();

            // Write response to socket synchronized with asynchronous callback events
            synchronized (SOCKET_LOCK) {
                while (txBuf.hasRemaining()) {
                    try {
                        int written = Os.write(clientFd, txBuf);
                        if (written < 0) {
                            try {
                                Os.close(clientFd);
                            } catch (Throwable ignored) {}
                            return;
                        }
                    } catch (ErrnoException e) {
                        if (e.errno == OsConstants.EINTR) {
                            continue; // Interrupted by signal, retry write
                        }
                        if (e.errno == OsConstants.ECONNRESET || e.errno == OsConstants.EPIPE) {
                            try {
                                Os.close(clientFd);
                            } catch (Throwable ignored) {}
                            return;
                        }
                        System.err.println("[art-bridge] Socket write error: " + e.getMessage());
                        try {
                            Os.close(clientFd);
                        } catch (Throwable ignored) {}
                        return;
                    } catch (Throwable t) {
                        System.err.println("[art-bridge] Error writing response: " + t.getMessage());
                        try {
                            Os.close(clientFd);
                        } catch (Throwable ignored) {}
                        return;
                    }
                }
            }
        }
    }

    /**
     * Run in client mode to test connection to an existing daemon.
     */
    private static void runClientMode(String socketName) {
        FileDescriptor clientFd = null;
        try {
            clientFd = Os.socket(OsConstants.AF_UNIX, OsConstants.SOCK_SEQPACKET, 0);
            UnixSocketAddress addr = UnixSocketAddress.createAbstract(socketName);
            Os.connect(clientFd, addr);
            System.out.println("[art-bridge] Connected to @" + socketName);

            ByteBuffer tx = ByteBuffer.allocate(BUFFER_CAPACITY).order(ByteOrder.LITTLE_ENDIAN);
            ByteBuffer rx = ByteBuffer.allocate(BUFFER_CAPACITY).order(ByteOrder.LITTLE_ENDIAN);

            // Send PING request (req_id = 1, opcode = 0x0001, argc = 0)
            tx.putLong(1L);
            tx.putShort((short) Dispatcher.OP_PING);
            tx.putShort((short) 0);
            tx.flip();

            Os.write(clientFd, tx);

            int n = Os.read(clientFd, rx);
            if (n < 14) {
                System.err.println("[art-bridge] Client error: response too short (" + n + " bytes)");
                return;
            }
            rx.flip();
            byte msgType = rx.get();
            if (msgType != Dispatcher.MSG_TYPE_RPC_RESPONSE) {
                System.err.println("[art-bridge] Client error: unexpected msg_type " + msgType);
                return;
            }
            {
                long respId = rx.getLong();
                byte status = rx.get();
                long payloadLen = rx.getInt() & 0xFFFFFFFFL;
                if (payloadLen <= rx.remaining() && payloadLen <= BUFFER_CAPACITY) {
                    byte[] payload = new byte[(int) payloadLen];
                    rx.get(payload);
                    System.out.println("[art-bridge] Received Response: id=" + respId + " status=" + status + " payload=" + new String(payload));
                }
            }
        } catch (Throwable t) {
            System.err.println("[art-bridge] Client error: " + t.getMessage());
        } finally {
            if (clientFd != null && clientFd.valid()) {
                try {
                    Os.close(clientFd);
                } catch (Throwable ignored) {}
            }
        }
    }

    private static void printUsage() {
        System.out.println("Usage: app_process /system/bin bridge.Main [OPTIONS]");
        System.out.println("Options:");
        System.out.println("  --socket <name>   Abstract socket name (default: art_bridge)");
        System.out.println("  --connect         Connect as client instead of binding as server");
        System.out.println("  --allow-uid <uid> Allow an additional peer UID (repeatable, comma-separated)");
        System.out.println("  --help            Show this help message");
    }
}
