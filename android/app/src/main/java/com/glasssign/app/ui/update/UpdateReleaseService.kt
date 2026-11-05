package com.glasssign.app.ui.update

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import okhttp3.OkHttpClient
import okhttp3.Request

/** 一次元数据应答：正文 + GitHub 的 ETag（有就用，没有就是 null）。 */
data class UpdateHttpReply(val body: String, val etag: String?, val notModified: Boolean = false)

/** 可注入的传输层：卡片状态机可以在测试里被一段脚本化应答驱动。 */
fun interface UpdateTransport {
    /** 请求 [url]；2xx 返回正文，304 返回 `notModified = true`。其它情况抛异常。 */
    fun get(url: String, etag: String?): UpdateHttpReply
}

/** 忽略 [etag] 的简化实现，测试与"不想处理条件请求"的调用方使用。 */
fun UpdateTransport.get(url: String): UpdateHttpReply = get(url, null)

/** 检查结果之外还带回"这次检查本身成不成功"，用于自动检查的限频记录。 */
data class UpdateCheckOutcome(
    val check: UpdateCheck,
    /** 通道真的应答了（2xx 或 304）。断网/超时/限流都算 false。 */
    val reached: Boolean,
    val etag: String? = null,
    /** 304：内容没变，沿用上一次的判定。 */
    val notModified: Boolean = false,
    /** 通道没有应答时，这是给用户看的那句话。 */
    val failureSentence: String? = null,
)

/**
 * 上一次成功的应答，用来让 304 复现同一个结论。
 *
 * 只存 ETag 是不够的：收到 304 时正文已经没有了，若把它当成「已是最新」，上次查出「有新版本但用户
 * 没装」的情况就会被改写成「已是最新」。所以连着正文和检查时的当前版本一起缓存，304 时对缓存正文
 * 重新跑一遍 [ReleasePolicy]。
 */
data class UpdateCachedReply(
    val etag: String,
    val body: String,
    /** 这份正文对应的端点（通道可能变，换通道的缓存不算命中）。 */
    val endpoint: String,
    /** 得到这份正文时用来比较的当前版本。版本变了就不该复用判定。 */
    val comparedWith: String,
)

/**
 * 读官方发布信息的网络调用。两条通道共享同一仓库，差异只有端点与包名规则：
 *
 * - 正式通道查 `/releases/latest`：GitHub 在这个端点不返回草稿与预发布，应答本身就是最新正式版。
 * - 预览通道必须查 `/releases` 列表：预览版在 GitHub 上通常标记为 prerelease，`/latest` 永远取不到。
 *
 * 不下载 APK、不碰磁盘；磁盘缓存由调用方（ViewModel）提供。
 */
