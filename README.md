# ART Bridge: Universal Android Hidden API & Bidirectional Callback Engine

A high-performance, single-threaded Android Hidden API execution bridge and native Rust IPC client with bidirectional callback event multiplexing.

The system connects native Linux/Android processes to an Android runtime (`app_process`) daemon over atomic Linux abstract domain sockets (`SOCK_SEQPACKET` at `\0art_bridge`) with zero-allocation Little-Endian binary framing.

---

## Workspace Layout

```
/work/art-bridge/
├── Cargo.toml                          # Workspace root
├── java/
│   ├── build.sh                        # Compile script: javac --release 11 + d8
│   ├── build/                          # Generated JAR and DEX binaries
│   │   ├── art-bridge.jar              # Standard JVM bytecode
│   │   └── art-bridge.dex              # Android Dalvik/ART executable DEX
│   ├── src/bridge/
│   │   ├── Main.java                   # Worker daemon entrypoint & SOCK_SEQPACKET listener
│   │   ├── ArgValue.java               # Tagged binary value encoding/decoding & type conversion
│   │   ├── BinderCache.java            # ServiceManager & proxy caching with auto-eviction
│   │   ├── CallbackRegistry.java       # Dynamic Java proxies & Binder stubs with thread-safe dispatch
│   │   ├── ReflectionEngine.java       # Universal reflective invocation & raw Binder transact
│   │   ├── Dispatcher.java             # Opcode dispatcher & multiplexed frame builder
│   │   ├── EchoDispatcher.java         # Test harness for live cross-process IPC
│   │   └── TestDispatcher.java         # Standalone self-test suite
│   └── stubs/                          # Compile-only stubs (no Android SDK download required)
│       ├── dalvik/system/VMRuntime.java
│       ├── android/app/IActivityManager.java
│       ├── android/os/
│       │   ├── Binder.java
│       │   ├── Build.java
│       │   ├── DeadObjectException.java
│       │   ├── IBinder.java
│       │   ├── IInterface.java
│       │   ├── Parcel.java
│       │   ├── RemoteException.java
│       │   ├── ServiceManager.java
│       │   └── SystemProperties.java
│       └── android/system/
│           ├── ErrnoException.java
│           ├── Os.java
│           ├── OsConstants.java
│           ├── StructUcred.java
│           └── UnixSocketAddress.java
└── crates/
    ├── bridge-proto/                   # Zero-dependency Little-Endian binary protocol
    │   ├── Cargo.toml
    │   └── src/lib.rs
    └── bridge-client/                  # Multiplexed synchronous/poll-friendly SOCK_SEQPACKET client
        ├── Cargo.toml
        ├── src/
        │   ├── lib.rs
        │   ├── client.rs
        │   └── error.rs
        └── tests/
            └── integration_test.rs     # Integration & live JVM test suite
```

---

## Binary Protocol Specification

Both sides enforce strict 64 KB packet limits and reuse pre-allocated `[u8; 65536]` / `ByteBuffer.allocate(65536)` buffers with `ByteOrder.LITTLE_ENDIAN`.

### 1. Tagged Argument Value (`ArgValue`)

Arguments and return values are encoded using compact binary tags:

| Tag | Name | Payload Layout |
|---|---|---|
| `0x00` | `Null` | (0 bytes) |
| `0x01` | `Int` | `val: i32` (4 bytes LE) |
| `0x02` | `Long` | `val: i64` (8 bytes LE) |
| `0x03` | `Bool` | `val: u8` (`0` or `1`) |
| `0x04` | `Str` | `len: u16` (2 bytes LE) + `utf8_bytes` |
| `0x05` | `Bytes` | `len: u32` (4 bytes LE) + `raw_bytes` |
| `0x06` | `CallbackToken` | `token: u32` (4 bytes LE) |

### 2. Request Frame (Client -> Worker)

```text
+------------------+-----------------+-----------------+----------------------------+
| req_id: u64 (8B) | opcode: u16(2B) | argc: u16 (2B)  | [ArgValue 1] [ArgValue 2]..|
+------------------+-----------------+-----------------+----------------------------+
```

