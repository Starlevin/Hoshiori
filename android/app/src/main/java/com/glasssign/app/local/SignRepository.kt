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

/**
 * The narrow credential surface the signing engine needs. Declared as an interface so the engine can be exercised
 * on a plain JVM (Robolectric / fake store) without touching the Android Keystore; the shipped implementation is
 * still [LocalCredentialStore] and [LocalServices] keeps constructing it directly.
 */
interface CredentialStore {
    fun read(id:String):JSONObject
    fun save(id:String,c:JSONObject)
    fun delete(id:String)
}

class LocalCredentialStore(private val context:Context) : CredentialStore {
    private fun store(id:String)=SecureStore(context.getSharedPreferences("local_secret_$id",Context.MODE_PRIVATE),"glasssign_local_$id")
    override fun save(id:String,c:JSONObject) {
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
    override fun read(id:String):JSONObject = store(id).readToken()?.let{JSONObject(it)} ?: throw SignError("auth","本机授权缺失或密钥失效，请重新导入")
    override fun delete(id:String) { store(id).clearAll() }
}
data class PendingBinding(val accountId:String,val replacesAccountId:String?,val rolesVerified:Boolean,val availableGameIds:List<String> = emptyList())

/**
 * Result provenance vocabulary. Every writer states the fact; readers only interpret.
 *  - [SOURCE_OFFICIAL]     the official status/info endpoint confirmed the day (or already-signed).
 *  - [SOURCE_ACCEPTED]     the interface ACCEPTED a submission but no independent confirmation happened.
 *  - [SOURCE_LOCAL_CACHE]  the local dedup path reused an earlier completed record (proof is the original one).
 *  - [SOURCE_LEGACY_LOCAL] pre-0.7 local completion with no official evidence (kept for old rows only).
 *  - [SOURCE_LEGACY]       pre-schema-5 rows, provenance unknown.
 */
internal const val SOURCE_OFFICIAL="official"
internal const val SOURCE_ACCEPTED="accepted"
internal const val SOURCE_LOCAL_CACHE="local_cache"
internal const val SOURCE_LEGACY_LOCAL="legacy_local"
internal const val SOURCE_LEGACY="legacy"
internal const val SOURCE_UNKNOWN="unknown"

class SignRepository(private val owner:Context,private val databaseName:String="local_sign_v4.db") : SQLiteOpenHelper(owner,databaseName,null,6) {
    companion object {
        const val SCHEMA=6
        val TZ:ZoneId=ZoneId.of("Asia/Shanghai")
        fun day()=ZonedDateTime.now(TZ).toLocalDate().toString()
        fun now()=ZonedDateTime.now(TZ).toOffsetDateTime().toString()
    }
    override fun onCreate(db:SQLiteDatabase) {
        db.execSQL("CREATE TABLE accounts(id TEXT PRIMARY KEY, provider TEXT NOT NULL, nickname TEXT NOT NULL, enabled INTEGER NOT NULL, created TEXT NOT NULL, auth TEXT NOT NULL DEFAULT 'unknown', auth_message TEXT NOT NULL DEFAULT '', verified TEXT NOT NULL DEFAULT '', game TEXT NOT NULL DEFAULT '', games TEXT NOT NULL DEFAULT '',provider_uid TEXT NOT NULL DEFAULT '',role_scope INTEGER NOT NULL DEFAULT 0,auto_enabled INTEGER NOT NULL DEFAULT 1)")
        db.execSQL("CREATE TABLE records(day TEXT NOT NULL, account TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE, target TEXT NOT NULL, name TEXT NOT NULL, status TEXT NOT NULL, message TEXT NOT NULL, attempts INTEGER NOT NULL, updated TEXT NOT NULL, stamp INTEGER NOT NULL, started TEXT NOT NULL DEFAULT '', result_source TEXT NOT NULL DEFAULT 'legacy', result_meta TEXT NOT NULL DEFAULT '', PRIMARY KEY(day,account,target))")
        createPendingBindings(db)
        createRoleBindings(db)
    }
    override fun onConfigure(db:SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
    private fun createPendingBindings(db:SQLiteDatabase) {
        db.execSQL("CREATE TABLE pending_bindings(account TEXT PRIMARY KEY REFERENCES accounts(id) ON DELETE CASCADE, replaces_account TEXT UNIQUE REFERENCES accounts(id) ON DELETE RESTRICT, roles_verified INTEGER NOT NULL DEFAULT 0, available_games TEXT NOT NULL DEFAULT '')")
        db.execSQL("CREATE TABLE retired_credentials(account TEXT PRIMARY KEY)")
    }
    private fun createRoleBindings(db:SQLiteDatabase) {
        db.execSQL("CREATE TABLE sign_guards(day TEXT NOT NULL,account TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,target TEXT NOT NULL,status TEXT NOT NULL,attempts INTEGER NOT NULL,stamp INTEGER NOT NULL,result_source TEXT NOT NULL DEFAULT 'legacy',result_meta TEXT NOT NULL DEFAULT '',PRIMARY KEY(day,account,target))")
        db.execSQL("CREATE TABLE game_bindings(account TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,target TEXT NOT NULL,game TEXT NOT NULL,name TEXT NOT NULL,enabled INTEGER NOT NULL DEFAULT 1,auto_sign INTEGER NOT NULL DEFAULT 1,PRIMARY KEY(account,target))")
        db.execSQL("CREATE TABLE discovered_roles(account TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,target TEXT NOT NULL,game TEXT NOT NULL,name TEXT NOT NULL,PRIMARY KEY(account,target))")
    }
    /**
     * Incremental, additive migrations only.
     *
     * Schema 5 -> 6 adds `result_meta` (explicit provenance) to `records` AND `sign_guards`, plus a bounded
     * compatibility backfill for rows written by older versions. Nothing is dropped, no user data or row is deleted,
     * and the migration snapshot for the previous schema is still captured. Downgrade rules:
     *  - a historical `success` whose provenance was guessed as `accepted` is stated explicitly as accepted;
     *  - a historical `accepted` is NEVER upgraded, no matter what the message says;
     *  - a `success`/`already` with no recorded provenance stays `unknown` (displayed as submitted, never as official).
     * The backfill is the ONLY place where message wording is consulted, and only to preserve the historical
     * interpretation of rows that predate explicit metadata.
     */
    override fun onUpgrade(db:SQLiteDatabase,oldVersion:Int,newVersion:Int) {
        check(oldVersion in 1..5 && newVersion==SCHEMA) { "Unsupported schema migration" }
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
        if(oldVersion<5) upgradeToFive(db)
        upgradeToSix(db)
    }
    /** The v0.7 schema-5 step, kept verbatim so a v1..v4 install still walks the same trusted path. */
    private fun upgradeToFive(db:SQLiteDatabase) {
        db.execSQL("ALTER TABLE accounts ADD COLUMN provider_uid TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE accounts ADD COLUMN role_scope INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE accounts ADD COLUMN auto_enabled INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE records ADD COLUMN started TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE records ADD COLUMN result_source TEXT NOT NULL DEFAULT 'legacy'")
        // Downgrade rule: a historical row is NEVER upgraded. Only the two wording patterns the old version really
        // wrote about itself are described (`本机` = a local record, `接受` = a pending submission); everything else
        // stays `legacy`, which is explicitly not a confirmation.
        db.execSQL("UPDATE records SET result_source=CASE WHEN status='already' AND message LIKE '%本机%' THEN 'legacy_local' WHEN status='success' AND message LIKE '%接受%' THEN 'accepted' WHEN status IN ('success','already') THEN 'legacy' ELSE 'legacy' END")
        createRoleBindings(db)
        db.rawQuery("SELECT r.account,r.target,r.name,a.provider FROM records r JOIN accounts a ON a.id=r.account ORDER BY r.updated DESC",null).use { c ->
            while(c.moveToNext()) {
                val game=GameCatalog.gameForTarget(c.getString(3),c.getString(1)) ?: continue
                db.insertWithOnConflict("game_bindings",null,ContentValues().apply{put("account",c.getString(0));put("target",c.getString(1));put("game",game);put("name",c.getString(2))},SQLiteDatabase.CONFLICT_IGNORE)
            }
        }
    }
    private fun upgradeToSix(db:SQLiteDatabase) {
        if(!hasColumn(db,"records","result_meta"))db.execSQL("ALTER TABLE records ADD COLUMN result_meta TEXT NOT NULL DEFAULT ''")
        // sign_guards is created in the same migration step for v1..v4 installs; add the column when it exists.
        if(tableExists(db,"sign_guards")&&!hasColumn(db,"sign_guards","result_meta"))
            db.execSQL("ALTER TABLE sign_guards ADD COLUMN result_meta TEXT NOT NULL DEFAULT ''")
        backfillMetadata(db,"records")
        if(tableExists(db,"sign_guards"))backfillMetadata(db,"sign_guards")
        // A `success` row whose DECLARED source is `accepted` is a pending submission, not a completed check-in: the
        // status column is normalised to `accepted` so every reader (planner, counts, UI) sees the same thing.
        db.execSQL("UPDATE records SET status='accepted' WHERE result_source='$SOURCE_ACCEPTED' AND status='success'")
        if(tableExists(db,"sign_guards"))
            db.execSQL("UPDATE sign_guards SET status='accepted' WHERE result_source='$SOURCE_ACCEPTED' AND status='success'")
    }
    /**
     * Compatibility backfill, applied once. Only rows that already carry a non-legacy `result_source` are described;
     * `accepted` and `legacy_local` are copied as-is (never upgraded), and `official` gets an explicit
     * "observed by legacy status read" note without inventing a timestamp (the row's own `updated` is used, which is
     * the moment the old version wrote the row — no new claim is made about the official endpoint).
     */
    private fun backfillMetadata(db:SQLiteDatabase,table:String) {
        // Plain string concatenation, not json_object(): older Android SQLite builds do not ship the JSON1 extension.
        db.execSQL("UPDATE $table SET result_meta='{\"source\":\"$SOURCE_ACCEPTED\"}' WHERE result_meta='' AND result_source='$SOURCE_ACCEPTED'")
        db.execSQL("UPDATE $table SET result_meta='{\"source\":\"$SOURCE_LEGACY_LOCAL\"}' WHERE result_meta='' AND result_source='$SOURCE_LEGACY_LOCAL'")
        // `official` gets an explicit "observed by a legacy status read" note; the timestamp is the row's own `updated`
        // (the moment the old version wrote it), so no new claim is made about the official endpoint.
        if(table=="records") {
            db.execSQL("UPDATE $table SET result_meta='{\"source\":\"$SOURCE_OFFICIAL\",\"verificationMethod\":\"legacy_status_read\",\"verifiedAt\":\"'||COALESCE(updated,'')||'\"}' WHERE result_meta='' AND result_source='$SOURCE_OFFICIAL'")
        } else {
            db.execSQL("UPDATE $table SET result_meta='{\"source\":\"$SOURCE_OFFICIAL\",\"verificationMethod\":\"legacy_status_read\"}' WHERE result_meta='' AND result_source='$SOURCE_OFFICIAL'")
        }
    }
    private fun tableExists(db:SQLiteDatabase,table:String):Boolean = db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",arrayOf(table)).use{it.moveToFirst()}
    private fun hasColumn(db:SQLiteDatabase,table:String,column:String):Boolean {
        var found=false
        db.rawQuery("PRAGMA table_info($table)",null).use{c->
            while(c.moveToNext())if(c.getString(1).equals(column,true)){found=true;break}
        }
        return found
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
        writableDatabase.execSQL("UPDATE accounts SET enabled=? WHERE id=? AND (?=0 OR (auth!='pending' AND NOT EXISTS (SELECT 1 FROM pending_bindings WHERE account=accounts.id)))",arrayOf<Any?>(if(value)1 else 0,id,if(value)1 else 0))
    }
    fun auth(id:String,state:String,message:String) { writableDatabase.execSQL("UPDATE accounts SET auth=?,auth_message=?,verified=? WHERE id=?",arrayOf(state,message,now(),id)) }
    fun authDiagnostic(id:String,message:String) {writableDatabase.execSQL("UPDATE accounts SET auth_message=? WHERE id=?",arrayOf(message,id))}
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
        writableDatabase.execSQL("UPDATE pending_bindings SET roles_verified=?,available_games=? WHERE account=?",arrayOf<Any?>(if(verified)1 else 0,if(verified)available.distinct().joinToString(",") else "",id))
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
    /**
     * Local-cache dedup: the task already has a completed row (or a guard row, after history deletion) today, so
     * nothing is submitted and no status is queried.
     *
     * The ORIGINAL proof is preserved: `resultSource` keeps the original evidence and [ResultMeta.verifiedAt] keeps
     * the original confirmation moment. Only the observable method is restated as `local_cache` and the message says
     * the display comes from this device's cache, so no reader can mistake it for a fresh official confirmation.
     *
     * @return the row as displayed, or null when nothing proves a completion (the caller must then execute).
     */
    fun acknowledgeSigned(day:String,account:String,target:SignTarget):CheckRecord? {
        val db=writableDatabase
        // Re-materialise the row from the guard when the user deleted their history; the guard keeps full proof.
        db.execSQL("INSERT OR IGNORE INTO records(day,account,target,name,status,message,attempts,updated,stamp,started,result_source,result_meta) SELECT day,account,target,?,status,'历史明细已清理；当天提交保护仍保留，未重新提交',attempts,?,stamp,'',result_source,result_meta FROM sign_guards WHERE day=? AND account=? AND target=? AND status IN ('success','already','accepted','uncertain','needs_action','running','verifying')",arrayOf(target.name,now(),day,account,target.id))
        val current=records(day).firstOrNull{it.accountId==account&&it.targetId==target.id} ?: return null
        val proof=current.resultMeta.let{meta->if(meta.source.isBlank())meta.copy(source=current.resultSource) else meta}
        // An accepted submission keeps BOTH its meaning and its status (`accepted`, never promoted to success); a
        // completed row keeps its proof and is only restated as a locally cached display.
        val accepted=proof.source==SOURCE_ACCEPTED||current.status=="accepted"
        val cached=if(accepted) proof else proof.copy(verificationMethod=SOURCE_LOCAL_CACHE)
        val status=if(accepted) "accepted" else current.status
        val message=if(accepted)
            "本机已有当天已提交待确认记录（来源：${cached.source.ifBlank{SOURCE_UNKNOWN}}），本次未重新提交"
        else
            "本机已有当天完成记录（来源：${cached.source.ifBlank{SOURCE_UNKNOWN}}），本次未重新提交"
        db.execSQL("UPDATE records SET status=?,message=?,updated=?,result_meta=? WHERE day=? AND account=? AND target=?",
            arrayOf(status,message,now(),cached.encode(),day,account,target.id))
        return records(day).firstOrNull{it.accountId==account&&it.targetId==target.id}
    }
    /** Read-only view of the cached proof, for callers that only need to display it (no write). */
    fun reuseCompleted(day:String,account:String,target:SignTarget):CheckRecord? {
        // Read-only on purpose: the engine calls this when a concurrent run already claimed the task, and a second run
        // must never re-label the row another run is still finishing. The message states the local basis at display
        // time (see [com.glasssign.app.data.sourceSentence]); nothing is written and no status is queried here.
        return records(day).firstOrNull{it.accountId==account&&it.targetId==target.id}
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
    fun autoEnabled(id:String,value:Boolean) {writableDatabase.execSQL("UPDATE accounts SET auto_enabled=? WHERE id=?",arrayOf<Any?>(if(value)1 else 0,id));writableDatabase.execSQL("UPDATE game_bindings SET auto_sign=? WHERE account=?",arrayOf<Any?>(if(value)1 else 0,id))}
    fun bindingEnabled(id:String,target:String,enabled:Boolean) {writableDatabase.execSQL("UPDATE game_bindings SET enabled=? WHERE account=? AND target=?",arrayOf<Any?>(if(enabled)1 else 0,id,target))}
    fun rename(id:String,alias:String) {require(alias.isNotBlank());writableDatabase.execSQL("UPDATE accounts SET nickname=? WHERE id=?",arrayOf(alias.take(40),id))}
    fun deleteRecords(id:String) {
        val db=writableDatabase;db.beginTransaction()
        try {
            // The guard keeps the full provenance so a later local-cache hit can still show the original proof.
            db.execSQL("INSERT OR REPLACE INTO sign_guards(day,account,target,status,attempts,stamp,result_source,result_meta) SELECT day,account,target,status,attempts,stamp,result_source,result_meta FROM records WHERE account=?",arrayOf(id))
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
    /**
     * Applies a READ-ONLY official status result. The rules are deliberately asymmetric:
     *
     *  - Only an official confirmation ([SOURCE_OFFICIAL] + `already`, or a verified `success`) may upgrade a row.
     *  - A failed / inconclusive query (`uncertain`, `failed`, `waiting_network`, a plain read error) NEVER downgrades
     *    an existing trusted proof; attempts and the submit-protection stamp are never reset by a status lookup.
     *  - A manual verification of a row that is already accepted is allowed (query-only) and only upgrades it when
     *    the official endpoint actually reports today as signed.
     *  - An unknown submission is resolved to `already` only by an official confirmation.
     *
     * @return the stored row after the update, or null when nothing is stored for that target.
     */
    fun reconcile(day:String,id:String,target:String,result:SignResult):CheckRecord? {
        val current=records(day).firstOrNull{it.accountId==id&&it.targetId==target} ?: return null
        val official=result.isOfficialConfirmation
        return if(official) {
            val proof=metadataOf(result,official=true)
            writableDatabase.execSQL(
                "UPDATE records SET status='already',message=?,updated=?,result_source=?,result_meta=? WHERE day=? AND account=? AND target=?",
                arrayOf(result.message,now(),SOURCE_OFFICIAL,proof.encode(),day,id,target))
            writableDatabase.execSQL(
                "UPDATE sign_guards SET status='already',result_source=?,result_meta=? WHERE day=? AND account=? AND target=?",
                arrayOf(SOURCE_OFFICIAL,proof.encode(),day,id,target))
            records(day).firstOrNull{it.accountId==id&&it.targetId==target}
        } else {
            // No conclusion. A row whose stored proof is `accepted` MUST stay accepted: an inconclusive query can
            // never demote an accepted submission to "unknown". Only the readable message is refreshed; the stored
            // status/source/verifiedAt proof are re-written from the CURRENT row, byte for byte.
            val keepStatus=when(current.status) {
                "verifying" -> buildStatusFromProof(current)
                else -> current.status
            }
            writableDatabase.execSQL(
                "UPDATE records SET status=?,message=?,updated=? WHERE day=? AND account=? AND target=?",
                arrayOf(keepStatus,"只读核对未取得新结论；原记录已保留（${result.message}）",now(),day,id,target))
            // Guards keep their own status/source untouched: a status lookup may neither upgrade nor unlock them.
            records(day).firstOrNull{it.accountId==id&&it.targetId==target}
        }
    }
    /**
     * The status a row had before a transient `verifying` marker.
     *
     * `accepted` is preserved as `accepted` (schema 6 stores it): collapsing it back to `success` would silently
     * upgrade a pending submission to a completed check-in. Official/local proofs restore to `already`, and a row
     * without any trusted proof falls back to `uncertain`.
     */
    private fun buildStatusFromProof(row:CheckRecord):String = when(sourceOfRecord(row)) {
        SOURCE_ACCEPTED -> "accepted"
        SOURCE_OFFICIAL,SOURCE_LEGACY_LOCAL -> "already"
        else -> "uncertain"
    }
    /** Explicit provenance of a stored row, falling back to the historical interpretation for pre-schema-6 rows. */
    private fun sourceOfRecord(row:CheckRecord):String {
        if(!row.resultMeta.isBlank)return row.resultMeta.source
        return row.resultSource.ifBlank{SOURCE_UNKNOWN}
    }
    /** The explicit metadata of a [SignResult]; provenance is taken from the writer, never guessed from wording. */
    private fun metadataOf(result:SignResult,official:Boolean):ResultMeta {
        val declared=result.resultSource.takeIf{it.isNotBlank() && it!=SOURCE_UNKNOWN}
        val source=declared ?: if(official) SOURCE_OFFICIAL else SOURCE_UNKNOWN
        return ResultMeta(
            source=source,
            providerCode=result.providerCode,
            httpStatus=result.httpStatus,
            verificationMethod=result.verificationMethod,
            verifiedAt=if(official) result.verifiedAt.ifBlank{now()} else result.verifiedAt,
            retryable=result.retryable,
            executionId=result.executionId,
        )
    }

    fun allowRejectedAuthRetry(id:String) {
        val db=writableDatabase
        for(table in listOf("records","sign_guards")) {
            val rows=mutableListOf<Pair<String,ResultMeta>>()
            db.rawQuery("SELECT target,result_meta"+(if(table=="records")",message" else ",''")+" FROM $table WHERE account=? AND day=? AND status='needs_action'",arrayOf(id,day())).use { c->
                while(c.moveToNext()) {
                    val meta=ResultMeta.decode(c.getString(1))
                    if(meta.httpStatus==401||meta.providerCode in setOf("auth","401","-100","10003","220")||
                        (meta.isBlank&&c.getString(2).contains("授权失效")))rows.add(c.getString(0) to meta.copy(retryable=true))
                }
            }
            rows.forEach { (target,meta)->
                val values=ContentValues().apply {put("status","failed");put("stamp",0);put("result_meta",meta.encode());if(table=="records")put("message","授权已更新；前次请求被明确拒绝，可安全重试")}
                db.update(table,values,"account=? AND day=? AND target=?",arrayOf(id,day(),target))
            }
        }
    }
    fun retiredCredentials():List<String> = readableDatabase.rawQuery("SELECT account FROM retired_credentials",null).use { c -> buildList{while(c.moveToNext())add(c.getString(0))} }
    fun forgetRetiredCredential(id:String) { writableDatabase.delete("retired_credentials","account=?",arrayOf(id)) }
    /**
     * Resolves rows left as `running`/`verifying` by a killed process. It NEVER overwrites an earlier trusted proof:
     *  - `verifying` is a transient READ-ONLY display state; recovering it returns to the previously stored result,
     *    or to a neutral "verification interrupted" note — the earlier official/accepted proof is untouched;
     *  - `running` (a claim with no result) becomes `uncertain`, because a submission may have happened.
     * A row that already carries an official/accepted proof is never downgraded by this recovery.
     */
    fun recoverInterrupted() {
        val db=writableDatabase;db.beginTransaction()
        try {
            // Verifying rows: restore the trust level the row was at before the read-only check started. The stored
            // resultSource / result_meta (verifiedAt, providerCode …) are never modified, so an earlier trusted proof
            // survives a killed process.
            db.execSQL("UPDATE records SET status=CASE "+
                "WHEN result_source='$SOURCE_ACCEPTED' OR result_meta LIKE '%\"source\":\"$SOURCE_ACCEPTED\"%' THEN 'success' "+
                "WHEN result_source IN ('$SOURCE_OFFICIAL','$SOURCE_LEGACY_LOCAL') THEN 'already' "+
                "ELSE 'uncertain' END,message='只读核验被系统中断；原结果与来源保持不变' WHERE status='verifying'")
            // A verifying guard has no message column; restore the same trust level without touching its proof.
            db.execSQL("UPDATE sign_guards SET status=CASE "+
                "WHEN result_source='$SOURCE_ACCEPTED' OR result_meta LIKE '%\"source\":\"$SOURCE_ACCEPTED\"%' THEN 'success' "+
                "WHEN result_source IN ('$SOURCE_OFFICIAL','$SOURCE_LEGACY_LOCAL') THEN 'already' "+
                "ELSE 'uncertain' END WHERE status='verifying'")
            // A claim without a result: the POST may or may not have reached the server.
            db.execSQL("UPDATE records SET status='uncertain',message='上次执行中断，结果不确定；请先到官方应用核对' WHERE status='running'")
            db.setTransactionSuccessful()
        } finally {db.endTransaction()}
    }
    /**
     * Unique (Beijing date, community account, task type + role) and atomic claim.
     *
     * Blocking rule: `success` / `already` / `needs_action` / `uncertain` / `running` / `verifying` all block a new
     * submission, and the guard table keeps that protection alive after the user deletes their history rows.
     */
    fun claim(day:String,account:String,target:SignTarget,stamp:Long=System.currentTimeMillis()):Boolean {
        val db=writableDatabase
        // BEGIN IMMEDIATE takes the write lock up front: two concurrent runs cannot both read "free" and then both
        // insert, which is what produced one submission per caller instead of one per task.
        db.beginTransactionNonExclusive()
        try {
            var attempt=1
            db.rawQuery("SELECT status,attempts,stamp,result_source,result_meta FROM records WHERE day=? AND account=? AND target=? UNION ALL SELECT status,attempts,stamp,result_source,result_meta FROM sign_guards WHERE day=? AND account=? AND target=?",arrayOf(day,account,target.id,day,account,target.id)).use{c->
                while(c.moveToNext()) {
                    val status=c.getString(0);attempt=maxOf(attempt,c.getInt(1)+1)
                    val guardSource=c.getString(3)
                    val cooldown=if(status=="waiting_network")120_000L else 1_800_000L
                    val blocked=status in BLOCKING_STATUSES
                        || (status in setOf("failed","waiting_network") && !ResultMeta.decode(c.getString(4)).retryable)
                        // Historical rows written before explicit metadata still protect a completed day.
                        || attempt>3
                        // A blocked-by-proof row from the guard table: deleting history must not unlock a submission.
                        || (guardSource in setOf(SOURCE_OFFICIAL,SOURCE_ACCEPTED,SOURCE_LEGACY_LOCAL) && status in setOf("success","already"))
                        || stamp-c.getLong(2)<cooldown
                    if(blocked)return false
                }
            }
            // A claim KEEPS the stored proof (status/source/result_meta/started): it only marks the row as in progress
            // and bumps the attempt counter. Overwriting the source here would destroy the very evidence the README
            // promises to preserve (an accepted submission would silently become "unknown").
            val v=ContentValues().apply {put("day",day);put("account",account);put("target",target.id);put("name",target.name);put("status","running");put("message","本地正在检查");put("attempts",attempt);put("updated",now());put("stamp",stamp);if(attempt==1){put("started",now());put("result_source",SOURCE_UNKNOWN);put("result_meta","")}}
            if(db.update("records",v,"day=? AND account=? AND target=?",arrayOf(day,account,target.id))==0)db.insertOrThrow("records",null,v)
            db.setTransactionSuccessful();return true
        } finally {db.endTransaction()}
    }
    /** Marks a claimed row as "read-only verification in progress" without touching its stored proof. */
    fun markVerifying(day:String,account:String,target:String) {
        writableDatabase.execSQL("UPDATE records SET status='verifying',message='正在只读核对官方状态；原结果与来源保持不变',updated=? WHERE day=? AND account=? AND target=? AND status NOT IN ('running')",arrayOf(now(),day,account,target))
    }
    /**
     * Stores a real task outcome. Provenance comes from the writer ([SignResult.resultSource]), never from the message
     * text: an accepted submission stays `accepted`, a verified success is `official`, and only a legacy result with
     * no declared source keeps the historical display convention.
     */
    fun finish(day:String,account:String,target:SignTarget,r:SignResult) {
        val official=r.isOfficialConfirmation
        val source=when {
            r.resultSource==SOURCE_OFFICIAL&&!official -> SOURCE_UNKNOWN
            r.resultSource.isNotBlank()&&r.resultSource!=SOURCE_UNKNOWN -> r.resultSource
            official -> SOURCE_OFFICIAL
            else -> SOURCE_UNKNOWN
        }
        // Contract: an ACCEPTED submission is stored as status `accepted`, never as `success`.
        //  - a current writer that declares source `accepted` is normalised to `accepted`;
        //  - a historical `success` + source `accepted` is normalised the same way (downgrade, never upgrade);
        //  - a row that was already `accepted` stays `accepted` (it can never be promoted back to `success`).
        val status=when {
            source==SOURCE_ACCEPTED -> "accepted"
            r.status in setOf("success","already")&&!official -> "uncertain"
            r.status=="needs_action"&&r.message.isUnknownSubmissionWording -> "uncertain"
            else -> r.status
        }
        // The local-cache display method is stated whenever the engine reuses a stored proof instead of querying.
        val method=when {
            r.verificationMethod.isNotBlank() -> r.verificationMethod
            r.status in setOf("success","already")&&!official -> SOURCE_LOCAL_CACHE
            else -> ""
        }
        val meta=ResultMeta(
            source=source,
            providerCode=r.providerCode,
            httpStatus=r.httpStatus,
            verificationMethod=method,
            verifiedAt=if(official) r.verifiedAt.ifBlank{now()} else r.verifiedAt,
            retryable=r.retryable&&status in setOf("failed","waiting_network"),
            executionId=r.executionId,
        )
        writableDatabase.execSQL("UPDATE records SET status=?,message=?,updated=?,result_source=?,result_meta=? WHERE day=? AND account=? AND target=?",
            arrayOf(status,r.message,now(),source,meta.encode(),day,account,target.id))
        // The guard mirrors the same proof, so deleting history cannot unlock or upgrade the task later.
        writableDatabase.execSQL("UPDATE sign_guards SET status=?,result_source=?,result_meta=? WHERE day=? AND account=? AND target=?",
            arrayOf(status,source,meta.encode(),day,account,target.id))
    }
    fun clearDiscoveryFailure(id:String) {writableDatabase.delete("sign_guards","account=? AND day=? AND target='discovery'",arrayOf(id,day())); writableDatabase.delete("records","account=? AND day=? AND target='discovery' AND status NOT IN ('running','verifying')",arrayOf(id,day()))}
    fun records(day:String):List<CheckRecord> = readableDatabase.rawQuery("SELECT account,target,name,status,message,attempts,updated,started,result_source,result_meta FROM records WHERE day=? ORDER BY account,target",arrayOf(day)).use{c->buildList{while(c.moveToNext())add(CheckRecord(c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4),c.getInt(5),c.getString(6),c.getString(7),c.getString(8),ResultMeta.decode(c.getString(9))))}}
    /**
     * Daily history. `ok` counts ONLY confirmed/protected completions (`already`/`success`); an `accepted` submission
     * and an unknown result are counted in `attention` so history can never claim a success that was not confirmed.
     */
    fun history():List<HistoryDay> = readableDatabase.rawQuery("SELECT day,COUNT(*),SUM(status IN ('success','already')),SUM(status IN ('failed','needs_action','uncertain','waiting_network','verifying')) FROM records GROUP BY day ORDER BY day DESC LIMIT 90",null).use{c->buildList{while(c.moveToNext())add(HistoryDay(c.getString(0),c.getInt(1),c.getInt(2),c.getInt(3)))}}
    /** Guard rows (the submit protection that outlives history deletion), newest day first. */
    fun guards(day:String?=null):List<CheckRecord> = readableDatabase.rawQuery("SELECT account,target,'',status,'',attempts,'','',result_source,result_meta"+(if(day==null)" FROM sign_guards ORDER BY day DESC" else ",day FROM sign_guards WHERE day=? ORDER BY account,target"),if(day==null)null else arrayOf(day)).use{c->buildList{while(c.moveToNext())add(CheckRecord(c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4),c.getInt(5),c.getString(6),c.getString(7),c.getString(8),ResultMeta.decode(c.getString(9))))}}
}

/** Statuses that must block any further submission for the same (day, account, target). */
internal val BLOCKING_STATUSES=setOf("success","already","accepted","needs_action","uncertain","running","verifying")
/** Historical wording that still means "the outcome is unknown" (compatibility only, never for new results). */
internal val SignResult.isUnknownSubmission:Boolean get()=status=="needs_action"&&message.isUnknownSubmissionWording
internal val String.isUnknownSubmissionWording:Boolean get()=listOf("不确定","未确认","中断").any{contains(it)}
/**
 * An explicit official confirmation, decided by the DECLARED source (or by a verified success), never by wording.
 * `accepted` is by definition not an official confirmation of the result.
 */
internal val SignResult.isOfficialConfirmation:Boolean get()=resultSource==SOURCE_OFFICIAL &&
    status in setOf("already","success") && verifiedAt.isNotBlank() &&
    verificationMethod in setOf("info","calendar","state","provider_status","official_status","official_response")
