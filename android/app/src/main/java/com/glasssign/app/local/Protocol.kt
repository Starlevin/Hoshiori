package com.glasssign.app.local

import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import java.io.IOException
import java.net.Proxy
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class SignTarget(val id: String, val name: String, val serverId: String? = null)

/*
 * v0.7.7 P0 protocol contract (docs/v077-report.md).
 *
 * The bug this file fixes is FACT-LEVEL: the generic `result(code =="0")` helper reported `success` for "the
 * interface accepted the submission", and downstream code then re-guessed provenance from the message text
 * ("接受" => accepted). Both halves are gone here — an acceptance is `accepted` + `accepted`, a real confirmation is
 * `success`/`already` + `official` with a verification method and time, and no reader parses wording.
 */

/** Provenance vocabulary. Declared by the writer; never derived from [SignResult.message]. */
object ResultSource {
    /** Nothing was learned, or the outcome of a possible submission is unknown. */
    const val UNKNOWN = "unknown"
    /** An official status/calendar endpoint confirmed today is signed (or was already signed). */
    const val OFFICIAL = "official"
    /** The interface ACCEPTED a submission but nothing confirmed the result. */
    const val ACCEPTED = "accepted"
    /** The row re-shows this device's earlier stored proof (not a new official query). */
    const val LOCAL_CACHE = "local_cache"
}

/** How a fact was learned. Bounded ASCII tokens only, safe to store and show. */
object VerifyMethod {
    /** Miyoushe `/event/luna/info` (`data.is_sign`). */
    const val INFO = "info"
    /** Skland `/game/attendance` calendar (`currentTs` + `records[].ts`). */
    const val CALENDAR = "calendar"
    /** Tagged `/apihub/awapi/signin/state` (`data.todaySign`). */
    const val STATE = "state"
    /** A provider's own "already signed" business code (e.g. Skland 10001 + 已签到). */
    const val PROVIDER_STATUS = "provider_status"
    /** A submission was accepted; explicitly NOT a verification method. */
    const val SUBMITTED = "submitted"
    /** The platform exposes no verified read-only status for this target: absence is stated, never faked. */
    const val NOT_SUPPORTED = "not_supported"
}

/**
 * One signing outcome, with the provenance stated EXPLICITLY by the writer.
 *
 * The first two parameters keep every existing positional call (`SignResult("success","…")`) compiling; the rest are
 * appended and defaulted.
 */
data class SignResult(
    val status: String,
    val message: String,
    /** [ResultSource] value. "unknown" / "official" / "accepted" / "local_cache". */
    val resultSource: String = ResultSource.UNKNOWN,
    /** Provider token echoed for diagnostics (`"0"`, `"10001"`, `"-5003"`), already charset-checked. */
    val providerCode: String = "",
    /** HTTP status when the outcome came from a rejected HTTP reply; null otherwise. */
    val httpStatus: Int? = null,
    /** [VerifyMethod] value, "" when the outcome carries no verification claim. */
    val verificationMethod: String = "",
    /** ISO local time ([SignRepository.now]) of the OFFICIAL confirmation; "" when nothing was confirmed. */
    val verifiedAt: String = "",
    /** True ONLY when the writer proves that repeating the same request is safe. Never true for a rejection. */
    val retryable: Boolean = false,
    /** Per-run identifier, attached by the engine so one click's writes can be told apart. */
    val executionId: String = "",
) {
    /** True when this result claims an official confirmation (the only thing the repository may upgrade with). */
    val isOfficial: Boolean get() = isOfficialConfirmation
}

/**
 * Metadata of a rejected request that is safe to keep: WHO rejected WHAT with WHICH status.
 *
 * Deliberately excluded: response bodies, query strings, user/role ids, headers, cookies, tokens, passwords.
 */
data class HttpRejection(val method: String, val host: String, val code: Int, val endpoint: String = "") {
    /** "POST bbs-api.tajiduo.com/apihub/awapi/sign · HTTP 402" — one line, no query, no body. */
    fun describe(): String = "$method $host$endpoint · HTTP $code"
}

/**
 * Structured, safe metadata of a rejection: implemented by [SignError], so no caller has to parse message text to
 * learn which request failed.
 */
interface ErrorMeta {
    val method: String
    val host: String
    val endpoint: String
}

