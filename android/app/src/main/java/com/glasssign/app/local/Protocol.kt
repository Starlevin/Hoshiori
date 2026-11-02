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
data class SignResult(val status: String, val message: String)
class SignError(val kind: String, message: String) : Exception(message) {
    val status get() = when (kind) { "auth", "captcha", "config" -> "needs_action"; "uncertain" -> "uncertain"; "network" -> "waiting_network"; else -> "failed" }
}
interface ProviderAdapter {
    fun queryStatus(c:JSONObject,t:SignTarget,persist:()->Unit):SignResult=SignResult("uncertain","该平台尚无已验证的独立查询接口，请在官方应用核对；未重新提交")
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
fun result(code: String, ok: String = "0", already: Set<String> = emptySet(), auth: Set<String> = emptySet(), captcha: Set<String> = emptySet()): SignResult = when (code) {
    in already -> SignResult("already", "官方响应确认今日已签")
    in auth -> SignResult("needs_action", "授权失效，请在官方应用重新登录后导入")
    in captcha -> SignResult("needs_action", "请在官方应用完成人机验证")
    ok -> SignResult("success", "官方接口已接受签到，请以官方记录为准")
    else -> SignResult("failed", "官方响应未确认成功，请在官方应用核对")
}
object Signing {
    fun md5(s: String) = MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    fun mys(t: String, r: String) = "$t,$r," + md5("salt=d9200c846b10886e8c874fc33c8f308b&t=$t&r=$r")
    fun tagged(t: String, r: String) = "$t,$r," + md5("${t}${r}1.2.5pUds3dfMkl")
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
class OfficialTransport : Transport {
    private val hosts = setOf("api-takumi.mihoyo.com", "act-nap-api.mihoyo.com", "zonai.skland.com", "bbs-api.tajiduo.com", "api.kurobbs.com", "user.laohu.com")
    private val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).connectTimeout(5, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()
    override fun call(method: String, url: String, headers: Map<String,String>, query: Map<String,String>, body: String?, form: Map<String,String>?): JSONObject {
        val parsed = url.toHttpUrl()
        if (parsed.scheme != "https" || parsed.host !in hosts || parsed.port != 443 || parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) throw SignError("protocol", "已拒绝非官方 HTTPS 地址")
        val endpoint = parsed.newBuilder().apply { query.forEach { (k,v) -> addQueryParameter(k,v) } }.build()
        val payload = if (form != null) FormBody.Builder().apply { form.forEach { (k,v) -> add(k,v) } }.build() else (body ?: "").toRequestBody("application/json; charset=utf-8".toMediaType())
        try {
            val request = Request.Builder().url(endpoint).apply { headers.forEach { (k,v) -> header(k,v) } }.method(method, if (method == "GET") null else payload).build()
            client.newCall(request).execute().use { reply ->
                when (reply.code) {
                    in 300..399 -> throw SignError("protocol", "社区发生跳转，已停止请求保护凭据")
                    401 -> throw SignError("auth", "授权失效，请重新导入")
                    403,412 -> throw SignError("captcha", "需要在官方应用完成人机验证")
                    429 -> throw SignError("rate_limit", "请求受限，请稍后核对")
                    in 500..599 -> throw SignError(if (method == "POST") "uncertain" else "network", if (method == "POST") "提交结果未确认，请先在官方应用核对" else "社区暂不可用，等待网络恢复后重试")
                    !in 200..299 -> throw SignError("protocol", "社区接口状态异常")
                }
                val source = reply.body?.source() ?: throw SignError(if(method=="POST")"uncertain" else "protocol", "社区返回空响应，结果待核对")
                if (source.request(2L * 1024 * 1024 + 1)) throw SignError(if(method=="POST")"uncertain" else "protocol", "社区响应过大，结果待核对")
                return try { JSONObject(source.readUtf8()) } catch (_: Exception) { throw SignError(if(method=="POST")"uncertain" else "protocol", "社区响应格式异常，结果待核对") }
            }
        } catch (_: IOException) { throw SignError(if (method == "POST") "uncertain" else "network", if (method == "POST") "提交时连接中断，结果不确定；请先在官方应用核对" else "联网失败，等待网络恢复后重试") }
    }
}
