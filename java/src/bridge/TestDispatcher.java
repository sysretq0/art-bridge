package bridge;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Self-test harness to verify Dispatcher opcode processing, typed ArgValue framing,
 * and universal reflection.
 */
public final class TestDispatcher {
    public static void main(String[] args) throws Exception {
        System.out.println("Running upgraded Java Dispatcher self-tests...");

        ByteBuffer rx = ByteBuffer.allocate(65536).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer tx = ByteBuffer.allocate(65536).order(ByteOrder.LITTLE_ENDIAN);

        // Test 1: PING
        rx.clear();
        rx.putLong(101L);
        rx.putShort((short) Dispatcher.OP_PING);
        rx.putShort((short) 0);
        rx.flip();

        tx.clear();
        Dispatcher.dispatch(rx, tx);
        tx.flip();

        assert tx.get() == Dispatcher.MSG_TYPE_RPC_RESPONSE : "Expected RPC_RESPONSE msg_type";
        assert tx.getLong() == 101L : "Mismatch in req_id";
        assert tx.get() == Dispatcher.STATUS_OK : "Status not OK";
        int len = tx.getInt();
        ArgValue pingVal = ArgValue.readFrom(tx);
        System.out.println("  ✓ OP_PING -> " + pingVal.asString());
        assert pingVal.asString().startsWith("PONG") : "Expected PONG";

        // Test 2: GET_SYSTEM_PROPERTY with ArgValue::Str
        rx.clear();
        rx.putLong(102L);
        rx.putShort((short) Dispatcher.OP_GET_SYSTEM_PROPERTY);
        rx.putShort((short) 2); // argc = 2
        ArgValue.ofStr("ro.build.version.sdk").writeTo(rx);
        ArgValue.ofStr("34").writeTo(rx);
        rx.flip();

        tx.clear();
        Dispatcher.dispatch(rx, tx);
        tx.flip();

        assert tx.get() == Dispatcher.MSG_TYPE_RPC_RESPONSE;
        assert tx.getLong() == 102L;
        assert tx.get() == Dispatcher.STATUS_OK;
        len = tx.getInt();
        ArgValue propVal = ArgValue.readFrom(tx);
        System.out.println("  ✓ OP_GET_SYSTEM_PROPERTY -> " + propVal.asString());

        // Test 3: INVOKE_STATIC_METHOD (android.os.SystemProperties.get(key, def))
        rx.clear();
        rx.putLong(103L);
        rx.putShort((short) Dispatcher.OP_INVOKE_STATIC_METHOD);
        rx.putShort((short) 4); // className, methodName, arg0, arg1
        ArgValue.ofStr("android.os.SystemProperties").writeTo(rx);
        ArgValue.ofStr("get").writeTo(rx);
        ArgValue.ofStr("ro.product.model").writeTo(rx);
        ArgValue.ofStr("Pixel 9").writeTo(rx);
        rx.flip();

        tx.clear();
        Dispatcher.dispatch(rx, tx);
        tx.flip();

        assert tx.get() == Dispatcher.MSG_TYPE_RPC_RESPONSE;
        assert tx.getLong() == 103L;
        assert tx.get() == Dispatcher.STATUS_OK;
        len = tx.getInt();
        ArgValue staticResult = ArgValue.readFrom(tx);
        System.out.println("  ✓ OP_INVOKE_STATIC_METHOD -> " + staticResult.asString());
        assert "Pixel 9".equals(staticResult.asString());

        // Test 4: Dynamic Callback Trigger (register first so trigger validates)
        final boolean[] callbackFired = new boolean[1];
        CallbackRegistry.setCallbackSender((cbId, code, cbArgs) -> {
            System.out.println("  ✓ Async Callback Event fired: id=" + cbId + " code=" + code + " argsCount=" + cbArgs.length);
            assert cbId == 42;
            assert code == 1001;
            assert cbArgs.length == 1;
            assert "event_data".equals(cbArgs[0].asString());
            callbackFired[0] = true;
        });
        CallbackRegistry.registerInterfaceProxy(42, "java.lang.Runnable");

        rx.clear();
        rx.putLong(104L);
        rx.putShort((short) Dispatcher.OP_TRIGGER_CALLBACK);
        rx.putShort((short) 3); // callbackId, code, arg0
        ArgValue.ofInt(42).writeTo(rx);
        ArgValue.ofInt(1001).writeTo(rx);
        ArgValue.ofStr("event_data").writeTo(rx);
        rx.flip();

        tx.clear();
        Dispatcher.dispatch(rx, tx);
        tx.flip();

        assert tx.get() == Dispatcher.MSG_TYPE_RPC_RESPONSE;
        assert tx.getLong() == 104L;
        assert tx.get() == Dispatcher.STATUS_OK;
        assert callbackFired[0] : "Callback was not fired";

        // Test 5: NEW_INSTANCE -> INVOKE_INSTANCE_METHOD -> GET/SET_FIELD -> RELEASE_OBJECT
        rx.clear();
        rx.putLong(105L);
        rx.putShort((short) Dispatcher.OP_NEW_INSTANCE);
        rx.putShort((short) 3);
        ArgValue.ofStr("bridge.SampleObject").writeTo(rx);
        ArgValue.ofStr("selftest").writeTo(rx);
        ArgValue.ofInt(7).writeTo(rx);
        rx.flip();
        tx.clear();
        Dispatcher.dispatch(rx, tx);
        tx.flip();
        assert tx.get() == Dispatcher.MSG_TYPE_RPC_RESPONSE;
        assert tx.getLong() == 105L;
        assert tx.get() == Dispatcher.STATUS_OK;
        len = tx.getInt();
        ArgValue tokVal = ArgValue.readFrom(tx);
        assert tokVal.getTag() == ArgValue.TAG_OBJECT_TOKEN : "Expected ObjectToken";
        int objId = tokVal.asObjectToken();
        System.out.println("  ✓ OP_NEW_INSTANCE -> ObjectToken(" + objId + ")");

        rx.clear();
        rx.putLong(106L);
        rx.putShort((short) Dispatcher.OP_INVOKE_INSTANCE_METHOD);
        rx.putShort((short) 2);
        ArgValue.ofObjectToken(objId).writeTo(rx);
        ArgValue.ofStr("getName").writeTo(rx);
        rx.flip();
        tx.clear();
        Dispatcher.dispatch(rx, tx);
        tx.flip();
        assert tx.get() == Dispatcher.MSG_TYPE_RPC_RESPONSE;
        assert tx.getLong() == 106L;
        assert tx.get() == Dispatcher.STATUS_OK;
        len = tx.getInt();
        ArgValue nameVal = ArgValue.readFrom(tx);
        assert "selftest".equals(nameVal.asString());
        System.out.println("  ✓ OP_INVOKE_INSTANCE_METHOD -> " + nameVal.asString());

        rx.clear();
        rx.putLong(107L);
        rx.putShort((short) Dispatcher.OP_GET_FIELD);
        rx.putShort((short) 2);
        ArgValue.ofObjectToken(objId).writeTo(rx);
        ArgValue.ofStr("counter").writeTo(rx);
        rx.flip();
        tx.clear();
        Dispatcher.dispatch(rx, tx);
        tx.flip();
        assert tx.get() == Dispatcher.MSG_TYPE_RPC_RESPONSE;
        assert tx.getLong() == 107L;
        assert tx.get() == Dispatcher.STATUS_OK;
        len = tx.getInt();
        ArgValue counterVal = ArgValue.readFrom(tx);
        assert counterVal.asInt() == 7 : "Expected counter 7, got " + counterVal.asInt();
        System.out.println("  ✓ OP_GET_FIELD -> " + counterVal.asInt());

        rx.clear();
        rx.putLong(108L);
        rx.putShort((short) Dispatcher.OP_SET_FIELD);
        rx.putShort((short) 3);
        ArgValue.ofObjectToken(objId).writeTo(rx);
        ArgValue.ofStr("counter").writeTo(rx);
        ArgValue.ofInt(99).writeTo(rx);
        rx.flip();
        tx.clear();
        Dispatcher.dispatch(rx, tx);
        tx.flip();
        assert tx.get() == Dispatcher.MSG_TYPE_RPC_RESPONSE;
        assert tx.getLong() == 108L;
        assert tx.get() == Dispatcher.STATUS_OK;
        System.out.println("  ✓ OP_SET_FIELD -> OK");

        rx.clear();
        rx.putLong(109L);
        rx.putShort((short) Dispatcher.OP_RELEASE_OBJECT);
        rx.putShort((short) 1);
        ArgValue.ofObjectToken(objId).writeTo(rx);
        rx.flip();
        tx.clear();
        Dispatcher.dispatch(rx, tx);
        tx.flip();
        assert tx.get() == Dispatcher.MSG_TYPE_RPC_RESPONSE;
        assert tx.getLong() == 109L;
        assert tx.get() == Dispatcher.STATUS_OK;
        System.out.println("  ✓ OP_RELEASE_OBJECT -> OK");

        // Test 6: UNREGISTER_CALLBACK
        CallbackRegistry.registerInterfaceProxy(43, "java.lang.Runnable");
        rx.clear();
        rx.putLong(110L);
        rx.putShort((short) Dispatcher.OP_UNREGISTER_CALLBACK);
        rx.putShort((short) 1);
        ArgValue.ofInt(43).writeTo(rx);
        rx.flip();
        tx.clear();
        Dispatcher.dispatch(rx, tx);
        tx.flip();
        assert tx.get() == Dispatcher.MSG_TYPE_RPC_RESPONSE;
        assert tx.getLong() == 110L;
        assert tx.get() == Dispatcher.STATUS_OK;
        assert CallbackRegistry.getRegistered(43) == null : "Callback should be unregistered";
        System.out.println("  ✓ OP_UNREGISTER_CALLBACK -> OK");

        System.out.println("All upgraded Java Dispatcher self-tests passed successfully!");
    }
}
