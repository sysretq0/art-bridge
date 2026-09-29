//! Zero-dependency Little-Endian binary protocol encoder and decoder
//! matching Android Java's `ByteBuffer.order(ByteOrder.LITTLE_ENDIAN)` wire format.
//!
//! ### Wire Specifications
//!
//! **Request Frame:**
//! ```text
//! +------------------+-----------------+-----------------+----------------------------+
//! | req_id: u64 (8B) | opcode: u16(2B) | argc: u16 (2B)  | [tag: u8][arg_payload] ... |
//! +------------------+-----------------+-----------------+----------------------------+
//! ```
//!
//! **RPC Response Frame (msg_type = 0x01):**
//! ```text
//! +---------------+------------------+---------------+-----------------------+---------------------+
//! | 0x01: u8 (1B) | req_id: u64 (8B) | status: u8(1B)| payload_len: u32 (4B) | payload bytes ...   |
//! +---------------+------------------+---------------+-----------------------+---------------------+
//! ```
//!
//! **Async Callback Event Frame (msg_type = 0x02):**
//! ```text
//! +---------------+--------------------+------------------------+----------------+----------------------------+
//! | 0x02: u8 (1B) | callback_id:u32(4B)| method_or_code:u32(4B) | argc: u16 (2B) | [tag: u8][arg_payload] ... |
//! +---------------+--------------------+------------------------+----------------+----------------------------+
//! ```

use std::fmt;

/// Maximum packet size (64 KB) enforced across all frames.
pub const MAX_PACKET_SIZE: usize = 65536;

/// Minimum request frame size (header only: 8 + 2 + 2 = 12 bytes).
pub const REQ_HEADER_SIZE: usize = 12;

/// RPC Response message type discriminator (0x01).
pub const MSG_TYPE_RPC_RESPONSE: u8 = 0x01;

/// Async Callback Event message type discriminator (0x02).
pub const MSG_TYPE_CALLBACK_EVENT: u8 = 0x02;

/// Response frame header size: 1 (msg_type) + 8 (req_id) + 1 (status) + 4 (payload_len) = 14 bytes.
pub const RESP_HEADER_SIZE: usize = 14;

/// Callback frame header size: 1 (msg_type) + 4 (callback_id) + 4 (method_or_code) + 2 (argc) = 11 bytes.
pub const CALLBACK_HEADER_SIZE: usize = 11;

// ----------------------------------------------------------------------------
// ArgValue Tags
// ----------------------------------------------------------------------------

pub const TAG_NULL: u8 = 0x00;
pub const TAG_INT: u8 = 0x01;
pub const TAG_LONG: u8 = 0x02;
pub const TAG_BOOL: u8 = 0x03;
pub const TAG_STR: u8 = 0x04;
pub const TAG_BYTES: u8 = 0x05;
pub const TAG_CALLBACK_TOKEN: u8 = 0x06;
pub const TAG_OBJECT_TOKEN: u8 = 0x07;
pub const TAG_INT_ARRAY: u8 = 0x08;
pub const TAG_STR_ARRAY: u8 = 0x09;

// ----------------------------------------------------------------------------
// Opcodes
// ----------------------------------------------------------------------------

pub const OP_PING: u16 = 0x0001;
pub const OP_GET_SYSTEM_PROPERTY: u16 = 0x0002;
pub const OP_FORCE_STOP_PACKAGE: u16 = 0x0003;
pub const OP_SET_PROCESS_LIMIT: u16 = 0x0004;
pub const OP_CHECK_SERVICE: u16 = 0x0005;

// Universal Reflection & Dynamic Callback opcodes
pub const OP_INVOKE_SERVICE_METHOD: u16 = 0x0010;
pub const OP_INVOKE_STATIC_METHOD: u16 = 0x0011;
pub const OP_RAW_BINDER_TRANSACT: u16 = 0x0012;
pub const OP_REGISTER_CALLBACK_PROXY: u16 = 0x0013;
pub const OP_REGISTER_BINDER_CALLBACK: u16 = 0x0014;
pub const OP_UNREGISTER_CALLBACK: u16 = 0x0015;
pub const OP_NEW_INSTANCE: u16 = 0x0016;
pub const OP_INVOKE_INSTANCE_METHOD: u16 = 0x0017;
pub const OP_GET_FIELD: u16 = 0x0018;
pub const OP_SET_FIELD: u16 = 0x001B;
pub const OP_RELEASE_OBJECT: u16 = 0x0019;
// Test-only simulation opcode (moved from 0x0015 to free it for UNREGISTER_CALLBACK).
pub const OP_TRIGGER_CALLBACK: u16 = 0x001A;

pub const OP_ECHO: u16 = 0x8000;

// ----------------------------------------------------------------------------
// Status Codes
// ----------------------------------------------------------------------------

pub const STATUS_OK: u8 = 0;
pub const STATUS_ERROR: u8 = 1;
pub const STATUS_UNKNOWN_OPCODE: u8 = 2;
pub const STATUS_INVALID_ARGUMENTS: u8 = 3;

// ----------------------------------------------------------------------------
// Error Handling
// ----------------------------------------------------------------------------

#[derive(Debug, PartialEq, Eq)]
pub enum ProtoError {
    BufferTooSmall {
        needed: usize,
        available: usize,
    },
    PacketTooLarge {
        size: usize,
        limit: usize,
    },
    Truncated {
        field: &'static str,
        expected: usize,
        available: usize,
    },
    InvalidUtf8(std::str::Utf8Error),
    InvalidTag(u8),
    UnknownMsgType(u8),
    LengthOverflow,
    TrailingBytes {
        expected: usize,
        actual: usize,
    },
}

