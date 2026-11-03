package com.glasssign.app.local

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * v0.7.7 P0 protocol/adapters contract (see docs/v077-p0-contract.md).
 *
 * Pins the two audit findings and the fix:
 *  1. `result(code == "0")` used to hand back an UNQUALIFIED `success`; it is now `success` + `accepted` +
 *     `submitted`, with `official`/`verifiedAt`/`verificationMethod` reserved for real confirmations.
 *  2. A rejected HTTP reply (the historical 402) is now attributable and carries a charset-checked business code,
 *     but never a body, token, query, role id or cookie.
 * Everything runs against the fake transport: no socket, no credential, no device, no build system.
 */
class SignContractTest {

    private class Call(val method:String,val url:String,val headers:Map<String,String>,val query:Map<String,String>,val body:String?,val form:Map<String,String>?)
    private class Fake(val responder:(Call)->JSONObject):Transport {
        val calls=mutableListOf<Call>()
        override fun call(method:String,url:String,headers:Map<String,String>,query:Map<String,String>,body:String?,form:Map<String,String>?):JSONObject {
            val c=Call(method,url,headers,query,body,form);calls.add(c);return responder(c)
        }
    }

    private val taggedCreds="""{"_accessToken":"fixture-access","refreshToken":"fixture-refresh","deviceId":"fixture-device","gameIds":["1289"],"communityCheckin":false}"""
    private val role=SignTarget("game|1289|50000001","异环 · 合成角色")

    // ---- the acceptance / confirmation split -------------------------------------------------------

    @Test fun aBareOkCodeIsAcceptedNotVerified() {
        val accepted=result("0")
        assertEquals("accepted",accepted.status)
        assertEquals(ResultSource.ACCEPTED,accepted.resultSource)
        assertEquals(VerifyMethod.SUBMITTED,accepted.verificationMethod)
        assertEquals("accepted is not a confirmation, so it has no verification time","",accepted.verifiedAt)
        assertFalse(accepted.retryable)
        assertFalse("only official declarations may upgrade a stored row",accepted.isOfficial)
    }

    @Test fun anOfficialAlreadyCodeCarriesMethodAndTime() {
        val already=result("1511",ok="200",already=setOf("1511"))
        assertEquals("already",already.status)
        assertEquals(ResultSource.OFFICIAL,already.resultSource)
        assertEquals(VerifyMethod.PROVIDER_STATUS,already.verificationMethod)
        assertTrue(already.verifiedAt.isNotBlank())
        assertEquals("1511",already.providerCode)
    }

    @Test fun authCaptchaAndUnknownCodesNeverClaimSuccess() {
        assertEquals("needs_action",result("220",auth=setOf("220")).status)
        assertEquals("needs_action",result("403",captcha=setOf("403")).status)
        for(code in listOf("","null","unexpected","-999")) assertEquals("failed",result(code).status)
        assertNotEquals(ResultSource.OFFICIAL,result("220",auth=setOf("220")).resultSource)
    }

    @Test fun aProviderCodeIsACharsetCheckedToken() {
        assertEquals("0",safeCode("0"))
        assertEquals("-5003",safeCode("-5003"))
        assertEquals("unknown",safeCode(null))
        assertEquals("unknown",safeCode(""))
        assertEquals("unknown",safeCode("code 402 from body"))
        assertEquals("unknown",safeCode("x".repeat(17)))
    }

    // ---- SignError -> SignResult (the engine's only exception path) -------------------------------

    @Test fun signErrorKeepsItsKindAndNeverBecomesRetryableOrOfficial() {
        val error=SignError("auth","授权失效，请重新导入")
        val r=error.asResult()
        assertEquals("needs_action",r.status)
        assertEquals("the error KIND travels in providerCode so the engine can gate account state","auth",r.providerCode)
        assertEquals(ResultSource.UNKNOWN,r.resultSource)
        assertFalse(r.retryable)
        assertFalse(r.isOfficial)
        assertEquals("",r.verificationMethod)
        assertEquals("",r.verifiedAt)
    }

    @Test fun everyErrorKindMapsToItsDocumentedWireStatus() {
        assertEquals("needs_action",SignError("auth","x").status)
        assertEquals("needs_action",SignError("captcha","x").status)
        assertEquals("needs_action",SignError("config","x").status)
        assertEquals("uncertain",SignError("uncertain","x").status)
        assertEquals("waiting_network",SignError("network","x").status)
        assertEquals("failed",SignError("protocol","x").status)
        assertEquals("failed",SignError("rate_limit","x").status)
    }

    // ---- HTTP rejection classification ------------------------------------------------------------

