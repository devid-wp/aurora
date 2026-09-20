# Aurora

Aurora is an Android music app with a **Rust core**.

The repository contains the Android application and its Rust foundation:

- a pure-Rust core library,
- a thin JNI bridge that exposes the core to Android,
- a local-library music player with Aurora UI, synchronized players, queue,
  search, settings, and Android media-session integration.

The Android app scans the device MediaStore for local audio, loads real
artwork, and keeps playback in a foreground service so controls continue to
work from notifications, lock screen, Bluetooth/headset actions, and Android
media interfaces. SoundCloud streaming, search, and download are implemented
against the current official SoundCloud API using OAuth (authorization code +
PKCE with client-credentials fallback) with credentials supplied at runtime:
see `app/src/main/java/com/aurora/app/source/SoundCloudSource.kt`. The app
builds and runs without credentials ("SoundCloud is not configured" state);
playlists and a backend remain out of scope.

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
└── android/                      # Android app (Gradle + Kotlin)
    ├── build.gradle.kts
    ├── settings.gradle.kts
    ├── gradle.properties
    ├── gradle/libs.versions.toml
    ├── gradle/wrapper/gradle-wrapper.properties
    └── app/
        └── src/main/
            ├── AndroidManifest.xml
            ├── java/com/aurora/app/
            │   ├── AuroraCore.kt       # JNI facade
            │   ├── MainActivity.kt      # Aurora screens and shared playback UI
            │   ├── PlaybackService.kt   # MediaPlayer + MediaSession state
            │   ├── MusicScanner.kt      # MediaStore library access
            │   ├── ArtworkLoader.kt     # Cached artwork and fallback covers
            │   └── Track.kt             # Local track model
            └── res/                    # theme, strings, and vector icons
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

The app shows the Aurora experience across Home, Search, Library, Settings,
Mini Player, Full Player, and Queue. The foreground playback service remains
the single source of truth for track, progress, queue, shuffle, and repeat.

## SoundCloud setup (real account + online listening)

SoundCloud features (account connect, online search/stream, likes, explicit
downloads) require SoundCloud app credentials **at build time**. Without them
the app still builds and runs, but Settings shows "SoundCloud is unavailable
in this build" and online search stays offline — exactly what the
`configured=false` launch diagnostic (`adb logcat | grep AuroraSC`) reports.

1. Register an application at https://developers.soundcloud.com/docs/api/register-app
   and set its redirect URI to exactly:
   `aurora://soundcloud/callback`
2. Copy your credentials into the git-ignored file `android/soundcloud.properties`:
   ```properties
   clientId=<your client id>
   clientSecret=<your client secret>
   redirectUri=aurora://soundcloud/callback
   ```
   (Environment variables `SOUNDCLOUD_CLIENT_ID` / `SOUNDCLOUD_CLIENT_SECRET`
   or Gradle properties work as alternatives; see `android/app/build.gradle.kts`.)
3. Rebuild and reinstall:
   ```sh
   cd android && ./gradlew :app:assembleDebug
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
4. In Aurora: Settings → Connect SoundCloud → approve in the browser.
   Aurora validates `state`, exchanges the code with PKCE, persists encrypted
   tokens, loads `GET /me`, and shows `Signed in as <username>`.
   Secrets are never logged; only stage booleans and HTTP statuses appear
   under the `AuroraSC` log tag.

Never commit `android/soundcloud.properties` (already git-ignored).

## Scope

Implemented: local MediaStore scanning, artwork loading, search, library
navigation, favorites, recent history, queue display, synchronized mini/full
players, seeking, shuffle/repeat, foreground playback, notifications,
lock-screen/media controls, audio focus, and Rust JNI startup.

Out of scope: streaming, downloading/offline sync, backend/API access, auth,
and playlists.

## License

To be decided.