impl fmt::Display for ProtoError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            ProtoError::BufferTooSmall { needed, available } => {
                write!(
                    f,
                    "Buffer too small: needed {needed} bytes, but only {available} available"
                )
            }
            ProtoError::PacketTooLarge { size, limit } => {
                write!(
                    f,
                    "Packet size {size} exceeds maximum limit of {limit} bytes"
                )
            }
            ProtoError::Truncated {
                field,
                expected,
                available,
            } => {
                write!(f, "Truncated frame at field '{field}': expected {expected} bytes, but found {available}")
            }
            ProtoError::InvalidUtf8(err) => write!(f, "Invalid UTF-8 sequence in string: {err}"),
            ProtoError::InvalidTag(tag) => write!(f, "Unknown ArgValue tag: 0x{tag:02x}"),
            ProtoError::UnknownMsgType(m) => write!(f, "Unknown incoming message type: 0x{m:02x}"),
            ProtoError::LengthOverflow => {
                write!(f, "Length value exceeds supported integer bounds")
            }
            ProtoError::TrailingBytes { expected, actual } => {
                write!(f, "Frame contains unexpected trailing bytes: expected {expected}, actual {actual}")
            }
        }
    }
}

impl std::error::Error for ProtoError {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match self {
            ProtoError::InvalidUtf8(err) => Some(err),
            _ => None,
        }
    }
}

