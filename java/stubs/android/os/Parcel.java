package android.os;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Compile-only stub for android.os.Parcel.
 */
public final class Parcel {
    private final ByteArrayOutputStream mBuffer = new ByteArrayOutputStream();
    private byte[] mData = new byte[0];
    private int mPos = 0;

    private Parcel() {}

    public static Parcel obtain() {
        return new Parcel();
    }

    public void recycle() {
        mBuffer.reset();
        mData = new byte[0];
        mPos = 0;
    }

    public void writeInterfaceToken(String interfaceName) {
        writeString(interfaceName);
    }

    public void enforceInterface(String interfaceName) {}

    public void writeInt(int val) {
        mBuffer.write(val & 0xFF);
        mBuffer.write((val >> 8) & 0xFF);
        mBuffer.write((val >> 16) & 0xFF);
        mBuffer.write((val >> 24) & 0xFF);
    }

    public int readInt() {
        return 0;
    }

    public void writeLong(long val) {
        for (int i = 0; i < 8; i++) {
            mBuffer.write((int) ((val >> (8 * i)) & 0xFF));
        }
    }

    public long readLong() {
        return 0L;
    }

    public void writeString(String val) {
        if (val != null) {
            byte[] b = val.getBytes(StandardCharsets.UTF_8);
            writeInt(b.length);
            try {
                mBuffer.write(b);
            } catch (IOException ignored) {}
        } else {
            writeInt(-1);
        }
    }

    public String readString() {
        return "";
    }

    public void writeByteArray(byte[] b) {
        if (b != null) {
            writeInt(b.length);
            try {
                mBuffer.write(b);
            } catch (IOException ignored) {}
        } else {
            writeInt(-1);
        }
    }

    public void writeIntArray(int[] b) {
        if (b != null) {
            writeInt(b.length);
            for (int v : b) {
                writeInt(v);
            }
        } else {
            writeInt(-1);
        }
    }

    public void writeStringArray(String[] b) {
        if (b != null) {
            writeInt(b.length);
            for (String s : b) {
                writeString(s);
            }
        } else {
            writeInt(-1);
        }
    }

    public void writeNoException() {
        writeInt(0);
    }

    public byte[] createByteArray() {
        return marshall();
    }

    public byte[] marshall() {
        if (mData.length > 0) {
            return mData.clone();
        }
        return mBuffer.toByteArray();
    }

    public void unmarshall(byte[] data, int offset, int length) {
        if (data != null && length >= 0) {
            mData = new byte[length];
            System.arraycopy(data, offset, mData, 0, length);
        }
    }

    public void setDataPosition(int pos) {
        mPos = pos;
    }

    public int dataPosition() {
        return mPos;
    }

    public int dataSize() {
        return marshall().length;
    }
}
