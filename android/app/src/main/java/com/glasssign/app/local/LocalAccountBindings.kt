package com.glasssign.app.local

import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import com.glasssign.app.data.GameCatalog
import java.util.UUID

class AccountMismatchError:IllegalStateException("当前登录的是其他账号。请切换回原账号，或取消后新增独立账号。")

/** A renewal is staged under a separate encrypted account until the owner confirms its roles. */
class LocalAccountBindings(private val repository:SignRepository,private val credentials:LocalCredentialStore) {
    suspend fun prepare(provider:String,nickname:String,c:JSONObject,replaces:String?=null,gameId:String="",gameIds:List<String> = if(gameId.isEmpty())emptyList() else listOf(gameId)):String = LocalSignEngine.mutex.withLock {
        val id=UUID.randomUUID().toString()
        credentials.save(id,GameCatalog.scope(provider,gameIds,c))
        try {repository.createPending(provider,nickname,id,replaces,gameId,gameIds)}
        catch(e:Exception){credentials.delete(id);throw e}
        id
    }

    suspend fun confirm(id:String,selectedGames:List<String>?=null,selectedRoleIds:Set<String>?=null,autoSign:Boolean=true):String = LocalSignEngine.mutex.withLock {
        val pending=repository.pendingBinding(id)
        check(pending!=null && pending.rolesVerified && repository.accounts().any{it.id==id&&it.authState=="pending"}) { "请先读取并确认角色" }
        val account=repository.accounts().first{it.id==id}
        val duplicate=repository.duplicate(account.provider,account.providerUserId,id)
        if(pending.replacesAccountId==null && duplicate!=null) {
            credentials.delete(id);repository.delete(id);return@withLock duplicate.id
        }
        if(pending.replacesAccountId!=null) {
            val original=repository.accounts().first{it.id==pending.replacesAccountId}
            if(original.providerUserId.isNotBlank()&&account.providerUserId.isNotBlank()&&original.providerUserId!=account.providerUserId)throw AccountMismatchError()
        }
        val selectedRoles=selectedRoleIds?.let{ids->
            val roles=repository.roleChoices(id).filter{it.targetId in ids && (selectedGames==null || it.gameId in selectedGames)}
            check(roles.isNotEmpty() && roles.map{it.targetId}.toSet()==ids){"请选择已经读取的角色"};roles
        }
        if(selectedGames!=null) {
            repository.validatePendingGames(id,selectedGames)
            val a=repository.accounts().first{it.id==id}
            credentials.save(id,GameCatalog.scope(a.provider,selectedGames,credentials.read(id)))
            repository.setGames(id,selectedGames)
        }
        if(pending.replacesAccountId!=null) {
            // Both accounts remain available if the commit is interrupted; repeating confirmation is safe.
            credentials.save(pending.replacesAccountId,credentials.read(id))
        }
        val destination=repository.commitPending(id,selectedRoles,autoSign)
        cleanupRetired()
        destination
    }

    suspend fun cancel(id:String) = LocalSignEngine.mutex.withLock {
        // Old builds could have overwritten a real account in-place. Never delete such an account here.
        check(repository.pendingBinding(id)!=null) { "无法撤回旧版授权，请保留账号并重新登录" }
        credentials.delete(id);repository.delete(id)
    }

    suspend fun deleteAccount(id:String) = LocalSignEngine.mutex.withLock {
        repository.pendingBindings().filter{it.replacesAccountId==id}.forEach {
            credentials.delete(it.accountId);repository.delete(it.accountId)
        }
        credentials.delete(id);repository.delete(id)
    }

    suspend fun clearCredentials() = LocalSignEngine.mutex.withLock {
        cleanupRetired()
        repository.pendingBindings().forEach {credentials.delete(it.accountId);repository.delete(it.accountId)}
        repository.accounts().forEach {
            credentials.delete(it.id);repository.enabled(it.id,false);repository.auth(it.id,"needs_action","本机授权已清除")
        }
    }

    /** Reads only the existing encrypted credential's game options; never exports its contents. */
    fun migrateLegacyGames() {
        repository.accounts().filter{it.providerUserId.isBlank()&&!it.bindingPending}.forEach{a->
            try { repository.identity(a.id,AccountIdentity.read(a.provider,credentials.read(a.id))) }catch(_:Exception){}
        }
        repository.accounts().filter{it.gameIds.isEmpty()&&!it.bindingPending}.forEach { a ->
            try {
                val ids=GameCatalog.inferLegacy(a.provider,credentials.read(a.id))
                if(ids.isNotEmpty())repository.setGames(a.id,ids)
            } catch(_:Exception) { /* Missing authorization remains untouched and can be renewed normally. */ }
        }
    }
    suspend fun updateGames(id:String,selected:List<String>,roleIds:Set<String>?=null,autoSign:Boolean?=null)=LocalSignEngine.mutex.withLock {
        val a=repository.accounts().first{it.id==id}
        check(!a.bindingPending){"请先完成账号绑定"}
        require(selected.isNotEmpty()&&selected.all{GameCatalog.find(it)?.provider==a.provider}){"请选择同一账号体系的游戏"}
        val selectedRoles=roleIds?.let{ids->
            val roles=repository.roleChoices(id).filter{it.targetId in ids&&it.gameId in selected}
            check(roles.isNotEmpty()&&roles.map{it.targetId}.toSet()==ids){"请选择已经读取的角色"};roles
        }
        val c=GameCatalog.scope(a.provider,selected,credentials.read(id)).apply{if(selectedRoles!=null)remove("roleIds")}
        credentials.save(id,c)
        val db=repository.writableDatabase;db.beginTransaction()
        try {
            repository.setGames(id,selected)
            if(selectedRoles!=null)repository.setBindings(id,selectedRoles,autoSign ?: a.autoSignEnabled)
            if(autoSign!=null)repository.autoEnabled(id,autoSign)
            db.setTransactionSuccessful()
        }finally{db.endTransaction()}
    }

    suspend fun revoke(id:String)=LocalSignEngine.mutex.withLock {
        check(repository.accounts().any{it.id==id&&!it.bindingPending})
        credentials.delete(id);repository.auth(id,"needs_action","授权已解除；账号与历史记录保留")
    }
    /** Retry deletion after a process dies between DB commit and staged-secret cleanup. */
    internal fun cleanupRetired() {
        repository.retiredCredentials().forEach {credentials.delete(it);repository.forgetRetiredCredential(it)}
    }
}
