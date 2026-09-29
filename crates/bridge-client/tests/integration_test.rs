use bridge_client::{
    encode_response_slice, ArgValue, BridgeClient, ClientError, OP_CHECK_SERVICE, OP_ECHO,
    OP_FORCE_STOP_PACKAGE, OP_GET_FIELD, OP_GET_SYSTEM_PROPERTY, OP_INVOKE_INSTANCE_METHOD,
    OP_INVOKE_SERVICE_METHOD, OP_INVOKE_STATIC_METHOD, OP_NEW_INSTANCE, OP_PING,
    OP_RAW_BINDER_TRANSACT, OP_REGISTER_CALLBACK_PROXY, OP_RELEASE_OBJECT, OP_SET_FIELD,
    OP_SET_PROCESS_LIMIT, OP_TRIGGER_CALLBACK, OP_UNREGISTER_CALLBACK, STATUS_ERROR, STATUS_OK,
    STATUS_UNKNOWN_OPCODE,
};
use bridge_proto::{Request, Response, MAX_PACKET_SIZE, MSG_TYPE_RPC_RESPONSE};
use std::os::fd::{AsRawFd, FromRawFd, OwnedFd};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::Arc;
use std::thread;
use std::time::Duration;

static TEST_SOCKET_SEQ: AtomicU64 = AtomicU64::new(1);

/// Create a unique abstract socket address for test isolation.
fn get_test_abstract_name() -> Vec<u8> {
    let pid = std::process::id();
    let seq = TEST_SOCKET_SEQ.fetch_add(1, Ordering::Relaxed);
    format!("art_bridge_test_{pid}_{seq}").into_bytes()
}

/// Helper that runs a mock server over a given abstract SOCK_SEQPACKET socket.
struct MockServer {
    _socket_name: Vec<u8>,
    stop_flag: Arc<AtomicBool>,
    handle: Option<thread::JoinHandle<()>>,
}

