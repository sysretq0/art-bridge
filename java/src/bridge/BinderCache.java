package bridge;

import android.app.IActivityManager;
import android.os.IBinder;
import android.os.ServiceManager;

import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caches Android ServiceManager Binder references and typed proxies (e.g. IActivityManager)
 * so expensive Binder lookups and IPC descriptor resolutions happen only once.
 *
 * Automatically evicts stale or dead Binder proxies when isBinderAlive() returns false
 * or when a DeadObjectException is encountered.
 */
public final class BinderCache {
    private static final ConcurrentHashMap<String, IBinder> SERVICE_CACHE = new ConcurrentHashMap<>();
    private static volatile IActivityManager activityManagerCache = null;

    private BinderCache() {}

    /**
     * Retrieve and cache an IBinder handle for the specified service name.
     * Auto-evicts and re-queries ServiceManager if the cached handle has died.
     */
    public static IBinder getService(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }

        IBinder cached = SERVICE_CACHE.get(name);
        if (cached != null) {
            if (cached.isBinderAlive()) {
                return cached;
            }
            // Auto-evict stale dead binder
            invalidate(name);
        }

        IBinder fresh = ServiceManager.getService(name);
        if (fresh == null) {
            return null;
        }
        if (!fresh.isBinderAlive()) {
            SERVICE_CACHE.remove(name);
            return null;
        }
        SERVICE_CACHE.put(name, fresh);
        return fresh;
    }

    /**
     * Check if a service exists in ServiceManager without blocking indefinitely.
     */
    public static boolean checkService(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        IBinder b = getService(name);
        return b != null && b.isBinderAlive();
    }

    /**
     * Retrieve and cache the IActivityManager interface proxy.
     * Automatically invalidates dead proxies and re-fetches from ServiceManager once.
     */
    public static IActivityManager getActivityManager() {
        IActivityManager am = activityManagerCache;
        if (am != null && am.asBinder() != null && am.asBinder().isBinderAlive()) {
            return am;
        }

        synchronized (BinderCache.class) {
            if (activityManagerCache != null && activityManagerCache.asBinder() != null && activityManagerCache.asBinder().isBinderAlive()) {
                return activityManagerCache;
            }

            // Invalidate stale references
            invalidate("activity");

            IBinder binder = getService("activity");
            if (binder == null || !binder.isBinderAlive()) {
                return null;
            }

            IActivityManager resolved = resolveActivityManager(binder);
            activityManagerCache = resolved;
            return resolved;
        }
    }

    private static IActivityManager resolveActivityManager(IBinder binder) {
        IActivityManager resolved = null;
        // 1. Try standard Stub.asInterface
        try {
            resolved = IActivityManager.Stub.asInterface(binder);
        } catch (Throwable ignored) {}

        // 2. Try ActivityManagerNative.asInterface via reflection (older Android versions)
        if (resolved == null) {
            try {
                Class<?> amn = Class.forName("android.app.ActivityManagerNative");
                Method asInterface = amn.getMethod("asInterface", IBinder.class);
                resolved = (IActivityManager) asInterface.invoke(null, binder);
            } catch (Throwable ignored) {}
        }

        // 3. Try ActivityManager.getService() (Android 8.0+)
        if (resolved == null) {
            try {
                Class<?> amClass = Class.forName("android.app.ActivityManager");
                Method getService = amClass.getMethod("getService");
                resolved = (IActivityManager) getService.invoke(null);
            } catch (Throwable ignored) {}
        }

        return resolved;
    }

    /**
     * Invalidate a cached service and its corresponding proxy.
     */
    public static void invalidate(String name) {
        if (name == null) {
            return;
        }
        SERVICE_CACHE.remove(name);
        if ("activity".equals(name)) {
            synchronized (BinderCache.class) {
                activityManagerCache = null;
            }
        }
    }

    /**
     * Invalidate all cached Binder references (e.g. after a system server restart).
     */
    public static void clear() {
        SERVICE_CACHE.clear();
        synchronized (BinderCache.class) {
            activityManagerCache = null;
        }
    }
}
