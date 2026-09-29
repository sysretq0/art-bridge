package bridge;

import android.os.DeadObjectException;
import android.os.IBinder;
import android.os.Parcel;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Universal reflection engine capable of executing any Android hidden API dynamically
 * without requiring pre-compiled stubs.
 */
public final class ReflectionEngine {
    private static final ConcurrentHashMap<String, Class<?>> CLASS_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Method> METHOD_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Field> FIELD_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Object> SERVICE_PROXY_CACHE = new ConcurrentHashMap<>();

    private ReflectionEngine() {}

    /**
     * Dynamically invokes a static method on any class.
     */
    public static ArgValue invokeStaticMethod(String className, String methodName, ArgValue[] args) throws Exception {
        Class<?> clazz = loadClass(className);
        Method method = resolveMethod(clazz, methodName, args, true);
        Object[] params = convertArguments(method.getParameterTypes(), args);
        method.setAccessible(true);
        Object result = method.invoke(null, params);
        return ArgValue.fromJavaObject(result);
    }

    /**
     * Dynamically resolves an Android service via ServiceManager, converts to its AIDL
     * interface proxy via <Interface>$Stub.asInterface(IBinder), and invokes the requested method.
     * Automatically invalidates dead proxies and retries once on DeadObjectException.
     */
    public static ArgValue invokeServiceMethod(String serviceName, String aidlInterfaceName, String methodName, ArgValue[] args) throws Exception {
        Object proxy = getServiceProxy(serviceName, aidlInterfaceName);
        if (proxy == null) {
            throw new IllegalStateException("Failed to resolve service proxy for: " + serviceName + " (" + aidlInterfaceName + ")");
        }

        Method method = resolveMethod(proxy.getClass(), methodName, args, false);
        Object[] params = convertArguments(method.getParameterTypes(), args);
        method.setAccessible(true);

        try {
            Object result = method.invoke(proxy, params);
            return wrapServiceResult(result);
        } catch (Throwable t) {
            if (isDeadObject(t)) {
                // Invalidate service cache & proxy cache, then retry once
                BinderCache.invalidate(serviceName);
                SERVICE_PROXY_CACHE.remove(serviceName + ":" + aidlInterfaceName);

                proxy = getServiceProxy(serviceName, aidlInterfaceName);
                if (proxy == null) {
                    throw new IllegalStateException("Failed to re-resolve service proxy after DeadObjectException", t);
                }
                method = resolveMethod(proxy.getClass(), methodName, args, false);
                method.setAccessible(true);
                Object result = method.invoke(proxy, params);
                return wrapServiceResult(result);
            }
            if (t instanceof Exception) {
                throw (Exception) t;
            }
            throw new RuntimeException(t);
        }
    }

    private static ArgValue wrapServiceResult(Object result) {
        if (result instanceof java.util.Collection) {
            java.util.Collection<?> col = (java.util.Collection<?>) result;
            boolean allStrings = true;
            for (Object item : col) {
                if (item != null && !(item instanceof CharSequence)) {
                    allStrings = false;
                    break;
                }
            }
            if (allStrings) {
                String[] arr = new String[col.size()];
                int idx = 0;
                for (Object item : col) {
                    arr[idx++] = item == null ? "" : String.valueOf(item);
                }
                return ArgValue.ofStrArray(arr);
            }
        }
        return ArgValue.fromJavaObject(result);
    }

    /**
     * Instantiates an arbitrary object via a matching constructor, stores it in
     * ObjectRegistry, and returns an ObjectToken.
     */
    public static ArgValue newInstance(String className, ArgValue[] args) throws Exception {
        Class<?> clazz = loadClass(className);
        Constructor<?> ctor = resolveConstructor(clazz, args);
        Object[] params = convertArguments(ctor.getParameterTypes(), args);
        ctor.setAccessible(true);
        Object result = ctor.newInstance(params);
        int id = ObjectRegistry.put(result);
        return ArgValue.ofObjectToken(id);
    }

    /**
     * Invokes a method on a live object held in ObjectRegistry.
     */
    public static ArgValue invokeInstanceMethod(int objectId, String methodName, ArgValue[] args) throws Exception {
        Object target = ObjectRegistry.get(objectId);
        if (target == null) {
            throw new IllegalStateException("ObjectToken " + objectId + " not found in ObjectRegistry");
        }
        Method method = resolveMethod(target.getClass(), methodName, args, false);
        Object[] params = convertArguments(method.getParameterTypes(), args);
        method.setAccessible(true);
        Object result = method.invoke(target, params);
        return ArgValue.fromJavaObject(result);
    }

