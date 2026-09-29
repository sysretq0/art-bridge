package android.app;

import android.os.IBinder;
import android.os.IInterface;
import android.os.RemoteException;

/**
 * Compile-only stub for android.app.IActivityManager.
 */
public interface IActivityManager extends IInterface {
    void forceStopPackage(String packageName, int userId) throws RemoteException;
    void setProcessLimit(int max) throws RemoteException;

    abstract class Stub implements IActivityManager {
        public static IActivityManager asInterface(IBinder obj) {
            if (obj == null) {
                return null;
            }
            if (obj instanceof IActivityManager) {
                return (IActivityManager) obj;
            }
            return new Proxy(obj);
        }

        @Override
        public IBinder asBinder() {
            return (IBinder) this;
        }

        private static class Proxy implements IActivityManager {
            private final IBinder mRemote;

            Proxy(IBinder remote) {
                mRemote = remote;
            }

            @Override
            public IBinder asBinder() {
                return mRemote;
            }

            @Override
            public void forceStopPackage(String packageName, int userId) throws RemoteException {
            }

            @Override
            public void setProcessLimit(int max) throws RemoteException {
            }
        }
    }
}
