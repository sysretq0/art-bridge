package android.system;

/**
 * Compile-only stub for android.system.ErrnoException.
 */
public final class ErrnoException extends Exception {
    public final int errno;
    public final String functionName;

    public ErrnoException(String functionName, int errno) {
        super(functionName + " failed: errno " + errno);
        this.functionName = functionName;
        this.errno = errno;
    }

    public ErrnoException(String functionName, int errno, Throwable cause) {
        super(functionName + " failed: errno " + errno, cause);
        this.functionName = functionName;
        this.errno = errno;
    }
}