impl MockServer {
    fn start(socket_name: Vec<u8>) -> Self {
        let stop_flag = Arc::new(AtomicBool::new(false));
        let stop_flag_clone = stop_flag.clone();
        let name_clone = socket_name.clone();

        let handle = thread::spawn(move || {
            let server_fd = unsafe {
                libc::socket(libc::AF_UNIX, libc::SOCK_SEQPACKET | libc::SOCK_CLOEXEC, 0)
            };
            assert!(server_fd >= 0, "Failed to create test server socket");
            let server_owned = unsafe { OwnedFd::from_raw_fd(server_fd) };

            let mut addr: libc::sockaddr_un = unsafe { std::mem::zeroed() };
            addr.sun_family = libc::AF_UNIX as libc::sa_family_t;
            unsafe {
                std::ptr::copy_nonoverlapping(
                    name_clone.as_ptr(),
                    addr.sun_path.as_mut_ptr().add(1),
                    name_clone.len(),
                );
            }
            let addr_len = (std::mem::size_of::<libc::sa_family_t>() + 1 + name_clone.len())
                as libc::socklen_t;

            let bind_res = unsafe {
                libc::bind(
                    server_fd,
                    (&addr as *const libc::sockaddr_un).cast(),
                    addr_len,
                )
            };
            assert_eq!(bind_res, 0, "Failed to bind mock server to abstract socket");

            let listen_res = unsafe { libc::listen(server_fd, 4) };
            assert_eq!(listen_res, 0);

            // Accept one client connection for this test
            let client_fd = unsafe {
                libc::accept4(
                    server_fd,
                    std::ptr::null_mut(),
                    std::ptr::null_mut(),
                    libc::SOCK_CLOEXEC,
                )
            };
            if client_fd < 0 {
                return;
            }
            let client_owned = unsafe { OwnedFd::from_raw_fd(client_fd) };

            let mut rx_buf = vec![0u8; MAX_PACKET_SIZE];
            let mut tx_buf = vec![0u8; MAX_PACKET_SIZE];

            while !stop_flag_clone.load(Ordering::Relaxed) {
                let n = unsafe {
                    libc::recv(
                        client_owned.as_raw_fd(),
                        rx_buf.as_mut_ptr().cast(),
                        rx_buf.len(),
                        0,
                    )
                };
                if n <= 0 {
                    break;
                }

                let req = match Request::decode_from(&rx_buf[..n as usize]) {
                    Ok(r) => r,
                    Err(_) => break,
                };

                let resp_len = match req.opcode {
                    OP_PING => encode_response_slice(
                        req.req_id,
                        STATUS_OK,
                        b"PONG sdk=34 uid=0",
                        &mut tx_buf,
                    )
                    .unwrap(),
                    OP_GET_SYSTEM_PROPERTY => {
                        let key = req.args.first().and_then(|a| a.as_str()).unwrap_or("");
                        let def = req.args.get(1).and_then(|a| a.as_str()).unwrap_or("");
                        let val = if key == "ro.build.version.release" {
                            "14"
                        } else {
                            def
                        };
                        encode_response_slice(req.req_id, STATUS_OK, val.as_bytes(), &mut tx_buf)
                            .unwrap()
                    }
                    OP_FORCE_STOP_PACKAGE => {
                        encode_response_slice(req.req_id, STATUS_OK, b"OK", &mut tx_buf).unwrap()
                    }
                    OP_SET_PROCESS_LIMIT => {
                        encode_response_slice(req.req_id, STATUS_OK, b"OK", &mut tx_buf).unwrap()
                    }
                    OP_CHECK_SERVICE => {
                        let service = req.args.first().and_then(|a| a.as_str()).unwrap_or("");
                        let res = if service == "activity" || service == "package" {
                            b"EXISTS".as_slice()
                        } else {
                            b"NOT_FOUND".as_slice()
                        };
                        encode_response_slice(req.req_id, STATUS_OK, res, &mut tx_buf).unwrap()
                    }
                    9999 => {
                        // Special test opcode: send oversized packet for MSG_TRUNC test
                        let mut oversized = vec![0x55u8; 70_000];
                        // Response header: msg_type = 0x01, req_id, status, payload_len
                        oversized[0] = MSG_TYPE_RPC_RESPONSE;
                        oversized[1..9].copy_from_slice(&req.req_id.to_le_bytes());
                        oversized[9] = STATUS_OK;
                        oversized[10..14].copy_from_slice(&(69986u32).to_le_bytes());
                        let sent = unsafe {
                            libc::send(
                                client_owned.as_raw_fd(),
                                oversized.as_ptr().cast(),
                                oversized.len(),
                                libc::MSG_NOSIGNAL,
                            )
                        };
                        assert_eq!(sent, 70_000);
                        continue;
                    }
                    _ => encode_response_slice(
                        req.req_id,
                        STATUS_UNKNOWN_OPCODE,
                        b"Unknown opcode",
                        &mut tx_buf,
                    )
                    .unwrap(),
                };

                let sent = unsafe {
                    libc::send(
                        client_owned.as_raw_fd(),
                        tx_buf.as_ptr().cast(),
                        resp_len,
                        libc::MSG_NOSIGNAL,
                    )
                };
                if sent <= 0 {
                    break;
                }
            }
            drop(client_owned);
            drop(server_owned);
        });

        thread::sleep(Duration::from_millis(50));

        Self {
            _socket_name: socket_name,
            stop_flag,
            handle: Some(handle),
        }
    }
}

impl Drop for MockServer {
    fn drop(&mut self) {
        self.stop_flag.store(true, Ordering::Relaxed);
        if let Some(h) = self.handle.take() {
            let _ = h.join();
        }
    }
}

#[test]
fn test_mock_responder_all_opcodes() {
    let socket_name = get_test_abstract_name();
    let _server = MockServer::start(socket_name.clone());

    let mut client = BridgeClient::connect_to(&socket_name).expect("Client failed to connect");

    // 1. PING
    let ping_res = client.ping().expect("PING failed");
    assert_eq!(ping_res, "PONG sdk=34 uid=0");

    // 2. GET_SYSTEM_PROPERTY
    let prop = client
        .get_system_property("ro.build.version.release", Some("unknown"))
        .expect("get_system_property failed");
    assert_eq!(prop, "14");

    let fallback_prop = client
        .get_system_property("non.existent.prop", Some("default_value"))
        .expect("get_system_property default failed");
    assert_eq!(fallback_prop, "default_value");

    // 3. FORCE_STOP_PACKAGE
    client
        .force_stop_package("com.android.settings", Some(0))
        .expect("force_stop_package failed");

    // 4. SET_PROCESS_LIMIT
    client
        .set_process_limit(32)
        .expect("set_process_limit failed");

    // 5. CHECK_SERVICE
    assert!(client
        .check_service("activity")
        .expect("check_service failed"));
    assert!(client
        .check_service("package")
        .expect("check_service failed"));
    assert!(!client
        .check_service("fake_service")
        .expect("check_service failed"));

    // 6. Unknown opcode
    let err = client.call(0x9998, &[]).unwrap_err();
    match err {
        ClientError::RemoteError { status, message } => {
            assert_eq!(status, STATUS_UNKNOWN_OPCODE);
            assert_eq!(message, "Unknown opcode");
        }
        other => panic!("Expected RemoteError, got: {other:?}"),
    }
}