    /**
     * Reads an instance or static field (including private/hidden via setAccessible).
     * Target is an ObjectToken for instance fields or a class-name Str for static fields.
     */
    public static ArgValue getField(ArgValue target, String fieldName) throws Exception {
        if (target.getTag() != ArgValue.TAG_OBJECT_TOKEN && target.getTag() != ArgValue.TAG_STR) {
            throw new IllegalArgumentException("GET_FIELD target must be ObjectToken or class-name Str");
        }
        if (target.getTag() == ArgValue.TAG_OBJECT_TOKEN) {
            Object obj = ObjectRegistry.get(target.asObjectToken());
            if (obj == null) {
                throw new IllegalStateException("ObjectToken " + target.asObjectToken() + " not found");
            }
            Field f = resolveField(obj.getClass(), fieldName);
            f.setAccessible(true);
            return ArgValue.fromJavaObject(f.get(obj));
        }
        // Static field: target carries the class name.
        String className = target.asString();
        Class<?> clazz = loadClass(className);
        Field f = resolveField(clazz, fieldName);
        f.setAccessible(true);
        return ArgValue.fromJavaObject(f.get(null));
    }

    /**
     * Writes an instance or static field (including private/hidden via setAccessible).
     */
    public static ArgValue setField(ArgValue target, String fieldName, ArgValue value) throws Exception {
        if (target.getTag() != ArgValue.TAG_OBJECT_TOKEN && target.getTag() != ArgValue.TAG_STR) {
            throw new IllegalArgumentException("SET_FIELD target must be ObjectToken or class-name Str");
        }
        if (target.getTag() == ArgValue.TAG_OBJECT_TOKEN) {
            Object obj = ObjectRegistry.get(target.asObjectToken());
            if (obj == null) {
                throw new IllegalStateException("ObjectToken " + target.asObjectToken() + " not found");
            }
            Field f = resolveField(obj.getClass(), fieldName);
            f.setAccessible(true);
            f.set(obj, convertSingleValue(f.getType(), value));
            return ArgValue.ofStr("OK");
        }
        String className = target.asString();
        Class<?> clazz = loadClass(className);
        Field f = resolveField(clazz, fieldName);
        f.setAccessible(true);
        f.set(null, convertSingleValue(f.getType(), value));
        return ArgValue.ofStr("OK");
    }

    /**
     * Performs a direct raw Binder transact call using Parcel marshalling.
     */
    public static ArgValue rawBinderTransact(String serviceName, int code, ArgValue[] args) throws Exception {
        try {
            return doRawBinderTransact(serviceName, code, args);
        } catch (Throwable t) {
            if (isDeadObject(t)) {
                BinderCache.invalidate(serviceName);
                return doRawBinderTransact(serviceName, code, args);
            }
            if (t instanceof Exception) {
                throw (Exception) t;
            }
            throw new RuntimeException(t);
        }
    }

