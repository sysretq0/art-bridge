package dalvik.system;

/**
 * Compile-only stub for Android ART's VMRuntime.
 */
public final class VMRuntime {
    private static final VMRuntime THE_ONE = new VMRuntime();

    private VMRuntime() {}

    public static VMRuntime getRuntime() {
        return THE_ONE;
    }

    public void setHiddenApiExemptions(String[] signaturePrefixes) {
        // Stub implementation for compilation
    }
}
