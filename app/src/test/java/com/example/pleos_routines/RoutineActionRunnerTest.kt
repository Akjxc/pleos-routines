package com.example.pleos_routines

import com.example.pleos_routines.model.*
import com.example.pleos_routines.routine.RoutineActionRunner
import com.example.pleos_routines.vehicle.ActionResult
import org.junit.Assert.*
import org.junit.Test

class RoutineActionRunnerTest {
    private fun env() = Environment(1000, "2026-11-15", 480, soc = 50, parked = true,
        acStates = mapOf("driver" to true), powerStates = mapOf("driver" to true), defrostActive = false)
    private fun routine() = Routine(enabled = true, conditions = RoutineConditions(acOn = true,
        powerOn = true, parked = true, requireDefrostOff = true), actions = listOf(
        ActionItem(Action.VENTILATE, "driver"), ActionItem(Action.NOTICE, message = "송풍 전환 완료")))

    @Test fun successfulAcChangeDoesNotBlockFollowingNotice() {
        var environment = env()
        val notices = mutableListOf<String>()
        var result: ActionResult? = null
        RoutineActionRunner({ environment }, { true }, { action, validate, done ->
            assertEquals(Action.VENTILATE, action.action)
            assertTrue(validate())
            environment = environment.copy(acStates = mapOf("driver" to false))
            done(ActionResult(true, "A/C 꺼짐"))
        }, notices::add).run(routine()) { result = it }
        assertTrue(result!!.confirmed)
        assertEquals(listOf("송풍 전환 완료"), notices)
        assertTrue(result!!.detail.contains("1. 성공"))
        assertTrue(result!!.detail.contains("2. 성공"))
    }

    @Test fun safetyChangeAfterSuccessfulActionStopsFollowingActionAndPreservesResult() {
        var environment = env()
        val notices = mutableListOf<String>()
        var result: ActionResult? = null
        RoutineActionRunner({ environment }, { true }, { _, _, done ->
            environment = environment.copy(acStates = mapOf("driver" to false), parked = false)
            done(ActionResult(true, "A/C 꺼짐"))
        }, notices::add).run(routine()) { result = it }
        assertFalse(result!!.confirmed)
        assertTrue(notices.isEmpty())
        assertTrue(result!!.detail.contains("1. 성공"))
        assertTrue(result!!.detail.contains("2. 미실행"))
    }

    @Test fun failedMiddleActionKeepsSuccessAndNamesSkippedActions() {
        var result: ActionResult? = null
        val notices = mutableListOf<String>()
        val routine = Routine(actions = listOf(ActionItem(message = "시작"),
            ActionItem(Action.SET_TEMPERATURE, "driver", 23f), ActionItem(message = "끝")))
        RoutineActionRunner({ env() }, { true }, { _, _, done -> done(ActionResult(false, "SDK 오류")) },
            notices::add).run(routine) { result = it }
        assertEquals(listOf("시작"), notices)
        assertFalse(result!!.confirmed)
        assertTrue(result!!.detail.contains("1. 성공"))
        assertTrue(result!!.detail.contains("2. 실패"))
        assertTrue(result!!.detail.contains("SDK 오류"))
        assertTrue(result!!.detail.contains("3. 미실행"))
    }

    @Test fun unknownPowerOrDefrostStillBlocksAfterAcChange() {
        for (changed in listOf(env().copy(powerStates = emptyMap()), env().copy(defrostActive = null))) {
            var environment = env()
            var result: ActionResult? = null
            RoutineActionRunner({ environment }, { true }, { _, _, done ->
                environment = changed.copy(acStates = mapOf("driver" to false))
                done(ActionResult(true, "A/C 꺼짐"))
            }, { fail("안전 조건 미확인 상태에서 안내까지 진행됨") }).run(routine()) { result = it }
            assertFalse(result!!.confirmed)
        }
    }
}
