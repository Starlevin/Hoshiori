package com.glasssign.app.data

import org.json.JSONArray
import org.json.JSONObject

data class Account(
    val id: String,
    val provider: String,
    val nickname: String,
    val enabled: Boolean,
    val createdAt: String,
    val authState: String = "unknown",
    val authMessage: String = "",
    val lastVerifiedAt: String = "",
    val bindingPending: Boolean = false,
    val gameId: String = "",
    val gameIds: List<String> = if(gameId.isEmpty()) emptyList() else listOf(gameId),
    val providerUserId:String="",
    val roleScope:Boolean=false,
    val autoSignEnabled:Boolean=true,
)

/** Result of the read-only authorization probe. Never claims a sign-in happened. */
data class AuthorizationCheck(
    val state: String,
    val message: String,
    val targets: List<String>,
    val checkedAt: String,
)

/**
 * Explicit provenance metadata stored next to a record (`records.result_meta` / `sign_guards.result_meta`).
 *
 * WHY: the historical code guessed provenance from message substrings ("接受", "本机") both when writing and when
 * reading. That made two different runs with identical wording indistinguishable, and it made "accepted by the
 * interface" look like "confirmed by the official status endpoint". From schema 6 the writer states the facts and
 * the reader only interprets them.
 *
 * This value is stored as a compact JSON object string. Every field has a safe default, so an old row (or an old
 * parser) keeps working: [UNKNOWN] is the empty instance, which means "no explicit metadata recorded".
 */
data class ResultMeta(
    /** "unknown" / "official" / "accepted" / "local_cache" / "legacy" — mirrors CheckRecord.resultSource. */
    val source:String="" ,
    val providerCode:String="",
    val httpStatus:Int?=null,
    /** How the fact was learned: "info" / "calendar" / "state" / "local_cache" / "" when unknown. */
    val verificationMethod:String="",
    /** ISO local time of the official confirmation, "" when no official confirmation happened. */
    val verifiedAt:String="",
    /** True only when the writer explicitly says the same request may be retried safely. */
    val retryable:Boolean=false,
    /** Per-run identifier so one click's writes can be told apart from earlier runs. */
    val executionId:String="",
) {
    val isBlank:Boolean get()=source.isBlank()&&providerCode.isBlank()&&httpStatus==null&&verificationMethod.isBlank()&&verifiedAt.isBlank()&&!retryable&&executionId.isBlank()
    fun encode():String=if(isBlank)"" else JSONObject().apply{
        put("source",source)
        if(providerCode.isNotBlank())put("providerCode",providerCode)
        httpStatus?.let{put("httpStatus",it)}
        if(verificationMethod.isNotBlank())put("verificationMethod",verificationMethod)
        if(verifiedAt.isNotBlank())put("verifiedAt",verifiedAt)
        if(retryable)put("retryable",true)
        if(executionId.isNotBlank())put("executionId",executionId)
    }.toString()
    companion object {
        val UNKNOWN=ResultMeta()
        /** Tolerant parser: a broken or partial JSON blob degrades to the unknown metadata, never to a crash. */
        fun decode(raw:String?):ResultMeta {
            if(raw.isNullOrBlank())return UNKNOWN
            return runCatching{
                val json=JSONObject(raw)
                ResultMeta(
                    source=json.optString("source"),
                    providerCode=json.optString("providerCode"),
                    httpStatus=if(json.has("httpStatus")&&!json.isNull("httpStatus"))json.optInt("httpStatus") else null,
                    verificationMethod=json.optString("verificationMethod"),
                    verifiedAt=json.optString("verifiedAt"),
                    retryable=json.optBoolean("retryable",false),
                    executionId=json.optString("executionId"),
                )
            }.getOrDefault(UNKNOWN)
        }
    }
}

data class CheckRecord(
    val accountId: String,
    val targetId: String,
    val targetName: String,
    val status: String,
    val message: String,
    val attempts: Int,
    val updatedAt: String,
    val startedAt:String="",
    val resultSource:String="legacy",
    /** Explicit provenance. Always appended last so every existing positional construction keeps compiling. */
    val resultMeta:ResultMeta=ResultMeta.UNKNOWN,
) {
    // Read-through accessors: the explicit metadata is the source of truth, the columns are the fallback for rows
    // written before schema 6. Never derived from [message] wording.
    val providerCode:String get()=resultMeta.providerCode
    val httpStatus:Int? get()=resultMeta.httpStatus
    val verificationMethod:String get()=resultMeta.verificationMethod
    val verifiedAt:String get()=resultMeta.verifiedAt
    val retryable:Boolean get()=resultMeta.retryable
    val executionId:String get()=resultMeta.executionId
}

data class Overview(
    val date: String,
    val serverTime: String,
    val version: String,
    val runTime: String,
    val nextRunAt: String,
    val accounts: List<Account>,
    val records: List<CheckRecord>,
    val summary: Map<String, Int>,
    val engineRunning: Boolean,
    val lastFinishedAt: String?,
    val lastTrigger: String?,
    val lastStartedAt:String?=null,
)

data class JobSnapshot(
    val id: String,
    val state: String,
    val error: String?,
    val attempted: Int,
    val skipped: Int,
    val busy: Boolean,
    val durationMs: Int,
)

data class HistoryDay(
    val day: String,
    val total: Int,
    val ok: Int,
    val attention: Int,
)

