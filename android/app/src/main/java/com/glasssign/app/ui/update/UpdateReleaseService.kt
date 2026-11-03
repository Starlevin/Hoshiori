package com.glasssign.app.ui.update

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import okhttp3.OkHttpClient
import okhttp3.Request

/** Injectable transport so the card's state machine can be driven by a scripted answer in tests. */
fun interface UpdateTransport {
    /** Performs the request; returns the body text of a 2xx answer, throws on anything else. */
    fun get(url: String): String
}

/**
 * The ONE network call the update card makes: the official GitHub release metadata.
 *
 * BOTH CHANNELS SHARE THIS RESULT. The GitHub channel and the 夸克 channel are two ways to obtain the
 * same file, so they must agree on *what* the newest version is; asking two sources would let them
 * disagree and turn a mirror into a second, unverifiable truth. The 夸克 channel only contributes the
 * launch URL, which comes from a bundled asset.
 *
 * WHAT THIS CLASS DELIBERATELY DOES NOT DO: it never downloads an APK, never touches disk, never
 * caches. It returns a value or throws an [IOException] subclass, and [UpdateFailure] maps those
 * outcomes to the sentences the user sees.
 */
class UpdateReleaseService(
    private val transport: UpdateTransport = OkHttpUpdateTransport(),
    private val endpoint: String = UpdateUrls.LATEST_RELEASE_URL,
) {

    /**
     * Reads the official release list and reduces it to the one verdict the card can render, relative
     * to [currentVersion].
     *
     * `/releases/latest` is asked on purpose: GitHub never returns a draft or a pre-release there, so
     * the answer is already "the newest published release". The list is still filtered by
     * [ReleasePolicy] because the endpoint alone does not guarantee a strict `X.Y.Z` tag, an APK
     * asset, or that the version is newer than this build.
     *
     * A 404 is reported as 「暂未发布」 and not as a network failure: GitHub uses 404 both for "no
     * published release yet" and for "repository not visible", and neither is something the user can
     * fix by retrying. That sentence is honest in both cases.
     */
    fun check(currentVersion: String): UpdateCheck {
        val body = try {
            transport.get(endpoint)
        } catch (e: Exception) {
            // Cancellation is a control signal, not a transport failure: let coroutine machinery see it.
            // `kotlinx.coroutines.CancellationException` is a typealias of the JVM class, so this single
            // check covers both `job.cancel()` and a transport that surfaces it directly.
            if (e is kotlinx.coroutines.CancellationException) throw e
            return failureOf(e)
        }
        val releases = try {
            ReleaseJson.parseReleases(body)
        } catch (e: IOException) {
            return UpdateCheck.Failed(UpdateFailure.HTTP, "发布信息无法解析：${e.message ?: "格式不正确"}")
        }
        if (releases.isEmpty()) {
            return UpdateCheck.Failed(UpdateFailure.NOT_PUBLISHED, "GitHub 上暂未发布正式版本。")
        }
        val (candidate, reason) = ReleasePolicy.select(currentVersion, releases)
        if (candidate != null) return UpdateCheck.Available(candidate)
        val detail = when (reason) {
            NoUpdateReason.UP_TO_DATE -> "已是最新版本（正式通道 ${releases.first().tag}）。"
            NoUpdateReason.MISSING_APK_OR_DIGEST -> "发现新版本，但该版本没有可直接下载的 APK 及其 sha256 校验值。"
            NoUpdateReason.ONLY_UNUSABLE_RELEASES -> "发现更新的版本，但只有草稿或预发布，不作为正式更新。"
            NoUpdateReason.UNREADABLE_PAYLOAD -> "当前版本号不是严格语义版本，已拒绝比较。"
        }
        return UpdateCheck.UpToDate(currentVersion, reason, detail)
    }

    companion object {
        /**
         * Maps a thrown transport/parsing exception to the user-facing failure sentence.
         *
         * Order matters: the specific subclasses are matched before the catch-all, so "no network" is
         * never reported as a generic failure. `ConnectException` and `UnknownHostException` both
         * surface as "offline" because from the user's point of view they are the same situation.
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

/** A non-2xx HTTP answer, with the code preserved so rate limiting can be named precisely. */
class UpdateHttpException(val code: Int) : IOException("HTTP $code")

/**
 * okhttp transport. Finite timeouts, no redirect following beyond the same host, no proxy: this is a
 * metadata read, and a hanging TLS handshake must never pin the UI's coroutine.
 */
class OkHttpUpdateTransport(
    private val client: OkHttpClient = defaultClient(),
) : UpdateTransport {

    override fun get(url: String): String {
        val (host, _) = UpdateUrls.parse(url)
            ?: throw IllegalArgumentException("非 https 地址：$url")
        if (host != UpdateUrls.GITHUB_API_HOST) throw IllegalArgumentException("非 GitHub API 主机：$host")
        val request = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build()
        return client.newCall(request).execute().use { reply ->
            if (!reply.isSuccessful) throw UpdateHttpException(reply.code)
            reply.body?.string() ?: throw IOException("GitHub 返回空响应")
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
