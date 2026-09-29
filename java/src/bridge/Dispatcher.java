package bridge;

import android.app.IActivityManager;
import android.os.Build;
import android.os.DeadObjectException;
import android.os.SystemProperties;
import android.system.Os;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Dispatcher executes low-overhead opcodes on behalf of the client:
 *  - 0x0001 (PING): Returns "PONG" + Android SDK version / UID.
 *  - 0x0002 (GET_SYSTEM_PROPERTY): Calls SystemProperties.get(key, def).
 *  - 0x0003 (FORCE_STOP_PACKAGE): Calls IActivityManager.forceStopPackage(pkg, userId).
 *  - 0x0004 (SET_PROCESS_LIMIT): Calls IActivityManager.setProcessLimit(max).
 *  - 0x0005 (CHECK_SERVICE): Checks if a Binder service exists in ServiceManager.
 *  - 0x0010 (INVOKE_SERVICE_METHOD): Universal reflection for AIDL service methods.
 *  - 0x0011 (INVOKE_STATIC_METHOD): Universal reflection for static hidden methods.
 *  - 0x0012 (RAW_BINDER_TRANSACT): Direct Binder transact call via Parcel.
 *  - 0x0013 (REGISTER_CALLBACK_PROXY): Dynamically synthesizes an interface proxy callback.
 *  - 0x0014 (REGISTER_BINDER_CALLBACK): Dynamically registers a Binder onTransact stub.
 *  - 0x0015 (UNREGISTER_CALLBACK): Releases a registered callback proxy or stub.
 *  - 0x0016 (NEW_INSTANCE): Instantiates an arbitrary object, returns ObjectToken.
 *  - 0x0017 (INVOKE_INSTANCE_METHOD): Invokes a method on a stored ObjectToken.
 *  - 0x0018 (GET_FIELD): Reads an instance or static field (2 args).
 *  - 0x001B (SET_FIELD): Writes an instance or static field (3 args).
 *  - 0x0019 (RELEASE_OBJECT): Removes an ObjectToken from ObjectRegistry.
 *  - 0x001A (TRIGGER_CALLBACK): Manually fires a registered callback (for test simulation).
 *  - 0x8000 (ECHO): Echoes back argument payload (tests unsigned u16 opcodes >= 0x8000 and payloads > 32KB).
 */
public final class Dispatcher {
    public static final byte MSG_TYPE_RPC_RESPONSE = 0x01;
    public static final byte MSG_TYPE_CALLBACK_EVENT = 0x02;

    public static final int OP_PING = 0x0001;
    public static final int OP_GET_SYSTEM_PROPERTY = 0x0002;
    public static final int OP_FORCE_STOP_PACKAGE = 0x0003;
    public static final int OP_SET_PROCESS_LIMIT = 0x0004;
    public static final int OP_CHECK_SERVICE = 0x0005;

    public static final int OP_INVOKE_SERVICE_METHOD = 0x0010;
    public static final int OP_INVOKE_STATIC_METHOD = 0x0011;
    public static final int OP_RAW_BINDER_TRANSACT = 0x0012;
    public static final int OP_REGISTER_CALLBACK_PROXY = 0x0013;
    public static final int OP_REGISTER_BINDER_CALLBACK = 0x0014;
    public static final int OP_UNREGISTER_CALLBACK = 0x0015;
    public static final int OP_NEW_INSTANCE = 0x0016;
    public static final int OP_INVOKE_INSTANCE_METHOD = 0x0017;
    public static final int OP_GET_FIELD = 0x0018;
    public static final int OP_SET_FIELD = 0x001B;
    public static final int OP_RELEASE_OBJECT = 0x0019;
    public static final int OP_TRIGGER_CALLBACK = 0x001A;

    public static final int OP_ECHO = 0x8000;

    public static final byte STATUS_OK = 0;
    public static final byte STATUS_ERROR = 1;
    public static final byte STATUS_UNKNOWN_OPCODE = 2;
    public static final byte STATUS_INVALID_ARGUMENTS = 3;

    private static final ArgValue OK_VALUE = ArgValue.ofStr("OK");

    private Dispatcher() {}

