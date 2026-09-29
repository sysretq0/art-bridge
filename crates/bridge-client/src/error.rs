use bridge_proto::ProtoError;
use std::fmt;

#[derive(Debug)]
pub enum ClientError {
    Io(std::io::Error),
    Proto(ProtoError),
    PacketTruncated { received: usize, capacity: usize },
    ConnectionClosed,
    RemoteError { status: u8, message: String },
    Timeout,
    InvalidAbstractName,
    PeerUidMismatch { expected: u32, actual: u32 },
}

impl fmt::Display for ClientError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            ClientError::Io(err) => write!(f, "I/O error: {err}"),
            ClientError::Proto(err) => write!(f, "Protocol error: {err}"),
            ClientError::PacketTruncated { received, capacity } => {
                write!(f, "Received truncated packet: packet size {received} exceeds buffer capacity {capacity}")
            }
            ClientError::ConnectionClosed => write!(f, "Connection closed by remote worker"),
            ClientError::RemoteError { status, message } => {
                write!(f, "Remote worker error (status {status}): {message}")
            }
            ClientError::Timeout => write!(f, "Operation timed out"),
            ClientError::InvalidAbstractName => {
                write!(f, "Abstract socket name too long for sockaddr_un")
            }
            ClientError::PeerUidMismatch { expected, actual } => {
                write!(
                    f,
                    "Untrusted peer: server UID {actual} does not match client UID {expected}"
                )
            }
        }
    }
}

impl std::error::Error for ClientError {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match self {
            ClientError::Io(err) => Some(err),
            ClientError::Proto(err) => Some(err),
            _ => None,
        }
    }
}

impl From<std::io::Error> for ClientError {
    fn from(err: std::io::Error) -> Self {
        ClientError::Io(err)
    }
}

impl From<ProtoError> for ClientError {
    fn from(err: ProtoError) -> Self {
        ClientError::Proto(err)
    }
}
