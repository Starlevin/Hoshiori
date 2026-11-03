package com.glasssign.app.local

import android.app.Application
import android.content.Context
import com.glasssign.app.data.RoleChoice
import com.glasssign.app.data.present
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Real SQLite + engine, isolated JVM application; never opens user databases or official sockets. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class,manifest=Config.NONE)
class ReliabilityV077IntegrationTest {
    private lateinit var context:Context
    private lateinit var repo:SignRepository
    private lateinit var databaseName:String
    private val day get()=SignRepository.day()
    private val target=SignTarget("game|1289|10001","异环 · 测试角色")
    private val stores=MemoryCredentials()

    @Before fun setup() {
        context=RuntimeEnvironment.getApplication()
        databaseName="reliability-v077-${UUID.randomUUID()}.db"
        repo=SignRepository(context,databaseName)
    }
    @After fun cleanup() {repo.close();context.deleteDatabase(databaseName)}

    private fun account():String {
        val id=repo.add("tagged","合成测试",gameId="nte")
        val role=RoleChoice(target.id,"nte",target.name)
        repo.storeRoleChoices(id,listOf(role));repo.setBindings(id,listOf(role),false)
        repo.auth(id,"valid","合成授权，无网络")
        stores.save(id,JSONObject().put("communityCheckin",false).put("roleIds",JSONArray().put("10001")))
        return id
    }

    private class MemoryCredentials:CredentialStore {
        private val values=mutableMapOf<String,String>()
        override fun save(id:String,c:JSONObject) {values[id]=c.toString()}
        override fun read(id:String)=JSONObject(values.getValue(id))
        override fun delete(id:String) {values.remove(id)}
    }
    private inner class FakeAdapter(var outcome:SignResult):ProviderAdapter {
        val discoveries=AtomicInteger();val submissions=AtomicInteger();val queries=AtomicInteger()
        var query=SignResult("uncertain","合成查询无结论")
        override fun targets(c:JSONObject,persist:()->Unit):List<SignTarget> {discoveries.incrementAndGet();return listOf(target)}
        override fun checkin(c:JSONObject,t:SignTarget,persist:()->Unit):SignResult {submissions.incrementAndGet();return outcome}
        override fun queryStatus(c:JSONObject,t:SignTarget,persist:()->Unit):SignResult {queries.incrementAndGet();return query}
    }
    private fun engine(adapter:ProviderAdapter)=LocalSignEngine(repo,stores,mapOf("tagged" to adapter))

    @Test fun acceptedIsPersistedAndRepeatAvoidsEvenDiscovery()=runBlocking {
        val id=account();val a=FakeAdapter(SignResult("accepted","已受理，等待确认",resultSource="accepted",providerCode="0",httpStatus=200,verificationMethod="not_supported"))
        val e=engine(a);e.run(listOf(id));e.run(listOf(id))
        assertEquals(1,a.submissions.get());assertEquals(1,a.discoveries.get())
        val r=repo.records(day).single { it.targetId==target.id }
        assertEquals("accepted",r.status);assertEquals("accepted",r.resultSource)
        assertEquals("submitted",present(r).group)
        assertEquals("已提交等待确认",present(r).label)
        assertEquals("0",r.providerCode);assertEquals(200,r.httpStatus);assertFalse(r.retryable)
        assertTrue(r.executionId.isNotBlank());assertEquals(1,r.attempts)
    }

    @Test fun trustedSuccessKeepsOriginalVerificationTimeOnCacheReuse()=runBlocking {
        val id=account();val original="2026-11-03T08:00:00+08:00"
        val a=FakeAdapter(SignResult("success","官方明确确认",resultSource="official",verificationMethod="official_status",verifiedAt=original))
        val e=engine(a);e.run(listOf(id));e.run(listOf(id))
        assertEquals(1,a.submissions.get());assertEquals(1,a.discoveries.get())
        val r=repo.records(day).single {it.targetId==target.id}
        assertEquals(original,r.verifiedAt)
        assertEquals("local_cache",r.verificationMethod)
        assertTrue(r.message.contains("本机"))
    }

    @Test fun acceptedSurvivesHistoryCleanupAndStillCannotSubmit()=runBlocking {
        val id=account();val a=FakeAdapter(SignResult("accepted","等待确认",resultSource="accepted"));val e=engine(a)
        e.run(listOf(id));repo.deleteRecords(id);e.run(listOf(id))
        assertEquals(1,a.submissions.get());assertEquals(1,a.discoveries.get())
        assertFalse(repo.claim(day,id,target))
        assertFalse(repo.claim(day,id,target,System.currentTimeMillis()+7_200_000L))
        val r=repo.records(day).single {it.targetId==target.id}
        assertEquals("accepted",r.status);assertEquals(1,r.attempts)
    }

