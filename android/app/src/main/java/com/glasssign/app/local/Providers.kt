package com.glasssign.app.local

import org.json.JSONObject
import java.time.ZonedDateTime
import java.util.UUID

class MiyousheAdapter(private val http: Transport) : ProviderAdapter {
    private data class Game(val name:String,val biz:String,val act:String,val base:String,val path:String,val signgame:String)
    private val base = "https://api-takumi.mihoyo.com"
    private val games = linkedMapOf(
        "genshin" to Game("原神","hk4e_cn","e202311201442471",base,"/event/luna","hk4e"),
        "honkai_sr" to Game("星穹铁道","hkrpg_cn","e202304121516551",base,"/event/luna",""),
        "zzz" to Game("绝区零","nap_cn","e202406242138391","https://act-nap-api.mihoyo.com","/event/luna/zzz","zzz"),
        "honkai3" to Game("崩坏3","bh3_cn","e202306201626331",base,"/event/luna",""))
    private fun headers(c:JSONObject,g:Game): Map<String,String> {
        required(c,"cookie")
        return mapOf("Cookie" to c.getString("cookie"), "DS" to Signing.mys(Signing.now(),Signing.nonce(6).lowercase()), "x-rpc-client_type" to "5", "x-rpc-app_version" to "2.109.0", "x-rpc-channel" to "miyousheluodi", "x-rpc-device_id" to c.optString("deviceId","00000000-0000-0000-0000-000000000000"), "Origin" to "https://act.mihoyo.com", "Referer" to "https://act.mihoyo.com/", "User-Agent" to "Mozilla/5.0 (Linux; Android 12) miHoYoBBS/2.109.0") + if(g.signgame.isBlank()) emptyMap() else mapOf("x-rpc-signgame" to g.signgame)
    }
    private fun request(c:JSONObject,g:Game,m:String,url:String,persist:()->Unit,query:Map<String,String> = emptyMap(),body:String?=null):JSONObject {
        var r=http.call(m,url,headers(c,g),query,body)
        if(r.optString("retcode")=="-100") {
            val parts=c.optString("cookie").split(';').mapNotNull { val p=it.trim().split('=',limit=2); if(p.size==2) p[0] to p[1] else null }.toMap().toMutableMap()
            val st=c.optString("stoken",parts["stoken"].orEmpty()); val uid=c.optString("stuid",parts["stuid"] ?: parts["account_id"].orEmpty()); val mid=c.optString("mid",parts["mid"] ?: parts["account_mid_v2"].orEmpty())
            if(st.isBlank() || uid.isBlank() || (st.startsWith("v2_") && mid.isBlank())) throw SignError("auth","Cookie 已失效，缺少续期授权")
            val cookie="stuid=$uid; stoken=$st" + if(st.startsWith("v2_")) "; mid=$mid" else ""
            val renewal=http.call("GET",base+"/auth/api/getCookieAccountInfoBySToken",mapOf("Cookie" to cookie))
            val token=renewal.optJSONObject("data")?.optString("cookie_token").orEmpty()
            if(renewal.optString("retcode")!="0" || token.isBlank()) throw SignError("auth","Cookie 续期失败，请重新登录")
            parts["cookie_token"]=token;c.put("cookie",parts.entries.joinToString("; "){"${it.key}=${it.value}"});persist()
            r=http.call(m,url,headers(c,g),query,body)
        }
        return r
    }
    private fun check(r:JSONObject) { when(r.optString("retcode")) { "0" -> Unit; "-100" -> throw SignError("auth","米游社授权失效"); "1034" -> throw SignError("captcha","请在米游社完成人机验证"); else -> throw SignError("protocol","米游社接口未确认成功") } }
    override fun targets(c:JSONObject,persist:()->Unit):List<SignTarget> {
        val items=mutableListOf<SignTarget>()
        for(key in c.strings("games",games.keys.toList())) {
            val g=games[key] ?: continue
            val r=request(c,g,"GET",base+"/binding/api/getUserGameRolesByCookie",persist,mapOf("game_biz" to g.biz));check(r)
            r.optJSONObject("data")?.rows("list")?.forEach { role ->
                val uid=role.optString("game_uid");val region=role.optString("region")
                if(uid.isNotBlank()&&region.isNotBlank()) items.add(SignTarget("$key|$uid|$region",g.name+" · "+role.optString("nickname",uid)))
            }
        }
        return items.distinctBy { it.id }.ifEmpty { listOf(SignTarget("no-roles","米游社角色绑定")) }
    }
    override fun queryStatus(c:JSONObject,t:SignTarget,persist:()->Unit):SignResult {
        val p=t.id.split('|');val g=games[p[0]] ?: return SignResult("uncertain","未识别游戏，请在官方应用核对")
        if(p.size!=3)return SignResult("uncertain","角色不完整，未重新提交")
        val r=request(c,g,"GET",g.base+g.path+"/info",persist,mapOf("lang" to "zh-cn","act_id" to g.act,"region" to p[2],"uid" to p[1]));check(r)
        return if(r.optJSONObject("data")?.opt("is_sign")==true)SignResult("already","只读官方状态确认已签到") else SignResult("uncertain","官方暂未确认已签；先前提交仍待核对，未重新提交")
    }
    override fun checkin(c:JSONObject,t:SignTarget,persist:()->Unit):SignResult {
        val p=t.id.split('|');val g=games[p[0]]
        if(p.size!=3 || g==null) return SignResult("needs_action","未发现有效角色，请在官方应用绑定")
        val query=mapOf("lang" to "zh-cn","act_id" to g.act,"region" to p[2],"uid" to p[1])
        var r=request(c,g,"GET",g.base+g.path+"/info",persist,query);check(r)
        val stateData=r.optJSONObject("data")
        if(stateData?.opt("is_sign") !is Boolean)throw SignError("protocol","米游社状态响应缺少签到状态，已停止提交")
        if(stateData.optBoolean("is_sign"))return SignResult("already","状态接口确认今日已签")
        val body=JSONObject(linkedMapOf("act_id" to g.act,"region" to p[2],"uid" to p[1])).toString()
        r=request(c,g,"POST",g.base+g.path+"/sign",persist,body=body)
        if(r.optJSONObject("data")?.optInt("success")==1)return SignResult("needs_action","请在米游社完成人机验证")
        val outcome=result(r.optString("retcode"),already=setOf("-5003"),auth=setOf("-100"),captcha=setOf("1034"))
        if(outcome.status!="success")return outcome
        val verify=try{request(c,g,"GET",g.base+g.path+"/info",persist,query)}catch(_:Exception){return SignResult("uncertain","提交已接受，但后续官方状态查询未完成；请先核对，未重新提交")}
        return if(verify.optString("retcode")=="0"&&verify.optJSONObject("data")?.optBoolean("is_sign")==true)SignResult("success","签到成功，官方状态已确认") else SignResult("uncertain","提交后状态未确认，请在官方应用核对后再操作")
    }
}

