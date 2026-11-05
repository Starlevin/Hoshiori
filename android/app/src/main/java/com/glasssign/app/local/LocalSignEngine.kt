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
import com.glasssign.app.data.Account
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

/**
 * A [CredentialStore] view bound to ONE account id.
 *
 * WHY: adapters receive `persist`/`c` and could, in principle, ask the store for another id. The engine therefore
 * never hands an adapter the raw store: it hands a wrapper whose `read`/`save`/`delete` accept only [ownerId].
 * A call for any other id fails closed with [CrossAccountCredentialError] BEFORE the Keystore is touched. This is a
 * local, minimal guard at the dispatch boundary — it is not a rewrite of the storage layout and it does not change
 * the database schema.
 */
class ScopedCredentialStore(private val delegate:CredentialStore,val ownerId:String) : CredentialStore {
    private fun guard(id:String) {
        if(id!=ownerId) throw CrossAccountCredentialError()
    }
    override fun read(id:String):JSONObject { guard(id);return delegate.read(id) }
    override fun save(id:String,c:JSONObject) { guard(id);delegate.save(id,c) }
    override fun delete(id:String) { guard(id);delegate.delete(id) }
}

/** An adapter tried to reach another account's credentials. Fail-closed: nothing is read, written or deleted. */
class CrossAccountCredentialError:IllegalStateException("适配器只能访问本次派发的账号授权；跨账号访问已被拒绝")

