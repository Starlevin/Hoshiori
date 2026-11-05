package com.glasssign.app

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.glasssign.app.data.*
import com.glasssign.app.local.*
import com.glasssign.app.login.PendingLogin
import androidx.work.WorkInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

data class TimelineRecord(val day:String,val record:CheckRecord)

data class UiState(
    val configured:Boolean=true,val hasToken:Boolean=false,val serverUrl:String="",val serverLabel:String="本地执行",
    val reduceMotion:Boolean=false,val overview:Overview?=null,val history:List<HistoryDay> = emptyList(),
    val selectedDay:String?=null,val dayRecords:DayRecords?=null,val loading:Boolean=false,val jobRunning:Boolean=false,
    val verifyingId:String?=null,val historyLoading:Boolean=false,val message:Message?=null,
    val scheduleEnabled:Boolean=false,val dailyTime:String="08:10",val waitingNetwork:Boolean=false,
    val bindings:List<GameBinding> = emptyList(),val timeline:List<TimelineRecord> = emptyList(),val focusAccountId:String?=null,
    val pendingLogin:PendingLogin?=null,val loginBusy:Boolean=false,val submitting:Boolean=false,
    /** Today's per-game/community/authorization feedback, derived once per refresh (data/HomeFeedback.kt). */
    val feedback:HomeFeedback?=null,
    /** The last worker wrote an explicit failure reason (timeout etc.); shown instead of a generic message. */
    val workerError:String?=null,
    /** A specific, fixable problem raised by the last operation. The home page renders its own buttons for it. */
    val fix:AuthFix?=null,
    val disabledProviders:Set<String> = emptySet(),
)

/**
 * One concrete, fixable problem (never a generic "本地操作失败"): the home page shows the cause plus the buttons that
 * can actually resolve it. [readOnly] buttons (reconcile / verify) never submit a check-in.
 */
data class AuthFix(val accountId:String,val provider:String,val nickname:String?=null,val cause:String,val retryable:Boolean=false)

