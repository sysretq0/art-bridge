//! Synchronous, poll-friendly native `SOCK_SEQPACKET` IPC client for
//! connecting to the `app_process` hidden API worker at `\0art_bridge`.

pub mod client;
pub mod error;

pub use client::{BridgeClient, DEFAULT_ABSTRACT_SOCKET};
pub use error::ClientError;

// Re-export protocol types for convenience
pub use bridge_proto::{
    encode_callback_event_slice, encode_request_args, encode_response_slice, ArgValue,
    ArgValueOwned, CallbackEvent, CallbackEventOwned, IncomingMessage, ProtoError, Request,
    Response, CALLBACK_HEADER_SIZE, MAX_PACKET_SIZE, MSG_TYPE_CALLBACK_EVENT,
    MSG_TYPE_RPC_RESPONSE, OP_CHECK_SERVICE, OP_ECHO, OP_FORCE_STOP_PACKAGE, OP_GET_FIELD,
    OP_GET_SYSTEM_PROPERTY, OP_INVOKE_INSTANCE_METHOD, OP_INVOKE_SERVICE_METHOD,
    OP_INVOKE_STATIC_METHOD, OP_NEW_INSTANCE, OP_PING, OP_RAW_BINDER_TRANSACT,
    OP_REGISTER_BINDER_CALLBACK, OP_REGISTER_CALLBACK_PROXY, OP_RELEASE_OBJECT, OP_SET_FIELD,
    OP_SET_PROCESS_LIMIT, OP_TRIGGER_CALLBACK, OP_UNREGISTER_CALLBACK, REQ_HEADER_SIZE,
    RESP_HEADER_SIZE, STATUS_ERROR, STATUS_INVALID_ARGUMENTS, STATUS_OK, STATUS_UNKNOWN_OPCODE,
    TAG_BOOL, TAG_BYTES, TAG_CALLBACK_TOKEN, TAG_INT, TAG_INT_ARRAY, TAG_LONG, TAG_NULL,
    TAG_OBJECT_TOKEN, TAG_STR, TAG_STR_ARRAY,
};