class LocalSignEngine(
    val repository:SignRepository,
    val credentials:CredentialStore,
    private val adapters:Map<String,ProviderAdapter>,
    /**
     * First-level gate (whole provider). Defaults to "everything allowed" so every existing test and call site keeps
     * its exact behaviour; production injects the user's [ProviderRegistry].
     *
     * This is a SECOND level on top of the existing account switches (`accounts.enabled` / `auto_enabled`) and role
     * switches (`game_bindings.enabled` / `auto_sign`) — those keep working unchanged and are not re-implemented here.
     */
    private val gate:ProviderExecutionGate=ProviderExecutionGate.ALLOW_ALL,
) {
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
    /** Cancellation-free gate read: a registry failure must never crash a run, and must never mean "allowed". */
    private fun open(providerId:String):Boolean = try { gate.isExecutionAllowed(providerId) } catch(_:Exception) { false }
    private fun pausedReason(providerId:String):String =
        (try { gate.blockedReason(providerId) } catch(_:Exception) { null }) ?: ProviderExecutionGate.PAUSED_REASON
    /** Credentials are only ever touched through this per-account view. */
    private fun scoped(id:String)=ScopedCredentialStore(credentials,id)

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
        // Level 2 is the account's own switch (unchanged). Level 1 is the provider switch, applied here and again
        // immediately before every claim.
        val candidates=repository.accounts().filter{!it.bindingPending && it.authState!="pending" && it.enabled &&
            (!scheduled || it.autoSignEnabled) && (ids==null || it.id in ids)}
        val eligible=ArrayList<Account>(candidates.size)
        var pausedAccounts=0
        for(acc in candidates) if(open(acc.provider)) eligible+=acc else pausedAccounts++
        // Paused providers are counted as skipped so the summary can show them; nothing is read or written for them.
        skipped+=pausedAccounts
        val guards=repository.guards(day)
        for(account in eligible) {
            coroutineContext.ensureActive()
            // ---- PLAN FIRST: no network call and no token renewal before we know there is work -----------------
            // The gate is re-checked here because this loop does the FIRST credential read (the community switch
            // below). The outer filter only decided per account once; a provider paused between that filter and this
            // iteration must not be read at all.
            if(!open(account.provider)) {skipped++;continue}
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
            // Same boundary rule as the per-target loop: the gate is the LAST check before the claim. Once the
            // discovery claim succeeds the block below always reaches finish(), so a pause can never leave a
            // `running` discovery row behind for work that was never attempted.
            if(!open(account.provider)) {skipped++;continue}
            if(!repository.claim(day,account.id,discovery)) {skipped++;continue}
            try {
                val store=scoped(account.id)
                c=GameCatalog.scope(account.provider,account.gameIds,store.read(account.id));adapter=adapters[account.provider] ?: throw SignError("config","平台尚未支持")
                targets=adapter.targets(c){store.save(account.id,c)}
                store.save(account.id,c)
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
                // The gate is checked immediately BEFORE the claim, and this is the last check for this target.
                // Claiming is the boundary where a task becomes "in flight": once claimed, the run MUST reach
                // finish() with a real result. Continuing after a successful claim would leave a `running` row for a
                // request that was never sent, and recovery would turn it into `uncertain`, wrongly locking the day.
                if(!open(account.provider)) {skipped++;continue}
                if(!repository.claim(day,account.id,t)) {
                    // Already proven/blocked today (a concurrent run won the claim): submit nothing, and state that
                    // the evidence comes from the local cache while keeping the original verifiedAt.
                    repository.acknowledgeSigned(day,account.id,t)
                    skipped++;continue
                }
                attempted++
                val result=try { adapter.checkin(c,t){scoped(account.id).save(account.id,c)} } catch(e:Exception){failure(e)}
                val tagged=result.copy(executionId=executionId)
                repository.finish(day,account.id,t,tagged)
                if(tagged.status=="needs_action")repository.auth(account.id,authStateFor(tagged,account.authState),tagged.message)
                waiting=waiting||tagged.status=="waiting_network"
                retryPending=retryPending||(tagged.retryable&&tagged.status in setOf("failed","waiting_network"))
            }
        }
        val eligibleIds=eligible.map{it.id}.toSet()
        // Paused-provider accounts are excluded from the retry accounting on purpose: they were never attempted, so
        // they must not create a retry loop. Their stored rows are left completely untouched.
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
        // Paused provider: no credential read, no request. The account keeps its stored authorization state.
        if(!open(a.provider))return@withLock pausedReason(a.provider)
        try {
            val store=scoped(id)
            val c=GameCatalog.scope(a.provider,a.gameIds,store.read(id));val targets=adapters.getValue(a.provider).targets(c){store.save(id,c)}
            store.save(id,c)
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
    // A locally-named lambda with an explicit return type is used instead of a trailing lambda: with
    // Kotlin 2.4 the trailing form infers Unit from the intermediate statements, which turns every
    // `return@withLock "..."` into a type error. `reconcileBody` makes the String contract explicit.
    suspend fun reconcile(id:String,target:String):String {
        val reconcileBody: suspend () -> String = reconcileBody@{
            val a=repository.accounts().firstOrNull{it.id==id} ?: return@reconcileBody "账号已删除"
            val day=SignRepository.day()
            val record=repository.records(day).firstOrNull{it.accountId==id&&it.targetId==target} ?: return@reconcileBody "只可校验今天已有记录"
            // Paused provider: nothing is read, nothing is marked `verifying`, and the stored proof is left as it is.
            if(!open(a.provider))return@reconcileBody pausedReason(a.provider)
            // A provider whose BUILT-IN manifest declares no read-only status endpoint can never conclude: say so instead
            // of marking the row `verifying` and then landing on the interface default, and send nothing.
            // The manifest is the production truth (the shipped adapters are the built-ins); a provider missing from the
            // manifest is not a built-in and is NOT blocked here — its adapter decides, and the engine still keeps the
            // proof intact via [SignRepository.reconcile].
            val manifest=BuiltinProviders.find(a.provider)
            if(manifest!=null && !manifest.canQueryStatus)return@reconcileBody "该平台没有可验证的独立状态查询接口，只读核对无法得出结论；原记录与来源已保留，未重新提交"
            val store=scoped(id)
            val c:JSONObject=try{GameCatalog.scope(a.provider,a.gameIds,store.read(id))}catch(e:Exception){val r=failure(e);if(r.status=="needs_action")repository.auth(id,"needs_action",r.message);return@reconcileBody r.message}
            repository.markVerifying(day,id,target)
            val result=try{adapters[a.provider]?.queryStatus(c,SignTarget(target,record.targetName)){store.save(id,c)} ?: failure(SignError("config","平台尚未支持"))}catch(e:Exception){failure(e)}
            if(result.isOfficialConfirmation) {
                repository.auth(id,"valid","授权可读取官方签到状态；其他角色仍按各自记录判断")
                repository.clearDiscoveryFailure(id)
            }
            else if(result.status=="needs_action")repository.auth(id,"needs_action",result.message)
            val stored=repository.reconcile(day,id,target,result.copy(executionId=UUID.randomUUID().toString()))
            if(result.isOfficialConfirmation) return@reconcileBody "官方状态已确认今日完成：${result.message}"
            val known=when(provenanceOf(record).source){
                SOURCE_OFFICIAL->"已有官方确认的完成记录"
                SOURCE_ACCEPTED->"已有官方接受提交的记录"
                SOURCE_LOCAL_CACHE,SOURCE_LEGACY_LOCAL->"已有本机完成记录"
                else->"已有 ${record.status} 记录"
            }
            if(stored==null) "记录已被清理；本次只读核对未取得新结论（${result.message}）"
            else "$known；本次只读核对未取得新结论，原记录已保留"
        }
        return mutex.withLock { reconcileBody() }
    }
    /** Enumerates games using existing authorization, without submitting any check-in. */
    suspend fun gamesForSelection(id:String):DiscoverOutcome = mutex.withLock {
        val a=repository.accounts().firstOrNull{it.id==id} ?: return@withLock DiscoverOutcome(emptyList(),false,"账号已删除")
        check(!a.bindingPending && a.authState!="pending") { "请先确认当前绑定" }
        // Paused provider: no credential read and no network request; the stored game/role scope is untouched.
        if(!open(a.provider))return@withLock DiscoverOutcome(emptyList(),false,pausedReason(a.provider))
        try {
            val store=scoped(id)
            val original=store.read(id)
            val c=GameCatalog.scope(a.provider,GameCatalog.idsFor(a.provider),original).apply{remove("roleIds")}
            fun persist() {
                val restored=GameCatalog.scope(a.provider,a.gameIds,c)
                if(original.has("roleIds"))restored.put("roleIds",original.get("roleIds"))
                store.save(id,restored)
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
        val a=repository.accounts().firstOrNull{it.id==id}
            ?: return@withLock DiscoverOutcome(emptyList(),false,"账号已删除")
        // Paused provider: the pending draft keeps its `pending` state (it is not silently re-classified) and no
        // credential is read. The caller shows the paused reason; nothing is bound and nothing is deleted.
        if(!open(a.provider))return@withLock DiscoverOutcome(emptyList(),false,pausedReason(a.provider))
        repository.markPendingRoles(id,false)
        try {
            val store=scoped(id)
            val c=GameCatalog.scope(a.provider,a.gameIds,store.read(id)).apply{remove("roleIds")}
            val targets=adapters.getValue(a.provider).targets(c){store.save(id,c)}
            store.save(id,c)
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
    /** Built-in provider switch (first-level gate). Its own prefs file; never stored in the account database. */
    val registry=ProviderRegistry(this.context)
    /** Lab experiments (water-drop glass). Completely independent of accounts, providers and sign rows. */
    val labs=LabStore(this.context)
    /** The production engine is gated by [registry]: a paused provider is never handed a credential. */
    val engine=LocalSignEngine(repository,credentials,officialAdapters(),registry)
    init {repository.recoverInterrupted();bindings.cleanupRetired();bindings.migrateLegacyGames()}
    /**
     * First-time account import. Deliberately NOT provider-gated: pausing a provider stops TASKS, it must never
     * block the user from adding/removing their own account (which would be a data-management regression). The
     * imported draft is `pending` and cannot be signed until roles are confirmed, and the gate blocks it at every
     * execution entry point afterwards. The credential is written only for the freshly generated id.
     */
    suspend fun add(provider:String,nickname:String,c:JSONObject,gameId:String=""):String=LocalSignEngine.mutex.withLock {
        val id=UUID.randomUUID().toString();ScopedCredentialStore(credentials,id).save(id,GameCatalog.scope(provider,gameId,c))
        try {repository.add(provider,nickname,id,gameId)}catch(e:Exception){ScopedCredentialStore(credentials,id).delete(id);throw e}
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
