package com.pelotom89.wingman.location

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.pelotom89.wingman.flightcontrol.LatLon
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * The subject's live position, sourced from THIS phone's own GPS chip — not a separate
 * beacon or backend. The phone stays with the cyclist (mounted on the RC they're
 * carrying/piloting with) throughout the flight, so its GPS is a direct proxy for the
 * subject's position, and it already reaches the aircraft for free over the existing
 * RC-to-aircraft radio link that VirtualStick commands travel over — no networking layer
 * needed (see the plan's "no separate GPS beacon" decision).
 *
 * Exposes staleness explicitly: GpsGuided mode should refuse to trust a fix that's too
 * old rather than silently flying toward a stale coordinate.
 */
class SubjectLocationProvider(context: Context) {

    private val fusedClient = LocationServices.getFusedLocationProviderClient(context)

    @SuppressLint("MissingPermission") // caller (PreflightChecklistScreen) gates on permission grant
    val fixFlow: Flow<LocationFix> = callbackFlow {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, UPDATE_INTERVAL_MS)
            .setMinUpdateIntervalMillis(MIN_UPDATE_INTERVAL_MS)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                trySend(
                    LocationFix(
                        position = LatLon(location.latitude, location.longitude),
                        accuracyMeters = location.accuracy.toDouble(),
                        timestampMillis = location.time,
                    ),
                )
            }
        }

        fusedClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
        awaitClose { fusedClient.removeLocationUpdates(callback) }
    }

    private companion object {
        const val UPDATE_INTERVAL_MS = 500L
        const val MIN_UPDATE_INTERVAL_MS = 200L
    }
}

data class LocationFix(
    val position: LatLon,
    val accuracyMeters: Double,
    val timestampMillis: Long,
) {
    fun isStale(nowMillis: Long, maxAgeMillis: Long = 3000): Boolean =
        (nowMillis - timestampMillis) > maxAgeMillis
}
