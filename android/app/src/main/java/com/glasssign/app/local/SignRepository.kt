package com.glasssign.app.local

import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.glasssign.app.data.*
import org.json.JSONObject
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

class LocalCredentialStore(private val context:Context) {
    private fun store(id:String)=SecureStore(context.getSharedPreferences("local_secret_$id",Context.MODE_PRIVATE),"glasssign_local_$id")
    fun save(id:String,c:JSONObject) {
        require(c.toString().length <= 65_536) { "授权数据过大" }
        fun checkFields(value:Any?) {
            when(value) {
                is JSONObject -> value.keys().forEach { key ->
                    require(key.lowercase() !in setOf("password","passwd","pwd","smscode","sms_code","captcha","otp")) { "不接受密码或验证码" }
                    checkFields(value.opt(key))
                }
                is org.json.JSONArray -> (0 until value.length()).forEach { checkFields(value.opt(it)) }
            }
        }
        checkFields(c)
        val copy=JSONObject(c.toString());copy.remove("_accessToken");store(id).saveToken(copy.toString()) }
    fun read(id:String):JSONObject = store(id).readToken()?.let{JSONObject(it)} ?: throw SignError("auth","本机授权缺失或密钥失效，请重新导入")
    fun delete(id:String) { store(id).clearAll() }
}
data class PendingBinding(val accountId:String,val replacesAccountId:String?,val rolesVerified:Boolean,val availableGameIds:List<String> = emptyList())

