use crate::error::ClientError;
use bridge_proto::{
    encode_request_args, ArgValue, ArgValueOwned, CallbackEventOwned, IncomingMessage, Response,
    MAX_PACKET_SIZE, MSG_TYPE_RPC_RESPONSE, OP_CHECK_SERVICE, OP_FORCE_STOP_PACKAGE,
    OP_GET_FIELD, OP_GET_SYSTEM_PROPERTY, OP_INVOKE_INSTANCE_METHOD, OP_INVOKE_SERVICE_METHOD,
    OP_INVOKE_STATIC_METHOD, OP_NEW_INSTANCE, OP_PING, OP_RAW_BINDER_TRANSACT,
    OP_REGISTER_BINDER_CALLBACK, OP_REGISTER_CALLBACK_PROXY, OP_RELEASE_OBJECT, OP_SET_FIELD,
    OP_SET_PROCESS_LIMIT, OP_TRIGGER_CALLBACK, OP_UNREGISTER_CALLBACK,
};
use std::collections::{HashMap, VecDeque};
use std::os::fd::{AsFd, AsRawFd, BorrowedFd, FromRawFd, OwnedFd, RawFd};
use std::time::{Duration, Instant};

/// Default abstract namespace socket name for the ART bridge daemon.
pub const DEFAULT_ABSTRACT_SOCKET: &[u8] = b"art_bridge";

/// Socket buffer size configured on connection (256 KB).
const SOCKET_BUFFER_SIZE: libc::c_int = 256 * 1024;

/// Default RPC deadline so an unparseable packet can never hang a caller forever.
const DEFAULT_REQUEST_TIMEOUT: Duration = Duration::from_secs(30);

/// Bound on queued out-of-order responses / callback events (evict oldest past this).
const MAX_QUEUED_MESSAGES: usize = 1024;

/// Synchronous, poll-friendly native client connecting to the Android app_process
/// hidden API worker via SOCK_SEQPACKET over the Linux abstract UNIX socket namespace.
///
/// Supports bidirectional multiplexing: demultiplexes incoming RPC Responses (0x01)
/// and Async Callback Events (0x02).
pub struct BridgeClient {
    fd: OwnedFd,
    next_id: u64,
    rx_buf: Box<[u8; MAX_PACKET_SIZE]>,
    tx_buf: Box<[u8; MAX_PACKET_SIZE]>,
    callback_events: VecDeque<CallbackEventOwned>,
    pending_responses: HashMap<u64, (u8, Vec<u8>)>,
    request_timeout: Option<Duration>,
}

impl BridgeClient {
    /// Connect to the default abstract socket `\0art_bridge`.
    pub fn connect() -> Result<Self, ClientError> {
        Self::connect_to(DEFAULT_ABSTRACT_SOCKET)
    }

    /// Connect to a specified abstract UNIX socket name using SOCK_SEQPACKET.
    pub fn connect_to(abstract_name: &[u8]) -> Result<Self, ClientError> {
        let fd =
            unsafe { libc::socket(libc::AF_UNIX, libc::SOCK_SEQPACKET | libc::SOCK_CLOEXEC, 0) };
        if fd < 0 {
            return Err(ClientError::Io(std::io::Error::last_os_error()));
        }
        let owned_fd = unsafe { OwnedFd::from_raw_fd(fd) };

        // Configure socket send and receive buffers
        unsafe {
            libc::setsockopt(
                fd,
                libc::SOL_SOCKET,
                libc::SO_RCVBUF,
                (&SOCKET_BUFFER_SIZE as *const libc::c_int).cast(),
                std::mem::size_of::<libc::c_int>() as libc::socklen_t,
            );
            libc::setsockopt(
                fd,
                libc::SOL_SOCKET,
                libc::SO_SNDBUF,
                (&SOCKET_BUFFER_SIZE as *const libc::c_int).cast(),
                std::mem::size_of::<libc::c_int>() as libc::socklen_t,
            );
        }

        let mut addr: libc::sockaddr_un = unsafe { std::mem::zeroed() };
        addr.sun_family = libc::AF_UNIX as libc::sa_family_t;

        if abstract_name.len() + 1 > addr.sun_path.len() {
            return Err(ClientError::InvalidAbstractName);
        }

        unsafe {
            std::ptr::copy_nonoverlapping(
                abstract_name.as_ptr(),
                addr.sun_path.as_mut_ptr().add(1),
                abstract_name.len(),
            );
        }

        let addr_len =
            (std::mem::size_of::<libc::sa_family_t>() + 1 + abstract_name.len()) as libc::socklen_t;

        let ret =
            unsafe { libc::connect(fd, (&addr as *const libc::sockaddr_un).cast(), addr_len) };
        if ret < 0 {
            return Err(ClientError::Io(std::io::Error::last_os_error()));
        }

        Self::from_connected_fd(owned_fd)
    }

