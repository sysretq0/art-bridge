package bridge;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * EchoDispatcher bridges stdin/stdout to Dispatcher and CallbackRegistry
 * for live cross-process end-to-end integration testing between Rust and the JVM.
 *
 * Supports both RPC Responses (msg_type = 0x01) and Async Callback Events (msg_type = 0x02)
 * with thread-safe synchronized writes.
 */
public final class EchoDispatcher {
    private static final Object IO_LOCK = new Object();
    private static final ThreadLocal<ByteBuffer> CB_BUF = ThreadLocal.withInitial(
            () -> ByteBuffer.allocate(65536).order(ByteOrder.LITTLE_ENDIAN));

    public static void main(String[] args) throws Exception {
        InputStream in = System.in;
        OutputStream out = System.out;

        ByteBuffer rx = ByteBuffer.allocate(65536).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer tx = ByteBuffer.allocate(65536).order(ByteOrder.LITTLE_ENDIAN);

        // Register thread-safe callback event sender
        CallbackRegistry.setCallbackSender((callbackId, methodOrCode, callbackArgs) -> {
            ByteBuffer cbBuf = CB_BUF.get();
            cbBuf.clear();
            cbBuf.put(Dispatcher.MSG_TYPE_CALLBACK_EVENT); // 0x02
            cbBuf.putInt(callbackId);
            cbBuf.putInt(methodOrCode);
            cbBuf.putShort((short) (callbackArgs != null ? callbackArgs.length : 0));
            if (callbackArgs != null) {
                for (ArgValue a : callbackArgs) {
                    a.writeTo(cbBuf);
                }
            }
            cbBuf.flip();

            int len = cbBuf.remaining();
            byte[] header = new byte[4];
            header[0] = (byte) (len & 0xFF);
            header[1] = (byte) ((len >> 8) & 0xFF);
            header[2] = (byte) ((len >> 16) & 0xFF);
            header[3] = (byte) ((len >> 24) & 0xFF);

            synchronized (IO_LOCK) {
                out.write(header);
                out.write(cbBuf.array(), cbBuf.position(), len);
                out.flush();
            }
        });

        byte[] header = new byte[4];
        while (true) {
            int read = readFully(in, header);
            if (read < 4) {
                break;
            }
            int frameLen = (header[0] & 0xFF)
                    | ((header[1] & 0xFF) << 8)
                    | ((header[2] & 0xFF) << 16)
                    | ((header[3] & 0xFF) << 24);

            if (frameLen <= 0 || frameLen > 65536) {
                break;
            }

            byte[] frame = new byte[frameLen];
            if (readFully(in, frame) < frameLen) {
                break;
            }

            rx.clear();
            rx.put(frame);
            rx.flip();

            tx.clear();
            try {
                Dispatcher.dispatch(rx, tx);
            } catch (java.nio.BufferOverflowException | IllegalArgumentException e) {
                long rid = 0;
                try {
                    rid = rx.duplicate().order(ByteOrder.LITTLE_ENDIAN).getLong(0);
                } catch (Throwable ignored) {}
                tx.clear();
                Dispatcher.writeError(tx, rid, Dispatcher.STATUS_ERROR, "response too large");
            }
            tx.flip();

            int respLen = tx.remaining();
            byte[] respHeader = new byte[4];
            respHeader[0] = (byte) (respLen & 0xFF);
            respHeader[1] = (byte) ((respLen >> 8) & 0xFF);
            respHeader[2] = (byte) ((respLen >> 16) & 0xFF);
            respHeader[3] = (byte) ((respLen >> 24) & 0xFF);

            synchronized (IO_LOCK) {
                out.write(respHeader);
                out.write(tx.array(), tx.position(), respLen);
                out.flush();
            }
        }
    }

    private static int readFully(InputStream in, byte[] b) throws Exception {
        int total = 0;
        while (total < b.length) {
            int n = in.read(b, total, b.length - total);
            if (n < 0) {
                break;
            }
            total += n;
        }
        return total;
    }
}
