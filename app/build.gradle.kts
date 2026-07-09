plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    // Reads app/google-services.json and wires Firebase (FCM) into the build.
    alias(libs.plugins.google.services)
}

android {
    namespace = "com.app.taskade_mobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.app.taskade_mobile"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        // Configure the SDK
        manifestPlaceholders["auth0Domain"] = "@string/com_auth0_domain"
        manifestPlaceholders["auth0Scheme"] = "@string/com_auth0_scheme"

        // Backend host (see ANDROID_INTEGRATION_PRD §10). This is the DEFAULT for
        // every build type (so `release` compiles and ships against production); the
        // `debug` type overrides it with a LAN IP below.
        // NOTE: on a device/emulator "127.0.0.1" means the phone itself, NOT your PC,
        // so it can never reach a backend running on your machine. The Android emulator
        // reaches the host PC's loopback via the special alias 10.0.2.2.
        buildConfigField("String", "API_BASE_URL", "\"https://taskade-backend.onrender.com/\"")
        buildConfigField("String", "WS_VOICE_URL", "\"wss://taskade-backend.onrender.com/ws/voice\"")

        // Failover SECONDARY (Hugging Face). The client prefers the primary above and
        // only switches here when the primary is unreachable (see BackendHostManager).
        // Left empty for `debug` below, so local dev never fails over to production.
        buildConfigField("String", "API_BASE_URL_FALLBACK", "\"https://soge2020-taskade-backend.hf.space\"")
        buildConfigField("String", "WS_VOICE_URL_FALLBACK", "\"wss://soge2020-taskade-backend.hf.space/ws/voice\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            // Physical phone on the same Wi-Fi → this PC's LAN IP (run the backend
            // with `uvicorn main:app --host 0.0.0.0`). Cleartext for this host is
            // whitelisted in res/xml/network_security_config.xml.
            //   • Emulator instead?  use  http://10.0.2.2:8000  /  ws://10.0.2.2:8000/ws/voice
            //   • NOTE: 127.0.0.1 means the PHONE itself, never your PC — don't use it.
            //   • If your PC's Wi-Fi IP changes, update it here AND in network_security_config.xml.
//            buildConfigField("String", "API_BASE_URL", "\"http://192.168.1.3:8000\"")
//            buildConfigField("String", "WS_VOICE_URL", "\"ws://192.168.1.3:8000/ws/voice\"")
//            // No secondary in debug — local dev must never fail over to a production host.
//            buildConfigField("String", "API_BASE_URL_FALLBACK", "\"\"")
//            buildConfigField("String", "WS_VOICE_URL_FALLBACK", "\"\"")
        }
        release {
            // Ship ONLY the architecture real phones use. ONNX Runtime bundles a large
            // native lib per ABI; x86/x86_64 are emulator-only and armeabi-v7a is for
            // extinct 32-bit phones, so dropping the three copies we never run on a real
            // device cuts the release APK from ~120 MB to ~37 MB. Scoped to `release`
            // only — `debug` keeps every ABI so the x86_64 emulator still works.
            ndk {
                abiFilters += "arm64-v8a"
            }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    implementation(libs.auth0)

    // Native "Sign in with Google" via the Credential Manager API.
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)

    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)

    // Backend integration — WebSocket voice channel + REST companion API.
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.retrofit)
    implementation(libs.kotlinx.serialization.json)

    // On-device Silero VAD for barge-in, and device location for profile context.
    implementation(libs.onnxruntime.android)
    implementation(libs.play.services.location)

    // Firebase Cloud Messaging — receives reminder pushes from the backend.
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    // Real-time backdrop blur for the dock's glassmorphism (RenderEffect on API 31+,
    // RenderScript fallback below). See DockView.setupBlur().
    implementation("com.github.Dimezis:BlurView:version-3.2.0")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}