    /// Adopt an existing connected file descriptor (useful for testing and custom transports).
    /// Verifies SO_PEERCRED before adopting; rejects peers with a foreign UID.
    pub fn from_connected_fd(fd: OwnedFd) -> Result<Self, ClientError> {
        verify_peer_credentials(fd.as_raw_fd())?;
        Ok(Self {
            fd,
            next_id: 1,
            rx_buf: vec![0u8; MAX_PACKET_SIZE]
                .into_boxed_slice()
                .try_into()
                .unwrap(),
            tx_buf: vec![0u8; MAX_PACKET_SIZE]
                .into_boxed_slice()
                .try_into()
                .unwrap(),
            callback_events: VecDeque::new(),
            pending_responses: HashMap::new(),
            request_timeout: Some(DEFAULT_REQUEST_TIMEOUT),
        })
    }

    /// Override the RPC deadline.
    pub fn set_request_timeout(&mut self, timeout: Duration) {
        self.set_request_timeout_opt(Some(timeout));
    }

    /// Set the RPC deadline as an option (`None` disables it).
    pub fn set_request_timeout_opt(&mut self, timeout: Option<Duration>) {
        self.request_timeout = timeout;
    }

    /// Current RPC deadline.
    pub fn request_timeout(&self) -> Option<Duration> {
        self.request_timeout
    }

    /// Poll for socket readability with a timeout in milliseconds.
    pub fn poll_readable(&self, timeout_ms: i32) -> Result<bool, ClientError> {
        let mut pfd = libc::pollfd {
            fd: self.as_raw_fd(),
            events: libc::POLLIN,
            revents: 0,
        };

        loop {
            let ret = unsafe { libc::poll(&mut pfd, 1, timeout_ms) };
            if ret > 0 {
                return Ok(
                    (pfd.revents & (libc::POLLIN | libc::POLLHUP | libc::POLLERR)) != 0,
                );
            } else if ret == 0 {
                return Ok(false);
            } else {
                let err = std::io::Error::last_os_error();
                if err.kind() == std::io::ErrorKind::Interrupted {
                    continue;
                }
                return Err(ClientError::Io(err));
            }
        }
    }