class SignRepository(context:Context,name:String="local_sign_v4.db") : SQLiteOpenHelper(context,name,null,4) {
    companion object { val TZ:ZoneId=ZoneId.of("Asia/Shanghai");fun day()=ZonedDateTime.now(TZ).toLocalDate().toString();fun now()=ZonedDateTime.now(TZ).toOffsetDateTime().toString() }
    override fun onCreate(db:SQLiteDatabase) {
        db.execSQL("CREATE TABLE accounts(id TEXT PRIMARY KEY, provider TEXT NOT NULL, nickname TEXT NOT NULL, enabled INTEGER NOT NULL, created TEXT NOT NULL, auth TEXT NOT NULL DEFAULT 'unknown', auth_message TEXT NOT NULL DEFAULT '', verified TEXT NOT NULL DEFAULT '', game TEXT NOT NULL DEFAULT '', games TEXT NOT NULL DEFAULT '')")
        db.execSQL("CREATE TABLE records(day TEXT NOT NULL, account TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE, target TEXT NOT NULL, name TEXT NOT NULL, status TEXT NOT NULL, message TEXT NOT NULL, attempts INTEGER NOT NULL, updated TEXT NOT NULL, stamp INTEGER NOT NULL, PRIMARY KEY(day,account,target))")
        createPendingBindings(db)
    }
    override fun onConfigure(db:SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
    private fun createPendingBindings(db:SQLiteDatabase) {
        db.execSQL("CREATE TABLE pending_bindings(account TEXT PRIMARY KEY REFERENCES accounts(id) ON DELETE CASCADE, replaces_account TEXT UNIQUE REFERENCES accounts(id) ON DELETE RESTRICT, roles_verified INTEGER NOT NULL DEFAULT 0, available_games TEXT NOT NULL DEFAULT '')")
        db.execSQL("CREATE TABLE retired_credentials(account TEXT PRIMARY KEY)")
    }
    override fun onUpgrade(db:SQLiteDatabase,oldVersion:Int,newVersion:Int) {
        check(oldVersion in 1..3 && newVersion==4) { "Unsupported schema migration" }
        if(oldVersion==1) {
            createPendingBindings(db)
            db.execSQL("UPDATE accounts SET enabled=0,auth='needs_action',auth_message='旧版授权未完成，请重新登录；原账号与记录已保留' WHERE auth='pending'")
        }
        if(oldVersion<3)db.execSQL("ALTER TABLE accounts ADD COLUMN game TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE accounts ADD COLUMN games TEXT NOT NULL DEFAULT ''")
        db.execSQL("UPDATE accounts SET games=game WHERE game!=''")
        if(oldVersion>=2)db.execSQL("ALTER TABLE pending_bindings ADD COLUMN available_games TEXT NOT NULL DEFAULT ''")
    }
    fun add(provider:String,nickname:String,id:String=UUID.randomUUID().toString(),gameId:String="",gameIds:List<String> = if(gameId.isEmpty())emptyList() else listOf(gameId)):String {
        require(provider in setOf("miyoushe","skland","tagged","kurobbs"))
        require(gameIds.all{GameCatalog.find(it)?.provider==provider}) { "游戏与平台不匹配" }
        writableDatabase.insertOrThrow("accounts",null,ContentValues().apply{put("id",id);put("provider",provider);put("nickname",nickname.take(40));put("enabled",1);put("created",now());put("game",gameIds.singleOrNull().orEmpty());put("games",gameIds.distinct().joinToString(","))});return id
    }
    fun accounts():List<Account> {
        val pending=pendingBindings().map{it.accountId}.toSet()
        return readableDatabase.rawQuery("SELECT * FROM accounts ORDER BY created",null).use { c ->
            buildList {
                while(c.moveToNext()) {
                    add(Account(c.getString(0),c.getString(1),c.getString(2),c.getInt(3)==1,c.getString(4),c.getString(5),c.getString(6),c.getString(7),c.getString(0) in pending,c.getString(8),c.getString(9).split(',').filter{it.isNotEmpty()}))
                }
            }
        }
    }

    fun enabled(id:String,value:Boolean) {
        // Account switches cannot bypass the separate role-confirmation step.
        writableDatabase.execSQL("UPDATE accounts SET enabled=? WHERE id=? AND (?=0 OR (auth!='pending' AND NOT EXISTS (SELECT 1 FROM pending_bindings WHERE account=accounts.id)))",arrayOf(if(value)1 else 0,id,if(value)1 else 0))
    }
    fun auth(id:String,state:String,message:String) { writableDatabase.execSQL("UPDATE accounts SET auth=?,auth_message=?,verified=? WHERE id=?",arrayOf(state,message,now(),id)) }
    fun delete(id:String) { writableDatabase.delete("accounts","id=?",arrayOf(id)) }
    fun createPending(provider:String,nickname:String,id:String,replaces:String?,gameId:String="",gameIds:List<String> = if(gameId.isEmpty())emptyList() else listOf(gameId)) {
        val db=writableDatabase;db.beginTransaction()
        try {
            if(replaces!=null) {
                val original=accounts().firstOrNull{it.id==replaces}
                require(original!=null && original.provider==provider && original.authState!="pending" && !original.bindingPending) { "原账号不存在或尚未完成绑定" }
                check(pendingBindings().none{it.replacesAccountId==replaces}) { "请先处理上次待确认授权" }
            }
            add(provider,nickname,id,gameId,gameIds);enabled(id,false);auth(id,"pending","正在读取角色，确认前不会执行签到")
            db.insertOrThrow("pending_bindings",null,ContentValues().apply{put("account",id);put("replaces_account",replaces)})
            db.setTransactionSuccessful()
        } finally {db.endTransaction()}
    }
    fun pendingBindings():List<PendingBinding> = readableDatabase.rawQuery("SELECT account,replaces_account,roles_verified,available_games FROM pending_bindings",null).use { c ->
        buildList {while(c.moveToNext())add(PendingBinding(c.getString(0),if(c.isNull(1))null else c.getString(1),c.getInt(2)==1,c.getString(3).split(',').filter{it.isNotEmpty()}))}
    }
    fun pendingBinding(id:String):PendingBinding? = pendingBindings().firstOrNull{it.accountId==id}
    fun markPendingRoles(id:String,verified:Boolean,available:List<String> = emptyList()) {
        writableDatabase.execSQL("UPDATE pending_bindings SET roles_verified=?,available_games=? WHERE account=?",arrayOf(if(verified)1 else 0,if(verified)available.distinct().joinToString(",") else "",id))
    }
    fun setGames(id:String,ids:List<String>) {
        val a=accounts().first{it.id==id}
        require(ids.isNotEmpty()&&ids.all{GameCatalog.find(it)?.provider==a.provider}){"请至少选择一个同账号体系的游戏"}
        writableDatabase.execSQL("UPDATE accounts SET game=?,games=? WHERE id=?",arrayOf(ids.distinct().singleOrNull().orEmpty(),ids.distinct().joinToString(","),id))
    }
    fun validatePendingGames(id:String,ids:List<String>) {
        val p=pendingBinding(id)
        check(p!=null&&p.rolesVerified&&ids.isNotEmpty()&&ids.all{it in p.availableGameIds}){"请选择已经读取到角色的游戏"}
    }
    fun acknowledgeSigned(day:String,account:String,target:SignTarget) {
        writableDatabase.execSQL("UPDATE records SET status='already',message='已签到；本机已有当天成功记录，未重复提交',updated=? WHERE day=? AND account=? AND target=? AND status IN ('success','already')",arrayOf(now(),day,account,target.id))
    }
    /** Keeps the original account id/history and its paused/enabled preference on renewal. */
    fun commitPending(id:String):String {
        val db=writableDatabase;db.beginTransaction()
        try {
            val pending=pendingBinding(id)
            check(pending!=null && pending.rolesVerified && accounts().any{it.id==id&&it.authState=="pending"}) { "请先读取并确认角色" }
            val destination=pending.replacesAccountId ?: id
            db.execSQL("UPDATE accounts SET game=(SELECT game FROM accounts WHERE id=?),games=(SELECT games FROM accounts WHERE id=?) WHERE id=?",arrayOf(id,id,destination))
            auth(destination,"valid","已登录并确认角色；签到结果以官方记录为准")
            // The new session already enumerated roles. Do not unlock uncertain sign submissions.
            clearDiscoveryFailure(destination)
            if(pending.replacesAccountId==null) {
                db.delete("pending_bindings","account=?",arrayOf(id));enabled(id,true)
            } else {
                db.insertOrThrow("retired_credentials",null,ContentValues().apply{put("account",id)})
                delete(id)
            }
            db.setTransactionSuccessful();return destination
        } finally {db.endTransaction()}
    }
    fun retiredCredentials():List<String> = readableDatabase.rawQuery("SELECT account FROM retired_credentials",null).use { c -> buildList{while(c.moveToNext())add(c.getString(0))} }
    fun forgetRetiredCredential(id:String) { writableDatabase.delete("retired_credentials","account=?",arrayOf(id)) }
    fun recoverInterrupted() { writableDatabase.execSQL("UPDATE records SET status='needs_action',message='上次执行中断，结果不确定；请先到官方应用核对' WHERE status='running'") }
    /** Unique (Beijing date, community account, task type + role) and atomic claim. */
    fun claim(day:String,account:String,target:SignTarget,stamp:Long=System.currentTimeMillis()):Boolean {
        val db=writableDatabase;db.beginTransaction()
        try {
            var attempt=1
            db.rawQuery("SELECT status,attempts,stamp FROM records WHERE day=? AND account=? AND target=?",arrayOf(day,account,target.id)).use{c->
                if(c.moveToFirst()) {
                    val status=c.getString(0);attempt=c.getInt(1)+1
                    val cooldown=if(status=="waiting_network")120_000L else 1_800_000L
                    if(status in setOf("success","already","needs_action","running") || attempt>3 || stamp-c.getLong(2)<cooldown)return false
                }
            }
            val v=ContentValues().apply {put("day",day);put("account",account);put("target",target.id);put("name",target.name);put("status","running");put("message","本地正在检查");put("attempts",attempt);put("updated",now());put("stamp",stamp)}
            db.insertWithOnConflict("records",null,v,SQLiteDatabase.CONFLICT_REPLACE);db.setTransactionSuccessful();return true
        } finally {db.endTransaction()}
    }
    fun finish(day:String,account:String,target:SignTarget,r:SignResult) { writableDatabase.execSQL("UPDATE records SET status=?,message=?,updated=? WHERE day=? AND account=? AND target=?",arrayOf(r.status,r.message,now(),day,account,target.id)) }
    fun clearDiscoveryFailure(id:String) { writableDatabase.delete("records","account=? AND day=? AND target='discovery' AND status!='running'",arrayOf(id,day())) }
    fun records(day:String):List<CheckRecord> = readableDatabase.rawQuery("SELECT account,target,name,status,message,attempts,updated FROM records WHERE day=? ORDER BY account,target",arrayOf(day)).use{c->buildList{while(c.moveToNext())add(CheckRecord(c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4),c.getInt(5),c.getString(6)))}}
    fun history():List<HistoryDay> = readableDatabase.rawQuery("SELECT day,COUNT(*),SUM(status IN ('success','already')),SUM(status IN ('failed','needs_action','waiting_network')) FROM records GROUP BY day ORDER BY day DESC LIMIT 90",null).use{c->buildList{while(c.moveToNext())add(HistoryDay(c.getString(0),c.getInt(1),c.getInt(2),c.getInt(3)))}}
}
