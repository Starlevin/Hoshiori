package com.glasssign.app.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glasssign.app.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class V070AccountsTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private val name="v070-${UUID.randomUUID()}.db"
    private lateinit var repo:SignRepository
    private lateinit var secrets:LocalCredentialStore
    private lateinit var bindings:LocalAccountBindings
    private val ids=mutableListOf<String>()
    private var posts=0
    private val first=SignTarget("genshin|fixture-role1|server","原神 · 合成角色1")
    private val second=SignTarget("genshin|fixture-role2|server","原神 · 合成角色2")
    private val adapter=object:ProviderAdapter {
        override fun targets(c:JSONObject,persist:()->Unit)=listOf(first,second)
        override fun checkin(c:JSONObject,t:SignTarget,persist:()->Unit):SignResult {posts++;return SignResult("success","合成官方确认")}
        override fun queryStatus(c:JSONObject,t:SignTarget,persist:()->Unit)=SignResult("already","只读官方状态确认已签到")
    }
    @Before fun before(){repo=SignRepository(context,name);secrets=LocalCredentialStore(context);bindings=LocalAccountBindings(repo,secrets)}
    @After fun after(){ids.forEach{secrets.delete(it)};repo.close();context.deleteDatabase(name);java.io.File(context.noBackupFilesDir,"schema-checkpoints/$name.v4.json").delete()}
    private fun credential(uid:String)=JSONObject().put("stuid",uid).put("cookie","synthetic-$uid")
    private suspend fun draft(uid:String):String {
        val id=bindings.prepare("miyoushe","合成 $uid",credential(uid),gameIds=GameCatalog.idsFor("miyoushe"));ids.add(id)
        assertTrue(LocalSignEngine(repo,secrets,mapOf("miyoushe" to adapter)).discover(id).ok)
        return id
    }
    private suspend fun bind(uid:String,roles:Set<String> = setOf(first.id),auto:Boolean=true):String {
        val id=draft(uid);assertEquals(id,bindings.confirm(id,listOf("genshin"),roles,auto));return id
    }
    @Test fun explicitRoleSelectionAndAutomaticPreferenceDoNotSignDuringBinding()=runBlocking {
        val id=bind("fixture-main",auto=false)
        assertEquals(0,posts);assertEquals(listOf(first.id),repo.bindings(id).map{it.targetId})
        assertTrue(repo.accounts().single().enabled);assertFalse(repo.accounts().single().autoSignEnabled)
        val engine=LocalSignEngine(repo,secrets,mapOf("miyoushe" to adapter))
        assertEquals(0,engine.run(scheduled=true).attempted)
        assertEquals(1,engine.run(listOf(id)).attempted)
        engine.run(listOf(id));assertEquals(1,posts)
        assertEquals("already",repo.records(SignRepository.day()).single().status)
    }
    @Test fun sameIdentityRedirectsWithoutReplacingAuthorizationAndDifferentAccountsStayIndependent()=runBlocking {
        val main=bind("fixture-main");val original=secrets.read(main).toString()
        val duplicate=draft("fixture-main")
        assertEquals(main,bindings.confirm(duplicate,listOf("genshin"),setOf(second.id)))
        assertEquals(original,secrets.read(main).toString());assertEquals(listOf(first.id),repo.bindings(main).map{it.targetId})
        assertEquals(1,repo.accounts().size)
        val alt=bind("fixture-alt",setOf(second.id));assertNotEquals(main,alt)
        assertEquals(2,repo.accounts().size)
        LocalSignEngine(repo,secrets,mapOf("miyoushe" to adapter)).run()
        assertEquals(setOf(main,alt),repo.records(SignRepository.day()).map{it.accountId}.toSet())
        assertEquals(2,posts)
    }
    @Test fun managementReadsRolesWithoutSigningAndCancelKeepsSelection()=runBlocking {
        val id=bind("fixture-main")
        val engine=LocalSignEngine(repo,secrets,mapOf("miyoushe" to adapter))
        assertEquals(2,engine.gamesForSelection(id).roleChoices.size)
        assertEquals(listOf(first.id),repo.bindings(id).map{it.targetId});assertEquals(0,posts)
        bindings.updateGames(id,listOf("genshin"),setOf(second.id),false)
        assertEquals(listOf(second.id),repo.bindings(id).map{it.targetId});assertFalse(repo.accounts().single().autoSignEnabled)
    }
    @Test fun forgedRolesAndWrongIdentityRenewalCannotMutateOriginal()=runBlocking {
        val main=bind("fixture-main")
        val pending=bindings.prepare("miyoushe","renew",credential("fixture-alt"),main,gameIds=listOf("genshin"));ids.add(pending)
        LocalSignEngine(repo,secrets,mapOf("miyoushe" to adapter)).discover(pending)
        try{bindings.confirm(pending,listOf("genshin"),setOf(first.id));fail()}catch(_:IllegalStateException){}
        assertEquals("fixture-main",repo.accounts().first{it.id==main}.providerUserId)
        bindings.cancel(pending);assertEquals(1,repo.accounts().size)
        try{bindings.updateGames(main,listOf("genshin"),setOf("genshin|foreign|server"));fail()}catch(_:IllegalStateException){}
        assertEquals(listOf(first.id),repo.bindings(main).map{it.targetId})
    }
    @Test fun revokePauseAndHistoryDeletionAreIndependentAndDoNotUnlockUnknownSubmission()=runBlocking {
        val main=bind("fixture-main");val alt=bind("fixture-alt")
        val day=SignRepository.day();repo.claim(day,main,first);repo.finish(day,main,first,SignResult("uncertain","合成未知提交"))
        repo.deleteRecords(main);assertTrue(repo.records(day).isEmpty())
        assertFalse(repo.claim(day,main,first,Long.MAX_VALUE))
        assertTrue(repo.accounts().first{it.id==main}.enabled);assertTrue(secrets.read(main).has("cookie"))
        bindings.revoke(main)
        assertEquals(2,repo.accounts().size);assertEquals(1,repo.bindings(main).size)
        assertTrue(repo.accounts().first{it.id==main}.enabled)
        assertTrue(secrets.read(alt).has("cookie"))
        repo.enabled(alt,false);assertFalse(repo.accounts().first{it.id==alt}.enabled)
    }
    @Test fun unknownCanOnlyBeResolvedByReadonlyOfficialStatusWithoutNewAttempt()=runBlocking {
        val id=bind("fixture-main");val day=SignRepository.day()
        repo.claim(day,id,first);repo.finish(day,id,first,SignResult("uncertain","合成未知"))
        LocalSignEngine(repo,secrets,mapOf("miyoushe" to adapter)).reconcile(id,first.id)
        assertEquals("already",repo.records(day).single().status);assertEquals(1,repo.records(day).single().attempts)
        assertEquals(0,posts);assertFalse(repo.claim(day,id,first,Long.MAX_VALUE))
    }
    @Test fun acceptedSubmissionKeepsItsProvenanceAcrossCacheAndHistoryCleanup()=runBlocking {
        val id=bind("fixture-main");val day=SignRepository.day()
        repo.claim(day,id,first);repo.finish(day,id,first,SignResult("success","官方接口已接受签到"))
        repo.acknowledgeSigned(day,id,first)
        assertEquals("submitted",present(repo.records(day).single()).group)
        assertEquals("accepted",repo.records(day).single().resultSource)
        repo.deleteRecords(id);repo.acknowledgeSigned(day,id,first)
        assertEquals("submitted",present(repo.records(day).single()).group)
        assertFalse(repo.claim(day,id,first,Long.MAX_VALUE))
    }
    @Test fun roleManagementFetchesOfficialRolesButCancelPreservesLegacyPinnedScope()=runBlocking {
        val id=bind("fixture-main")
        secrets.save(id,secrets.read(id).put("roleIds",org.json.JSONArray().put("synthetic-pinned")))
        val fresh=object:ProviderAdapter {
            override fun targets(c:JSONObject,persist:()->Unit):List<SignTarget>{assertFalse(c.has("roleIds"));c.put("rotated","synthetic");persist();return listOf(first,second)}
            override fun checkin(c:JSONObject,t:SignTarget,persist:()->Unit):SignResult=error("Readonly query submitted sign")
        }
        assertEquals(2,LocalSignEngine(repo,secrets,mapOf("miyoushe" to fresh)).gamesForSelection(id).roleChoices.size)
        assertEquals("synthetic-pinned",secrets.read(id).getJSONArray("roleIds").getString(0))
        assertEquals("synthetic",secrets.read(id).getString("rotated"))
        assertEquals(listOf(first.id),repo.bindings(id).map{it.targetId})
        bindings.updateGames(id,listOf("genshin"),setOf(second.id))
        assertFalse(secrets.read(id).has("roleIds"));assertEquals(listOf(second.id),repo.bindings(id).map{it.targetId})
    }
    @Test fun reauthorizationUnlocksOnlyAnExplicitAuthRejectionAndKeepsUnknownBlocked()=runBlocking {
        val id=bind("fixture-main");val day=SignRepository.day()
        repo.claim(day,id,first);repo.finish(day,id,first,SignResult("needs_action","授权失效，请重新登录"))
        repo.claim(day,id,second);repo.finish(day,id,second,SignResult("uncertain","提交结果不确定"))
        val stage=bindings.prepare("miyoushe","renew",credential("fixture-main"),id,gameIds=listOf("genshin"));ids.add(stage)
        LocalSignEngine(repo,secrets,mapOf("miyoushe" to adapter)).discover(stage)
        assertEquals(id,bindings.confirm(stage,listOf("genshin"),setOf(first.id)))
        val record=repo.records(day).first{it.targetId==first.id}
        assertEquals("failed",record.status);assertEquals(1,record.attempts)
        assertTrue(repo.claim(day,id,first));assertFalse(repo.claim(day,id,second,Long.MAX_VALUE))
        assertEquals("uncertain",repo.records(day).first{it.targetId==second.id}.status)
    }
    @Test fun renewalKeepsPauseAndHonorsExplicitAutomaticPreference()=runBlocking {
        val id=bind("fixture-main",auto=false);repo.enabled(id,false)
        for(auto in listOf(false,true)) {
            val stage=bindings.prepare("miyoushe","renew",credential("fixture-main"),id,gameIds=listOf("genshin"));ids.add(stage)
            LocalSignEngine(repo,secrets,mapOf("miyoushe" to adapter)).discover(stage)
            assertEquals(id,bindings.confirm(stage,listOf("genshin"),setOf(first.id),auto))
            assertFalse(repo.accounts().first{it.id==id}.enabled)
            assertEquals(auto,repo.accounts().first{it.id==id}.autoSignEnabled)
            assertEquals(auto,repo.bindings(id).single().autoSignEnabled)
        }
    }
    @Test fun snapshotFailureRollsBackSchemaWithoutDeletingLegacyData() {
        repo.close();context.deleteDatabase(name)
        createV4()
        val checkpoint=java.io.File(context.noBackupFilesDir,"schema-checkpoints/$name.v4.json")
        checkpoint.parentFile!!.mkdirs();checkpoint.writeText("invalid checkpoint")
        repo=SignRepository(context,name)
        try{repo.writableDatabase;fail()}catch(_:Exception){}
        context.openOrCreateDatabase(name,Context.MODE_PRIVATE,null).use{db->assertEquals(4,db.version);db.rawQuery("SELECT nickname FROM accounts WHERE id='fixture'",null).use{c->assertTrue(c.moveToFirst());assertEquals("旧版合成",c.getString(0))}}
        MigrationSnapshot.quarantineInvalid(context,name)
        assertFalse(checkpoint.exists())
        assertTrue(checkpoint.parentFile!!.listFiles()!!.any{it.name.startsWith(checkpoint.name+".invalid-")})
        assertEquals(5,repo.writableDatabase.version)
        assertFalse(repo.accounts().single().enabled)
        assertEquals("success",repo.records("2026-11-01").single().status)
        assertTrue(checkpoint.exists())
    }
    private fun createV4(){context.openOrCreateDatabase(name,Context.MODE_PRIVATE,null).use{db->
        db.execSQL("CREATE TABLE accounts(id TEXT PRIMARY KEY,provider TEXT NOT NULL,nickname TEXT NOT NULL,enabled INTEGER NOT NULL,created TEXT NOT NULL,auth TEXT NOT NULL DEFAULT 'unknown',auth_message TEXT NOT NULL DEFAULT '',verified TEXT NOT NULL DEFAULT '',game TEXT NOT NULL DEFAULT '',games TEXT NOT NULL DEFAULT '')")
        db.execSQL("CREATE TABLE records(day TEXT NOT NULL,account TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,target TEXT NOT NULL,name TEXT NOT NULL,status TEXT NOT NULL,message TEXT NOT NULL,attempts INTEGER NOT NULL,updated TEXT NOT NULL,stamp INTEGER NOT NULL,PRIMARY KEY(day,account,target))")
        db.execSQL("CREATE TABLE pending_bindings(account TEXT PRIMARY KEY REFERENCES accounts(id) ON DELETE CASCADE,replaces_account TEXT UNIQUE REFERENCES accounts(id) ON DELETE RESTRICT,roles_verified INTEGER NOT NULL DEFAULT 0,available_games TEXT NOT NULL DEFAULT '')")
        db.execSQL("CREATE TABLE retired_credentials(account TEXT PRIMARY KEY)")
        db.execSQL("INSERT INTO accounts VALUES('fixture','miyoushe','旧版合成',0,'date','valid','','','zzz','zzz')")
        db.execSQL("INSERT INTO records VALUES('2026-11-01','fixture','zzz|fixture|server','合成角色','success','synthetic',1,'date',0)")
        db.version=4
    }}
}
