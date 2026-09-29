package android.os;

/**
 * Minimal compile-only stub for android.os.Looper so hidden APIs that
 * instantiate a default Handler do not throw RuntimeException on the
 * host JVM. On a real device the framework implementation is used.
 */
public final class Looper {
    private static Looper sMainLooper;

    private Looper() {}

    public static void prepareMainLooper() {
        if (sMainLooper == null) {
            sMainLooper = new Looper();
        }
    }

    public static Looper getMainLooper() {
        return sMainLooper;
    }

    public static Looper myLooper() {
        return sMainLooper;
    }

    public static void loop() {
    }
}