class SklandAdapter(private val http:Transport) : ProviderAdapter {
    private val base="https://zonai.skland.com/api/v1"
    private fun call(c:JSONObject,path:String,body:JSONObject?=null,endfield:Boolean=false,extra:Map<String,String> = emptyMap(),get:Boolean=false):JSONObject {
        required(c,"cred","signToken","dId")
        val time=((System.currentTimeMillis()/1000)+c.optLong("timeOffsetSeconds")).toString()
        val ctx=linkedMapOf("platform" to if(endfield)"3" else "1","timestamp" to time,"dId" to c.getString("dId"),"vName" to if(endfield)"1.0.0" else "1.5.1")
        // JSONObject(Map) does not guarantee insertion order: signing context is explicitly serialized.
        val context=ctx.entries.joinToString(",","{","}"){JSONObject.quote(it.key)+":"+JSONObject.quote(it.value)}
        val text=body?.toString().orEmpty()
        val headers=ctx+mapOf("cred" to c.getString("cred"),"sign" to Signing.skland("/api/v1$path",text,time,context,c.getString("signToken")),"User-Agent" to "Skland/1.5.1 (com.hypergryph.skland; build:100501001; Android 34; ) Okhttp/4.11.0","X-Requested-With" to "com.hypergryph.skland")+extra
        return http.call(if(get)"GET" else "POST",base+path,headers,body=text)
    }
    override fun targets(c:JSONObject,persist:()->Unit):List<SignTarget> {
        val r=call(c,"/game/player/binding",get=true)
        if(r.optString("code")!="0")throw SignError("auth","森空岛授权失效或角色查询失败")
        val selected=c.strings("games",listOf("arknights","endfield"));val items=mutableListOf<SignTarget>()
        r.optJSONObject("data")?.rows("list")?.forEach { game ->
            val kind=game.optString("appCode")
            if(kind in selected) {
                game.rows("bindingList").forEach { b ->
                    if(kind=="endfield") {
                        val roles=b.rows("roles").ifEmpty { listOfNotNull(b.optJSONObject("defaultRole")) }
                        roles.forEach { role -> val id=role.optString("roleId");val server=role.optString("serverId");if(id.isNotBlank()&&server.isNotBlank())items.add(SignTarget("endfield|$id|$server","终末地 · "+role.optString("nickname",id))) }
                    } else if(kind=="arknights") {
                        val uid=b.optString("uid");val gid=b.optString("channelMasterId")
                        if(uid.isNotBlank()&&gid.isNotBlank())items.add(SignTarget("arknights|$uid|$gid","明日方舟 · "+b.optString("nickName",uid)))
                    }
                }
                if(c.optBoolean("communityCheckin",true)) { val gid=if(kind=="endfield")"3" else if(kind=="arknights")"1" else "";if(gid.isNotEmpty())items.add(SignTarget("island|$gid","森空岛 · 社区检票")) }
            }
        }
        return items.distinctBy{it.id}.ifEmpty{listOf(SignTarget("no-roles","森空岛绑定角色"))}
    }
    override fun checkin(c:JSONObject,t:SignTarget,persist:()->Unit):SignResult {
        val p=t.id.split('|')
        val r=when {
            p[0]=="endfield"&&p.size==3 -> call(c,"/game/endfield/attendance",endfield=true,extra=mapOf("sk-game-role" to "3_${p[1]}_${p[2]}"))
            p[0]=="arknights"&&p.size==3 -> call(c,"/game/attendance",JSONObject().put("uid",p[1]).put("gameId",p[2]))
            p[0]=="island"&&p.size==2 -> call(c,"/score/checkin",JSONObject().put("gameId",p[1]))
            else -> return SignResult("needs_action","请在森空岛绑定角色")
        }
        if(r.optString("code")=="10001")return if(listOf("已签到","重复").any{r.optString("message").contains(it)})SignResult("already","官方提示今日已签") else SignResult("needs_action","设备授权需核对，请在官方应用处理")
        return result(r.optString("code"),auth=setOf("10003"),captcha=setOf("10002"))
    }
}