#[test]
fn test_msg_trunc_oversized_packet() {
    let socket_name = get_test_abstract_name();
    let _server = MockServer::start(socket_name.clone());

    let mut client = BridgeClient::connect_to(&socket_name).expect("Client failed to connect");

    let req_id = client
        .send_request(9999, &[])
        .expect("Failed to send request");

    let err = client.recv_response(req_id).unwrap_err();
    match err {
        ClientError::PacketTruncated { received, capacity } => {
            assert_eq!(received, 70_000);
            assert_eq!(capacity, MAX_PACKET_SIZE);
        }
        other => panic!("Expected PacketTruncated error, got: {other:?}"),
    }
}

#[test]
fn test_poll_readable() {
    let socket_name = get_test_abstract_name();
    let _server = MockServer::start(socket_name.clone());

    let mut client = BridgeClient::connect_to(&socket_name).expect("Client failed to connect");

    let is_readable = client.poll_readable(10).expect("poll failed");
    assert!(!is_readable);

    let id = client.send_request(OP_PING, &[]).expect("send failed");

    let readable_now = client.poll_readable(500).expect("poll failed");
    assert!(readable_now);

    let resp = client.recv_response(id).expect("recv failed");
    assert_eq!(resp.payload_as_str().unwrap(), "PONG sdk=34 uid=0");
}

// ----------------------------------------------------------------------------
// Live JVM Tests (EchoDispatcher Subprocess)
// ----------------------------------------------------------------------------

struct LiveJvmSession {
    child: std::process::Child,
}

impl LiveJvmSession {
    fn start() -> (Self, std::process::ChildStdin, std::process::ChildStdout) {
        let jar_path = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../../java/build/art-bridge.jar");
        assert!(
            jar_path.exists(),
            "art-bridge.jar missing at {} — run bash java/build.sh",
            jar_path.display()
        );
        let stubs_path = "/work/art-bridge/java/build/stubs_classes";

        let mut child = std::process::Command::new("java")
            .args([
                "-cp",
                &format!("{}:{stubs_path}", jar_path.display()),
                "bridge.EchoDispatcher",
            ])
            .stdin(std::process::Stdio::piped())
            .stdout(std::process::Stdio::piped())
            .spawn()
            .expect("Failed to spawn Java EchoDispatcher");

        let stdin = child.stdin.take().unwrap();
        let stdout = child.stdout.take().unwrap();

        (Self { child }, stdin, stdout)
    }
}

impl Drop for LiveJvmSession {
    fn drop(&mut self) {
        let _ = self.child.kill();
        let _ = self.child.wait();
    }
}

/// Helper to send framed request to JVM stdin and read framed response from JVM stdout.
fn jvm_roundtrip(
    stdin: &mut std::process::ChildStdin,
    stdout: &mut std::process::ChildStdout,
    req_id: u64,
    opcode: u16,
    args: &[ArgValue<'_>],
) -> Response<'static> {
    use std::io::{Read, Write};

    let mut req_buf = vec![0u8; 65536];
    let written = bridge_proto::encode_request_args(req_id, opcode, args, &mut req_buf).unwrap();

    let len_prefix = (written as u32).to_le_bytes();
    stdin.write_all(&len_prefix).unwrap();
    stdin.write_all(&req_buf[..written]).unwrap();
    stdin.flush().unwrap();

    let mut resp_len_buf = [0u8; 4];
    stdout.read_exact(&mut resp_len_buf).unwrap();
    let resp_len = u32::from_le_bytes(resp_len_buf) as usize;

    let mut resp_buf = vec![0u8; resp_len];
    stdout.read_exact(&mut resp_buf).unwrap();

    // Box leak for static lifetime in tests
    let leaked: &'static [u8] = Box::leak(resp_buf.into_boxed_slice());
    Response::decode_from(leaked).unwrap()
}

