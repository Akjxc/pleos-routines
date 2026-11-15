package com.example.pleos_routines.vehicle

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import com.example.pleos_routines.model.Action
import com.example.pleos_routines.model.ConditionKind
import com.example.pleos_routines.model.Routine
import com.example.pleos_routines.model.TemperatureArea
import com.example.pleos_routines.model.areaValue
import kotlin.math.abs

data class VehicleSnapshot(
    val soc: Int? = null, val areas: List<TemperatureArea> = emptyList(),
    val temperatures: Map<String, Float> = emptyMap(),
    val batteryStatus: String = "연결 확인 중", val climateStatus: String = "연결 확인 중",
    val acStates: Map<String, Boolean> = emptyMap(),
    val powerStates: Map<String, Boolean> = emptyMap(),
    val fanSpeeds: Map<String, Int> = emptyMap(),
    val defrostActive: Boolean? = null,
    val acAreas: Set<String> = emptySet(),
    val fanRanges: Map<String, IntRange> = emptyMap()
)

data class ActionResult(val confirmed: Boolean, val detail: String)

class PleosVehicleGateway(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var vehicle: Vehicle? = null
    private var session = 0
    private var active = false
    private var observer: (VehicleSnapshot) -> Unit = {}
    private var snapshot = VehicleSnapshot()
    private val seats = mutableMapOf<String, SeatArea>()
    private val temperatureAreas = mutableMapOf<String, TemperatureArea>()
    private val acReadable = mutableSetOf<String>()
    private val acWritable = mutableSetOf<String>()
    private val powerReadable = mutableSetOf<String>()
    private val fanReadable = mutableSetOf<String>()
    private val fanWritable = mutableMapOf<String, IntRange>()
    private val maxDefrostReadable = mutableSetOf<String>()
    private var frontDefrosters = emptyList<WindowArea>()
    private var listener: EvBatteryStateOfChargeListener? = null
    private var pending: ((ActionResult) -> Unit)? = null
    private var batteryReadable = false
    private var lastBatteryRead = 0L
    private var lastClimateRead = 0L
    private var batteryReadId = 0
    private var readInFlight = false
    private var climateReadId = 0
    private var climateReadInFlight = false
    private var writeId = 0
    private var writesRequireReconnect = false
    val hasPendingRequest: Boolean get() = pending != null

    private fun dispatch(token: Int, block: () -> Unit) { main.post { if (active && token == session) block() } }
    private fun emit() { observer(snapshot) }
    private fun readable(access: AccessType) = access == AccessType.READ || access == AccessType.READ_WRITE
    private fun writable(access: AccessType) = access == AccessType.WRITE || access == AccessType.READ_WRITE
    private fun seatKey(seat: SeatArea) = seat.values.map { it.name }.sorted().joinToString("+")
    private fun remember(seat: SeatArea): String = seatKey(seat).also { seats[it] = seat }
    private fun relevantFanAreas(target: String): Set<String> {
        val targetSeats = target.split('+').filter { it.isNotBlank() }.toSet()
        return fanReadable.filterTo(mutableSetOf()) { key ->
            val fanSeats = key.split('+').filter { it.isNotBlank() }.toSet()
            fanSeats.isNotEmpty() && targetSeats.containsAll(fanSeats)
        }
    }
    private fun failure(error: Exception): String {
        Log.w("PleosRoutines", "Vehicle SDK request failed", error)
        val detail = error.message.orEmpty()
        return when {
            detail.contains("permission", true) || error is SecurityException -> "차량 권한이 거부되었습니다"
            detail.contains("not available", true) -> "현재 값을 읽을 수 없습니다. 공조 전원과 Virtual Console 연결을 확인하세요"
            detail.contains("support", true) -> "차량에서 지원하지 않는 기능입니다"
            else -> "차량 서비스 응답을 확인할 수 없습니다. 다시 연결하세요"
        }
    }

    // AC/fan capabilities are independent of temperature support. Empty choices
    // prevent AC/fan-only seats from being accepted as temperature targets.
    private fun publishCapabilities() {
        val ids = temperatureAreas.keys + acWritable + fanWritable.keys
        snapshot = snapshot.copy(areas = ids.sorted().map { key ->
            temperatureAreas[key] ?: TemperatureArea(key,
                seats.getValue(key).values.joinToString(" + ") { seatLabel(it.name) }, 0f, 0f, 0f)
        }, acAreas = acWritable.toSet(), fanRanges = fanWritable.toMap(),
            climateStatus = if (ids.isEmpty()) "공조 읽기/쓰기 미지원" else "공조 지원 · ${ids.size}개 영역")
        emit()
    }

    private fun capabilityRequest(token: Int, request: () -> Unit) {
        try { request() } catch (e: Exception) { dispatch(token) {
            snapshot = snapshot.copy(climateStatus = "공조 지원 조회: ${failure(e)}"); emit()
        } }
    }

    fun start(onState: (VehicleSnapshot) -> Unit) {
        stop(); active = true; observer = onState
        snapshot = VehicleSnapshot(); emit()
        val token = session
        try {
            val sdk = Vehicle(context); vehicle = sdk; sdk.initialize()
            try { sdk.getEvBattery().checkEvBatteryStateOfChargeCapabilities(onComplete = { config -> dispatch(token) {
                batteryReadable = readable(config.accessType)
                snapshot = snapshot.copy(batteryStatus = if (batteryReadable) "읽기 지원 · 실제 조회 중" else "배터리 읽기 미지원")
                if (batteryReadable) {
                    listener = object : EvBatteryStateOfChargeListener {
                        override fun onEvBatteryStateOfChargeUpdated(value: Int?) { dispatch(token) { acceptBattery(value) } }
                        override fun onFailed(e: Exception) { dispatch(token) {
                            snapshot = snapshot.copy(soc = null, batteryStatus = "구독 오류: ${failure(e)}"); emit()
                        } }
                    }
                    runCatching { sdk.getEvBattery().registerEvBatteryStateOfCharge(listener!!) }
                    pollBattery(token)
                }
                emit()
            } }, onFailed = { e -> dispatch(token) { snapshot = snapshot.copy(batteryStatus = "배터리: ${failure(e)}"); emit() } })
            } catch (e: Exception) { snapshot = snapshot.copy(batteryStatus = "배터리: ${failure(e)}"); emit() }
            val hvac = sdk.getHVAC()
            val unsupported: (Exception) -> Unit = { e -> dispatch(token) {
                snapshot = snapshot.copy(climateStatus = "공조 지원 조회: ${failure(e)}"); emit()
            } }
            capabilityRequest(token) {
                hvac.checkHvacTemperatureCapabilities(onComplete = { config, bounds, temperatureConfig -> dispatch(token) {
                    if (readable(config.accessType) && writable(config.accessType) && temperatureConfig != null) {
                        bounds.forEach { (seat, range) ->
                            val key = remember(seat)
                            val area = TemperatureArea(key, seat.values.joinToString(" + ") { seatLabel(it.name) },
                                maxOf(range.minValue, temperatureConfig.minCelsius),
                                minOf(range.maxValue, temperatureConfig.maxCelsius), temperatureConfig.incrementCelsius)
                            if (area.choices().isNotEmpty()) temperatureAreas[key] = area
                        }
                    }
                    publishCapabilities(); pollClimate(token)
                } }, onFailed = unsupported)
            }
            capabilityRequest(token) {
                hvac.checkHvacAcCapabilities(onComplete = { config, areas -> dispatch(token) {
                    areas.forEach { seat ->
                        val key = remember(seat)
                        if (readable(config.accessType)) acReadable.add(key)
                        if (readable(config.accessType) && writable(config.accessType)) acWritable.add(key)
                    }
                    publishCapabilities(); pollClimate(token)
                } }, onFailed = unsupported)
            }
            capabilityRequest(token) {
                hvac.checkHvacPowerCapabilities(onComplete = { config, areas -> dispatch(token) {
                    if (readable(config.accessType)) areas.forEach { powerReadable.add(remember(it)) }
                    pollClimate(token)
                } }, onFailed = unsupported)
            }
            capabilityRequest(token) {
                hvac.checkHvacFanSpeedCapabilities(onComplete = { config, bounds -> dispatch(token) {
                    bounds.forEach { (seat, range) ->
                        val key = remember(seat)
                        if (readable(config.accessType)) fanReadable.add(key)
                        if (readable(config.accessType) && writable(config.accessType) && range.minValue <= range.maxValue)
                            fanWritable[key] = range.minValue..range.maxValue
                    }
                    publishCapabilities(); pollClimate(token)
                } }, onFailed = unsupported)
            }
            capabilityRequest(token) {
                hvac.checkHvacDefrosterCapabilities(onComplete = { config, areas -> dispatch(token) {
                    if (readable(config.accessType)) frontDefrosters = areas.filter { WindowValue.FRONT_WINDSHIELD in it.values }
                    pollClimate(token)
                } }, onFailed = unsupported)
            }
            capabilityRequest(token) {
                hvac.checkHvacMaxDefrostCapabilities(onComplete = { config, areas -> dispatch(token) {
                    if (readable(config.accessType)) areas.forEach { maxDefrostReadable.add(remember(it)) }
                    pollClimate(token)
                } }, onFailed = unsupported)
            }
            main.postDelayed(object : Runnable {
                override fun run() {
                    if (!active || session != token) return
                    if (lastBatteryRead > 0 && SystemClock.elapsedRealtime() - lastBatteryRead > 6000) {
                        snapshot = snapshot.copy(soc = null, batteryStatus = "배터리 응답 지연"); emit()
                    }
                    if (lastClimateRead > 0 && SystemClock.elapsedRealtime() - lastClimateRead > 6000) clearClimate("공조 응답 지연")
                    pollBattery(token); pollClimate(token); main.postDelayed(this, 2000)
                }
            }, 2000)
            main.postDelayed({ dispatch(token) {
                if (snapshot.batteryStatus == "연결 확인 중") snapshot = snapshot.copy(batteryStatus = "배터리 연결 응답 없음 · 다시 연결하세요")
                if (snapshot.climateStatus == "연결 확인 중") snapshot = snapshot.copy(climateStatus = "공조 연결 응답 없음 · 다시 연결하세요")
                emit()
            } }, 8000)
        } catch (e: Exception) {
            snapshot = snapshot.copy(batteryStatus = failure(e), climateStatus = failure(e)); emit()
        }
    }

    private fun acceptBattery(value: Int?) {
        lastBatteryRead = SystemClock.elapsedRealtime()
        snapshot = snapshot.copy(soc = value?.takeIf { it in 0..100 },
            batteryStatus = if (value != null && value in 0..100) "SDK 연결 · 구독 + 2초 조회" else "배터리 값 사용 불가")
        emit()
    }
    private fun pollBattery(token: Int) {
        val sdk = vehicle ?: return
        if (!batteryReadable || readInFlight) return
        readInFlight = true
        val id = ++batteryReadId
        main.postDelayed({ if (active && session == token && id == batteryReadId && readInFlight) {
            readInFlight = false; batteryReadId++
            snapshot = snapshot.copy(soc = null, batteryStatus = "배터리 조회 시간 초과"); emit()
        } }, 5000)
        try { sdk.getEvBattery().getEvBatteryStateOfCharge(onComplete = { value -> dispatch(token) {
            if (id == batteryReadId) { readInFlight = false; batteryReadId++; acceptBattery(value) }
        } }, onFailed = { e -> dispatch(token) {
            if (id == batteryReadId) {
                readInFlight = false; batteryReadId++
                snapshot = snapshot.copy(soc = null, batteryStatus = "배터리 조회: ${failure(e)}"); emit()
            }
        } }) } catch (e: Exception) {
            readInFlight = false; batteryReadId++
            snapshot = snapshot.copy(soc = null, batteryStatus = failure(e)); emit()
        }
    }

    private fun clearClimate(status: String) {
        snapshot = snapshot.copy(temperatures = emptyMap(), acStates = emptyMap(), powerStates = emptyMap(),
            fanSpeeds = emptyMap(), defrostActive = null, climateStatus = status)
        emit()
    }

    private fun pollClimate(token: Int) {
        if (climateReadInFlight || pending != null) return
        try { readClimate(token) {} } catch (e: Exception) {
            climateReadId++; climateReadInFlight = false
            clearClimate("공조 조회: ${failure(e)}")
        }
    }

    // Each round owns its values and deadline. Missing/failed values disappear;
    // a late callback from an older round cannot restore stale state.
    private fun readClimate(token: Int, onComplete: (VehicleSnapshot) -> Unit) {
        val sdk = vehicle ?: return
        val hvac = sdk.getHVAC()
        val id = ++climateReadId
        climateReadInFlight = true
        val temperatures = mutableMapOf<String, Float>()
        val ac = mutableMapOf<String, Boolean>()
        val power = mutableMapOf<String, Boolean>()
        val fans = mutableMapOf<String, Int>()
        val defrost = mutableListOf<Boolean?>()
        val requests = mutableListOf<() -> Unit>()
        var remaining = 0
        var completed = false
        var failed = false
        fun current() = active && session == token && vehicle === sdk && id == climateReadId && !completed
        fun finish() {
            if (!current()) return
            completed = true; climateReadInFlight = false
            lastClimateRead = SystemClock.elapsedRealtime()
            val expectedDefrost = frontDefrosters.size + maxDefrostReadable.size
            val defrostState = when {
                defrost.any { it == true } -> true
                frontDefrosters.isNotEmpty() && maxDefrostReadable.isNotEmpty() &&
                    defrost.size == expectedDefrost && defrost.all { it == false } -> false
                else -> null
            }
            snapshot = snapshot.copy(temperatures = temperatures.toMap(), acStates = ac.toMap(),
                powerStates = power.toMap(), fanSpeeds = fans.toMap(), defrostActive = defrostState,
                climateStatus = when {
                    requests.isEmpty() -> "공조 조회 미지원 · 지원 확인 중이거나 읽기 권한 없음"
                    failed -> "공조 일부 값 사용 불가 · 미확인 기능 실행 차단"
                    else -> "공조 연결 · 실제 값 조회"
                })
            emit(); onComplete(snapshot)
        }
        fun <T> request(start: ((T?) -> Unit, (Exception) -> Unit) -> Unit, accept: (T?) -> Unit) {
            requests.add {
                var answered = false
                fun answer(value: T?, error: Exception?) { dispatch(token) {
                    if (current() && !answered) {
                        answered = true
                        if (error != null) { failed = true; failure(error) } else accept(value)
                        if (--remaining == 0) finish()
                    }
                } }
                try { start({ answer(it, null) }, { answer(null, it) }) }
                catch (e: Exception) { answer(null, e) }
            }
        }
        temperatureAreas.keys.forEach { key -> request<Float>(
            { ok, bad -> hvac.getHvacTemperature(seats.getValue(key), TemperatureUnit.CELSIUS, onComplete = ok, onFailed = bad) },
            { value -> if (value != null && value.isFinite()) temperatures[key] = value else failed = true }) }
        acReadable.forEach { key -> request<Boolean>(
            { ok, bad -> hvac.getHvacAcState(seats.getValue(key), onComplete = ok, onFailed = bad) },
            { value -> if (value != null) ac[key] = value else failed = true }) }
        powerReadable.forEach { key -> request<Boolean>(
            { ok, bad -> hvac.getHvacPowerState(seats.getValue(key), onComplete = ok, onFailed = bad) },
            { value -> if (value != null) power[key] = value else failed = true }) }
        fanReadable.forEach { key -> request<Int>(
            { ok, bad -> hvac.getHvacFanSpeed(seats.getValue(key), onComplete = ok, onFailed = bad) },
            { value -> if (value != null && value >= 0) fans[key] = value else failed = true }) }
        frontDefrosters.forEach { area -> request<Boolean>(
            { ok, bad -> hvac.getHvacDefrosterState(area, onComplete = ok, onFailed = bad) },
            { value -> defrost.add(value); if (value == null) failed = true }) }
        maxDefrostReadable.forEach { key -> request<Boolean>(
            { ok, bad -> hvac.getHvacMaxDefrostState(seats.getValue(key), onComplete = ok, onFailed = bad) },
            { value -> defrost.add(value); if (value == null) failed = true }) }
        remaining = requests.size
        main.postDelayed({ if (current()) { failed = true; finish() } }, 3500)
        if (requests.isEmpty()) finish() else requests.forEach { it() }
    }

    fun setTemperature(areaId: String, celsius: Float, onResult: (ActionResult) -> Unit) {
        executeClimate(Routine(action = Action.SET_TEMPERATURE, targetAreaId = areaId, temperatureC = celsius), onResult = onResult)
    }

    fun executeClimate(routine: Routine, validateConditions: () -> Boolean = { true }, onResult: (ActionResult) -> Unit) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { executeClimate(routine, validateConditions, onResult) }; return }
        val sdk = vehicle
        val key = routine.targetAreaId
        val area = seats[key]
        val supported = when (routine.action) {
            Action.SET_TEMPERATURE -> temperatureAreas[key]?.accepts(routine.temperatureC) == true
            Action.AC_ON, Action.VENTILATE -> key in acWritable
            Action.SET_FAN -> fanWritable[key]?.contains(routine.fanSpeed) == true
            else -> false
        }
        if (writesRequireReconnect) {
            onResult(ActionResult(false, "이전 요청의 적용 여부가 불명확합니다. 차량을 다시 연결하세요.")); return
        }
        if (!active || pending != null || sdk == null || area == null || !supported) {
            onResult(ActionResult(false, "연결·지원 범위를 확인하거나 진행 중인 요청을 기다려 주세요.")); return
        }
        pending = onResult
        val token = session
        val id = ++writeId
        var writeSubmitted = false
        var writeAcknowledged = false
        fun current() = active && session == token && vehicle === sdk && writeId == id && pending != null
        fun finish(result: ActionResult) {
            if (!current()) return
            val callback = pending; pending = null
            // Do not publish values sampled before an uncertain write.
            climateReadId++; climateReadInFlight = false
            if (!result.confirmed) clearClimate("공조 적용 미확인 · 실제 값 재조회 중")
            callback?.invoke(result)
        }
        main.postDelayed({ if (current()) {
            // An unacknowledged setter can still complete later. Do not overlap
            // it with another write in the same SDK session.
            if (writeSubmitted && !writeAcknowledged) writesRequireReconnect = true
            finish(ActionResult(false, "요청 시간 초과 · 적용 여부 미확인"))
        } }, 10000)
        fun fail(e: Exception) { dispatch(token) { if (current()) finish(ActionResult(false, "공조 요청 실패: ${failure(e)}")) } }
        try { readClimate(token) { before ->
            if (!current()) return@readClimate
            if (before.defrostActive != false) {
                finish(ActionResult(false, "성에 제거 우선 · 앞유리 성에 제거와 최대 성에 제거 꺼짐을 확인할 수 없어 실행을 보류했습니다."))
                return@readClimate
            }
            val conditionsMatch = routine.effectiveConditions.all { condition ->
                when (condition.kind) {
                    ConditionKind.AC_STATE -> condition.acOn != null && areaValue(before.acStates, key) == condition.acOn
                    ConditionKind.POWER_STATE -> condition.powerOn != null && areaValue(before.powerStates, key) == condition.powerOn
                    ConditionKind.BATTERY_RANGE -> true
                    ConditionKind.PARKED_STATE -> true
                    ConditionKind.DEFROST_OFF -> condition.requireDefrostOff && before.defrostActive == false
                }
            }
            if (!conditionsMatch) {
                finish(ActionResult(false, "공조 추가 조건 불충족 또는 현재 상태 미확인 · 실행 보류"))
                return@readClimate
            }
            // SDK properties have independent grouping: one AC zone can span
            // the cabin while the readable fan zone covers just the front row.
            // Preserve every supported fan zone inside this AC target; missing
            // readings never stand in for a running fan.
            val originalFans = relevantFanAreas(key).associateWith { before.fanSpeeds[it] }
            if (routine.action == Action.VENTILATE && (areaValue(before.acStates, key) != true ||
                    areaValue(before.powerStates, key) != true || originalFans.isEmpty() ||
                    originalFans.values.any { it == null || it <= 0 } || before.defrostActive != false)) {
                finish(ActionResult(false, "송풍 전환 보류 · A/C·공조 전원·팬 작동 및 앞유리 성에 제거 꺼짐을 확인하세요."))
                return@readClimate
            }
            val hvac = try { sdk.getHVAC() } catch (e: Exception) { fail(e); return@readClimate }
            fun verify() {
                if (!current()) return
                try { readClimate(token) { after ->
                    if (!current()) return@readClimate
                    val confirmed = when (routine.action) {
                        Action.SET_TEMPERATURE -> after.temperatures[key]?.let { abs(it - routine.temperatureC) < .05f } == true
                        Action.AC_ON -> areaValue(after.acStates, key) == true
                        Action.SET_FAN -> after.fanSpeeds[key] == routine.fanSpeed
                        Action.VENTILATE -> areaValue(after.acStates, key) == false && areaValue(after.powerStates, key) == true &&
                            originalFans.isNotEmpty() && relevantFanAreas(key) == originalFans.keys &&
                            originalFans.all { (fanKey, speed) -> speed != null && speed > 0 && after.fanSpeeds[fanKey] == speed } &&
                            after.defrostActive == false
                        else -> false
                    }
                    if (confirmed) finish(ActionResult(true, when (routine.action) {
                        Action.SET_TEMPERATURE -> "적용 확인 · ${routine.temperatureC}°C"
                        Action.AC_ON -> "적용 확인 · A/C 켜짐"
                        Action.SET_FAN -> "적용 확인 · 팬 ${routine.fanSpeed}단"
                        Action.VENTILATE -> "적용 확인 · A/C 꺼짐 · 공조 전원과 팬 ${originalFans.values.filterNotNull().distinct().joinToString("/")}단 유지"
                        else -> "적용 확인"
                    })) else finish(ActionResult(false, "요청 완료 · 실제 값으로 적용을 확인하지 못했습니다."))
                } } catch (e: Exception) { fail(e) }
            }
            fun acknowledge() { dispatch(token) {
                if (current() && !writeAcknowledged) { writeAcknowledged = true; main.postDelayed({ verify() }, 350) }
            } }
            // Never change power, defroster, max-defrost, or implicitly adjust fan.
            try {
                if (!validateConditions()) {
                    finish(ActionResult(false, "실행 직전 조건 변경 또는 현재 상태 미확인 · 실행 보류"))
                    return@readClimate
                }
                if (!current()) return@readClimate
                writeSubmitted = true
                when (routine.action) {
                    Action.SET_TEMPERATURE -> hvac.setHvacTemperature(area, routine.temperatureC, TemperatureUnit.CELSIUS,
                        onComplete = { _, _ -> acknowledge() }, onFailed = ::fail)
                    Action.AC_ON -> hvac.setHvacAc(area, true, onComplete = { _, _ -> acknowledge() }, onFailed = ::fail)
                    Action.VENTILATE -> hvac.setHvacAc(area, false, onComplete = { _, _ -> acknowledge() }, onFailed = ::fail)
                    Action.SET_FAN -> hvac.setHvacFanSpeed(area, routine.fanSpeed, onComplete = { _, _ -> acknowledge() }, onFailed = ::fail)
                    else -> finish(ActionResult(false, "지원되지 않는 공조 동작입니다."))
                }
            } catch (e: Exception) { fail(e) }
        } } catch (e: Exception) { fail(e) }
    }

    fun stop() {
        active = false; session++; writeId++; climateReadId++; batteryReadId++; writesRequireReconnect = false
        observer = {}; seats.clear(); temperatureAreas.clear(); acReadable.clear(); acWritable.clear()
        powerReadable.clear(); fanReadable.clear(); fanWritable.clear(); maxDefrostReadable.clear(); frontDefrosters = emptyList()
        batteryReadable = false; lastBatteryRead = 0L; lastClimateRead = 0L
        readInFlight = false; climateReadInFlight = false
        val callback = pending; pending = null
        val sdk = vehicle; vehicle = null
        listener?.let { runCatching { sdk?.getEvBattery()?.unregisterEvBatteryStateOfCharge(it) } }; listener = null
        runCatching { sdk?.release() }
        callback?.invoke(ActionResult(false, "차량 연결 종료 · 진행 중인 요청의 적용 여부 미확인"))
    }
    private fun seatLabel(name: String) = when (name) {
        "ROW_1_LEFT" -> "앞좌석 왼쪽"; "ROW_1_RIGHT" -> "앞좌석 오른쪽"
        "ROW_2_LEFT" -> "뒷좌석 왼쪽"; "ROW_2_RIGHT" -> "뒷좌석 오른쪽"; else -> name
    }
}
