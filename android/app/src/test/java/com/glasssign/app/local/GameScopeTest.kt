package com.glasssign.app.local

import com.glasssign.app.data.GameCatalog
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*

class GameScopeTest {
    @Test fun choosingZzzQueriesOnlyZzzWithoutMutatingOriginalSession() {
        val original=JSONObject("""{"cookie":"synthetic","games":["genshin","honkai_sr","zzz"]}""")
        val scoped=GameCatalog.scope("miyoushe","zzz",original)
        val queries=mutableListOf<String>()
        val fake=object:Transport {
            override fun call(method:String,url:String,headers:Map<String,String>,query:Map<String,String>,body:String?,form:Map<String,String>?):JSONObject {
                queries.add(query.getValue("game_biz"))
                return JSONObject("""{"retcode":0,"data":{"list":[{"game_uid":"fixture","region":"server","nickname":"synthetic"}]}}""")
            }
        }
        assertEquals(listOf("zzz|fixture|server"),MiyousheAdapter(fake).targets(scoped){}.map{it.id})
        assertEquals(listOf("nap_cn"),queries)
        assertEquals(3,original.getJSONArray("games").length())
    }
    @Test fun selectingGameScopesEachProtocolCorrectly() {
        assertEquals("endfield",GameCatalog.scope("skland","endfield",JSONObject()).getJSONArray("games").getString(0))
        assertEquals("1289",GameCatalog.scope("tagged","nte",JSONObject()).getJSONArray("gameIds").getString(0))
        assertEquals("3",GameCatalog.scope("kurobbs","wuwa",JSONObject()).getJSONArray("games").getString(0))
    }
    @Test(expected=IllegalArgumentException::class) fun mismatchedPlatformRejected() {
        GameCatalog.scope("miyoushe","nte",JSONObject())
    }
    @Test fun legacyMultiGameScopeIsPreserved() {
        val original=JSONObject("""{"games":["genshin","honkai_sr"]}""")
        assertEquals(original.toString(),GameCatalog.scope("miyoushe","",original).toString())
    }
}
