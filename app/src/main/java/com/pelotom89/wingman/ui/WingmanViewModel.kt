package com.pelotom89.wingman.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pelotom89.wingman.core.FlightLogger
import com.pelotom89.wingman.flightcontrol.FlightState
import com.pelotom89.wingman.flightcontrol.FlightStateMachine
import com.pelotom89.wingman.flightcontrol.LatLon
import com.pelotom89.wingman.flightcontrol.ManualOverrideGate
import com.pelotom89.wingman.location.SubjectLocationProvider
import com.pelotom89.wingman.sdk.AircraftConnectionRepository
import com.pelotom89.wingman.sdk.FlightSafetyActionsController
import com.pelotom89.wingman.sdk.GimbalController
import com.pelotom89.wingman.sdk.PerceptionRepository
import com.pelotom89.wingman.sdk.SdkRegistrationState
import com.pelotom89.wingman.sdk.VirtualStickController
import com.pelotom89.wingman.sdk.WingmanApplication
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch

/** How long the FC link may stay down after ProductConnect before the UI calls it stalled
 *  (on healthy runs it was observed coming up within a few seconds of onProductConnect). */
private const val FC_LINK_STALL_TIMEOUT_MS = 20_000L

/** Cadence/bound for the read-only FC probe while waiting (see
 *  AircraftConnectionRepository.probeFlightControllerLink for what it can and can't do). */
private const val FC_PROBE_INTERVAL_MS = 5_000L
private const val FC_PROBE_MAX_ATTEMPTS = 24

/**
 * Composition root wiring sdk/ + location/ + flightcontrol/ together, and the single
 * object the UI layer observes. Kept intentionally thin: every actual decision (what
 * command to send, when to switch modes) lives in FlightStateMachine, not here — this
 * class only assembles the dependency graph and exposes it as Compose-friendly state.
 *
 * GPS-only: vision/ was removed (see README) — the aircraft camera preview is still shown
 * to the operator (sdk/CameraPreviewScreen) purely for situational awareness, but nothing
 * here reads its frames anymore. Following is driven entirely by the controller phone's
 * own GPS (location/SubjectLocationProvider) and aimed by [GimbalController], not vision.
 */
class WingmanViewModel(application: Application) : AndroidViewModel(application) {

    private val aircraftConnectionRepository = AircraftConnectionRepository()
    private val perceptionRepository = PerceptionRepository()
    private val subjectLocationProvider = SubjectLocationProvider(application)
    private val gimbalController = GimbalController()

    private val commandFlowHolder = MutableStateFlow(com.pelotom89.wingman.sdk.VirtualStickCommand.ZERO)
    // Single shared instance -- see ManualOverrideGate's header comment for the real bug
    // (two disconnected flows) this replaced.
    private val overrideActiveHolder = MutableStateFlow(false)
    private val virtualStickController = VirtualStickController(commandFlowHolder, overrideActiveHolder)
    private val manualOverrideGate = ManualOverrideGate(overrideActiveHolder, virtualStickController)
    private val flightLogger = FlightLogger(application)
    private val flightSafetyActionsController = FlightSafetyActionsController()

    private val flightStateMachine = FlightStateMachine(
        telemetryFlow = aircraftConnectionRepository.telemetryFlow,
        obstacleSnapshotFlow = perceptionRepository.obstacleSnapshotFlow,
        locationFixFlow = subjectLocationProvider.fixFlow, // Flow<out T> covariance: LocationFix satisfies LocationFix?
        manualOverrideActiveFlow = manualOverrideGate.activeFlow,
    )

    val registrationState: StateFlow<SdkRegistrationState> = WingmanApplication.instance.registrationStateFlow

    val flightState: StateFlow<FlightState> = flightStateMachine.flightStateFlow