#[test]
fn test_live_jvm_universal_reflection_mixed_args() {
    let (_session, mut stdin, mut stdout) = LiveJvmSession::start();

    // 1. Invoke static method: java.lang.String.valueOf(int)
    let resp1 = jvm_roundtrip(
        &mut stdin,
        &mut stdout,
        1001,
        OP_INVOKE_STATIC_METHOD,
        &[
            ArgValue::Str("java.lang.String"),
            ArgValue::Str("valueOf"),
            ArgValue::Int(12345),
        ],
    );
    assert_eq!(resp1.req_id, 1001);
    assert_eq!(resp1.status, STATUS_OK);
    let val1 = resp1.payload_as_arg_value().unwrap();
    assert_eq!(val1, ArgValue::Str("12345"));

    // 2. Invoke static method: java.lang.Long.toString(long)
    let resp2 = jvm_roundtrip(
        &mut stdin,
        &mut stdout,
        1002,
        OP_INVOKE_STATIC_METHOD,
        &[
            ArgValue::Str("java.lang.Long"),
            ArgValue::Str("toString"),
            ArgValue::Long(9876543210123),
        ],
    );
    assert_eq!(resp2.req_id, 1002);
    let val2 = resp2.payload_as_arg_value().unwrap();
    assert_eq!(val2, ArgValue::Str("9876543210123"));

    // 3. Invoke static method: java.lang.Boolean.toString(boolean)
    let resp3 = jvm_roundtrip(
        &mut stdin,
        &mut stdout,
        1003,
        OP_INVOKE_STATIC_METHOD,
        &[
            ArgValue::Str("java.lang.Boolean"),
            ArgValue::Str("toString"),
            ArgValue::Bool(true),
        ],
    );
    let val3 = resp3.payload_as_arg_value().unwrap();
    assert_eq!(val3, ArgValue::Str("true"));

    // 4. Invoke static method on android.os.SystemProperties.get(key, def)
    let resp4 = jvm_roundtrip(
        &mut stdin,
        &mut stdout,
        1004,
        OP_INVOKE_STATIC_METHOD,
        &[
            ArgValue::Str("android.os.SystemProperties"),
            ArgValue::Str("get"),
            ArgValue::Str("ro.product.model"),
            ArgValue::Str("Pixel 9 Pro"),
        ],
    );
    let val4 = resp4.payload_as_arg_value().unwrap();
    assert_eq!(val4, ArgValue::Str("Pixel 9 Pro"));

    // 5. Invoke service method on IActivityManager via ReflectionEngine
    let resp5 = jvm_roundtrip(
        &mut stdin,
        &mut stdout,
        1005,
        OP_INVOKE_SERVICE_METHOD,
        &[
            ArgValue::Str("activity"),
            ArgValue::Str("android.app.IActivityManager"),
            ArgValue::Str("setProcessLimit"),
            ArgValue::Int(24),
        ],
    );
    assert_eq!(resp5.req_id, 1005);
    assert_eq!(resp5.status, STATUS_OK);
}

