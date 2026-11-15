package com.example.pleos_routines.routine

import com.example.pleos_routines.model.*
import kotlin.math.*

class RoutineEngine(val latched: MutableSet<String> = mutableSetOf()) {
    private val locations = mutableMapOf<String, Boolean>()
    private val gears = mutableMapOf<String, Boolean>()

    fun resetTransitions() { locations.clear(); gears.clear() }

    fun conditionsMatch(r: Routine, env: Environment): Boolean = conditionProblem(r, env) == null

    fun conditionProblem(r: Routine, env: Environment): String? {
        val targetArea = r.effectiveActions.firstOrNull { it.targetAreaId.isNotBlank() }?.targetAreaId ?: r.targetAreaId
        return r.effectiveConditions.firstNotNullOfOrNull { c ->
            val matches = when (c.kind) {
                ConditionKind.AC_STATE -> c.acOn != null && areaValue(env.acStates, targetArea) == c.acOn
                ConditionKind.POWER_STATE -> c.powerOn != null && areaValue(env.powerStates, targetArea) == c.powerOn
                ConditionKind.PARKED_STATE -> c.parked != null && env.parked == c.parked
                ConditionKind.DEFROST_OFF -> c.requireDefrostOff && env.defrostActive == false
                ConditionKind.BATTERY_RANGE -> env.soc != null && env.soc in (c.batteryMin ?: 0)..(c.batteryMax ?: 100)
            }
            if (matches) null else "${c.title()} 조건 불충족 또는 상태 미확인"
        }
    }

    /** Read-only explanation: never advances transitions or consumes a trigger. */
    fun waitingReason(r: Routine, env: Environment): String {
        if (!r.enabled) return "꺼짐"
        conditionProblem(r, env)?.let { return it }
        return when (r.trigger) {
            Trigger.MANUAL -> if (env.parked == true) "수동 실행 대기" else "P단 확인 필요"
            Trigger.BATTERY_BELOW -> when {
                env.soc == null || env.soc !in 0..100 -> "배터리 상태 미확인"
                r.id in latched -> "현재 저전력 구간 감지 완료 · ${r.thresholdPercent + 3}% 이상 회복 대기"
                else -> "배터리 ${r.thresholdPercent}% 이하 대기"
            }
            Trigger.AT_TIME -> when {
                r.daysOfWeek.isNotEmpty() && env.dayOfWeek !in r.daysOfWeek -> "실행 요일 아님 · ${r.daysText()}"
                "time:${r.id}:${env.dateKey}" in latched -> "오늘 이벤트 감지 완료 · 다음 실행일 대기"
                else -> "실행 시각 대기 · %02d:%02d".format(r.hour, r.minute)
            }
            Trigger.GEAR -> if (env.parked == null) "기어 상태 미확인" else
                "${if (r.gearParked) "P단 진입" else "P단 이탈"} 대기"
            Trigger.LOCATION -> {
                val p = env.location
                when {
                    p == null || env.nowMs - p.timestampMs !in 0..30000 -> "최신 차량 위치 대기"
                    r.latitude == null || r.longitude == null -> "편집에서 위치 설정 필요"
                    else -> "위치 ${if (r.locationExit) "이탈" else "진입"} 대기 · 중심까지 ${distance(p.latitude, p.longitude, r.latitude, r.longitude).toInt()}m"
                }
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
