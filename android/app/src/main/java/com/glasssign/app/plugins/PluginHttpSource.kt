package com.glasssign.app.plugins

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Plain HTTPS GET for the pinned artifact. This is the ONLY network code in the plugins package.
 *
 * SECURITY POSTURE (each point is a deliberate choice, not a default):
 *  - One client, no proxy: a user proxy could replace the bytes. The pinned SHA-256 would catch it,
 *    but an app download has no reason to be routed through one.
 *  - Redirects are DISABLED (`followRedirects=false`): GitHub answers a release download with a
 *    302 to its own CDN, and following it automatically would silently move the transfer to a host
 *    星栞 never audited. The hop is handled explicitly in [PluginHttpSource.open] against a fixed
 *    allow-list, and whatever arrives is still gated by the pinned SHA-256.
 *  - Timeouts are finite: a hung TLS handshake must not pin a coroutine forever.
 *  - Cancellation goes through okhttp's `Call.cancel()`, so an abandoned page stops consuming the
 *    user's mobile data instead of only ignoring the result. [DownloadCancelToken] is registered
 *    BEFORE the call exists, so a cancel that arrives early is still honoured.
 */
object PluginHttpClient {
    fun build(): OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.MINUTES)
        .build()

    /**
     * Hosts GitHub itself redirects release downloads to. Following one of these is not "trusting a
     * random host": the bytes are hashed and compared with the pinned digest, so the host can lie
     * about the content but cannot change which file is accepted.
     */
    val CDN_HOSTS = PluginSpec.DOWNLOAD_CDN_HOSTS
    const val CDN_SCHEME = "https"
}

/**
 * Cancellation token that can be handed to the UI before the HTTP call exists. Cancel is
 * idempotent and sticky: if it arrives first, [attach] cancels the call immediately.
 */
class DownloadCancelToken : DownloadHandle {
    private val cancelled = AtomicBoolean(false)
    @Volatile private var call: okhttp3.Call? = null

    val isCancelled: Boolean get() = cancelled.get()

    /** Registered by the source right after the call is created. */
    fun attach(call: okhttp3.Call) {
        this.call = call
        if (cancelled.get()) runCatching { call.cancel() }
    }

    override fun cancel() {
        cancelled.set(true)
        call?.let { runCatching { it.cancel() } }
    }
}

class PluginHttpSource(
    private val client: OkHttpClient = PluginHttpClient.build(),
    private val maxHops: Int = 3,
) : DownloadSource {

    override fun open(
        expectedUrl: String,
        sink: OutputStream,
        handle: DownloadHandle,
        onProgress: (Long) -> Unit,
    ): String {
        if (!HostPolicy.allows(expectedUrl, PluginSpec.DOWNLOAD_HOST, PluginSpec.DOWNLOAD_SCHEME, PluginSpec.DOWNLOAD_PORT)) {
            throw DownloadRejected(PluginState.FAIL_HOST)
        }
        val token = handle as? DownloadCancelToken
        var url = expectedUrl
        var hop = 0
        while (true) {
            if (token?.isCancelled == true) throw PluginCancelledException()
            val call = client.newCall(
                Request.Builder()
                    .url(url)
                    .get()
                    .header("Accept", "application/octet-stream")
                    .build()
            )
            token?.attach(call)
            try {
                call.execute().use { reply ->
                    if (reply.isRedirect) {
                        val location = reply.header("Location").orEmpty()
                        if (location.isBlank()) throw DownloadRejected("下载地址要求跳转但未给出目标，已停止")
                        val next = reply.request.url.resolve(location)?.toString().orEmpty()
                        hop++
                        if (hop > maxHops) throw DownloadRejected("下载跳转次数过多，已停止")
                        if (!allowedHop(next)) {
                            throw DownloadRejected("下载被跳转到非固定主机（${HostPolicy.describeHost(next)}），已停止")
                        }
                        url = next
                    } else {
                        if (reply.code !in 200..299) throw IOException("下载服务器返回 HTTP ${reply.code}")
                        val body = reply.body ?: throw IOException("下载服务器返回空响应")
                        val declared = body.contentLength()
                        if (declared > PluginSpec.SIZE_BYTES) {
                            // Guaranteed to fail the size check later; refuse before writing megabytes.
                            throw DownloadRejected("服务器声明的文件大小 $declared 字节超过固定版本，已停止")
                        }
                        val source = body.source()
                        var total = 0L
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            if (token?.isCancelled == true) throw PluginCancelledException()
                            val read = source.read(buffer)
                            if (read < 0) break
                            if (read == 0) continue
                            total += read
                            if (total > PluginSpec.SIZE_BYTES) throw DownloadRejected(PluginState.FAIL_OVERFLOW)
                            sink.write(buffer, 0, read)
                            onProgress(total)
                        }
                        sink.flush()
                        return reply.request.url.toString()
                    }
                }
            } catch (e: IOException) {
                if (token?.isCancelled == true) throw PluginCancelledException()
                throw e
            }
        }
    }

    /** github.com itself (a re-issued request) or one of the pinned CDN hosts, always over https. */
    private fun allowedHop(url: String): Boolean {
        if (HostPolicy.allows(url, PluginSpec.DOWNLOAD_HOST, PluginSpec.DOWNLOAD_SCHEME, PluginSpec.DOWNLOAD_PORT)) return true
        val host = HostPolicy.hostOf(url)
        return host in PluginHttpClient.CDN_HOSTS && HostPolicy.allows(url, host, PluginHttpClient.CDN_SCHEME, 443)
    }
}