    @Test fun http401IsARejectionAndHttp402IsNotAnAuthorizationFailure() {
        val unauthorized=classifyStatus("GET","bbs-api.tajiduo.com",401,"/apihub/awapi/signin/state")
        assertEquals("auth",unauthorized.kind)
        assertEquals("needs_action",unauthorized.status)
        assertEquals(401,unauthorized.httpStatus)

        val rejected=classifyStatus("POST","bbs-api.tajiduo.com",402,"/apihub/awapi/sign")
        assertEquals("an unexplained provider rejection is not an authorization failure","protocol",rejected.kind)
        assertEquals("failed",rejected.status)
        assertEquals(402,rejected.httpStatus)
        val text=rejected.message.orEmpty()
        assertTrue("the rejection is attributed",text.contains("POST bbs-api.tajiduo.com/apihub/awapi/sign"))
        assertTrue("the status survives",text.contains("HTTP 402"))
        assertTrue("the cause is stated as unknown",text.contains("来源未知"))
        assertTrue("it is not a login prompt",text.contains("不代表授权失效"))
        assertFalse("no authorization wording",text.contains("请重新导入"))
    }

    @Test fun http402ExplainsItselfWithoutGuessingAPaymentRootCause() {
        val text=classifyStatus("POST","bbs-api.tajiduo.com",402).message.orEmpty()
        assertFalse("never claims the cause is payment",text.contains("需要付费")||text.contains("余额不足"))
        assertTrue("says the meaning is not observable",text.contains("未知")||text.contains("核对"))
    }

    @Test fun statusesKeepTheirExistingMeaning() {
        assertEquals("protocol",classifyStatus("GET","h",302).kind)
        assertEquals("captcha",classifyStatus("GET","h",403).kind)
        assertEquals("captcha",classifyStatus("GET","h",412).kind)
        assertEquals("network",classifyStatus("GET","h",503).kind)
        assertEquals("waiting_network",classifyStatus("GET","h",503).status)
        assertEquals("uncertain",classifyStatus("POST","h",503).kind)
        assertEquals("uncertain",classifyStatus("POST","h",500).status)
        assertEquals("protocol",classifyStatus("GET","h",418).kind)
        assertEquals("failed",classifyStatus("GET","h",418).status)
    }

    @Test fun aBusinessCodeSurvivesIntoTheErrorButNeverTheBody() {
        val error=classifyStatus("POST","bbs-api.tajiduo.com",402,"/apihub/awapi/sign","40001")
        assertTrue("the provider's own code is preserved",error.message.orEmpty().contains("40001"))
        assertEquals(402,error.httpStatus)
        assertEquals("POST",error.method)
        assertEquals("bbs-api.tajiduo.com",error.host)
        assertEquals("/apihub/awapi/sign",error.endpoint)
        assertEquals("POST bbs-api.tajiduo.com/apihub/awapi/sign · HTTP 402",error.rejection()?.describe())
    }

    @Test fun anEndpointIsAPathOnlyAndCannotCarryCredentialMaterial() {
        assertEquals("/apihub/awapi/sign",safeEndpoint("/apihub/awapi/sign?gameId=1289&roleId=50000001"))
        assertEquals("/apihub/awapi/sign",safeEndpoint("/apihub/awapi/sign#frag"))
        assertEquals("",safeEndpoint(""))
        assertEquals("",safeEndpoint(null))
        assertEquals("no absolute URLs", "", safeEndpoint("https://bbs-api.tajiduo.com/x"))
        assertEquals("no path traversal or escaped content","",safeEndpoint("/a/..%2F..%2Fetc"))
        assertEquals("no user material","",safeEndpoint("/user/token=abc/state"))
        assertEquals("",safeEndpoint("/"+ "a".repeat(120)))
    }

    // ---- bounded 402 body reading (JSON / HTML / malformed) ---------------------------------------

    @Test fun aJsonRejectionBodyYieldsItsBusinessCode() {
        assertEquals("402",RejectionCode.fromBody("""{"code":402}"""))
        assertEquals("-100",RejectionCode.fromBody("""{"retcode":"-100","msg":"x"}"""))
        assertEquals("40001",RejectionCode.fromBody("""{"errno":40001}"""))
        assertEquals("500",RejectionCode.fromBody("""{"error":{"code":500}}"""))
    }

