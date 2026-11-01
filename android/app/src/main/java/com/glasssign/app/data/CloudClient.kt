package com.glasssign.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URI
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Single-owner HTTPS client for the private GlassSign backend.
 *
 * Security properties:
 * * only HTTPS, redirects are refused (an API key must never be forwarded);
 * * the API key is decrypted from [SecureStore] for each request and never logged;
 * * server errors are reduced to the backend's own short `detail` message.
 */
class CloudClient(context: Context) {

    private val store = SecureStore(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

    companion object {
        private const val PREFS = "glasssign_v3"
        private const val CONNECT_TIMEOUT = 10_000
        private const val READ_TIMEOUT = 45_000
        private const val USER_AGENT = "GlassSign-Android/0.3"
    }

    fun server(): String = store.server()

    fun hasToken(): Boolean = store.hasToken()

    fun configured(): Boolean = server().startsWith("https://") && hasToken()

    fun reduceMotion(): Boolean = store.reduceMotion()

    fun setReduceMotion(value: Boolean) = store.setReduceMotion(value)

    fun serverDisplay(): String {
        val value = server()
        if (value.isBlank()) return "未配置"
        return runCatching {
            val uri = URI(value)
            buildString {
                append(uri.host ?: value)
                if (uri.port > 0) append(":").append(uri.port)
                if (!uri.path.isNullOrBlank() && uri.path != "/") append(uri.path)
            }
        }.getOrDefault(value)
    }

    /** Save address and key. A blank key keeps the existing Keystore entry. */
    fun configure(rawServer: String, apiKey: String?) {
        val normalized = normalizeServer(rawServer)
        val key = apiKey?.trim().orEmpty()
        if (key.isNotEmpty()) {
            require(key.length >= 32) { "API 密钥至少 32 个字符" }
            store.saveToken(key)
        } else {
            require(store.hasToken()) { "请输入至少 32 个字符的 API 密钥" }
        }
        store.saveServer(normalized)
    }

    fun clearConfiguration() = store.clearAll()

    private fun normalizeServer(raw: String): String {
        val text = raw.trim().trimEnd('/')
        require(text.startsWith("https://", ignoreCase = true)) { "云端地址必须使用 HTTPS" }
        require(!text.contains(" ")) { "云端地址不能包含空格" }
        val uri = try {
            URI(text)
        } catch (_: Exception) {
            throw IllegalArgumentException("云端地址格式有误")
        }
        require(uri.scheme.equals("https", ignoreCase = true)) { "云端地址必须使用 HTTPS" }
        require(!uri.host.isNullOrBlank()) { "云端地址缺少域名" }
        require(uri.userInfo == null) { "地址中不能包含用户名或密码" }
        require(uri.query == null && uri.fragment == null) { "地址不能包含查询参数或锚点" }
        return text
    }

    suspend fun overview(): Overview = withContext(Dispatchers.IO) {
        parseOverview(request("/v1/overview"))
    }

    suspend fun status(): JSONObject = withContext(Dispatchers.IO) { request("/v1/status") }

    suspend fun runAsync(accountIds: List<String>? = null): String = withContext(Dispatchers.IO) {
        val payload = JSONObject()
        if (accountIds != null) {
            payload.put("account_ids", org.json.JSONArray(accountIds))
        }
        val response = request("/v1/sign/run?wait=false", "POST", payload)
        response.optString("job_id").ifBlank { throw CloudException("云端未返回任务编号") }
    }

    suspend fun job(jobId: String): JobSnapshot = withContext(Dispatchers.IO) {
        parseJob(request("/v1/jobs/$jobId"))
    }

    suspend fun awaitJob(jobId: String, timeoutMs: Long = 150_000): JobSnapshot {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last = JobSnapshot(jobId, "queued", null, 0, 0, false, 0)
        while (System.currentTimeMillis() < deadline) {
            last = job(jobId)
            when (last.state) {
                "done" -> return last
                "error" -> throw CloudException(last.error ?: "云端任务执行失败")
            }
            delay(1_200)
        }
        throw CloudException("云端任务仍在执行，请稍后刷新")
    }

    suspend fun addAccount(
        provider: String,
        nickname: String,
        credentials: JSONObject,
        consent: Boolean,
    ): String? = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("provider", provider)
            .put("nickname", nickname.trim())
            .put("credentials", credentials)
            .put("auto_sign_consent", consent)
        request("/v1/accounts?wait=false", "POST", body).optString("initial_job").ifBlank { null }
    }

