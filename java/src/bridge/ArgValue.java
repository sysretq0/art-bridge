package bridge;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * Compact tagged argument value matching the Rust ArgValue wire specification:
 *  - 0x00: Null
 *  - 0x01: Int (i32)
 *  - 0x02: Long (i64)
 *  - 0x03: Bool (bool)
 *  - 0x04: Str (String)
 *  - 0x05: Bytes (byte[])
 *  - 0x06: CallbackToken (u32)
 *  - 0x07: ObjectToken (u32, live Java Object in ObjectRegistry)
 *  - 0x08: IntArray (int[])
 *  - 0x09: StrArray (String[])
 */
public final class ArgValue {
    public static final byte TAG_NULL = 0x00;
    public static final byte TAG_INT = 0x01;
    public static final byte TAG_LONG = 0x02;
    public static final byte TAG_BOOL = 0x03;
    public static final byte TAG_STR = 0x04;
    public static final byte TAG_BYTES = 0x05;
    public static final byte TAG_CALLBACK_TOKEN = 0x06;
    public static final byte TAG_OBJECT_TOKEN = 0x07;
    public static final byte TAG_INT_ARRAY = 0x08;
    public static final byte TAG_STR_ARRAY = 0x09;

    private final byte tag;
    private final Object value;

    private ArgValue(byte tag, Object value) {
        this.tag = tag;
        this.value = value;
    }

    public static ArgValue ofNull() {
        return new ArgValue(TAG_NULL, null);
    }

    public static ArgValue ofInt(int val) {
        return new ArgValue(TAG_INT, val);
    }

    public static ArgValue ofLong(long val) {
        return new ArgValue(TAG_LONG, val);
    }

    public static ArgValue ofBool(boolean val) {
        return new ArgValue(TAG_BOOL, val);
    }

    public static ArgValue ofStr(String val) {
        return new ArgValue(TAG_STR, val != null ? val : "");
    }

    public static ArgValue ofBytes(byte[] val) {
        return new ArgValue(TAG_BYTES, val != null ? val : new byte[0]);
    }

    public static ArgValue ofCallbackToken(int token) {
        return new ArgValue(TAG_CALLBACK_TOKEN, token);
    }

    public static ArgValue ofObjectToken(int token) {
        return new ArgValue(TAG_OBJECT_TOKEN, token);
    }

    public static ArgValue ofIntArray(int[] val) {
        return new ArgValue(TAG_INT_ARRAY, val != null ? val.clone() : new int[0]);
    }

    public static ArgValue ofStrArray(String[] val) {
        return new ArgValue(TAG_STR_ARRAY, val != null ? val.clone() : new String[0]);
    }

    public byte getTag() {
        return tag;
    }

    public Object getValue() {
        return value;
    }

    public boolean isNull() {
        return tag == TAG_NULL;
    }

