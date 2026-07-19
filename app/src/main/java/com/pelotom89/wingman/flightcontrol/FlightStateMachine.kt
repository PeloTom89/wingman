package com.pelotom89.wingman.flightcontrol

import com.pelotom89.wingman.location.LocationFix
import com.pelotom89.wingman.sdk.AircraftTelemetry
import com.pelotom89.wingman.sdk.ObstacleReading
import com.pelotom89.wingman.sdk.VirtualStickCommand
import com.pelotom89.wingman.vision.TrackingResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The single place flight-control POLICY lives, per the plan — vision/, sdk/telemetry,
 * and location/ are deliberately dumb data sources that know nothing about tracking
 * state or safety, so this is the only class that decides what the aircraft should do
 * next and why. Every command this emits has already passed through [SafetyLimits] and
 * [ObstacleSafetyClamp] before [commandFlow] reaches VirtualStickController — nothing
 * downstream re-checks safety, so nothing upstream of this class should ever be trusted
 * to skip it.
 */
class FlightStateMachine(
    private val trackingResultFlow: Flow<TrackingResult>,
    private val telemetryFlow: Flow<AircraftTelemetry>,
    private val obstacleReadingsFlow: Flow<List<ObstacleReading>>,
    private val locationFixFlow: Flow<LocationFix?>,
    private val manualOverrideActiveFlow: Flow<Boolean>,
    private val safetyLimits: SafetyLimits = SafetyLimits(),
    private val obstacleSafetyClamp: ObstacleSafetyClamp = ObstacleSafetyClamp(),
    private val commandCalculator: FlightCommandCalculator = FlightCommandCalculator(),
    private val debouncer: TrackingLossDebouncer = TrackingLossDebouncer(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val _flightStateFlow = MutableStateFlow<FlightState>(FlightState.Idle)
    val flightStateFlow: StateFlow<FlightState> get() = _flightStateFlow.asStateFlow()

    private val _commandFlow = MutableStateFlow(VirtualStickCommand.ZERO)
    val commandFlow: StateFlow<VirtualStickCommand> get() = _commandFlow.asStateFlow()

    private var launchPoint: LatLon? = null

    /** Called once, at takeoff — anchors the geofence check in [SafetyLimits]. */
    fun armLaunchPoint(point: LatLon) {
        launchPoint = point
    }

    /** Operator action: begin VisualTrack after a successful tap-to-select seed. */
    fun startTracking() {
        if (_flightStateFlow.value == FlightState.Idle) {
            _flightStateFlow.value = FlightState.VisualTrack
        }
    }

    fun start(scope: CoroutineScope) {
        scope.launch {
            combine(
                trackingResultFlow,
                telemetryFlow,
                obstacleReadingsFlow,
                locationFixFlow,
                manualOverrideActiveFlow,
            ) { tracking, telemetry, obstacles, locationFix, overrideActive ->
                Tick(tracking, telemetry, obstacles, locationFix, overrideActive)
            }.collect { tick -> process(tick) }
        }
    }

    private fun process(tick: Tick) {
        if (tick.overrideActive) {
            _flightStateFlow.value = FlightState.ManualOverride
            _commandFlow.value = VirtualStickCommand.ZERO
            return
        }

        // Safety escalations take priority over whatever tracking/state logic would
        // otherwise decide — per the plan: "Any state -> ReturnToHome: battery threshold
        // breach or geofence breach."
        val batteryStatus = safetyLimits.batteryStatus(tick.telemetry.batteryPercent)
        val launch = launchPoint
        val geofenceBreached = launch != null &&
            safetyLimits.isGeofenceBreached(launch, LatLon(tick.telemetry.latitude, tick.telemetry.longitude))

        val escalatedState = when {
            batteryStatus == BatteryStatus.CRITICAL ->
                FlightState.EmergencyStop("Battery critical (${tick.telemetry.batteryPercent}%)")
            batteryStatus == BatteryStatus.RTH_TRIGGER ->
                FlightState.ReturnToHome("Battery at RTH threshold (${tick.telemetry.batteryPercent}%)")
            geofenceBreached -> FlightState.ReturnToHome("Geofence radius exceeded")
            safetyLimits.isAltitudeExceeded(tick.telemetry.altitudeMeters) ->
                FlightState.ReturnToHome("Altitude ceiling exceeded")
            else -> null
        }

        if (escalatedState != null) {
            _flightStateFlow.value = escalatedState
            // RTH/EmergencyStop are intentionally NOT driven via VirtualStick: zero the
            // stick output here and trigger the aircraft's own native go-home/land
            // behavior out-of-band (not yet implemented — see README's open items;
            // FlightController's native startGoHome()/startLanding() are the intended
            // calls, deliberately independent of the VirtualStick path this class owns).
            _commandFlow.value = VirtualStickCommand.ZERO
            return
        }

        val currentState = _flightStateFlow.value
        if (currentState !is FlightState.VisualTrack && currentState !is FlightState.GpsGuided) {
            // Idle, or recovering from a prior ReturnToHome/EmergencyStop: hold position,
            // wait for an explicit operator action (startTracking()) rather than guessing.
            _commandFlow.value = VirtualStickCommand.ZERO
            return
        }

        val nextState = debouncer.onTick(tick.tracking, currentState) ?: currentState
        _flightStateFlow.value = nextState

        val proposedCommand = when (nextState) {
            is FlightState.VisualTrack -> {
                val box = (tick.tracking as? TrackingResult.Tracking)?.box
                    ?: (tick.tracking as? TrackingResult.Lost)?.lastKnownBox
                box?.let { commandCalculator.computeVisualTrackCommand(it) } ?: VirtualStickCommand.ZERO
            }
            is FlightState.GpsGuided -> {
                val fix = tick.locationFix
                if (fix == null || fix.isStale(nowMillis())) {
                    VirtualStickCommand.ZERO // no trustworthy subject position — hold, don't guess
                } else {
                    commandCalculator.computeGpsGuidedCommand(
                        aircraft = LatLon(tick.telemetry.latitude, tick.telemetry.longitude),
                        aircraftHeadingDegrees = tick.telemetry.headingDegrees,
                        subject = fix.position,
                    )
                }
            }
            else -> VirtualStickCommand.ZERO
        }

        val speedLimited = safetyLimits.clampSpeed(proposedCommand)
        val obstacleClamped = obstacleSafetyClamp.clamp(speedLimited, tick.obstacles)

        if (obstacleSafetyClamp.isFullyBlocked(speedLimited, obstacleClamped)) {
            _flightStateFlow.value = FlightState.EmergencyStop("Obstacle clamp blocked all axes of motion")
        }

        _commandFlow.value = obstacleClamped
    }

    private data class Tick(
        val tracking: TrackingResult,
        val telemetry: AircraftTelemetry,
        val obstacles: List<ObstacleReading>,
        val locationFix: LocationFix?,
        val overrideActive: Boolean,
    )
}