    @Test fun failedReadOnlyQueryDoesNotDestroyAcceptedEvidence()=runBlocking {
        val id=account();val a=FakeAdapter(SignResult("accepted","等待确认",resultSource="accepted"));val e=engine(a)
        e.run(listOf(id));e.reconcile(id,target.id)
        assertEquals(1,a.queries.get());assertEquals(1,a.submissions.get())
        assertEquals("accepted",repo.records(day).single {it.targetId==target.id}.status)
        a.query=SignResult("already","官方明确今日已签",resultSource="official",verificationMethod="official_status",verifiedAt=SignRepository.now())
        e.reconcile(id,target.id)
        val r=repo.records(day).single {it.targetId==target.id}
        assertEquals("already",r.status);assertEquals("official",r.resultSource)
        assertEquals(1,r.attempts);assertEquals(1,a.submissions.get())
    }

    @Test fun accountAndDateAndTargetKeysAreIndependent() {
        val first=account();val second=account()
        assertTrue(repo.claim(day,first,target));repo.finish(day,first,target,SignResult("accepted","等待确认",resultSource="accepted"))
        assertTrue(repo.claim(day,second,target))
        assertTrue(repo.claim("2026-01-01",first,target))
        assertTrue(repo.claim(day,first,SignTarget("community|1","社区")))
        assertFalse(repo.claim(day,first,target))
    }

    @Test fun fastConcurrentRunsOnlySubmitOnce()=runBlocking {
        val id=account();val a=FakeAdapter(SignResult("accepted","等待确认",resultSource="accepted"));val e=engine(a)
        coroutineScope {(1..8).map {async(Dispatchers.IO){e.run(listOf(id))}}.awaitAll()}
        assertEquals(1,a.submissions.get());assertEquals(1,a.discoveries.get())
    }

    @Test fun http402DiscoveryDoesNotInvalidateKnownAuthorization()=runBlocking {
        val id=account();val calls=AtomicInteger()
        val verifiedBefore=repo.accounts().single().lastVerifiedAt
        val adapter=object:ProviderAdapter {
            override fun targets(c:JSONObject,persist:()->Unit):List<SignTarget> {calls.incrementAndGet();throw classifyStatus("POST","bbs-api.tajiduo.com",402)}
            override fun checkin(c:JSONObject,t:SignTarget,persist:()->Unit):SignResult=error("A rejected discovery must never sign")
        }
        engine(adapter).run(listOf(id))
        assertEquals("valid",repo.accounts().single().authState)
        assertEquals(verifiedBefore,repo.accounts().single().lastVerifiedAt)
        val r=repo.records(day).single()
        assertEquals(402,r.httpStatus);assertFalse(r.retryable)
        assertTrue(r.message.contains("402"));assertFalse(r.status in setOf("success","already","accepted"))
    }

    @Test fun interruptedRunningSubmissionCannotBeReclaimed() {
        val id=account();assertTrue(repo.claim(day,id,target))
        repo.recoverInterrupted();assertFalse(repo.claim(day,id,target))
        assertEquals("uncertain",repo.records(day).single().status)
    }

    @Test fun firstRunIncludesCommunityAndRepeatDoesNotRediscover()=runBlocking {
        val id=account();stores.save(id,JSONObject().put("communityCheckin",true))
        val discoveries=AtomicInteger();val submits=AtomicInteger()
        val adapter=object:ProviderAdapter {
            override fun targets(c:JSONObject,persist:()->Unit):List<SignTarget>{discoveries.incrementAndGet();return listOf(target,SignTarget("community|1","社区测试"))}
            override fun checkin(c:JSONObject,t:SignTarget,persist:()->Unit):SignResult {submits.incrementAndGet();return SignResult("accepted","等待确认",resultSource="accepted")}
        }
        val e=engine(adapter);e.run(listOf(id));e.run(listOf(id))
        assertEquals(2,submits.get());assertEquals(1,discoveries.get())
        assertEquals(setOf(target.id,"community|1"),repo.records(day).map{it.targetId}.toSet())
        assertTrue(repo.records(day).all{it.status=="accepted"})
    }

    @Test fun unverifiedWriterCannotCreateAConfirmedSuccess() {
        val id=account();assertTrue(repo.claim(day,id,target))
        repo.finish(day,id,target,SignResult("success","HTTP成功不代表签到成功"))
        val r=repo.records(day).single()
        assertEquals("uncertain",r.status);assertNotEquals("success",present(r).group)
    }

    @Test fun explicitNonRetryableFailureCannotRetryAfterCooldown() {
        val id=account();assertTrue(repo.claim(day,id,target))
        repo.finish(day,id,target,SignResult("failed","服务端402",httpStatus=402,retryable=false))
        assertFalse(repo.claim(day,id,target,System.currentTimeMillis()+7_200_000L))
    }