    /// Encode and send a request frame over the SOCK_SEQPACKET connection.
    pub fn send_request(&mut self, opcode: u16, args: &[ArgValue<'_>]) -> Result<u64, ClientError> {
        let req_id = self.next_id;
        self.next_id = self.next_id.wrapping_add(1);

        let written = encode_request_args(req_id, opcode, args, &mut *self.tx_buf)
            .map_err(ClientError::Proto)?;

        let raw_fd = self.as_raw_fd();
        let sent = unsafe {
            libc::send(
                raw_fd,
                self.tx_buf.as_ptr().cast(),
                written,
                libc::MSG_NOSIGNAL,
            )
        };

        if sent < 0 {
            return Err(ClientError::Io(std::io::Error::last_os_error()));
        }
        if sent as usize != written {
            return Err(ClientError::Io(std::io::Error::new(
                std::io::ErrorKind::WriteZero,
                "Partial atomic write on SOCK_SEQPACKET",
            )));
        }

        Ok(req_id)
    }

    /// Receive the next raw datagram from the socket with MSG_TRUNC detection.
    fn recv_raw_packet(&mut self) -> Result<usize, ClientError> {
        let raw_fd = self.as_raw_fd();
        loop {
            let ret = unsafe {
                libc::recv(
                    raw_fd,
                    self.rx_buf.as_mut_ptr().cast(),
                    self.rx_buf.len(),
                    libc::MSG_TRUNC,
                )
            };
            if ret < 0 {
                let err = std::io::Error::last_os_error();
                if err.kind() == std::io::ErrorKind::Interrupted {
                    continue;
                }
                return Err(ClientError::Io(err));
            }
            if ret == 0 {
                return Err(ClientError::ConnectionClosed);
            }
            let n = ret as usize;
            if n > self.rx_buf.len() {
                return Err(ClientError::PacketTruncated {
                    received: n,
                    capacity: self.rx_buf.len(),
                });
            }
            return Ok(n);
        }
    }

    /// Cap a caller-supplied poll timeout by the RPC deadline (`None` disables it).
    fn effective_timeout_ms(&self, timeout_ms: i32) -> i32 {
        match self.request_timeout {
            None => timeout_ms,
            Some(d) => {
                let cap: i64 = d.as_millis().try_into().unwrap_or(i32::MAX as i64);
                let cap = cap.clamp(0, i32::MAX as i64) as i32;
                if timeout_ms < 0 { cap } else { timeout_ms.min(cap) }
            }
        }
    }

    /// Stash an out-of-order response, evicting an arbitrary entry past the bound.
    fn stash_response(
        map: &mut HashMap<u64, (u8, Vec<u8>)>,
        req_id: u64,
        status: u8,
        payload: &[u8],
    ) {
        if map.len() >= MAX_QUEUED_MESSAGES {
            if let Some(k) = map.keys().next().copied() {
                map.remove(&k);
            }
        }
        map.insert(req_id, (status, payload.to_vec()));
    }

    /// Queue a callback event, dropping the oldest past the bound.
    fn push_callback(queue: &mut VecDeque<CallbackEventOwned>, cb: CallbackEventOwned) {
        if queue.len() >= MAX_QUEUED_MESSAGES {
            queue.pop_front();
        }
        queue.push_back(cb);
    }

    /// Synchronously receive and demultiplex responses:
    /// - Async callback events (0x02) are pushed to the callback queue.
    /// - Responses (0x01) for other requests are queued in pending_responses.
    /// - Returns when response matching expected_id arrives or the request timeout expires.
    pub fn recv_response(&mut self, expected_id: u64) -> Result<Response<'_>, ClientError> {
        // Check if response was already received while processing other messages
        if let Some((status, payload)) = self.pending_responses.remove(&expected_id) {
            let total = 14 + payload.len();
            self.rx_buf[0] = MSG_TYPE_RPC_RESPONSE;
            self.rx_buf[1..9].copy_from_slice(&expected_id.to_le_bytes());
            self.rx_buf[9] = status;
            self.rx_buf[10..14].copy_from_slice(&(payload.len() as u32).to_le_bytes());
            self.rx_buf[14..total].copy_from_slice(&payload);
            return Response::decode_from(&self.rx_buf[..total]).map_err(ClientError::Proto);
        }

        let deadline = self.request_timeout.map(|t| Instant::now() + t);
        loop {
            if let Some(dl) = deadline {
                let now = Instant::now();
                if now >= dl {
                    return Err(ClientError::Timeout);
                }
                let remaining = dl.saturating_duration_since(now);
                let remaining_ms: i64 = remaining.as_millis().try_into().unwrap_or(i32::MAX as i64);
                let timeout_ms = remaining_ms.clamp(0, i32::MAX as i64) as i32;
                if !self.poll_readable(timeout_ms)? {
                    return Err(ClientError::Timeout);
                }
            }
            let n = self.recv_raw_packet()?;
            let msg =
                IncomingMessage::decode_from(&self.rx_buf[..n]).map_err(ClientError::Proto)?;

            match msg {
                IncomingMessage::Callback(cb) => {
                    Self::push_callback(&mut self.callback_events, cb.to_owned());
                }
                IncomingMessage::Response(resp) => {
                    if resp.req_id == expected_id {
                        // Re-decode from self.rx_buf
                        return Response::decode_from(&self.rx_buf[..n])
                            .map_err(ClientError::Proto);
                    } else {
                        Self::stash_response(
                            &mut self.pending_responses,
                            resp.req_id,
                            resp.status,
                            resp.payload,
                        );
                    }
                }
            }
        }
    }

