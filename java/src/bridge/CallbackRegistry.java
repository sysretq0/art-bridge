package bridge;

import android.os.Binder;
import android.os.Parcel;
import android.os.RemoteException;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.ConcurrentHashMap;

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
    private static volatile CallbackSender globalSender = null;

    private CallbackRegistry() {}

    public static void setCallbackSender(CallbackSender sender) {
        globalSender = sender;
    }

    public static Object getRegistered(int callbackId) {
        return REGISTRY.get(callbackId);
    }

    public static void unregister(int callbackId) {
        REGISTRY.remove(callbackId);
    }

    public static void clear() {
        REGISTRY.clear();
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

                // Deterministic method identifier: method name hash & 0x7FFFFFFF
                int methodId = method.getName().hashCode() & 0x7FFFFFFF;

                ArgValue[] args;
                if (methodArgs == null || methodArgs.length == 0) {
                    args = new ArgValue[0];
                } else {
                    args = new ArgValue[methodArgs.length];
                    for (int i = 0; i < methodArgs.length; i++) {
                        args[i] = ArgValue.fromJavaObject(methodArgs[i]);
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
                Class<?> returnType = method.getReturnType();
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
        };

        Object proxy = Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, handler);
        REGISTRY.put(callbackId, proxy);
        return proxy;
    }

    /**
     * Create and register a dynamic Binder stub that intercepts onTransact.
     */
    public static Binder registerBinderStub(int callbackId, String descriptor) {
        DynamicBinderStub stub = new DynamicBinderStub(callbackId, descriptor);
        REGISTRY.put(callbackId, stub);
        return stub;
    }

    /**
     * Dynamic Binder stub implementing onTransact for raw Binder callbacks.
     */
    public static class DynamicBinderStub extends Binder {
        private final int callbackId;

        public DynamicBinderStub(int callbackId, String descriptor) {
            super(descriptor);
            this.callbackId = callbackId;
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            ArgValue[] args;
            if (data != null) {
                byte[] raw = data.marshall();
                args = new ArgValue[]{ArgValue.ofBytes(raw)};
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
            return true;
        }
    }

    /**
     * Manually trigger a callback event (useful for test simulations).
     */
    public static void triggerCallback(int callbackId, int methodOrCode, ArgValue[] args) throws Exception {
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