    private static ArgValue doRawBinderTransact(String serviceName, int code, ArgValue[] args) throws Exception {
        IBinder binder = BinderCache.getService(serviceName);
        if (binder == null) {
            throw new IllegalStateException("Service not found in ServiceManager: " + serviceName);
        }

        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            // Write args to parcel
            for (ArgValue arg : args) {
                switch (arg.getTag()) {
                    case ArgValue.TAG_NULL:
                        data.writeInt(0);
                        break;
                    case ArgValue.TAG_INT:
                        data.writeInt(arg.asInt());
                        break;
                    case ArgValue.TAG_LONG:
                        data.writeLong(arg.asLong());
                        break;
                    case ArgValue.TAG_STR:
                        data.writeString(arg.asString());
                        break;
                    case ArgValue.TAG_BYTES:
                        data.writeByteArray(arg.asBytes());
                        break;
                    case ArgValue.TAG_BOOL:
                        data.writeInt(arg.asBool() ? 1 : 0);
                        break;
                    case ArgValue.TAG_INT_ARRAY:
                        data.writeIntArray(arg.asIntArray());
                        break;
                    case ArgValue.TAG_STR_ARRAY:
                        data.writeStringArray(arg.asStrArray());
                        break;
                    case ArgValue.TAG_OBJECT_TOKEN:
                    case ArgValue.TAG_CALLBACK_TOKEN: {
                        Object resolved = arg.getTag() == ArgValue.TAG_OBJECT_TOKEN
                                ? ObjectRegistry.get(arg.asObjectToken())
                                : CallbackRegistry.getRegistered(arg.asCallbackToken());
                        if (resolved instanceof IBinder) {
                            try {
                                data.getClass().getMethod("writeStrongBinder", IBinder.class)
                                        .invoke(data, (IBinder) resolved);
                            } catch (NoSuchMethodException e) {
                                throw new IllegalArgumentException("IBinder transact arg requires device Parcel");
                            }
                        } else {
                            throw new IllegalArgumentException("transact token arg does not resolve to IBinder");
                        }
                        break;
                    }
                    default:
                        throw new IllegalArgumentException("unsupported tag for transact: 0x"
                                + Integer.toHexString(arg.getTag() & 0xFF));
                }
            }

            boolean ok = binder.transact(code, data, reply, 0);
            if (!ok) {
                throw new IllegalStateException("Binder.transact returned false for code " + code);
            }

            byte[] replyBytes = reply.marshall();
            return ArgValue.ofBytes(replyBytes);
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private static Object getServiceProxy(String serviceName, String aidlInterfaceName) throws Exception {
        String cacheKey = serviceName + ":" + aidlInterfaceName;
        Object cached = SERVICE_PROXY_CACHE.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        IBinder binder = BinderCache.getService(serviceName);
        if (binder == null) {
            return null;
        }

        // Try <aidlInterfaceName>$Stub.asInterface(IBinder)
        Class<?> stubClass = loadClass(aidlInterfaceName + "$Stub");
        Method asInterface = stubClass.getMethod("asInterface", IBinder.class);
        asInterface.setAccessible(true);
        Object proxy = asInterface.invoke(null, binder);
        if (proxy != null) {
            SERVICE_PROXY_CACHE.put(cacheKey, proxy);
        }
        return proxy;
    }

    private static String methodCacheKey(Class<?> clazz, String methodName, ArgValue[] args) {
        StringBuilder sb = new StringBuilder();
        sb.append(clazz.getName()).append('#').append(methodName).append('(').append(args.length).append(")[");
        for (ArgValue a : args) {
            sb.append((int) (a.getTag() & 0xFF)).append(',');
        }
        sb.append(']');
        return sb.toString();
    }

    private static Method resolveMethod(Class<?> clazz, String methodName, ArgValue[] args, boolean isStatic) throws NoSuchMethodException {
        String cacheKey = methodCacheKey(clazz, methodName, args);
        Method cached = METHOD_CACHE.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        // First pass: best-scoring parameter match across public + declared.
        Method best = null;
        int bestScore = Integer.MAX_VALUE;
        for (Method m : clazz.getMethods()) {
            if (m.getName().equals(methodName) && m.getParameterCount() == args.length) {
                if (isStatic && !Modifier.isStatic(m.getModifiers())) {
                    continue;
                }
                int score = matchScore(m.getParameterTypes(), args);
                if (score < bestScore) {
                    bestScore = score;
                    best = m;
                }
            }
        }
        for (Method m : clazz.getDeclaredMethods()) {
            if (m.getName().equals(methodName) && m.getParameterCount() == args.length) {
                if (isStatic && !Modifier.isStatic(m.getModifiers())) {
                    continue;
                }
                int score = matchScore(m.getParameterTypes(), args);
                if (score < bestScore) {
                    bestScore = score;
                    best = m;
                }
            }
        }
        if (best != null) {
            putMethodCache(cacheKey, best);
            return best;
        }

        // Fallback: first arity-only match, uncached.
        for (Method m : clazz.getMethods()) {
            if (m.getName().equals(methodName) && m.getParameterCount() == args.length) {
                if (isStatic && !Modifier.isStatic(m.getModifiers())) {
                    continue;
                }
                return m;
            }
        }
        for (Method m : clazz.getDeclaredMethods()) {
            if (m.getName().equals(methodName) && m.getParameterCount() == args.length) {
                if (isStatic && !Modifier.isStatic(m.getModifiers())) {
                    continue;
                }
                return m;
            }
        }

        throw new NoSuchMethodException("No matching method " + methodName + " with " + args.length + " args on " + clazz.getName());
    }

    private static void putMethodCache(String key, Method m) {
        if (METHOD_CACHE.size() >= 512) {
            METHOD_CACHE.clear();
        }
        METHOD_CACHE.put(key, m);
    }

    private static void putFieldCache(String key, Field f) {
        if (FIELD_CACHE.size() >= 512) {
            FIELD_CACHE.clear();
        }
        FIELD_CACHE.put(key, f);
    }

    private static Constructor<?> resolveConstructor(Class<?> clazz, ArgValue[] args) throws NoSuchMethodException {
        Constructor<?> best = null;
        int bestScore = Integer.MAX_VALUE;
        for (Constructor<?> c : clazz.getConstructors()) {
            if (c.getParameterCount() == args.length) {
                int score = matchScore(c.getParameterTypes(), args);
                if (score < bestScore) {
                    bestScore = score;
                    best = c;
                }
            }
        }
        for (Constructor<?> c : clazz.getDeclaredConstructors()) {
            if (c.getParameterCount() == args.length) {
                int score = matchScore(c.getParameterTypes(), args);
                if (score < bestScore) {
                    bestScore = score;
                    best = c;
                }
            }
        }
        if (best != null) {
            return best;
        }
        // Fallback: first ctor with matching arity.
        for (Constructor<?> c : clazz.getConstructors()) {
            if (c.getParameterCount() == args.length) {
                return c;
            }
        }
        for (Constructor<?> c : clazz.getDeclaredConstructors()) {
            if (c.getParameterCount() == args.length) {
                return c;
            }
        }
        throw new NoSuchMethodException("No matching constructor with " + args.length + " args on " + clazz.getName());
    }

    private static Field resolveField(Class<?> clazz, String fieldName) throws NoSuchFieldException {
        String key = clazz.getName() + '#' + fieldName;
        Field cached = FIELD_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        Class<?> cur = clazz;
        while (cur != null) {
            try {
                Field f = cur.getDeclaredField(fieldName);
                putFieldCache(key, f);
                return f;
            } catch (NoSuchFieldException e) {
                cur = cur.getSuperclass();
            }
        }
        try {
            Field f = clazz.getField(fieldName);
            putFieldCache(key, f);
            return f;
        } catch (NoSuchFieldException e) {
            throw new NoSuchFieldException("Field " + fieldName + " not found on " + clazz.getName());
        }
    }

    /**
     * Overload score: exact type match scores 0, widening/boxing acceptance
     * (mirroring {@link #parametersMatch} rules) scores 1, no match scores
     * MAX_VALUE. The candidate with the lowest total wins, so e.g.
     * {@code String.valueOf(42)} resolves to {@code valueOf(int)}, not
     * {@code valueOf(char)}.
     */
    private static int matchScore(Class<?>[] paramTypes, ArgValue[] args) {
        int total = 0;
        for (int i = 0; i < paramTypes.length; i++) {
            int s = singleScore(paramTypes[i], args[i]);
            if (s == Integer.MAX_VALUE) {
                return Integer.MAX_VALUE;
            }
            total += s;
        }
        return total;
    }

    private static int singleScore(Class<?> pt, ArgValue arg) {
        if (arg.isNull()) {
            return pt.isPrimitive() ? Integer.MAX_VALUE : 0;
        }
        byte tag = arg.getTag();
        if (pt == int.class || pt == Integer.class) {
            return tag == ArgValue.TAG_INT ? 0 : Integer.MAX_VALUE;
        }
        if (pt == long.class || pt == Long.class) {
            if (tag == ArgValue.TAG_LONG) return 0;
            if (tag == ArgValue.TAG_INT) return 1;
            return Integer.MAX_VALUE;
        }
        if (pt == boolean.class || pt == Boolean.class) {
            if (tag == ArgValue.TAG_BOOL) return 0;
            if (tag == ArgValue.TAG_INT) return 1;
            return Integer.MAX_VALUE;
        }
        if (pt == String.class || pt == CharSequence.class) {
            return tag == ArgValue.TAG_STR ? 0 : Integer.MAX_VALUE;
        }
        if (pt == byte[].class) {
            return tag == ArgValue.TAG_BYTES ? 0 : Integer.MAX_VALUE;
        }
        if (pt == int[].class || pt == Integer[].class) {
            return tag == ArgValue.TAG_INT_ARRAY ? 0 : Integer.MAX_VALUE;
        }
        if (pt == String[].class) {
            return tag == ArgValue.TAG_STR_ARRAY ? 0 : Integer.MAX_VALUE;
        }
        if (pt == byte.class || pt == Byte.class
                || pt == short.class || pt == Short.class
                || pt == char.class || pt == Character.class) {
            if (tag == ArgValue.TAG_INT || tag == ArgValue.TAG_STR) return 1;
            return Integer.MAX_VALUE;
        }
        if (pt == float.class || pt == Float.class
                || pt == double.class || pt == Double.class) {
            if (tag == ArgValue.TAG_INT || tag == ArgValue.TAG_LONG || tag == ArgValue.TAG_STR) return 1;
            return Integer.MAX_VALUE;
        }
        // Reference type: tokens match anything; Str matches String-assignable
        // targets; primitives-in-boxes widen to Object/Number.
        if (tag == ArgValue.TAG_OBJECT_TOKEN || tag == ArgValue.TAG_CALLBACK_TOKEN) {
            return 0;
        }
        if (tag == ArgValue.TAG_STR && pt == Object.class) {
            return 1;
        }
        if (tag == ArgValue.TAG_STR && pt.isAssignableFrom(String.class)) {
            return 0;
        }
        if (tag == ArgValue.TAG_INT && (pt == Object.class || pt == Number.class)) {
            return 1;
        }
        if (tag == ArgValue.TAG_LONG && (pt == Object.class || pt == Number.class)) {
            return 1;
        }
        if (tag == ArgValue.TAG_BOOL && pt == Object.class) {
            return 1;
        }
        if (tag == ArgValue.TAG_BYTES && pt == Object.class) {
            return 1;
        }
        if (tag == ArgValue.TAG_INT_ARRAY && pt == Object.class) {
            return 1;
        }
        if (tag == ArgValue.TAG_STR_ARRAY && pt == Object.class) {
            return 1;
        }
        return Integer.MAX_VALUE;
    }

    private static boolean parametersMatch(Class<?>[] paramTypes, ArgValue[] args) {
        for (int i = 0; i < paramTypes.length; i++) {
            Class<?> pt = paramTypes[i];
            ArgValue arg = args[i];
            if (arg.isNull()) {
                if (pt.isPrimitive()) {
                    return false;
                }
                continue;
            }
            byte tag = arg.getTag();
            if (pt == int.class || pt == Integer.class) {
                if (tag != ArgValue.TAG_INT) return false;
            } else if (pt == long.class || pt == Long.class) {
                if (tag != ArgValue.TAG_LONG && tag != ArgValue.TAG_INT) return false;
            } else if (pt == boolean.class || pt == Boolean.class) {
                if (tag != ArgValue.TAG_BOOL && tag != ArgValue.TAG_INT) return false;
            } else if (pt == String.class || pt == CharSequence.class) {
                if (tag != ArgValue.TAG_STR) return false;
            } else if (pt == byte[].class) {
                if (tag != ArgValue.TAG_BYTES) return false;
            } else if (pt == int[].class || pt == Integer[].class) {
                if (tag != ArgValue.TAG_INT_ARRAY) return false;
            } else if (pt == String[].class) {
                if (tag != ArgValue.TAG_STR_ARRAY) return false;
            } else if (pt == byte.class || pt == Byte.class
                    || pt == short.class || pt == Short.class
                    || pt == char.class || pt == Character.class) {
                if (tag != ArgValue.TAG_INT && tag != ArgValue.TAG_STR) return false;
            } else if (pt == float.class || pt == Float.class
                    || pt == double.class || pt == Double.class) {
                if (tag != ArgValue.TAG_INT && tag != ArgValue.TAG_LONG && tag != ArgValue.TAG_STR) return false;
            } else {
                // Reference type: Null, ObjectToken, CallbackToken, or directly assignable value.
                if (tag == ArgValue.TAG_OBJECT_TOKEN || tag == ArgValue.TAG_CALLBACK_TOKEN) {
                    continue;
                }
                // Allow Str for enums / CharSequence-like targets; otherwise require token/null.
                if (tag == ArgValue.TAG_STR && (pt.isAssignableFrom(String.class) || pt == Object.class)) {
                    continue;
                }
                if (tag == ArgValue.TAG_INT && (pt == Object.class || pt == Number.class)) {
                    continue;
                }
                if (tag == ArgValue.TAG_LONG && (pt == Object.class || pt == Number.class)) {
                    continue;
                }
                if (tag == ArgValue.TAG_BOOL && pt == Object.class) {
                    continue;
                }
                if (tag == ArgValue.TAG_BYTES && pt == Object.class) {
                    continue;
                }
                if (tag == ArgValue.TAG_INT_ARRAY && pt == Object.class) {
                    continue;
                }
                if (tag == ArgValue.TAG_STR_ARRAY && pt == Object.class) {
                    continue;
                }
                // Any other pairing (e.g. Str for Context/Parcelable) is not a match;
                // let the resolver try the next overload instead of failing at invoke.
                return false;
            }
        }
        return true;
    }

    private static Object[] convertArguments(Class<?>[] paramTypes, ArgValue[] args) {
        Object[] params = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            params[i] = convertSingleValue(paramTypes[i], args[i]);
        }
        return params;
    }

