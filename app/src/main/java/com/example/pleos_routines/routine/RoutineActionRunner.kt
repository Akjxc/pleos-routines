package com.example.pleos_routines.routine

import com.example.pleos_routines.model.*
import com.example.pleos_routines.vehicle.ActionResult

/** Runs one chain without retrying writes or discarding already completed results. */
class RoutineActionRunner(
    private val environment: () -> Environment,
    private val isActive: () -> Boolean,
    private val executeClimate: (Routine, () -> Boolean, (ActionResult) -> Unit) -> Unit,
    private val showNotice: (String) -> Unit
) {
    fun run(routine: Routine, onResult: (ActionResult) -> Unit) {
        val engine = RoutineEngine()
        val actions = routine.effectiveActions
        var conditions = routine.effectiveConditions
        val conditionArea = routine.effectiveActions.firstOrNull { it.targetAreaId.isNotBlank() }?.targetAreaId
            ?: routine.targetAreaId
        val details = mutableListOf<String>()
        fun guardedRoutine() = routine.copy(conditions = RoutineConditions(items = conditions))
        fun canContinue() = isActive() && engine.conditionsMatch(guardedRoutine(), environment())
        fun skipped(from: Int, reason: String) {
            actions.drop(from).forEachIndexed { offset, action ->
                details += "${from + offset + 1}. 미실행 · ${action.summary()} · $reason"
            }
        }
        fun runAt(index: Int) {
            if (index == actions.size) {
                onResult(ActionResult(true, details.joinToString("\n"))); return
            }
            if (!canContinue()) {
                skipped(index, "실행 조건 변경 또는 상태 미확인")
                onResult(ActionResult(false, details.joinToString("\n"))); return
            }
            val item = actions[index]
            if (item.action == Action.NOTICE) {
                showNotice(item.message)
                details += "${index + 1}. 성공 · 안내 표시 · ${item.message}"
                runAt(index + 1); return
            }
            val singleAction = guardedRoutine().copy(action = item.action, targetAreaId = item.targetAreaId,
                temperatureC = item.temperatureC, message = item.message, fanSpeed = item.fanSpeed, actions = emptyList())
            executeClimate(singleAction, ::canContinue) { result ->
                details += "${index + 1}. ${if (result.confirmed) "성공" else "실패"} · ${item.summary()} · ${result.detail}"
                if (!result.confirmed) {
                    skipped(index + 1, "앞선 동작 실패")
                    onResult(ActionResult(false, details.joinToString("\n")))
                } else {
                    // Only consume AC conditions deliberately changed by this successful write.
                    // Power, battery, parking and defrost remain live guards throughout the chain.
                    val affectsConditionArea = conditionArea.isNotBlank() && item.targetAreaId.split('+').toSet()
                        .containsAll(conditionArea.split('+'))
                    val resultingAc = when (item.action) { Action.AC_ON -> true; Action.VENTILATE -> false; else -> null }
                    if (affectsConditionArea && resultingAc != null) {
                        conditions = conditions.filterNot { it.kind == ConditionKind.AC_STATE && it.acOn != resultingAc }
                    }
                    runAt(index + 1)
                }
            }
        }
        runAt(0)
    }
}
