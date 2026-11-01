package com.glasssign.app.local

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.*
import androidx.work.testing.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class LocalIntegrationTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private lateinit var repo:SignRepository
    private lateinit var secrets:LocalCredentialStore
    private val ids=mutableListOf<String>()
    private lateinit var dbName:String
    @Before fun setup() {dbName="test-${UUID.randomUUID()}.db";repo=SignRepository(context,dbName);secrets=LocalCredentialStore(context)}
    @After fun clean() {ids.forEach{secrets.delete(it)};repo.close();context.deleteDatabase(dbName)}
    private fun account(provider:String="miyoushe"):String {
        val id=repo.add(provider,"测试账号");ids.add(id);secrets.save(id,JSONObject().put("test","synthetic-only"));return id
    }
    @Test fun keystoreCiphertextRoundtripTamperDeleteAndAccessTokenExclusion() {
        val id=account();val marker="synthetic-secret-${UUID.randomUUID()}"
        secrets.save(id,JSONObject().put("cookie",marker).put("_accessToken","ephemeral-test-token"))
        assertEquals(marker,secrets.read(id).getString("cookie"));assertFalse(secrets.read(id).has("_accessToken"))
        val prefs=context.getSharedPreferences("local_secret_$id",Context.MODE_PRIVATE)
        assertFalse(prefs.all.values.joinToString().contains(marker))
        val key=prefs.all.keys.single();prefs.edit().putString(key,"broken-ciphertext").commit()
        try{secrets.read(id);fail()}catch(e:SignError){assertEquals("auth",e.kind)}
        secrets.delete(id);assertTrue(prefs.all.isEmpty())
    }
    @Test fun fourMockAdaptersRunThroughEncryptedStoreAndSqliteOnce()=runBlocking {
        val calls=AtomicInteger()
        val adapters=listOf("miyoushe","skland","tagged","kurobbs").associateWith { provider ->
            account(provider)
            object:ProviderAdapter {
                override fun targets(c:JSONObject,persist:()->Unit)=listOf(SignTarget("game|role-1","模拟角色1"),SignTarget("game|role-2","模拟角色2"),SignTarget("game|role-1","模拟重复角色"))
                override fun checkin(c:JSONObject,t:SignTarget,persist:()->Unit):SignResult {calls.incrementAndGet();return SignResult(if(t.id.endsWith("1"))"success" else "already","仅为 mock 测试结果")}
            }
        }
        val engine=LocalSignEngine(repo,secrets,adapters)
        val first=engine.run();val second=engine.run()
        assertEquals(8,first.attempted);assertEquals(0,second.attempted);assertEquals(8,calls.get());assertEquals(8,repo.records(SignRepository.day()).size)
        assertEquals(1,repo.history().size)
    }
    @Test fun manualAndScheduledConcurrencyDoesNotDuplicatePost()=runBlocking {
        val id=account();val calls=AtomicInteger()
        val adapter=object:ProviderAdapter {
            override fun targets(c:JSONObject,persist:()->Unit)=listOf(SignTarget("role|1","测试角色"))
            override fun checkin(c:JSONObject,t:SignTarget,persist:()->Unit):SignResult {Thread.sleep(30);calls.incrementAndGet();return SignResult("success","mock only")}
        }
        val engine=LocalSignEngine(repo,secrets,mapOf("miyoushe" to adapter))
        coroutineScope {(1..8).map{async(Dispatchers.IO){engine.run(listOf(id))}}.awaitAll()}
        assertEquals(1,calls.get());assertEquals(1,repo.records(SignRepository.day()).single().attempts)
    }
    @Test fun databaseClaimIsAtomicAcrossConnectionsAndDateRoleTaskKeysDiffer() {
        val id=account();val day=SignRepository.day();val other=SignRepository(context,dbName);val wins=AtomicInteger();val pool=Executors.newFixedThreadPool(6)
        try {
            val futures=(1..12).map{i->pool.submit{if((if(i%2==0)repo else other).claim(day,id,SignTarget("game|1","role")))wins.incrementAndGet()}}
            futures.forEach{it.get(10,TimeUnit.SECONDS)};assertEquals(1,wins.get())
            assertTrue(repo.claim(day,id,SignTarget("community|1","community")))
            assertTrue(repo.claim("2026-10-30",id,SignTarget("game|1","role")))
        }finally{pool.shutdownNow();other.close()}
    }
    @Test fun interruptedSubmissionRequiresOfficialCheckAndIsNotRetried() {
        val id=account();val day=SignRepository.day();val t=SignTarget("role|1","test")
        assertTrue(repo.claim(day,id,t));repo.recoverInterrupted()
        assertEquals("needs_action",repo.records(day).single().status);assertFalse(repo.claim(day,id,t,Long.MAX_VALUE))
    }
    @Test fun cooldownDailyAttemptLimitAndDeleteCascade() {
        val id=account();val day=SignRepository.day();val t=SignTarget("game|1","test")
        for(i in 0..2) {
            assertTrue(repo.claim(day,id,t,1_000_000L+i*120_001));repo.finish(day,id,t,SignResult("waiting_network","mock offline"))
            assertFalse(repo.claim(day,id,t,1_000_001L+i*120_001))
        }
        assertFalse(repo.claim(day,id,t,2_000_000L));repo.delete(id);assertTrue(repo.records(day).isEmpty())
    }
    @Test fun unknownExceptionsNeverExposeSecretOrBecomeSuccess()=runBlocking {
        account();val adapter=object:ProviderAdapter {
            override fun targets(c:JSONObject,persist:()->Unit):List<SignTarget> = throw IllegalStateException("synthetic-secret-must-not-escape")
            override fun checkin(c:JSONObject,t:SignTarget,persist:()->Unit)=error("unreachable")
        }
        LocalSignEngine(repo,secrets,mapOf("miyoushe" to adapter)).run()
        val record=repo.records(SignRepository.day()).single();assertEquals("failed",record.status);assertFalse(record.message.contains("synthetic-secret"))
    }
    @Test fun pausedAccountsDoNotRunAutomaticallyButExplicitCheckCanRun()=runBlocking {
        val id=account();repo.enabled(id,false);val calls=AtomicInteger()
        val adapter=object:ProviderAdapter {
            override fun targets(c:JSONObject,persist:()->Unit):List<SignTarget> {calls.incrementAndGet();return emptyList()}
            override fun checkin(c:JSONObject,t:SignTarget,persist:()->Unit)=SignResult("failed","unreachable")
        }
        val engine=LocalSignEngine(repo,secrets,mapOf("miyoushe" to adapter));engine.run();assertEquals(0,calls.get());engine.run(listOf(id));assertEquals(1,calls.get())
    }
    @Test fun networkConstraintAndUniqueQueuePersistAcrossBootRestore() {
        // Actual WorkManager + Android JobScheduler on API 35, with no accounts and no backend.
        val manager=WorkManager.getInstance(context)
        manager.cancelUniqueWork(SignScheduler.RUN).result.get(10,TimeUnit.SECONDS)
        val request=OneTimeWorkRequestBuilder<SignWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build()).setInitialDelay(1,TimeUnit.DAYS).build()
        manager.enqueueUniqueWork(SignScheduler.RUN,ExistingWorkPolicy.KEEP,request).result.get(10,TimeUnit.SECONDS)
        val before=manager.getWorkInfoById(request.id).get(10,TimeUnit.SECONDS)!!
        assertEquals(WorkInfo.State.ENQUEUED,before.state)
        val s=LocalServices.get(context);val old=s.prefs.all
        try {
            s.prefs.edit().putBoolean("schedule_enabled",true).putString("daily_time","23:59").commit()
            BootReceiver().onReceive(context,Intent(Intent.ACTION_BOOT_COMPLETED))
            val after=manager.getWorkInfoById(request.id).get(10,TimeUnit.SECONDS)!!
            assertFalse(after.state.isFinished)
            assertTrue(manager.getWorkInfosForUniqueWork(SignScheduler.TICK).get(10,TimeUnit.SECONDS).any{!it.state.isFinished})
        }finally{
            manager.cancelUniqueWork(SignScheduler.RUN).result.get(10,TimeUnit.SECONDS)
            manager.cancelUniqueWork(SignScheduler.TICK).result.get(10,TimeUnit.SECONDS)
            s.prefs.edit().clear().apply();old.forEach{(k,v)->when(v){is String->s.prefs.edit().putString(k,v).commit();is Boolean->s.prefs.edit().putBoolean(k,v).commit()}}
        }
    }
}