    /// Dispatch request and immediately await matching response.
    pub fn call(
        &mut self,
        opcode: u16,
        args: &[ArgValue<'_>],
    ) -> Result<Response<'_>, ClientError> {
        let id = self.send_request(opcode, args)?;
        let resp = self.recv_response(id)?;
        if !resp.is_ok() {
            let msg = resp
                .payload_as_str()
                .unwrap_or("<invalid utf-8>")
                .to_string();
            return Err(ClientError::RemoteError {
                status: resp.status,
                message: msg,
            });
        }
        Ok(resp)
    }

    // ------------------------------------------------------------------------
    // Callback Demultiplexing APIs
    // ------------------------------------------------------------------------

    /// Poll for an incoming callback event (or drain queued ones).
    pub fn poll_callback_event(
        &mut self,
        timeout_ms: i32,
    ) -> Result<Option<CallbackEventOwned>, ClientError> {
        if let Some(event) = self.callback_events.pop_front() {
            return Ok(Some(event));
        }

        if !self.poll_readable(self.effective_timeout_ms(timeout_ms))? {
            return Ok(None);
        }

        let n = self.recv_raw_packet()?;
        let msg = IncomingMessage::decode_from(&self.rx_buf[..n]).map_err(ClientError::Proto)?;

        match msg {
            IncomingMessage::Callback(cb) => Ok(Some(cb.to_owned())),
            IncomingMessage::Response(resp) => {
                Self::stash_response(
                    &mut self.pending_responses,
                    resp.req_id,
                    resp.status,
                    resp.payload,
                );
                Ok(None)
            }
        }
    }

    /// Block until an asynchronous callback event arrives (bounded by the RPC deadline).
    pub fn recv_callback_event(&mut self) -> Result<CallbackEventOwned, ClientError> {
        if let Some(event) = self.callback_events.pop_front() {
            return Ok(event);
        }

        let deadline = self.request_timeout.map(|t| Instant::now() + t);
        loop {
            if let Some(dl) = deadline {
                let now = Instant::now();
                if now >= dl {
                    return Err(ClientError::Timeout);
                }
                let remaining = dl.saturating_duration_since(now);
                let remaining_ms: i64 =
                    remaining.as_millis().try_into().unwrap_or(i32::MAX as i64);
                let timeout_ms = remaining_ms.clamp(0, i32::MAX as i64) as i32;
                if !self.poll_readable(timeout_ms)? {
                    return Err(ClientError::Timeout);
                }
            }
            let n = self.recv_raw_packet()?;
            let msg =
                IncomingMessage::decode_from(&self.rx_buf[..n]).map_err(ClientError::Proto)?;

            match msg {
                IncomingMessage::Callback(cb) => return Ok(cb.to_owned()),
                IncomingMessage::Response(resp) => {
                    Self::stash_response(
                        &mut self.pending_responses,
                        resp.req_id,
                        resp.status,
                        resp.payload,
                    );
                }
            }
        }
    }

    // ------------------------------------------------------------------------
    // Universal Dynamic Reflection & Callback APIs
    // ------------------------------------------------------------------------