class SignError(
    val kind: String,
    message: String,
    val httpStatus: Int? = null,
    override val method: String = "",
    override val host: String = "",
    override val endpoint: String = "",
    val businessCode: String = "",
) : Exception(message), ErrorMeta {
    val status get() = when (kind) { "auth", "captcha", "config" -> "needs_action"; "uncertain" -> "uncertain"; "network" -> "waiting_network"; else -> "failed" }
    /**
     * The [SignResult] for this failure (the engine's single entry point for exceptions).
     *
     * `providerCode` carries the error KIND, not a provider code: the engine decides whether the ACCOUNT may change
     * state from it ([LocalSignEngine.authStateFor] -> only auth/captcha/config). A rejection is never retryable and
     * never official.
     */
    fun asResult(): SignResult = SignResult(
        status = status,
        message = message ?: "社区请求失败",
        resultSource = ResultSource.UNKNOWN,
        providerCode = businessCode.ifBlank { kind },
        httpStatus = httpStatus,
        verificationMethod = "",
        verifiedAt = "",
        retryable = kind=="network" && method!="POST",
    )
    /** The absolute rejection this error describes, or null for a local/transport-only failure. */
    fun rejection(): HttpRejection? = httpStatus?.let { HttpRejection(method, host, it, endpoint) }
}

/**
 * Bounded, charset-checked provider token. Anything that is not 1..16 chars of `[A-Za-z0-9_-]` collapses to
 * "unknown": a provider code is a diagnostic token, never free text taken from a response body.
 */
fun safeCode(raw: String?): String = raw?.trim()?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,16}")) } ?: "unknown"

/**
 * Safe endpoint path for a rejection record: the request PATH only, no query string, ordinary characters only.
 * `/apihub/awapi/sign?gameId=1289&roleId=5` -> `/apihub/awapi/sign`.
 */
fun safeEndpoint(path: String?): String {
    val raw = path?.substringBefore('?')?.substringBefore('#')?.trim().orEmpty()
    if (raw.isEmpty() || raw.length > 96 || !raw.startsWith("/")) return ""
    if (!raw.matches(Regex("/[A-Za-z0-9._~/-]*"))) return ""
    return raw.replace(Regex("/{2,}"), "/").split('/').joinToString("/") {
        if(it.matches(Regex("[0-9]{5,}|[A-Za-z0-9_-]{24,}"))) "redacted" else it
    }
}

/**
 * Bounded, best-effort reading of a business code out of a NON-2xx body (the historical HTTP 402 case, whose body
 * used to be discarded entirely).
 *
 * Contract: at most [MAX_REJECTION_BODY] bytes are inspected, the body is never stored, logged or propagated, and
 * only a NUMERIC token from a small allowlist of key names is returned:
 *   `{"code":402}` / `{"retcode":"-100"}` / `{"error":{"code":"40001"}}` -> "402" / "-100" / "40001"
 * An HTML error page, a JSON body without such a key, a malformed body and an oversized body all yield "".
 */
object RejectionCode {
    /** Hard upper bound on bytes inspected; a hostile or verbose body can never be read whole. */
    const val MAX_REJECTION_BODY = 4 * 1024
    private val KEYS = listOf("code", "retcode", "errno", "err_code", "error_code")
    private val NUMERIC = Regex("-?[0-9]{1,12}")
    private val TAGGED_NUMERIC = Regex("<code>\\s*(-?[0-9]{1,12})\\s*</code>", RegexOption.IGNORE_CASE)
    private val LOOSE_NUMERIC = Regex("\"(?:ret)?code\"\\s*:\\s*\"?(-?[0-9]{1,12})\"?", RegexOption.IGNORE_CASE)

    /**
     * Extracts a safe business code from an already-read bounded body. JSON first (a real API rejection), then the
     * two legacy textual forms: `<code>402</code>` and a bare `"code":402`. A plain HTML page yields "".
     */
    fun fromBody(body: String?): String {
        if (body.isNullOrBlank()) return ""
        val text = body.trim()
        if (text.length > MAX_REJECTION_BODY) return ""
        if (text.startsWith("{")) {
            val json = runCatching { JSONObject(text) }.getOrNull()
            if (json != null) return codeOf(json) ?: ""
        }
        TAGGED_NUMERIC.find(text)?.groupValues?.get(1)?.let { return it }
        LOOSE_NUMERIC.find(text)?.groupValues?.get(1)?.let { return it }
        return ""
    }

    private fun codeOf(json: JSONObject): String? {
        for (key in KEYS) {
            val direct = json.opt(key)
            if (direct is Int || direct is Long) return NUMERIC.find(direct.toString())?.value
            if (direct is String) return NUMERIC.find(direct.trim())?.value
        }
        // One level of nesting covers the `{"error":{"code":…}}` shape without walking arbitrary structures.
        val nested = json.opt("error")
        if (nested is JSONObject) return codeOf(nested)
        return null
    }

