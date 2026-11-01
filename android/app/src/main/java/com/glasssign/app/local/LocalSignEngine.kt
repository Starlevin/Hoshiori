package com.glasssign.app.local

import android.content.Context
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import com.glasssign.app.data.GameCatalog
import java.util.UUID

data class RunSummary(val attempted:Int,val skipped:Int,val waiting:Boolean,val retryPending:Boolean=false,val day:String=SignRepository.day())
class LocalSignEngine(val repository:SignRepository,val credentials:LocalCredentialStore,private val adapters:Map<String,ProviderAdapter>) {
    companion object { val mutex=Mutex() }
    private fun failure(e:Exception):SignResult = if(e is SignError)SignResult(e.status,e.message ?: "社区请求失败") else SignResult("failed","本地处理失败，请核对授权或官方记录")
    suspend fun run(ids:List<String>?=null):RunSummary = mutex.withLock {
        val day=SignRepository.day();var attempted=0;var skipped=0;var waiting=false
        // A scheduled run, manual click or stale queued job must not activate a pending binding.
        val eligible=repository.accounts().filter{!it.bindingPending && it.authState!="pending" && ((ids==null&&it.enabled)||(ids!=null&&it.id in ids))}
        for(account in eligible) {
            coroutineContext.ensureActive()
            val c:JSONObject;val adapter:ProviderAdapter;val targets:List<SignTarget>
            val discovery=SignTarget("discovery","授权与角色检查")
            // Discovery itself is claimed: token renewal is serialized and bounded too.
            if(!repository.claim(day,account.id,discovery)) {skipped++;continue}
            try {
                c=GameCatalog.scope(account.provider,account.gameIds,credentials.read(account.id));adapter=adapters[account.provider] ?: throw SignError("config","平台尚未支持")
                targets=adapter.targets(c){credentials.save(account.id,c)}
                credentials.save(account.id,c)
                repository.auth(account.id,if(targets.any{it.isBoundRole()})"valid" else "needs_action",if(targets.any{it.isBoundRole()})"授权可读取角色；不代表签到成功" else "请在官方应用绑定角色")
                // Remove successful discovery claims; only actual task outcomes appear as success.
                repository.finish(day,account.id,discovery,SignResult("failed","角色查询完成"));repository.clearDiscoveryFailure(account.id)
            } catch(e:Exception) {
                val r=failure(e);repository.finish(day,account.id,discovery,r);repository.auth(account.id,r.status,r.message);waiting=waiting||r.status=="waiting_network";continue
            }
            for(t in targets.distinctBy{it.id}) {
                coroutineContext.ensureActive()
                if(!repository.claim(day,account.id,t)) {repository.acknowledgeSigned(day,account.id,t);skipped++;continue}
                attempted++
                val r=try { adapter.checkin(c,t){credentials.save(account.id,c)} } catch(e:Exception){failure(e)}
                repository.finish(day,account.id,t,r)
                if(r.status=="needs_action")repository.auth(account.id,"needs_action",r.message)
                waiting=waiting||r.status=="waiting_network"
            }
        }
        val eligibleIds=eligible.map{it.id}.toSet()
        val unfinished=repository.records(day).filter{it.accountId in eligibleIds && it.attempts<3}
        RunSummary(attempted,skipped,waiting || unfinished.any{it.status=="waiting_network"},unfinished.any{it.status=="failed"},day)
    }
    suspend fun verify(id:String):String = mutex.withLock {
        val a=repository.accounts().firstOrNull{it.id==id} ?: return@withLock "账号已删除"
        if(a.bindingPending || a.authState=="pending")return@withLock "请先完成角色确认；验证授权不会自动完成绑定"
        try {
            val c=GameCatalog.scope(a.provider,a.gameIds,credentials.read(id));val targets=adapters.getValue(a.provider).targets(c){credentials.save(id,c)}
            credentials.save(id,c)
            val valid=targets.any{it.isBoundRole()}
            val message=if(valid)"授权可读取角色，尚未验证真实签到" else "授权未发现角色，请到官方应用确认"
            repository.auth(id,if(valid)"valid" else "needs_action",message);message
        } catch(e:Exception){ val r=failure(e);repository.auth(id,r.status,r.message);r.message }
    }

