package com.example.pleos_routines

import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.pleos_routines.data.RoutineStore
import com.example.pleos_routines.model.*
import com.example.pleos_routines.routine.RoutineEngine
import com.example.pleos_routines.routine.RoutineActionRunner
import com.example.pleos_routines.ui.RoutineEditorDialog
import com.example.pleos_routines.vehicle.*
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {
    private lateinit var store: RoutineStore
    private lateinit var engine: RoutineEngine
    private lateinit var gateway: PleosVehicleGateway
    private lateinit var parking: ParkingStateSource
    private var routines = listOf<Routine>()
    private var records = listOf<RunRecord>()
    private var vehicle = VehicleSnapshot()
    private var started = false
    private var busy = false
    private var runningRoutineId: String? = null
    private val routineStateViews = mutableMapOf<String, TextView>()
    private var screenSession = 0
    private var editor: AlertDialog? = null
    private lateinit var locationSource: PleosLocationSource
    private var currentLocation: GeoPoint? = null
    private var locationStatus = "위치 권한을 허용하면 위치 트리거를 사용할 수 있습니다"
    private val ticker = Handler(Looper.getMainLooper())
    private val automaticQueue = ArrayDeque<Pair<Routine, Long>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val root = findViewById<View>(R.id.root)
        val pad = dp(28)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, pad + bars.bottom); insets
        }
        store = RoutineStore(this)
        val saved = store.load(); routines = saved.routines; records = saved.records
        engine = RoutineEngine(saved.latched.toMutableSet())
        gateway = PleosVehicleGateway(applicationContext); parking = ParkingStateSource(applicationContext)
        locationSource = PleosLocationSource(applicationContext)
        findViewById<View>(R.id.location_permission).setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, "pleos.car.permission.FUSED_LOCATION") != PackageManager.PERMISSION_GRANTED)
                requestPermissions(arrayOf("pleos.car.permission.FUSED_LOCATION"), 201)
            else startLocation()
        }
        findViewById<View>(R.id.add_routine).setOnClickListener { edit(null) }
        findViewById<View>(R.id.reconnect).setOnClickListener {
            if (busy) notice("진행 중인 공조 요청을 기다려 주세요.") else connect()
        }
        store.recoveryWarning?.let { notice(it) }
        renderRoutines(); renderHistory()
    }

    override fun onStart() {
        super.onStart(); started = true; engine.resetTransitions(); connect(); startLocation()
        val token = screenSession
        ticker.post(object : Runnable {
            override fun run() {
                if (!started || token != screenSession) return
                evaluateBattery(); ticker.postDelayed(this, 1000)
            }
        })
    }

    private fun startLocation() {
        if (ContextCompat.checkSelfPermission(this, "pleos.car.permission.FUSED_LOCATION") != PackageManager.PERMISSION_GRANTED) {
            currentLocation = null; locationStatus = "위치 권한 필요 · 위치 권한 버튼을 누르세요"; renderStatus(); return
        }
        locationSource.start({ point -> currentLocation = point; renderStatus(); evaluateBattery() }, { status -> locationStatus = status; renderStatus() })
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 201 && started) startLocation()
    }

    override fun onStop() {
        if (!gateway.hasPendingRequest) busy = false
        screenSession++; started = false; ticker.removeCallbacksAndMessages(null); automaticQueue.clear(); locationSource.stop(); currentLocation = null; editor?.dismiss(); editor = null
        gateway.stop(); parking.stop(); super.onStop()
    }

    private fun connect() {
        vehicle = VehicleSnapshot()
        parking.start { renderStatus(); updateControls(); evaluateBattery() }
        gateway.start { next ->
            val areasChanged = next.areas != vehicle.areas
            vehicle = next; renderStatus(); evaluateBattery()
            if (areasChanged) renderRoutines()
        }
    }

    private fun renderStatus() {
        val gear = when (parking.parked) { true -> "P · 설정 가능"; false -> "주행 기어 · 설정 잠김"; null -> "기어 확인 불가 · 설정 잠김" }
        val temperatures = vehicle.areas.joinToString(" / ") { "${it.label} ${vehicle.temperatures[it.id]?.let { t -> "${t}°C" } ?: "--"}" }
        findViewById<TextView>(R.id.vehicle_status).text = "배터리 ${vehicle.soc?.let { "$it%" } ?: "--"}     |     $gear\n" +
            "${vehicle.batteryStatus}   ·   ${vehicle.climateStatus}" + (if (temperatures.isEmpty()) "" else "\n$temperatures") +
            "\n$locationStatus" + (currentLocation?.let { " · %.5f, %.5f".format(it.latitude, it.longitude) } ?: "")
    }

    private fun updateControls() {
        findViewById<View>(R.id.add_routine).isEnabled = parking.parked == true && !busy
        findViewById<View>(R.id.reconnect).isEnabled = !busy
    }

    private fun save(): Boolean {
        val ok = store.save(StoredState(routines, engine.latched.toSet(), records))
        if (!ok) notice("저장하지 못했습니다. 저장 공간을 확인하세요.")
        return ok
    }

    private fun notice(text: String) {
        findViewById<TextView>(R.id.notice_banner).apply { this.text = text; visibility = View.VISIBLE }
    }

    private fun record(routine: Routine, detail: String, countAsRun: Boolean = true) {
        // A queued event may hold an older snapshot; never overwrite newer settings/statistics.
        routines = routines.map { current ->
            if (countAsRun && current.id == routine.id)
                current.copy(runCount = current.runCount + 1, lastRunTimeMs = System.currentTimeMillis())
            else current
        }
        records = (listOf(RunRecord(System.currentTimeMillis(), routine.name, detail)) + records).take(20)
        save()
        if (started) { renderRoutines(); renderHistory() }
    }

    private fun environment(): Environment {
        val now = System.currentTimeMillis(); val c = Calendar.getInstance()
        val calDay = c.get(Calendar.DAY_OF_WEEK)
        val isoDay = when (calDay) {
            Calendar.MONDAY -> 1; Calendar.TUESDAY -> 2; Calendar.WEDNESDAY -> 3
            Calendar.THURSDAY -> 4; Calendar.FRIDAY -> 5; Calendar.SATURDAY -> 6
            Calendar.SUNDAY -> 7; else -> 1
        }
        return Environment(
            now, SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(now)), c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE),
            dayOfWeek = isoDay, soc = vehicle.soc, parked = parking.parked, location = currentLocation,
            acStates = vehicle.acStates, powerStates = vehicle.powerStates, defrostActive = vehicle.defrostActive
        )
    }

    private fun routineProblem(r: Routine): String? = r.validationError(vehicle.areas) ?: run {
        r.effectiveActions.firstNotNullOfOrNull { actionItem ->
            when {
                (actionItem.action == Action.AC_ON || actionItem.action == Action.VENTILATE) &&
                    actionItem.targetAreaId !in vehicle.acAreas -> "이 영역의 A/C 제어를 지원하지 않습니다."
                actionItem.action == Action.SET_FAN &&
                    vehicle.fanRanges[actionItem.targetAreaId]?.contains(actionItem.fanSpeed) != true ->
                    "지원되는 송풍 세기를 선택하세요."
                else -> null
            }
        }
    }

    private fun executeRoutineActions(
        routine: Routine,
        validateConditions: () -> Boolean,
        onResult: (ActionResult) -> Unit
    ) {
        val conditionArea = routine.effectiveActions.firstOrNull { it.targetAreaId.isNotBlank() }?.targetAreaId
            ?: routine.targetAreaId
        RoutineActionRunner(::environment, validateConditions, { action, guard, done ->
            gateway.executeClimate(action, guard, conditionAreaId = conditionArea, onResult = done)
        }, ::notice).run(routine, onResult)
    }

    private fun evaluateBattery() {
        if (!started) return
        renderRoutineStates()
        val previous = engine.latched.toSet()
        val matches = engine.evaluate(routines, environment())
        if (previous != engine.latched && !save()) {
            engine.latched.clear(); engine.latched.addAll(previous); return
        }
        matches.forEach { routine ->
            if (automaticQueue.none { it.first.id == routine.id }) automaticQueue.addLast(routine to System.currentTimeMillis())
        }
        drainAutomatic()
    }

    private fun drainAutomatic() {
        if (busy || !started || automaticQueue.isEmpty()) return
        val (event, at) = automaticQueue.removeFirst()
        val current = routines.find { it.id == event.id }
        val problem = routineProblem(event)
        if (System.currentTimeMillis() - at > 30000 || current != event || !event.enabled || !engine.conditionsMatch(event, environment()) || problem != null) {
            record(event, "자동 실행 건너뜀 · ${problem ?: "조건 변경 또는 이벤트 만료"}", countAsRun = false)
            drainAutomatic(); return
        }
        busy = true; runningRoutineId = event.id; renderRoutines(); updateControls(); val token = screenSession
        executeRoutineActions(event, validateConditions = {
            started && token == screenSession &&
                routines.find { it.id == event.id } == event
        }) { result ->
            busy = false; runningRoutineId = null
            record(event, result.detail, countAsRun = result.confirmed)
            if (started && token == screenSession) {
                notice("${event.name} · ${result.detail}"); renderRoutines(); drainAutomatic()
            }
        }
    }

    private fun guarded(block: () -> Unit, denied: () -> Unit = { notice("P단 확인 후 사용할 수 있습니다. 차량 연결과 기어를 확인하세요.") }) {
        if (busy) { notice("공조 요청 처리 중입니다."); return }
        parking.requireParked({ if (started && !busy) block() }, denied)
    }

    private fun edit(routine: Routine?) = guarded({
        if (routine == null && routines.size >= 30) { notice("MVP에서는 최대 30개 루틴을 저장할 수 있습니다."); return@guarded }
        editor = RoutineEditorDialog(this, routine, vehicle, currentLocation,
            save = { updated, complete ->
                parking.requireParked({
                    val problem = routineProblem(updated)
                    if (problem != null) complete(problem) else {
                        val old = routines; val oldLatch = engine.latched.toSet()
                        engine.onEdited(routines.find { it.id == updated.id }, updated)
                        routines = if (routine == null) routines.filterNot { it.id == updated.id } + updated else routines.map { if (it.id == updated.id) updated else it }
                        if (save()) { complete(null); renderRoutines(); evaluateBattery() }
                        else { routines = old; engine.latched.clear(); engine.latched.addAll(oldLatch); complete("저장 실패") }
                    }
                }, { complete("P단 확인 후 저장할 수 있습니다.") })
            }, delete = { complete ->
                parking.requireParked({
                    val old = routines; val oldLatch = engine.latched.toSet()
                    routines = routines.filterNot { it.id == routine?.id }; engine.latched.remove(routine?.id)
                    if (save()) { complete(null); renderRoutines() }
                    else { routines = old; engine.latched.clear(); engine.latched.addAll(oldLatch); complete("삭제 저장 실패") }
                }, { complete("P단 확인 후 삭제할 수 있습니다.") })
            }).show()
    })

    private fun toggle(routine: Routine) = guarded({
        val current = routines.find { it.id == routine.id } ?: return@guarded
        val updated = current.copy(enabled = !current.enabled)
        val error = if (updated.enabled) routineProblem(updated) else null
        if (error != null) { notice("편집에서 설정을 확인하세요. $error"); renderRoutines(); return@guarded }
        val old = routines; routines = routines.map { if (it.id == updated.id) updated else it }
        if (!save()) routines = old
        renderRoutines(); evaluateBattery()
    }, { notice("P단에서 활성 상태를 변경할 수 있습니다."); renderRoutines() })

    private fun runManual(routine: Routine) {
        if (!routine.enabled || routine.trigger != Trigger.MANUAL) return
        if (!engine.conditionsMatch(routine, environment())) { notice("추가 조건을 충족하지 않거나 상태를 확인할 수 없습니다."); return }
        if (busy) return
        busy = true; runningRoutineId = routine.id; updateControls(); renderRoutines()
        val token = screenSession
        parking.requireParked({
            val current = routines.find { it.id == routine.id }
            if (!started || token != screenSession || current?.enabled != true || current != routine) {
                busy = false; runningRoutineId = null; if (started) renderRoutines(); return@requireParked
            }
            val error = routineProblem(current) ?: if (!engine.conditionsMatch(current, environment())) "추가 조건이 변경되었습니다." else null
            if (error != null) { busy = false; runningRoutineId = null; notice(error); renderRoutines(); return@requireParked }
            notice("${current.name} · 공조 요청 중")
            executeRoutineActions(current, validateConditions = {
                started && token == screenSession &&
                    parking.parked == true &&
                    routines.find { it.id == current.id } == current
            }) { result ->
                busy = false; runningRoutineId = null
                record(routine, result.detail, countAsRun = result.confirmed)
                if (token != screenSession) { if (started) { renderRoutines(); updateControls() }; return@executeRoutineActions }
                if (started) { notice("${routine.name} · ${result.detail}"); renderRoutines(); updateControls(); drainAutomatic() }
            }
        }, { busy = false; runningRoutineId = null; if (started) { notice("P단 확인 후 공조를 실행할 수 있습니다."); renderRoutines(); updateControls() } })
    }

    private fun renderRoutines() {
        val list = findViewById<LinearLayout>(R.id.routine_list); list.removeAllViews()
        routineStateViews.clear()
        if (routines.isEmpty()) list.addView(text("아직 루틴이 없습니다. + 루틴 추가로 시작하세요.", 18))
        routines.forEach { routine ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(16))
                background = GradientDrawable().apply { setColor(Color.rgb(27, 43, 64)); cornerRadius = dp(16).toFloat() }
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) }
            }
            val row = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
            row.addView(text(routine.name, 22).apply { setTypeface(typeface, Typeface.BOLD) }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(SwitchMaterial(this).apply {
                setTextColor(Color.rgb(214, 226, 240))
                text = if (routine.enabled) "사용 중" else "꺼짐"; isChecked = routine.enabled; isEnabled = !busy
                contentDescription = "${routine.name} 활성 상태"; setOnClickListener { isChecked = routine.enabled; toggle(routine) }
            })
            card.addView(row)
            val lastRun = if (routine.lastRunTimeMs > 0L) {
                SimpleDateFormat("MM/dd HH:mm", Locale.KOREA).format(Date(routine.lastRunTimeMs))
            } else {
                "없음"
            }
            card.addView(text(routine.summary(), 17))
            val state = text("", 15).apply { setTextColor(Color.rgb(115, 223, 192)) }
            routineStateViews[routine.id] = state
            card.addView(state)
            card.addView(text("실행 ${routine.runCount}회 · 최근 실행 $lastRun", 14).apply {
                setTextColor(Color.rgb(185, 201, 216))
                setPadding(0, dp(4), 0, dp(4))
            })
            val firstAction = routine.effectiveActions.first()
            card.addView(text(
                if (firstAction.action == Action.NOTICE) firstAction.message else
                    vehicle.areas.find { it.id == firstAction.targetAreaId }?.label ?: "편집에서 공조 영역을 선택하세요.", 15
            ).apply {
                setTextColor(Color.rgb(185, 201, 216)); setPadding(0, dp(8), 0, dp(8))
            })
            val buttons = LinearLayout(this)
            buttons.addView(MaterialButton(this).apply { text = "편집"; isEnabled = !busy; setOnClickListener { edit(routine) } })
            if (routine.trigger == Trigger.MANUAL) buttons.addView(MaterialButton(this).apply {
                text = if (busy) "처리 중" else "실행"; isEnabled = routine.enabled && !busy
                setOnClickListener { runManual(routine) }
            }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12) })
            card.addView(buttons); list.addView(card)
        }
        renderRoutineStates()
        updateControls()
    }

    private fun renderRoutineStates() {
        val env = environment()
        routines.forEach { routine ->
            routineStateViews[routine.id]?.text = when {
                busy && runningRoutineId == routine.id -> "실행 중"
                !routine.enabled -> "꺼짐"
                else -> routineProblem(routine)?.let { "설정 필요 · $it" }
                    ?: engine.waitingReason(routine, env)
            }
        }
    }

    private fun renderHistory() {
        val format = SimpleDateFormat("MM/dd HH:mm:ss", Locale.KOREA)
        findViewById<TextView>(R.id.history).text = records.joinToString("\n\n") {
            "${format.format(Date(it.time))} · ${it.routineName}\n${it.detail}"
        }.ifEmpty { "아직 실행 기록이 없습니다.\n루틴을 활성화하고 실행해 보세요." }
    }

    private fun text(value: String, size: Int) = TextView(this).apply { text = value; textSize = size.toFloat(); setTextColor(Color.WHITE) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