    /**
     * Parse binary request from rx buffer and encode binary response into tx buffer.
     * Both buffers MUST be configured with ByteOrder.LITTLE_ENDIAN.
     */
    public static void dispatch(ByteBuffer rx, ByteBuffer tx) {
        if (rx.remaining() < 12) {
            writeError(tx, 0L, STATUS_INVALID_ARGUMENTS, "Frame smaller than request header (12 bytes)");
            return;
        }

        long reqId = rx.getLong();
        // Unsigned 16-bit integer masking
        int opcode = rx.getShort() & 0xFFFF;
        int argc = rx.getShort() & 0xFFFF;

        // Validate against remaining frame bytes
        if (argc > rx.remaining()) {
            writeError(tx, reqId, STATUS_INVALID_ARGUMENTS, "Argument count " + argc + " exceeds frame capacity (remaining bytes: " + rx.remaining() + ")");
            return;
        }

        ArgValue[] args = new ArgValue[argc];
        for (int i = 0; i < argc; i++) {
            try {
                args[i] = ArgValue.readFrom(rx);
            } catch (Throwable t) {
                writeError(tx, reqId, STATUS_INVALID_ARGUMENTS, "Failed to decode argument at index " + i + ": " + t.getMessage());
                return;
            }
        }

        try {
            switch (opcode) {
                case OP_PING: {
                    int sdk = Build.VERSION.SDK_INT;
                    int uid = Os.getuid();
                    String reply = "PONG sdk=" + sdk + " uid=" + uid;
                    writeResponse(tx, reqId, STATUS_OK, ArgValue.ofStr(reply));
                    break;
                }
                case OP_GET_SYSTEM_PROPERTY: {
                    if (args.length < 1) {
                        writeError(tx, reqId, STATUS_INVALID_ARGUMENTS, "Missing property key argument");
                        return;
                    }
                    String key = args[0].asString();
                    String def = (args.length >= 2) ? args[1].asString() : "";
                    String val = SystemProperties.get(key, def);
                    writeResponse(tx, reqId, STATUS_OK, ArgValue.ofStr(val));
                    break;
                }
                case OP_FORCE_STOP_PACKAGE: {
                    if (args.length < 1) {
                        writeError(tx, reqId, STATUS_INVALID_ARGUMENTS, "Missing package name argument");
                        return;
                    }
                    String pkg = args[0].asString();
                    int userId = (args.length >= 2) ? args[1].asInt() : 0;
                    executeForceStopPackage(pkg, userId);
                    writeResponse(tx, reqId, STATUS_OK, OK_VALUE);
                    break;
                }
                case OP_SET_PROCESS_LIMIT: {
                    if (args.length < 1) {
                        writeError(tx, reqId, STATUS_INVALID_ARGUMENTS, "Missing process limit argument");
                        return;
                    }
                    int max = args[0].asInt();
                    executeSetProcessLimit(max);
                    writeResponse(tx, reqId, STATUS_OK, OK_VALUE);
                    break;
                }
                case OP_CHECK_SERVICE: {
                    if (args.length < 1) {
                        writeError(tx, reqId, STATUS_INVALID_ARGUMENTS, "Missing service name argument");
                        return;
                    }
                    String serviceName = args[0].asString();
                    boolean exists = BinderCache.checkService(serviceName);
                    String result = exists ? "EXISTS" : "NOT_FOUND";
                    writeResponse(tx, reqId, STATUS_OK, ArgValue.ofStr(result));
                    break;
                }
                case OP_INVOKE_STATIC_METHOD: {
                    if (args.length < 2) {
                        writeError(tx, reqId, STATUS_INVALID_ARGUMENTS, "Usage: INVOKE_STATIC_METHOD(className, methodName, args...)");
                        return;
                    }
                    String className = args[0].asString();
                    String methodName = args[1].asString();
                    ArgValue[] methodArgs = subArray(args, 2);
                    ArgValue result = ReflectionEngine.invokeStaticMethod(className, methodName, methodArgs);
                    writeResponse(tx, reqId, STATUS_OK, result);
                    break;
                }
                case OP_INVOKE_SERVICE_METHOD: {
                    if (args.length < 3) {
                        writeError(tx, reqId, STATUS_INVALID_ARGUMENTS, "Usage: INVOKE_SERVICE_METHOD(serviceName, aidlInterface, methodName, args...)");
                        return;
                    }
                    String serviceName = args[0].asString();
                    String aidlInterface = args[1].asString();
                    String methodName = args[2].asString();
                    ArgValue[] methodArgs = subArray(args, 3);
                    ArgValue result = ReflectionEngine.invokeServiceMethod(serviceName, aidlInterface, methodName, methodArgs);
                    writeResponse(tx, reqId, STATUS_OK, result);
                    break;
                }
                case OP_RAW_BINDER_TRANSACT: {
                    if (args.length < 2) {
                        writeError(tx, reqId, STATUS_INVALID_ARGUMENTS, "Usage: RAW_BINDER_TRANSACT(serviceName, code, args...)");
                        return;
                    }
                    String serviceName = args[0].asString();
                    int code = args[1].asInt();
                    ArgValue[] transactArgs = subArray(args, 2);
                    ArgValue result = ReflectionEngine.rawBinderTransact(serviceName, code, transactArgs);
                    writeResponse(tx, reqId, STATUS_OK, result);
                    break;
                }
                case OP_REGISTER_CALLBACK_PROXY: {
                    if (args.length < 2) {
                        writeError(tx, reqId, STATUS_INVALID_ARGUMENTS, "Usage: REGISTER_CALLBACK_PROXY(callbackId, interfaceClassName)");
                        return;
                    }
                    int callbackId = args[0].asInt();
                    String ifaceName = args[1].asString();
                    CallbackRegistry.registerInterfaceProxy(callbackId, ifaceName);
                    writeResponse(tx, reqId, STATUS_OK, ArgValue.ofCallbackToken(callbackId));
                    break;
                }
                case OP_REGISTER_BINDER_CALLBACK: {
                    if (args.length < 2) {
                        writeError(tx, reqId, STATUS_INVALID_ARGUMENTS, "Usage: REGISTER_BINDER_CALLBACK(callbackId, descriptor)");
                        return;
                    }
                    int callbackId = args[0].asInt();
                    String descriptor = args[1].asString();
                    CallbackRegistry.registerBinderStub(callbackId, descriptor);
                    writeResponse(tx, reqId, STATUS_OK, ArgValue.ofCallbackToken(callbackId));
                    break;
                }
                case OP_UNREGISTER_CALLBACK: {
                    if (args.length != 1) {
                        writeError(tx, reqId, STATUS_INVALID_ARGUMENTS, "Usage: UNREGISTER_CALLBACK(callbackId)");
                        return;
                    }
                    int callbackId = args[0].asInt();
                    CallbackRegistry.unregister(callbackId);
                    writeResponse(tx, reqId, STATUS_OK, OK_VALUE);
                    break;
                }
                case OP_NEW_INSTANCE: {
                    if (args.length < 1) {
                        writeError(tx, reqId, STATUS_INVALID_ARGUMENTS, "Usage: NEW_INSTANCE(className, args...)");
                        return;
                    }
                    String className = args[0].asString();
                    ArgValue[] ctorArgs = subArray(args, 1);
                    ArgValue result = ReflectionEngine.newInstance(className, ctorArgs);
                    writeResponse(tx, reqId, STATUS_OK, result);
                    break;
                }
                case OP_INVOKE_INSTANCE_METHOD: {
                    if (args.length < 2) {
                        writeError(tx, reqId, STATUS_INVALID_ARGUMENTS, "Usage: INVOKE_INSTANCE_METHOD(objectToken, methodName, args...)");
                        return;
                    }
                    int objectId = args[0].asObjectToken();
                    String methodName = args[1].asString();
                    ArgValue[] methodArgs = subArray(args, 2);
                    ArgValue result = ReflectionEngine.invokeInstanceMethod(objectId, methodName, methodArgs);
                    writeResponse(tx, reqId, STATUS_OK, result);
                    break;
                }
                case OP_GET_FIELD: {
                    if (args.length != 2) {
                        writeError(tx, reqId, STATUS_INVALID_ARGUMENTS, "Usage: GET_FIELD(target, field)");
                        return;
                    }
                    ArgValue result = ReflectionEngine.getField(args[0], args[1].asString());
                    writeResponse(tx, reqId, STATUS_OK, result);
                    break;
                }
                case OP_SET_FIELD: {
                    if (args.length != 3) {
                        writeError(tx, reqId, STATUS_INVALID_ARGUMENTS, "Usage: SET_FIELD(target, field, value)");
                        return;
                    }
                    ArgValue result = ReflectionEngine.setField(args[0], args[1].asString(), args[2]);
                    writeResponse(tx, reqId, STATUS_OK, result);
                    break;
                }
                case OP_RELEASE_OBJECT: {
                    if (args.length < 1) {
                        writeError(tx, reqId, STATUS_INVALID_ARGUMENTS, "Usage: RELEASE_OBJECT(objectToken)");
                        return;
                    }
                    int objectId = args[0].asObjectToken();
                    ObjectRegistry.remove(objectId);
                    writeResponse(tx, reqId, STATUS_OK, OK_VALUE);
                    break;
                }
                case OP_TRIGGER_CALLBACK: {
                    if (args.length < 2) {
                        writeError(tx, reqId, STATUS_INVALID_ARGUMENTS, "Usage: TRIGGER_CALLBACK(callbackId, methodOrCode, args...)");
                        return;
                    }
                    int callbackId = args[0].asInt();
                    int methodOrCode = args[1].asInt();
                    ArgValue[] cbArgs = subArray(args, 2);
                    CallbackRegistry.triggerCallback(callbackId, methodOrCode, cbArgs);
                    writeResponse(tx, reqId, STATUS_OK, OK_VALUE);
                    break;
                }
                case OP_ECHO: {
                    ArgValue payload = (args.length > 0) ? args[0] : ArgValue.ofNull();
                    writeResponse(tx, reqId, STATUS_OK, payload);
                    break;
                }
                default: {
                    String msg = "Unknown opcode: 0x" + Integer.toHexString(opcode);
                    writeError(tx, reqId, STATUS_UNKNOWN_OPCODE, msg);
                    break;
                }
            }
        } catch (Throwable t) {
            tx.clear();
            Throwable cause = (t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null) ? t.getCause() : t;
            String err = (cause.getMessage() != null) ? (cause.getClass().getSimpleName() + ": " + cause.getMessage()) : cause.toString();
            writeError(tx, reqId, STATUS_ERROR, err);
        }
    }

