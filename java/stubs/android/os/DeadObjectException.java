package android.os;

/**
 * Compile-only stub for android.os.DeadObjectException.
 * Thrown when an IPC call fails because the remote process has died.
 */
public class DeadObjectException extends RemoteException {
    public DeadObjectException() {
        super();
    }

    public DeadObjectException(String message) {
        super(message);
    }
}
