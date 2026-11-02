package com.glasssign.app.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glasssign.app.data.GameCatalog
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MultiGameMigrationTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private val name="multi-${UUID.randomUUID()}.db"
    private lateinit var repo:SignRepository
    private lateinit var secrets:LocalCredentialStore
    private val ids=mutableListOf<String>()
    @Before fun before(){repo=SignRepository(context,name);secrets=LocalCredentialStore(context)}
    @After fun after(){ids.forEach{secrets.delete(it)};repo.close();context.deleteDatabase(name)}
    private fun account(games:List<String> = emptyList()):String {
        val id=UUID.randomUUID().toString();ids.add(id)
        repo.add("miyoushe","合成账号",id,gameIds=games)
        secrets.save(id,GameCatalog.scope("miyoushe",games,JSONObject().put("cookie","synthetic")))
        repo.enabled(id,false);repo.auth(id,"valid","synthetic")
        val t=SignTarget("genshin|fixture|server","合成角色")
        repo.claim(SignRepository.day(),id,t);repo.finish(SignRepository.day(),id,t,SignResult("success","synthetic"))
        return id
    }
    private val adapter=object:ProviderAdapter {
        override fun targets(c:JSONObject,persist:()->Unit):List<SignTarget> {
            c.put("rotated","synthetic");persist()
            return listOf(SignTarget("genshin|fixture|server","原神 · 合成角色"),SignTarget("zzz|fixture|server","绝区零 · 合成角色"))
        }
        override fun checkin(c:JSONObject,t:SignTarget,persist:()->Unit):SignResult=error("Read-only operation submitted checkin")
    }
    @Test fun existingAuthorizationCanSelectGamesWithoutLoginAndKeepsPauseHistory()=runBlocking {
        val id=account(listOf("genshin"))
        val outcome=LocalSignEngine(repo,secrets,mapOf("miyoushe" to adapter)).gamesForSelection(id)
        assertEquals(listOf("genshin","zzz"),outcome.availableGameIds)
        assertEquals(listOf("genshin"),repo.accounts().single().gameIds)
        assertEquals("genshin",secrets.read(id).getJSONArray("games").getString(0))
        assertEquals("synthetic",secrets.read(id).getString("rotated"))
        LocalAccountBindings(repo,secrets).updateGames(id,listOf("genshin","zzz"))
        repo.close();repo=SignRepository(context,name)
        assertEquals(id,repo.accounts().single().id)
        assertEquals(listOf("genshin","zzz"),repo.accounts().single().gameIds)
        assertFalse(repo.accounts().single().enabled)
        assertEquals("success",repo.records(SignRepository.day()).single().status)
    }
    @Test fun pendingSelectionMustHaveVerifiedRoleAndUnknownGameCannotBeConfirmed()=runBlocking {
        val bindings=LocalAccountBindings(repo,secrets)
        val id=bindings.prepare("miyoushe","fixture",JSONObject().put("cookie","synthetic"),gameIds=GameCatalog.idsFor("miyoushe"));ids.add(id)
        val outcome=LocalSignEngine(repo,secrets,mapOf("miyoushe" to adapter)).discover(id)
        assertEquals(listOf("genshin","zzz"),outcome.availableGameIds)
        for(invalid in listOf(emptyList(),listOf("honkai3"),listOf("nte"))) {
            try {bindings.confirm(id,invalid);fail("Unverified game accepted")}catch(_:IllegalStateException){}
            assertFalse(repo.accounts().single().enabled)
        }
        assertEquals(id,bindings.confirm(id,listOf("zzz")))
        assertEquals(listOf("zzz"),repo.accounts().single().gameIds)
        assertEquals("zzz",secrets.read(id).getJSONArray("games").getString(0))
    }
    @Test fun legacyMultiGameMigrationIsIdempotentAndPreservesPausedAccount() {
        val id=account()
        val bindings=LocalAccountBindings(repo,secrets)
        bindings.migrateLegacyGames();bindings.migrateLegacyGames()
        assertEquals(listOf("genshin","honkai_sr","zzz"),repo.accounts().single().gameIds)
        assertFalse(repo.accounts().single().enabled)
        assertEquals(id,repo.records(SignRepository.day()).single().accountId)
        assertEquals("synthetic",secrets.read(id).getString("cookie"))
    }
    @Test fun schemaThreeUpgradePreservesSingleGameAndPendingProof() {
        repo.close();context.deleteDatabase(name)
        context.openOrCreateDatabase(name,Context.MODE_PRIVATE,null).use { db ->
            db.execSQL("CREATE TABLE accounts(id TEXT PRIMARY KEY,provider TEXT NOT NULL,nickname TEXT NOT NULL,enabled INTEGER NOT NULL,created TEXT NOT NULL,auth TEXT NOT NULL DEFAULT 'unknown',auth_message TEXT NOT NULL DEFAULT '',verified TEXT NOT NULL DEFAULT '',game TEXT NOT NULL DEFAULT '')")
            db.execSQL("CREATE TABLE records(day TEXT NOT NULL,account TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,target TEXT NOT NULL,name TEXT NOT NULL,status TEXT NOT NULL,message TEXT NOT NULL,attempts INTEGER NOT NULL,updated TEXT NOT NULL,stamp INTEGER NOT NULL,PRIMARY KEY(day,account,target))")
            db.execSQL("CREATE TABLE pending_bindings(account TEXT PRIMARY KEY REFERENCES accounts(id) ON DELETE CASCADE,replaces_account TEXT UNIQUE REFERENCES accounts(id) ON DELETE RESTRICT,roles_verified INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("CREATE TABLE retired_credentials(account TEXT PRIMARY KEY)")
            db.execSQL("INSERT INTO accounts VALUES('fixture','miyoushe','合成',0,'date','valid','','','zzz')")
            db.execSQL("INSERT INTO records VALUES('2026-11-01','fixture','zzz|fixture|server','合成','success','synthetic',1,'date',0)")
            db.version=3
        }
        repo=SignRepository(context,name)
        assertEquals(5,repo.readableDatabase.version)
        assertEquals(listOf("zzz"),repo.accounts().single().gameIds)
        assertFalse(repo.accounts().single().enabled)
        assertEquals("success",repo.records("2026-11-01").single().status)
    }
}
