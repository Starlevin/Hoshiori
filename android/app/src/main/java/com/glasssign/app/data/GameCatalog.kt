package com.glasssign.app.data

import org.json.JSONArray
import org.json.JSONObject

data class GameChoice(val id:String,val title:String,val provider:String,val protocolId:String=id)

/** A user binds one game; the community remains an internal transport detail. */
object GameCatalog {
    val all=listOf(
        GameChoice("genshin","原神","miyoushe"),
        GameChoice("honkai_sr","崩坏：星穹铁道","miyoushe"),
        GameChoice("zzz","绝区零","miyoushe"),
        GameChoice("honkai3","崩坏3","miyoushe"),
        GameChoice("arknights","明日方舟","skland"),
        GameChoice("endfield","终末地","skland"),
        GameChoice("nte","异环","tagged","1289"),
        GameChoice("wuwa","鸣潮","kurobbs","3"),
        GameChoice("pgr","战双帕弥什","kurobbs","2"),
        // 2026-11-08：插件平台。需先在「设置 → 插件」安装并验签领签，签到在星栞本机离线模拟执行。
        GameChoice("lingqian_tasks","领签任务","lingqian","daily"),
    )
    fun find(id:String):GameChoice?=all.firstOrNull{it.id==id}
    fun firstFor(provider:String):GameChoice=all.first{it.provider==provider}
    fun idsFor(provider:String)=all.filter{it.provider==provider}.map{it.id}
    fun inferLegacy(provider:String,source:JSONObject):List<String> {
        val key=if(provider=="tagged")"gameIds" else "games"
        val array=source.optJSONArray(key)
        if(array==null)return idsFor(provider).filter{it!="honkai3"}
        val values=(0 until array.length()).map{array.optString(it)}.toSet()
        return all.filter{it.provider==provider && it.protocolId in values}.map{it.id}
    }
    fun gameForTarget(provider:String,target:String):String? {
        val parts=target.split('|')
        // 插件平台只有一个模拟任务，target 形如 `task|daily`。
        if(provider=="lingqian")return find("lingqian_tasks")?.takeIf{it.provider==provider}?.id
        return when(provider) {
            "miyoushe","skland" -> find(parts.first())?.takeIf{it.provider==provider}?.id
            "tagged","kurobbs" -> if(parts.size>=3&&parts[0]=="game")all.firstOrNull{it.provider==provider&&it.protocolId==parts[1]}?.id else null
            else -> null
        }
    }
    fun scope(provider:String,gameIds:List<String>,source:JSONObject):JSONObject {
        if(gameIds.isEmpty())return JSONObject(source.toString())
        val selected=gameIds.distinct().map{requireNotNull(find(it)){"请选择支持的游戏"}}
        require(selected.all{it.provider==provider}){"游戏与登录平台不匹配"}
        val copy=JSONObject(source.toString())
        copy.put(if(provider=="tagged")"gameIds" else "games",JSONArray(selected.map{it.protocolId}))
        if(provider=="tagged")copy.put("gameId",selected.first().protocolId)
        return copy
    }
    fun scope(provider:String,gameId:String,source:JSONObject):JSONObject {
        val copy=JSONObject(source.toString())
        if(gameId.isEmpty())return copy // Legacy multi-game accounts retain their scope.
        val game=requireNotNull(find(gameId)){"请选择支持的游戏"}
        require(game.provider==provider){"游戏与登录平台不匹配"}
        val key=if(provider=="tagged")"gameIds" else "games"
        copy.put(key,JSONArray().put(game.protocolId))
        if(provider=="tagged")copy.put("gameId",game.protocolId)
        return copy
    }
}