#[test]
fn test_live_jvm_async_callback_multiplexing() {
    let (_session, mut stdin, mut stdout) = LiveJvmSession::start();
    use std::io::{Read, Write};

    // 1. Register a dynamic callback proxy
    let resp = jvm_roundtrip(
        &mut stdin,
        &mut stdout,
        2001,
        OP_REGISTER_CALLBACK_PROXY,
        &[ArgValue::Int(99), ArgValue::Str("java.lang.Runnable")],
    );
    assert_eq!(resp.status, STATUS_OK);
    let token = resp.payload_as_arg_value().unwrap();
    assert_eq!(token, ArgValue::CallbackToken(99));

    // 2. Trigger an asynchronous callback event from the JVM
    let mut trigger_buf = vec![0u8; 1024];
    let written = bridge_proto::encode_request_args(
        2002,
        OP_TRIGGER_CALLBACK,
        &[
            ArgValue::Int(99),
            ArgValue::Int(5001), // method_or_code
            ArgValue::Str("onProcessDied"),
            ArgValue::Int(12345),
            ArgValue::Bool(true),
        ],
        &mut trigger_buf,
    )
    .unwrap();

    let prefix = (written as u32).to_le_bytes();
    stdin.write_all(&prefix).unwrap();
    stdin.write_all(&trigger_buf[..written]).unwrap();
    stdin.flush().unwrap();

    // The JVM will write:
    //  1. The Async Callback Event (msg_type = 0x02) fired via CallbackRegistry
    //  2. The RPC Response (msg_type = 0x01) for OP_TRIGGER_CALLBACK
    // Read the first frame: must be the Async Callback Event (0x02)
    let mut len_buf = [0u8; 4];
    stdout.read_exact(&mut len_buf).unwrap();
    let cb_len = u32::from_le_bytes(len_buf) as usize;
    let mut cb_buf = vec![0u8; cb_len];
    stdout.read_exact(&mut cb_buf).unwrap();

    let msg = bridge_proto::IncomingMessage::decode_from(&cb_buf).unwrap();
    match msg {
        bridge_proto::IncomingMessage::Callback(cb) => {
            assert_eq!(cb.callback_id, 99);
            assert_eq!(cb.method_or_code, 5001);
            assert_eq!(cb.args.len(), 3);
            assert_eq!(cb.args[0], ArgValue::Str("onProcessDied"));
            assert_eq!(cb.args[1], ArgValue::Int(12345));
            assert_eq!(cb.args[2], ArgValue::Bool(true));
        }
        _ => panic!("Expected Async Callback Event!"),
    }

    // Read the second frame: RPC Response (0x01) for request 2002
    stdout.read_exact(&mut len_buf).unwrap();
    let rpc_len = u32::from_le_bytes(len_buf) as usize;
    let mut rpc_buf = vec![0u8; rpc_len];
    stdout.read_exact(&mut rpc_buf).unwrap();

    let resp_msg = bridge_proto::IncomingMessage::decode_from(&rpc_buf).unwrap();
    match resp_msg {
        bridge_proto::IncomingMessage::Response(r) => {
            assert_eq!(r.req_id, 2002);
            assert_eq!(r.status, STATUS_OK);
        }
        _ => panic!("Expected RPC Response!"),
    }
}

#[test]
fn test_live_jvm_unsigned_boundary_large_payload() {
    let (_session, mut stdin, mut stdout) = LiveJvmSession::start();

    // Prepare 40 KB string payload (> 32768 bytes, tests unsigned u16 arg length & payload)
    let large_string = "X".repeat(40_000);
    assert!(large_string.len() > 32768);

    // Opcode 0x8000 (32768 >= 0x8000, tests unsigned u16 opcode masking)
    let resp = jvm_roundtrip(
        &mut stdin,
        &mut stdout,
        88888,
        OP_ECHO,
        &[ArgValue::Str(&large_string)],
    );

    assert_eq!(resp.req_id, 88888);
    assert_eq!(resp.status, STATUS_OK);
    let val = resp.payload_as_arg_value().unwrap();
    match val {
        ArgValue::Str(s) => {
            assert_eq!(s.len(), 40_000);
            assert_eq!(s, large_string);
        }
        other => panic!("Expected ArgValue::Str, got: {other:?}"),
    }
}

#[test]
fn test_live_jvm_raw_binder_transact() {
    let (_session, mut stdin, mut stdout) = LiveJvmSession::start();

    // Call rawBinderTransact on activity service
    let resp = jvm_roundtrip(
        &mut stdin,
        &mut stdout,
        3001,
        OP_RAW_BINDER_TRANSACT,
        &[
            ArgValue::Str("activity"),
            ArgValue::Int(1), // FIRST_CALL_TRANSACTION
            ArgValue::Str("test_token"),
            ArgValue::Int(42),
        ],
    );
    assert_eq!(resp.req_id, 3001);
    assert_eq!(resp.status, STATUS_OK);
    let val = resp.payload_as_arg_value().unwrap();
    assert!(matches!(val, ArgValue::Bytes(_)));
}