    @Test fun anHtmlOrMalformedRejectionBodyYieldsNothing() {
        val html="<html><head><title>402 Payment Required</title></head><body><h1>Blocked</h1><p>token=secret</p></body></html>"
        assertEquals("a page's prose is not a business code","",RejectionCode.fromBody(html))
        assertEquals("non_json",RejectionCode.kindOf(html))
        assertEquals("",RejectionCode.fromBody("{not json"))
        assertEquals("",RejectionCode.fromBody(""))
        assertEquals("",RejectionCode.fromBody(null))
        assertEquals("",RejectionCode.fromBody("""{"msg":"no code here"}"""))
        assertEquals("no_code_field",RejectionCode.kindOf("""{"msg":"no code here"}"""))
        assertEquals("a tagged code element is honoured","402",RejectionCode.fromBody("<code>402</code>"))
    }

    @Test fun anOversizedRejectionBodyIsNotInspected() {
        val huge="""{"code":402,"pad":"""" + "x".repeat(RejectionCode.MAX_REJECTION_BODY + 1) + """"}"""
        assertEquals("",RejectionCode.fromBody(huge))
    }

    // ---- adapters: acceptance vs confirmation ------------------------------------------------------

    /** state(first) -> post -> state(verify), each with an independent code/data payload. */
    private fun taggedTransport(
        before:String,
        after:String?=null,
        signReply:String="""{"code":0}""",
        roles:String="""{"code":0,"data":{"roles":[{"roleId":"50000001"},{"roleId":"50000002"}]}}""",
    )=Fake{call->
        when{
            call.url.endsWith("getGameRoles")->JSONObject(roles)
            call.url.endsWith("signin/state")->{
                val reply=if(seenState) after ?: before else before
                seenState=true
                JSONObject(reply)
            }
            call.url.endsWith("/awapi/sign")->JSONObject(signReply)
            else->error("unexpected ${call.method} ${call.url}")
        }
    }
    private var seenState=false

    @Test fun taggedDoubleRoleCannotBeProvenByAGameLevelStateFlag() {
        seenState=false
        // Role A is signed; the target is role B of the same game. `todaySign` cannot tell them apart.
        val transport=taggedTransport(before="""{"code":0,"data":{"todaySign":true}}""")
        val outcome=TaggedAdapter(transport).checkin(JSONObject(taggedCreds),SignTarget("game|1289|50000002","异环 · 乙")){}
        assertEquals("uncertain",outcome.status)
        assertEquals(ResultSource.UNKNOWN,outcome.resultSource)
        assertEquals(0,transport.calls.count{it.url.endsWith("/awapi/sign")})
    }

    @Test fun taggedUnattributableSignedStateStopsBeforeSubmission() {
        seenState=false
        val transport=taggedTransport(before="""{"code":0,"data":{"todaySign":true}}""",after="""{"code":0,"data":{"todaySign":true}}""")
        val outcome=TaggedAdapter(transport).checkin(JSONObject(taggedCreds),role){}
        assertEquals("uncertain",outcome.status)
        assertEquals(2,transport.calls.size)
        assertEquals(0,transport.calls.count{it.url.endsWith("/awapi/sign")})
    }

    @Test fun taggedSingleAuthorizedRoleMayBeProvenAndGetsAVerificationTime() {
        seenState=false
        val single="""{"code":0,"data":{"roles":[{"roleId":"50000001"}]}}"""
        val before="""{"code":0,"data":{"todaySign":false}}"""
        val after="""{"code":0,"data":{"todaySign":true}}"""
        val transport=taggedTransport(before=before,after=after,roles=single)
        val outcome=TaggedAdapter(transport).checkin(JSONObject(taggedCreds),role){}
        assertEquals("success",outcome.status)
        assertEquals(ResultSource.OFFICIAL,outcome.resultSource)
        assertEquals(VerifyMethod.STATE,outcome.verificationMethod)
        assertTrue(outcome.verifiedAt.isNotBlank())
        assertEquals(1,transport.calls.count{it.url.endsWith("/awapi/sign")})
    }

    @Test fun taggedSingleRolePreCheckAvoidsAnIdenticalResubmission() {
        seenState=false
        val single="""{"code":0,"data":{"roles":[{"roleId":"50000001"}]}}"""
        val transport=taggedTransport(before="""{"code":0,"data":{"todaySign":true}}""",roles=single)
        val outcome=TaggedAdapter(transport).checkin(JSONObject(taggedCreds),role){}
        assertEquals("already",outcome.status)
        assertEquals(ResultSource.OFFICIAL,outcome.resultSource)
        assertEquals(VerifyMethod.STATE,outcome.verificationMethod)
        assertEquals("a provable already-signed role is never resubmitted",0,transport.calls.count{it.url.endsWith("/awapi/sign")})
    }

