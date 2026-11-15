package com.example.pleos_routines

import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pleos_routines.model.*
import com.example.pleos_routines.vehicle.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Dedicated Pleos AVD only: Virtual Console active, HVAC on, AC on, fan3, defrost off. */
@RunWith(AndroidJUnit4::class)
class ExpandedSdkTest {
    @Test fun actualClimateActionsAndLocationAreAvailable() {
        val main=Handler(Looper.getMainLooper())
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val ready=CountDownLatch(1); val state=AtomicReference(VehicleSnapshot())
        val gateway=PleosVehicleGateway(context)
        main.post { gateway.start { s -> state.set(s)
            if(s.acStates.isNotEmpty() && s.fanSpeeds.isNotEmpty() && s.defrostActive!=null)ready.countDown()
        } }
        try {
            assertTrue("SDK HVAC state timeout",ready.await(30,TimeUnit.SECONDS))
            val s=state.get()
            println("ACTUAL HVAC ac=${s.acStates} power=${s.powerStates} fan=${s.fanSpeeds} defrost=${s.defrostActive} acAreas=${s.acAreas} fanRanges=${s.fanRanges}")
            assertEquals(false,s.defrostActive)
            fun execute(r:Routine) {
                val latch=CountDownLatch(1); val result=AtomicReference<ActionResult>()
                main.post { gateway.executeClimate(r) { result.set(it);latch.countDown() } }
                assertTrue("setter timeout",latch.await(15,TimeUnit.SECONDS))
                println("ACTUAL ACTION ${r.action}: ${result.get()}")
                assertTrue(result.get().detail,result.get().confirmed)
            }
            val fanArea=s.fanRanges.entries.first { it.value.contains(3) }.key
            execute(Routine(action=Action.SET_FAN,targetAreaId=fanArea,fanSpeed=3))
            val acArea=s.acAreas.first()
            execute(Routine(action=Action.AC_ON,targetAreaId=acArea))
            execute(Routine(action=Action.VENTILATE,targetAreaId=acArea))
            execute(Routine(action=Action.AC_ON,targetAreaId=acArea))
        } finally { main.post { gateway.stop() } }
        val fix=CountDownLatch(1); val point=AtomicReference<GeoPoint>()
        val source=PleosLocationSource(context)
        main.post { source.start({if(it!=null){point.set(it);fix.countDown()}},{println("ACTUAL LOCATION $it")}) }
        try { assertTrue("Pleos live location timeout",fix.await(20,TimeUnit.SECONDS));assertNotNull(point.get()) }
        finally { main.post { source.stop() } }
    }
}
