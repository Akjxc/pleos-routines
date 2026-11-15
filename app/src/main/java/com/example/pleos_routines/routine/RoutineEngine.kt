package com.example.pleos_routines.routine

import com.example.pleos_routines.model.*
import kotlin.math.*

class RoutineEngine(val latched: MutableSet<String> = mutableSetOf()) {
    private val locations = mutableMapOf<String, Boolean>()
    private val gears = mutableMapOf<String, Boolean>()

    fun resetTransitions() { locations.clear(); gears.clear() }

    fun conditionsMatch(r: Routine, env: Environment): Boolean {
        val targetArea = r.effectiveActions.firstOrNull { it.targetAreaId.isNotBlank() }?.targetAreaId ?: r.targetAreaId
        return r.effectiveConditions.all { c ->
            when (c.kind) {
                ConditionKind.AC_STATE -> c.acOn != null && areaValue(env.acStates, targetArea) == c.acOn
                ConditionKind.POWER_STATE -> c.powerOn != null && areaValue(env.powerStates, targetArea) == c.powerOn
                ConditionKind.PARKED_STATE -> c.parked != null && env.parked == c.parked
                ConditionKind.DEFROST_OFF -> c.requireDefrostOff && env.defrostActive == false
                ConditionKind.BATTERY_RANGE -> env.soc != null && env.soc in (c.batteryMin ?: 0)..(c.batteryMax ?: 100)
            }
        }
    }

    fun evaluate(routines: List<Routine>, env: Environment): List<Routine> = routines.filter { r ->
        val matches = conditionsMatch(r, env)
        when (r.trigger) {
            Trigger.MANUAL -> false
            Trigger.BATTERY_BELOW -> {
                val soc = env.soc
                if (soc == null || soc !in 0..100) false else {
                    if (soc >= r.thresholdPercent + 3) latched.remove(r.id)
                    r.enabled && matches && soc <= r.thresholdPercent && latched.add(r.id)
                }
            }
            Trigger.AT_TIME -> {
                val prefix = "time:${r.id}:"
                val key = prefix + env.dateKey
                latched.removeAll { it.startsWith(prefix) && it != key }
                val dayMatches = r.daysOfWeek.isEmpty() || env.dayOfWeek in r.daysOfWeek
                r.enabled && matches && dayMatches && env.minuteOfDay == r.hour * 60 + r.minute && latched.add(key)
            }
            Trigger.GEAR -> {
                val old = gears[r.id]
                if (env.parked == null) { gears.remove(r.id); false }
                else { gears[r.id] = env.parked; r.enabled && matches && old != null && old != env.parked && env.parked == r.gearParked }
            }
            Trigger.LOCATION -> {
                val p = env.location
                if (p == null || env.nowMs - p.timestampMs !in 0..30000 || (p.accuracyMeters.isFinite() && (p.accuracyMeters < 0 || p.accuracyMeters > minOf(100f, r.radiusMeters / 2))) || !p.latitude.isFinite() || !p.longitude.isFinite() || p.latitude !in -90.0..90.0 || p.longitude !in -180.0..180.0 || r.latitude == null || r.longitude == null) {
                    locations.remove(r.id); false
                } else {
                    val d = distance(p.latitude, p.longitude, r.latitude, r.longitude)
                    val old = locations[r.id]
                    val margin = maxOf(25.0, r.radiusMeters * .1)
                    val inside = when { d <= r.radiusMeters - margin -> true; d >= r.radiusMeters + margin -> false; else -> old }
                    if (inside != null) locations[r.id] = inside
                    r.enabled && matches && old != null && inside != null && old != inside && inside != r.locationExit
                }
            }
        }
    }

    fun evaluateBattery(routines: List<Routine>, soc: Int?): List<Routine> = evaluate(
        routines.filter { it.trigger == Trigger.BATTERY_BELOW }, Environment(0, "", 0, soc = soc)
    )

    fun onEdited(old: Routine?, updated: Routine) {
        if (old?.copy(name = updated.name, enabled = updated.enabled, message = updated.message) != updated) {
            latched.remove(updated.id); latched.removeAll { it.startsWith("time:${updated.id}:") }
            locations.remove(updated.id); gears.remove(updated.id)
        }
    }

    companion object {
        fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val dlat = Math.toRadians(lat2 - lat1); val dlon = Math.toRadians(lon2 - lon1)
            val a = sin(dlat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dlon / 2).pow(2)
            return 6371000 * 2 * atan2(sqrt(a.coerceIn(0.0, 1.0)), sqrt((1 - a).coerceIn(0.0, 1.0)))
        }
    }
}