    val telemetry = aircraftConnectionRepository.telemetryFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Distinct from [registrationState]'s ProductConnected -- see
     *  AircraftConnectionRepository.flightControllerConnectedFlow's header comment. */
    val flightControllerConnected: StateFlow<Boolean> = aircraftConnectionRepository.flightControllerConnectedFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** RC-N3 key-channel health, for isolating WHICH hop is broken when the FC link is
     *  down -- see AircraftConnectionRepository.remoteControllerConnectedFlow. */
    val remoteControllerConnected: StateFlow<Boolean> = aircraftConnectionRepository.remoteControllerConnectedFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** True while SDKManager reports the product (RC-N3 over USB) connected but the
     *  aircraft's flight controller hasn't come up -- the window where either the link is
     *  still establishing (normal for the first few seconds) or the RC firmware is stuck
     *  in the DJI-acknowledged preempted state (Mobile-SDK-Android-V5 issue #427). */
    private val awaitingFlightController: Flow<Boolean> = combine(
        WingmanApplication.instance.registrationStateFlow,
        flightControllerConnected,
    ) { registration, fcConnected ->
        registration is SdkRegistrationState.ProductConnected && !fcConnected
    }.distinctUntilChanged()

    /** Surfaced on the preflight screen: the FC link has been down for
     *  [FC_LINK_STALL_TIMEOUT_MS] straight after ProductConnect, which on this hardware
     *  means it is NOT still establishing -- it's the issue-#427 stuck state, and the
     *  operator needs to act (force-stop DJI Fly, replug USB) rather than keep waiting.
     *  There is deliberately no automatic silver bullet behind this: DJI provides no API
     *  that forces the link up (see AircraftConnectionRepository's header). */
    @OptIn(ExperimentalCoroutinesApi::class)
    val aircraftLinkStalled: StateFlow<Boolean> = awaitingFlightController
        .transformLatest { awaiting ->
            emit(false)
            if (awaiting) {
                delay(FC_LINK_STALL_TIMEOUT_MS)
                emit(true)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    init {
        viewModelScope.launch {
            flightStateMachine.commandFlow.collect { commandFlowHolder.value = it }
        }
        // Gimbal aiming is independent of the VirtualStick command path entirely -- see
        // FlightCommandCalculator's header comment on why the aircraft yaws to face the
        // subject while the gimbal only ever pitches (no gimbal yaw needed or used).
        viewModelScope.launch {
            flightStateMachine.gimbalPitchDegreesFlow.collect { pitchDegrees ->
                gimbalController.rotateTo(pitchDegrees, 0.0)
            }
        }
        // Fires once per transition INTO a new state class (distinctUntilChangedBy keys
        // on the class, not full equality) -- ReturnToHome/EmergencyStop carry a `reason`
        // string that can change tick-to-tick (e.g. battery percent counting down), which
        // would otherwise re-trigger the native action on every tick under plain equality.
        viewModelScope.launch {
            flightState.distinctUntilChangedBy { it::class }.collect { state ->
                when (state) {
                    is FlightState.ReturnToHome -> flightSafetyActionsController.startGoHome()
                    is FlightState.EmergencyStop -> flightSafetyActionsController.startAutoLanding()
                    else -> Unit
                }
            }
        }
        // Bounded active retry while the FC link is down post-ProductConnect: a read-only
        // probe request down the FC key channel every few seconds (collectLatest cancels
        // the loop the moment the link comes up or the product disconnects). This is the
        // strongest retry MSDK V5's public surface allows -- there is no reconnect API,
        // and DJI's own sample has no equivalent loop (it just waits, and exhibits the
        // same intermittent stall; see AircraftConnectionRepository's header for the
        // issue-#427 evidence). The probe's real value is making each attempt's concrete
        // native error visible in logcat; aircraftLinkStalled is what tells the operator
        // to stop waiting and act.
        viewModelScope.launch {
            awaitingFlightController.collectLatest { awaiting ->
                if (awaiting) {
                    repeat(FC_PROBE_MAX_ATTEMPTS) {
                        aircraftConnectionRepository.probeFlightControllerLink()
                        delay(FC_PROBE_INTERVAL_MS)
                    }
                }
            }
        }
        flightStateMachine.start(viewModelScope)
        virtualStickController.start(viewModelScope)
    }

    fun onManualOverridePressed() {
        Log.i("WingmanUI", "onManualOverridePressed called")
        manualOverrideGate.trip()
    }

    fun onManualOverrideCleared() = manualOverrideGate.clear()

    /** Operator action (a button in MainActivity's flight screen, replacing the old
     *  tap-to-select-a-subject gesture) — the subject is always "whoever is carrying this
     *  phone," so there's nothing to select, just a decision to start. */
    fun onStartFollowingPressed() {
        flightStateMachine.armLaunchPoint(
            telemetry.value?.let { LatLon(it.latitude, it.longitude) } ?: return,
        )
        flightStateMachine.startFollowing()
    }

    override fun onCleared() {
        super.onCleared()
        virtualStickController.stop()
        flightLogger.close()
    }
}