// ----------------------------------------------------------------------------
// Tagged ArgValue
// ----------------------------------------------------------------------------

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ArgValue<'a> {
    Null,
    Int(i32),
    Long(i64),
    Bool(bool),
    Str(&'a str),
    Bytes(&'a [u8]),
    CallbackToken(u32),
    ObjectToken(u32),
    IntArray(Vec<i32>),
    StrArray(Vec<String>),
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ArgValueOwned {
    Null,
    Int(i32),
    Long(i64),
    Bool(bool),
    Str(String),
    Bytes(Vec<u8>),
    CallbackToken(u32),
    ObjectToken(u32),
    IntArray(Vec<i32>),
    StrArray(Vec<String>),
}

impl<'a> ArgValue<'a> {
    pub fn tag(&self) -> u8 {
        match self {
            ArgValue::Null => TAG_NULL,
            ArgValue::Int(_) => TAG_INT,
            ArgValue::Long(_) => TAG_LONG,
            ArgValue::Bool(_) => TAG_BOOL,
            ArgValue::Str(_) => TAG_STR,
            ArgValue::Bytes(_) => TAG_BYTES,
            ArgValue::CallbackToken(_) => TAG_CALLBACK_TOKEN,
            ArgValue::ObjectToken(_) => TAG_OBJECT_TOKEN,
            ArgValue::IntArray(_) => TAG_INT_ARRAY,
            ArgValue::StrArray(_) => TAG_STR_ARRAY,
        }
    }

    pub fn encoded_len(&self) -> usize {
        1 + match self {
            ArgValue::Null => 0,
            ArgValue::Int(_) => 4,
            ArgValue::Long(_) => 8,
            ArgValue::Bool(_) => 1,
            ArgValue::Str(s) => 2 + s.len(),
            ArgValue::Bytes(b) => 4 + b.len(),
            ArgValue::CallbackToken(_) => 4,
            ArgValue::ObjectToken(_) => 4,
            ArgValue::IntArray(v) => 4 + 4 * v.len(),
            ArgValue::StrArray(v) => 4 + v.iter().map(|s| 2 + s.len()).sum::<usize>(),
        }
    }

    pub fn encode_into(&self, buf: &mut [u8]) -> Result<usize, ProtoError> {
        let needed = self.encoded_len();
        if buf.len() < needed {
            return Err(ProtoError::BufferTooSmall {
                needed,
                available: buf.len(),
            });
        }

        buf[0] = self.tag();
        let mut offset = 1;

        match self {
            ArgValue::Null => {}
            ArgValue::Int(v) => {
                buf[offset..offset + 4].copy_from_slice(&v.to_le_bytes());
                offset += 4;
            }
            ArgValue::Long(v) => {
                buf[offset..offset + 8].copy_from_slice(&v.to_le_bytes());
                offset += 8;
            }
            ArgValue::Bool(v) => {
                buf[offset] = if *v { 1 } else { 0 };
                offset += 1;
            }
            ArgValue::Str(s) => {
                let b = s.as_bytes();
                if b.len() > u16::MAX as usize {
                    return Err(ProtoError::LengthOverflow);
                }
                buf[offset..offset + 2].copy_from_slice(&(b.len() as u16).to_le_bytes());
                offset += 2;
                buf[offset..offset + b.len()].copy_from_slice(b);
                offset += b.len();
            }
            ArgValue::Bytes(b) => {
                if b.len() > u32::MAX as usize {
                    return Err(ProtoError::LengthOverflow);
                }
                buf[offset..offset + 4].copy_from_slice(&(b.len() as u32).to_le_bytes());
                offset += 4;
                buf[offset..offset + b.len()].copy_from_slice(b);
                offset += b.len();
            }
            ArgValue::CallbackToken(tok) => {
                buf[offset..offset + 4].copy_from_slice(&tok.to_le_bytes());
                offset += 4;
            }
            ArgValue::ObjectToken(tok) => {
                buf[offset..offset + 4].copy_from_slice(&tok.to_le_bytes());
                offset += 4;
            }
            ArgValue::IntArray(v) => {
                if v.len() > u32::MAX as usize {
                    return Err(ProtoError::LengthOverflow);
                }
                buf[offset..offset + 4].copy_from_slice(&(v.len() as u32).to_le_bytes());
                offset += 4;
                for x in v.iter() {
                    buf[offset..offset + 4].copy_from_slice(&x.to_le_bytes());
                    offset += 4;
                }
            }
            ArgValue::StrArray(v) => {
                if v.len() > u32::MAX as usize {
                    return Err(ProtoError::LengthOverflow);
                }
                buf[offset..offset + 4].copy_from_slice(&(v.len() as u32).to_le_bytes());
                offset += 4;
                for s in v.iter() {
                    let b = s.as_bytes();
                    if b.len() > u16::MAX as usize {
                        return Err(ProtoError::LengthOverflow);
                    }
                    buf[offset..offset + 2].copy_from_slice(&(b.len() as u16).to_le_bytes());
                    offset += 2;
                    buf[offset..offset + b.len()].copy_from_slice(b);
                    offset += b.len();
                }
            }
        }

        Ok(offset)
    }

    pub fn decode_from(buf: &'a [u8]) -> Result<(Self, usize), ProtoError> {
        if buf.is_empty() {
            return Err(ProtoError::Truncated {
                field: "arg tag",
                expected: 1,
                available: 0,
            });
        }

        let tag = buf[0];
        let mut offset = 1;

        let val = match tag {
            TAG_NULL => ArgValue::Null,
            TAG_INT => {
                if buf.len() < offset + 4 {
                    return Err(ProtoError::Truncated {
                        field: "int payload",
                        expected: offset + 4,
                        available: buf.len(),
                    });
                }
                let v = i32::from_le_bytes(buf[offset..offset + 4].try_into().unwrap());
                offset += 4;
                ArgValue::Int(v)
            }
            TAG_LONG => {
                if buf.len() < offset + 8 {
                    return Err(ProtoError::Truncated {
                        field: "long payload",
                        expected: offset + 8,
                        available: buf.len(),
                    });
                }
                let v = i64::from_le_bytes(buf[offset..offset + 8].try_into().unwrap());
                offset += 8;
                ArgValue::Long(v)
            }
            TAG_BOOL => {
                if buf.len() < offset + 1 {
                    return Err(ProtoError::Truncated {
                        field: "bool payload",
                        expected: offset + 1,
                        available: buf.len(),
                    });
                }
                let v = buf[offset] != 0;
                offset += 1;
                ArgValue::Bool(v)
            }
            TAG_STR => {
                if buf.len() < offset + 2 {
                    return Err(ProtoError::Truncated {
                        field: "str len",
                        expected: offset + 2,
                        available: buf.len(),
                    });
                }
                let len = u16::from_le_bytes(buf[offset..offset + 2].try_into().unwrap()) as usize;
                offset += 2;
                if buf.len() < offset + len {
                    return Err(ProtoError::Truncated {
                        field: "str utf8",
                        expected: offset + len,
                        available: buf.len(),
                    });
                }
                let s = std::str::from_utf8(&buf[offset..offset + len])
                    .map_err(ProtoError::InvalidUtf8)?;
                offset += len;
                ArgValue::Str(s)
            }
            TAG_BYTES => {
                if buf.len() < offset + 4 {
                    return Err(ProtoError::Truncated {
                        field: "bytes len",
                        expected: offset + 4,
                        available: buf.len(),
                    });
                }
                let len = u32::from_le_bytes(buf[offset..offset + 4].try_into().unwrap()) as usize;
                offset += 4;
                if buf.len() < offset + len {
                    return Err(ProtoError::Truncated {
                        field: "bytes data",
                        expected: offset + len,
                        available: buf.len(),
                    });
                }
                let b = &buf[offset..offset + len];
                offset += len;
                ArgValue::Bytes(b)
            }
            TAG_CALLBACK_TOKEN => {
                if buf.len() < offset + 4 {
                    return Err(ProtoError::Truncated {
                        field: "callback token",
                        expected: offset + 4,
                        available: buf.len(),
                    });
                }
                let tok = u32::from_le_bytes(buf[offset..offset + 4].try_into().unwrap());
                offset += 4;
                ArgValue::CallbackToken(tok)
            }
            TAG_OBJECT_TOKEN => {
                if buf.len() < offset + 4 {
                    return Err(ProtoError::Truncated {
                        field: "object token",
                        expected: offset + 4,
                        available: buf.len(),
                    });
                }
                let tok = u32::from_le_bytes(buf[offset..offset + 4].try_into().unwrap());
                offset += 4;
                ArgValue::ObjectToken(tok)
            }
            TAG_INT_ARRAY => {
                if buf.len() < offset + 4 {
                    return Err(ProtoError::Truncated {
                        field: "int array len",
                        expected: offset + 4,
                        available: buf.len(),
                    });
                }
                let len = u32::from_le_bytes(buf[offset..offset + 4].try_into().unwrap()) as usize;
                offset += 4;
                if buf.len() < offset + 4 * len {
                    return Err(ProtoError::Truncated {
                        field: "int array data",
                        expected: offset + 4 * len,
                        available: buf.len(),
                    });
                }
                let mut v = Vec::with_capacity(len);
                for _ in 0..len {
                    v.push(i32::from_le_bytes(buf[offset..offset + 4].try_into().unwrap()));
                    offset += 4;
                }
                ArgValue::IntArray(v)
            }
            TAG_STR_ARRAY => {
                if buf.len() < offset + 4 {
                    return Err(ProtoError::Truncated {
                        field: "str array len",
                        expected: offset + 4,
                        available: buf.len(),
                    });
                }
                let count =
                    u32::from_le_bytes(buf[offset..offset + 4].try_into().unwrap()) as usize;
                offset += 4;
                let remaining = buf.len() - offset;
                if count.saturating_mul(2) > remaining {
                    return Err(ProtoError::Truncated {
                        field: "str array count",
                        expected: count.saturating_mul(2),
                        available: remaining,
                    });
                }
                let mut v = Vec::with_capacity(count);
                for _ in 0..count {
                    if buf.len() < offset + 2 {
                        return Err(ProtoError::Truncated {
                            field: "str array elem len",
                            expected: offset + 2,
                            available: buf.len(),
                        });
                    }
                    let slen =
                        u16::from_le_bytes(buf[offset..offset + 2].try_into().unwrap()) as usize;
                    offset += 2;
                    if buf.len() < offset + slen {
                        return Err(ProtoError::Truncated {
                            field: "str array elem utf8",
                            expected: offset + slen,
                            available: buf.len(),
                        });
                    }
                    let s = std::str::from_utf8(&buf[offset..offset + slen])
                        .map_err(ProtoError::InvalidUtf8)?;
                    offset += slen;
                    v.push(s.to_string());
                }
                ArgValue::StrArray(v)
            }
            other => return Err(ProtoError::InvalidTag(other)),
        };

        Ok((val, offset))
    }

    pub fn to_owned(&self) -> ArgValueOwned {
        match self {
            ArgValue::Null => ArgValueOwned::Null,
            ArgValue::Int(v) => ArgValueOwned::Int(*v),
            ArgValue::Long(v) => ArgValueOwned::Long(*v),
            ArgValue::Bool(v) => ArgValueOwned::Bool(*v),
            ArgValue::Str(s) => ArgValueOwned::Str(s.to_string()),
            ArgValue::Bytes(b) => ArgValueOwned::Bytes(b.to_vec()),
            ArgValue::CallbackToken(t) => ArgValueOwned::CallbackToken(*t),
            ArgValue::ObjectToken(t) => ArgValueOwned::ObjectToken(*t),
            ArgValue::IntArray(v) => ArgValueOwned::IntArray(v.clone()),
            ArgValue::StrArray(v) => ArgValueOwned::StrArray(v.clone()),
        }
    }

    pub fn as_int(&self) -> Option<i32> {
        match self {
            ArgValue::Int(v) => Some(*v),
            ArgValue::Long(v) => Some(*v as i32),
            ArgValue::Bool(b) => Some(if *b { 1 } else { 0 }),
            _ => None,
        }
    }

    pub fn as_long(&self) -> Option<i64> {
        match self {
            ArgValue::Long(v) => Some(*v),
            ArgValue::Int(v) => Some(*v as i64),
            _ => None,
        }
    }

    pub fn as_bool(&self) -> Option<bool> {
        match self {
            ArgValue::Bool(b) => Some(*b),
            ArgValue::Int(v) => Some(*v != 0),
            _ => None,
        }
    }

    pub fn as_str(&self) -> Option<&'a str> {
        match self {
            ArgValue::Str(s) => Some(*s),
            _ => None,
        }
    }

    pub fn as_bytes(&self) -> Option<&'a [u8]> {
        match self {
            ArgValue::Bytes(b) => Some(*b),
            ArgValue::Str(s) => Some(s.as_bytes()),
            _ => None,
        }
    }

    pub fn as_callback_token(&self) -> Option<u32> {
        match self {
            ArgValue::CallbackToken(t) => Some(*t),
            _ => None,
        }
    }

    pub fn as_object_token(&self) -> Option<u32> {
        match self {
            ArgValue::ObjectToken(t) => Some(*t),
            _ => None,
        }
    }

    pub fn as_int_array(&self) -> Option<&[i32]> {
        match self {
            ArgValue::IntArray(v) => Some(v.as_slice()),
            _ => None,
        }
    }

    pub fn as_str_array(&self) -> Option<&[String]> {
        match self {
            ArgValue::StrArray(v) => Some(v.as_slice()),
            _ => None,
        }
    }
}