data class DayRecords(
    val day: String,
    val records: List<CheckRecord>,
    val summary: Map<String, Int>,
)

data class Message(val text: String, val error: Boolean = false, val at: Long = System.currentTimeMillis())

class CloudException(message: String, val statusCode: Int = 0) : Exception(message)

private fun JSONArray?.objects(): List<JSONObject> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { optJSONObject(it) }
}

private fun JSONObject?.obj(key: String): JSONObject? =
    if (this != null && !isNull(key)) optJSONObject(key) else null

private fun JSONObject?.arr(key: String): JSONArray? =
    if (this != null && !isNull(key)) optJSONArray(key) else null

fun parseRecord(json: JSONObject): CheckRecord = CheckRecord(
    accountId = json.optString("account_id"),
    targetId = json.optString("target_id"),
    targetName = json.optString("target_name").ifBlank { "签到任务" },
    status = json.optString("status", "failed"),
    message = json.optString("message"),
    attempts = json.optInt("attempts", 1),
    updatedAt = json.optString("updated_at"),
    // Server payloads predate the metadata column: absent keys keep the historical defaults exactly.
    resultSource = json.optString("result_source", "legacy"),
    resultMeta = ResultMeta.decode(json.optString("result_meta")),
)

fun parseRecords(array: JSONArray?): List<CheckRecord> = array.objects().map(::parseRecord)

fun parseSummary(json: JSONObject?): Map<String, Int> {
    val result = linkedMapOf<String, Int>()
    if (json == null) return result
    val keys = json.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        result[key] = json.optInt(key)
    }
    return result
}

fun parseOverview(json: JSONObject): Overview {
    val engine = json.obj("engine")
    val last = engine.obj("last")
    return Overview(
        date = json.optString("date"),
        serverTime = json.optString("server_time"),
        version = json.optString("version", "0.3.0"),
        runTime = json.optString("run_time", "08:10"),
        nextRunAt = json.optString("next_run_at"),
        accounts = json.arr("accounts").objects().map {
            Account(
                id = it.optString("id"),
                provider = it.optString("provider"),
                nickname = it.optString("nickname"),
                enabled = it.optBoolean("enabled", true),
                createdAt = it.optString("created_at"),
                authState = it.optString("auth_state").ifBlank { "unknown" },
                authMessage = it.optString("auth_message"),
                lastVerifiedAt = it.optString("last_verified_at"),
            )
        },
        records = parseRecords(json.arr("records")),
        summary = parseSummary(json.obj("summary")),
        engineRunning = engine?.optBoolean("running") ?: false,
        lastFinishedAt = last?.optString("finished_at")?.takeIf { it.isNotBlank() },
        lastTrigger = last?.optString("trigger")?.takeIf { it.isNotBlank() },
    )
}

fun parseJob(json: JSONObject): JobSnapshot {
    val result = json.obj("result")
    return JobSnapshot(
        id = json.optString("id"),
        state = json.optString("state", "queued"),
        error = json.optString("error").takeIf { it.isNotBlank() },
        attempted = result?.optInt("attempted") ?: 0,
        skipped = result?.optInt("skipped") ?: 0,
        busy = result?.optBoolean("busy") ?: false,
        durationMs = result?.optInt("duration_ms") ?: 0,
    )
}

fun parseAuthorizationCheck(json: JSONObject): AuthorizationCheck {
    val names = mutableListOf<String>()
    json.arr("targets")?.let { array ->
        for (index in 0 until array.length()) {
            names.add(array.optString(index))
        }
    }
    return AuthorizationCheck(
        state = json.optString("state").ifBlank { "unknown" },
        message = json.optString("message"),
        targets = names,
        checkedAt = json.optString("checked_at"),
    )
}

fun parseHistory(json: JSONObject): List<HistoryDay> = json.arr("days").objects().map {
    HistoryDay(
        day = it.optString("day"),
        total = it.optInt("total"),
        ok = it.optInt("ok"),
        attention = it.optInt("attention"),
    )
}

fun parseDayRecords(json: JSONObject): DayRecords = DayRecords(
    day = json.optString("day"),
    records = parseRecords(json.arr("records")),
    summary = parseSummary(json.obj("summary")),
)


data class GameBinding(val accountId:String,val targetId:String,val gameId:String,val name:String,val enabled:Boolean=true,val autoSignEnabled:Boolean=true)
data class RoleChoice(val targetId:String,val gameId:String,val name:String)
fun maskedIdentity(value:String):String=if(value.isBlank())"身份未提供" else if(value.length<5)"••"+value.takeLast(1) else value.take(2)+"••••"+value.takeLast(2)


data class BindingSelection(val games:Set<String>,val roles:Set<String>,val autoSign:Boolean)
fun initialBindingSelection(original:Account?,bindings:List<GameBinding>,choices:List<RoleChoice>):BindingSelection {
    val available=choices.map{it.gameId}.toSet()
    val games=original?.gameIds?.toSet()?.intersect(available) ?: available
    val known=if(original==null)choices.map{it.targetId}.toSet() else bindings.filter{it.accountId==original.id}.map{it.targetId}.toSet()
    return BindingSelection(games,choices.filter{it.gameId in games&&it.targetId in known}.map{it.targetId}.toSet(),original?.autoSignEnabled ?: true)
}
