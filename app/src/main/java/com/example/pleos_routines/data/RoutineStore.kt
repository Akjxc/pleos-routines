package com.example.pleos_routines.data

import android.content.Context
import com.example.pleos_routines.model.*
import org.json.JSONArray
import org.json.JSONObject

class RoutineStore(context: Context) {
    private val prefs = context.getSharedPreferences("pleos_routines", Context.MODE_PRIVATE)
    var recoveryWarning: String? = null
        private set

    fun load(): StoredState {
        val raw = prefs.getString("state", null) ?: return StoredState(templates())
        return try {
            val root = JSONObject(raw)
            val schemaVersion = root.optInt("schemaVersion", 1)
            require(schemaVersion in 1..4)
            fun noticeMessage(id: String, message: String): String =
                if (id == "template-ac-dry" &&
                    message == "A/C를 끄고 5분간 에어컨 건조를 시작합니다.")
                    "A/C를 끄고 현재 송풍을 유지합니다." else message
            val array = root.getJSONArray("routines")
            val loadedRoutines = (0 until array.length()).map {
                val r = array.getJSONObject(it)
                val days = mutableListOf<Int>()
                if (r.has("days")) {
                    val daysArr = r.getJSONArray("days")
                    for (i in 0 until daysArr.length()) {
                        days.add(daysArr.getInt(i))
                    }
                }
                val actions = mutableListOf<ActionItem>()
                if (r.has("actions")) {
                    val actArr = r.getJSONArray("actions")
                    for (i in 0 until actArr.length()) {
                        val actObj = actArr.getJSONObject(i)
                        actions.add(ActionItem(
                            action = Action.valueOf(actObj.getString("action")),
                            targetAreaId = actObj.optString("area", ""),
                            temperatureC = actObj.optDouble("temperature", 23.0).toFloat(),
                            message = noticeMessage(r.getString("id"), actObj.optString("message", "안내문")),
                            fanSpeed = actObj.optInt("fan", 3)
                        ))
                    }
                }
                Routine(
                    id = r.getString("id"),
                    name = r.getString("name"),
                    enabled = r.getBoolean("enabled"),
                    trigger = Trigger.valueOf(r.getString("trigger")),
                    action = Action.valueOf(r.getString("action")),
                    thresholdPercent = r.getInt("threshold"),
                    message = noticeMessage(r.getString("id"), r.getString("message")),
                    targetAreaId = r.getString("area"),
                    temperatureC = r.getDouble("temperature").toFloat(),
                    hour = r.optInt("hour", 8),
                    minute = r.optInt("minute", 0),
                    latitude = if (r.has("latitude")) r.getDouble("latitude") else null,
                    longitude = if (r.has("longitude")) r.getDouble("longitude") else null,
                    radiusMeters = r.optDouble("radius", 500.0).toFloat(),
                    locationExit = r.optBoolean("exit", false),
                    gearParked = r.optBoolean("gearParked", true),
                    fanSpeed = r.optInt("fan", 3),
                    conditions = r.optJSONObject("conditions")?.let { c ->
                        val items = c.optJSONArray("items")?.let { a -> (0 until a.length()).mapNotNull { i ->
                            a.optJSONObject(i)?.let { x -> RoutineCondition(
                                kind = runCatching { ConditionKind.valueOf(x.getString("kind")) }.getOrNull() ?: return@let null,
                                acOn = if (x.has("ac")) x.getBoolean("ac") else null,
                                powerOn = if (x.has("power")) x.getBoolean("power") else null,
                                batteryMin = if (x.has("min")) x.getInt("min") else null,
                                batteryMax = if (x.has("max")) x.getInt("max") else null,
                                parked = if (x.has("parked")) x.getBoolean("parked") else null,
                                requireDefrostOff = x.optBoolean("defrostOff", false)
                            ) }
                        } }.orEmpty()
                        RoutineConditions(
                            acOn = if (c.has("ac")) c.getBoolean("ac") else null,
                            powerOn = if (c.has("power")) c.getBoolean("power") else null,
                            batteryMin = if (c.has("min")) c.getInt("min") else null,
                            batteryMax = if (c.has("max")) c.getInt("max") else null,
                            parked = if (c.has("parked")) c.getBoolean("parked") else null,
                            requireDefrostOff = c.optBoolean("defrostOff", false),
                            items = items
                        )
                    } ?: RoutineConditions(),
                    daysOfWeek = days,
                    actions = actions,
                    runCount = r.optInt("runCount", 0),
                    lastRunTimeMs = r.optLong("lastRunTimeMs", 0L)
                )
            }
            val existingIds = loadedRoutines.map { it.id }.toSet()
            // Older versions cannot distinguish deleted presets from never offered ones.
            // Preserve their absence and only introduce the new arrival preset once.
            val offered = root.optJSONArray("offeredTemplateIds")?.let { a ->
                (0 until a.length()).map { a.getString(it) }.toSet()
            } ?: templates().filterNot { it.id == "template-arrival" }.map { it.id }.toSet()
            val missingTemplates = templates().filter { it.id !in offered && it.id !in existingIds }
                .take((30 - loadedRoutines.size).coerceAtLeast(0))
            val routines = loadedRoutines + missingTemplates

            require(routines.size <= 30 && routines.map { it.id }.distinct().size == routines.size)
            require(routines.all {
                it.id.isNotBlank() && it.name.isNotBlank() && it.temperatureC.isFinite() &&
                    it.thresholdPercent in 1..95 && it.hour in 0..23 && it.minute in 0..59
            })
            val latchArray = root.getJSONArray("latched")
            val records = root.getJSONArray("records")
            val restored = StoredState(
                routines,
                (0 until latchArray.length()).map { latchArray.getString(it) }.toSet(),
                (0 until records.length()).map {
                    val record = records.getJSONObject(it)
                    RunRecord(record.getLong("time"), record.getString("name"), record.getString("detail"))
                }.take(20),
                offered + templates().map { it.id }
            )
            if (schemaVersion < 4 || restored.offeredTemplateIds != offered) {
                if (!save(restored)) recoveryWarning = "이전 루틴은 읽었지만 저장 형식 갱신을 저장하지 못했습니다."
            }
            restored
        } catch (_: Exception) {
            recoveryWarning = "저장 데이터를 읽지 못해 비활성 템플릿으로 복구했습니다."
            StoredState(templates())
        }
    }

