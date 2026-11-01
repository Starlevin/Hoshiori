package com.glasssign.app.local

import com.glasssign.app.data.GameCatalog
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*

class MultiGameProtocolTest {
    @Test fun selectedSubsetUsesOneCredentialAndOnlySelectedGameQueries() {
        val original=JSONObject("""{"cookie":"synthetic","games":["genshin","zzz","honkai3"]}""")
        val selected=GameCatalog.scope("miyoushe",listOf("genshin","honkai3"),original)
        val h=ProviderTest.Fake { JSONObject("""{"retcode":0,"data":{"list":[]}}""") }
        MiyousheAdapter(h).targets(selected){}
        assertEquals(listOf("hk4e_cn","bh3_cn"),h.calls.map{it.query["game_biz"]})
        assertEquals(3,original.getJSONArray("games").length())
        assertEquals("synthetic",selected.getString("cookie"))
    }
    @Test fun honkai3AlreadySignedStopsBeforePostWithCorrectActivity() {
        val h=ProviderTest.Fake { JSONObject("""{"retcode":0,"data":{"is_sign":true}}""") }
        val result=MiyousheAdapter(h).checkin(JSONObject().put("cookie","synthetic"),SignTarget("honkai3|fixture|server","合成崩坏3")){}
        assertEquals("already",result.status)
        assertEquals(1,h.calls.size)
        assertEquals("GET",h.calls.single().method)
        assertEquals("https://api-takumi.mihoyo.com/event/luna/info",h.calls.single().url)
        assertEquals("e202306201626331",h.calls.single().query["act_id"])
        assertFalse(h.calls.single().headers.containsKey("x-rpc-signgame"))
    }
    @Test fun legacyImplicitGamesDoNotOptInToNewHonkai3() {
        assertEquals(listOf("genshin","honkai_sr","zzz"),GameCatalog.inferLegacy("miyoushe",JSONObject()))
        assertEquals(listOf("wuwa"),GameCatalog.inferLegacy("kurobbs",JSONObject("""{"games":["3"]}""")))
        assertEquals("nte",GameCatalog.gameForTarget("tagged","game|1289|fixture"))
        assertNull(GameCatalog.gameForTarget("tagged","community|1"))
    }
    @Test(expected=IllegalArgumentException::class) fun crossEcosystemSelectionRejected() {
        GameCatalog.scope("miyoushe",listOf("genshin","nte"),JSONObject())
    }
}