    /** A bounded diagnostic token for a rejection body that carried no usable code. Never body text. */
    fun kindOf(body: String?): String {
        val text = body?.trim().orEmpty()
        if (text.isEmpty()) return ""
        return if (text.startsWith("{") || text.startsWith("[")) "no_code_field" else "non_json"
    }
}

/*
 * Provider business-code mapping.
 *
 * The acceptance branch is the historical P0 defect: `code == ok` used to be reported as an unqualified `success`
 * and its provenance was later guessed from the message. Now an acceptance is explicitly `accepted` with
 * [VerifyMethod.SUBMITTED]; only an adapter that really read an official status (or verified after the POST) may
 * return `official`.
 */
fun result(code: String, ok: String = "0", already: Set<String> = emptySet(), auth: Set<String> = emptySet(), captcha: Set<String> = emptySet()): SignResult = when (code) {
    in already -> SignResult("already", "官方响应确认今日已签", resultSource = ResultSource.OFFICIAL, providerCode = safeCode(code), verificationMethod = VerifyMethod.PROVIDER_STATUS, verifiedAt = SignRepository.now())
    in auth -> SignResult("needs_action", "授权失效，请在官方应用重新登录后导入", resultSource = ResultSource.UNKNOWN, providerCode = safeCode(code))
    in captcha -> SignResult("needs_action", "请在官方应用完成人机验证", resultSource = ResultSource.UNKNOWN, providerCode = safeCode(code))
    ok -> SignResult("accepted", "已提交，等待官方确认；不会重复提交", resultSource = ResultSource.ACCEPTED, providerCode = safeCode(code), verificationMethod = VerifyMethod.SUBMITTED)
    else -> SignResult("failed", "官方响应未确认成功，请在官方应用核对", resultSource = ResultSource.UNKNOWN, providerCode = safeCode(code))
}

/**
 * The three operations every provider implements. (Restored verbatim: this interface is the seam the engine and
 * every adapter compile against.)
 */
interface ProviderAdapter {
    fun queryStatus(c:JSONObject,t:SignTarget,persist:()->Unit):SignResult=SignResult("uncertain","该平台尚无已验证的独立查询接口，请在官方应用核对；未重新提交",resultSource=ResultSource.UNKNOWN,verificationMethod=VerifyMethod.NOT_SUPPORTED)
    fun targets(c: JSONObject, persist: () -> Unit): List<SignTarget>
    fun checkin(c: JSONObject, t: SignTarget, persist: () -> Unit): SignResult
}

fun required(c: JSONObject, vararg fields: String) {
    if (fields.any { c.optString(it).isBlank() }) throw SignError("config", "缺少授权字段，请重新导入本人授权")
}
fun JSONObject.rows(key: String): List<JSONObject> = optJSONArray(key)?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()
fun JSONObject.strings(key: String, fallback: List<String>): List<String> {
    if (!has(key)) return fallback
    val a = optJSONArray(key) ?: throw SignError("config", "游戏或角色选项必须是数组")
    return (0 until a.length()).map { a.getString(it) }
}

/*
 * The verification-GET attribution rule (P0, see docs/v077-p0-contract.md §4).
 *
 * Tagged's `get_game_sign_state` only takes a gameId, so `data.todaySign==true` is a statement about the ACCOUNT +
 * GAME, not about one role. Accepting it as a proof for whatever role we just posted would let role A's answer
 * impersonate role B's result, which is exactly the "as another role" error the audit required to exclude. The
 * three helpers below therefore make role ownership explicit and only ever enable a conservative single GET.
 */

/**
 * [taggedStateProvesRole] decision, as a value so the rule is testable without a socket.
 *
 * [authorizedRoles]: the role ids the account is verified to own for this game (from the last role enumeration).
 * [singleAuthorizedRole]: the account provably owns exactly ONE role for this game (the only case where a game-level
 * `todaySign` answer can be attributed to a role).
 */
fun taggedStateProvesRole(requestedRole: String, authorizedRoles: Set<String>, singleAuthorizedRole: Boolean): Boolean {
    if (requestedRole.isBlank()) return false
    if (authorizedRoles.isNotEmpty() && requestedRole !in authorizedRoles) return false
    return singleAuthorizedRole && authorizedRoles == setOf(requestedRole)
}

