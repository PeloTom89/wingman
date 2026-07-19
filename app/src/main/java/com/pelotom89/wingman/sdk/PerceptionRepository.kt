package com.pelotom89.wingman.sdk

import dji.v5.manager.aircraft.perception.PerceptionManager
import dji.v5.manager.aircraft.perception.data.ObstacleData
import dji.v5.manager.aircraft.perception.data.PerceptionDirection
import dji.v5.manager.aircraft.perception.listener.PerceptionInformationListener
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Raw per-direction obstacle distance telemetry. This exists specifically because DJI's
 * own APAS obstacle avoidance is confirmed DISABLED whenever VirtualStick control is
 * active on the Mini 4 Pro (unlike the M300/M350/M30/Mavic 3E/3M lineup, which keep it
 * active) — see flightcontrol/ObstacleSafetyClamp.kt, which is what actually consumes
 * this data to replace that missing safety net. This repository is deliberately dumb:
 * it reports distances and nothing else, no judgment calls.
 *
 * NOTE: IPerceptionManager's exact per-direction shape (which of forward/backward/
 * left/right/up/down are discrete distances vs. a combined horizontal reading with an
 * angle interval) should be confirmed against the pinned SDK version's API reference —
 * this models the most likely documented shape from research, not a compiled reference.
 */
class PerceptionRepository {

    val obstacleReadingsFlow: Flow<List<ObstacleReading>> = callbackFlow {
        val listener = PerceptionInformationListener { info ->
            trySend(info.toObstacleReadings())
        }
        PerceptionManager.getInstance().addPerceptionInformationListener(listener)
        awaitClose { PerceptionManager.getInstance().removePerceptionInformationListener(listener) }
    }

    private fun ObstacleData.toObstacleReadings(): List<ObstacleReading> =
        PerceptionDirection.values().mapNotNull { direction ->
            val distance = getObstacleDistance(direction)?.toDouble() ?: return@mapNotNull null
            ObstacleReading(direction = direction.toDomain(), distanceMeters = distance)
        }
}

/** App-owned direction enum so flightcontrol/ never imports a DJI type directly. */
enum class ObstacleDirection { FORWARD, BACKWARD, LEFT, RIGHT, UPWARD, DOWNWARD }

data class ObstacleReading(val direction: ObstacleDirection, val distanceMeters: Double)

private fun PerceptionDirection.toDomain(): ObstacleDirection = when (this) {
    PerceptionDirection.UPWARD -> ObstacleDirection.UPWARD
    PerceptionDirection.DOWNWARD -> ObstacleDirection.DOWNWARD
    // HORIZONTAL carries an angle interval rather than 4 discrete sides in some MSDK V5
    // builds; if that's what the pinned version actually exposes, this mapping needs to
    // widen to derive FORWARD/BACKWARD/LEFT/RIGHT from the angle interval instead of a
    // 1:1 enum match — flagged here rather than silently guessed.
    else -> ObstacleDirection.FORWARD
}
