plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("org.jetbrains.kotlin.kapt")
}

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
            buildConfigField("String", "SOUNDCLOUD_CLIENT_ID", "\"\"")
            buildConfigField("String", "SOUNDCLOUD_CLIENT_SECRET", "\"\"")
            buildConfigField("String", "SOUNDCLOUD_REDIRECT_URI", "\"aurora://soundcloud/callback\"")
        }
        release {
            isMinifyEnabled = false
            buildConfigField("String", "SOUNDCLOUD_CLIENT_ID", "\"\"")
            buildConfigField("String", "SOUNDCLOUD_CLIENT_SECRET", "\"\"")
            buildConfigField("String", "SOUNDCLOUD_REDIRECT_URI", "\"aurora://soundcloud/callback\"")
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