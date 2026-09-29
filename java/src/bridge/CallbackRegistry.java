package bridge;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages bidirectional callbacks and dynamic Binder / Proxy synthesis.
 *
 * When an Android service invokes a callback interface method or sends a Binder transaction,
 * CallbackRegistry serializes the event into an Async Callback Event (msg_type = 0x02):
 *   [msg_type: u8 = 0x02][callback_id: u32][method_or_code: u32][argc: u16][ArgValue...]
 */
public final class CallbackRegistry {
    public interface CallbackSender {
        void sendCallbackEvent(int callbackId, int methodOrCode, ArgValue[] args) throws Exception;
    }

    private static final ConcurrentHashMap<Integer, Object> REGISTRY = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer, AtomicBoolean> ACTIVE = new ConcurrentHashMap<>();
    private static volatile CallbackSender globalSender = null;

    private CallbackRegistry() {}

    public static void setCallbackSender(CallbackSender sender) {
        globalSender = sender;
    }

    public static Object getRegistered(int callbackId) {
        return REGISTRY.get(callbackId);
    }

    public static void unregister(int callbackId) {
        AtomicBoolean flag = ACTIVE.remove(callbackId);
        if (flag != null) {
            flag.set(false);
        }
        REGISTRY.remove(callbackId);
    }

    public static void clear() {
        for (AtomicBoolean flag : ACTIVE.values()) {
            flag.set(false);
        }
        ACTIVE.clear();
        REGISTRY.clear();
    }

    private static void markActive(int callbackId) {
        AtomicBoolean existing = ACTIVE.get(callbackId);
        if (existing != null && existing.get()) {
            throw new IllegalStateException("callbackId already registered: " + callbackId);
        }
        ACTIVE.put(callbackId, new AtomicBoolean(true));
    }

    private static boolean isActive(int callbackId) {
        AtomicBoolean flag = ACTIVE.get(callbackId);
        return flag != null && flag.get();
    }

    private static ArgValue encodeCallbackArg(Object obj) {
        if (obj == null) {
            return ArgValue.ofNull();
        }
        if (obj instanceof Integer) {
            return ArgValue.ofInt((Integer) obj);
        }
        if (obj instanceof Long) {
            return ArgValue.ofLong((Long) obj);
        }
        if (obj instanceof Boolean) {
            return ArgValue.ofBool((Boolean) obj);
        }
        if (obj instanceof String) {
            return ArgValue.ofStr((String) obj);
        }
        if (obj instanceof byte[]) {
            return ArgValue.ofBytes((byte[]) obj);
        }
        if (obj instanceof int[]) {
            return ArgValue.ofIntArray((int[]) obj);
        }
        if (obj instanceof String[]) {
            return ArgValue.ofStrArray((String[]) obj);
        }
        // Any other object becomes its String form; never mint ObjectRegistry
        // tokens the client never requested.
        return ArgValue.ofStr(String.valueOf(obj));
    }

    private static Object defaultValue(Class<?> returnType) {
        if (returnType == void.class) return null;
        if (returnType == boolean.class) return false;
        if (returnType == int.class) return 0;
        if (returnType == long.class) return 0L;
        if (returnType == byte.class) return (byte) 0;
        if (returnType == short.class) return (short) 0;
        if (returnType == char.class) return (char) 0;
        if (returnType == float.class) return 0.0f;
        if (returnType == double.class) return 0.0;
        return null;
    }

