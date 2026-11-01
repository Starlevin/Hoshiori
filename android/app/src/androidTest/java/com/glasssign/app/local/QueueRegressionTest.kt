package com.glasssign.app.local

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.*
import androidx.lifecycle.viewModelScope
import com.glasssign.app.HomeViewModel
import kotlinx.coroutines.cancel
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/** Uses an empty dedicated test installation; never uses community credentials. */
@RunWith(AndroidJUnit4::class)
class QueueRegressionTest {
    private val app=ApplicationProvider.getApplicationContext<Application>()
    private val manager=WorkManager.getInstance(app)
    @Before fun before() {
        assertTrue("Dedicated empty test installation required",LocalServices.get(app).repository.accounts().isEmpty())
        manager.cancelUniqueWork(SignScheduler.RUN).result.get(10,TimeUnit.SECONDS)
    }
    @After fun after() {manager.cancelUniqueWork(SignScheduler.RUN).result.get(10,TimeUnit.SECONDS)}

    @Test fun repeatedClicksDoNotAppendBlockedWorkChain() {
        val pending=OneTimeWorkRequestBuilder<SignWorker>().setInitialDelay(1,TimeUnit.DAYS).build()
        manager.enqueueUniqueWork(SignScheduler.RUN,ExistingWorkPolicy.KEEP,pending).result.get(10,TimeUnit.SECONDS)
        val scheduler=SignScheduler(app)
        repeat(5){scheduler.run()}
        val infos=manager.getWorkInfosForUniqueWork(SignScheduler.RUN).get(10,TimeUnit.SECONDS)
        assertEquals(listOf(pending.id),infos.filter{!it.state.isFinished}.map{it.id})
    }

    @Test fun viewModelReturnsToIdleAfterTwoCompletedRuns() {
        val main=InstrumentationRegistry.getInstrumentation()
        lateinit var vm:HomeViewModel
        main.runOnMainSync {vm=HomeViewModel(app)}
        try {
            repeat(2) {
                val previous=LocalServices.get(app).prefs.getString("last_finished",null)
                main.runOnMainSync {vm.runNow()}
                val deadline=System.currentTimeMillis()+25_000
                var finished=false
                while(System.currentTimeMillis()<deadline) {
                    var idle=false
                    main.runOnMainSync {idle=!vm.state.jobRunning&&!vm.state.waitingNetwork&&!vm.state.submitting}
                    if(idle&&LocalServices.get(app).prefs.getString("last_finished",null)!=previous) {finished=true;break}
                    Thread.sleep(100)
                }
                assertTrue("Run ${it+1} must complete and release loading state",finished)
            }
        } finally {main.runOnMainSync{vm.viewModelScope.cancel()}}
    }
}