class UpdateReleaseService(
    private val transport: UpdateTransport = OkHttpUpdateTransport(),
    private val channel: UpdateReleaseChannel = UpdateReleaseChannel.OFFICIAL,
    private val endpoint: String = endpointFor(channel),
    private val cachedReply: () -> UpdateCachedReply? = { null },
) {

    fun check(currentVersion: String, etag: String? = null): UpdateCheck =
        checkWithMeta(currentVersion, etag).check

    /** 上一次 [checkWithMeta] 真正用到的发布信息正文；调用方据此写入缓存，供下次 304 复用。 */
    var lastServedBody: String? = null
        private set

    /** 上一次 [checkWithMeta] 查询的端点（换通道后缓存不应命中）。 */
    val lastServedEndpoint: String get() = endpoint

    /**
     * [etag] 只有在该通道有可复用的缓存正文时才会真正生效；否则一律无条件重查，
     * 因为 304 在没有旧正文的情况下无法得出任何结论。
     */
    fun checkWithMeta(currentVersion: String, etag: String? = null): UpdateCheckOutcome {
        lastServedBody = null
        val reusable = cachedReply()?.takeIf {
            it.endpoint == endpoint && it.comparedWith == currentVersion && it.body.isNotBlank()
        }
        val conditionalEtag = etag?.takeIf { reusable != null && it == reusable.etag }
        val reply = try {
            transport.get(endpoint, conditionalEtag)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            val failure = failureOf(e)
            return UpdateCheckOutcome(failure, reached = false, failureSentence = failure.message)
        }
        // 304 只有在能复现旧结论时才算数；否则当作没有应答，绝不伪报"已是最新"。
        val body = if (reply.notModified) reusable?.body else reply.body
        val etagOut = if (reply.notModified) reusable?.etag else reply.etag
        if (body.isNullOrBlank()) {
            val failure = UpdateCheck.Failed(UpdateFailure.HTTP, "发布信息没有变化，但本地没有可复用的结果，请重试。")
            return UpdateCheckOutcome(failure, reached = false, failureSentence = failure.message)
        }
        lastServedBody = body
        return reduce(currentVersion, body, etagOut, notModified = reply.notModified)
    }

    /** 把一段发布信息正文归约成卡片能渲染的唯一结论。 */
    private fun reduce(
        currentVersion: String,
        body: String,
        etag: String?,
        notModified: Boolean,
    ): UpdateCheckOutcome {
        val releases = try {
            ReleaseJson.parseReleases(body)
        } catch (e: IOException) {
            return UpdateCheckOutcome(
                check = UpdateCheck.Failed(UpdateFailure.HTTP, "发布信息无法解析：${e.message ?: "格式不正确"}"),
                reached = true,
                etag = etag,
                failureSentence = "发布信息无法解析，请稍后重试。",
            )
        }
        if (releases.isEmpty()) {
            val failed = UpdateCheck.Failed(UpdateFailure.NOT_PUBLISHED, "GitHub 上暂未发布${channel.label}版本。")
            return UpdateCheckOutcome(failed, reached = true, etag = etag, failureSentence = failed.message)
        }
        val (candidate, reason) = ReleasePolicy.select(currentVersion, releases, channel)
        if (candidate != null) {
            return UpdateCheckOutcome(
                UpdateCheck.Available(candidate),
                reached = true,
                etag = etag,
                notModified = notModified,
            )
        }
        val detail = when (reason) {
            NoUpdateReason.UP_TO_DATE ->
                "已是最新版本（${channel.label}通道 ${releases.first().tag}）。"
            NoUpdateReason.MISSING_APK_OR_DIGEST ->
                "发现新版本，但该版本没有可直接下载的 ${channel.label} APK 及其 sha256 校验值。"
            NoUpdateReason.ONLY_UNUSABLE_RELEASES ->
                "发现更新的版本，但只有草稿或不可用的预发布，不作为${channel.label}更新。"
            NoUpdateReason.UNREADABLE_PAYLOAD -> "当前版本号不是严格语义版本，已拒绝比较。"
        }
        return UpdateCheckOutcome(
            UpdateCheck.UpToDate(currentVersion, reason, detail),
            reached = true,
            etag = etag,
            notModified = notModified,
        )
    }

    companion object {
        /** 正式通道查 latest，预览通道必须查列表（预览发布是 prerelease，latest 取不到）。 */
        fun endpointFor(channel: UpdateReleaseChannel): String = when (channel) {
            UpdateReleaseChannel.OFFICIAL -> UpdateUrls.LATEST_RELEASE_URL
            UpdateReleaseChannel.PREVIEW -> UpdateUrls.RELEASES_LIST_URL
        }

        /**
         * 把传输/解析异常映射成用户看的那句话。
         *
         * 顺序有意义：具体子类先匹配，所以"没网"永远不会被写成"未知错误"。
         * `ConnectException` 与 `UnknownHostException` 都按"无网络"处理，因为对用户来说它们是同一件事。
         */
        fun failureOf(e: Throwable): UpdateCheck.Failed = when (e) {
            is UnknownHostException, is ConnectException ->
                UpdateCheck.Failed(UpdateFailure.OFFLINE, "当前无网络：无法连接 GitHub，请检查网络后重试。")
            is SocketTimeoutException ->
                UpdateCheck.Failed(UpdateFailure.TIMEOUT, "连接 GitHub 超时，请稍后重试。")
            is SSLException ->
                UpdateCheck.Failed(UpdateFailure.TLS, "与 GitHub 的安全连接失败（证书或系统时间异常）。")
            is UpdateHttpException -> when (e.code) {
                403, 429 -> UpdateCheck.Failed(
                    UpdateFailure.RATE_LIMITED,
                    "GitHub 访问频率受限（HTTP ${e.code}），请稍后再试。",
                )
                404 -> UpdateCheck.Failed(
                    UpdateFailure.NOT_PUBLISHED,
                    "GitHub 上暂未发布正式版本。",
                )
                else -> UpdateCheck.Failed(UpdateFailure.HTTP, "GitHub 返回 HTTP ${e.code}，本次检查未完成。")
            }
            else -> UpdateCheck.Failed(
                UpdateFailure.OFFLINE,
                "无法完成检查：${e.message ?: e.javaClass.simpleName}",
            )
        }
    }
}

/** 非 2xx 应答，保留状态码以便精确说出"被限流"。 */
class UpdateHttpException(val code: Int) : IOException("HTTP $code")

/** okhttp 实现。超时有限，卡住的 TLS 握手不能把界面协程钉死。 */
class OkHttpUpdateTransport(
    private val client: OkHttpClient = defaultClient(),
) : UpdateTransport {

    override fun get(url: String, etag: String?): UpdateHttpReply {
        val (host, _) = UpdateUrls.parse(url)
            ?: throw IllegalArgumentException("非 https 地址：$url")
        if (host != UpdateUrls.GITHUB_API_HOST) throw IllegalArgumentException("非 GitHub API 主机：$host")
        val builder = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
        etag?.takeIf { it.isNotBlank() }?.let { builder.header("If-None-Match", it) }
        return client.newCall(builder.build()).execute().use { reply ->
            if (reply.code == 304) return UpdateHttpReply("", etag, notModified = true)
            if (!reply.isSuccessful) throw UpdateHttpException(reply.code)
            val body = reply.body?.string() ?: throw IOException("GitHub 返回空响应")
            UpdateHttpReply(body, reply.header("ETag"))
        }
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(false)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .build()
    }
}