/**
 * Decides whether ONE post-submission status read may be treated as proof for [requestedRole].
 *
 * All conditions are conservative on purpose; any gap means "no proof" (the caller keeps `accepted`/`uncertain`):
 *  - the response must really be a success reply for the queried game;
 *  - the account must provably own exactly one authorized role for this game, or the response must name a role that
 *    equals the requested one;
 *  - the response's `todaySign` must be the Boolean `true` (a missing/`null`/`"true"` value proves nothing);
 *  - a response that names a DIFFERENT role is a rejection, never a proof (see [stateRoleMismatch]).
 */
fun taggedRoleProof(
    requestedRole: String,
    authorizedRoles: Set<String>,
    responseCode: String,
    responseGameId: String,
    expectedGameId: String,
    responseRoleId: String,
    todaySign: Any?,
): Boolean {
    if (responseCode != "0") return false
    if (responseGameId.isNotBlank() && responseGameId != expectedGameId) return false
    if (responseRoleId.isNotBlank() && responseRoleId != requestedRole) return false
    val namesRole = responseRoleId == requestedRole && requestedRole.isNotBlank()
    val singleRole = taggedStateProvesRole(requestedRole, authorizedRoles, authorizedRoles == setOf(requestedRole))
    if (!namesRole && !singleRole) return false
    return todaySign == true
}

/**
 * True when the state reply explicitly names a role that is NOT the requested one. The caller must not perform the
 * POST in that case: it would act on an identically named but different role, and the answer could not be attributed.
 */
fun stateRoleMismatch(requestedRole: String, responseRoleId: String): Boolean =
    requestedRole.isNotBlank() && responseRoleId.isNotBlank() && responseRoleId != requestedRole

/*
 * -------------------------------------------------------------------------------------------------------------------
 * Transport boundary
 * -------------------------------------------------------------------------------------------------------------------
 */

