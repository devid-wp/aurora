//! # Aurora Core
//!
//! The pure-Rust core of the Aurora Android music app.
//!
//! This crate is deliberately free of Android, JNI and platform dependencies:
//! it holds the application's logic and shared types, and is consumed by
//! platform-specific crates (see `aurora-bridge`, which exposes it to Android
//! over JNI).
//!
//! Feature modules (player, streaming, downloading, search, backend, auth,
//! playlists, ...) are intentionally **not** implemented yet. They will land
//! as dedicated modules in this crate as development proceeds.

mod app;
mod error;

pub use app::AppInfo;
pub use error::{Error, Result};