    @Test fun taggedStateNeverProvesASingleRoleWhenTheAccountOwnsTwo() {
        seenState=false
        val transport=taggedTransport(before="""{"code":0,"data":{"todaySign":true}}""")
        val outcome=TaggedAdapter(transport).checkin(JSONObject(taggedCreds),role){}
        assertEquals("uncertain",outcome.status)
        assertNotEquals(ResultSource.OFFICIAL,outcome.resultSource)
    }

    @Test fun taggedResponseNamingAnotherRoleStopsBeforeSubmitting() {
        seenState=false
        val transport=taggedTransport(before="""{"code":0,"data":{"todaySign":false,"roleId":"50000009"}}""")
        val outcome=TaggedAdapter(transport).checkin(JSONObject(taggedCreds),role){}
        assertEquals("uncertain",outcome.status)
        assertTrue(outcome.message.contains("其他角色"))
        assertEquals("no submission may be attributed to the wrong role",0,transport.calls.count{it.url.endsWith("/awapi/sign")})
    }

    @Test fun taggedVerificationNeverIncreasesTheRequestCount() {
        seenState=false
        val transport=taggedTransport(before="""{"code":0,"data":{"todaySign":false}}""",after="""{"code":0,"data":{"todaySign":false}}""")
        TaggedAdapter(transport).checkin(JSONObject(taggedCreds),role){}
        assertEquals("one submission only, never a second attempt",1,transport.calls.count{it.url.endsWith("/awapi/sign")})
        assertEquals("role lookup + pre-check + sign + one verify",4,transport.calls.size)
        assertEquals(2,transport.calls.count{it.url.endsWith("signin/state")})
    }

    @Test fun taggedVerificationFailureKeepsTheAcceptedSubmission() {
        seenState=false
        var states=0
        val transport=Fake{call->
            when{
                call.url.endsWith("getGameRoles")->JSONObject("""{"code":0,"data":{"roles":[{"roleId":"50000001"}]}}""")
                call.url.endsWith("signin/state")->{
                    states++
                    if(states==1)JSONObject("""{"code":0,"data":{"todaySign":false}}""") else throw SignError("network","合成断网")
                }
                else->JSONObject("""{"code":0}""")
            }
        }
        val outcome=TaggedAdapter(transport).checkin(JSONObject(taggedCreds),role){}
        assertEquals("a failed post-check must not demote the accepted submission",ResultSource.ACCEPTED,outcome.resultSource)
        assertNotEquals(ResultSource.OFFICIAL,outcome.resultSource)
        assertEquals(1,transport.calls.count{it.url.endsWith("/awapi/sign")})
        assertEquals("no extra probing after the failure",2,states)
    }

    @Test fun taggedCommunityAcceptanceStaysAcceptedWithoutAMadeUpApi() {
        val transport=Fake{call->when{
            call.url.endsWith("/apihub/api/signin")->JSONObject("""{"code":0}""")
            else->error("community check-in must not call ${call.url}")
        }}
        val outcome=TaggedAdapter(transport).checkin(JSONObject(taggedCreds),SignTarget("community|1","塔吉多 · 社区签到")){}
        assertEquals("accepted",outcome.status)
        assertEquals(ResultSource.ACCEPTED,outcome.resultSource)
        assertEquals(VerifyMethod.SUBMITTED,outcome.verificationMethod)
        assertTrue("no invented status endpoint",transport.calls.all{it.url.endsWith("/apihub/api/signin")})
    }

    @Test fun taggedBusiness402IsNotAnAuthorizationFailure() {
        val transport=Fake{call->when{
            call.url.endsWith("getGameRoles")->JSONObject("""{"code":402}""")
            else->error("no further request")
        }}
        val error=try{TaggedAdapter(transport).targets(JSONObject(taggedCreds)){};null}catch(e:SignError){e}
        assertNotNull(error)
        assertEquals("an unexplained business 402 must not force a re-login","protocol",error?.kind)
        assertEquals("failed",error?.status)
        assertTrue(error?.message.orEmpty().contains("402"))
    }

    @Test fun taggedHttp401StillMeansReauthorize() {
        val transport=Fake{throw classifyStatus("GET","bbs-api.tajiduo.com",401,"/usercenter/api/v2/getGameRoles")}
        val error=try{TaggedAdapter(transport).targets(JSONObject(taggedCreds)){};null}catch(e:SignError){e}
        assertEquals("auth",error?.kind)
        assertEquals("needs_action",error?.status)
    }