object Signing {
    fun md5(s: String) = MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    fun mys(t: String, r: String) = "$t,$r," + md5("salt=d9200c846b10886e8c874fc33c8f308b&t=$t&r=$r")
    fun tagged(t: String, r: String) = "$t,$r," + md5("${t}${r}1.2.5pUds3dfMkl")
    /** Match web JSON.stringify: Android JSONObject.quote unnecessarily escapes forward slashes. */
    fun sklandContext(platform: String, time: String, deviceId: String, version: String): String =
        linkedMapOf("platform" to platform, "timestamp" to time, "dId" to deviceId, "vName" to version)
            .entries.joinToString(",", "{", "}") {
                JSONObject.quote(it.key) + ":" + JSONObject.quote(it.value).replace("\\/", "/")
            }
    fun skland(path: String, body: String, time: String, context: String, token: String): String {
        val mac = Mac.getInstance("HmacSHA256"); mac.init(SecretKeySpec(token.toByteArray(), "HmacSHA256"))
        val hex = mac.doFinal((path + body + time + context).toByteArray()).joinToString("") { "%02x".format(it) }
        return md5(hex)
    }
    private val random = SecureRandom()
    fun nonce(n: Int): String { val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"; return (1..n).map { chars[random.nextInt(chars.length)] }.joinToString("") }
    fun now() = (System.currentTimeMillis() / 1000).toString()
}

interface Transport {
    fun call(method: String, url: String, headers: Map<String,String> = emptyMap(), query: Map<String,String> = emptyMap(), body: String? = null, form: Map<String,String>? = null): JSONObject
}

/**
 * Classifies a non-2xx reply into the single [SignError] the engine records. Pure so every branch is testable
 * without a socket. A 2xx reply is never passed here: its JSON body keeps the original business handling.
 *
 * 401 is a KNOWN authorization failure. 402 is NOT: it is a provider-defined rejection whose meaning we cannot
 * observe (quota, risk control, a custom rule), so it never becomes `auth`, never triggers an automatic
 * re-authorization or payment, and is never retryable. Unknown statuses stay `protocol` for the same reason.
 */
fun classifyStatus(method: String, host: String, code: Int, endpoint: String = "", businessCode: String = ""): SignError {
    val path = safeEndpoint(endpoint)
    val rejection = HttpRejection(method, host, code, path)
    val detail = "（" + rejection.describe() + "）"
    val business = businessCode.takeIf { it.matches(Regex("-?[0-9]{1,8}")) }
    fun failure(kind: String, message: String) = SignError(kind, message + if (business == null) "" else "，业务码 $business", code, method, host, path,business.orEmpty())
    return when {
        code in 300..399 -> failure("protocol", "社区发生跳转，已停止请求保护凭据$detail")
        code == 401 -> failure("auth", "授权失效，请重新导入$detail")
        code == 403 || code == 412 -> failure("captcha", "需要在官方应用完成人机验证$detail")
        // Provider rejection with an unknown cause: explain, do not guess, do not act on credentials or billing.
        code == 402 -> failure("protocol", "社区拒绝了本次请求$detail，来源未知；该响应不代表授权失效，也不触发登录或付费，请先在官方应用核对")
        code == 429 -> failure("rate_limit", "请求受限，请稍后核对$detail")
        code in 500..599 && method == "POST" -> failure("uncertain", "提交结果未确认，请先在官方应用核对$detail")
        code in 500..599 -> failure("network", "社区暂不可用，等待网络恢复后重试$detail")
        else -> failure("protocol", "社区接口状态异常$detail")
    }
}

/**
 * Renders the transport's own explanation of an HTTP-level rejection as a bracketed suffix, or nothing when the
 * transport cannot explain itself (for example a synthetic test transport). Only method/host/path/status can appear
 * here by construction, never credential material.
 */
fun rejectionDetail(transport: Transport): String = when (transport) {
    is OfficialTransport -> transport.lastRejection()?.let { "（" + it.describe() + "）" }.orEmpty()
    else -> ""
}

class OfficialTransport : Transport {
    private val hosts = setOf("api-takumi.mihoyo.com", "act-nap-api.mihoyo.com", "zonai.skland.com", "bbs-api.tajiduo.com", "api.kurobbs.com", "user.laohu.com")
    private val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).connectTimeout(5, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()
    @Volatile private var rejection: HttpRejection? = null
    /** The most recent non-2xx rejection of this transport, or null once a 2xx reply is being handled. */
    internal fun lastRejection(): HttpRejection? = rejection
    override fun call(method: String, url: String, headers: Map<String,String>, query: Map<String,String>, body: String?, form: Map<String,String>?): JSONObject {
        val parsed = url.toHttpUrl()
        if (parsed.scheme != "https" || parsed.host !in hosts || parsed.port != 443 || parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) throw SignError("protocol", "已拒绝非官方 HTTPS 地址")
        val endpoint = parsed.newBuilder().apply { query.forEach { (k,v) -> addQueryParameter(k,v) } }.build()
        val payload = if (form != null) FormBody.Builder().apply { form.forEach { (k,v) -> add(k,v) } }.build() else (body ?: "").toRequestBody("application/json; charset=utf-8".toMediaType())
        try {
            val request = Request.Builder().url(endpoint).apply { headers.forEach { (k,v) -> header(k,v) } }.method(method, if (method == "GET") null else payload).build()
            client.newCall(request).execute().use { reply ->
                // A 2xx body keeps the original business handling; only a non-2xx reply is classified.
                rejection = if (reply.code in 200..299) null else HttpRejection(method, parsed.host, reply.code, safeEndpoint(parsed.encodedPath))
                if (reply.code !in 200..299) {
                    // The historical bug: the 402 body (the only place the provider's own code lives) was dropped.
                    // Read it bounded, extract a charset-checked token, and never keep or log the body itself.
                    val business = readBusinessCode(reply)
                    throw classifyStatus(method, parsed.host, reply.code, parsed.encodedPath, business)
                }
                val source = reply.body?.source() ?: throw SignError(if(method=="POST")"uncertain" else "protocol", "社区返回空响应，结果待核对")
                if (source.request(2L * 1024 * 1024 + 1)) throw SignError(if(method=="POST")"uncertain" else "protocol", "社区响应过大，结果待核对")
                return try { JSONObject(source.readUtf8()) } catch (_: Exception) { throw SignError(if(method=="POST")"uncertain" else "protocol", "社区响应格式异常，结果待核对") }
            }
        } catch (_: IOException) { throw SignError(if (method == "POST") "uncertain" else "network", if (method == "POST") "提交时连接中断，结果不确定；请先在官方应用核对" else "联网失败，等待网络恢复后重试") }
    }

    /**
     * Reads at most [RejectionCode.MAX_REJECTION_BODY] bytes of a non-2xx body and returns ONLY a numeric business
     * code token. The body never leaves this function: it is not returned, stored, logged or attached to the error.
     */
    private fun readBusinessCode(reply: Response): String {
        val limit = RejectionCode.MAX_REJECTION_BODY.toLong()
        val peeked = runCatching {
            val source = reply.body?.source() ?: return ""
            if (!source.request(limit)) return ""
            RejectionCode.fromBody(source.peek().readUtf8(limit))
        }.getOrDefault("")
        return if (peeked.isNotBlank()) peeked else runCatching { RejectionCode.fromBody(reply.peekBody(limit).string()) }.getOrDefault("")
    }
}