impl ArgValueOwned {
    pub fn as_borrowed(&self) -> ArgValue<'_> {
        match self {
            ArgValueOwned::Null => ArgValue::Null,
            ArgValueOwned::Int(v) => ArgValue::Int(*v),
            ArgValueOwned::Long(v) => ArgValue::Long(*v),
            ArgValueOwned::Bool(v) => ArgValue::Bool(*v),
            ArgValueOwned::Str(s) => ArgValue::Str(s.as_str()),
            ArgValueOwned::Bytes(b) => ArgValue::Bytes(b.as_slice()),
            ArgValueOwned::CallbackToken(t) => ArgValue::CallbackToken(*t),
            ArgValueOwned::ObjectToken(t) => ArgValue::ObjectToken(*t),
            ArgValueOwned::IntArray(v) => ArgValue::IntArray(v.clone()),
            ArgValueOwned::StrArray(v) => ArgValue::StrArray(v.clone()),
        }
    }

    pub fn as_int(&self) -> Option<i32> {
        match self {
            ArgValueOwned::Int(v) => Some(*v),
            ArgValueOwned::Long(v) => Some(*v as i32),
            ArgValueOwned::Bool(b) => Some(if *b { 1 } else { 0 }),
            _ => None,
        }
    }

    pub fn as_str(&self) -> Option<&str> {
        match self {
            ArgValueOwned::Str(s) => Some(s.as_str()),
            _ => None,
        }
    }

    pub fn as_callback_token(&self) -> Option<u32> {
        match self {
            ArgValueOwned::CallbackToken(t) => Some(*t),
            _ => None,
        }
    }

    pub fn as_object_token(&self) -> Option<u32> {
        match self {
            ArgValueOwned::ObjectToken(t) => Some(*t),
            _ => None,
        }
    }

    pub fn as_int_array(&self) -> Option<&[i32]> {
        match self {
            ArgValueOwned::IntArray(v) => Some(v.as_slice()),
            _ => None,
        }
    }

    pub fn as_str_array(&self) -> Option<&[String]> {
        match self {
            ArgValueOwned::StrArray(v) => Some(v.as_slice()),
            _ => None,
        }
    }
}

// ----------------------------------------------------------------------------
// Request
// ----------------------------------------------------------------------------

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Request<'a> {
    pub req_id: u64,
    pub opcode: u16,
    pub args: Vec<ArgValue<'a>>,
}

