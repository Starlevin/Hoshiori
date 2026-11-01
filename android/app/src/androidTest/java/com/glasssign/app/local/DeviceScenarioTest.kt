package com.glasssign.app.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.*
import org.junit.Test
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Invoked only by the zero-credential emulator harness with an explicit scenario argument. */
@RunWith(AndroidJUnit4::class)
class DeviceScenarioTest {
    @Test fun scenario() {
        val mode=InstrumentationRegistry.getArguments().getString("scenario")
        assumeTrue(mode!=null)
        val c=ApplicationProvider.getApplicationContext<Context>();val s=LocalServices.get(c);val manager=WorkManager.getInstance(c)
        assertTrue("Device scenarios require an empty test installation",s.repository.accounts().isEmpty())
        when(mode) {
            "enqueue_network" -> {
                manager.cancelUniqueWork(SignScheduler.RUN).result.get(10,TimeUnit.SECONDS)
                val request=OneTimeWorkRequestBuilder<SignWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
                manager.enqueueUniqueWork(SignScheduler.RUN,ExistingWorkPolicy.REPLACE,request).result.get(10,TimeUnit.SECONDS)
                s.prefs.edit().putString("scenario_work",request.id.toString()).commit()
                Thread.sleep(1000)
                assertEquals(WorkInfo.State.ENQUEUED,manager.getWorkInfoById(request.id).get(10,TimeUnit.SECONDS)!!.state)
            }
            "assert_network_completed" -> {
                val id=UUID.fromString(s.prefs.getString("scenario_work","")!!)
                val deadline=System.currentTimeMillis()+45000
                while(System.currentTimeMillis()<deadline && manager.getWorkInfoById(id).get(10,TimeUnit.SECONDS)!!.state!=WorkInfo.State.SUCCEEDED)Thread.sleep(250)
                assertEquals(WorkInfo.State.SUCCEEDED,manager.getWorkInfoById(id).get(10,TimeUnit.SECONDS)!!.state)
                assertNotNull(s.prefs.getString("last_finished",null))
            }
            "prepare_boot" -> {
                manager.cancelAllWork().result.get(10,TimeUnit.SECONDS)
                s.prefs.edit().putBoolean("schedule_enabled",true).putString("daily_time","00:01").remove("last_scheduled_day").remove("last_finished").remove("last_trigger").commit()
                val tick=PeriodicWorkRequestBuilder<ScheduleTickWorker>(15,TimeUnit.MINUTES).setInitialDelay(1,TimeUnit.DAYS).build()
                manager.enqueueUniquePeriodicWork(SignScheduler.TICK,ExistingPeriodicWorkPolicy.KEEP,tick).result.get(10,TimeUnit.SECONDS)
                // Do not invoke restore: the harness now performs a real Android reboot.
            }
            "assert_boot_completed" -> {
                val deadline=System.currentTimeMillis()+45000
                while(System.currentTimeMillis()<deadline && s.prefs.getString("last_scheduled_day","")!=SignRepository.day())Thread.sleep(250)
                assertEquals(SignRepository.day(),s.prefs.getString("last_scheduled_day",""))
                assertEquals("scheduled",s.prefs.getString("last_trigger",""))
                assertTrue(manager.getWorkInfosForUniqueWork(SignScheduler.TICK).get(10,TimeUnit.SECONDS).any{!it.state.isFinished})
                SignScheduler(c).save(false,"08:10")
            }
            else -> fail("Unknown test scenario")
        }
        assertTrue(s.repository.records(SignRepository.day()).isEmpty())
    }
}