    /** Enumerates games using existing authorization, without submitting any check-in. */
    suspend fun gamesForSelection(id:String):DiscoverOutcome = mutex.withLock {
        val a=repository.accounts().firstOrNull{it.id==id} ?: return@withLock DiscoverOutcome(emptyList(),false,"账号已删除")
        check(!a.bindingPending && a.authState!="pending") { "请先确认当前绑定" }
        try {
            val c=GameCatalog.scope(a.provider,GameCatalog.idsFor(a.provider),credentials.read(id))
            fun persist() { credentials.save(id,GameCatalog.scope(a.provider,a.gameIds,c)) }
            val targets=adapters.getValue(a.provider).targets(c){persist()}
            persist()
            val roles=targets.filter{it.isBoundRole()}.map{it.name}
            val available=targets.mapNotNull{GameCatalog.gameForTarget(a.provider,it.id)}.distinct()
            DiscoverOutcome(roles,available.isNotEmpty(),if(available.isNotEmpty())"已读取可绑定游戏；保存前不会改变签到范围" else "未读取到可绑定角色，请检查官方绑定",available)
        } catch(e:Exception) { val result=failure(e);DiscoverOutcome(emptyList(),false,result.message) }
    }

    /**
     * Read-only role discovery used right after a login. The account stays in state `pending` until
     * the user confirms the roles, so a captured session alone never counts as a bound account.
     */
    suspend fun discover(id:String):DiscoverOutcome = mutex.withLock {
        repository.markPendingRoles(id,false)
        val a=repository.accounts().firstOrNull{it.id==id}
        if(a==null) DiscoverOutcome(emptyList(),false,"账号已删除")
        else try {
            val c=GameCatalog.scope(a.provider,a.gameIds,credentials.read(id))
            val targets=adapters.getValue(a.provider).targets(c){credentials.save(id,c)}
            credentials.save(id,c)
            val roles=targets.filter{it.isBoundRole()}.map{it.name}
            val available=targets.mapNotNull{GameCatalog.gameForTarget(a.provider,it.id)}.distinct()
            repository.markPendingRoles(id,roles.isNotEmpty(),available)
            val message=if(roles.isNotEmpty())"已从官方接口读取 ${roles.size} 个角色，请确认" else "已登录，但官方接口没有返回已绑定角色"
            repository.auth(id,"pending",message)
            DiscoverOutcome(roles,roles.isNotEmpty(),message,available)
        } catch(e:Exception){ val r=failure(e);repository.auth(id,r.status,r.message);DiscoverOutcome(emptyList(),false,r.message) }
    }
}

private fun SignTarget.isBoundRole()=id!="no-roles" && id!="discovery" && !id.startsWith("community|") && !id.startsWith("island|")

data class DiscoverOutcome(val roles:List<String>,val ok:Boolean,val message:String,val availableGameIds:List<String> = emptyList())
class LocalServices private constructor(context:Context) {
    val context=context.applicationContext
    val prefs=this.context.getSharedPreferences("local_settings_v4",Context.MODE_PRIVATE)
    val repository=SignRepository(this.context)
    val credentials=LocalCredentialStore(this.context)
    val bindings=LocalAccountBindings(repository,credentials)
    val engine=LocalSignEngine(repository,credentials,officialAdapters())
    init {repository.recoverInterrupted();bindings.cleanupRetired();bindings.migrateLegacyGames()}
    suspend fun add(provider:String,nickname:String,c:JSONObject,gameId:String=""):String=LocalSignEngine.mutex.withLock {
        val id=UUID.randomUUID().toString();credentials.save(id,GameCatalog.scope(provider,gameId,c))
        try {repository.add(provider,nickname,id,gameId)}catch(e:Exception){credentials.delete(id);throw e}
        id
    }
    suspend fun delete(id:String)=bindings.deleteAccount(id)
    /** Stores a freshly captured official session and parks it as `pending` until roles are confirmed. */
    suspend fun beginLogin(provider:String,nickname:String,c:JSONObject,replaceId:String?=null,gameId:String="",gameIds:List<String> = if(gameId.isEmpty())emptyList() else listOf(gameId)):String=bindings.prepare(provider,nickname,c,replaceId,gameId,gameIds)
    suspend fun confirmLogin(id:String,selected:List<String>?=null):String=bindings.confirm(id,selected)
    suspend fun abandonLogin(id:String)=bindings.cancel(id)
    suspend fun clearCredentials()=bindings.clearCredentials()
    companion object {
        @Volatile private var instance:LocalServices?=null
        fun get(context:Context):LocalServices = instance ?: synchronized(this){instance ?: LocalServices(context).also{instance=it}}
    }
}
