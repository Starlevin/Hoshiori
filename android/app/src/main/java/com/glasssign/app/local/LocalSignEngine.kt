package com.glasssign.app.local

import android.content.Context
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import com.glasssign.app.data.GameCatalog
import com.glasssign.app.data.RoleChoice
import com.glasssign.app.data.AUTHORIZATION_CHECK_NAME
import com.glasssign.app.data.AUTHORIZATION_CHECK_TARGET
import java.util.UUID

data class RunSummary(
    val attempted:Int,
    val skipped:Int,
    val waiting:Boolean,
    /** True only when a stored row was written with an explicit retryable flag ([SignResult.retryable]). */
    val retryPending:Boolean=false,
    val day:String=SignRepository.day(),
    /** Per-click identifier attached to every write of this run (`result_meta.executionId`). */
    val executionId:String="",
    /** Accounts whose tasks were all already proven today: no discovery call and no check-in was made for them. */
    val alreadyComplete:Int=0,
    /** Read-only reconcile suggestions (uncertain/accepted rows); nothing was submitted for them. */
    val awaitingConfirmation:Int=0,
) {
    /** True when this click did no network work at all because everything was already guarded. */
    val noOp:Boolean get()=attempted==0&&!waiting&&!retryPending&&alreadyComplete>0
}

