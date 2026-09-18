//! Application identity and lifecycle scaffolding.
//!
//! [`AppInfo`] is the smallest piece of shared state the rest of Aurora can
//! rely on. Future modules (player, streaming, ...) will receive it (or its
//! members) as construction context.

/// Static information about the Aurora application.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct AppInfo {
    /// Display name of the application.
    pub name: &'static str,
    /// Version of the application, taken from the workspace `Cargo.toml`.
    pub version: &'static str,
}

impl AppInfo {
    /// Returns information for the currently compiled build.
    pub const fn current() -> Self {
        Self {
            name: "Aurora",
            version: env!("CARGO_PKG_VERSION"),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reports_package_version() {
        assert_eq!(AppInfo::current().version, env!("CARGO_PKG_VERSION"));
    }

    #[test]
    fn reports_app_name() {
        assert_eq!(AppInfo::current().name, "Aurora");
    }
}