package com.example.pleos_routines

import com.example.pleos_routines.model.*
import com.example.pleos_routines.routine.RoutineEngine
import org.junit.Assert.*
import org.junit.Test

class RoutineEngineTest {
    private val routine = Routine(id = "battery", name = "배터리", trigger = Trigger.BATTERY_BELOW,
        action = Action.NOTICE, enabled = true)

    @Test fun thresholdCrossingFiresOnceUntilThreePointRecovery() {
        val engine = RoutineEngine()
        val counts = listOf(50, 30, 29, 30, 32, 30, 33, 30).map {
            engine.evaluateBattery(listOf(routine), it).size
        }
        assertEquals(listOf(0, 1, 0, 0, 0, 0, 0, 1), counts)
    }

    @Test fun initialLowValueFiresButInvalidValuesDoNot() {
        val engine = RoutineEngine()
        listOf(null, -1, 101).forEach { assertTrue(engine.evaluateBattery(listOf(routine), it).isEmpty()) }
        assertEquals(listOf("battery"), engine.evaluateBattery(listOf(routine), 29).map { it.id })
    }

    @Test fun restoredLatchAndEnableToggleDoNotDuplicateNotice() {
        val engine = RoutineEngine(mutableSetOf("battery"))
        assertTrue(engine.evaluateBattery(listOf(routine.copy(enabled = false)), 29).isEmpty())
        assertTrue(engine.evaluateBattery(listOf(routine), 29).isEmpty())
        engine.evaluateBattery(listOf(routine.copy(enabled = false)), 50)
        assertEquals(1, engine.evaluateBattery(listOf(routine), 29).size)
    }

    @Test fun manualAndDisabledRoutinesNeverAutoRun() {
        assertTrue(RoutineEngine().evaluateBattery(listOf(routine.copy(enabled = false),
            routine.copy(id = "manual", trigger = Trigger.MANUAL)), 10).isEmpty())
    }

    @Test fun editingMessagePreservesLatchButThresholdChangeRearmsIt() {
        val engine = RoutineEngine(mutableSetOf("battery"))
        engine.onEdited(routine, routine.copy(message = "새 안내"))
        assertTrue(engine.evaluateBattery(listOf(routine), 29).isEmpty())
        engine.onEdited(routine, routine.copy(thresholdPercent = 40))
        assertEquals(1, engine.evaluateBattery(listOf(routine.copy(thresholdPercent = 40)), 29).size)
    }

    @Test fun validationRejectsUnsafeCombinationsAndUnsupportedTemperature() {
        val area = TemperatureArea("driver", "운전석", 18f, 28f, .5f)
        assertNotNull(routine.copy(action = Action.SET_TEMPERATURE, targetAreaId="missing").validationError(listOf(area)))
        assertNotNull(routine.copy(thresholdPercent = 0).validationError(listOf(area)))
        val manual = routine.copy(trigger = Trigger.MANUAL, action = Action.SET_TEMPERATURE,
            targetAreaId = "driver", temperatureC = 23f)
        assertNull(manual.validationError(listOf(area)))
        assertNotNull(manual.copy(temperatureC = 23.2f).validationError(listOf(area)))
        assertNotNull(manual.copy(temperatureC = Float.NaN).validationError(listOf(area)))
        assertNotNull(manual.copy(targetAreaId = "missing").validationError(listOf(area)))
    }

    @Test fun manualRoutineCannotSaveThresholdThatWouldInvalidateStoredState() {
        assertNotNull(routine.copy(trigger = Trigger.MANUAL, thresholdPercent = 0).validationError(emptyList()))
        assertNotNull(routine.copy(trigger = Trigger.MANUAL, thresholdPercent = 99).validationError(emptyList()))
    }
}