#[test]
fn test_request_timeout_expires() {
    let socket_name = get_test_abstract_name();
    // Server that accepts one connection and never responds.
    let server_name = socket_name.clone();
    let handle = thread::spawn(move || {
        let server_fd =
            unsafe { libc::socket(libc::AF_UNIX, libc::SOCK_SEQPACKET | libc::SOCK_CLOEXEC, 0) };
        assert!(server_fd >= 0);
        let _owned = unsafe { OwnedFd::from_raw_fd(server_fd) };
        let mut addr: libc::sockaddr_un = unsafe { std::mem::zeroed() };
        addr.sun_family = libc::AF_UNIX as libc::sa_family_t;
        unsafe {
            std::ptr::copy_nonoverlapping(
                server_name.as_ptr(),
                addr.sun_path.as_mut_ptr().add(1),
                server_name.len(),
            );
        }
        let addr_len =
            (std::mem::size_of::<libc::sa_family_t>() + 1 + server_name.len()) as libc::socklen_t;
        assert_eq!(
            unsafe {
                libc::bind(
                    server_fd,
                    (&addr as *const libc::sockaddr_un).cast(),
                    addr_len,
                )
            },
            0
        );
        assert_eq!(unsafe { libc::listen(server_fd, 1) }, 0);
        let client_fd = unsafe {
            libc::accept4(
                server_fd,
                std::ptr::null_mut(),
                std::ptr::null_mut(),
                libc::SOCK_CLOEXEC,
            )
        };
        if client_fd < 0 {
            return;
        }
        let _client = unsafe { OwnedFd::from_raw_fd(client_fd) };
        // Never respond; hold the connection open past the client deadline.
        thread::sleep(Duration::from_millis(600));
    });
    thread::sleep(Duration::from_millis(50));

    let mut client = BridgeClient::connect_to(&socket_name).expect("connect failed");
    client.set_request_timeout(Duration::from_millis(300));
    let req_id = client.send_request(OP_PING, &[]).expect("send failed");
    let err = client.recv_response(req_id).unwrap_err();
    assert!(
        matches!(err, ClientError::Timeout),
        "expected Timeout, got: {err:?}"
    );
    let _ = handle.join();
}