    suspend fun updateCredentials(
        accountId: String,
        provider: String,
        nickname: String,
        credentials: JSONObject,
    ): String? = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("provider", provider)
            .put("nickname", nickname.trim())
            .put("credentials", credentials)
            .put("auto_sign_consent", true)
        request("/v1/accounts/$accountId/credentials?wait=false", "POST", body)
            .optString("initial_job").ifBlank { null }
    }

    /**
     * Read-only authorization probe. The backend only performs role discovery, so
     * a successful result can never be mistaken for a completed check-in.
     */
    suspend fun verifyAuthorization(accountId: String): AuthorizationCheck =
        withContext(Dispatchers.IO) {
            parseAuthorizationCheck(request("/v1/accounts/$accountId/verify", "POST", JSONObject()))
        }

    suspend fun setEnabled(accountId: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        request("/v1/accounts/$accountId/enabled", "POST",
            JSONObject().put("enabled", enabled))
        Unit
    }

    suspend fun deleteAccount(accountId: String) = withContext(Dispatchers.IO) {
        request("/v1/accounts/$accountId/delete", "POST", JSONObject().put("confirm", true))
        Unit
    }

    suspend fun history(days: Int = 7): List<HistoryDay> = withContext(Dispatchers.IO) {
        parseHistory(request("/v1/records/history?days=$days"))
    }

    suspend fun dayRecords(day: String): DayRecords = withContext(Dispatchers.IO) {
        parseDayRecords(request("/v1/records?day=$day"))
    }

    private fun request(path: String, method: String = "GET", payload: JSONObject? = null): JSONObject {
        val token = store.readToken()
            ?: throw CloudException("本地密钥已失效，请到设置中重新填写 API 密钥")
        val connection = (URL(server() + path).openConnection() as? HttpsURLConnection)
            ?: throw CloudException("云端地址必须是 HTTPS")
        return try {
            connection.connectTimeout = CONNECT_TIMEOUT
            connection.readTimeout = READ_TIMEOUT
            connection.instanceFollowRedirects = false
            connection.requestMethod = method
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Cache-Control", "no-store")
            connection.setRequestProperty("User-Agent", USER_AGENT)
            if (method != "GET") {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                val bytes = (payload ?: JSONObject()).toString().toByteArray(Charsets.UTF_8)
                connection.outputStream.use { it.write(bytes) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code in 300..399) {
                throw CloudException("云端地址发生跳转，已拒绝发送密钥，请检查域名配置", code)
            }
            if (code !in 200..299) {
                val detail = runCatching {
                    JSONObject(text).optString("detail").takeIf { it.isNotBlank() }
                }.getOrNull()
                throw CloudException(detail ?: "云端返回 HTTP $code", code)
            }
            if (text.isBlank()) JSONObject() else JSONObject(text)
        } catch (error: CloudException) {
            throw error
        } catch (error: org.json.JSONException) {
            throw CloudException("云端响应格式异常，请检查服务版本")
        } catch (error: Exception) {
            throw CloudException(
                when (error) {
                    is java.net.SocketTimeoutException -> "连接云端超时，请检查网络与服务状态"
                    is java.net.UnknownHostException -> "无法解析云端域名，请检查地址"
                    is javax.net.ssl.SSLException -> "HTTPS 证书校验失败，请使用受信任的证书"
                    else -> "无法连接云端：${error.javaClass.simpleName}"
                }
            )
        } finally {
            connection.disconnect()
        }
    }
}
