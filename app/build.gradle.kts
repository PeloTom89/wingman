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
    compileOnly("com.dji:dji-sdk-v5-aircraft-provided:$djiSdkVersion")
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
