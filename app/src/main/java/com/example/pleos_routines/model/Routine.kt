package com.example.pleos_routines.model

import java.util.UUID
import kotlin.math.abs
import kotlin.math.round

enum class Trigger { MANUAL, BATTERY_BELOW, AT_TIME, LOCATION, GEAR }
enum class Action { NOTICE, SET_TEMPERATURE, AC_ON, VENTILATE, SET_FAN }

data class TemperatureArea(val id: String, val label: String, val min: Float, val max: Float, val step: Float) {
    fun accepts(value: Float): Boolean = value.isFinite() && step.isFinite() && step > 0 &&
        value >= min && value <= max && abs((value - min) / step - round((value - min) / step)) < .01f
    fun choices(): List<Float> = if (min.isFinite() && max.isFinite() && step > 0 && max >= min &&
        (max - min) / step < 300) (0..((max - min) / step).toInt()).map { min + it * step } else emptyList()
}

data class ActionItem(
    val action: Action = Action.NOTICE,
    val targetAreaId: String = "",
    val temperatureC: Float = 23f,
    val message: String = "안내문",
    val fanSpeed: Int = 3
) {
    fun summary(): String = when (action) {
        Action.NOTICE -> "안내 표시"
        Action.SET_TEMPERATURE -> "${temperatureC}°C"
        Action.AC_ON -> "A/C 켜기"
        Action.VENTILATE -> "A/C 끄고 송풍 유지"
        Action.SET_FAN -> "송풍 ${fanSpeed}단"
    }
}

data class Routine(
    val id: String = UUID.randomUUID().toString(), val name: String = "새 루틴", val enabled: Boolean = false,
    val trigger: Trigger = Trigger.MANUAL, val action: Action = Action.NOTICE,
    val thresholdPercent: Int = 30, val message: String = "충전 계획을 확인하고 ECO 모드 활용을 고려하세요.",
    val targetAreaId: String = "", val temperatureC: Float = 23f,
    val hour: Int = 8, val minute: Int = 0, val latitude: Double? = null, val longitude: Double? = null,
    val radiusMeters: Float = 500f, val locationExit: Boolean = false, val gearParked: Boolean = true,
    val fanSpeed: Int = 3, val conditions: RoutineConditions = RoutineConditions(),
    val daysOfWeek: List<Int> = emptyList(), // 1=Mon, 2=Tue, 3=Wed, 4=Thu, 5=Fri, 6=Sat, 7=Sun
    val actions: List<ActionItem> = emptyList(),
    val runCount: Int = 0,
    val lastRunTimeMs: Long = 0L
) {
    val effectiveConditions: List<RoutineCondition>
        get() = conditions.effectiveList()
    val effectiveActions: List<ActionItem>
        get() = actions.ifEmpty { listOf(ActionItem(action, targetAreaId, temperatureC, message, fanSpeed)) }

    fun validationError(areas: List<TemperatureArea>): String? {
        if (name.isBlank() || name.length > 40) return "이름을 1~40자로 입력하세요."
        if (hour !in 0..23 || minute !in 0..59) return "시간은 00:00~23:59로 입력하세요."
        if (trigger == Trigger.LOCATION && (latitude == null || longitude == null || !latitude.isFinite() || !longitude.isFinite() || latitude !in -90.0..90.0 || longitude !in -180.0..180.0 || !radiusMeters.isFinite() || radiusMeters !in 100f..10000f)) return "위도·경도와 반경 100~10000m를 확인하세요."
        if (effectiveConditions.any { it.kind == ConditionKind.BATTERY_RANGE && (it.batteryMin?.let { v -> v !in 0..100 } == true || it.batteryMax?.let { v -> v !in 0..100 } == true || (it.batteryMin ?: 0) > (it.batteryMax ?: 100)) }) return "배터리 추가 조건 범위는 0~100%입니다."
        if (thresholdPercent !in 1..95) return "배터리 기준은 1~95%입니다."
        effectiveActions.forEach { item ->
            if (item.action != Action.NOTICE && item.targetAreaId.isBlank()) return "공조 대상 영역을 선택하세요."
            if (item.action == Action.SET_FAN && item.fanSpeed !in 0..20) return "송풍 세기를 확인하세요."
            if (item.action == Action.NOTICE && (item.message.isBlank() || item.message.length > 200)) return "안내문을 1~200자로 입력하세요."
            if (item.action == Action.SET_TEMPERATURE && areas.none { it.id == item.targetAreaId && it.accepts(item.temperatureC) }) return "지원되는 공조 영역과 온도를 선택하세요."
        }
        return null
    }

    fun daysText(): String = when {
        daysOfWeek.isEmpty() -> "매일"
        daysOfWeek.sorted() == listOf(1, 2, 3, 4, 5) -> "평일"
        daysOfWeek.sorted() == listOf(6, 7) -> "주말"
        else -> daysOfWeek.sorted().joinToString(", ") { dayName(it) }
    }

    private fun dayName(day: Int): String = when (day) {
        1 -> "월"; 2 -> "화"; 3 -> "수"; 4 -> "목"; 5 -> "금"; 6 -> "토"; 7 -> "일"; else -> ""
    }

    fun summary(): String {
        val whenText = when (trigger) {
            Trigger.MANUAL -> "수동 실행"
            Trigger.BATTERY_BELOW -> "배터리 ${thresholdPercent}% 이하"
            Trigger.AT_TIME -> "${daysText()} %02d:%02d".format(hour, minute)
            Trigger.LOCATION -> "저장 위치 ${radiusMeters.toInt()}m ${if (locationExit) "이탈" else "진입"}"
            Trigger.GEAR -> "${if (gearParked) "P단" else "주행 기어"}으로 변경"
        }
        val what = effectiveActions.joinToString(" + ") { it.summary() }
        return "$whenText → $what"
    }
}

