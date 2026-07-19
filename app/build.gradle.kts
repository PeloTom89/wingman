import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// DJI App Key is a per-developer credential tied to this app's applicationId in the
// DJI Developer console (developer.dji.com) — it must match applicationId below exactly,
// or SDK registration fails at runtime with no obvious error. Kept out of source control:
// copy `local.properties.example` to `local.properties` and fill in DJI_API_KEY there.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "com.pelotom89.wingman"
    // MSDK V5 requires a real device (USB accessory + aircraft radio link) — it will not
    // run meaningfully in the emulator. minSdk 26 matches DJI's V5 sample project baseline;
    // verify against the exact MSDK version's own requirements before first build.
    compileSdk = 34

    defaultConfig {
        applicationId = "com.pelotom89.wingman"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        manifestPlaceholders["djiApiKey"] = localProperties.getProperty("DJI_API_KEY", "")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        // DJI's aircraft/networkImp artifacts and several native-heavy transitive deps
        // (video codec, OpenCV/TFLite if added) commonly collide on these; verify the
        // exact conflicting paths against the actual build error and extend as needed.
        resources.excludes += setOf(
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE",
            "META-INF/LICENSE.txt",
            "META-INF/NOTICE",
            "META-INF/NOTICE.txt",
        )
    }
}

dependencies {
    // --- DJI Mobile SDK V5 ---
    // Pin to a concrete release ≥ 5.13.0 (first version with Mini 4 Pro support).
    // Verify the current version at https://developer.dji.com/mobile-sdk/downloads
    // before first build — DJI ships frequent point releases and this number will drift.
    val djiSdkVersion = "5.18.0"
    // "-provided" is NOT Gradle `compileOnly` scope despite the name suggesting it -- it's
    // where DJI actually puts the real Java/Kotlin classes (SDKManager, KeyManager,
    // VirtualStickManager, PerceptionManager, etc; confirmed via `javap` against the real
    // jar). `dji-sdk-v5-aircraft` itself is almost entirely native .so files (a 61MB
    // libdjisdk_jni.so) with a near-empty classes.jar. Marking "-provided" as `compileOnly`
    // compiles fine but crashes at runtime with NoClassDefFoundError the moment
    // SDKManager.getInstance() is touched -- confirmed via a real on-device install.
    //
    // Fixing that scope surfaces a SEPARATE, still-open problem: dji.v5.manager.SDKManager
    // itself fails ART's bytecode verifier at runtime ("Constructor returning without
    // calling superclass constructor") on Android 16 (API 36) -- confirmed on a real Moto
    // G Play 2026 device, reproduced identically across AGP 8.1.4/8.6.0 and MSDK 5.17.0/
    // 5.18.0, so it isn't a local toolchain or version-pin issue. Matches multiple open,
    // unresolved reports on DJI's own GitHub (dji-sdk/Mobile-SDK-Android issues #1311 and
    // #1104, dji-sdk/Mobile-SDK-Android-V5 issue #671) describing the same failure on
    // recent Android versions. This currently blocks the app from launching at all on
    // Android 16 devices -- see README's "Known blocking issue" section before assuming
    // any DJI SDK code in this repo runs. Try an older Android device (12-14) if you have
    // one; that's the next real diagnostic step, not further Gradle config changes.
    implementation("com.dji:dji-sdk-v5-aircraft-provided:$djiSdkVersion")
    implementation("com.dji:dji-sdk-v5-aircraft:$djiSdkVersion")
    runtimeOnly("com.dji:dji-sdk-v5-networkImp:$djiSdkVersion")

    // --- Jetpack Compose ---
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")

    // --- Core AndroidX ---
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // --- Location (subject GPS proxy, see location/SubjectLocationProvider.kt) ---
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // --- On-device vision tracking ---
    // MediaPipe Tasks Vision provides the mobile-optimized, GPU-delegated object detector
    // used at reduced cadence in vision/SubjectDetector.kt (see plan: detect-then-track,
    // not full per-frame detection, to avoid contending with live video decode).
    implementation("com.google.mediapipe:tasks-vision:0.10.14")

    // --- Testing ---
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
