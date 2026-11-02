package com.glasssign.app.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RetryCompletionTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private val dbName="retry-${UUID.randomUUID()}.db"
    private lateinit var repository:SignRepository
    private lateinit var credentials:LocalCredentialStore
    private lateinit var id:String
    @Before fun setup(){repository=SignRepository(context,dbName);credentials=LocalCredentialStore(context);id=repository.add("miyoushe","合成重试账号");credentials.save(id,JSONObject().put("test","synthetic-only"))}
    @After fun cleanup(){credentials.delete(id);repository.close();context.deleteDatabase(dbName)}
    private fun engine(outcome:()->SignResult)=LocalSignEngine(repository,credentials,mapOf("miyoushe" to object:ProviderAdapter{
        override fun targets(c:JSONObject,persist:()->Unit)=listOf(SignTarget("game|fixture","合成角色"))
        override fun checkin(c:JSONObject,t:SignTarget,persist:()->Unit)=outcome()
    }))
    private fun expireCooldown(){repository.writableDatabase.execSQL("UPDATE records SET stamp=0")}
    @Test fun retryableFailureKeepsScheduleOpenDuringCooldownThenCompletesAfterSuccess()=runBlocking {
        var success=false;val engine=engine{SignResult(if(success)"success" else "failed","mock only")}
        val first=engine.run();assertTrue(first.retryPending);assertFalse(first.waiting)
        assertFalse(SchedulePolicy.canCompleteDay(first.day,first.day,first.waiting,first.retryPending))
        val cooling=engine.run();assertEquals(0,cooling.attempted);assertTrue(cooling.retryPending)
        expireCooldown();success=true;val recovered=engine.run()
        assertFalse(recovered.retryPending);assertTrue(SchedulePolicy.canCompleteDay(recovered.day,recovered.day,recovered.waiting,recovered.retryPending))
        assertEquals(2,repository.records(first.day).single().attempts)
    }
    @Test fun dailyAttemptLimitEndsRetryWithoutChangingFailureToSuccess()=runBlocking {
        val engine=engine{SignResult("failed","mock protocol failure")}
        repeat(3){val summary=engine.run();assertEquals(it<2,summary.retryPending);expireCooldown()}
        val exhausted=engine.run();assertEquals(0,exhausted.attempted);assertFalse(exhausted.retryPending)
        assertEquals("failed",repository.records(exhausted.day).single().status);assertEquals(3,repository.records(exhausted.day).single().attempts)
    }
    @Test fun uncertainSubmissionIsTerminalAndNeverAutomaticallyResent()=runBlocking {
        var posts=0;val engine=engine{posts++;SignResult("needs_action","合成不确定提交，请先核对")}
        assertFalse(engine.run().retryPending);expireCooldown();engine.run()
        assertEquals(1,posts);assertEquals("uncertain",repository.records(SignRepository.day()).single().status)
    }
    @Test fun pausedAccountsDoNotKeepAutomaticSchedulePending()=runBlocking {
        val engine=engine{SignResult("failed","mock failure")};assertTrue(engine.run().retryPending)
        repository.enabled(id,false)
        val paused=engine.run();assertFalse(paused.retryPending);assertFalse(paused.waiting)
    }
}
