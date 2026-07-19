package com.pelotom89.wingman.sdk

import android.app.Application
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
 * NOTE: exact SDKManager/SDKManagerCallback method names are per DJI's documented MSDK V5
 * "Chapter 3: Integrate SDK" registration pattern; verify against the pinned SDK version's
 * API reference (developer.dji.com/api-reference-v5) at first build — this file has not
 * been compiled against the real SDK jar.
 */
class WingmanApplication : Application() {

    private val _registrationState = MutableStateFlow<SdkRegistrationState>(SdkRegistrationState.NotStarted)
    val registrationStateFlow: StateFlow<SdkRegistrationState> get() = _registrationState.asStateFlow()

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