    private static ArgValue[] subArray(ArgValue[] src, int start) {
        if (start >= src.length) {
            return new ArgValue[0];
        }
        ArgValue[] dst = new ArgValue[src.length - start];
        System.arraycopy(src, start, dst, 0, dst.length);
        return dst;
    }

    private static void executeForceStopPackage(String pkg, int userId) throws Exception {
        IActivityManager am = BinderCache.getActivityManager();
        if (am == null) {
            throw new IllegalStateException("Failed to resolve IActivityManager proxy from ServiceManager");
        }

        try {
            am.forceStopPackage(pkg, userId);
        } catch (Throwable t) {
            if (isDeadObject(t)) {
                BinderCache.invalidate("activity");
                am = BinderCache.getActivityManager();
                if (am == null) {
                    throw new IllegalStateException("Failed to re-resolve IActivityManager after DeadObjectException", t);
                }
                am.forceStopPackage(pkg, userId);
            } else {
                throw t;
            }
        }
    }

    private static void executeSetProcessLimit(int max) throws Exception {
        IActivityManager am = BinderCache.getActivityManager();
        if (am == null) {
            throw new IllegalStateException("Failed to resolve IActivityManager proxy from ServiceManager");
        }

        try {
            am.setProcessLimit(max);
        } catch (Throwable t) {
            if (isDeadObject(t)) {
                BinderCache.invalidate("activity");
                am = BinderCache.getActivityManager();
                if (am == null) {
                    throw new IllegalStateException("Failed to re-resolve IActivityManager after DeadObjectException", t);
                }
                am.setProcessLimit(max);
            } else {
                throw t;
            }
        }
    }

    private static boolean isDeadObject(Throwable t) {
        return ReflectionEngine.isDeadObject(t);
    }

    /**
     * Serializes an RPC Response (msg_type = 0x01) with an ArgValue payload.
     * [msg_type: u8 = 0x01][req_id: u64][status: u8][payload_len: u32][payload_bytes]
     */
    public static void writeResponse(ByteBuffer tx, long reqId, byte status, ArgValue val) {
        tx.put(MSG_TYPE_RPC_RESPONSE);
        tx.putLong(reqId);
        tx.put(status);
        if (val != null) {
            int len = val.encodedLen();
            tx.putInt(len);
            val.writeTo(tx);
        } else {
            tx.putInt(0);
        }
    }

    /**
     * Serializes an RPC Error Response (msg_type = 0x01).
     */
    public static void writeError(ByteBuffer tx, long reqId, byte status, String message) {
        tx.put(MSG_TYPE_RPC_RESPONSE);
        tx.putLong(reqId);
        tx.put(status);
        byte[] msgBytes = (message != null ? message : "Unknown error").getBytes(StandardCharsets.UTF_8);
        tx.putInt(msgBytes.length);
        tx.put(msgBytes);
    }
}