    @Test fun kuroAcceptanceIsNotAVerifiedSuccess() {
        val transport=Fake{call->when{
            call.url.endsWith("signIn/v2")->JSONObject("""{"code":200}""")
            call.url.endsWith("user/signIn")->JSONObject("""{"code":200}""")
            else->error("unexpected ${call.url}")
        }}
        val c=JSONObject("""{"token":"fixture","userId":"7","gameIds":["3"]}""")
        val game=KuroBBSAdapter(transport).checkin(c,SignTarget("game|3|100","鸣潮 · 角色","70000001")){}
        assertEquals("accepted",game.status)
        assertEquals("no proof endpoint exists, so it stays accepted",ResultSource.ACCEPTED,game.resultSource)
        assertEquals(VerifyMethod.SUBMITTED,game.verificationMethod)
        val community=KuroBBSAdapter(transport).checkin(c,SignTarget("community|2","库街区 · 社区签到")){}
        assertEquals(ResultSource.ACCEPTED,community.resultSource)
        assertEquals("",community.verifiedAt)
    }

    @Test fun kuroExplicitAuthFailureStaysNeedsAction() {
        val transport=Fake{JSONObject("""{"code":220}""")}
        val outcome=KuroBBSAdapter(transport).checkin(JSONObject("""{"token":"fixture","userId":"7"}"""),SignTarget("game|3|100","鸣潮 · 角色","70000001")){}
        assertEquals("needs_action",outcome.status)
        assertEquals("220",outcome.providerCode)
    }

    @Test fun miyousheAcceptanceWithoutConfirmedStateIsNotSuccess() {
        val transport=Fake{call->
            when{
                call.method=="GET"->JSONObject("""{"retcode":0,"data":{"is_sign":false}}""")
                else->JSONObject("""{"retcode":0}""")
            }
        }
        val outcome=MiyousheAdapter(transport).checkin(JSONObject("""{"cookie":"fixture-cookie"}"""),SignTarget("genshin|1|cn_gf01","原神 · 角色")){}
        assertEquals("accepted",outcome.status)
        assertEquals(ResultSource.ACCEPTED,outcome.resultSource)
        assertEquals("one submission only",1,transport.calls.count{it.method=="POST"})
        assertEquals("one bounded verification read",2,transport.calls.count{it.method=="GET"})
    }

    @Test fun miyousheVerifiedPostcheckIsOfficialWithItsMethodAndTime() {
        var posts=0
        val transport=Fake{call->
            when{
                call.method=="POST"->{posts++;JSONObject("""{"retcode":0}""")}
                posts==0->JSONObject("""{"retcode":0,"data":{"is_sign":false}}""")
                else->JSONObject("""{"retcode":0,"data":{"is_sign":true}}""")
            }
        }
        val outcome=MiyousheAdapter(transport).checkin(JSONObject("""{"cookie":"fixture-cookie"}"""),SignTarget("genshin|1|cn_gf01","原神 · 角色")){}
        assertEquals("success",outcome.status)
        assertEquals(ResultSource.OFFICIAL,outcome.resultSource)
        assertEquals(VerifyMethod.INFO,outcome.verificationMethod)
        assertTrue(outcome.verifiedAt.isNotBlank())
    }

    @Test fun miyousheReadStateWithNonBooleanSignIsAProtocolStop() {
        val transport=Fake{JSONObject("""{"retcode":0,"data":{"is_sign":"true"}}""")}
        val error=try{MiyousheAdapter(transport).checkin(JSONObject("""{"cookie":"fixture-cookie"}"""),SignTarget("genshin|1|cn_gf01","原神 · 角色")){};null}catch(e:SignError){e}
        assertEquals("a string is not the official boolean","protocol",error?.kind)
        assertEquals("nothing was submitted",1,transport.calls.size)
    }

    @Test fun adapterSignErrorIsAttributableWithoutEchoingMaterial() {
        val transport=Fake{throw classifyStatus("POST","api-takumi.mihoyo.com",402,"/event/luna/sign","7")}
        val error=try{MiyousheAdapter(transport).checkin(JSONObject("""{"cookie":"fixture-cookie"}"""),SignTarget("genshin|1|cn_gf01","原神 · 角色")){};null}catch(e:SignError){e}
        assertNotNull(error)
        assertEquals(402,error?.httpStatus)
        assertEquals("/event/luna/sign",error?.endpoint)
        val text=error?.message.orEmpty()
        assertFalse("no cookie material",text.contains("fixture-cookie"))
        assertFalse("no query material",text.contains("?"))
        assertFalse("no raw body",text.contains("body"))
    }

    @Test fun aSyntheticTransportExplainsNothingAndStaysSafe() {
        val transport=Fake{JSONObject("{}")}
        assertEquals("",rejectionDetail(transport))
    }
}
