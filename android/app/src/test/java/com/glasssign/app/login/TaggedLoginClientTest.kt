package com.glasssign.app.login

import com.glasssign.app.local.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TaggedLoginClientTest {
    private data class Call(val url:String,val headers:Map<String,String>,val form:Map<String,String>)
    private class Fake(vararg val replies:String):Transport {
        val calls=mutableListOf<Call>()
        override fun call(method:String,url:String,headers:Map<String,String>,query:Map<String,String>,body:String?,form:Map<String,String>?):JSONObject {
            assertEquals("POST",method);calls.add(Call(url,headers,form ?: emptyMap()))
            return JSONObject(replies[calls.size-1])
        }
    }
    private val device=TaggedLoginClient.Device("synthetic-device","test-model","17","test-brand")
    private val account="""{"code":0,"result":{"token":"synthetic-account-authorization","userId":123}}"""
    private val community="""{"code":0,"data":{"refreshToken":"synthetic-refresh","accessToken":"synthetic-access","uid":456}}"""
    @Test fun independentProtocolEncryptionVector() {
        assertEquals("IqfquRou563hM4IuwWbe4Q==",TaggedLoginClient.encrypted("13800000000"))
    }
    @Test fun passwordLoginExchangesInBandAuthorizationAndNeverReturnsPassword() {
        val http=Fake(account,community)
        val result=TaggedLoginClient(http,device).loginPassword("13800000000","synthetic-password")
        assertEquals(listOf("https://user.laohu.com/openApi/secureLogin","https://bbs-api.tajiduo.com/usercenter/api/login"),http.calls.map{it.url})
        assertEquals("IqfquRou563hM4IuwWbe4Q==",http.calls[0].form["username"])
        assertNotEquals("synthetic-password",http.calls[0].form["password"])
        assertFalse(http.calls[0].headers.containsKey("robot-auth-type"))
        assertEquals("test-model",http.calls[0].form["deviceModel"])
        assertEquals("synthetic-account-authorization",http.calls[1].form["token"])
        assertEquals("synthetic-refresh",result.getString("refreshToken"))
        assertEquals("456",result.getString("uid"))
        assertFalse(result.has("password"));assertFalse(result.has("phone"));assertFalse(result.has("token"))
        assertFalse(result.toString().contains("synthetic-account-authorization"))
    }
    @Test fun sendingSmsDoesNotLogInOrExchangeAuthorization() {
        val http=Fake("""{"code":0}""")
        TaggedLoginClient(http,device).sendSms("13800000000")
        assertEquals(1,http.calls.size)
        assertEquals("13800000000",http.calls.single().form["cellphone"])
        assertTrue(http.calls.single().url.endsWith("/sendPhoneCaptchaWithOutLogin"))
    }
    @Test fun smsLoginEncryptsCodeAndUsesSameExchange() {
        val http=Fake(account,community)
        val result=TaggedLoginClient(http,device).loginSms("13800000000","123456")
        assertTrue(http.calls[0].url.endsWith("/sms/new/login"))
        assertNotEquals("123456",http.calls[0].form["captcha"])
        assertFalse(result.has("captcha"));assertEquals("1289",result.getJSONArray("gameIds").getString(0))
    }
    @Test fun loginRejectionNeverExchangesOrLeaksServerPayload() {
        val http=Fake("""{"code":403,"message":"风控 raw-private-token"}""")
        try {TaggedLoginClient(http,device).loginPassword("13800000000","synthetic");fail("Expected rejection")}
        catch(e:SignError){assertTrue(e.message!!.contains("安全验证"));assertFalse(e.message!!.contains("raw-private-token"))}
        assertEquals(1,http.calls.size)
    }
    @Test fun incompleteCommunityResponseCannotBecomeAUsableAccount() {
        val http=Fake(account,"""{"code":0,"data":{"accessToken":"synthetic"}}""")
        try {TaggedLoginClient(http,device).loginPassword("13800000000","synthetic");fail("Expected incomplete response")}
        catch(e:SignError){assertEquals("protocol",e.kind)}
    }
    @Test fun incompleteAccountResponseDoesNotReachCommunityService() {
        val http=Fake("""{"code":0,"result":{"userId":123}}""")
        try {TaggedLoginClient(http,device).loginPassword("13800000000","synthetic");fail("Expected incomplete response")}
        catch(_:SignError){}
        assertEquals(1,http.calls.size)
    }
    @Test fun invalidInputsMakeNoNetworkCalls() {
        val http=Fake()
        try {TaggedLoginClient(http,device).loginPassword("not-a-phone","synthetic");fail("Expected validation")}
        catch(_:IllegalArgumentException){}
        assertTrue(http.calls.isEmpty())
    }
    @Test fun signatureIsIndependentOfInsertionOrder() {
        assertEquals(TaggedLoginClient.signed(linkedMapOf("b" to "2","a" to "1")),TaggedLoginClient.signed(linkedMapOf("a" to "1","b" to "2")))
    }
}
