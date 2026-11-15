package com.example.pleos_routines

import com.example.pleos_routines.model.*
import com.example.pleos_routines.routine.RoutineEngine
import org.junit.Assert.*
import org.junit.Test

class AutomationTest {
    private val now = 1_000_000L
    private fun env(soc: Int? = 50, parked: Boolean? = true, location: GeoPoint? = null, minute: Int = 480) =
        Environment(nowMs = now, dateKey = "2026-11-15", minuteOfDay = minute, soc = soc,
            parked = parked, location = location)
    private fun routine(trigger: Trigger) = Routine(id = "r", enabled = true, trigger = trigger)

    @Test fun scheduledNoticeFiresOnlyOnceInChosenMinuteAndAgainNextDay() {
        val e = RoutineEngine(); val r = routine(Trigger.AT_TIME)
        assertTrue(e.evaluate(listOf(r), env(minute = 479)).isEmpty())
        assertEquals(1, e.evaluate(listOf(r), env()).size)
        assertTrue(e.evaluate(listOf(r), env()).isEmpty())
        assertEquals(1, RoutineEngine(e.latched.toMutableSet()).evaluate(listOf(r), env().copy(dateKey="2026-11-16")).size)
    }
    @Test fun locationRequiresOutsideToInsideAndHysteresisPreventsJitter() {
        val e=RoutineEngine(); val r=routine(Trigger.LOCATION).copy(latitude=0.0,longitude=0.0,radiusMeters=500f)
        fun point(latitude:Double)=GeoPoint(latitude,0.0,5f,now)
        assertTrue(e.evaluate(listOf(r),env(location=point(.01))).isEmpty())
        assertEquals(1,e.evaluate(listOf(r),env(location=point(.003))).size)
        assertTrue(e.evaluate(listOf(r),env(location=point(.0046))).isEmpty())
        assertTrue(e.evaluate(listOf(r),env(location=point(.003))).isEmpty())
        assertTrue(e.evaluate(listOf(r),env(location=point(.01))).isEmpty())
        assertEquals(1,e.evaluate(listOf(r),env(location=point(.003))).size)
    }
    @Test fun initialInsideOrOldLocationCannotRunAndExitIsDistinct() {
        val r=routine(Trigger.LOCATION).copy(latitude=0.0,longitude=0.0,locationExit=true)
        val e=RoutineEngine(); val inside=GeoPoint(0.0,0.0,5f,now)
        assertTrue(e.evaluate(listOf(r),env(location=inside)).isEmpty())
        assertTrue(e.evaluate(listOf(r),env(location=GeoPoint(.01,0.0,5f,now-60_000))).isEmpty())
        assertTrue(e.evaluate(listOf(r),env(location=inside)).isEmpty())
        assertEquals(1,e.evaluate(listOf(r),env(location=inside.copy(latitude=.01))).size)
    }
    @Test fun gearNeedsRealTransitionAndUnknownResetsBaseline() {
        val e=RoutineEngine(); val r=routine(Trigger.GEAR)
        assertTrue(e.evaluate(listOf(r),env()).isEmpty())
        assertTrue(e.evaluate(listOf(r),env(parked=false)).isEmpty())
        assertEquals(1,e.evaluate(listOf(r),env()).size)
        e.evaluate(listOf(r),env(parked=null))
        assertTrue(e.evaluate(listOf(r),env()).isEmpty())
    }
    @Test fun allConditionsUseAndUnknownIsNotOff() {
        val r=routine(Trigger.BATTERY_BELOW).copy(targetAreaId="driver", conditions=RoutineConditions(
            acOn=true,powerOn=true,batteryMin=10,batteryMax=40,parked=true,requireDefrostOff=true))
        val unknown=env(soc=29)
        assertFalse(RoutineEngine().conditionsMatch(r,unknown))
        val valid=unknown.copy(acStates=mapOf("driver" to true),powerStates=mapOf("driver" to true),defrostActive=false)
        assertTrue(RoutineEngine().conditionsMatch(r,valid))
        assertFalse(RoutineEngine().conditionsMatch(r,valid.copy(defrostActive=true)))
        assertEquals(1,RoutineEngine().evaluate(listOf(r),valid).size)
    }
    @Test fun invalidLocationAndInvalidConditionRangeCannotBeSaved() {
        assertNotNull(routine(Trigger.LOCATION).validationError(emptyList()))
        assertNotNull(routine(Trigger.LOCATION).copy(latitude=100.0,longitude=0.0).validationError(emptyList()))
        assertNotNull(routine(Trigger.MANUAL).copy(conditions=RoutineConditions(batteryMin=70,batteryMax=20)).validationError(emptyList()))
    }
    @Test fun groupedHvacZonesResolveWithoutInventingUnknownOrConflictingValues() {
        assertEquals(true, areaValue(mapOf("left+right" to true),"left"))
        assertEquals(3, areaValue(mapOf("left+right" to 3),"left+right+rear"))
        assertNull(areaValue(mapOf("left" to true,"right" to false),"left+right"))
        assertNull(areaValue(mapOf("left" to true),"rear"))
    }
}