class LocalSignEngine(val repository:SignRepository,val credentials:CredentialStore,private val adapters:Map<String,ProviderAdapter>) {
    companion object {
        /**
         * One engine per process; every entry point serializes on this lock.
         *
         * Not a per-call lock: the scheduler, a manual click and a stale queued job may all start at once, and the
         * SUBMISSION decision is "read the stored rows, then claim". Two runs that both planned before the first one
         * claimed would both submit. Holding the same lock across plan+claim+finish makes the plan authoritative.
         */
        val mutex=Mutex()
    }
    private fun failure(e:Exception):SignResult = (e as? SignError)?.asResult() ?: SignResult("failed","本地处理失败，请核对授权或官方记录")
    /**
     * The ONE auth state a provider outcome may produce for the ACCOUNT row.
     *
     * Only an explicit authorization/captcha/config rejection changes the account state. A network error, an unknown
     * HTTP status (including 402 and any unrecognised business code) or a protocol failure says nothing about whether
     * the stored credential is valid, so the account keeps "valid" and the per-task row carries the failure.
     */
    private fun authStateFor(r:SignResult,current:String):String = if(r.status=="needs_action") "needs_action" else current
    /** Community targets are owned only when the stored scope enables `communityCheckin`. */
    private fun ownsCommunityTasks(id:String,store:CredentialStore):Boolean = try {
        store.read(id).optBoolean("communityCheckin",true)
    } catch (_:Exception) { true }
    suspend fun run(ids:List<String>?=null,scheduled:Boolean=false):RunSummary = mutex.withLock {
        val day=SignRepository.day();val executionId=UUID.randomUUID().toString()
        var attempted=0;var skipped=0;var waiting=false;var retryPending=false;var alreadyComplete=0;var awaiting=0
        // A scheduled run, manual click or stale queued job must not activate a pending binding.
        val eligible=repository.accounts().filter{!it.bindingPending && it.authState!="pending" && it.enabled && (!scheduled || it.autoSignEnabled) && (ids==null || it.id in ids)}
        val guards=repository.guards(day)
        for(account in eligible) {
            coroutineContext.ensureActive()
            // ---- PLAN FIRST: no network call and no token renewal before we know there is work -----------------
            // The known scope is the user's explicit bindings plus the stored community/no-role targets; community
            // tasks are owned only when the stored scope enables them. Nothing is ever invented.
            val community=ownsCommunityTasks(account.id,credentials)
            val todayRows=repository.records(day)
            val cachedExtras=(todayRows+guards).filter { it.accountId==account.id &&
                (com.glasssign.app.data.isCommunityTarget(it.targetId)||it.targetId=="no-roles") }
                .map { RoleChoice(it.targetId,"",it.targetName) }
            val expectedCommunityIds=if(!community)emptyList() else when(account.provider) {
                "tagged"->listOf("community|1")
                "kurobbs"->listOf("community|2")
                "skland"->account.gameIds.ifEmpty { listOf("arknights","endfield") }.mapNotNull { when(it){"arknights"->"island|1";"endfield"->"island|3";else->null} }
                else->emptyList()
            }
            val communityScopeKnown=expectedCommunityIds.all { id->cachedExtras.any { it.targetId==id } }
            val plan=planAccount(
                AccountPlanInput(account.id,account.gameIds,account.roleScope),
                repository.bindings(account.id),
                if(community)repository.roleChoices(account.id)+cachedExtras else emptyList(),
                todayRows,
                guards,
                scheduled,
            )
            awaiting+=plan.awaitingConfirmation.size
            if(plan.nothingTodo && communityScopeKnown) {
                // Every owned task already carries an official/accepted proof for today: no discovery GET, no token
                // POST, no check-in. A read-only reconcile stays an explicit user action (`reconcile`), never automatic.
                // The completed rows are re-stated as a LOCAL CACHE display (the proof itself, including its
                // verifiedAt, is preserved) so the UI never presents an old result as a fresh official query.
                plan.skipped.forEach{(task,_)->repository.acknowledgeSigned(day,account.id,task.target)}
                alreadyComplete++
                skipped+=plan.skipped.size
                continue
            }
            if(plan.trustedScope&&!plan.hasWork && communityScopeKnown) {
                // Stored but unconfirmed outcomes only: submission must not happen, so no token is renewed either.
                skipped+=plan.skipped.size
                continue
            }
            val c:JSONObject;val adapter:ProviderAdapter;val targets:List<SignTarget>
            val discovery=SignTarget(AUTHORIZATION_CHECK_TARGET,AUTHORIZATION_CHECK_NAME)
            // Discovery itself is claimed: token renewal is serialized and bounded too.
            if(!repository.claim(day,account.id,discovery)) {skipped++;continue}
            try {
                c=GameCatalog.scope(account.provider,account.gameIds,credentials.read(account.id));adapter=adapters[account.provider] ?: throw SignError("config","平台尚未支持")
                targets=adapter.targets(c){credentials.save(account.id,c)}
                credentials.save(account.id,c)
                repository.identity(account.id,AccountIdentity.read(account.provider,c))
                repository.learnBindings(account.id,targets.mapNotNull{t->GameCatalog.gameForTarget(account.provider,t.id)?.let{RoleChoice(t.id,it,t.name)}})
                repository.auth(account.id,if(targets.any{it.isBoundRole()})"valid" else "needs_action",if(targets.any{it.isBoundRole()})"授权可读取角色；不代表签到成功" else "请在官方应用绑定角色")
                // Remove successful discovery claims; only actual task outcomes appear as success.
                repository.finish(day,account.id,discovery,SignResult("failed","角色查询完成"));repository.clearDiscoveryFailure(account.id)
            } catch(e:Exception) {
                val r=failure(e).copy(executionId=executionId)
                repository.finish(day,account.id,discovery,r)
                // A discovery failure is NEVER a statement about the session: only an explicit auth/captcha/config
                // rejection marks the account needs_action. Network / unknown HTTP (402 included) keep "valid".
                if(r.status=="needs_action")repository.auth(account.id,"needs_action",r.message) else repository.authDiagnostic(account.id,r.message)
                waiting=waiting||r.status=="waiting_network"
                retryPending=retryPending||(r.retryable&&r.status in setOf("failed","waiting_network"))
                continue
            }
            // Re-plan with the fresh bindings so a game the user JUST enabled is never skipped, then execute only
            // planned tasks: the discovery result can never make the engine sign a target it was not told about.
            for(t in targets.distinctBy { it.id }) {
                coroutineContext.ensureActive()
                val binding=repository.bindings(account.id).firstOrNull { it.targetId==t.id }
                if((account.roleScope&&t.isBoundRole()&&binding==null)||binding?.enabled==false||
                    (scheduled&&binding?.autoSignEnabled==false)) {skipped++;continue}
                if(!repository.claim(day,account.id,t)) {
                    // Already proven/blocked today (a concurrent run won the claim): submit nothing, and state that
                    // the evidence comes from the local cache while keeping the original verifiedAt.
                    repository.acknowledgeSigned(day,account.id,t)
                    skipped++;continue
                }
                attempted++
                val result=try { adapter.checkin(c,t){credentials.save(account.id,c)} } catch(e:Exception){failure(e)}
                val tagged=result.copy(executionId=executionId)
                repository.finish(day,account.id,t,tagged)
                if(tagged.status=="needs_action")repository.auth(account.id,authStateFor(tagged,account.authState),tagged.message)
                waiting=waiting||tagged.status=="waiting_network"
                retryPending=retryPending||(tagged.retryable&&tagged.status in setOf("failed","waiting_network"))
            }
        }
        val eligibleIds=eligible.map{it.id}.toSet()
        val unfinished=repository.records(day).filter{it.accountId in eligibleIds && it.attempts<3}
        RunSummary(
            attempted=attempted,
            skipped=skipped,
            waiting=waiting||unfinished.any{it.status=="waiting_network"&&it.retryable},
            // Retryability is explicit: a stored row retries ONLY when its metadata says retryable=true.
            retryPending=retryPending||unfinished.any{it.resultMeta.retryable&&it.status in setOf("failed","waiting_network")},
            day=day,
            executionId=executionId,
            alreadyComplete=alreadyComplete,
            awaitingConfirmation=awaiting,
        )
    }
    suspend fun verify(id:String):String = mutex.withLock {
        val a=repository.accounts().firstOrNull{it.id==id} ?: return@withLock "账号已删除"
        if(a.bindingPending || a.authState=="pending")return@withLock "请先完成角色确认；验证授权不会自动完成绑定"
        try {
            val c=GameCatalog.scope(a.provider,a.gameIds,credentials.read(id));val targets=adapters.getValue(a.provider).targets(c){credentials.save(id,c)}
            credentials.save(id,c)
            val valid=targets.any{it.isBoundRole()}
            val message=if(valid)"授权可读取角色，尚未验证真实签到" else "授权未发现角色，请到官方应用确认"
            repository.auth(id,if(valid)"valid" else "needs_action",message)
            if(valid)repository.clearDiscoveryFailure(id)
            message
        } catch(e:Exception){ val r=failure(e);if(r.status=="needs_action")repository.auth(id,"needs_action",r.message) else repository.authDiagnostic(id,r.message);r.message }
    }

