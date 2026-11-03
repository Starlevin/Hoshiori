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
class LocalAccountBindingsTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private val dbName="binding-test-${UUID.randomUUID()}.db"
    private lateinit var repository:SignRepository
    private lateinit var credentials:LocalCredentialStore
    private lateinit var bindings:LocalAccountBindings
    private val secrets=mutableSetOf<String>()
    @Before fun setup() {repository=SignRepository(context,dbName);credentials=LocalCredentialStore(context);bindings=LocalAccountBindings(repository,credentials)}
    @After fun cleanup() {secrets.forEach{credentials.delete(it)};repository.close();context.deleteDatabase(dbName)}
    private fun c(value:String)=JSONObject().put("test",value)
    private fun original(enabled:Boolean=true):String {
        val id=repository.add("miyoushe","原账号");secrets.add(id);credentials.save(id,c("synthetic-original"))
        repository.auth(id,"valid","合成有效授权");repository.enabled(id,enabled)
        val t=SignTarget("game|fixture-role","合成角色")
        assertTrue(repository.claim(SignRepository.day(),id,t));repository.finish(SignRepository.day(),id,t,SignResult("success","mock only"))
        return id
    }
    private suspend fun prepare(replaces:String?=null):String = bindings.prepare("miyoushe","暂存账号",c("synthetic-new"),replaces).also{secrets.add(it)}
    private fun missing(id:String) {try{credentials.read(id);fail("Expected cleared synthetic credential")}catch(_:SignError){}}

    @Test fun cancelRenewalPreservesOriginalCredentialAccountAndHistory()=runBlocking {
        val id=original();val stage=prepare(id)
        assertNotEquals(id,stage);assertEquals("synthetic-original",credentials.read(id).getString("test"))
        bindings.cancel(stage)
        assertEquals(listOf(id),repository.accounts().map{it.id});assertTrue(repository.accounts().single().enabled)
        assertEquals("synthetic-original",credentials.read(id).getString("test"))
        assertEquals("success",repository.records(SignRepository.day()).single().status)
        assertTrue(repository.pendingBindings().isEmpty());missing(stage)
    }
    @Test fun confirmationPreservesIdentityHistoryAndPausedPreference()=runBlocking {
        val id=original(false);val stage=prepare(id);repository.markPendingRoles(stage,true)
        assertEquals(id,bindings.confirm(stage))
        assertEquals(listOf(id),repository.accounts().map{it.id});assertFalse(repository.accounts().single().enabled)
        assertEquals("synthetic-new",credentials.read(id).getString("test"));assertEquals(1,repository.records(SignRepository.day()).single().attempts)
        assertFalse(repository.claim(SignRepository.day(),id,SignTarget("game|fixture-role","合成角色")))
        assertTrue(repository.pendingBindings().isEmpty());assertTrue(repository.retiredCredentials().isEmpty());missing(stage)
    }
    @Test fun confirmingWithoutVerifiedRolesDoesNotTouchOriginal()=runBlocking {
        val id=original();val stage=prepare(id)
        try{bindings.confirm(stage);fail()}catch(_:IllegalStateException){}
        assertEquals("synthetic-original",credentials.read(id).getString("test"))
        assertEquals("pending",repository.accounts().first{it.id==stage}.authState)
    }
    @Test fun confirmingNewAccountEnablesOnlyAfterRoleVerification()=runBlocking {
        val stage=prepare();assertFalse(repository.accounts().single().enabled)
        repository.markPendingRoles(stage,true)
        assertEquals(stage,bindings.confirm(stage));assertTrue(repository.accounts().single().enabled)
        assertEquals("valid",repository.accounts().single().authState)
        assertEquals("synthetic-new",credentials.read(stage).getString("test"));assertTrue(repository.pendingBindings().isEmpty())
    }
    @Test fun deletingOriginalAlsoRemovesStagedReplacement()=runBlocking {
        val id=original();val stage=prepare(id);bindings.deleteAccount(id)
        assertTrue(repository.accounts().isEmpty());assertTrue(repository.records(SignRepository.day()).isEmpty());assertTrue(repository.pendingBindings().isEmpty())
        missing(id);missing(stage)
    }
    @Test fun clearingCredentialsCancelsDraftButPreservesOriginalHistory()=runBlocking {
        val id=original();val stage=prepare(id);bindings.clearCredentials()
        assertEquals(listOf(id),repository.accounts().map{it.id});assertFalse(repository.accounts().single().enabled)
        assertEquals("needs_action",repository.accounts().single().authState)
        assertEquals("success",repository.records(SignRepository.day()).single().status);missing(id);missing(stage)
    }
    @Test fun stagedReplacementSurvivesRepositoryReopenWithoutReplacingOriginal()=runBlocking {
        val id=original();val stage=prepare(id);repository.close();repository=SignRepository(context,dbName)
        bindings=LocalAccountBindings(repository,credentials)
        assertEquals(id,repository.pendingBinding(stage)!!.replacesAccountId)
        assertEquals("synthetic-original",credentials.read(id).getString("test"));bindings.cancel(stage)
        assertEquals(listOf(id),repository.accounts().map{it.id})
    }
    @Test fun interruptedPostCommitSecretCleanupCanBeRecovered()=runBlocking {
        val id=original();val stage=prepare(id);repository.markPendingRoles(stage,true)
        // Simulate process death after the DB commit, before staged ciphertext is removed.
        credentials.save(id,credentials.read(stage));assertEquals(id,repository.commitPending(stage))
        assertEquals(listOf(stage),repository.retiredCredentials())
        repository.close();repository=SignRepository(context,dbName);bindings=LocalAccountBindings(repository,credentials)
        bindings.cleanupRetired();assertTrue(repository.retiredCredentials().isEmpty());missing(stage)
        assertEquals("synthetic-new",credentials.read(id).getString("test"))
    }
    @Test fun cannotCancelOrdinaryOrLegacyAccountThroughDraftAction()=runBlocking {
        val id=original();repository.auth(id,"pending","旧版未完成更新")
        try{bindings.cancel(id);fail()}catch(_:IllegalStateException){}
        assertEquals(listOf(id),repository.accounts().map{it.id});assertEquals(1,repository.records(SignRepository.day()).size)
        assertEquals("synthetic-original",credentials.read(id).getString("test"))
    }
    @Test fun repeatedRenewalDoesNotOverwriteExistingDraftOrOriginal()=runBlocking {
        val id=original();val stage=prepare(id)
        try{prepare(id);fail()}catch(_:IllegalStateException){}
        assertEquals(2,repository.accounts().size);assertEquals(stage,repository.pendingBindings().single().accountId)
        assertEquals("synthetic-original",credentials.read(id).getString("test"))
    }
    @Test fun failedRediscoveryRevokesPreviousRoleVerification()=runBlocking {
        val id=original();val stage=prepare(id);repository.markPendingRoles(stage,true)
        val adapter=object:ProviderAdapter {
            override fun targets(c:JSONObject,persist:()->Unit):List<SignTarget> = throw SignError("auth","合成失效授权")
            override fun checkin(c:JSONObject,t:SignTarget,persist:()->Unit)=error("No sign request allowed")
        }
        LocalSignEngine(repository,credentials,mapOf("miyoushe" to adapter)).discover(stage)
        assertFalse(repository.pendingBinding(stage)!!.rolesVerified)
        try{bindings.confirm(stage);fail()}catch(_:IllegalStateException){}
        assertEquals("synthetic-original",credentials.read(id).getString("test"))
    }
    @Test fun schemaOneUpgradePreservesAccountsCredentialsAndRecords() {
        val legacyName="legacy-${UUID.randomUUID()}.db";val id=UUID.randomUUID().toString();secrets.add(id);credentials.save(id,c("synthetic-legacy"))
        try {
            context.openOrCreateDatabase(legacyName,Context.MODE_PRIVATE,null).use{db->
                db.execSQL("CREATE TABLE accounts(id TEXT PRIMARY KEY, provider TEXT NOT NULL, nickname TEXT NOT NULL, enabled INTEGER NOT NULL, created TEXT NOT NULL, auth TEXT NOT NULL DEFAULT 'unknown', auth_message TEXT NOT NULL DEFAULT '', verified TEXT NOT NULL DEFAULT '')")
                db.execSQL("CREATE TABLE records(day TEXT NOT NULL, account TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE, target TEXT NOT NULL, name TEXT NOT NULL, status TEXT NOT NULL, message TEXT NOT NULL, attempts INTEGER NOT NULL, updated TEXT NOT NULL, stamp INTEGER NOT NULL, PRIMARY KEY(day,account,target))")
                db.execSQL("INSERT INTO accounts VALUES (?,'miyoushe','原有账号',1,'2026-10-31','pending','','')",arrayOf(id))
                db.execSQL("INSERT INTO records VALUES ('2026-10-31',?,'game|fixture-role','合成角色','success','mock only',1,'2026-10-31',1)",arrayOf(id))
                db.version=1
            }
            SignRepository(context,legacyName).use { upgraded ->
                assertEquals(5,upgraded.readableDatabase.version);assertEquals(id,upgraded.accounts().single().id)
                assertEquals("needs_action",upgraded.accounts().single().authState);assertFalse(upgraded.accounts().single().enabled)
                assertEquals("success",upgraded.records("2026-10-31").single().status)
                assertTrue(upgraded.pendingBindings().isEmpty());assertTrue(upgraded.retiredCredentials().isEmpty())
                assertEquals("synthetic-legacy",credentials.read(id).getString("test"))
            }
        } finally {context.deleteDatabase(legacyName)}
    }
    @Test fun renewalUnlocksDiscoveryButKeepsUncertainSignSubmissionBlocked()=runBlocking {
        val id=original();val day=SignRepository.day()
        val discovery=SignTarget("discovery","合成授权发现")
        val uncertain=SignTarget("game|uncertain","合成不确定提交")
        for(t in listOf(discovery,uncertain)) {
            assertTrue(repository.claim(day,id,t));repository.finish(day,id,t,SignResult("needs_action","mock only"))
        }
        val stage=prepare(id);repository.markPendingRoles(stage,true);bindings.confirm(stage)
        assertTrue(repository.claim(day,id,discovery))
        assertFalse(repository.claim(day,id,uncertain))
        assertEquals("needs_action",repository.records(day).first{it.targetId==uncertain.id}.status)
    }
}
