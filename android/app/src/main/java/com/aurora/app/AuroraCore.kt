package com.aurora.app

/**
 * JNI facade over the Rust `aurora-bridge` cdylib (`libaurora_bridge.so`).
 *
 * Native symbols follow the `Java_<package>_<Class>_<method>` convention,
 * implemented in `crates/bridge/src/lib.rs`. Keep this class free of logic;
 * everything else lives in the Rust core.
 */
object AuroraCore {
    init {
        System.loadLibrary("aurora_bridge")
    }

    /** @return the Aurora version reported by the Rust core */
    external fun version(): String
}