package com.example.pleos_routines.vehicle

import android.content.Context
import android.os.Handler
import android.os.Looper
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.constant.control.VehicleGear

/** Uses the Gear API shipped in Vehicle 2.3.2; the Pleos AVD has no android.car shared library. */
class ParkingStateSource(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var vehicle: Vehicle? = null
    private var session = 0
    private var observer: (Boolean?) -> Unit = {}
    var parked: Boolean? = null
        private set
    fun start(onParked: (Boolean?) -> Unit) {
        stop(); observer = onParked
        val token = session
        try { vehicle = Vehicle(context).also { it.initialize() } } catch (_: Exception) { observer(null) }
        main.post(object : Runnable {
            override fun run() {
                if (session != token) return
                query { }
                main.postDelayed(this, 2000)
            }
        })
    }
    private fun query(done: (Boolean?) -> Unit) {
        val token = session
        var completed = false
        fun finish(value: Boolean?) { main.post {
            if (!completed && session == token) {
                completed = true; parked = value; observer(value); done(value)
            }
        } }
        main.postDelayed({ finish(null) }, 1800)
        try { vehicle?.getGear()?.getCurrentGearSelectionState(onComplete = { gear ->
            finish(if (gear == null || gear == VehicleGear.GEAR_UNKNOWN) null else gear == VehicleGear.GEAR_PARK)
        }, onFailed = { finish(null) }) ?: finish(null) } catch (_: Exception) { finish(null) }
    }
    fun requireParked(action: () -> Unit, denied: () -> Unit) = query { if (it == true) action() else denied() }
    fun stop() {
        session++; runCatching { vehicle?.release() }; vehicle = null; parked = null; observer = {}
    }
}
