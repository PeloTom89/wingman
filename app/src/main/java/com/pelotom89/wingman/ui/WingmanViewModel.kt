package com.pelotom89.wingman.ui

import android.app.Application
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
import com.pelotom89.wingman.sdk.PerceptionRepository
import com.pelotom89.wingman.sdk.SdkRegistrationState
import com.pelotom89.wingman.sdk.VideoFeedRepository
import com.pelotom89.wingman.sdk.VirtualStickController
import com.pelotom89.wingman.sdk.WingmanApplication
import com.pelotom89.wingman.vision.CoastingBoxTracker
import com.pelotom89.wingman.vision.SubjectDetector
import com.pelotom89.wingman.vision.SubjectTracker
import com.pelotom89.wingman.vision.TapToSelectHandler
import com.pelotom89.wingman.vision.TrackingResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Composition root wiring sdk/ + vision/ + location/ + flightcontrol/ together, and the
 * single object the UI layer observes. Kept intentionally thin: every actual decision
 * (what command to send, when to switch modes) lives in FlightStateMachine, not here —
 * this class only assembles the dependency graph and exposes it as Compose-friendly state.
 */
class WingmanViewModel(application: Application) : AndroidViewModel(application) {

    private val aircraftConnectionRepository = AircraftConnectionRepository()
    private val perceptionRepository = PerceptionRepository()
    private val subjectLocationProvider = SubjectLocationProvider(application)
    private val subjectDetector = SubjectDetector(application)
    private val subjectTracker = SubjectTracker(subjectDetector, CoastingBoxTracker())
    val tapToSelectHandler = TapToSelectHandler(subjectTracker)

    private val _trackingResultFlow = MutableStateFlow<TrackingResult>(TrackingResult.NotStarted)

    private val commandFlowHolder = MutableStateFlow(com.pelotom89.wingman.sdk.VirtualStickCommand.ZERO)
    private val overrideActiveHolder = MutableStateFlow(false)
    private val virtualStickController = VirtualStickController(commandFlowHolder, overrideActiveHolder)
    private val manualOverrideGate = ManualOverrideGate(virtualStickController)
    private val flightLogger = FlightLogger(application)
    private val flightSafetyActionsController = FlightSafetyActionsController()

    private val flightStateMachine = FlightStateMachine(
        trackingResultFlow = _trackingResultFlow,
        telemetryFlow = aircraftConnectionRepository.telemetryFlow,
        obstacleSnapshotFlow = perceptionRepository.obstacleSnapshotFlow,
        locationFixFlow = subjectLocationProvider.fixFlow, // Flow<out T> covariance: LocationFix satisfies LocationFix?
        manualOverrideActiveFlow = manualOverrideGate.activeFlow,
    )

    val registrationState: StateFlow<SdkRegistrationState> = WingmanApplication.instance.registrationStateFlow

    val flightState: StateFlow<FlightState> = flightStateMachine.flightStateFlow

    val telemetry = aircraftConnectionRepository.telemetryFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    init {
        viewModelScope.launch {
            flightStateMachine.commandFlow.collect { commandFlowHolder.value = it }
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
        flightStateMachine.start(viewModelScope)
        virtualStickController.start(viewModelScope)
    }

    fun onManualOverridePressed() = manualOverrideGate.trip()

    fun onManualOverrideCleared() = manualOverrideGate.clear()

    fun onSubjectSelected() {
        flightStateMachine.armLaunchPoint(
            telemetry.value?.let { LatLon(it.latitude, it.longitude) } ?: return,
        )
        flightStateMachine.startTracking()
    }

    override fun onCleared() {
        super.onCleared()
        virtualStickController.stop()
        subjectDetector.close()
        flightLogger.close()
    }
}
