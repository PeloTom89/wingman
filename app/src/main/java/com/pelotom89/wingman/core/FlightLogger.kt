package com.pelotom89.wingman.core

import android.content.Context
import com.pelotom89.wingman.flightcontrol.FlightState
import com.pelotom89.wingman.sdk.AircraftTelemetry
import com.pelotom89.wingman.sdk.VirtualStickCommand
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Structured per-flight log, written locally. Post-incident debugging without any
 * telemetry trail would be painful for an app that's autonomously flying near a person —
 * this is the record that lets you reconstruct exactly what state the machine was in and
 * what command it sent, tick by tick, after the fact.
 *
 * Plain CSV rather than a DB: this only ever needs to be read after a flight, sequentially,
 * by a human or a spreadsheet — no query surface is worth the added complexity.
 */
class FlightLogger(context: Context) {

    private val logDir = File(context.getExternalFilesDir(null), "flight_logs").apply { mkdirs() }
    private val file = File(logDir, "flight_${TIMESTAMP_FORMAT.format(Date())}.csv")
    private val writer = FileWriter(file, /* append = */ true).apply {
        write("timestampMillis,state,lat,lon,altitudeM,batteryPct,pitch,roll,yaw,vertical\n")
    }

    @Synchronized
    fun log(state: FlightState, telemetry: AircraftTelemetry?, command: VirtualStickCommand) {
        writer.write(
            listOf(
                System.currentTimeMillis(),
                state::class.simpleName,
                telemetry?.latitude ?: "",
                telemetry?.longitude ?: "",
                telemetry?.altitudeMeters ?: "",
                telemetry?.batteryPercent ?: "",
                command.pitchMetersPerSecond,
                command.rollMetersPerSecond,
                command.yawDegreesPerSecond,
                command.verticalMetersPerSecond,
            ).joinToString(","),
        )
        writer.write("\n")
        writer.flush() // flush every line: a crash mid-flight shouldn't lose the buffer
    }

    fun close() = writer.close()

    private companion object {
        val TIMESTAMP_FORMAT = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
    }
}