- `req_id` (`u64`): Multiplexed correlation identifier.
- `opcode` (`u16`): Operation code (e.g. `0x0010` for `INVOKE_STATIC_METHOD`).
- `argc` (`u16`): Count of `ArgValue` arguments.
- Arguments: `argc` sequential tagged `ArgValue` encodings.

### 3. Multiplexed Incoming Frames (Worker -> Client)

Incoming frames are distinguished by an initial `msg_type: u8` byte:

#### A. RPC Response Frame (`msg_type = 0x01`)

```text
+------------------+------------------+---------------+-----------------------+---------------------+
| msg_type = 1(1B) | req_id: u64 (8B) | status: u8(1B)| payload_len: u32 (4B) | payload bytes ...   |
+------------------+------------------+---------------+-----------------------+---------------------+
```

- `msg_type` (`u8`): Constant `0x01`.
- `req_id` (`u64`): Matches the client's request correlation identifier.
- `status` (`u8`): `0 = OK`, `1 = ERROR`, `2 = UNKNOWN_OPCODE`, `3 = INVALID_ARGUMENTS`.
- `payload_len` (`u32`): Length of response payload.
- `payload_bytes`: Return `ArgValue` binary encoding or UTF-8 error string.

#### B. Async Callback Event Frame (`msg_type = 0x02`)

```text
+------------------+--------------------+-------------------------+----------------+---------------------+
| msg_type = 2(1B) | callback_id:u32(4B)| method_or_code: u32(4B) | argc: u16 (2B) | [ArgValue 1] ...    |
+------------------+--------------------+-------------------------+----------------+---------------------+
```

- `msg_type` (`u8`): Constant `0x02`.
- `callback_id` (`u32`): ID assigned during callback registration.
- `method_or_code` (`u32`): Reflected method index (proxy) or transaction code (Binder stub).
- `argc` (`u16`): Count of argument values passed by the Android framework.
- Arguments: `argc` sequential tagged `ArgValue` encodings.

---

## Supported Opcodes

### Legacy Core Opcodes
| Opcode | Name | Arguments | Description |
|---|---|---|---|
| `0x0001` | `PING` | (none) | Returns `"PONG sdk=" + SDK + " uid=" + UID` |
| `0x0002` | `GET_SYSTEM_PROPERTY` | `key: String`, `[def: String]` | Invokes `android.os.SystemProperties.get(key, def)` |
| `0x0003` | `FORCE_STOP_PACKAGE` | `pkg: String`, `[userId: int]` | Invokes `IActivityManager.forceStopPackage(pkg, userId)` |
| `0x0004` | `SET_PROCESS_LIMIT` | `max: int` | Invokes `IActivityManager.setProcessLimit(max)` |
| `0x0005` | `CHECK_SERVICE` | `service: String` | Checks `ServiceManager` for active Binder handle |

### Universal Reflection & Dynamic Callback Opcodes
| Opcode | Name | Arguments | Description |
|---|---|---|---|
| `0x0010` | `INVOKE_STATIC_METHOD` | `className: Str`, `methodName: Str`, `args...` | Dynamically executes any static method |
| `0x0011` | `INVOKE_SERVICE_METHOD` | `service: Str`, `aidlInterface: Str`, `method: Str`, `args...` | Resolves service via `ServiceManager`, proxies via `<Interface>$Stub.asInterface`, invokes method, and retries on `DeadObjectException` |
| `0x0012` | `RAW_BINDER_TRANSACT` | `service: Str`, `code: Int`, `args...` | Marshals arguments into `Parcel`, calls `IBinder.transact()`, and returns reply bytes |
| `0x0013` | `REGISTER_CALLBACK_PROXY`| `callbackId: Int`, `interfaceName: Str` | Creates dynamic `java.lang.reflect.Proxy` forwarding calls as `0x02` async events |
| `0x0014` | `REGISTER_BINDER_STUB` | `callbackId: Int`, `descriptor: Str` | Creates custom `android.os.Binder` stub forwarding `onTransact` as `0x02` async events |
| `0x0015` | `UNREGISTER_CALLBACK` | `callbackId: Int` | Releases registered callback proxy or stub |
| `0x8000` | `ECHO` | `value: ArgValue` | Echoes argument back for boundary and large-payload testing |