#[test]
fn test_live_jvm_object_lifecycle() {
    let (_session, mut stdin, mut stdout) = LiveJvmSession::start();

    // NEW_INSTANCE bridge.SampleObject(String, int)
    let resp = jvm_roundtrip(
        &mut stdin,
        &mut stdout,
        4001,
        OP_NEW_INSTANCE,
        &[
            ArgValue::Str("bridge.SampleObject"),
            ArgValue::Str("test-name"),
            ArgValue::Int(100),
        ],
    );
    assert_eq!(resp.status, STATUS_OK, "NEW_INSTANCE failed");
    let token = match resp.payload_as_arg_value().unwrap() {
        ArgValue::ObjectToken(id) => id,
        other => panic!("Expected ObjectToken, got: {other:?}"),
    };

    // INVOKE_INSTANCE_METHOD getName -> Str
    let resp = jvm_roundtrip(
        &mut stdin,
        &mut stdout,
        4002,
        OP_INVOKE_INSTANCE_METHOD,
        &[ArgValue::ObjectToken(token), ArgValue::Str("getName")],
    );
    assert_eq!(resp.status, STATUS_OK);
    assert_eq!(
        resp.payload_as_arg_value().unwrap(),
        ArgValue::Str("test-name")
    );

    // INVOKE_INSTANCE_METHOD increment(5) -> Int(105)
    let resp = jvm_roundtrip(
        &mut stdin,
        &mut stdout,
        4003,
        OP_INVOKE_INSTANCE_METHOD,
        &[
            ArgValue::ObjectToken(token),
            ArgValue::Str("increment"),
            ArgValue::Int(5),
        ],
    );
    assert_eq!(resp.status, STATUS_OK);
    assert_eq!(resp.payload_as_arg_value().unwrap(), ArgValue::Int(105));

    // GET_FIELD counter -> Int(105)
    let resp = jvm_roundtrip(
        &mut stdin,
        &mut stdout,
        4004,
        OP_GET_FIELD,
        &[ArgValue::ObjectToken(token), ArgValue::Str("counter")],
    );
    assert_eq!(resp.status, STATUS_OK);
    assert_eq!(resp.payload_as_arg_value().unwrap(), ArgValue::Int(105));

    // SET_FIELD counter = 999, then GET_FIELD
    let resp = jvm_roundtrip(
        &mut stdin,
        &mut stdout,
        4005,
        OP_SET_FIELD,
        &[
            ArgValue::ObjectToken(token),
            ArgValue::Str("counter"),
            ArgValue::Int(999),
        ],
    );
    assert_eq!(resp.status, STATUS_OK);
    let resp = jvm_roundtrip(
        &mut stdin,
        &mut stdout,
        4006,
        OP_GET_FIELD,
        &[ArgValue::ObjectToken(token), ArgValue::Str("counter")],
    );
    assert_eq!(resp.payload_as_arg_value().unwrap(), ArgValue::Int(999));

    // Private field access via setAccessible(true)
    let resp = jvm_roundtrip(
        &mut stdin,
        &mut stdout,
        4007,
        OP_GET_FIELD,
        &[ArgValue::ObjectToken(token), ArgValue::Str("name")],
    );
    assert_eq!(resp.status, STATUS_OK);
    assert_eq!(
        resp.payload_as_arg_value().unwrap(),
        ArgValue::Str("test-name")
    );
    let resp = jvm_roundtrip(
        &mut stdin,
        &mut stdout,
        4008,
        OP_SET_FIELD,
        &[
            ArgValue::ObjectToken(token),
            ArgValue::Str("name"),
            ArgValue::Str("renamed"),
        ],
    );
    assert_eq!(resp.status, STATUS_OK);
    let resp = jvm_roundtrip(
        &mut stdin,
        &mut stdout,
        4009,
        OP_INVOKE_INSTANCE_METHOD,
        &[ArgValue::ObjectToken(token), ArgValue::Str("getName")],
    );
    assert_eq!(
        resp.payload_as_arg_value().unwrap(),
        ArgValue::Str("renamed")
    );

    // Complex return -> ObjectToken (List from makeList)
    let resp = jvm_roundtrip(
        &mut stdin,
        &mut stdout,
        4010,
        OP_INVOKE_INSTANCE_METHOD,
        &[
            ArgValue::ObjectToken(token),
            ArgValue::Str("makeList"),
            ArgValue::Str("x"),
            ArgValue::Str("y"),
        ],
    );
    assert_eq!(resp.status, STATUS_OK);
    let list_token = match resp.payload_as_arg_value().unwrap() {
        ArgValue::ObjectToken(id) => id,
        other => panic!("Expected ObjectToken for List, got: {other:?}"),
    };
    // Release the List token via RELEASE_OBJECT
    let resp = jvm_roundtrip(
        &mut stdin,
        &mut stdout,
        4011,
        OP_RELEASE_OBJECT,
        &[ArgValue::ObjectToken(list_token)],
    );
    assert_eq!(resp.status, STATUS_OK);

    // RELEASE_OBJECT original instance
    let resp = jvm_roundtrip(
        &mut stdin,
        &mut stdout,
        4012,
        OP_RELEASE_OBJECT,
        &[ArgValue::ObjectToken(token)],
    );
    assert_eq!(resp.status, STATUS_OK);

    // Using a released token must fail
    let resp = jvm_roundtrip(
        &mut stdin,
        &mut stdout,
        4013,
        OP_INVOKE_INSTANCE_METHOD,
        &[ArgValue::ObjectToken(token), ArgValue::Str("getName")],
    );
    assert_eq!(resp.status, STATUS_ERROR);
}

