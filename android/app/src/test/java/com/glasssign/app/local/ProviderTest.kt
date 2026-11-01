package com.glasssign.app.local

import org.junit.Test
import org.junit.Assert.*
import org.json.JSONObject
import java.time.ZonedDateTime

class ProviderTest {
    data class Call(val method:String,val url:String,val headers:Map<String,String>,val query:Map<String,String>,val body:String?,val form:Map<String,String>?)
    class Fake(val responder:(Call)->JSONObject):Transport {
        val calls=mutableListOf<Call>()
        override fun call(method:String,url:String,headers:Map<String,String>,query:Map<String,String>,body:String?,form:Map<String,String>?):JSONObject {
            val c=Call(method,url,headers,query,body,form);calls.add(c);return responder(c)
        }
    }
    fun fixture(name:String)=JSONObject(javaClass.getResource("/$name")!!.readText())
    @Test fun signaturesMatchPythonGoldenVectors() {
        assertEquals("1700000000,abc123,0469c15c8dda31d69eb62a61935c2c26",Signing.mys("1700000000","abc123"))
        assertEquals("1700000000,AbCd1234,ca1364f880b348d3723e1fd54728fae5",Signing.tagged("1700000000","AbCd1234"))
        assertEquals("8b694e415230f476920cc76a00edcb19",Signing.skland("/api/v1/game/player/binding","","1700000000","""{"platform":"1","timestamp":"1700000000","dId":"device-1","vName":"1.5.1"}""","sign-token"))
    }
    @Test fun miyousheRolesDedupeAndAlready() {
        val http=Fake { if(it.url.endsWith("/info"))JSONObject("""{"retcode":0,"data":{"is_sign":false}}""") else if(it.url.endsWith("/sign"))fixture("miyoushe_sign_already.json") else fixture("miyoushe_roles_multi.json") }
        val a=MiyousheAdapter(http);val c=JSONObject("""{"cookie":"fixture-cookie","games":["genshin"]}""")
        val targets=a.targets(c){};assertEquals(2,targets.size);assertEquals("genshin|100000001|cn_gf01",targets[0].id)
        assertEquals("already",a.checkin(c,targets[0]){}.status)
        assertTrue(http.calls.first().headers.getValue("DS").matches(Regex("[0-9]+,[a-z0-9]{6},[a-f0-9]{32}")))
    }
    @Test fun miyousheCookieRotationPersistedBeforeNextRequestFails() {
        var saved="";var requests=0
        val http=Fake { if(it.url.contains("getCookieAccountInfoBySToken"))JSONObject("""{"retcode":0,"data":{"cookie_token":"rotated-cookie"}}""") else if(requests++==0)JSONObject("""{"retcode":-100}""") else throw SignError("network","测试断网") }
        val c=JSONObject("""{"cookie":"stuid=1; stoken=fixture-token","games":["genshin"]}""")
        try {MiyousheAdapter(http).targets(c){saved=c.getString("cookie")};fail()}catch(_:SignError){}
        assertTrue(saved.contains("cookie_token=rotated-cookie"));assertTrue(http.calls[1].headers.getValue("Cookie").contains("stoken=fixture-token"))
    }
    @Test fun miyousheDoesNotClaimSuccessWithoutFinalConfirmation() {
        val h=Fake{if(it.url.endsWith("/sign"))JSONObject("""{"retcode":0}""") else JSONObject("""{"retcode":0,"data":{"is_sign":false}}""")}
        assertEquals("needs_action",MiyousheAdapter(h).checkin(JSONObject("""{"cookie":"fixture-cookie"}"""),SignTarget("genshin|1|cn_gf01","test")){}.status)
    }
    @Test fun miyousheCaptchaStopsBeforeSign() {
        val h=Fake{JSONObject("""{"retcode":1034}""")}
        try {MiyousheAdapter(h).checkin(JSONObject("""{"cookie":"fixture-cookie"}"""),SignTarget("genshin|1|cn_gf01","test")){};fail()}catch(e:SignError){assertEquals("captcha",e.kind)}
        assertEquals(1,h.calls.size)
    }
    @Test fun sklandAllGamesAndOverloadedAlreadyCode() {
        val h=Fake { if(it.url.endsWith("/binding"))fixture("skland_binding_multi.json") else fixture("skland_already.json") }
        val a=SklandAdapter(h);val c=JSONObject("""{"cred":"fixture-c","signToken":"fixture-s","dId":"fixture-d"}""")
        val ts=a.targets(c){};assertEquals(5,ts.size);assertTrue(ts.any{it.id=="endfield|30000002|40000002"})
        assertEquals("already",a.checkin(c,SignTarget("island|3","test")){}.status)
        val uncertain=SklandAdapter(Fake{JSONObject("""{"code":10001,"message":"device check"}""")})
        assertEquals("needs_action",uncertain.checkin(c,SignTarget("island|3","test")){}.status)
    }
    @Test fun sklandEndfieldHeaderAndSignedBody() {
        val h=Fake {JSONObject("""{"code":0}""")};val c=JSONObject("""{"cred":"fixture-c","signToken":"fixture-s","dId":"fixture-d"}""")
        SklandAdapter(h).checkin(c,SignTarget("endfield|30|40","test")){}
        val call=h.calls.single();assertEquals("3_30_40",call.headers["sk-game-role"]);assertEquals("3",call.headers["platform"])
        val time=call.headers.getValue("timestamp")
        val context="""{"platform":"3","timestamp":"$time","dId":"fixture-d","vName":"1.0.0"}"""
        assertEquals(Signing.skland("/api/v1/game/endfield/attendance","",time,context,"fixture-s"),call.headers["sign"])
    }
    @Test fun taggedRefreshAndAlreadyAvoidsPost() {
        val h=Fake {when {it.url.endsWith("refreshToken")->JSONObject("""{"code":0,"data":{"accessToken":"fixture-a","refreshToken":"fixture-rotated","uid":"9"}}""");it.url.endsWith("getGameRoles")->fixture("tagged_roles.json");it.url.endsWith("signin/state")->fixture("tagged_state_signed.json");else->error("Unexpected request")}}
        val c=JSONObject("""{"refreshToken":"fixture-old","deviceId":"fixture-d","communityCheckin":false}""");val a=TaggedAdapter(h);var saved=false
        val ts=a.targets(c){saved=true};assertTrue(saved);assertEquals("fixture-rotated",c.getString("refreshToken"));assertEquals(2,ts.size)
        assertEquals("already",a.checkin(c,ts[0]){}.status);assertFalse(h.calls.any{it.url.endsWith("/awapi/sign")})
    }
    @Test fun taggedRotationSurvivesFollowingNetworkFailure() {
        val c=JSONObject("""{"refreshToken":"fixture-old","deviceId":"fixture-d"}""");var saved=""
        val h=Fake {if(it.url.endsWith("refreshToken"))JSONObject("""{"code":0,"data":{"accessToken":"fixture-a","refreshToken":"fixture-new"}}""")else throw SignError("network","测试断网")}
        try {TaggedAdapter(h).targets(c){saved=c.getString("refreshToken")};fail()}catch(_:SignError){}
        assertEquals("fixture-new",saved)
    }
    @Test fun kuroPassesActualServerIdAndDetectsExpiredAuth() {
        val h=Fake { when {it.url.endsWith("mineV2")->JSONObject("""{"code":200,"data":{"mine":{"userId":"777"}}}""");it.url.endsWith("findRoleList")->fixture("kuro_role_list.json");else->fixture("kuro_auth_expired.json")}}
        val c=JSONObject("""{"token":"fixture-t","games":["3"],"communityCheckin":false}""");val a=KuroBBSAdapter(h)
        val ts=a.targets(c){};assertEquals(2,ts.size);assertEquals("70000001",ts[0].serverId)
        assertEquals("needs_action",a.checkin(c,ts[0]){}.status);assertEquals("70000001",h.calls.last().form!!["serverId"])
    }
    @Test fun missingCodesAndUnknownCodesAreNotSuccess() {
        for(code in listOf("","null","unexpected","-999"))assertEquals("failed",result(code).status)
        assertEquals("needs_action",result("401",auth=setOf("401")).status)
        assertEquals("waiting_network",SignError("network","offline").status)
    }
    @Test fun officialTransportRejectsForeignHostsBeforeNetwork() {
        for(url in listOf("http://api.kurobbs.com/x","https://example.com/x","https://api.kurobbs.com:8443/x","https://user:pw@api.kurobbs.com/x")) {
            try{OfficialTransport().call("GET",url);fail(url)}catch(e:SignError){assertEquals("protocol",e.kind)}
        }
    }
    @Test fun scheduleUsesBeijingDateAndRecoversMissedTime() {
        val now=ZonedDateTime.parse("2026-11-01T01:00:00Z")
        assertTrue(SchedulePolicy.due(now,true,"08:10","2026-10-31"))
        assertFalse(SchedulePolicy.due(now,true,"08:10","2026-11-01"))
        assertFalse(SchedulePolicy.due(now,false,"08:10",""))
        assertFalse(SchedulePolicy.due(ZonedDateTime.parse("2026-10-31T23:59:00Z"),true,"08:10",""))
    }
}