data class RunRecord(val time: Long, val routineName: String, val detail: String)
data class StoredState(
    val routines: List<Routine>, val latched: Set<String> = emptySet(), val records: List<RunRecord> = emptyList(),
    val offeredTemplateIds: Set<String> = templates().map { it.id }.toSet()
)

fun templates() = listOf(
    Routine(
        id = "template-commute", name = "출근 쾌적", enabled = false, trigger = Trigger.AT_TIME,
        hour = 8, minute = 0, daysOfWeek = listOf(1, 2, 3, 4, 5),
        actions = listOf(
            ActionItem(action = Action.SET_TEMPERATURE, temperatureC = 22f),
            ActionItem(action = Action.AC_ON),
            ActionItem(action = Action.NOTICE, message = "출근길 쾌적 공조를 시작합니다.")
        )
    ),
    Routine(
        id = "template-ac-dry", name = "에어컨 자동 건조", enabled = false, trigger = Trigger.GEAR, gearParked = true,
        actions = listOf(
            ActionItem(action = Action.VENTILATE),
            ActionItem(action = Action.NOTICE, message = "A/C를 끄고 현재 송풍을 유지합니다.")
        )
    ),
    Routine(
        id = "template-arrival", name = "도착 전 송풍", enabled = false, trigger = Trigger.LOCATION,
        radiusMeters = 500f,
        conditions = RoutineConditions(acOn = true, powerOn = true, requireDefrostOff = true),
        actions = listOf(
            ActionItem(action = Action.VENTILATE),
            ActionItem(action = Action.NOTICE, message = "도착 전 A/C를 끄고 기존 송풍을 유지합니다.")
        )
    ),
    Routine(id = "template-battery", name = "스마트 ECO 안내", enabled = false, trigger = Trigger.BATTERY_BELOW, thresholdPercent = 30),
    Routine(id = "template-departure", name = "출발 준비", enabled = false, action = Action.SET_TEMPERATURE, temperatureC = 23f)
)

data class RoutineConditions(
    val acOn: Boolean? = null, val powerOn: Boolean? = null,
    val batteryMin: Int? = null, val batteryMax: Int? = null, val parked: Boolean? = null,
    val requireDefrostOff: Boolean = false,
    val items: List<RoutineCondition> = emptyList()
) {
    fun effectiveList(): List<RoutineCondition> = items.ifEmpty {
        buildList {
            if (acOn != null) add(RoutineCondition(ConditionKind.AC_STATE, acOn = acOn))
            if (powerOn != null) add(RoutineCondition(ConditionKind.POWER_STATE, powerOn = powerOn))
            if (batteryMin != null || batteryMax != null) add(RoutineCondition(ConditionKind.BATTERY_RANGE, batteryMin = batteryMin, batteryMax = batteryMax))
            if (parked != null) add(RoutineCondition(ConditionKind.PARKED_STATE, parked = parked))
            if (requireDefrostOff) add(RoutineCondition(ConditionKind.DEFROST_OFF, requireDefrostOff = true))
        }
    }
}

enum class ConditionKind { AC_STATE, POWER_STATE, BATTERY_RANGE, PARKED_STATE, DEFROST_OFF }

data class RoutineCondition(
    val kind: ConditionKind,
    val acOn: Boolean? = null,
    val powerOn: Boolean? = null,
    val batteryMin: Int? = null,
    val batteryMax: Int? = null,
    val parked: Boolean? = null,
    val requireDefrostOff: Boolean = false
) {
    fun title(): String = when (kind) {
        ConditionKind.AC_STATE -> "에어컨 상태"
        ConditionKind.POWER_STATE -> "공조 전원 상태"
        ConditionKind.BATTERY_RANGE -> "배터리 범위"
        ConditionKind.PARKED_STATE -> "주차 상태"
        ConditionKind.DEFROST_OFF -> "성에 제거 꺼짐"
    }
}
data class GeoPoint(val latitude: Double, val longitude: Double, val accuracyMeters: Float, val timestampMs: Long)
data class Environment(
    val nowMs: Long, val dateKey: String, val minuteOfDay: Int, val dayOfWeek: Int = 1,
    val soc: Int? = null, val parked: Boolean? = null, val location: GeoPoint? = null,
    val acStates: Map<String, Boolean> = emptyMap(), val powerStates: Map<String, Boolean> = emptyMap(),
    val defrostActive: Boolean? = null
)

/** A state may be exposed for one seat or an SDK group of seats. Never pick one conflicting zone. */
fun <T> areaValue(values: Map<String, T>, target: String): T? {
    values[target]?.let { return it }
    val seats = target.split('+').filter { it.isNotBlank() }.toSet()
    if (seats.isEmpty()) return null
    val covering = values.filterKeys { it.split('+').toSet().containsAll(seats) }
    val relevant = if (covering.isNotEmpty()) {
        val count = covering.keys.minOf { it.split('+').size }
        covering.filterKeys { it.split('+').size == count }
    } else values.filterKeys { key -> key.split('+').any { it in seats } }
    return relevant.values.distinct().singleOrNull()
}
