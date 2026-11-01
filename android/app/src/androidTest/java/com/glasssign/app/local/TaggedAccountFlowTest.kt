package com.glasssign.app.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glasssign.app.login.TaggedLoginClient
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class TaggedAccountFlowTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private val name="tagged-native-${UUID.randomUUID()}.db"
    private lateinit var repo:SignRepository
    private lateinit var credentials:LocalCredentialStore
    private val ids=mutableListOf<String>()
    private class Fake:Transport {
        val paths=mutableListOf<String>()
        override fun call(method:String,url:String,headers:Map<String,String>,query:Map<String,String>,body:String?,form:Map<String,String>?):JSONObject {
            paths.add(url)
            return JSONObject(when {
                url.endsWith("/openApi/secureLogin") -> """{"code":0,"result":{"token":"synthetic-account-only","userId":7}}"""
                url.endsWith("/usercenter/api/login") -> """{"code":0,"data":{"refreshToken":"synthetic-r1","accessToken":"synthetic-a1","uid":7}}"""
                url.endsWith("/refreshToken") -> """{"code":0,"data":{"refreshToken":"synthetic-r2","accessToken":"synthetic-a2","uid":7}}"""
                url.endsWith("/getGameRoles") -> {
                    assertEquals("1289",query["gameId"])
                    """{"code":0,"data":{"roles":[{"roleId":"fixture-role","roleName":"合成异环角色"}]}}"""
                }
                else -> error("No sign submission is permitted in a binding test")
            })
        }
    }
    @Before fun before() {repo=SignRepository(context,name);credentials=LocalCredentialStore(context)}
    @After fun after() {ids.forEach{credentials.delete(it)};repo.close();context.deleteDatabase(name)}
    @Test fun nativeLoginExchangeAndRolesCanBeConfirmedWithoutBrowserSession()=runBlocking {
        val http=Fake()
        val credential=TaggedLoginClient(http,TaggedLoginClient.Device("fixture-device","test","17","test"))
            .loginPassword("13800000000","synthetic-password")
        val bindings=LocalAccountBindings(repo,credentials)
        val id=bindings.prepare("tagged","fixture",credential,gameId="nte");ids.add(id)
        assertFalse(repo.accounts().single().enabled)
        val engine=LocalSignEngine(repo,credentials,mapOf("tagged" to TaggedAdapter(http)))
        val result=engine.discover(id)
        assertTrue(result.ok);assertEquals(listOf("异环 · 合成异环角色"),result.roles)
        assertTrue(repo.accounts().single().bindingPending)
        val stored=credentials.read(id)
        assertEquals("synthetic-r2",stored.getString("refreshToken"))
        assertFalse(stored.toString().contains("synthetic-account-only"))
        assertFalse(stored.toString().contains("synthetic-password"))
        assertFalse(stored.has("phone"));assertFalse(stored.has("captcha"))
        assertEquals(id,bindings.confirm(id));assertTrue(repo.accounts().single().enabled)
        assertTrue(repo.records(SignRepository.day()).isEmpty())
        assertEquals(4,http.paths.size)
    }
    @Test fun cancelledNativeBindingLeavesNoSyntheticAccountOrCredential()=runBlocking {
        val http=Fake();val c=TaggedLoginClient(http,TaggedLoginClient.Device("fixture","test","17","test"))
            .loginPassword("13800000000","synthetic-password")
        val bindings=LocalAccountBindings(repo,credentials)
        val id=bindings.prepare("tagged","fixture",c,gameId="nte");ids.add(id)
        bindings.cancel(id);assertTrue(repo.accounts().isEmpty())
        try {credentials.read(id);fail("Expected removed credential")}catch(_:SignError){}
    }
}
