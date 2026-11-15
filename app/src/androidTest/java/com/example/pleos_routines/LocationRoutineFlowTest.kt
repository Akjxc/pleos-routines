package com.example.pleos_routines

import android.os.Handler
import android.os.Looper
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pleos_routines.data.RoutineStore
import com.example.pleos_routines.model.*
import com.example.pleos_routines.vehicle.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Dedicated AVD: start at127,37.01; host moves to127,37 after activity is listening. */
@RunWith(AndroidJUnit4::class)
class LocationRoutineFlowTest {
    @Test fun realLocationEntryAutomaticallyDisablesAcAndPreservesFan() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val main=Handler(Looper.getMainLooper()); val ready=CountDownLatch(1)
        val acArea=AtomicReference<String>();val gateway=PleosVehicleGateway(context)
        main.post { gateway.start { s -> s.acAreas.firstOrNull()?.let {acArea.set(it);ready.countDown()} } }
        assertTrue("AC capability unavailable",ready.await(20,TimeUnit.SECONDS))
        val stopped=CountDownLatch(1);main.post{gateway.stop();stopped.countDown()};stopped.await(5,TimeUnit.SECONDS)
        val store=RoutineStore(context);val original=store.load()
        val name="위치 자동 송풍 검증"
        val routine=Routine(id="test-location-entry",name=name,enabled=true,trigger=Trigger.LOCATION,
            action=Action.VENTILATE,targetAreaId=acArea.get(),latitude=37.0,longitude=127.0,radiusMeters=500f,
            conditions=RoutineConditions(acOn=true,powerOn=true,requireDefrostOff=true))
        assertTrue(store.save(original.copy(routines=original.routines+routine)))
        var scenario:ActivityScenario<MainActivity>?=null
        try {
            scenario=ActivityScenario.launch(MainActivity::class.java)
            android.util.Log.i("PleosFlowTest","GEOFENCE_LISTENING")
            val deadline=System.currentTimeMillis()+60000
            var record:RunRecord?=null
            while(System.currentTimeMillis()<deadline && record==null) {
                record=store.load().records.firstOrNull { it.routineName==name }
                if(record==null)Thread.sleep(500)
            }
            assertNotNull("No actual location-entry execution was recorded",record)
            println("ACTUAL GEOFENCE RESULT ${record!!.detail}")
            assertTrue(record.detail,record.detail.startsWith("적용 확인"))
        } finally { scenario?.close();assertTrue(store.save(original)) }
    }
}
