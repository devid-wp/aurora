plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("org.jetbrains.kotlin.kapt")
}

import java.util.Properties

// ── SoundCloud app credentials (local development builds) ─────────────────
// Credentials are supplied OUTSIDE the source tree and are never committed
// to Git. Resolution order (first non-blank wins):
//   1. android/soundcloud.properties  (git-ignored) with keys:
//        SOUNDCLOUD_CLIENT_ID=...
//        SOUNDCLOUD_CLIENT_SECRET=...
//   2. environment variables SOUNDCLOUD_CLIENT_ID / SOUNDCLOUD_CLIENT_SECRET
//   3. Gradle properties -PSOUNDCLOUD_CLIENT_ID=… / -PSOUNDCLOUD_CLIENT_SECRET=…
// When absent the values stay empty and the build still succeeds; the app
// then honestly reports "SoundCloud is unavailable in this build".
// Credential values are never printed anywhere in the build output.
val soundCloudProperties = Properties().apply {
    val file = rootProject.file("soundcloud.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun soundCloudCredential(propKey: String, envKey: String): String =
    (
        soundCloudProperties.getProperty(propKey)
            ?: System.getenv(envKey)
            ?: project.findProperty(propKey)?.toString()
        ).orEmpty().trim()

fun gradleStringLiteral(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val soundCloudClientId = soundCloudCredential("SOUNDCLOUD_CLIENT_ID", "SOUNDCLOUD_CLIENT_ID")
val soundCloudClientSecret = soundCloudCredential("SOUNDCLOUD_CLIENT_SECRET", "SOUNDCLOUD_CLIENT_SECRET")
val soundCloudRedirectUri = soundCloudCredential("redirectUri", "SOUNDCLOUD_REDIRECT_URI")
    .ifBlank { "aurora://soundcloud/callback" }

android {
    namespace = "com.aurora.app"
    compileSdk = 35

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.aurora.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            // One ABI for now; aurora-bridge is built for arm64-v8a.
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        debug {
            buildConfigField("String", "SOUNDCLOUD_CLIENT_ID", gradleStringLiteral(soundCloudClientId))
            buildConfigField("String", "SOUNDCLOUD_CLIENT_SECRET", gradleStringLiteral(soundCloudClientSecret))
            buildConfigField("String", "SOUNDCLOUD_REDIRECT_URI", gradleStringLiteral(soundCloudRedirectUri))
            // Optional Audius developer key (x-api-key header). Empty by default:
            // public catalog reads work without user sign-in.
            buildConfigField("String", "AUDIUS_API_KEY", gradleStringLiteral(soundCloudCredential("AUDIUS_API_KEY", "AUDIUS_API_KEY")))
        }
        release {
            isMinifyEnabled = false
            buildConfigField("String", "SOUNDCLOUD_CLIENT_ID", gradleStringLiteral(soundCloudClientId))
            buildConfigField("String", "SOUNDCLOUD_CLIENT_SECRET", gradleStringLiteral(soundCloudClientSecret))
            buildConfigField("String", "SOUNDCLOUD_REDIRECT_URI", gradleStringLiteral(soundCloudRedirectUri))
            buildConfigField("String", "AUDIUS_API_KEY", gradleStringLiteral(soundCloudCredential("AUDIUS_API_KEY", "AUDIUS_API_KEY")))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.room:room-testing:2.6.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.arch.core:core-testing:2.2.0")
    testImplementation("org.robolectric:robolectric:4.12.2")
}
