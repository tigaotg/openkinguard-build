plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.tigaotg.openkinguard"
    compileSdk = 37

    // This diagnostic application is already installed on field-test devices.
    // Never use an implicit, newly generated ~/.android/debug.keystore for upgrades.
    // Restore the separately encrypted backup before attempting any APK build.
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("private-signing/openkinguard-s1-diagnostic-debug.keystore")
            storePassword = "android" // Password of Android's original *debug* keystore.
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // One-off field-test build has a separate app ID and ephemeral CI key.
        create("fieldTest") {
            storeFile = rootProject.file("private-signing/openkinguard-field-test.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("fieldTest") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".fieldtest106"
            versionNameSuffix = "-field-test"
            signingConfig = signingConfigs.getByName("fieldTest")
            matchingFallbacks += listOf("debug")
        }
        create("familyRelayTest") {
            storeFile = rootProject.file("private-signing/openkinguard-family-relay-test.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // A new, isolated development certificate. NEVER use it to upgrade the
        // existing healthPreview/S1 identity. Keep the actual keystore private.
        create("isolatedHealthPilot") {
            storeFile = rootProject.file("private-signing/openkinguard-health-pilot-dev.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // Dedicated certificate for a NEW package ID; never signs an update of
        // the installed 0.9.4 healthPilot or earlier diagnostic applications.
        create("isolatedQrPilot") {
            storeFile = rootProject.file("private-signing/openkinguard-qr-pilot-dev.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // Independent new identity: can coexist with 0.9.7 qrPilot on both phones.
        // Reuse the same backed-up PKCS12 and user-owned password for later updates.
        create("isolatedNewPilot") {
            storeFile = rootProject.file("private-signing/openkinguard-new-pilot-v1.p12")
            storePassword = System.getenv("OKG_NEW_PILOT_SIGNING_PASSWORD")
            keyAlias = "openkinguardnewpilot"
            keyPassword = System.getenv("OKG_NEW_PILOT_SIGNING_PASSWORD")
        }
        // V2 is a separate app identity. The v1 signing password was lost;
        // NEVER sign an in-place newPilot upgrade with this different key.
        create("isolatedPilotV2") {
            storeFile = rootProject.file("private-signing/openkinguard-new-pilot-v2.p12")
            storePassword = System.getenv("OKG_PILOT_V2_SIGNING_PASSWORD")
            keyAlias = "openkinguard-pilot-v2"
            keyPassword = System.getenv("OKG_PILOT_V2_SIGNING_PASSWORD")
        }
        // Fresh certificate and app identity when the earlier pilot keys are unavailable.
        // Keep this PKCS12 and its password to sign all future Pilot V3 updates.
        create("isolatedPilotV3") {
            storeFile = rootProject.file("private-signing/openkinguard-pilot-v3.p12")
            storePassword = System.getenv("OKG_PILOT_V3_SIGNING_PASSWORD")
            keyAlias = "openkinguard-pilot-v3"
            keyPassword = System.getenv("OKG_PILOT_V3_SIGNING_PASSWORD")
        }
        // Fresh V4 certificate because the separate V3 signing material is unavailable.
        // The new build has its own package suffix; it cannot overwrite V3's data.
        create("isolatedPilotV4") {
            storeFile = rootProject.file("private-signing/openkinguard-pilot-v4.p12")
            storePassword = System.getenv("OKG_PILOT_V4_SIGNING_PASSWORD")
            keyAlias = "openkinguard-pilot-v4"
            keyPassword = System.getenv("OKG_PILOT_V4_SIGNING_PASSWORD")
        }
    }

    defaultConfig {
        applicationId = "io.github.tigaotg.openkinguard.debug"
        minSdk = 29
        targetSdk = 36
        versionCode = 76
        versionName = "0.10.6-72h-diagnostic"
    }

    buildTypes {
        debug {
            // The earlier S1 debug-signing private key was not preserved.
            // This field-diagnostic build stays separate from the original S1 app.
            applicationIdSuffix = ".s1diagnostic"
        }
        create("familyRelayTest") {
            // Disposable trial app, installed next to BOTH pilotV4 and S1 diagnostic.
            initWith(getByName("debug"))
            applicationIdSuffix = ".familyrelaytest105"
            versionNameSuffix = "-0.10.5-oneoff-test"
            signingConfig = signingConfigs.getByName("familyRelayTest")
            matchingFallbacks += listOf("debug")
        }
        create("healthPreview") {
            // An independently installed test app: preserve the existing S1/0.6.4
            // app, its Android Keystore identity, pairings, and historical data.
            initWith(getByName("debug"))
            applicationIdSuffix = ".healthpreview"
            versionNameSuffix = "-standalone"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("debug")
        }
        create("healthPilot") {
            // A completely separate app ID; the historical 0.8 preview signing
            // key is absent, so upgrading that installed app is not possible here.
            initWith(getByName("debug"))
            applicationIdSuffix = ".healthpilot"
            versionNameSuffix = "-standalone"
            signingConfig = signingConfigs.getByName("isolatedHealthPilot")
            matchingFallbacks += listOf("debug")
        }
        create("qrPilot") {
            // Isolated install: existing healthPilot keeps its data and key.
            initWith(getByName("debug"))
            applicationIdSuffix = ".qrpilot"
            versionNameSuffix = "-standalone"
            signingConfig = signingConfigs.getByName("isolatedQrPilot")
            matchingFallbacks += listOf("debug")
        }
        create("newPilot") {
            // Fresh installation alongside qrPilot; NEVER clear qrPilot's data to install.
            initWith(getByName("debug"))
            applicationIdSuffix = ".newpilot"
            versionNameSuffix = "-new-identity"
            signingConfig = signingConfigs.getByName("isolatedNewPilot")
            matchingFallbacks += listOf("debug")
        }
        create("pilotV2") {
            // Coexists with all earlier test installations and their saved pairings.
            initWith(getByName("debug"))
            applicationIdSuffix = ".pilotv2"
            versionNameSuffix = "-pilot-v2"
            signingConfig = signingConfigs.getByName("isolatedPilotV2")
            matchingFallbacks += listOf("debug")
        }
        create("pilotV3") {
            // Separate install; never overwrites V2 or its encrypted local identities.
            initWith(getByName("debug"))
            applicationIdSuffix = ".pilotv3"
            versionNameSuffix = "-pilot-v3"
            signingConfig = signingConfigs.getByName("isolatedPilotV3")
            matchingFallbacks += listOf("debug")
        }
        create("pilotV4") {
            // Separate installation while retaining every older APK's own local data.
            initWith(getByName("debug"))
            applicationIdSuffix = ".pilotv4"
            versionNameSuffix = "-pilot-v4"
            signingConfig = signingConfigs.getByName("isolatedPilotV4")
            matchingFallbacks += listOf("debug")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:contracts"))
    implementation(project(":core:engine"))
    implementation(project(":adapters:mock"))
    implementation(project(":security:protocol"))

    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core:1.17.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("com.google.zxing:core:3.5.4")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // BSD-3-Clause WebRTC native SDK. Media never uses bespoke JPEG/TCP framing.
    implementation("io.github.webrtc-sdk:android:150.7871.04")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
