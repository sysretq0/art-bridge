package android.os;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Compile-only stub for android.os.ServiceManager.
 */
public final class ServiceManager {
    private static final Map<String, IBinder> sCache = new ConcurrentHashMap<>();

    static {
        // Register default mock binders for host JVM test environments
        sCache.put("activity", new MockBinder("android.app.IActivityManager"));
    }

    private ServiceManager() {}

    public static IBinder getService(String name) {
        if (name == null) {
            return null;
        }
        return sCache.get(name);
    }

    public static IBinder checkService(String name) {
        if (name == null) {
            return null;
        }
        return sCache.get(name);
    }

    public static void addService(String name, IBinder service) {
        if (name != null && service != null) {
            sCache.put(name, service);
        }
    }

    public static void addService(String name, IBinder service, boolean allowIsolated) {
        addService(name, service);
    }

    public static void addService(String name, IBinder service, boolean allowIsolated, int dumpPriority) {
        addService(name, service);
    }

    public static String[] listServices() {
        return sCache.keySet().toArray(new String[0]);
    }

    public static void initServiceCache(Map<String, IBinder> cache) {
        if (cache != null) {
            sCache.putAll(cache);
        }
    }

    private static class MockBinder implements IBinder {
        private final String descriptor;

        MockBinder(String descriptor) {
            this.descriptor = descriptor;
        }

        @Override
        public String getInterfaceDescriptor() {
            return descriptor;
        }

        @Override
        public boolean pingBinder() {
            return true;
        }

        @Override
        public boolean isBinderAlive() {
            return true;
        }

        @Override
        public IInterface queryLocalInterface(String descriptor) {
            return null;
        }

        @Override
        public boolean transact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (reply != null) {
                reply.writeInt(0);
            }
            return true;
        }

        @Override
        public void linkToDeath(DeathRecipient recipient, int flags) {}

        @Override
        public boolean unlinkToDeath(DeathRecipient recipient, int flags) {
            return true;
        }
    }
}