impl<'a> Request<'a> {
    pub fn new(req_id: u64, opcode: u16, args: Vec<ArgValue<'a>>) -> Self {
        Self {
            req_id,
            opcode,
            args,
        }
    }

    pub fn ping(req_id: u64) -> Self {
        Self::new(req_id, OP_PING, Vec::new())
    }

    pub fn get_system_property(req_id: u64, key: &'a str, def: Option<&'a str>) -> Self {
        let mut args = Vec::with_capacity(2);
        args.push(ArgValue::Str(key));
        if let Some(d) = def {
            args.push(ArgValue::Str(d));
        }
        Self::new(req_id, OP_GET_SYSTEM_PROPERTY, args)
    }

    pub fn invoke_static_method(
        req_id: u64,
        class_name: &'a str,
        method_name: &'a str,
        method_args: &[ArgValue<'a>],
    ) -> Self {
        let mut args = Vec::with_capacity(2 + method_args.len());
        args.push(ArgValue::Str(class_name));
        args.push(ArgValue::Str(method_name));
        args.extend_from_slice(method_args);
        Self::new(req_id, OP_INVOKE_STATIC_METHOD, args)
    }

    pub fn invoke_service_method(
        req_id: u64,
        service_name: &'a str,
        aidl_interface: &'a str,
        method_name: &'a str,
        method_args: &[ArgValue<'a>],
    ) -> Self {
        let mut args = Vec::with_capacity(3 + method_args.len());
        args.push(ArgValue::Str(service_name));
        args.push(ArgValue::Str(aidl_interface));
        args.push(ArgValue::Str(method_name));
        args.extend_from_slice(method_args);
        Self::new(req_id, OP_INVOKE_SERVICE_METHOD, args)
    }

    pub fn raw_binder_transact(
        req_id: u64,
        service_name: &'a str,
        code: i32,
        transact_args: &[ArgValue<'a>],
    ) -> Self {
        let mut args = Vec::with_capacity(2 + transact_args.len());
        args.push(ArgValue::Str(service_name));
        args.push(ArgValue::Int(code));
        args.extend_from_slice(transact_args);
        Self::new(req_id, OP_RAW_BINDER_TRANSACT, args)
    }

    pub fn register_callback_proxy(req_id: u64, callback_id: u32, interface_name: &'a str) -> Self {
        Self::new(
            req_id,
            OP_REGISTER_CALLBACK_PROXY,
            vec![
                ArgValue::Int(callback_id as i32),
                ArgValue::Str(interface_name),
            ],
        )
    }

    pub fn register_binder_callback(req_id: u64, callback_id: u32, descriptor: &'a str) -> Self {
        Self::new(
            req_id,
            OP_REGISTER_BINDER_CALLBACK,
            vec![ArgValue::Int(callback_id as i32), ArgValue::Str(descriptor)],
        )
    }

    pub fn unregister_callback(req_id: u64, callback_id: u32) -> Self {
        Self::new(
            req_id,
            OP_UNREGISTER_CALLBACK,
            vec![ArgValue::Int(callback_id as i32)],
        )
    }

    pub fn new_instance(
        req_id: u64,
        class_name: &'a str,
        ctor_args: &[ArgValue<'a>],
    ) -> Self {
        let mut args = Vec::with_capacity(1 + ctor_args.len());
        args.push(ArgValue::Str(class_name));
        args.extend_from_slice(ctor_args);
        Self::new(req_id, OP_NEW_INSTANCE, args)
    }

    pub fn invoke_instance_method(
        req_id: u64,
        object_token: u32,
        method_name: &'a str,
        method_args: &[ArgValue<'a>],
    ) -> Self {
        let mut args = Vec::with_capacity(2 + method_args.len());
        args.push(ArgValue::ObjectToken(object_token));
        args.push(ArgValue::Str(method_name));
        args.extend_from_slice(method_args);
        Self::new(req_id, OP_INVOKE_INSTANCE_METHOD, args)
    }

    pub fn get_field(req_id: u64, target: ArgValue<'a>, field_name: &'a str) -> Self {
        Self::new(req_id, OP_GET_FIELD, vec![target, ArgValue::Str(field_name)])
    }

    pub fn set_field(
        req_id: u64,
        target: ArgValue<'a>,
        field_name: &'a str,
        value: ArgValue<'a>,
    ) -> Self {
        Self::new(
            req_id,
            OP_SET_FIELD,
            vec![target, ArgValue::Str(field_name), value],
        )
    }

    pub fn release_object(req_id: u64, object_token: u32) -> Self {
        Self::new(
            req_id,
            OP_RELEASE_OBJECT,
            vec![ArgValue::ObjectToken(object_token)],
        )
    }

    pub fn encoded_len(&self) -> usize {
        let mut len = REQ_HEADER_SIZE;
        for a in &self.args {
            len += a.encoded_len();
        }
        len
    }

    pub fn encode_into(&self, buf: &mut [u8]) -> Result<usize, ProtoError> {
        let needed = self.encoded_len();
        if needed > MAX_PACKET_SIZE {
            return Err(ProtoError::PacketTooLarge {
                size: needed,
                limit: MAX_PACKET_SIZE,
            });
        }
        if buf.len() < needed {
            return Err(ProtoError::BufferTooSmall {
                needed,
                available: buf.len(),
            });
        }
        if self.args.len() > u16::MAX as usize {
            return Err(ProtoError::LengthOverflow);
        }

        let mut offset = 0;
        buf[offset..offset + 8].copy_from_slice(&self.req_id.to_le_bytes());
        offset += 8;
        buf[offset..offset + 2].copy_from_slice(&self.opcode.to_le_bytes());
        offset += 2;
        buf[offset..offset + 2].copy_from_slice(&(self.args.len() as u16).to_le_bytes());
        offset += 2;

        for arg in &self.args {
            let written = arg.encode_into(&mut buf[offset..])?;
            offset += written;
        }

        Ok(offset)
    }

    pub fn decode_from(buf: &'a [u8]) -> Result<Self, ProtoError> {
        if buf.len() > MAX_PACKET_SIZE {
            return Err(ProtoError::PacketTooLarge {
                size: buf.len(),
                limit: MAX_PACKET_SIZE,
            });
        }
        if buf.len() < REQ_HEADER_SIZE {
            return Err(ProtoError::Truncated {
                field: "request header",
                expected: REQ_HEADER_SIZE,
                available: buf.len(),
            });
        }

        let mut offset = 0;
        let req_id = u64::from_le_bytes(buf[offset..offset + 8].try_into().unwrap());
        offset += 8;
        let opcode = u16::from_le_bytes(buf[offset..offset + 2].try_into().unwrap());
        offset += 2;
        let argc = u16::from_le_bytes(buf[offset..offset + 2].try_into().unwrap()) as usize;
        offset += 2;

        let mut args = Vec::with_capacity(argc);
        for _ in 0..argc {
            let (arg, n) = ArgValue::decode_from(&buf[offset..])?;
            args.push(arg);
            offset += n;
        }

        if offset != buf.len() {
            return Err(ProtoError::TrailingBytes {
                expected: offset,
                actual: buf.len(),
            });
        }

        Ok(Self {
            req_id,
            opcode,
            args,
        })
    }
}

// ----------------------------------------------------------------------------
// Response
// ----------------------------------------------------------------------------

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Response<'a> {
    pub req_id: u64,
    pub status: u8,
    pub payload: &'a [u8],
}

impl<'a> Response<'a> {
    pub fn new(req_id: u64, status: u8, payload: &'a [u8]) -> Self {
        Self {
            req_id,
            status,
            payload,
        }
    }

    pub fn ok(req_id: u64, payload: &'a [u8]) -> Self {
        Self::new(req_id, STATUS_OK, payload)
    }

    pub fn is_ok(&self) -> bool {
        self.status == STATUS_OK
    }

    /// Attempts to decode payload as an ArgValue.
    pub fn payload_as_arg_value(&self) -> Result<ArgValue<'a>, ProtoError> {
        let (val, consumed) = ArgValue::decode_from(self.payload)?;
        if consumed != self.payload.len() {
            return Err(ProtoError::TrailingBytes {
                expected: consumed,
                actual: self.payload.len(),
            });
        }
        Ok(val)
    }

    /// Transparently extracts text: if payload starts with TAG_STR, decodes inner string;
    /// otherwise decodes payload as UTF-8 directly.
    pub fn payload_as_str(&self) -> Result<&'a str, ProtoError> {
        if self.status != STATUS_OK {
            return std::str::from_utf8(self.payload).map_err(ProtoError::InvalidUtf8);
        }
        if !self.payload.is_empty() && self.payload[0] == TAG_STR && self.payload.len() >= 3 {
            let len = u16::from_le_bytes(self.payload[1..3].try_into().unwrap()) as usize;
            if self.payload.len() == 3 + len {
                return std::str::from_utf8(&self.payload[3..3 + len])
                    .map_err(ProtoError::InvalidUtf8);
            }
        }
        std::str::from_utf8(self.payload).map_err(ProtoError::InvalidUtf8)
    }

    pub fn encoded_len(&self) -> usize {
        RESP_HEADER_SIZE + self.payload.len()
    }

    pub fn encode_into(&self, buf: &mut [u8]) -> Result<usize, ProtoError> {
        let needed = self.encoded_len();
        if needed > MAX_PACKET_SIZE {
            return Err(ProtoError::PacketTooLarge {
                size: needed,
                limit: MAX_PACKET_SIZE,
            });
        }
        if buf.len() < needed {
            return Err(ProtoError::BufferTooSmall {
                needed,
                available: buf.len(),
            });
        }
        if self.payload.len() > u32::MAX as usize {
            return Err(ProtoError::LengthOverflow);
        }

        buf[0] = MSG_TYPE_RPC_RESPONSE; // 0x01
        buf[1..9].copy_from_slice(&self.req_id.to_le_bytes());
        buf[9] = self.status;
        buf[10..14].copy_from_slice(&(self.payload.len() as u32).to_le_bytes());
        buf[14..14 + self.payload.len()].copy_from_slice(self.payload);

        Ok(needed)
    }

    pub fn decode_from(buf: &'a [u8]) -> Result<Self, ProtoError> {
        if buf.len() > MAX_PACKET_SIZE {
            return Err(ProtoError::PacketTooLarge {
                size: buf.len(),
                limit: MAX_PACKET_SIZE,
            });
        }
        if buf.len() < RESP_HEADER_SIZE {
            return Err(ProtoError::Truncated {
                field: "response header",
                expected: RESP_HEADER_SIZE,
                available: buf.len(),
            });
        }
        if buf[0] != MSG_TYPE_RPC_RESPONSE {
            return Err(ProtoError::UnknownMsgType(buf[0]));
        }

        let req_id = u64::from_le_bytes(buf[1..9].try_into().unwrap());
        let status = buf[9];
        let payload_len = u32::from_le_bytes(buf[10..14].try_into().unwrap()) as usize;

        if buf.len() < 14 + payload_len {
            return Err(ProtoError::Truncated {
                field: "response payload",
                expected: 14 + payload_len,
                available: buf.len(),
            });
        }

        let payload = &buf[14..14 + payload_len];
        if 14 + payload_len != buf.len() {
            return Err(ProtoError::TrailingBytes {
                expected: 14 + payload_len,
                actual: buf.len(),
            });
        }

        Ok(Self {
            req_id,
            status,
            payload,
        })
    }
}

// ----------------------------------------------------------------------------
// Async Callback Event Frame (msg_type = 0x02)
// ----------------------------------------------------------------------------

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CallbackEvent<'a> {
    pub callback_id: u32,
    pub method_or_code: u32,
    pub args: Vec<ArgValue<'a>>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CallbackEventOwned {
    pub callback_id: u32,
    pub method_or_code: u32,
    pub args: Vec<ArgValueOwned>,
}

impl<'a> CallbackEvent<'a> {
    pub fn new(callback_id: u32, method_or_code: u32, args: Vec<ArgValue<'a>>) -> Self {
        Self {
            callback_id,
            method_or_code,
            args,
        }
    }

