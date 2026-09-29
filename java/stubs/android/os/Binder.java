package android.os;

/**
 * Compile-only stub for android.os.Binder.
 */
public class Binder implements IBinder {
    private String mDescriptor;

    public Binder() {
        this(null);
    }

    public Binder(String descriptor) {
        mDescriptor = descriptor;
    }

    public void attachInterface(IInterface owner, String descriptor) {
        mDescriptor = descriptor;
    }

    @Override
    public String getInterfaceDescriptor() {
        return mDescriptor;
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

    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        return false;
    }

    @Override
    public final boolean transact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        return onTransact(code, data, reply, flags);
    }

    @Override
    public void linkToDeath(DeathRecipient recipient, int flags) {}

    @Override
    public boolean unlinkToDeath(DeathRecipient recipient, int flags) {
        return true;
    }
}
