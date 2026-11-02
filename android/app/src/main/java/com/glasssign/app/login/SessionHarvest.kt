package com.glasssign.app.login

import org.json.JSONArray
import org.json.JSONObject

/**
 * Maps an official web session (cookies + web storage) onto the credential JSON the local
 * provider adapters already consume. Pure Kotlin: unit tested without Android or network.
 *
 * The mapping is deliberately conservative — when a required field is missing the outcome is
 * [HarvestResult.Incomplete] and the caller must show a failure. It never fabricates a token.
 */
sealed class HarvestResult {
    data class Ready(val credentials: JSONObject) : HarvestResult()
    data class Incomplete(val missing: String) : HarvestResult()
}

object CookieJar {
    fun parse(header: String?, allowed: Collection<String>): Map<String, String> {
        if (header.isNullOrBlank() || allowed.isEmpty()) return emptyMap()
        val allow = allowed.map { it.lowercase() }.toSet()
        val out = LinkedHashMap<String, String>()
        header.split(';').forEach { part ->
            val at = part.indexOf('=')
            if (at <= 0) return@forEach
            val name = part.substring(0, at).trim()
            val value = part.substring(at + 1).trim()
            if (name.isEmpty() || value.isEmpty()) return@forEach
            if (name.lowercase() !in allow) return@forEach
            out[name] = value
        }
        return out
    }

    fun pick(jar: Map<String, String>, vararg names: String): String =
        names.firstNotNullOfOrNull { wanted ->
            jar.entries.firstOrNull { it.key.equals(wanted, ignoreCase = true) }?.value?.takeIf { it.isNotBlank() }
        }.orEmpty()

    fun join(jar: Map<String, String>): String = jar.entries.joinToString("; ") { "${it.key}=${it.value}" }
}

object SessionHarvest {
    /** localStorage values may be stored raw or JSON-quoted; unwrap both forms. */
    fun unwrap(value: String?): String {
        val raw = value?.trim().orEmpty()
        if (raw.length >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) {
            return runCatching { JSONObject("{\"v\":$raw}").optString("v") }.getOrDefault(raw.trim('"'))
        }
        return raw
    }

    /** The three values a Skland signature depends on; compared as one unit, never logged. */
    data class SklandSession(val cred: String, val signToken: String, val dId: String)

    /**
     * Requires two consecutive identical reads before a Skland session is handed over.
     *
     * The official page writes `SK_OAUTH_CRED_KEY`, `SK_TOKEN_CACHE_KEY` and
     * `SK_SHUMEI_DEVICE_ID_KEY` one after another, so a single poll can land in the middle of a
     * rewrite (fresh cred, previous sign token, or an account switch already started). Accepting only
     * a session observed twice in a row narrows that timing window. It does not prove the pair was
     * issued together and it does not make an officially rejected request succeed.
     *
     * State is held in memory only. [reset] must be called whenever the page or account changes.
     */
    class SklandContinuity {
        private var previous: SklandSession? = null
        private var confirmed: SklandSession? = null

        /** Clears the pending sample; the next read is treated as the first of a new pair. */
        fun reset() { previous = null; confirmed = null }

        /**
         * Records the current read and returns the confirmed session once two consecutive reads match.
         * A missing value, or any change of cred / sign token / device id, restarts the pair.
         */
        fun accept(current: SklandSession?): SklandSession? {
            if (current == null || current.cred.isEmpty() || current.signToken.isEmpty() || current.dId.isEmpty()) {
                previous = null
                return null
            }
            if (previous == current) { confirmed = current; return confirmed }
            previous = current
            return null
        }

        /** The session a previous poll already confirmed, retained so a later read cannot revoke it. */
        fun confirmed(): SklandSession? = confirmed
    }

    /** Extracts the Skland session triple, or null when the page has not supplied all three yet. */
    fun sklandSession(storage: Map<String, String>): SklandSession? {
        val cred = unwrap(storage["SK_OAUTH_CRED_KEY"])
        val signToken = unwrap(storage["SK_TOKEN_CACHE_KEY"])
        val dId = unwrap(storage["SK_SHUMEI_DEVICE_ID_KEY"])
        if (cred.isEmpty() || signToken.isEmpty() || dId.isEmpty()) return null
        return SklandSession(cred, signToken, dId)
    }

    /**
     * @param deviceId the app's own local device id. Only mihoyo keeps it in the credential; Skland
     *   must run on the official web device id, so this value is never substituted there.
     */
    fun build(provider: String, jar: Map<String, String>, storage: Map<String, String>, deviceId: String): HarvestResult =
        when (provider) {
            "miyoushe" -> miyoushe(jar, deviceId)
            "skland" -> skland(storage)
            "kurobbs" -> kurobbs(jar, storage)
            "tagged" -> tagged(jar, storage)
            else -> HarvestResult.Incomplete("该平台暂不支持网页登录")
        }