#[test]
fn test_live_jvm_array_passing() {
    let (_session, mut stdin, mut stdout) = LiveJvmSession::start();

    // ECHO roundtrips for the new array wire types
    let resp = jvm_roundtrip(
        &mut stdin,
        &mut stdout,
        5001,
        OP_ECHO,
        &[ArgValue::IntArray(vec![1, -2, 300])],
    );
    assert_eq!(resp.status, STATUS_OK);
    assert_eq!(
        resp.payload_as_arg_value().unwrap(),
        ArgValue::IntArray(vec![1, -2, 300])
    );

    let resp = jvm_roundtrip(
        &mut stdin,
        &mut stdout,
        5002,
        OP_ECHO,
        &[ArgValue::StrArray(vec!["x".to_string(), "yz".to_string()])],
    );
    assert_eq!(resp.status, STATUS_OK);
    assert_eq!(
        resp.payload_as_arg_value().unwrap(),
        ArgValue::StrArray(vec!["x".to_string(), "yz".to_string()])
    );

    // NEW_INSTANCE SampleObject() then pass arrays to methods
    let resp = jvm_roundtrip(
        &mut stdin,
        &mut stdout,
        5003,
        OP_NEW_INSTANCE,
        &[ArgValue::Str("bridge.SampleObject")],
    );
    assert_eq!(resp.status, STATUS_OK);
    let token = match resp.payload_as_arg_value().unwrap() {
        ArgValue::ObjectToken(id) => id,
        other => panic!("Expected ObjectToken, got: {other:?}"),
    };

    let resp = jvm_roundtrip(
        &mut stdin,
        &mut stdout,
        5004,
        OP_INVOKE_INSTANCE_METHOD,
        &[
            ArgValue::ObjectToken(token),
            ArgValue::Str("sumInts"),
            ArgValue::IntArray(vec![1, 2, 3, 4]),
        ],
    );
    assert_eq!(resp.status, STATUS_OK);
    assert_eq!(resp.payload_as_arg_value().unwrap(), ArgValue::Int(10));

    let resp = jvm_roundtrip(
        &mut stdin,
        &mut stdout,
        5005,
        OP_INVOKE_INSTANCE_METHOD,
        &[
            ArgValue::ObjectToken(token),
            ArgValue::Str("joinStrings"),
            ArgValue::StrArray(vec!["a".to_string(), "b".to_string(), "c".to_string()]),
        ],
    );
    assert_eq!(resp.status, STATUS_OK);
    assert_eq!(resp.payload_as_arg_value().unwrap(), ArgValue::Str("a,b,c"));

    // Array fields round-trip through GET_FIELD
    let resp = jvm_roundtrip(
        &mut stdin,
        &mut stdout,
        5006,
        OP_GET_FIELD,
        &[ArgValue::ObjectToken(token), ArgValue::Str("numbers")],
    );
    assert_eq!(resp.status, STATUS_OK);
    assert_eq!(
        resp.payload_as_arg_value().unwrap(),
        ArgValue::IntArray(vec![1, 2, 3])
    );

    let resp = jvm_roundtrip(
        &mut stdin,
        &mut stdout,
        5007,
        OP_GET_FIELD,
        &[ArgValue::ObjectToken(token), ArgValue::Str("words")],
    );
    assert_eq!(resp.status, STATUS_OK);
    assert_eq!(
        resp.payload_as_arg_value().unwrap(),
        ArgValue::StrArray(vec!["a".to_string(), "b".to_string()])
    );

    let resp = jvm_roundtrip(
        &mut stdin,
        &mut stdout,
        5008,
        OP_RELEASE_OBJECT,
        &[ArgValue::ObjectToken(token)],
    );
    assert_eq!(resp.status, STATUS_OK);
}

#[test]
fn test_live_jvm_unregister_callback() {
    let (_session, mut stdin, mut stdout) = LiveJvmSession::start();

    // Register, then unregister; trigger afterwards must fail.
    let resp = jvm_roundtrip(
        &mut stdin,
        &mut stdout,
        6001,
        OP_REGISTER_CALLBACK_PROXY,
        &[ArgValue::Int(6001), ArgValue::Str("java.lang.Runnable")],
    );
    assert_eq!(resp.status, STATUS_OK);

    let resp = jvm_roundtrip(
        &mut stdin,
        &mut stdout,
        6002,
        OP_UNREGISTER_CALLBACK,
        &[ArgValue::Int(6001)],
    );
    assert_eq!(resp.status, STATUS_OK);

    let resp = jvm_roundtrip(
        &mut stdin,
        &mut stdout,
        6003,
        OP_TRIGGER_CALLBACK,
        &[ArgValue::Int(6001), ArgValue::Int(1)],
    );
    assert_eq!(resp.status, STATUS_ERROR);

    // Unregister is idempotent.
    let resp = jvm_roundtrip(
        &mut stdin,
        &mut stdout,
        6004,
        OP_UNREGISTER_CALLBACK,
        &[ArgValue::Int(6001)],
    );
    assert_eq!(resp.status, STATUS_OK);
}