    /**
     * Create and register a dynamic java.lang.reflect.Proxy for any interface.
     *
     * <p>The proxy is backed by a live {@link DynamicBinderStub}: AIDL
     * {@code Stub$Proxy} implementations call {@code ((IInterface) callback).asBinder()}
     * to marshal the callback into a {@code Parcel}, and {@code system_server} later
     * invokes {@code Binder.onTransact} on that {@code IBinder}. The stub's
     * {@code onTransact} emits the {@code 0x02} callback frame.
     */
    public static Object registerInterfaceProxy(int callbackId, String interfaceClassName) throws Exception {
        Class<?> iface = Class.forName(interfaceClassName);
        if (!iface.isInterface()) {
            throw new IllegalArgumentException(interfaceClassName + " is not an interface");
        }
        markActive(callbackId);

        // Backing binder delivered to system_server via asBinder().
        final DynamicBinderStub backingStub = new DynamicBinderStub(callbackId, interfaceClassName);

        InvocationHandler handler = new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] methodArgs) throws Throwable {
                String name = method.getName();
                // asBinder() is how AIDL marshals typed callbacks; return the live Binder
                // without emitting a spurious 0x02 frame.
                if ("asBinder".equals(name) && method.getParameterCount() == 0) {
                    return backingStub;
                }
                // Ignore standard Object methods like toString, hashCode, equals
                if (method.getDeclaringClass() == Object.class) {
                    if ("toString".equals(name)) {
                        return "ArtBridgeProxy$" + iface.getSimpleName() + "[id=" + callbackId + "]";
                    }
                    if ("hashCode".equals(name)) {
                        return callbackId;
                    }
                    if ("equals".equals(name)) {
                        return proxy == methodArgs[0];
                    }
                }

                // Stale proxies (after unregister/clear) must no-op.
                if (!isActive(callbackId)) {
                    return defaultValue(method.getReturnType());
                }

                // Deterministic method identifier: method name hash & 0x7FFFFFFF
                int methodId = method.getName().hashCode() & 0x7FFFFFFF;

                ArgValue[] args;
                if (methodArgs == null || methodArgs.length == 0) {
                    args = new ArgValue[0];
                } else {
                    args = new ArgValue[methodArgs.length];
                    for (int i = 0; i < methodArgs.length; i++) {
                        args[i] = encodeCallbackArg(methodArgs[i]);
                    }
                }

                CallbackSender sender = globalSender;
                if (sender != null) {
                    try {
                        sender.sendCallbackEvent(callbackId, methodId, args);
                    } catch (Throwable t) {
                        System.err.println("[art-bridge] Failed to dispatch callback event: " + t.getMessage());
                    }
                }

                // Return default primitive/null value
                return defaultValue(method.getReturnType());
            }
        };

        Object proxy = Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, handler);
        REGISTRY.put(callbackId, proxy);
        return proxy;
    }

    /**
     * Create and register a dynamic Binder stub that intercepts onTransact.
     */
    public static Binder registerBinderStub(int callbackId, String descriptor) {
        markActive(callbackId);
        DynamicBinderStub stub = new DynamicBinderStub(callbackId, descriptor);
        REGISTRY.put(callbackId, stub);
        return stub;
    }

    /**
     * Dynamic Binder stub implementing onTransact for raw Binder callbacks.
     */
    public static class DynamicBinderStub extends Binder {
        // Standard Android Binder contract code for querying the interface descriptor.
        private static final int INTERFACE_TRANSACTION = 0x5f4e5446;

        private final int callbackId;

        public DynamicBinderStub(int callbackId, String descriptor) {
            super(descriptor);
            this.callbackId = callbackId;
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            // dumpsys / system_server expect the descriptor here.
            if (code == INTERFACE_TRANSACTION) {
                if (reply != null) {
                    reply.writeString(getInterfaceDescriptor());
                }
                return true;
            }

            ArgValue[] args;
            if (data != null) {
                byte[] rawBytes;
                try {
                    rawBytes = data.marshall();
                } catch (RuntimeException e) {
                    // Parcels holding Binders/FDs may fail to marshall; never throw.
                    rawBytes = new byte[0];
                }
                args = new ArgValue[]{ArgValue.ofBytes(rawBytes)};
            } else {
                args = new ArgValue[0];
            }

            CallbackSender sender = globalSender;
            if (sender != null) {
                try {
                    sender.sendCallbackEvent(callbackId, code, args);
                } catch (Throwable t) {
                    System.err.println("[art-bridge] Failed to dispatch Binder onTransact callback: " + t.getMessage());
                }
            }
            // Two-way calls must carry the no-exception header or the caller blocks/fails.
            if (reply != null && (flags & IBinder.FLAG_ONEWAY) == 0) {
                reply.writeNoException();
            }
            return true;
        }
    }

    /**
     * Manually trigger a callback event (useful for test simulations).
     */    public static void triggerCallback(int callbackId, int methodOrCode, ArgValue[] args) throws Exception {
        if (!REGISTRY.containsKey(callbackId)) {
            throw new IllegalStateException("Callback " + callbackId + " not registered");
        }
        CallbackSender sender = globalSender;
        if (sender != null) {
            sender.sendCallbackEvent(callbackId, methodOrCode, args);
        } else {
            throw new IllegalStateException("No active CallbackSender registered");
        }
    }
}
