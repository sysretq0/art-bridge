package bridge;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Retains live Java objects created or returned via the bridge so native
 * clients can reference them across calls without recompiling the DEX.
 *
 * Each stored object is identified by an {@code ObjectToken(u32)} (wire tag
 * {@code 0x07}) carrying the integer id returned here.
 */
public final class ObjectRegistry {
    private static final ConcurrentHashMap<Integer, Object> OBJECTS = new ConcurrentHashMap<>();
    private static final AtomicInteger NEXT_ID = new AtomicInteger(1);
    static final int MAX_OBJECTS = 4096;

    private ObjectRegistry() {}

    public static int put(Object obj) {
        if (obj == null) {
            throw new IllegalArgumentException("Cannot store null in ObjectRegistry");
        }
        if (OBJECTS.size() >= MAX_OBJECTS) {
            throw new IllegalStateException("ObjectRegistry full");
        }
        int id = NEXT_ID.getAndIncrement();
        // Avoid id 0 (reserved); wrap on overflow.
        if (id == 0) {
            id = NEXT_ID.getAndIncrement();
        }
        OBJECTS.put(id, obj);
        return id;
    }

    public static Object get(int id) {
        return OBJECTS.get(id);
    }

    public static Object remove(int id) {
        return OBJECTS.remove(id);
    }

    public static boolean contains(int id) {
        return OBJECTS.containsKey(id);
    }

    public static int size() {
        return OBJECTS.size();
    }

    public static void clear() {
        OBJECTS.clear();
    }
}