    /// Invokes any static hidden method dynamically.
    pub fn invoke_static_method(
        &mut self,
        class_name: &str,
        method_name: &str,
        method_args: &[ArgValue<'_>],
    ) -> Result<ArgValueOwned, ClientError> {
        let mut args = Vec::with_capacity(2 + method_args.len());
        args.push(ArgValue::Str(class_name));
        args.push(ArgValue::Str(method_name));
        args.extend_from_slice(method_args);

        let id = self.send_request(OP_INVOKE_STATIC_METHOD, &args)?;
        let resp = self.recv_response(id)?;
        if !resp.is_ok() {
            return Err(ClientError::RemoteError {
                status: resp.status,
                message: resp.payload_as_str().unwrap_or("").to_string(),
            });
        }
        let val = resp.payload_as_arg_value().map_err(ClientError::Proto)?;
        Ok(val.to_owned())
    }

    /// Invokes any method on an AIDL service interface proxy dynamically.
    pub fn invoke_service_method(
        &mut self,
        service_name: &str,
        aidl_interface: &str,
        method_name: &str,
        method_args: &[ArgValue<'_>],
    ) -> Result<ArgValueOwned, ClientError> {
        let mut args = Vec::with_capacity(3 + method_args.len());
        args.push(ArgValue::Str(service_name));
        args.push(ArgValue::Str(aidl_interface));
        args.push(ArgValue::Str(method_name));
        args.extend_from_slice(method_args);

        let id = self.send_request(OP_INVOKE_SERVICE_METHOD, &args)?;
        let resp = self.recv_response(id)?;
        if !resp.is_ok() {
            return Err(ClientError::RemoteError {
                status: resp.status,
                message: resp.payload_as_str().unwrap_or("").to_string(),
            });
        }
        let val = resp.payload_as_arg_value().map_err(ClientError::Proto)?;
        Ok(val.to_owned())
    }

    /// Executes a raw Binder transaction via Parcel marshalling.
    pub fn raw_binder_transact(
        &mut self,
        service_name: &str,
        code: i32,
        transact_args: &[ArgValue<'_>],
    ) -> Result<Vec<u8>, ClientError> {
        let mut args = Vec::with_capacity(2 + transact_args.len());
        args.push(ArgValue::Str(service_name));
        args.push(ArgValue::Int(code));
        args.extend_from_slice(transact_args);

        let id = self.send_request(OP_RAW_BINDER_TRANSACT, &args)?;
        let resp = self.recv_response(id)?;
        if !resp.is_ok() {
            return Err(ClientError::RemoteError {
                status: resp.status,
                message: resp.payload_as_str().unwrap_or("").to_string(),
            });
        }
        let val = resp.payload_as_arg_value().map_err(ClientError::Proto)?;
        Ok(val.as_bytes().unwrap_or(&[]).to_vec())
    }

    /// Registers a dynamic java.lang.reflect.Proxy for any interface.
    pub fn register_callback_proxy(
        &mut self,
        callback_id: u32,
        interface_name: &str,
    ) -> Result<u32, ClientError> {
        let args = [
            ArgValue::Int(callback_id as i32),
            ArgValue::Str(interface_name),
        ];
        let id = self.send_request(OP_REGISTER_CALLBACK_PROXY, &args)?;
        let resp = self.recv_response(id)?;
        if !resp.is_ok() {
            return Err(ClientError::RemoteError {
                status: resp.status,
                message: resp.payload_as_str().unwrap_or("").to_string(),
            });
        }
        let val = resp.payload_as_arg_value().map_err(ClientError::Proto)?;
        Ok(val.as_callback_token().unwrap_or(callback_id))
    }

    /// Registers a dynamic Binder onTransact stub.
    pub fn register_binder_callback(
        &mut self,
        callback_id: u32,
        descriptor: &str,
    ) -> Result<u32, ClientError> {
        let args = [ArgValue::Int(callback_id as i32), ArgValue::Str(descriptor)];
        let id = self.send_request(OP_REGISTER_BINDER_CALLBACK, &args)?;
        let resp = self.recv_response(id)?;
        if !resp.is_ok() {
            return Err(ClientError::RemoteError {
                status: resp.status,
                message: resp.payload_as_str().unwrap_or("").to_string(),
            });
        }
        let val = resp.payload_as_arg_value().map_err(ClientError::Proto)?;
        Ok(val.as_callback_token().unwrap_or(callback_id))
    }

    /// Releases a registered callback proxy or stub so it can be garbage-collected.
    pub fn unregister_callback(&mut self, callback_id: u32) -> Result<(), ClientError> {
        let args = [ArgValue::Int(callback_id as i32)];
        let id = self.send_request(OP_UNREGISTER_CALLBACK, &args)?;
        let resp = self.recv_response(id)?;
        if !resp.is_ok() {
            return Err(ClientError::RemoteError {
                status: resp.status,
                message: resp.payload_as_str().unwrap_or("").to_string(),
            });
        }
        Ok(())
    }

    /// Fires a registered callback (for test simulation).
    pub fn trigger_callback(
        &mut self,
        callback_id: u32,
        method_or_code: u32,
        args: &[ArgValue<'_>],
    ) -> Result<(), ClientError> {
        let mut full_args = Vec::with_capacity(2 + args.len());
        full_args.push(ArgValue::Int(callback_id as i32));
        full_args.push(ArgValue::Int(method_or_code as i32));
        full_args.extend_from_slice(args);

        let id = self.send_request(OP_TRIGGER_CALLBACK, &full_args)?;
        let resp = self.recv_response(id)?;
        if !resp.is_ok() {
            return Err(ClientError::RemoteError {
                status: resp.status,
                message: resp.payload_as_str().unwrap_or("").to_string(),
            });
        }
        Ok(())
    }

    /// Instantiates an arbitrary object and returns its ObjectToken.
    pub fn new_instance(
        &mut self,
        class_name: &str,
        ctor_args: &[ArgValue<'_>],
    ) -> Result<ArgValueOwned, ClientError> {
        let mut args = Vec::with_capacity(1 + ctor_args.len());
        args.push(ArgValue::Str(class_name));
        args.extend_from_slice(ctor_args);
        let id = self.send_request(OP_NEW_INSTANCE, &args)?;
        let resp = self.recv_response(id)?;
        if !resp.is_ok() {
            return Err(ClientError::RemoteError {
                status: resp.status,
                message: resp.payload_as_str().unwrap_or("").to_string(),
            });
        }
        let val = resp.payload_as_arg_value().map_err(ClientError::Proto)?;
        Ok(val.to_owned())
    }

    /// Invokes a method on a live object referenced by ObjectToken.
    pub fn invoke_instance_method(
        &mut self,
        object_token: u32,
        method_name: &str,
        method_args: &[ArgValue<'_>],
    ) -> Result<ArgValueOwned, ClientError> {
        let mut args = Vec::with_capacity(2 + method_args.len());
        args.push(ArgValue::ObjectToken(object_token));
        args.push(ArgValue::Str(method_name));
        args.extend_from_slice(method_args);
        let id = self.send_request(OP_INVOKE_INSTANCE_METHOD, &args)?;
        let resp = self.recv_response(id)?;
        if !resp.is_ok() {
            return Err(ClientError::RemoteError {
                status: resp.status,
                message: resp.payload_as_str().unwrap_or("").to_string(),
            });
        }
        let val = resp.payload_as_arg_value().map_err(ClientError::Proto)?;
        Ok(val.to_owned())
    }

    /// Reads an instance (ObjectToken) or static (class-name Str) field.
    pub fn get_field(
        &mut self,
        target: ArgValue<'_>,
        field_name: &str,
    ) -> Result<ArgValueOwned, ClientError> {
        let args = [target, ArgValue::Str(field_name)];
        let id = self.send_request(OP_GET_FIELD, &args)?;
        let resp = self.recv_response(id)?;
        if !resp.is_ok() {
            return Err(ClientError::RemoteError {
                status: resp.status,
                message: resp.payload_as_str().unwrap_or("").to_string(),
            });
        }
        let val = resp.payload_as_arg_value().map_err(ClientError::Proto)?;
        Ok(val.to_owned())
    }

    /// Writes an instance (ObjectToken) or static (class-name Str) field.
    pub fn set_field(
        &mut self,
        target: ArgValue<'_>,
        field_name: &str,
        value: ArgValue<'_>,
    ) -> Result<(), ClientError> {
        let args = [target, ArgValue::Str(field_name), value];
        let id = self.send_request(OP_SET_FIELD, &args)?;
        let resp = self.recv_response(id)?;
        if !resp.is_ok() {
            return Err(ClientError::RemoteError {
                status: resp.status,
                message: resp.payload_as_str().unwrap_or("").to_string(),
            });
        }
        Ok(())
    }

    /// Removes an ObjectToken from the server registry.
    pub fn release_object(&mut self, object_token: u32) -> Result<(), ClientError> {
        let args = [ArgValue::ObjectToken(object_token)];
        let id = self.send_request(OP_RELEASE_OBJECT, &args)?;
        let resp = self.recv_response(id)?;
        if !resp.is_ok() {
            return Err(ClientError::RemoteError {
                status: resp.status,
                message: resp.payload_as_str().unwrap_or("").to_string(),
            });
        }
        Ok(())
    }

    // ------------------------------------------------------------------------
    // Convenience RPC Methods
    // ------------------------------------------------------------------------

    pub fn ping(&mut self) -> Result<String, ClientError> {
        let id = self.send_request(OP_PING, &[])?;
        let resp = self.recv_response(id)?;
        if !resp.is_ok() {
            return Err(ClientError::RemoteError {
                status: resp.status,
                message: resp.payload_as_str().unwrap_or("").to_string(),
            });
        }
        Ok(resp
            .payload_as_str()
            .map_err(ClientError::Proto)?
            .to_string())
    }

    pub fn get_system_property(
        &mut self,
        key: &str,
        def: Option<&str>,
    ) -> Result<String, ClientError> {
        let mut args = Vec::with_capacity(2);
        args.push(ArgValue::Str(key));
        if let Some(d) = def {
            args.push(ArgValue::Str(d));
        }
        let id = self.send_request(OP_GET_SYSTEM_PROPERTY, &args)?;
        let resp = self.recv_response(id)?;
        if !resp.is_ok() {
            return Err(ClientError::RemoteError {
                status: resp.status,
                message: resp.payload_as_str().unwrap_or("").to_string(),
            });
        }
        Ok(resp
            .payload_as_str()
            .map_err(ClientError::Proto)?
            .to_string())
    }

    pub fn force_stop_package(
        &mut self,
        pkg: &str,
        user_id: Option<i32>,
    ) -> Result<(), ClientError> {
        let uid = user_id.unwrap_or(0);
        let args = [ArgValue::Str(pkg), ArgValue::Int(uid)];
        let id = self.send_request(OP_FORCE_STOP_PACKAGE, &args)?;
        let resp = self.recv_response(id)?;
        if !resp.is_ok() {
            return Err(ClientError::RemoteError {
                status: resp.status,
                message: resp.payload_as_str().unwrap_or("").to_string(),
            });
        }
        Ok(())
    }

    pub fn set_process_limit(&mut self, max: i32) -> Result<(), ClientError> {
        let args = [ArgValue::Int(max)];
        let id = self.send_request(OP_SET_PROCESS_LIMIT, &args)?;
        let resp = self.recv_response(id)?;
        if !resp.is_ok() {
            return Err(ClientError::RemoteError {
                status: resp.status,
                message: resp.payload_as_str().unwrap_or("").to_string(),
            });
        }
        Ok(())
    }

    pub fn check_service(&mut self, service_name: &str) -> Result<bool, ClientError> {
        let args = [ArgValue::Str(service_name)];
        let id = self.send_request(OP_CHECK_SERVICE, &args)?;
        let resp = self.recv_response(id)?;
        if !resp.is_ok() {
            return Err(ClientError::RemoteError {
                status: resp.status,
                message: resp.payload_as_str().unwrap_or("").to_string(),
            });
        }
        let text = resp.payload_as_str().map_err(ClientError::Proto)?;
        Ok(text == "EXISTS")
    }

    pub fn echo(&mut self, val: &ArgValue<'_>) -> Result<ArgValueOwned, ClientError> {
        let id = self.send_request(bridge_proto::OP_ECHO, std::slice::from_ref(val))?;
        let resp = self.recv_response(id)?;
        if !resp.is_ok() {
            return Err(ClientError::RemoteError {
                status: resp.status,
                message: resp.payload_as_str().unwrap_or("").to_string(),
            });
        }
        let res = resp.payload_as_arg_value().map_err(ClientError::Proto)?;
        Ok(res.to_owned())
    }
}

impl AsRawFd for BridgeClient {
    fn as_raw_fd(&self) -> RawFd {
        self.fd.as_raw_fd()
    }
}

impl AsFd for BridgeClient {
    fn as_fd(&self) -> BorrowedFd<'_> {
        self.fd.as_fd()
    }
}

/// Verify the connected peer's UID matches our own UID (no implicit root bypass).
fn verify_peer_credentials(fd: RawFd) -> Result<(), ClientError> {
    let mut ucred: libc::ucred = unsafe { std::mem::zeroed() };
    let mut len = std::mem::size_of::<libc::ucred>() as libc::socklen_t;
    let ret = unsafe {
        libc::getsockopt(
            fd,
            libc::SOL_SOCKET,
            libc::SO_PEERCRED,
            (&mut ucred as *mut libc::ucred).cast(),
            &mut len,
        )
    };
    if ret != 0 {
        return Err(ClientError::Io(std::io::Error::last_os_error()));
    }
    let my_uid = unsafe { libc::getuid() };
    if ucred.uid != my_uid {
        return Err(ClientError::PeerUidMismatch {
            expected: my_uid,
            actual: ucred.uid,
        });
    }
    Ok(())
}