class TaggedAdapter(private val http:Transport) : ProviderAdapter {
    private val base="https://bbs-api.tajiduo.com"
    private fun headers(c:JSONObject,token:String) = mapOf("authorization" to token,"uid" to c.optString("uid","0"),"deviceid" to c.optString("deviceId"),"appversion" to "1.2.5","platform" to "android","ds" to Signing.tagged(Signing.now(),Signing.nonce(8)),"User-Agent" to "okhttp/4.12.0")
    private fun token(c:JSONObject,persist:()->Unit):String {
        c.optString("_accessToken").takeIf{it.isNotBlank()}?.let{return it}
        required(c,"refreshToken","deviceId")
        val r=http.call("POST",base+"/usercenter/api/refreshToken",headers(c,c.getString("refreshToken"))+mapOf("debug-uid" to "3"),form=emptyMap())
        if(r.optString("code")!="0")throw SignError("auth","塔吉多续期失败，请重新登录")
        val d=r.optJSONObject("data") ?: throw SignError("protocol","令牌响应格式异常")
        required(d,"accessToken","refreshToken")
        c.put("refreshToken",d.getString("refreshToken")).put("uid",d.optString("uid",c.optString("uid"))).put("_accessToken",d.getString("accessToken"));persist()
        return d.getString("accessToken")
    }
    override fun targets(c:JSONObject,persist:()->Unit):List<SignTarget> {
        val token=token(c,persist);val items=mutableListOf<SignTarget>()
        if(c.optBoolean("communityCheckin",true))items.add(SignTarget("community|1","塔吉多 · 社区签到"))
        for(gid in c.strings("gameIds",listOf(c.optString("gameId","1289")))) {
            if(!gid.all{it.isDigit()} || gid.isBlank())continue
            val configured=c.strings("roleIds",emptyList())
            val roles=if(configured.isNotEmpty())configured.map{it to it} else {
                val r=http.call("GET",base+"/usercenter/api/v2/getGameRoles",headers(c,token),query=mapOf("gameId" to gid))
                if(r.optString("code")!="0") {
                    val code=r.optString("code").takeIf{it.matches(Regex("[A-Za-z0-9_-]{1,12}"))} ?: "unknown"
                    throw SignError(when(code){"401","402"->"auth";"403"->"captcha";else->"protocol"},"塔吉多角色查询未完成（$code），请检查授权或官方绑定状态")
                }
                val d=r.optJSONObject("data");(d?.rows("roles").orEmpty().ifEmpty{d?.rows("list").orEmpty()}).map{it.optString("roleId") to it.optString("roleName",it.optString("nickname"))}
            }
            roles.filter{it.first.isNotBlank()}.forEach{(id,name)->items.add(SignTarget("game|$gid|$id","异环 · $name"))}
        }
        if(items.none{it.id.startsWith("game|")})items.add(SignTarget("no-roles","塔吉多游戏角色"))
        return items.distinctBy{it.id}
    }
    override fun queryStatus(c:JSONObject,t:SignTarget,persist:()->Unit):SignResult {
        val p=t.id.split('|')
        if(p.size!=3||p[0]!="game")return SignResult("uncertain","社区任务请在官方应用核对，未重新提交")
        val r=http.call("GET",base+"/apihub/awapi/signin/state",headers(c,token(c,persist)),query=mapOf("gameId" to p[1]))
        return if(r.optString("code")=="0"&&r.optJSONObject("data")?.opt("todaySign")==true)SignResult("already","只读官方状态确认已签到") else SignResult("uncertain","官方暂未确认已签；未重新提交，请核对游戏状态")
    }
    override fun checkin(c:JSONObject,t:SignTarget,persist:()->Unit):SignResult {
        val h=headers(c,token(c,persist));val p=t.id.split('|')
        if(p[0]=="community") {val r=http.call("POST",base+"/apihub/api/signin",h,form=mapOf("communityId" to "1"));return result(r.optString("code"),auth=setOf("401","402"),captcha=setOf("403"))}
        if(p.size!=3 || p[0]!="game")return SignResult("needs_action","未获取到游戏角色，请重新绑定")
        fun state()=http.call("GET",base+"/apihub/awapi/signin/state",h,query=mapOf("gameId" to p[1]))
        val s=state()
        if(s.optString("code")!="0")return result(s.optString("code"),auth=setOf("401","402"),captcha=setOf("403"))
        val stateData=s.optJSONObject("data")
        if(stateData?.opt("todaySign") !is Boolean)throw SignError("protocol","塔吉多状态响应缺少签到状态，已停止提交")
        if(stateData.optBoolean("todaySign"))return SignResult("already","游戏状态确认今日已签")
        val r=http.call("POST",base+"/apihub/awapi/sign",h,form=mapOf("roleId" to p[2],"gameId" to p[1]))
        return result(r.optString("code"),auth=setOf("401","402"),captcha=setOf("403"))
    }
}

