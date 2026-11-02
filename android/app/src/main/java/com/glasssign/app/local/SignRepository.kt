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

class SignRepository(private val owner:Context,private val databaseName:String="local_sign_v4.db") : SQLiteOpenHelper(owner,databaseName,null,5) {
    companion object { val TZ:ZoneId=ZoneId.of("Asia/Shanghai");fun day()=ZonedDateTime.now(TZ).toLocalDate().toString();fun now()=ZonedDateTime.now(TZ).toOffsetDateTime().toString() }
    override fun onCreate(db:SQLiteDatabase) {
        db.execSQL("CREATE TABLE accounts(id TEXT PRIMARY KEY, provider TEXT NOT NULL, nickname TEXT NOT NULL, enabled INTEGER NOT NULL, created TEXT NOT NULL, auth TEXT NOT NULL DEFAULT 'unknown', auth_message TEXT NOT NULL DEFAULT '', verified TEXT NOT NULL DEFAULT '', game TEXT NOT NULL DEFAULT '', games TEXT NOT NULL DEFAULT '',provider_uid TEXT NOT NULL DEFAULT '',role_scope INTEGER NOT NULL DEFAULT 0,auto_enabled INTEGER NOT NULL DEFAULT 1)")
        db.execSQL("CREATE TABLE records(day TEXT NOT NULL, account TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE, target TEXT NOT NULL, name TEXT NOT NULL, status TEXT NOT NULL, message TEXT NOT NULL, attempts INTEGER NOT NULL, updated TEXT NOT NULL, stamp INTEGER NOT NULL, started TEXT NOT NULL DEFAULT '', result_source TEXT NOT NULL DEFAULT 'legacy', PRIMARY KEY(day,account,target))")
        createPendingBindings(db)
        createRoleBindings(db)
    }
    override fun onConfigure(db:SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
    private fun createPendingBindings(db:SQLiteDatabase) {
        db.execSQL("CREATE TABLE pending_bindings(account TEXT PRIMARY KEY REFERENCES accounts(id) ON DELETE CASCADE, replaces_account TEXT UNIQUE REFERENCES accounts(id) ON DELETE RESTRICT, roles_verified INTEGER NOT NULL DEFAULT 0, available_games TEXT NOT NULL DEFAULT '')")
        db.execSQL("CREATE TABLE retired_credentials(account TEXT PRIMARY KEY)")
    }
    private fun createRoleBindings(db:SQLiteDatabase) {
        db.execSQL("CREATE TABLE sign_guards(day TEXT NOT NULL,account TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,target TEXT NOT NULL,status TEXT NOT NULL,attempts INTEGER NOT NULL,stamp INTEGER NOT NULL,result_source TEXT NOT NULL DEFAULT 'legacy',PRIMARY KEY(day,account,target))")
        db.execSQL("CREATE TABLE game_bindings(account TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,target TEXT NOT NULL,game TEXT NOT NULL,name TEXT NOT NULL,enabled INTEGER NOT NULL DEFAULT 1,auto_sign INTEGER NOT NULL DEFAULT 1,PRIMARY KEY(account,target))")
        db.execSQL("CREATE TABLE discovered_roles(account TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,target TEXT NOT NULL,game TEXT NOT NULL,name TEXT NOT NULL,PRIMARY KEY(account,target))")
    }
    override fun onUpgrade(db:SQLiteDatabase,oldVersion:Int,newVersion:Int) {
        check(oldVersion in 1..4 && newVersion==5) { "Unsupported schema migration" }
        // SQLiteOpenHelper encloses this in a transaction. Failure never drops the original tables.
        MigrationSnapshot.capture(owner,databaseName,db,oldVersion)
        if(oldVersion==1) {
            createPendingBindings(db)
            db.execSQL("UPDATE accounts SET enabled=0,auth='needs_action',auth_message='旧版授权未完成，请重新登录；原账号与记录已保留' WHERE auth='pending'")
        }
        if(oldVersion<3)db.execSQL("ALTER TABLE accounts ADD COLUMN game TEXT NOT NULL DEFAULT ''")
        if(oldVersion<4) {
            db.execSQL("ALTER TABLE accounts ADD COLUMN games TEXT NOT NULL DEFAULT ''")
            db.execSQL("UPDATE accounts SET games=game WHERE game!=''")
            if(oldVersion>=2)db.execSQL("ALTER TABLE pending_bindings ADD COLUMN available_games TEXT NOT NULL DEFAULT ''")
        }
        db.execSQL("ALTER TABLE accounts ADD COLUMN provider_uid TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE accounts ADD COLUMN role_scope INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE accounts ADD COLUMN auto_enabled INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE records ADD COLUMN started TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE records ADD COLUMN result_source TEXT NOT NULL DEFAULT 'legacy'")
        db.execSQL("UPDATE records SET result_source=CASE WHEN status='already' AND message LIKE '%本机%' THEN 'legacy_local' WHEN status='success' AND message LIKE '%接受%' THEN 'accepted' WHEN status IN ('success','already') THEN 'official' ELSE 'legacy' END")
        createRoleBindings(db)
        db.rawQuery("SELECT r.account,r.target,r.name,a.provider FROM records r JOIN accounts a ON a.id=r.account ORDER BY r.updated DESC",null).use { c ->
            while(c.moveToNext()) {
                val game=GameCatalog.gameForTarget(c.getString(3),c.getString(1)) ?: continue
                db.insertWithOnConflict("game_bindings",null,ContentValues().apply{put("account",c.getString(0));put("target",c.getString(1));put("game",game);put("name",c.getString(2))},SQLiteDatabase.CONFLICT_IGNORE)
            }
        }
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
                    add(Account(c.getString(0),c.getString(1),c.getString(2),c.getInt(3)==1,c.getString(4),c.getString(5),c.getString(6),c.getString(7),c.getString(0) in pending,c.getString(8),c.getString(9).split(',').filter{it.isNotEmpty()},c.getString(10),c.getInt(11)==1,c.getInt(12)==1))
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
        writableDatabase.execSQL("INSERT OR IGNORE INTO records(day,account,target,name,status,message,attempts,updated,stamp,started,result_source) SELECT day,account,target,?,status,'历史明细已清理；当天提交保护仍保留，未重新提交',attempts,?,stamp,'',result_source FROM sign_guards WHERE day=? AND account=? AND target=? AND status IN ('success','already','uncertain','needs_action','running')",arrayOf(target.name,now(),day,account,target.id))
        writableDatabase.execSQL("UPDATE records SET status='already',message='已签到；本机已有当天成功记录，未重复提交',updated=? WHERE day=? AND account=? AND target=? AND status IN ('success','already') AND result_source!='accepted'",arrayOf(now(),day,account,target.id))
    }
    /** Keeps the original account id/history and its paused/enabled preference on renewal. */
    fun commitPending(id:String,selectedRoles:List<RoleChoice>?=null,autoSign:Boolean=true):String {
        val db=writableDatabase;db.beginTransaction()
        try {
            val pending=pendingBinding(id)
            check(pending!=null && pending.rolesVerified && accounts().any{it.id==id&&it.authState=="pending"}) { "请先读取并确认角色" }
            val destination=pending.replacesAccountId ?: id
            db.execSQL("UPDATE accounts SET game=(SELECT game FROM accounts WHERE id=?),games=(SELECT games FROM accounts WHERE id=?) WHERE id=?",arrayOf(id,id,destination))
            val source=accounts().first{it.id==id}
            if(destination!=id)identity(destination,source.providerUserId)
            autoEnabled(destination,autoSign)
            if(selectedRoles!=null) {
                storeRoleChoices(destination,selectedRoles)
                setBindings(destination,selectedRoles,autoSign)
            }
            auth(destination,"valid","已登录并确认角色；签到结果以官方记录为准")
            // The new session already enumerated roles. Do not unlock uncertain sign submissions.
            clearDiscoveryFailure(destination)
            allowRejectedAuthRetry(destination)
            if(pending.replacesAccountId==null) {
                db.delete("pending_bindings","account=?",arrayOf(id));enabled(id,true);autoEnabled(id,autoSign)
            } else {
                db.insertOrThrow("retired_credentials",null,ContentValues().apply{put("account",id)})
                delete(id)
            }
            db.setTransactionSuccessful();return destination
        } finally {db.endTransaction()}
    }
    fun identity(id:String,value:String) {writableDatabase.execSQL("UPDATE accounts SET provider_uid=? WHERE id=?",arrayOf(value,id))}
    fun duplicate(provider:String,uid:String,excluding:String):Account?=if(uid.isBlank())null else accounts().firstOrNull{it.id!=excluding&&!it.bindingPending&&it.provider==provider&&it.providerUserId==uid}
    fun autoEnabled(id:String,value:Boolean) {writableDatabase.execSQL("UPDATE accounts SET auto_enabled=? WHERE id=?",arrayOf(if(value)1 else 0,id));writableDatabase.execSQL("UPDATE game_bindings SET auto_sign=? WHERE account=?",arrayOf(if(value)1 else 0,id))}
    fun bindingEnabled(id:String,target:String,enabled:Boolean) {writableDatabase.execSQL("UPDATE game_bindings SET enabled=? WHERE account=? AND target=?",arrayOf(if(enabled)1 else 0,id,target))}
    fun rename(id:String,alias:String) {require(alias.isNotBlank());writableDatabase.execSQL("UPDATE accounts SET nickname=? WHERE id=?",arrayOf(alias.take(40),id))}
    fun deleteRecords(id:String) {
        val db=writableDatabase;db.beginTransaction()
        try {
            db.execSQL("INSERT OR REPLACE INTO sign_guards SELECT day,account,target,status,attempts,stamp,result_source FROM records WHERE account=?",arrayOf(id))
            db.delete("records","account=?",arrayOf(id));db.setTransactionSuccessful()
        }finally{db.endTransaction()}
    }
    fun storeRoleChoices(id:String,roles:List<RoleChoice>) {
        val db=writableDatabase;db.beginTransaction()
        try {
            db.delete("discovered_roles","account=?",arrayOf(id))
            roles.distinctBy{it.targetId}.forEach {r->db.insertOrThrow("discovered_roles",null,ContentValues().apply{put("account",id);put("target",r.targetId);put("game",r.gameId);put("name",r.name)})}
            db.setTransactionSuccessful()
        } finally {db.endTransaction()}
    }
    fun roleChoices(id:String):List<RoleChoice> = readableDatabase.rawQuery("SELECT target,game,name FROM discovered_roles WHERE account=?",arrayOf(id)).use{c->buildList{while(c.moveToNext())add(RoleChoice(c.getString(0),c.getString(1),c.getString(2)))}}
    fun bindings(id:String?=null):List<GameBinding> = readableDatabase.rawQuery("SELECT account,target,game,name,enabled,auto_sign FROM game_bindings"+if(id==null)"" else " WHERE account=?",id?.let{arrayOf(it)}).use{c->buildList{while(c.moveToNext())add(GameBinding(c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getInt(4)==1,c.getInt(5)==1))}}
    fun setBindings(id:String,selected:List<RoleChoice>,autoSign:Boolean) {
        val known=roleChoices(id).map{it.targetId to it.gameId}.toSet()
        require(selected.isNotEmpty()&&selected.all{(it.targetId to it.gameId) in known}){"请选择已读取的角色"}
        val db=writableDatabase;db.beginTransaction()
        try {
            db.delete("game_bindings","account=?",arrayOf(id))
            selected.distinctBy{it.targetId}.forEach{r->db.insertOrThrow("game_bindings",null,ContentValues().apply{put("account",id);put("target",r.targetId);put("game",r.gameId);put("name",r.name);put("auto_sign",if(autoSign)1 else 0)})}
            db.execSQL("UPDATE accounts SET role_scope=1 WHERE id=?",arrayOf(id));db.setTransactionSuccessful()
        }finally{db.endTransaction()}
    }
    fun learnBindings(id:String,roles:List<RoleChoice>) {
        if(accounts().first{it.id==id}.roleScope)return
        roles.forEach{r->writableDatabase.insertWithOnConflict("game_bindings",null,ContentValues().apply{put("account",id);put("target",r.targetId);put("game",r.gameId);put("name",r.name)},SQLiteDatabase.CONFLICT_IGNORE)}
    }
    fun reconcile(day:String,id:String,target:String,result:SignResult) {
        // A status lookup never resets attempts or authorizes another uncertain submission.
        if(result.status !in setOf("already","uncertain"))return
        writableDatabase.execSQL("UPDATE records SET status=?,message=?,updated=? WHERE day=? AND account=? AND target=?",arrayOf(result.status,result.message,now(),day,id,target))
        if(result.status=="already")writableDatabase.execSQL("UPDATE records SET result_source='official' WHERE day=? AND account=? AND target=?",arrayOf(day,id,target))
        writableDatabase.execSQL("UPDATE sign_guards SET status=?,result_source=? WHERE day=? AND account=? AND target=?",arrayOf(result.status,if(result.status=="already")"official" else "unknown",day,id,target))
    }
    fun allowRejectedAuthRetry(id:String) {
        // Reauthorization can unlock an explicit authorization rejection, never an uncertain POST.
        writableDatabase.execSQL("UPDATE records SET status='failed',message='授权已更新；前次请求被明确拒绝，可安全重试',stamp=0 WHERE account=? AND status='needs_action' AND message LIKE '%授权失效%'",arrayOf(id))
    }
    fun retiredCredentials():List<String> = readableDatabase.rawQuery("SELECT account FROM retired_credentials",null).use { c -> buildList{while(c.moveToNext())add(c.getString(0))} }
    fun forgetRetiredCredential(id:String) { writableDatabase.delete("retired_credentials","account=?",arrayOf(id)) }
    fun recoverInterrupted() { writableDatabase.execSQL("UPDATE records SET status='uncertain',message='上次执行中断，结果不确定；请先到官方应用核对' WHERE status='running'") }
    /** Unique (Beijing date, community account, task type + role) and atomic claim. */
    fun claim(day:String,account:String,target:SignTarget,stamp:Long=System.currentTimeMillis()):Boolean {
        val db=writableDatabase;db.beginTransaction()
        try {
            var attempt=1
            db.rawQuery("SELECT status,attempts,stamp FROM records WHERE day=? AND account=? AND target=? UNION ALL SELECT status,attempts,stamp FROM sign_guards WHERE day=? AND account=? AND target=? LIMIT 1",arrayOf(day,account,target.id,day,account,target.id)).use{c->
                if(c.moveToFirst()) {
                    val status=c.getString(0);attempt=c.getInt(1)+1
                    val cooldown=if(status=="waiting_network")120_000L else 1_800_000L
                    if(status in setOf("success","already","needs_action","uncertain","running") || attempt>3 || stamp-c.getLong(2)<cooldown)return false
                }
            }
            val v=ContentValues().apply {put("day",day);put("account",account);put("target",target.id);put("name",target.name);put("status","running");put("message","本地正在检查");put("attempts",attempt);put("updated",now());put("stamp",stamp);put("started",now())}
            db.insertWithOnConflict("records",null,v,SQLiteDatabase.CONFLICT_REPLACE);db.setTransactionSuccessful();return true
        } finally {db.endTransaction()}
    }
    fun finish(day:String,account:String,target:SignTarget,r:SignResult) { val source=if(r.status=="success"&&r.message.contains("接受"))"accepted" else if(r.status in setOf("success","already"))"official" else "unknown"; val state=if(r.status=="needs_action" && listOf("不确定","未确认","中断").any{r.message.contains(it)})"uncertain" else r.status; writableDatabase.execSQL("UPDATE records SET status=?,message=?,updated=?,result_source=? WHERE day=? AND account=? AND target=?",arrayOf(state,r.message,now(),source,day,account,target.id)) }
    fun clearDiscoveryFailure(id:String) {writableDatabase.delete("sign_guards","account=? AND day=? AND target='discovery'",arrayOf(id,day())); writableDatabase.delete("records","account=? AND day=? AND target='discovery' AND status!='running'",arrayOf(id,day())) }
    fun records(day:String):List<CheckRecord> = readableDatabase.rawQuery("SELECT account,target,name,status,message,attempts,updated,started,result_source FROM records WHERE day=? ORDER BY account,target",arrayOf(day)).use{c->buildList{while(c.moveToNext())add(CheckRecord(c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4),c.getInt(5),c.getString(6),c.getString(7),c.getString(8)))}}
    fun history():List<HistoryDay> = readableDatabase.rawQuery("SELECT day,COUNT(*),SUM(status IN ('success','already')),SUM(status IN ('failed','needs_action','uncertain','waiting_network')) FROM records GROUP BY day ORDER BY day DESC LIMIT 90",null).use{c->buildList{while(c.moveToNext())add(HistoryDay(c.getString(0),c.getInt(1),c.getInt(2),c.getInt(3)))}}
}
