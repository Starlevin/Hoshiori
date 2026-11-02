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
)
class HomeViewModel(application:Application):AndroidViewModel(application) {
    private val local=LocalServices.get(application)
    private val scheduler=SignScheduler(application)
    var state by mutableStateOf(UiState(reduceMotion=local.prefs.getBoolean("reduce_motion",false)))
        private set
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
    private fun action(block:suspend ()->Unit) {viewModelScope.launch {try{block()}catch(_:kotlinx.coroutines.TimeoutCancellationException){state=state.copy(loginBusy=false,verifyingId=null,submitting=false,loading=false,historyLoading=false,message=Message("角色查询超时，可到账号页继续确认；原账号与记录保留",true))}catch(e:CancellationException){throw e}catch(e:AccountMismatchError){state=state.copy(loginBusy=false,message=Message(e.message.orEmpty(),true))}catch(_:Exception){state=state.copy(verifyingId=null,loginBusy=false,submitting=false,loading=false,historyLoading=false,message=Message("本地操作失败，请检查授权或稍后重试",true))}}}
    private suspend fun reload() {
        val snapshot=withContext(Dispatchers.IO) {
            val day=SignRepository.day();val records=local.repository.records(day)
            Overview(day,SignRepository.now(),BuildConfig.VERSION_NAME,local.prefs.getString("daily_time","08:10")!!,"",local.repository.accounts(),records,records.groupingBy{it.status}.eachCount(),false,local.prefs.getString("last_finished",null),local.prefs.getString("last_trigger",null),local.prefs.getString("last_started",null))
        }
        val bindings=withContext(Dispatchers.IO){local.repository.bindings()}
        val history=withContext(Dispatchers.IO){local.repository.history()}
        val timeline=withContext(Dispatchers.IO){history.flatMap{day->local.repository.records(day.day).map{TimelineRecord(day.day,it)}}}
        state=state.copy(bindings=bindings,history=history,timeline=timeline,overview=snapshot,loading=false,scheduleEnabled=local.prefs.getBoolean("schedule_enabled",false),dailyTime=local.prefs.getString("daily_time","08:10")!!)
    }
    fun refresh(showLoading:Boolean=true) {if(showLoading)state=state.copy(loading=true);action{reload()}}
    fun runNow(accountIds:List<String>?=null) {
        if(state.jobRunning || state.waitingNetwork || state.submitting) {
            state=state.copy(message=Message("已有本地任务执行或排队，完成后可再次检查"));return
        }
        state=state.copy(submitting=true)
        action {
            withContext(Dispatchers.IO){scheduler.run(accountIds)}
            state=state.copy(submitting=false,message=Message("本地任务已提交；结果见记录页"))
        }
    }
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
        val message=withContext(Dispatchers.IO){local.engine.verify(accountId)};reload();state=state.copy(verifyingId=null,message=Message(message))
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
