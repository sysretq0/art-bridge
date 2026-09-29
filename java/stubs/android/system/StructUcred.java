package android.system;

/**
 * Compile-only stub for android.system.StructUcred (used with SO_PEERCRED).
 */
public final class StructUcred {
    public final int pid;
    public final int uid;
    public final int gid;

    public StructUcred(int pid, int uid, int gid) {
        this.pid = pid;
        this.uid = uid;
        this.gid = gid;
    }

    @Override
    public String toString() {
        return "StructUcred[pid=" + pid + ", uid=" + uid + ", gid=" + gid + "]";
    }
}
