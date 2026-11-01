package com.glasssign.app.local

import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import com.glasssign.app.data.GameCatalog
import java.util.UUID

/** A renewal is staged under a separate encrypted account until the owner confirms its roles. */
class LocalAccountBindings(private val repository:SignRepository,private val credentials:LocalCredentialStore) {
    suspend fun prepare(provider:String,nickname:String,c:JSONObject,replaces:String?=null,gameId:String="",gameIds:List<String> = if(gameId.isEmpty())emptyList() else listOf(gameId)):String = LocalSignEngine.mutex.withLock {
        val id=UUID.randomUUID().toString()
        credentials.save(id,GameCatalog.scope(provider,gameIds,c))
        try {repository.createPending(provider,nickname,id,replaces,gameId,gameIds)}
        catch(e:Exception){credentials.delete(id);throw e}
        id
    }

    suspend fun confirm(id:String,selectedGames:List<String>?=null):String = LocalSignEngine.mutex.withLock {
        val pending=repository.pendingBinding(id)
        check(pending!=null && pending.rolesVerified && repository.accounts().any{it.id==id&&it.authState=="pending"}) { "请先读取并确认角色" }
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
        val destination=repository.commitPending(id)
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
        repository.accounts().filter{it.gameIds.isEmpty()&&!it.bindingPending}.forEach { a ->
            try {
                val ids=GameCatalog.inferLegacy(a.provider,credentials.read(a.id))
                if(ids.isNotEmpty())repository.setGames(a.id,ids)
            } catch(_:Exception) { /* Missing authorization remains untouched and can be renewed normally. */ }
        }
    }
    suspend fun updateGames(id:String,selected:List<String>)=LocalSignEngine.mutex.withLock {
        val a=repository.accounts().first{it.id==id}
        check(!a.bindingPending){"请先完成账号绑定"}
        require(selected.isNotEmpty()&&selected.all{GameCatalog.find(it)?.provider==a.provider}){"请选择同一账号体系的游戏"}
        val c=GameCatalog.scope(a.provider,selected,credentials.read(id))
        credentials.save(id,c);repository.setGames(id,selected)
    }

    /** Retry deletion after a process dies between DB commit and staged-secret cleanup. */
    internal fun cleanupRetired() {
        repository.retiredCredentials().forEach {credentials.delete(it);repository.forgetRetiredCredential(it)}
    }
}