    private fun miyoushe(jar: Map<String, String>, deviceId: String): HarvestResult {
        val accountId = CookieJar.pick(jar, "account_id_v2", "account_id", "ltuid_v2", "ltuid")
        val token = CookieJar.pick(jar, "cookie_token_v2", "cookie_token", "ltoken_v2", "ltoken")
        if (accountId.isEmpty()) return HarvestResult.Incomplete("官方页面尚未返回米游社账号标识，请确认已登录成功")
        if (token.isEmpty()) return HarvestResult.Incomplete("官方页面尚未返回米游社会话令牌，请确认已登录成功")
        val stoken = CookieJar.pick(jar, "stoken", "stoken_v2")
        val mid = CookieJar.pick(jar, "mid", "account_mid_v2", "ltmid_v2")
        // Keep the platform's own cookie names, plus plain aliases the renewal routine reads.
        val cookie = LinkedHashMap(jar)
        if (!cookie.keys.any { it.equals("stuid", true) }) cookie["stuid"] = accountId
        if (mid.isNotEmpty() && !cookie.keys.any { it.equals("mid", true) }) cookie["mid"] = mid
        if (stoken.isNotEmpty() && !cookie.keys.any { it.equals("stoken", true) }) cookie["stoken"] = stoken
        return HarvestResult.Ready(
            JSONObject()
                .put("cookie", CookieJar.join(cookie))
                .put("stuid", accountId)
                .put("deviceId", deviceId)
                .put("games", JSONArray(listOf("genshin", "honkai_sr", "zzz")))
                .put("source", "web-login")
        )
    }

    private fun skland(storage: Map<String, String>): HarvestResult {
        val cred = unwrap(storage["SK_OAUTH_CRED_KEY"])
        val signToken = unwrap(storage["SK_TOKEN_CACHE_KEY"])
        // The signed request context must carry the device id the official site issued together with
        // this cred. Inventing one (a random UUID) would sign with an identity the platform never saw,
        // so an absent official id is reported as "not ready" instead of being substituted.
        val platformDevice = unwrap(storage["SK_SHUMEI_DEVICE_ID_KEY"])
        if (cred.isEmpty()) return HarvestResult.Incomplete("官方页面尚未返回森空岛登录凭据，请确认已登录成功")
        if (signToken.isEmpty()) return HarvestResult.Incomplete("官方页面尚未完成签名令牌初始化，请稍候重试")
        if (platformDevice.isEmpty()) return HarvestResult.Incomplete("官方页面尚未提供森空岛设备标识，签名环境未就绪；请稍候或重新打开登录页")
        return HarvestResult.Ready(
            JSONObject()
                .put("cred", cred)
                .put("signToken", signToken)
                .put("dId", platformDevice)
                .put("games", JSONArray(listOf("arknights", "endfield")))
                .put("communityCheckin", true)
                .put("source", "web-login")
        )
    }

    private fun kurobbs(jar: Map<String, String>, storage: Map<String, String>): HarvestResult {
        val token = CookieJar.pick(jar, "user_token")
            .ifBlank { unwrap(storage["user_token"]) }
            .ifBlank { unwrap(storage["token"]) }
        if (token.isEmpty()) return HarvestResult.Incomplete("官方页面尚未返回库街区登录令牌，请确认已登录成功")
        return HarvestResult.Ready(
            JSONObject()
                .put("token", token)
                .put("games", JSONArray(listOf("3", "2")))
                .put("communityCheckin", true)
                .put("source", "web-login")
        )
    }

    private fun tagged(jar: Map<String, String>, storage: Map<String, String>): HarvestResult {
        val refresh = CookieJar.pick(jar, "refreshToken", "refresh_token")
            .ifBlank { unwrap(storage["refreshToken"]) }
            .ifBlank { unwrap(storage["refresh_token"]) }
        val access = CookieJar.pick(jar, "access_token", "token")
            .ifBlank { unwrap(storage["accessToken"]) }
            .ifBlank { unwrap(storage["access_token"]) }
            .ifBlank { unwrap(storage["token"]) }
        val uid = CookieJar.pick(jar, "uid").ifBlank { unwrap(storage["uid"]) }.ifBlank { "0" }
        val device = CookieJar.pick(jar, "deviceId", "deviceid")
            .ifBlank { unwrap(storage["deviceId"]) }
            .ifBlank { "glasssign-local" }
        val token = refresh.ifBlank { access }
        if (token.isEmpty()) return HarvestResult.Incomplete("官方页面尚未返回塔吉多登录令牌，请确认已登录成功")
        return HarvestResult.Ready(
            JSONObject()
                .put(if (refresh.isNotEmpty()) "refreshToken" else "_accessToken", token)
                .put("uid", uid)
                .put("deviceId", device)
                .put("gameIds", JSONArray(listOf("1289")))
                .put("communityCheckin", true)
                .put("source", "web-login")
        )
    }
}
