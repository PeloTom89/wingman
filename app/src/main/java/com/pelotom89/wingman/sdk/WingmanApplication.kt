package com.pelotom89.wingman.sdk

import android.app.Application
import android.content.Context
import com.cySdkyc.clx.Helper
import dji.v5.common.error.IDJIError
import dji.v5.common.register.DJISDKInitEvent
import dji.v5.manager.SDKManager
import dji.v5.manager.interfaces.SDKManagerCallback
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * SDK registration is the async gate every other DJI call sits behind: nothing in
 * [AircraftConnectionRepository], [PerceptionRepository], [VirtualStickController], etc.
 * is safe to touch until [registrationState] reports [SdkRegistrationState.Registered].
 *
 * CRITICAL, discovered via real on-device debugging (2026-07-18): DJI's MSDK V5 classes
 * are wrapped by a commercial Android app-protection/obfuscation runtime
 * (`com.cySdkyc.clx.Helper` — a SecNeo-style tool). The compile-time classes are
 * intentionally-inert protected stubs (bytecode with a dead leading `return` as the first
 * instruction of most methods, including constructors); the REAL classes live encrypted in
 * native libs and are injected into the classloader at runtime by SecNeo's native
 * `JNI_OnLoad`, triggered by the [Helper.install] call below. That call must run in
 * [attachBaseContext], NOT [onCreate], because class loading starts before `onCreate()`.
 *
 * BUT [Helper.install] is necessary, not sufficient. Two build-side conditions gate it,
 * both verified on-device (Moto G Play 2026 / Android 16), see app/build.gradle.kts:
 *   1. The SecNeo native runtime REFUSES to inject in a *debuggable* process (anti-tamper):
 *      its JNI_OnLoad silently bails, `install()` swallows the resulting error, and every
 *      DJI class fails to resolve. The debug build must be built `isDebuggable = false`.
 *      This was the actual blocker behind the long-standing launch crash — install() was
 *      already being called here and still crashed, purely because the build was debuggable.
 *   2. `dji-sdk-v5-aircraft-provided` must be `compileOnly` (DJI's official scope) so the
 *      inert stubs are never packaged into the app's primary dex.
 * Matches the still-open dji-sdk/Mobile-SDK-Android-V5 issue #671 ("Helper.install()
 * Failure") and #1311/#1104 — all reported against debuggable builds.
 */
class WingmanApplication : Application() {

    private val _registrationState = MutableStateFlow<SdkRegistrationState>(SdkRegistrationState.NotStarted)
    val registrationStateFlow: StateFlow<SdkRegistrationState> get() = _registrationState.asStateFlow()

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        Helper.install(this)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        registerWithDji()
    }

    private fun registerWithDji() {
        _registrationState.value = SdkRegistrationState.Registering
        SDKManager.getInstance().init(this, object : SDKManagerCallback {
            override fun onRegisterSuccess() {
                _registrationState.value = SdkRegistrationState.Registered
            }

            override fun onRegisterFailure(error: IDJIError) {
                _registrationState.value = SdkRegistrationState.Failed(error.description())
            }

            override fun onProductConnect(productId: Int) {
                _registrationState.value = SdkRegistrationState.ProductConnected(productId)
            }

            override fun onProductDisconnect(productId: Int) {
                // Registration itself remains valid across a product disconnect (e.g. the
                // RC/aircraft link drops mid-flight) — only the connection state changes.
                // AircraftConnectionRepository is the layer that should react to this by
                // surfacing "telemetry stale" to the flight state machine, not this class.
                _registrationState.value = SdkRegistrationState.Registered
            }

            override fun onProductChanged(productId: Int) = Unit

            override fun onInitProcess(event: DJISDKInitEvent, totalProcess: Int) = Unit

            override fun onDatabaseDownloadProgress(current: Long, total: Long) = Unit
        })
    }

    companion object {
        lateinit var instance: WingmanApplication
            private set
    }
}

sealed class SdkRegistrationState {
    data object NotStarted : SdkRegistrationState()
    data object Registering : SdkRegistrationState()
    data class Failed(val message: String) : SdkRegistrationState()
    data object Registered : SdkRegistrationState()
    data class ProductConnected(val productId: Int) : SdkRegistrationState()
}
