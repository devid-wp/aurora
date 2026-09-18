//! # Aurora Bridge
//!
//! JNI bridge between the Android app and [`aurora_core`].
//!
//! Compiled as a `cdylib` (`libaurora_bridge.so`) and loaded from Kotlin via
//! `System.loadLibrary("aurora_bridge")`. Each exported symbol maps to a Java
//! native method on the `com.aurora.app.AuroraCore` facade class.
//!
//! Keep this crate thin: all logic belongs in `aurora-core`. This crate only
//! translates between JNI types and core types.

use jni::objects::JObject;
use jni::sys::jstring;
use jni::JNIEnv;

/// Returns the Aurora version as a Java `String`.
///
/// Native method: `com.aurora.app.AuroraCore.version()`.
#[no_mangle]
pub extern "system" fn Java_com_aurora_app_AuroraCore_version<'local>(
    env: JNIEnv<'local>,
    _this: JObject<'local>,
) -> jstring {
    let version = aurora_core::AppInfo::current().version;
    env.new_string(version)
        .expect("failed to allocate the version Java string")
        .into_raw()
}