    pub fn encoded_len(&self) -> usize {
        let mut len = CALLBACK_HEADER_SIZE;
        for a in &self.args {
            len += a.encoded_len();
        }
        len
    }

    pub fn encode_into(&self, buf: &mut [u8]) -> Result<usize, ProtoError> {
        let needed = self.encoded_len();
        if needed > MAX_PACKET_SIZE {
            return Err(ProtoError::PacketTooLarge {
                size: needed,
                limit: MAX_PACKET_SIZE,
            });
        }
        if buf.len() < needed {
            return Err(ProtoError::BufferTooSmall {
                needed,
                available: buf.len(),
            });
        }

        buf[0] = MSG_TYPE_CALLBACK_EVENT; // 0x02
        buf[1..5].copy_from_slice(&self.callback_id.to_le_bytes());
        buf[5..9].copy_from_slice(&self.method_or_code.to_le_bytes());
        buf[9..11].copy_from_slice(&(self.args.len() as u16).to_le_bytes());

        let mut offset = CALLBACK_HEADER_SIZE;
        for arg in &self.args {
            let n = arg.encode_into(&mut buf[offset..])?;
            offset += n;
        }

        Ok(offset)
    }

    pub fn decode_from(buf: &'a [u8]) -> Result<Self, ProtoError> {
        if buf.len() > MAX_PACKET_SIZE {
            return Err(ProtoError::PacketTooLarge {
                size: buf.len(),
                limit: MAX_PACKET_SIZE,
            });
        }
        if buf.len() < CALLBACK_HEADER_SIZE {
            return Err(ProtoError::Truncated {
                field: "callback header",
                expected: CALLBACK_HEADER_SIZE,
                available: buf.len(),
            });
        }
        if buf[0] != MSG_TYPE_CALLBACK_EVENT {
            return Err(ProtoError::UnknownMsgType(buf[0]));
        }

        let callback_id = u32::from_le_bytes(buf[1..5].try_into().unwrap());
        let method_or_code = u32::from_le_bytes(buf[5..9].try_into().unwrap());
        let argc = u16::from_le_bytes(buf[9..11].try_into().unwrap()) as usize;

        let mut offset = CALLBACK_HEADER_SIZE;
        let mut args = Vec::with_capacity(argc);
        for _ in 0..argc {
            let (arg, n) = ArgValue::decode_from(&buf[offset..])?;
            args.push(arg);
            offset += n;
        }

        if offset != buf.len() {
            return Err(ProtoError::TrailingBytes {
                expected: offset,
                actual: buf.len(),
            });
        }

        Ok(Self {
            callback_id,
            method_or_code,
            args,
        })
    }