    @Test fun providerErrorCodeIsKeptSeparatelyFromHttpStatus() {
        val r=classifyStatus("POST","bbs-api.tajiduo.com",402,"/usercenter/api/refreshToken","10005").asResult()
        assertEquals(402,r.httpStatus);assertEquals("10005",r.providerCode)
        assertFalse(r.retryable)
        val record=com.glasssign.app.data.CheckRecord("a","discovery","授权与角色检查",r.status,r.message,1,"now",resultMeta=com.glasssign.app.data.ResultMeta(httpStatus=402,providerCode=r.providerCode))
        assertEquals("failed",com.glasssign.app.data.semanticState(record))
    }

    @Test fun confirmedAuthorizationRejectionMayUnlockAfterReauthorization() {
        val id=account();assertTrue(repo.claim(day,id,target))
        repo.finish(day,id,target,SignError("auth","授权失效，请重新登录",httpStatus=401).asResult())
        repo.deleteRecords(id)
        repo.acknowledgeSigned(day,id,target)
        repo.allowRejectedAuthRetry(id)
        assertTrue(repo.claim(day,id,target))
    }

    @Test fun schemaFiveUpgradePreservesAcceptedGuardAndAccount() {
        repo.close()
        context.openOrCreateDatabase(databaseName,Context.MODE_PRIVATE,null).use { db ->
            db.execSQL("CREATE TABLE accounts(id TEXT PRIMARY KEY,provider TEXT NOT NULL,nickname TEXT NOT NULL,enabled INTEGER NOT NULL,created TEXT NOT NULL,auth TEXT NOT NULL DEFAULT 'unknown',auth_message TEXT NOT NULL DEFAULT '',verified TEXT NOT NULL DEFAULT '',game TEXT NOT NULL DEFAULT '',games TEXT NOT NULL DEFAULT '',provider_uid TEXT NOT NULL DEFAULT '',role_scope INTEGER NOT NULL DEFAULT 0,auto_enabled INTEGER NOT NULL DEFAULT 1)")
            db.execSQL("CREATE TABLE records(day TEXT NOT NULL,account TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,target TEXT NOT NULL,name TEXT NOT NULL,status TEXT NOT NULL,message TEXT NOT NULL,attempts INTEGER NOT NULL,updated TEXT NOT NULL,stamp INTEGER NOT NULL,started TEXT NOT NULL DEFAULT '',result_source TEXT NOT NULL DEFAULT 'legacy',PRIMARY KEY(day,account,target))")
            db.execSQL("CREATE TABLE sign_guards(day TEXT NOT NULL,account TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,target TEXT NOT NULL,status TEXT NOT NULL,attempts INTEGER NOT NULL,stamp INTEGER NOT NULL,result_source TEXT NOT NULL DEFAULT 'legacy',PRIMARY KEY(day,account,target))")
            db.execSQL("CREATE TABLE pending_bindings(account TEXT PRIMARY KEY REFERENCES accounts(id) ON DELETE CASCADE,replaces_account TEXT UNIQUE REFERENCES accounts(id),roles_verified INTEGER NOT NULL DEFAULT 0,available_games TEXT NOT NULL DEFAULT '')")
            db.execSQL("CREATE TABLE retired_credentials(account TEXT PRIMARY KEY)")
            db.execSQL("CREATE TABLE game_bindings(account TEXT NOT NULL REFERENCES accounts(id),target TEXT NOT NULL,game TEXT NOT NULL,name TEXT NOT NULL,enabled INTEGER NOT NULL DEFAULT 1,auto_sign INTEGER NOT NULL DEFAULT 1,PRIMARY KEY(account,target))")
            db.execSQL("CREATE TABLE discovered_roles(account TEXT NOT NULL REFERENCES accounts(id),target TEXT NOT NULL,game TEXT NOT NULL,name TEXT NOT NULL,PRIMARY KEY(account,target))")
            db.execSQL("INSERT INTO accounts(id,provider,nickname,enabled,created,auth,game,games) VALUES('old','tagged','原备注',1,'old-time','valid','nte','nte')")
            db.execSQL("INSERT INTO records VALUES(?,'old',?,'旧受理记录','success','官方接受提交',2,'old-time',1,'old-start','accepted')",arrayOf(day,target.id))
            db.execSQL("INSERT INTO sign_guards VALUES(?,'old','community|1','success',2,1,'accepted')",arrayOf(day))
            db.version=5
        }
        repo=SignRepository(context,databaseName)
        assertEquals("原备注",repo.accounts().single().nickname)
        assertEquals(6,repo.readableDatabase.version)
        val r=repo.records(day).single()
        assertEquals("accepted",r.status);assertEquals("accepted",r.resultSource)
        assertEquals(2,r.attempts);assertEquals("old-start",r.startedAt)
        assertFalse(repo.claim(day,"old",target))
        assertFalse(repo.claim(day,"old",SignTarget("community|1","社区")))
        val checkpoint=context.noBackupFilesDir.resolve("schema-checkpoints/$databaseName.v5.json")
        val tables=JSONObject(checkpoint.readText()).getJSONObject("tables")
        assertTrue(tables.has("sign_guards"));assertTrue(tables.has("game_bindings"))
    }
}