class HomeViewModel(application:Application):AndroidViewModel(application) {
    private val local=LocalServices.get(application)
    private val scheduler=SignScheduler(application)
    var state by mutableStateOf(UiState(reduceMotion=local.prefs.getBoolean("reduce_motion",false)))
        private set
    private val providerListener=local.registry.listen{refresh(false)}
    override fun onCleared() {
        local.registry.stopListening(providerListener)
        super.onCleared()
    }
    init {
        refresh()
        viewModelScope.launch {
            scheduler.manager.getWorkInfosForUniqueWorkFlow(SignScheduler.RUN).map { infos -> infos.map { it.id to it.state } }.distinctUntilChanged().collect { infos ->
                val active=infos.any{it.second==WorkInfo.State.RUNNING}
                state=state.copy(jobRunning=active,waitingNetwork=infos.any{it.second==WorkInfo.State.ENQUEUED || it.second==WorkInfo.State.BLOCKED})
                refresh(false)
            }
        }
    }
    /**
     * Runs an operation and turns any failure into an explicit cause + a fix entry (data/HomeFeedback.kt
     * [HomeFeedback]), never just a transient Snackbar. The generic wording is kept only as the fallback when no
     * account can be attributed.
     */
    private fun action(fixAccount:String?=null,block:suspend ()->Unit) {
        viewModelScope.launch {
            try { block() }
            catch(_:kotlinx.coroutines.TimeoutCancellationException) {
                operationFailed(fixAccount,"操作超时（官方接口未在时限内返回），原账号与记录保留")
            }
            catch(e:CancellationException) { throw e }
            catch(e:AccountMismatchError) { state=state.copy(loginBusy=false,message=Message(e.message.orEmpty(),true)) }
            catch(e:Exception) { operationFailed(fixAccount,describeFailure(e)) }
        }
    }
    private fun operationFailed(account:String?,cause:String) {
        state=state.copy(verifyingId=null,loginBusy=false,submitting=false,loading=false,historyLoading=false,
            fix=fixFor(account,cause),message=Message(cause,true))
    }
    /** Keeps the specific cause from the adapters ("塔吉多角色查询未完成（500）…") instead of a generic sentence. */
    private fun describeFailure(e:Exception):String {
        val raw=e.message.orEmpty().trim()
        val text=if(raw.isBlank())"本地操作失败" else raw
        return if(text.length>90)text.take(88)+"…" else text
    }
    private fun fixFor(accountId:String?,cause:String):AuthFix? {
        val account=accountId?.let{id->state.overview?.accounts?.firstOrNull{it.id==id}}
        return AuthFix(accountId.orEmpty(),account?.provider.orEmpty(),account?.nickname,cause,retryable=false)
    }
    private suspend fun reload() {
        val disabledProviders=local.registry.snapshot().filter{!it.enabled}.map{it.manifest.id}.toSet()
        val snapshot=withContext(Dispatchers.IO) {
            val day=SignRepository.day();val records=local.repository.records(day)
            Overview(day,SignRepository.now(),BuildConfig.VERSION_NAME,local.prefs.getString("daily_time","08:10")!!,"",local.repository.accounts(),records,records.groupingBy{it.status}.eachCount(),false,local.prefs.getString("last_finished",null),local.prefs.getString("last_trigger",null),local.prefs.getString("last_started",null))
        }
        val bindings=withContext(Dispatchers.IO){local.repository.bindings()}
        val history=withContext(Dispatchers.IO){local.repository.history()}
        val timeline=withContext(Dispatchers.IO){history.flatMap{day->local.repository.records(day.day).map{TimelineRecord(day.day,it)}}}
        val workerError=local.prefs.getString("last_worker_error",null)
        val storedBook=local.prefs.getString(HOME_FEEDBACK_PREFS,null)
        val book=observeRun(readBook(storedBook),snapshot.lastStartedAt,snapshot.lastFinishedAt,state.jobRunning)
        // Only one finished run per new `last_finished` value: a refresh loop cannot multiply result cards.
        if(serializeBook(book)!=storedBook)local.prefs.edit().putString(HOME_FEEDBACK_PREFS,serializeBook(book)).apply()
        val feedback=summarize(
            day=snapshot.date,
            accounts=snapshot.accounts.map{AuthAccount(it.id,it.provider,it.nickname,it.enabled&&it.provider !in disabledProviders,it.authState,it.authMessage,it.bindingPending,it.roleScope,it.gameIds)},
            // Enabled bindings of ENABLED accounts only: a paused account may not raise the "应签到" denominator.
            tasks=bindings.filter{it.enabled&&snapshot.accounts.firstOrNull{a->a.id==it.accountId}?.let{a->a.enabled&&a.provider !in disabledProviders}==true}.distinctBy{it.accountId to it.targetId},
            records=snapshot.records,
            accountNames=snapshot.accounts.associate{it.id to it.nickname},
            running=state.jobRunning||state.submitting,
            queued=state.waitingNetwork,
            lastStartedAt=snapshot.lastStartedAt,
            lastFinishedAt=snapshot.lastFinishedAt,
        ).let{base->base.copy(result=runResult(base,local.prefs.getString("last_trigger","").orEmpty(),snapshot.lastFinishedAt.orEmpty(),book.sequence,workerError))}
        state=state.copy(bindings=bindings,history=history,timeline=timeline,overview=snapshot,loading=false,feedback=feedback,workerError=workerError,scheduleEnabled=local.prefs.getBoolean("schedule_enabled",false),dailyTime=local.prefs.getString("daily_time","08:10")!!,disabledProviders=disabledProviders)
    }
    fun refresh(showLoading:Boolean=true) {if(showLoading)state=state.copy(loading=true);action{reload()}}
    fun runNow(accountIds:List<String>?=null) {
        if(state.jobRunning || state.waitingNetwork || state.submitting) {
            state=state.copy(message=Message("已有本地任务执行或排队，完成后可再次检查"));return
        }
        val selected=state.overview?.accounts.orEmpty().filter{accountIds==null||it.id in accountIds}
        if(selected.isNotEmpty()&&selected.none{local.registry.isExecutionAllowed(it.provider)}) {
            state=state.copy(message=Message("所选平台适配器已暂停，请在设置 → 实验室中启用"));return
        }
        state=state.copy(submitting=true,fix=null)
        action {
            withContext(Dispatchers.IO){scheduler.run(accountIds)}
            // The result is read back from the records on the next reload; the home page shows the summary card.
            state=state.copy(submitting=false)
            reload()
        }
    }
    /** One account's game/role retry, used by the per-row 重试 in the home feedback card. */
    fun retryAccount(accountId:String) {runNow(listOf(accountId))}
    /** The fix entry was dismissed by the user (it is derived from a failure, not from stored state). */
    fun dismissFix() {state=state.copy(fix=null)}
    /**
     * Re-reads the authorization + roles of ONE account (read-only: `local.engine.verify` submits no check-in) and
     * turns the outcome into either a pending binding sheet or an explicit fix entry with the real cause.
     */
    fun verifyAccount(accountId:String) {state=state.copy(verifyingId=accountId,fix=null);action(accountId) {
        val a=withContext(Dispatchers.IO){local.repository.accounts().firstOrNull{it.id==accountId}}
        // A pending draft needs the binding sheet, not a probe: keep the existing path (it owns its sheet).
        if(a!=null&&(a.bindingPending||a.authState=="pending")) {
            state=state.copy(verifyingId=null)
            verifyAuthorization(accountId)
            return@action
        }
        val text=withContext(Dispatchers.IO){local.engine.verify(accountId)}
        val after=withContext(Dispatchers.IO){local.repository.accounts().firstOrNull{it.id==accountId}}
        reload()
        val usable=after?.authState=="valid"
        state=state.copy(verifyingId=null,fix=if(usable)null else fixFor(accountId,text))
    }}
    fun addAccount(provider:String,nickname:String,credentials:JSONObject,gameId:String="") {action {
        val id=withContext(Dispatchers.IO){local.add(provider,nickname,credentials,gameId)};reload();runNow(listOf(id))
    }}
    /** Session captured on the official page: store it, read the real roles, then ask the user. */
    fun beginLogin(provider:String,nickname:String,credentials:JSONObject,replaceId:String?=null,gameId:String="") {action {
        state=state.copy(loginBusy=true)
        val id=withContext(Dispatchers.IO){
            local.beginLogin(provider,nickname,credentials,replaceId,"",GameCatalog.idsFor(provider))
        }
        val outcome=kotlinx.coroutines.withTimeout(120_000L){withContext(Dispatchers.IO){local.engine.discover(id)}}
        reload()
        val original=state.overview?.accounts?.firstOrNull{it.id==replaceId}
        val selection=initialBindingSelection(original,state.bindings,outcome.roleChoices)
        state=state.copy(loginBusy=false,pendingLogin=PendingLogin(id,provider,nickname,outcome.roles,outcome.message,outcome.availableGameIds,selection.games,roleChoices=outcome.roleChoices,selectedRoleIds=selection.roles,providerUserId=outcome.providerUserId,autoSignEnabled=selection.autoSign))
    }}
    fun togglePendingGame(id:String,selected:Boolean) {
        val p=state.pendingLogin ?: return
        if(state.loginBusy || id !in p.availableGameIds)return
        state=state.copy(pendingLogin=p.copy(selectedGameIds=if(selected)p.selectedGameIds+id else p.selectedGameIds-id,
            selectedRoleIds=if(selected)p.selectedRoleIds+p.roleChoices.filter{it.gameId==id}.map{it.targetId} else p.selectedRoleIds-p.roleChoices.filter{it.gameId==id}.map{it.targetId}.toSet(),confirming=false))
    }
    fun togglePendingRole(id:String,selected:Boolean) {
        val p=state.pendingLogin ?: return
        if(state.loginBusy||p.roleChoices.none{it.targetId==id&&it.gameId in p.selectedGameIds})return
        state=state.copy(pendingLogin=p.copy(selectedRoleIds=if(selected)p.selectedRoleIds+id else p.selectedRoleIds-id,confirming=false))
    }
    fun pendingAuto(value:Boolean) {state.pendingLogin?.let{state=state.copy(pendingLogin=it.copy(autoSignEnabled=value))}}
    fun reviewBinding() {state.pendingLogin?.takeIf{it.ready}?.let{state=state.copy(pendingLogin=it.copy(confirming=!it.confirming))}}
    fun replacementForPending():String?=state.pendingLogin?.let{local.repository.pendingBinding(it.accountId)?.replacesAccountId}
    fun discardDraft(id:String) {action{withContext(Dispatchers.IO){local.abandonLogin(id)};reload()}}
    fun clearFocus(){state=state.copy(focusAccountId=null)}
    fun rename(id:String,name:String) {action{withContext(Dispatchers.IO){LocalSignEngine.mutex.withLock{local.repository.rename(id,name)}};reload()}}
    fun revoke(id:String) {action{withContext(Dispatchers.IO){local.bindings.revoke(id)};reload();state=state.copy(message=Message("已解除授权，账号、暂停偏好和历史记录均保留"))}}
    fun clearRecords(id:String) {action{withContext(Dispatchers.IO){LocalSignEngine.mutex.withLock{local.repository.deleteRecords(id)}};reload()}}
    fun bindingEnabled(id:String,target:String,value:Boolean) {action{withContext(Dispatchers.IO){LocalSignEngine.mutex.withLock{local.repository.bindingEnabled(id,target,value)}};reload()}}
    fun autoEnabled(id:String,value:Boolean) {action{withContext(Dispatchers.IO){LocalSignEngine.mutex.withLock{local.repository.autoEnabled(id,value)}};reload()}}
    fun reconcile(id:String,target:String) {action{val text=withContext(Dispatchers.IO){local.engine.reconcile(id,target)};reload();state=state.copy(message=Message(text))}}
    fun manageGames(accountId:String) {action {
        val a=withContext(Dispatchers.IO){local.repository.accounts().firstOrNull{it.id==accountId}} ?: return@action
        if(a.bindingPending || a.authState=="pending") {verifyAuthorization(accountId);return@action}
        state=state.copy(loginBusy=true)
        val outcome=kotlinx.coroutines.withTimeout(120_000L){withContext(Dispatchers.IO){local.engine.gamesForSelection(accountId)}}
        state=state.copy(loginBusy=false,pendingLogin=PendingLogin(a.id,a.provider,a.nickname,outcome.roles,outcome.message,outcome.availableGameIds,a.gameIds.filter{it in outcome.availableGameIds}.toSet(),true,outcome.roleChoices,
            if(a.roleScope)state.bindings.filter{it.accountId==a.id}.map{it.targetId}.toSet().intersect(outcome.roleChoices.map{it.targetId}.toSet()) else outcome.roleChoices.filter{it.gameId in a.gameIds}.map{it.targetId}.toSet(),outcome.providerUserId,a.autoSignEnabled))
    }}
    fun reportLoginProblem(text:String) {state=state.copy(message=Message(text,true))}
    fun confirmLogin() {action {
        val pending=state.pendingLogin ?: return@action
        if(!pending.ready) {
            state=state.copy(message=Message("尚未读取到可确认角色，不能完成绑定",true))
            return@action
        }
        state=state.copy(loginBusy=true)
        val confirmedId=withContext(Dispatchers.IO){
            if(pending.existingAccount) {
                check(pending.selectedGameIds.isNotEmpty() && pending.selectedGameIds.all{it in pending.availableGameIds})
                local.bindings.updateGames(pending.accountId,pending.selectedGameIds.toList(),pending.selectedRoleIds.takeIf{pending.roleChoices.isNotEmpty()},pending.autoSignEnabled);pending.accountId
            } else local.confirmLogin(pending.accountId,pending.selectedGameIds.toList().takeIf{pending.availableGameIds.isNotEmpty()},pending.selectedRoleIds.takeIf{pending.roleChoices.isNotEmpty()},pending.autoSignEnabled)
        }
        state=state.copy(loginBusy=false,pendingLogin=null,focusAccountId=if(pending.existingAccount||confirmedId!=pending.accountId)confirmedId else null,message=Message(if(pending.existingAccount)"已保存游戏与角色；暂停状态保持不变" else if(confirmedId!=pending.accountId)"此平台账号已绑定，已定位原账号；原授权与记录保留" else "绑定完成；可按需立即签到"))
        reload()
    }}
    fun cancelLogin() {action {
        val pending=state.pendingLogin ?: return@action
        if(!pending.existingAccount)withContext(Dispatchers.IO){local.abandonLogin(pending.accountId)}
        state=state.copy(pendingLogin=null,loginBusy=false,message=Message("已取消本次绑定；原有账号与记录保持不变"))
        reload()
    }}
    fun updateCredentials(accountId:String,provider:String,nickname:String,credentials:JSONObject) {action {
        withContext(Dispatchers.IO){LocalSignEngine.mutex.withLock{val a=local.repository.accounts().first{it.id==accountId};local.credentials.save(accountId,GameCatalog.scope(a.provider,a.gameIds,credentials));local.repository.clearDiscoveryFailure(accountId);local.repository.auth(accountId,"unknown","授权已更新，请重新验证")}}
        reload();state=state.copy(message=Message("本机授权已更新；结果不确定的任务请先在官方应用核对"))
    }}
    fun setEnabled(accountId:String,enabled:Boolean) {action {withContext(Dispatchers.IO){LocalSignEngine.mutex.withLock{local.repository.enabled(accountId,enabled)}};reload()}}
    fun verifyAuthorization(accountId:String) {state=state.copy(verifyingId=accountId);action {
        val account=withContext(Dispatchers.IO){local.repository.accounts().firstOrNull{it.id==accountId}}
        if(account!=null && (account.bindingPending || account.authState=="pending")) {
            val outcome=kotlinx.coroutines.withTimeout(120_000L){withContext(Dispatchers.IO){local.engine.discover(accountId)}}
            reload()
            val replaced=withContext(Dispatchers.IO){local.repository.pendingBinding(account.id)?.replacesAccountId}
            val original=state.overview?.accounts?.firstOrNull{it.id==replaced}
            val selection=initialBindingSelection(original,state.bindings,outcome.roleChoices)
            state=state.copy(verifyingId=null,pendingLogin=PendingLogin(account.id,account.provider,account.nickname,outcome.roles,outcome.message,outcome.availableGameIds,selection.games,roleChoices=outcome.roleChoices,selectedRoleIds=selection.roles,providerUserId=outcome.providerUserId,autoSignEnabled=selection.autoSign))
            return@action
        }
        val message=withContext(Dispatchers.IO){local.engine.verify(accountId)};reload()
        // A failed probe now keeps its own cause and its fix buttons; a clean probe stays a one-line confirmation.
        val usable=state.overview?.accounts?.firstOrNull{it.id==accountId}?.authState=="valid"
        state=state.copy(verifyingId=null,fix=if(usable)null else fixFor(accountId,message),message=if(usable)Message(message) else null)
    }}
    fun deleteAccount(accountId:String,nickname:String) {action {
        withContext(Dispatchers.IO){local.delete(accountId)};reload();state=state.copy(message=Message("已删除本机账号、授权与记录"))
    }}
    fun clearCredentials() {action {
        withContext(Dispatchers.IO){local.clearCredentials()}
        scheduler.save(false,state.dailyTime);reload();state=state.copy(message=Message("已清除全部本机授权并暂停计划"))
    }}
    fun loadHistory() {state=state.copy(historyLoading=true);action {
        val days=withContext(Dispatchers.IO){local.repository.history()};val day=state.selectedDay ?: days.firstOrNull()?.day ?: SignRepository.day()
        val records=withContext(Dispatchers.IO){local.repository.records(day)}
        state=state.copy(history=days,selectedDay=day,dayRecords=DayRecords(day,records,records.groupingBy{it.status}.eachCount()),historyLoading=false)
    }}
    fun selectDay(day:String) {state=state.copy(selectedDay=day);loadHistory()}
    fun saveSchedule(enabled:Boolean,time:String) {scheduler.save(enabled,time);refresh(false)}
    fun setReduceMotion(value:Boolean) {local.prefs.edit().putBoolean("reduce_motion",value).apply();state=state.copy(reduceMotion=value)}
    fun dismissMessage(at:Long) {if(state.message?.at==at)state=state.copy(message=null)}
}
