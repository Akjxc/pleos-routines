package com.example.pleos_routines

import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import com.example.pleos_routines.data.RoutineStore
import com.example.pleos_routines.model.*
import com.example.pleos_routines.ui.RoutineEditorDialog
import com.example.pleos_routines.vehicle.VehicleSnapshot
import com.example.pleos_routines.vehicle.PleosVehicleGateway
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class EditorAndStoreTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private fun isolatedContext(): Context = object : ContextWrapper(base) {
        private val namespace = "store-test-${System.nanoTime()}"
        override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences(namespace, mode)
    }

    @Test fun deletedPresetAndEmptyListStayDeletedAfterReload() {
        val context = isolatedContext()
        try {
            val store = RoutineStore(context)
            val initial = store.load()
            assertTrue(store.save(initial.copy(routines = initial.routines.filterNot { it.id == "template-battery" })))
            assertFalse(RoutineStore(context).load().routines.any { it.id == "template-battery" })
            assertTrue(store.save(StoredState(emptyList())))
            assertTrue(RoutineStore(context).load().routines.isEmpty())
        } finally { context.getSharedPreferences("pleos_routines", 0).edit().clear().commit() }
    }

    @Test fun legacyUserDataSurvivesAndArrivalPresetIsOfferedOnlyOnce() {
        val context = isolatedContext()
        try {
            val original = Routine(id = "custom", name = "내 설정", runCount = 7, message = "유지")
            val store = RoutineStore(context)
            assertTrue(store.save(StoredState(listOf(original))))
            val prefs = context.getSharedPreferences("pleos_routines", 0)
            val legacy = JSONObject(prefs.getString("state", null)!!).put("schemaVersion", 3)
            legacy.remove("offeredTemplateIds")
            assertTrue(prefs.edit().putString("state", legacy.toString()).commit())
            val loaded = RoutineStore(context).load()
            assertEquals(original, loaded.routines.first { it.id == "custom" })
            assertEquals(setOf("custom", "template-arrival"), loaded.routines.map { it.id }.toSet())
            assertFalse(loaded.routines.first { it.id == "template-arrival" }.enabled)
            assertTrue(store.save(loaded.copy(routines = listOf(original))))
            assertEquals(listOf(original), RoutineStore(context).load().routines)
        } finally { context.getSharedPreferences("pleos_routines", 0).edit().clear().commit() }
    }

    @Test fun fullLegacyListIsNotResetWhenNewPresetIsIntroduced() {
        val context = isolatedContext()
        try {
            val routines = (1..30).map { Routine(id = "custom-$it", name = "내 루틴 $it") }
            val store = RoutineStore(context)
            assertTrue(store.save(StoredState(routines)))
            val prefs = context.getSharedPreferences("pleos_routines", 0)
            val legacy = JSONObject(prefs.getString("state", null)!!).put("schemaVersion", 3)
            legacy.remove("offeredTemplateIds")
            assertTrue(prefs.edit().putString("state", legacy.toString()).commit())
            assertEquals(routines, RoutineStore(context).load().routines)
        } finally { context.getSharedPreferences("pleos_routines", 0).edit().clear().commit() }
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    @Test fun stoppingScreenPersistsAlreadyCompletedActionsAndCancellation() {
        val store = RoutineStore(base)
        val original = store.load()
        val name = "화면 종료 기록 검증"
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val snapshotField = MainActivity::class.java.getDeclaredField("vehicle").apply { isAccessible = true }
                val routinesField = MainActivity::class.java.getDeclaredField("routines").apply { isAccessible = true }
                val gatewayField = MainActivity::class.java.getDeclaredField("gateway").apply { isAccessible = true }
                var snapshot = VehicleSnapshot()
                val deadline = System.currentTimeMillis() + 15000
                while (snapshot.areas.none { it.choices().isNotEmpty() } && System.currentTimeMillis() < deadline) {
                    scenario.onActivity { snapshot = snapshotField.get(it) as VehicleSnapshot }
                    Thread.sleep(20)
                }
                val area = snapshot.areas.first { it.choices().isNotEmpty() }
                val routine = Routine(id = "test-screen-stop", name = name, enabled = true,
                    actions = listOf(ActionItem(message = "첫 안내 완료"),
                        ActionItem(Action.SET_TEMPERATURE, area.id, snapshot.temperatures[area.id] ?: 22f)))
                val destroyed = CountDownLatch(1)
                scenario.onActivity { activity ->
                    activity.lifecycle.addObserver(LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_DESTROY) destroyed.countDown()
                    })
                    routinesField.set(activity, original.routines + routine)
                    MainActivity::class.java.getDeclaredMethod("runManual", Routine::class.java)
                        .apply { isAccessible = true }.invoke(activity, routine)
                    val main = Handler(Looper.getMainLooper())
                    val pendingDeadline = System.currentTimeMillis() + 5000
                    main.post(object : Runnable {
                        override fun run() {
                            if ((gatewayField.get(activity) as PleosVehicleGateway).hasPendingRequest) activity.finish()
                            else if (System.currentTimeMillis() < pendingDeadline) main.postDelayed(this, 10)
                        }
                    })
                }
                assertTrue("진행 중인 공조 요청에서 화면이 종료되지 않음", destroyed.await(10, TimeUnit.SECONDS))
                val record = store.load().records.firstOrNull { it.routineName == name }
                assertNotNull("화면 종료 시 부분 실행 기록이 사라짐", record)
                assertTrue(record!!.detail, record.detail.contains("1. 성공"))
                assertTrue(record.detail, record.detail.contains("2. 실패"))
                assertEquals(0, store.load().routines.first { it.id == routine.id }.runCount)
            }
        } finally { assertTrue(store.save(original)) }
    }

    @Test fun selectingSameActionTypeLoadsEachCardsOwnAreaAndTemperature() {
        var dialog: AlertDialog? = null
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                scenario.onActivity { activity ->
                    val routine = Routine(actions = listOf(
                        ActionItem(Action.SET_TEMPERATURE, "driver", 22f),
                        ActionItem(Action.SET_TEMPERATURE, "passenger", 24f)
                    ))
                    val snapshot = VehicleSnapshot(areas = listOf(
                        TemperatureArea("driver", "운전석", 16f, 30f, .5f),
                        TemperatureArea("passenger", "조수석", 16f, 30f, .5f)
                    ))
                    dialog = RoutineEditorDialog(activity, routine, snapshot, null,
                        save = { _, done -> done(null) }, delete = { it(null) }).show()
                }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                scenario.onActivity {
                    val views = descendants(dialog!!.window!!.decorView)
                    val card = views.filterIsInstance<TextView>().first { it.text.toString() == "2. 24.0°C" }
                    (card.parent as View).performClick()
                }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                scenario.onActivity {
                    val spinners = descendants(dialog!!.window!!.decorView).filterIsInstance<Spinner>()
                    assertEquals("조수석", spinners.first { it.contentDescription == "공조 영역" }.selectedItem)
                    assertEquals("24.0°C", spinners.first { it.contentDescription == "설정 온도" }.selectedItem)
                    val card = descendants(dialog!!.window!!.decorView).filterIsInstance<TextView>()
                        .first { it.text.toString() == "1. 22.0°C" }
                    (card.parent as View).performClick()
                }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                scenario.onActivity {
                    val spinners = descendants(dialog!!.window!!.decorView).filterIsInstance<Spinner>()
                    assertEquals("운전석", spinners.first { it.contentDescription == "공조 영역" }.selectedItem)
                    assertEquals("22.0°C", spinners.first { it.contentDescription == "설정 온도" }.selectedItem)
                }
            } finally { scenario.onActivity { dialog?.dismiss() } }
        }
    }
}
