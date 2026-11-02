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
class GameBindingIntegrationTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private val dbName="game-binding-${UUID.randomUUID()}.db"
    private lateinit var repository:SignRepository
    private lateinit var credentials:LocalCredentialStore
    private val ids=mutableListOf<String>()
    @Before fun before() {repository=SignRepository(context,dbName);credentials=LocalCredentialStore(context)}
    @After fun after() {ids.forEach{credentials.delete(it)};repository.close();context.deleteDatabase(dbName)}
    @Test fun gameSelectionSurvivesReopenAndRepeatedCheckDoesNotResubmit()=runBlocking {
        val id=LocalAccountBindings(repository,credentials).prepare("miyoushe","fixture",JSONObject().put("fixture",true),gameId="zzz");ids.add(id)
        repository.close();repository=SignRepository(context,dbName)
        assertEquals("zzz",repository.accounts().single().gameId)
        var submissions=0
        val adapter=object:ProviderAdapter {
            override fun targets(c:JSONObject,persist:()->Unit):List<SignTarget> {
                assertEquals("zzz",c.getJSONArray("games").getString(0));assertEquals(1,c.getJSONArray("games").length())
                return listOf(SignTarget("zzz|fixture|server","绝区零 · 合成角色"))
            }
            override fun checkin(c:JSONObject,t:SignTarget,persist:()->Unit):SignResult {submissions++;return SignResult("success","synthetic only")}
        }
        val engine=LocalSignEngine(repository,credentials,mapOf("miyoushe" to adapter))
        assertTrue(engine.discover(id).ok)
        LocalAccountBindings(repository,credentials).confirm(id)
        engine.run(listOf(id));engine.run(listOf(id))
        assertEquals(1,submissions);assertEquals(1,repository.records(SignRepository.day()).count{it.status=="already"})
        assertEquals(1,repository.records(SignRepository.day()).single().attempts)
    }
    @Test fun communityOnlyResponseCannotConfirmNteGameBinding()=runBlocking {
        val id=LocalAccountBindings(repository,credentials).prepare("tagged","fixture",JSONObject().put("fixture",true),gameId="nte");ids.add(id)
        val adapter=object:ProviderAdapter {
            override fun targets(c:JSONObject,persist:()->Unit)=listOf(SignTarget("community|1","社区签到"),SignTarget("no-roles","未绑定角色"))
            override fun checkin(c:JSONObject,t:SignTarget,persist:()->Unit):SignResult=error("Must not submit")
        }
        assertFalse(LocalSignEngine(repository,credentials,mapOf("tagged" to adapter)).discover(id).ok)
        assertFalse(repository.pendingBinding(id)!!.rolesVerified)
        assertFalse(repository.accounts().single().enabled)
    }
    @Test fun schemaTwoUpgradePreservesAccountAndHistory() {
        repository.close();context.deleteDatabase(dbName)
        context.openOrCreateDatabase(dbName,Context.MODE_PRIVATE,null).use { db ->
            db.execSQL("CREATE TABLE accounts(id TEXT PRIMARY KEY,provider TEXT NOT NULL,nickname TEXT NOT NULL,enabled INTEGER NOT NULL,created TEXT NOT NULL,auth TEXT NOT NULL DEFAULT 'unknown',auth_message TEXT NOT NULL DEFAULT '',verified TEXT NOT NULL DEFAULT '')")
            db.execSQL("CREATE TABLE records(day TEXT NOT NULL,account TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,target TEXT NOT NULL,name TEXT NOT NULL,status TEXT NOT NULL,message TEXT NOT NULL,attempts INTEGER NOT NULL,updated TEXT NOT NULL,stamp INTEGER NOT NULL,PRIMARY KEY(day,account,target))")
            db.execSQL("CREATE TABLE pending_bindings(account TEXT PRIMARY KEY REFERENCES accounts(id) ON DELETE CASCADE,replaces_account TEXT UNIQUE REFERENCES accounts(id) ON DELETE RESTRICT,roles_verified INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("CREATE TABLE retired_credentials(account TEXT PRIMARY KEY)")
            db.execSQL("INSERT INTO accounts VALUES('legacy','miyoushe','fixture',1,'date','valid','','')")
            db.execSQL("INSERT INTO records VALUES(?,'legacy','fixture','fixture','success','synthetic',1,'date',0)",arrayOf(SignRepository.day()))
            db.version=2
        }
        repository=SignRepository(context,dbName)
        assertEquals("",repository.accounts().single().gameId)
        assertEquals("success",repository.records(SignRepository.day()).single().status)
        assertEquals(5,repository.readableDatabase.version)
    }
}