class KuroBBSAdapter(private val http:Transport) : ProviderAdapter {
    private val base="https://api.kurobbs.com"
    private val servers=mapOf("3" to "76402e5b20be2c39f095a152090afddc","2" to "1000")
    private fun headers(c:JSONObject,game:Boolean=false):Map<String,String> {required(c,"token");return mapOf("token" to c.getString("token"),"devCode" to c.optString("devCode"),"distinct_id" to c.optString("distinct_id"),"source" to if(game)"ios" else "android","model" to "2211133C","version" to "1.0.9","User-Agent" to if(game)"Mozilla/5.0 (iPhone; CPU iPhone OS 17_3 like Mac OS X) KuroGameBox/2.2.0" else "okhttp/3.10.0") + if(game)mapOf("Origin" to "https://web-static.kurobbs.com") else emptyMap()}
    override fun targets(c:JSONObject,persist:()->Unit):List<SignTarget> {
        required(c,"token")
        for(key in listOf("devCode","distinct_id"))if(c.optString(key).isBlank())c.put(key,UUID.randomUUID().toString())
        persist()
        if(c.optString("userId").isBlank()) {
            val r=http.call("POST",base+"/user/mineV2",headers(c),form=emptyMap())
            if(r.optString("code")!="200")throw SignError("auth","库街区账号读取失败，请重新授权")
            c.put("userId",r.optJSONObject("data")?.optJSONObject("mine")?.optString("userId").orEmpty());required(c,"userId");persist()
        }
        val items=mutableListOf<SignTarget>()
        if(c.optBoolean("communityCheckin",true))items.add(SignTarget("community|2","库街区 · 社区签到"))
        for(gid in c.strings("games",listOf("3","2"))) {
            if(gid !in servers)continue
            val configured=c.optJSONObject("roleIds")?.optJSONArray(gid)
            val roles=if(configured!=null)(0 until configured.length()).map{JSONObject().put("roleId",configured.getString(it))} else {
                val r=http.call("POST",base+"/user/role/findRoleList",headers(c),form=mapOf("gameId" to gid))
                if(r.optString("code")!="200")throw SignError("protocol","库街区角色查询失败")
                r.optJSONArray("data")?.let{a->(0 until a.length()).mapNotNull{a.optJSONObject(it)}} ?: r.optJSONObject("data")?.let{it.rows("list").ifEmpty{it.rows("roleList")}}.orEmpty()
            }
            roles.forEach{role->val id=role.optString("roleId");if(id.isNotBlank())items.add(SignTarget("game|$gid|$id",(if(gid=="3")"鸣潮" else "战双帕弥什")+" · $id",role.optString("serverId").takeIf{it.isNotBlank()}))}
        }
        if(items.none{it.id.startsWith("game|")})items.add(SignTarget("no-roles","库街区游戏角色"))
        return items.distinctBy{it.id}
    }
    override fun checkin(c:JSONObject,t:SignTarget,persist:()->Unit):SignResult {
        val p=t.id.split('|')
        val r=when {
            p[0]=="community" -> http.call("POST",base+"/user/signIn",headers(c)+mapOf("Cookie" to "user_token=${c.getString("token")}","source" to "ios"),form=mapOf("gameId" to "2"))
            p.size==3&&p[0]=="game"&&p[1] in servers -> http.call("POST",base+"/encourage/signIn/v2",headers(c,true),form=mapOf("gameId" to p[1],"serverId" to (t.serverId ?: servers.getValue(p[1])),"roleId" to p[2],"userId" to c.getString("userId"),"reqMonth" to "%02d".format(ZonedDateTime.now(SignRepository.TZ).monthValue)))
            else -> return SignResult("needs_action","未发现游戏角色，请在库街区绑定")
        }
        return result(r.optString("code"),ok="200",already=setOf("1511"),auth=setOf("220"))
    }
}
fun officialAdapters(http:Transport=OfficialTransport()):Map<String,ProviderAdapter> = mapOf("miyoushe" to MiyousheAdapter(http),"skland" to SklandAdapter(http),"tagged" to TaggedAdapter(http),"kurobbs" to KuroBBSAdapter(http))