    private static Object convertSingleValue(Class<?> pt, ArgValue arg) {
        if (arg.isNull()) {
            return null;
        }
        if (pt == int.class || pt == Integer.class) {
            return arg.asInt();
        }
        if (pt == long.class || pt == Long.class) {
            return arg.asLong();
        }
        if (pt == boolean.class || pt == Boolean.class) {
            return arg.asBool();
        }
        if (pt == String.class || pt == CharSequence.class) {
            return arg.asString();
        }
        if (pt == byte[].class) {
            return arg.asBytes();
        }
        if (pt == int[].class) {
            return arg.asIntArray();
        }
        if (pt == Integer[].class) {
            int[] a = arg.asIntArray();
            Integer[] boxed = new Integer[a.length];
            for (int i = 0; i < a.length; i++) {
                boxed[i] = a[i];
            }
            return boxed;
        }
        if (pt == String[].class) {
            return arg.asStrArray();
        }
        if (pt == byte.class || pt == Byte.class) {
            return (byte) arg.asInt();
        }
        if (pt == short.class || pt == Short.class) {
            return (short) arg.asInt();
        }
        if (pt == char.class || pt == Character.class) {
            String s = arg.asString();
            return s.isEmpty() ? (char) arg.asInt() : s.charAt(0);
        }
        if (pt == float.class || pt == Float.class) {
            if (arg.getTag() == ArgValue.TAG_STR) {
                return Float.parseFloat(arg.asString());
            }
            if (arg.getTag() == ArgValue.TAG_LONG) {
                return (float) arg.asLong();
            }
            return (float) arg.asInt();
        }
        if (pt == double.class || pt == Double.class) {
            if (arg.getTag() == ArgValue.TAG_STR) {
                return Double.parseDouble(arg.asString());
            }
            if (arg.getTag() == ArgValue.TAG_LONG) {
                return (double) arg.asLong();
            }
            return (double) arg.asInt();
        }
        if (arg.getTag() == ArgValue.TAG_CALLBACK_TOKEN) {
            return CallbackRegistry.getRegistered(arg.asCallbackToken());
        }
        if (arg.getTag() == ArgValue.TAG_OBJECT_TOKEN) {
            Object o = ObjectRegistry.get(arg.asObjectToken());
            if (o == null) {
                throw new IllegalArgumentException("ObjectToken " + arg.asObjectToken() + " not found in ObjectRegistry");
            }
            return o;
        }
        if (pt == Object.class) {
            return arg.toJavaObject();
        }
        // Fallback: pass through raw value (may fail with IllegalArgumentException at invoke).
        return arg.toJavaObject();
    }

    private static Class<?> loadClass(String name) throws ClassNotFoundException {
        Class<?> cached = CLASS_CACHE.get(name);
        if (cached != null) {
            return cached;
        }
        Class<?> loaded = Class.forName(name);
        CLASS_CACHE.put(name, loaded);
        return loaded;
    }

    static boolean isDeadObject(Throwable t) {
        Throwable curr = t;
        while (curr != null) {
            if (curr instanceof DeadObjectException) return true;
            String name = curr.getClass().getSimpleName();
            if ("DeadObjectException".equals(name) || "DeadSystemException".equals(name)) return true;
            if (curr.getMessage() != null && curr.getMessage().contains("DeadObjectException")) return true;
            curr = curr.getCause();
        }
        return false;
    }
}
