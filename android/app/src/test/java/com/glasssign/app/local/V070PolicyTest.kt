package com.glasssign.app.local

import com.glasssign.app.data.*
import com.glasssign.app.ui.glassPolicy
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class V070PolicyTest {
    @Test fun reauthorizationStartsWithPreviousScopeAndAutomaticPreference() {
        val original=Account("fixture","miyoushe","main",false,"date",gameIds=listOf("genshin"),autoSignEnabled=false)
        val choices=listOf(RoleChoice("genshin|main|server","genshin","main"),RoleChoice("genshin|alt|server","genshin","alt"),RoleChoice("zzz|main|server","zzz","zzz"))
        val previous=listOf(GameBinding("fixture","genshin|main|server","genshin","main"))
        val retained=initialBindingSelection(original,previous,choices)
        assertEquals(setOf("genshin"),retained.games);assertEquals(setOf("genshin|main|server"),retained.roles);assertFalse(retained.autoSign)
        val new=initialBindingSelection(null,emptyList(),choices)
        assertEquals(setOf("genshin","zzz"),new.games);assertEquals(3,new.roles.size);assertTrue(new.autoSign)
    }
    @Test fun dedupeUsesStableIdentityNeverTokenOrAlias() {
        assertEquals("fixture-main",AccountIdentity.read("miyoushe",JSONObject().put("cookie","account_id_v2=fixture-main; cookie_token=synthetic")))
        assertEquals("",AccountIdentity.read("skland",JSONObject().put("cred","same-token").put("nickname","main")))
        assertEquals("",AccountIdentity.read("tagged",JSONObject().put("uid","0")))
        assertEquals("fixture-alt",AccountIdentity.read("kurobbs",JSONObject().put("userId","fixture-alt")))
    }
    @Test fun unknownAndAcceptedAreNotClaimedOfficiallyVerified() {
        val r=CheckRecord("fixture","game|fixture","fixture","uncertain","synthetic",1,"date")
        assertEquals("uncertain",present(r).group)
        assertEquals("未知",present(r).label)
        // v0.7.7: provenance is explicit, so the historical "接受" wording no longer decides the label.
        assertEquals("签到成功",present(r.copy(status="success",message="官方接口已接受签到")).label)
        assertEquals("已提交等待确认",present(r.copy(status="success",message="官方接口已接受签到",resultSource="accepted")).label)
        assertEquals("已签到",present(r.copy(status="already")).label)
        assertEquals("本机当天完成记录（沿用原证明，未重新查询官方）",present(r.copy(status="already",message="本机已有当天成功记录",resultSource="legacy_local")).source)
    }
    @Test fun blurAndMotionFallBackIndependentlyForRealCapabilities() {
        assertTrue(glassPolicy(35,false,false,false,false,true,"standard").blur)
        assertFalse(glassPolicy(30,false,false,false,false,true,"standard").blur)
        assertFalse(glassPolicy(35,true,false,false,false,true,"standard").blur)
        assertFalse(glassPolicy(35,false,true,false,false,true,"standard").blur)
        assertFalse(glassPolicy(35,false,false,true,false,true,"standard").blur)
        assertFalse(glassPolicy(35,false,false,false,false,true,"light").blur)
        assertFalse(glassPolicy(35,false,false,false,false,true,"off").blur)
        assertFalse(glassPolicy(35,false,false,false,true,true,"standard").motion)
        assertFalse(glassPolicy(35,false,false,false,false,false,"standard").motion)
    }
    @Test fun readOnlyMiyousheStatusNeverPostsEvenWhenUnconfirmed() {
        val h=ProviderTest.Fake{JSONObject("""{"retcode":0,"data":{"is_sign":false}}""")}
        val a=MiyousheAdapter(h);val c=JSONObject().put("cookie","synthetic")
        assertEquals("uncertain",a.queryStatus(c,SignTarget("zzz|fixture|server","fixture")){}.status)
        assertTrue(h.calls.all{it.method=="GET"})
    }
    @Test fun postAcceptedButVerificationNetworkFailureNeverBecomesRetryableNetworkError() {
        var calls=0
        val h=ProviderTest.Fake {
            calls++
            if(it.method=="POST")JSONObject("""{"retcode":0}""")
            else if(calls==1)JSONObject("""{"retcode":0,"data":{"is_sign":false}}""")
            else throw SignError("network","合成状态查询断网")
        }
        assertEquals("accepted",MiyousheAdapter(h).checkin(JSONObject().put("cookie","synthetic"),SignTarget("genshin|fixture|server","fixture")){}.status)
        assertEquals(1,h.calls.count{it.method=="POST"})
    }
    @Test fun legacyUnknownMessageMapsToUnknownWithoutChangingStoredEvidence() {
        val r=CheckRecord("fixture","fixture","fixture","needs_action","提交中断，结果不确定",1,"date")
        assertEquals("未知",present(r).label)
        assertEquals("needs_action",r.status)
    }
}