    public int asInt() {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof Boolean) {
            return ((Boolean) value) ? 1 : 0;
        }
        if (value instanceof String) {
            return Integer.parseInt((String) value);
        }
        return 0;
    }

    public long asLong() {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String) {
            return Long.parseLong((String) value);
        }
        return 0L;
    }

    public boolean asBool() {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue() != 0;
        }
        if (value instanceof String) {
            return Boolean.parseBoolean((String) value);
        }
        return false;
    }

    public String asString() {
        return value != null ? value.toString() : "";
    }

    public byte[] asBytes() {
        if (value instanceof byte[]) {
            return (byte[]) value;
        }
        if (value instanceof String) {
            return ((String) value).getBytes(StandardCharsets.UTF_8);
        }
        return new byte[0];
    }

    public int asCallbackToken() {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return 0;
    }

    public int asObjectToken() {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return 0;
    }

    public int[] asIntArray() {
        if (value instanceof int[]) {
            return ((int[]) value).clone();
        }
        return new int[0];
    }

    public String[] asStrArray() {
        if (value instanceof String[]) {
            return ((String[]) value).clone();
        }
        return new String[0];
    }

    int[] intArrayRef() {
        return (value instanceof int[]) ? (int[]) value : new int[0];
    }

    String[] strArrayRef() {
        return (value instanceof String[]) ? (String[]) value : new String[0];
    }

    public Object toJavaObject() {
        return value;
    }

    public static ArgValue fromJavaObject(Object obj) {
        if (obj == null) {
            return ofNull();
        }
        if (obj instanceof Integer) {
            return ofInt((Integer) obj);
        }
        if (obj instanceof Long) {
            return ofLong((Long) obj);
        }
        if (obj instanceof Boolean) {
            return ofBool((Boolean) obj);
        }
        if (obj instanceof String) {
            return ofStr((String) obj);
        }
        if (obj instanceof byte[]) {
            return ofBytes((byte[]) obj);
        }
        if (obj instanceof int[]) {
            return ofIntArray((int[]) obj);
        }
        if (obj instanceof String[]) {
            return ofStrArray((String[]) obj);
        }
        if (obj instanceof Byte) {
            return ofInt(((Byte) obj).intValue());
        }
        if (obj instanceof Short) {
            return ofInt(((Short) obj).intValue());
        }
        if (obj instanceof Character) {
            return ofStr(String.valueOf(obj));
        }
        // Any other non-null object (IBinder, Parcelable, List, ComponentName, ...)
        // is retained in the registry and referenced by token.
        int id = ObjectRegistry.put(obj);
        return ofObjectToken(id);
    }

    /**
     * Decode an ArgValue from the Little-Endian ByteBuffer with strict bounds checking.
     */
    public static ArgValue readFrom(ByteBuffer buf) {
        if (buf.remaining() < 1) {
            throw new IllegalArgumentException("Truncated buffer: cannot read ArgValue tag byte");
        }
        byte tag = buf.get();
        switch (tag) {
            case TAG_NULL:
                return ofNull();

            case TAG_INT:
                if (buf.remaining() < 4) {
                    throw new IllegalArgumentException("Truncated buffer: expected 4 bytes for Int");
                }
                return ofInt(buf.getInt());

            case TAG_LONG:
                if (buf.remaining() < 8) {
                    throw new IllegalArgumentException("Truncated buffer: expected 8 bytes for Long");
                }
                return ofLong(buf.getLong());

            case TAG_BOOL:
                if (buf.remaining() < 1) {
                    throw new IllegalArgumentException("Truncated buffer: expected 1 byte for Bool");
                }
                return ofBool(buf.get() != 0);

            case TAG_STR: {
                if (buf.remaining() < 2) {
                    throw new IllegalArgumentException("Truncated buffer: expected 2 bytes for Str len");
                }
                // Mask u16 with 0xFFFF
                int len = buf.getShort() & 0xFFFF;
                if (len > buf.remaining()) {
                    throw new IllegalArgumentException("Str len " + len + " exceeds buffer remaining " + buf.remaining());
                }
                byte[] bytes = new byte[len];
                buf.get(bytes);
                return ofStr(new String(bytes, StandardCharsets.UTF_8));
            }

            case TAG_BYTES: {
                if (buf.remaining() < 4) {
                    throw new IllegalArgumentException("Truncated buffer: expected 4 bytes for Bytes len");
                }
                int len = buf.getInt();
                if (len < 0 || len > buf.remaining()) {
                    throw new IllegalArgumentException("Bytes len " + len + " exceeds buffer remaining " + buf.remaining());
                }
                byte[] bytes = new byte[len];
                buf.get(bytes);
                return ofBytes(bytes);
            }

            case TAG_CALLBACK_TOKEN:
                if (buf.remaining() < 4) {
                    throw new IllegalArgumentException("Truncated buffer: expected 4 bytes for CallbackToken");
                }
                return ofCallbackToken(buf.getInt());

            case TAG_OBJECT_TOKEN:
                if (buf.remaining() < 4) {
                    throw new IllegalArgumentException("Truncated buffer: expected 4 bytes for ObjectToken");
                }
                return ofObjectToken(buf.getInt());

            case TAG_INT_ARRAY: {
                if (buf.remaining() < 4) {
                    throw new IllegalArgumentException("Truncated buffer: expected 4 bytes for IntArray len");
                }
                int count = buf.getInt();
                if (count < 0 || count > buf.remaining() / 4) {
                    throw new IllegalArgumentException("IntArray len " + count + " exceeds buffer remaining " + buf.remaining());
                }
                int[] arr = new int[count];
                for (int i = 0; i < count; i++) {
                    arr[i] = buf.getInt();
                }
                return ofIntArray(arr);
            }

            case TAG_STR_ARRAY: {
                if (buf.remaining() < 4) {
                    throw new IllegalArgumentException("Truncated buffer: expected 4 bytes for StrArray count");
                }
                int count = buf.getInt();
                if (count < 0 || count > buf.remaining()) {
                    throw new IllegalArgumentException("StrArray count " + count + " exceeds buffer remaining " + buf.remaining());
                }
                String[] arr = new String[count];
                for (int i = 0; i < count; i++) {
                    if (buf.remaining() < 2) {
                        throw new IllegalArgumentException("Truncated buffer: expected 2 bytes for StrArray elem len");
                    }
                    int len = buf.getShort() & 0xFFFF;
                    if (len > buf.remaining()) {
                        throw new IllegalArgumentException("StrArray elem len " + len + " exceeds buffer remaining " + buf.remaining());
                    }
                    byte[] bytes = new byte[len];
                    buf.get(bytes);
                    arr[i] = new String(bytes, StandardCharsets.UTF_8);
                }
                return ofStrArray(arr);
            }

            default:
                throw new IllegalArgumentException("Unknown ArgValue tag: 0x" + Integer.toHexString(tag & 0xFF));
        }
    }

    /**
     * Encode this ArgValue into the ByteBuffer.
     */
    public void writeTo(ByteBuffer buf) {
        buf.put(tag);
        switch (tag) {
            case TAG_NULL:
                break;
            case TAG_INT:
                buf.putInt(asInt());
                break;
            case TAG_LONG:
                buf.putLong(asLong());
                break;
            case TAG_BOOL:
                buf.put((byte) (asBool() ? 1 : 0));
                break;
            case TAG_STR: {
                byte[] bytes = asString().getBytes(StandardCharsets.UTF_8);
                if (bytes.length > 0xFFFF) {
                    throw new IllegalArgumentException("Str len exceeds u16");
                }
                buf.putShort((short) bytes.length);
                buf.put(bytes);
                break;
            }
            case TAG_BYTES: {
                byte[] bytes = asBytes();
                buf.putInt(bytes.length);
                buf.put(bytes);
                break;
            }
            case TAG_CALLBACK_TOKEN:
                buf.putInt(asCallbackToken());
                break;
            case TAG_OBJECT_TOKEN:
                buf.putInt(asObjectToken());
                break;
            case TAG_INT_ARRAY: {
                int[] arr = intArrayRef();
                buf.putInt(arr.length);
                for (int v : arr) {
                    buf.putInt(v);
                }
                break;
            }
            case TAG_STR_ARRAY: {
                String[] arr = strArrayRef();
                buf.putInt(arr.length);
                for (String s : arr) {
                    byte[] bytes = (s != null ? s : "").getBytes(StandardCharsets.UTF_8);
                    if (bytes.length > 0xFFFF) {
                        throw new IllegalArgumentException("Str len exceeds u16");
                    }
                    buf.putShort((short) bytes.length);
                    buf.put(bytes);
                }
                break;
            }
        }
    }

    public int encodedLen() {
        switch (tag) {
            case TAG_NULL:
                return 1;
            case TAG_INT:
                return 1 + 4;
            case TAG_LONG:
                return 1 + 8;
            case TAG_BOOL:
                return 1 + 1;
            case TAG_STR:
                return 1 + 2 + asString().getBytes(StandardCharsets.UTF_8).length;
            case TAG_BYTES:
                return 1 + 4 + asBytes().length;
            case TAG_CALLBACK_TOKEN:
                return 1 + 4;
            case TAG_OBJECT_TOKEN:
                return 1 + 4;
            case TAG_INT_ARRAY:
                return 1 + 4 + 4 * intArrayRef().length;
            case TAG_STR_ARRAY: {
                int n = 1 + 4;
                for (String s : strArrayRef()) {
                    n += 2 + (s != null ? s.getBytes(StandardCharsets.UTF_8).length : 0);
                }
                return n;
            }
            default:
                return 1;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ArgValue)) return false;
        ArgValue argValue = (ArgValue) o;
        if (tag != argValue.tag) return false;
        if (tag == TAG_BYTES) {
            return Arrays.equals((byte[]) value, (byte[]) argValue.value);
        }
        if (tag == TAG_INT_ARRAY) {
            return Arrays.equals((int[]) value, (int[]) argValue.value);
        }
        if (tag == TAG_STR_ARRAY) {
            return Arrays.equals((String[]) value, (String[]) argValue.value);
        }
        return Objects.equals(value, argValue.value);
    }

    @Override
    public int hashCode() {
        if (tag == TAG_BYTES) {
            return Objects.hash(tag, Arrays.hashCode((byte[]) value));
        }
        if (tag == TAG_INT_ARRAY) {
            return Objects.hash(tag, Arrays.hashCode((int[]) value));
        }
        if (tag == TAG_STR_ARRAY) {
            return Objects.hash(tag, Arrays.hashCode((String[]) value));
        }
        return Objects.hash(tag, value);
    }

    @Override
    public String toString() {
        switch (tag) {
            case TAG_NULL: return "ArgValue::Null";
            case TAG_INT: return "ArgValue::Int(" + value + ")";
            case TAG_LONG: return "ArgValue::Long(" + value + ")";
            case TAG_BOOL: return "ArgValue::Bool(" + value + ")";
            case TAG_STR: return "ArgValue::Str(\"" + value + "\")";
            case TAG_BYTES: return "ArgValue::Bytes(len=" + asBytes().length + ")";
            case TAG_CALLBACK_TOKEN: return "ArgValue::CallbackToken(" + value + ")";
            case TAG_OBJECT_TOKEN: return "ArgValue::ObjectToken(" + value + ")";
            case TAG_INT_ARRAY: return "ArgValue::IntArray(len=" + intArrayRef().length + ")";
            case TAG_STR_ARRAY: return "ArgValue::StrArray(len=" + strArrayRef().length + ")";
            default: return "ArgValue::Unknown";
        }
    }
}
