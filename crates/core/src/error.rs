//! Central error type shared across Aurora.

use core::fmt;

/// Error type for the Aurora core library.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Error {
    /// A feature that has been scaffolded but not implemented yet.
    Unimplemented(&'static str),
}

impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Error::Unimplemented(feature) => write!(f, "`{feature}` is not implemented yet"),
        }
    }
}

impl std::error::Error for Error {}

/// Convenience alias used across the crate.
pub type Result<T> = core::result::Result<T, Error>;