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
)

/** Result of the read-only authorization probe. Never claims a sign-in happened. */
data class AuthorizationCheck(
    val state: String,
    val message: String,
    val targets: List<String>,
    val checkedAt: String,
)

data class CheckRecord(
    val accountId: String,
    val targetId: String,
    val targetName: String,
    val status: String,
    val message: String,
    val attempts: Int,
    val updatedAt: String,
)

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