    pub fn to_owned(&self) -> CallbackEventOwned {
        CallbackEventOwned {
            callback_id: self.callback_id,
            method_or_code: self.method_or_code,
            args: self.args.iter().map(|a| a.to_owned()).collect(),
        }
    }
}

// ----------------------------------------------------------------------------
// Demultiplexed Incoming Message
// ----------------------------------------------------------------------------

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum IncomingMessage<'a> {
    Response(Response<'a>),
    Callback(CallbackEvent<'a>),
}

impl<'a> IncomingMessage<'a> {
    pub fn decode_from(buf: &'a [u8]) -> Result<Self, ProtoError> {
        if buf.is_empty() {
            return Err(ProtoError::Truncated {
                field: "msg_type",
                expected: 1,
                available: 0,
            });
        }
        match buf[0] {
            MSG_TYPE_RPC_RESPONSE => Response::decode_from(buf).map(IncomingMessage::Response),
            MSG_TYPE_CALLBACK_EVENT => {
                CallbackEvent::decode_from(buf).map(IncomingMessage::Callback)
            }
            other => Err(ProtoError::UnknownMsgType(other)),
        }
    }
}

// ----------------------------------------------------------------------------
// Zero-Heap Slice Helpers
// ----------------------------------------------------------------------------

pub fn encode_request_args(
    req_id: u64,
    opcode: u16,
    args: &[ArgValue<'_>],
    buf: &mut [u8],
) -> Result<usize, ProtoError> {
    let mut needed = REQ_HEADER_SIZE;
    for a in args {
        needed += a.encoded_len();
    }
    if needed > MAX_PACKET_SIZE {
        return Err(ProtoError::PacketTooLarge {
            size: needed,
            limit: MAX_PACKET_SIZE,
        });
    }
    if buf.len() < needed {
        return Err(ProtoError::BufferTooSmall {
            needed,
            available: buf.len(),
        });
    }

    buf[0..8].copy_from_slice(&req_id.to_le_bytes());
    buf[8..10].copy_from_slice(&opcode.to_le_bytes());
    buf[10..12].copy_from_slice(&(args.len() as u16).to_le_bytes());

    let mut offset = REQ_HEADER_SIZE;
    for arg in args {
        let n = arg.encode_into(&mut buf[offset..])?;
        offset += n;
    }

    Ok(offset)
}

pub fn encode_response_slice(
    req_id: u64,
    status: u8,
    payload: &[u8],
    buf: &mut [u8],
) -> Result<usize, ProtoError> {
    let needed = RESP_HEADER_SIZE + payload.len();
    if needed > MAX_PACKET_SIZE {
        return Err(ProtoError::PacketTooLarge {
            size: needed,
            limit: MAX_PACKET_SIZE,
        });
    }
    if buf.len() < needed {
        return Err(ProtoError::BufferTooSmall {
            needed,
            available: buf.len(),
        });
    }

    buf[0] = MSG_TYPE_RPC_RESPONSE;
    buf[1..9].copy_from_slice(&req_id.to_le_bytes());
    buf[9] = status;
    buf[10..14].copy_from_slice(&(payload.len() as u32).to_le_bytes());
    buf[14..14 + payload.len()].copy_from_slice(payload);

    Ok(needed)
}

pub fn encode_callback_event_slice(
    callback_id: u32,
    method_or_code: u32,
    args: &[ArgValue<'_>],
    buf: &mut [u8],
) -> Result<usize, ProtoError> {
    if args.len() > u16::MAX as usize {
        return Err(ProtoError::LengthOverflow);
    }
    let mut needed = CALLBACK_HEADER_SIZE;
    for a in args {
        needed += a.encoded_len();
    }
    if needed > MAX_PACKET_SIZE {
        return Err(ProtoError::PacketTooLarge {
            size: needed,
            limit: MAX_PACKET_SIZE,
        });
    }
    if buf.len() < needed {
        return Err(ProtoError::BufferTooSmall {
            needed,
            available: buf.len(),
        });
    }
    buf[0] = MSG_TYPE_CALLBACK_EVENT;
    buf[1..5].copy_from_slice(&callback_id.to_le_bytes());
    buf[5..9].copy_from_slice(&method_or_code.to_le_bytes());
    buf[9..11].copy_from_slice(&(args.len() as u16).to_le_bytes());
    let mut offset = CALLBACK_HEADER_SIZE;
    for arg in args {
        let n = arg.encode_into(&mut buf[offset..])?;
        offset += n;
    }
    Ok(offset)
}

// ----------------------------------------------------------------------------
// Unit Tests
// ----------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_arg_values_roundtrip() {
        let int_arr = vec![1, -2, 300, i32::MAX, i32::MIN];
        let str_arr = vec!["alpha".to_string(), "".to_string(), "ß-utf8".to_string()];
        let args = vec![
            ArgValue::Null,
            ArgValue::Int(-12345),
            ArgValue::Long(9876543210),
            ArgValue::Bool(true),
            ArgValue::Str("hello world"),
            ArgValue::Bytes(&[0xDE, 0xAD, 0xBE, 0xEF]),
            ArgValue::CallbackToken(42),
            ArgValue::ObjectToken(777),
            ArgValue::IntArray(int_arr),
            ArgValue::StrArray(str_arr),
        ];

        let mut buf = vec![0u8; 1024];
        let mut offset = 0;
        for a in &args {
            let n = a.encode_into(&mut buf[offset..]).unwrap();
            offset += n;
        }

        let mut decode_offset = 0;
        for expected in &args {
            let (decoded, n) = ArgValue::decode_from(&buf[decode_offset..offset]).unwrap();
            assert_eq!(&decoded, expected);
            decode_offset += n;
        }
        assert_eq!(decode_offset, offset);
    }

    #[test]
    fn test_request_typed_roundtrip() {
        let req = Request::new(
            123,
            OP_INVOKE_STATIC_METHOD,
            vec![
                ArgValue::Str("android.os.Process"),
                ArgValue::Str("myPid"),
                ArgValue::Int(100),
            ],
        );
        let mut buf = vec![0u8; 256];
        let n = req.encode_into(&mut buf).unwrap();

        let decoded = Request::decode_from(&buf[..n]).unwrap();
        assert_eq!(decoded.req_id, 123);
        assert_eq!(decoded.opcode, OP_INVOKE_STATIC_METHOD);
        assert_eq!(decoded.args.len(), 3);
        assert_eq!(decoded.args[0], ArgValue::Str("android.os.Process"));
        assert_eq!(decoded.args[1], ArgValue::Str("myPid"));
        assert_eq!(decoded.args[2], ArgValue::Int(100));
    }

    #[test]
    fn test_response_typed_roundtrip() {
        let resp = Response::ok(456, b"result_bytes");
        let mut buf = vec![0u8; 256];
        let n = resp.encode_into(&mut buf).unwrap();

        let msg = IncomingMessage::decode_from(&buf[..n]).unwrap();
        match msg {
            IncomingMessage::Response(r) => {
                assert_eq!(r.req_id, 456);
                assert_eq!(r.status, STATUS_OK);
                assert_eq!(r.payload, b"result_bytes");
            }
            IncomingMessage::Callback(_) => panic!("Expected Response"),
        }
    }

    #[test]
    fn test_callback_event_roundtrip() {
        let cb = CallbackEvent::new(
            77,
            1001,
            vec![
                ArgValue::Int(1),
                ArgValue::Str("onForegroundActivitiesChanged"),
                ArgValue::Bool(false),
            ],
        );
        let mut buf = vec![0u8; 256];
        let n = cb.encode_into(&mut buf).unwrap();

        let msg = IncomingMessage::decode_from(&buf[..n]).unwrap();
        match msg {
            IncomingMessage::Callback(c) => {
                assert_eq!(c.callback_id, 77);
                assert_eq!(c.method_or_code, 1001);
                assert_eq!(c.args.len(), 3);
                assert_eq!(c.args[0], ArgValue::Int(1));
                assert_eq!(c.args[1], ArgValue::Str("onForegroundActivitiesChanged"));
                assert_eq!(c.args[2], ArgValue::Bool(false));
            }
            IncomingMessage::Response(_) => panic!("Expected Callback"),
        }
    }
}