    /**
     * Explicit user action only: read-only official status for one target.
     *
     *  - Works for ANY existing stored row, including an `accepted` submission (query-only manual verification).
     *  - It may renew an expired token internally (adapters refresh on demand), but it never submits a check-in.
     *  - A failed/inconclusive query preserves the stored accepted/official proof (see [SignRepository.reconcile]).
     *  - The row shows `verifying` while the read runs; that transient state never erases the earlier proof.
     */
    suspend fun reconcile(id:String,target:String):String=mutex.withLock {
        val a=repository.accounts().firstOrNull{it.id==id} ?: return@withLock "账号已删除"
        val day=SignRepository.day()
        val record=repository.records(day).firstOrNull{it.accountId==id&&it.targetId==target} ?: return@withLock "只可校验今天已有记录"
        val c:JSONObject=try{GameCatalog.scope(a.provider,a.gameIds,credentials.read(id))}catch(e:Exception){val r=failure(e);if(r.status=="needs_action")repository.auth(id,"needs_action",r.message);return@withLock r.message}
        repository.markVerifying(day,id,target)
        val result=try{adapters.getValue(a.provider).queryStatus(c,SignTarget(target,record.targetName)){credentials.save(id,c)}}catch(e:Exception){failure(e)}
        if(result.isOfficialConfirmation) {
            repository.auth(id,"valid","授权可读取官方签到状态；其他角色仍按各自记录判断")
            repository.clearDiscoveryFailure(id)
        }
        else if(result.status=="needs_action")repository.auth(id,"needs_action",result.message)
        val stored=repository.reconcile(day,id,target,result.copy(executionId=UUID.randomUUID().toString()))
        if(result.isOfficialConfirmation) return@withLock "官方状态已确认今日完成：${result.message}"
        val known=when(provenanceOf(record).source){
            SOURCE_OFFICIAL->"已有官方确认的完成记录"
            SOURCE_ACCEPTED->"已有官方接受提交的记录"
            SOURCE_LOCAL_CACHE,SOURCE_LEGACY_LOCAL->"已有本机完成记录"
            else->"已有 ${record.status} 记录"
        }
        if(stored==null) "记录已被清理；本次只读核对未取得新结论（${result.message}）"
        else "$known；本次只读核对未取得新结论，原记录已保留"
    }
    /** Enumerates games using existing authorization, without submitting any check-in. */
    suspend fun gamesForSelection(id:String):DiscoverOutcome = mutex.withLock {
        val a=repository.accounts().firstOrNull{it.id==id} ?: return@withLock DiscoverOutcome(emptyList(),false,"账号已删除")
        check(!a.bindingPending && a.authState!="pending") { "请先确认当前绑定" }
        try {
            val original=credentials.read(id)
            val c=GameCatalog.scope(a.provider,GameCatalog.idsFor(a.provider),original).apply{remove("roleIds")}
            fun persist() {
                val restored=GameCatalog.scope(a.provider,a.gameIds,c)
                if(original.has("roleIds"))restored.put("roleIds",original.get("roleIds"))
                credentials.save(id,restored)
            }
            val targets=adapters.getValue(a.provider).targets(c){persist()}
            persist()
            val choices=targets.mapNotNull{t->GameCatalog.gameForTarget(a.provider,t.id)?.let{RoleChoice(t.id,it,t.name)}}
            repository.storeRoleChoices(id,choices)
            repository.identity(id,AccountIdentity.read(a.provider,c))
            val roles=targets.filter{it.isBoundRole()}.map{it.name}
            val available=targets.mapNotNull{GameCatalog.gameForTarget(a.provider,it.id)}.distinct()
            DiscoverOutcome(roles,available.isNotEmpty(),if(available.isNotEmpty())"已读取可绑定游戏；保存前不会改变签到范围" else "未读取到可绑定角色，请检查官方绑定",available,choices,AccountIdentity.read(a.provider,c))
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
            val c=GameCatalog.scope(a.provider,a.gameIds,credentials.read(id)).apply{remove("roleIds")}
            val targets=adapters.getValue(a.provider).targets(c){credentials.save(id,c)}
            credentials.save(id,c)
            val choices=targets.mapNotNull{t->GameCatalog.gameForTarget(a.provider,t.id)?.let{RoleChoice(t.id,it,t.name)}}
            repository.storeRoleChoices(id,choices)
            repository.identity(id,AccountIdentity.read(a.provider,c))
            val roles=targets.filter{it.isBoundRole()}.map{it.name}
            val available=targets.mapNotNull{GameCatalog.gameForTarget(a.provider,it.id)}.distinct()
            repository.markPendingRoles(id,roles.isNotEmpty(),available)
            val message=if(roles.isNotEmpty())"已从官方接口读取 ${roles.size} 个角色，请确认" else "已登录，但官方接口没有返回已绑定角色"
            repository.auth(id,"pending",message)
            DiscoverOutcome(roles,roles.isNotEmpty(),message,available,choices,AccountIdentity.read(a.provider,c))
        } catch(e:Exception){ val r=failure(e);repository.auth(id,r.status,r.message);DiscoverOutcome(emptyList(),false,r.message) }
    }
}

private fun SignTarget.isBoundRole()=id!="no-roles" && id!="discovery" && !id.startsWith("community|") && !id.startsWith("island|")

data class DiscoverOutcome(val roles:List<String>,val ok:Boolean,val message:String,val availableGameIds:List<String> = emptyList(),val roleChoices:List<RoleChoice> = emptyList(),val providerUserId:String="")
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
    suspend fun confirmLogin(id:String,selected:List<String>?=null,roles:Set<String>?=null,autoSign:Boolean=true):String=bindings.confirm(id,selected,roles,autoSign)
    suspend fun abandonLogin(id:String)=bindings.cancel(id)
    suspend fun clearCredentials()=bindings.clearCredentials()
    companion object {
        @Volatile private var instance:LocalServices?=null
        fun get(context:Context):LocalServices = instance ?: synchronized(this){instance ?: LocalServices(context).also{instance=it}}
    }
}
