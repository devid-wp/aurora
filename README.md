# Aurora

Aurora is an Android music app with a **Rust core**.

This repository currently contains only the minimal foundation for that app:

- a pure-Rust core library,
- a thin JNI bridge that exposes the core to Android,
- a minimal Android app shell that loads the bridge.

The Android shell currently contains a minimal home screen with placeholder
sections for Library, Search and Now Playing. Player, streaming, downloading,
backend, auth and playlists are still out of scope and will be added as
development proceeds.

## Repository layout

```
.
├── Cargo.toml                    # Rust workspace
├── .cargo/config.toml            # Android target linker configuration
├── crates/
│   ├── core/                     # aurora-core — pure Rust logic (no Android deps)
│   │   └── src/
│   │       ├── lib.rs            # crate root, public API
│   │       ├── app.rs            # AppInfo (name, version)
│   │       └── error.rs          # shared Error/Result types
│   └── bridge/                   # aurora-bridge — JNI cdylib loaded by Android
│       └── src/lib.rs            # Java_com_aurora_app_AuroraCore_* exports
└── android/                      # Android app shell (Gradle + Kotlin)
    ├── build.gradle.kts
    ├── settings.gradle.kts
    ├── gradle.properties
    ├── gradle/libs.versions.toml
    ├── gradle/wrapper/gradle-wrapper.properties
    └── app/
        └── src/main/
            ├── AndroidManifest.xml
            ├── java/com/aurora/app/
            │   ├── AuroraCore.kt # JNI facade (loads libaurora_bridge.so)
            │   └── MainActivity.kt
            └── res/              # minimal resources (theme, strings, icon)
```

Design rules:

- **`aurora-core` is pure Rust.** No Android, JNI or platform code in it, so
  it can be tested natively and reused by other platforms later.
- **`aurora-bridge` is thin.** It only translates between JNI and core types;
  all logic lives in `aurora-core`.
- **Feature modules** (player, streaming, downloading, search, backend, auth,
  playlists) will be added as modules inside `aurora-core` when they are
  designed.

## Building the Rust workspace

Requirements: Rust 1.81+.

```sh
cargo build                  # core + bridge (host)
cargo test -p aurora-core    # core unit tests
```

To produce the Android JNI library (`libaurora_bridge.so`):

```sh
cargo build -p aurora-bridge --release --target aarch64-linux-android
```

The `.cargo/config.toml` points at `aarch64-linux-android-clang` (the
NDK-style clang shipped with Termux). On other machines, point it at the
Android NDK's clang or use `cargo ndk`, which configures everything for you.

Then copy the shared library into the app:

```sh
mkdir -p android/app/src/main/jniLibs/arm64-v8a
cp target/aarch64-linux-android/release/libaurora_bridge.so \
   android/app/src/main/jniLibs/arm64-v8a/
```

`android/app/src/main/jniLibs/` is git-ignored; the `.so` is always built from
Rust, never committed.

## Building the Android app

The Gradle/Kotlin shell requires the standard Android toolchain, which is not
available in the current development environment:

- JDK 17
- Android SDK (`local.properties` with `sdk.dir`, or `ANDROID_HOME`)
- Android NDK (only if building the bridge from Gradle later)

Open `android/` in Android Studio and run the `:app` configuration, or from a
terminal with Gradle installed:

```sh
cd android
# generate the wrapper first if gradle-wrapper.jar is missing:
gradle wrapper
./gradlew :app:assembleDebug
```

The app shows a minimal black-and-purple home screen. It loads the Rust core
through JNI as part of startup, proving the foundation is wired end to end.

## Roadmap (planned, not implemented)

- Player
- Streaming
- Downloading (offline)
- Search
- Backend / API client
- Auth
- Playlists
- UI beyond the placeholder activity

## License

To be decided.
