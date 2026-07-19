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
            // REQUIRED for the DJI SDK to work at all. MSDK V5's SecNeo app-protection
            // runtime refuses to unpack its real classes in a debuggable process (anti-
            // tamper): libSdkyclx_clx.so's JNI_OnLoad detects the debuggable flag / attached
            // JDWP and silently bails -- it never registers the native Helper.i() nor injects
            // the decrypted DJI classes, so the app dies with VerifyError (if the inert stubs
            // are packaged) or NoClassDefFoundError (if they aren't) on the first DJI class.
            // VERIFIED on-device 2026-07-18 (Moto G Play 2026, Android 16): with this the
            // SecNeo injection completes and the app runs through Application.onCreate + SDK
            // registration into MainActivity; flipping it back to debuggable reintroduces the
            // crash while changing nothing else. The tradeoff: this debug variant is not
            // Java-debuggable (no breakpoints/JDWP). That is inherent -- you cannot run this
            // SDK under a debugger. Use logging, or a separate throwaway debuggable variant
            // for non-DJI UI work only.
            isDebuggable = false
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

    lint {
        // A non-debuggable debug build triggers AGP's release-style `lintVital`, which
        // otherwise aborts the build on pre-existing, unrelated lint findings (e.g. an
        // androidx Fragment-version advisory). Don't let lint gate packaging here.
        abortOnError = false
    }

    packaging {
        // DJI's aircraft/networkImp artifacts and their native-heavy transitive deps
        // (video codec libs) commonly collide on these; verify the exact conflicting
        // paths against the actual build error and extend as needed.
        resources.excludes += setOf(
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE",
            "META-INF/LICENSE.txt",
            "META-INF/NOTICE",
            "META-INF/NOTICE.txt",
        )
        // Precautionary, NOT the fix for the launch crash. DJI's aircraft AAR manifest
        // explicitly declares android:extractNativeLibs="true", but AGP's manifest merger
        // overrides that to the modern default "false". Setting useLegacyPackaging=true
        // restores DJI's declared intent (native .so extracted to the on-disk lib dir).
        // On-device isolation testing (Moto G Play 2026, Android 16, 2026-07-18) showed the
        // SecNeo class injection succeeds with this either true OR false -- so it is NOT
        // what unblocks launch (isDebuggable=false is). It's kept because several DJI native
        // libs are known to load companions by file path at later stages (registration/
        // flight) that can't be exercised here without a real DJI key + aircraft; honoring
        // the SDK's own manifest declaration is cheap insurance on those untested paths.
        jniLibs.useLegacyPackaging = true
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
    // MUST be `compileOnly` (DJI's official V5 sample scope), NOT `implementation`. These
    // classes (SDKManager, KeyManager, ...) are only compile-time stubs: their shipped
    // bytecode is deliberately inert (every method starts with a dead leading `return`),
    // and at runtime the SecNeo protection layer injects the *real* decrypted classes via
    // `com.cySdkyc.clx.Helper.install()` (called from WingmanApplication.attachBaseContext).
    // If these stubs are packaged (`implementation`) they land in the app's primary dex and
    // ART's verifier rejects SDKManager's constructor ("returning without calling superclass
    // constructor") the instant it's touched -- the crash that blocked launch on Android 16.
    // With `compileOnly` the stubs compile the app but are never packaged, leaving the
    // runtime-injected real classes as the only definition. That injection is performed by
    // the SecNeo native runtime, which REFUSES to run in a debuggable process -- so the
    // load-bearing other half of this fix is `debug { isDebuggable = false }` in the
    // android{} block above. Matches the previously unresolved reports on DJI's GitHub
    // (Mobile-SDK-Android #1311/#1104, -V5 #671), which all reproduce on debug builds.
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

    // --- Testing ---
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