    fun save(state: StoredState): Boolean = prefs.edit().putString("state", JSONObject().apply {
        put("schemaVersion", 4)
        put("offeredTemplateIds", JSONArray(state.offeredTemplateIds.toList()))
        put("routines", JSONArray().apply {
            state.routines.forEach { r ->
                put(JSONObject().apply {
                    put("id", r.id)
                    put("name", r.name)
                    put("enabled", r.enabled)
                    put("trigger", r.trigger.name)
                    put("action", r.action.name)
                    put("threshold", r.thresholdPercent)
                    put("message", r.message)
                    put("area", r.targetAreaId)
                    put("temperature", r.temperatureC)
                    put("hour", r.hour)
                    put("minute", r.minute)
                    put("latitude", r.latitude)
                    put("longitude", r.longitude)
                    put("radius", r.radiusMeters)
                    put("exit", r.locationExit)
                    put("gearParked", r.gearParked)
                    put("fan", r.fanSpeed)
                    put("runCount", r.runCount)
                    put("lastRunTimeMs", r.lastRunTimeMs)
                    put("days", JSONArray(r.daysOfWeek))
                    put("actions", JSONArray().apply {
                        r.actions.forEach { act ->
                            put(JSONObject().apply {
                                put("action", act.action.name)
                                put("area", act.targetAreaId)
                                put("temperature", act.temperatureC)
                                put("message", act.message)
                                put("fan", act.fanSpeed)
                            })
                        }
                    })
                    put("conditions", JSONObject().apply {
                        val legacy = r.effectiveConditions
                        put("ac", legacy.firstOrNull { it.kind == ConditionKind.AC_STATE }?.acOn)
                        put("power", legacy.firstOrNull { it.kind == ConditionKind.POWER_STATE }?.powerOn)
                        put("min", legacy.firstOrNull { it.kind == ConditionKind.BATTERY_RANGE }?.batteryMin)
                        put("max", legacy.firstOrNull { it.kind == ConditionKind.BATTERY_RANGE }?.batteryMax)
                        put("parked", legacy.firstOrNull { it.kind == ConditionKind.PARKED_STATE }?.parked)
                        put("defrostOff", legacy.any { it.kind == ConditionKind.DEFROST_OFF && it.requireDefrostOff })
                        put("items", JSONArray().apply {
                            r.effectiveConditions.forEach { item -> put(JSONObject().apply {
                                put("kind", item.kind.name)
                                item.acOn?.let { put("ac", it) }
                                item.powerOn?.let { put("power", it) }
                                item.batteryMin?.let { put("min", it) }
                                item.batteryMax?.let { put("max", it) }
                                item.parked?.let { put("parked", it) }
                                if (item.requireDefrostOff) put("defrostOff", true)
                            }) }
                        })
                    })
                })
            }
        })
        put("latched", JSONArray(state.latched.toList()))
        put("records", JSONArray().apply {
            state.records.take(20).forEach { r ->
                put(JSONObject().apply {
                    put("time", r.time)
                    put("name", r.routineName)
                    put("detail", r.detail)
                })
            }
        })
    }.toString()).commit()
}