---

## Security & Concurrency Hardening

1. **Android Hidden API Unlock**:
   At startup, `Main.java` exempts all hidden APIs via:
   ```java
   VMRuntime.getRuntime().setHiddenApiExemptions(new String[]{"L"});
   ```
2. **Abstract Socket Namespace (`\0art_bridge`)**:
   Avoids filesystem permission issues and leaves no stale socket files on disk.
3. **Peer Credential Authentication (`SO_PEERCRED`)**:
   Inspects caller UID at connection acceptance, allowing only identical UID, root (`0`), or Android shell (`2000`).
4. **Packet Truncation Detection (`MSG_TRUNC`)**:
   The native Rust client passes `libc::MSG_TRUNC` to `recv(2)`. If a response exceeds 64 KB, the client safely errors with `ClientError::PacketTruncated` rather than panicking on truncated deserialization.
5. **Thread-Safe Socket Multiplexing**:
   Android Binder callbacks execute on arbitrary Binder threadpool threads (`binder:XXXX_X`). Both `Main.java` and `EchoDispatcher.java` guard socket writes (`writeResponse` and `sendCallbackEvent`) with synchronized locks, ensuring atomic packet delivery without interleaving.
6. **Dead Binder Recovery**:
   `BinderCache` checks `binder.isBinderAlive()` and catches `DeadObjectException`, automatically invalidating stale caches and re-resolving services once from `ServiceManager`.

---

## Building and Verification

### 1. Build Java Worker & DEX

```bash
cd /work/art-bridge/java
bash build.sh
```

Outputs:
- `build/art-bridge.jar`
- `build/art-bridge.dex`

### 2. Run Rust Unit & Integration Tests

```bash
cd /work/art-bridge
cargo test --workspace
```

The test suite automatically tests:
- Binary encoding and decoding roundtrips for all `ArgValue` variants.
- Buffer reuse and packet size bounds (64 KB).
- Mock abstract `SOCK_SEQPACKET` responder across all opcodes.
- `MSG_TRUNC` detection under oversized responses.
- `poll_readable` socket readiness.
- Live cross-process IPC directly against the compiled Java bytecode (`EchoDispatcher`):
  - Dynamic reflection with mixed `Int`, `Long`, `Bool`, and `Str` arguments.
  - Raw Binder `transact` Parcel IPC.
  - Boundary payloads (`> 32 KB`, 40 KB string) with unsigned `u16` masking.
  - Bidirectional callback proxy registration and async event demultiplexing during concurrent RPC requests.

---

## Deployment on Android

To run the daemon on an Android device via `adb` (or root shell):

```bash
# Push DEX to device
adb push /work/art-bridge/java/build/art-bridge.dex /data/local/tmp/

# Launch via app_process
adb shell "CLASSPATH=/data/local/tmp/art-bridge.dex app_process /system/bin bridge.Main --socket art_bridge"
```

The native Rust client can then connect immediately:

```rust
use bridge_client::BridgeClient;
use bridge_proto::ArgValue;

let mut client = BridgeClient::connect()?;

// 1. Basic PING
let pong = client.ping()?;
println!("{pong}");

// 2. Invoke any static hidden method
let sdk = client.invoke_static_method(
    "android.os.SystemProperties",
    "get",
    &[ArgValue::Str("ro.build.version.sdk"), ArgValue::Str("unknown")],
)?;

// 3. Register a dynamic callback and process incoming events
client.register_callback_proxy(101, "android.app.IProcessObserver")?;

// 4. Poll and receive unsolicited async framework events
if let Some(event) = client.poll_callback_event() {
    println!("Callback event received for ID {}: code {}", event.callback_id, event.method_or_code);
}
```
