package com.glasssign.app.ui.update

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** 下载失败时给用户看的一句话。原文照抄，不软化。 */
data class UpdateDownloadError(val message: String, val retryable: Boolean = true)

/**
 * 一次下载尝试的结果。
 *
 * 只有 [file] 存在且 [sha256] 已算出的那一份才会被交给安装流程：这里不判断"像不像 APK"，那由
 * [UpdateInstallVerifier] 与包名、versionCode、签名一起判断。
 */
sealed class UpdateDownloadResult {
    data class Success(val file: File, val sizeBytes: Long, val sha256: String) : UpdateDownloadResult()

    /** 用户自己取消的，不是网络故障，不能报"下载失败"。 */
    data object Cancelled : UpdateDownloadResult()

    data class Failed(val error: UpdateDownloadError) : UpdateDownloadResult()
}

/** 下载过程中的回调。实现方决定投递到哪个线程；这里的实现不会直接碰 Compose 状态。 */
interface UpdateDownloadObserver {
    /** [total] 为 0 表示服务器没给长度，进度条只能按已下载字节显示。 */
    fun onStart(total: Long)

    fun onProgress(bytes: Long, total: Long)
}

/** 取消令牌：先于 HTTP 请求创建，所以"还没连上就取消"也能生效。 */
class UpdateDownloadToken(private val onCancel: () -> Unit = {}) {
    private val cancelled = AtomicBoolean(false)
    @Volatile private var call: okhttp3.Call? = null

    val isCancelled: Boolean get() = cancelled.get()

    fun attach(call: okhttp3.Call) {
        this.call = call
        if (cancelled.get()) runCatching { call.cancel() }
    }

    fun cancel() {
        if (cancelled.compareAndSet(false, true)) {
            onCancel()
            call?.let { runCatching { it.cancel() } }
        }
    }
}

/** 传输层的最小接口，测试里用脚本化字节流替代真实网络。 */
fun interface UpdateDownloadTransport {
    /**
     * 把 [url] 的响应体写入 [sink]，按已写入字节调用 [onProgress]；返回真实来源的最终地址。
     * 出错抛 [IOException]；用户取消时不要把它包装成普通网络错误。
     */
    fun stream(
        url: String,
        sink: OutputStream,
        token: UpdateDownloadToken,
        onProgress: (Long) -> Unit,
    ): String
}

/** GitHub 重定向后真正提供文件的 CDN 主机；与插件页使用的是同一张白名单。 */
internal val UPDATE_DOWNLOAD_CDN_HOSTS = setOf(
    "objects.githubusercontent.com",
    "release-assets.githubusercontent.com",
    "github-releases.githubusercontent.com",
)

/**
 * okhttp 实现。安全姿态与插件页一致：不跟代理、不自动跟随重定向（GitHub 的 302 落到自家 CDN，
 * 走白名单显式跟随）、超时有限、取消直接 cancel 掉 call。
 */
class OkHttpUpdateDownloadTransport(
    private val client: OkHttpClient = buildClient(),
    private val maxHops: Int = 3,
) : UpdateDownloadTransport {

    override fun stream(
        url: String,
        sink: OutputStream,
        token: UpdateDownloadToken,
        onProgress: (Long) -> Unit,
    ): String {
        if (!UpdateUrls.isReleaseApkUrl(url)) {
            throw UpdateDownloadFailure("下载地址不是官方发布地址，已停止。", retryable = false)
        }
        var current = url
        var hop = 0
        while (true) {
            if (token.isCancelled) throw UpdateDownloadCancelledException()
            val call = client.newCall(
                Request.Builder()
                    .url(current)
                    .get()
                    .header("Accept", "application/octet-stream")
                    .header("User-Agent", USER_AGENT)
                    .build(),
            )
            token.attach(call)
            try {
                call.execute().use { reply ->
                    if (reply.code in 300..399) {
                        val location = reply.header("Location").orEmpty()
                        if (location.isBlank()) {
                            throw UpdateDownloadFailure("下载地址要求跳转但没有给出目标，已停止。", retryable = false)
                        }
                        val next = reply.request.url.resolve(location)?.toString().orEmpty()
                        hop++
                        if (hop > maxHops) throw UpdateDownloadFailure("下载跳转次数过多，已停止。", retryable = false)
                        if (!allowedHop(next)) {
                            throw UpdateDownloadFailure(
                                "下载被跳转到非 GitHub 官方主机（${com.glasssign.app.plugins.HostPolicy.describeHost(next)}），已停止。",
                                retryable = false,
                            )
                        }
                        current = next
                        return@use
                    }
                    if (reply.code !in 200..299) {
                        throw UpdateDownloadFailure("下载服务器返回 HTTP ${reply.code}。", retryable = reply.code >= 500)
                    }
                    val body = reply.body ?: throw UpdateDownloadFailure("下载服务器返回空响应。")
                    val declaredLength = body.contentLength()
                    // 大小由调用方在写入时卡住；这里只负责把字节交出去。
                    val source = body.source()
                    var total = 0L
                    val buffer = ByteArray(64 * 1024)
                    onProgress(0)
                    while (true) {
                        if (token.isCancelled) throw UpdateDownloadCancelledException()
                        val read = source.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        sink.write(buffer, 0, read)
                        total += read
                        onProgress(total)
                    }
                    sink.flush()
                    if (declaredLength > 0 && total != declaredLength) {
                        throw UpdateDownloadFailure("下载不完整：服务器声明 $declaredLength 字节，实际收到 $total 字节。")
                    }
                    return reply.request.url.toString()
                }
            } catch (e: IOException) {
                if (token.isCancelled) throw UpdateDownloadCancelledException()
                throw e
            }
        }
    }

    /** github.com 自己或固定 CDN 主机，一律 https。 */
    private fun allowedHop(url: String): Boolean {
        val (host, _) = UpdateUrls.parse(url) ?: return false
        if (host == UpdateUrls.GITHUB_WEB_HOST) return UpdateUrls.isReleaseApkUrl(url)
        return host in UPDATE_DOWNLOAD_CDN_HOSTS
    }

    companion object {
        /** 不伪装成浏览器：GitHub 对普通客户端也会重定向到 CDN。 */
        const val USER_AGENT = "Hoshiori-Android-Update"

        fun buildClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.MINUTES)
            .build()
    }
}

/** 策略性停止（地址、大小、类型），不要重试。 */
class UpdateDownloadFailure(message: String, val retryable: Boolean = true) : IOException(message)

/** 用户取消，不是网络错误。 */
class UpdateDownloadCancelledException : IOException("cancelled")

/** 应用私有缓存目录：`<cacheDir>/updates/`。只有这一个子目录会被 FileProvider 暴露。 */
object UpdateCache {
    const val DIR_NAME = "updates"
    const val APK_PREFIX = "hoshiori-update-"
    const val APK_SUFFIX = ".apk"
    const val PART_SUFFIX = ".part"

    fun dir(cacheRoot: File): File = File(cacheRoot, DIR_NAME)

    /** 一个带版本号的文件名，便于用户与日志辨认"这是哪一版"。 */
    fun apkName(version: String): String {
        // 任何可能是路径分隔、上跳或空格的字符都被替换掉：这个文件名由服务器给的版本号拼出来，
        // 必须保证它只能落在一个平铺的文件名里，不可能指向父目录。
        val safe = version.replace(Regex("[^0-9A-Za-z._-]"), "_").trim('.')
        return APK_PREFIX + safe + APK_SUFFIX
    }
}

/**
 * 把官方 release 的 APK 下载到应用私有缓存，并算好整文件 SHA-256。
 *
 * 这里刻意不做的事：不校验包名/签名（那是 [UpdateInstallVerifier] 的事，需要 PackageManager），
 * 不弹任何系统界面，也不在失败时留下半截文件。下载与校验分开，是因为前者能纯 JVM 测，
 * 后者需要平台能力。
 *
 * 三条不变量（测试固定住的）：
 *  1. `.part` 文件在一次失败/取消后一定不存在——半截 APK 比没有更糟；
 *  2. 只有完整收到、大小与 GitHub 声明一致且哈希算完的文件才会被改名为最终文件名；
 *  3. 服务器返回 HTML（限速页、登录页、错误页）时立即报"不是安装包"，而不是交给校验器假装失败。
 */
class UpdateDownloader(
    private val transport: UpdateDownloadTransport = OkHttpUpdateDownloadTransport(),
    private val expectedSize: Long = -1L,
    private val expectedSha256: String? = null,
    private val minBytes: Long = MIN_APK_BYTES,
) {

    /**
     * 下载到 [dir]，文件名 [fileName]。
     *
     * @param onProgress 在下载线程上调用，调用方负责切换线程。
     */
    suspend fun download(
        url: String,
        dir: File,
        fileName: String,
        token: UpdateDownloadToken,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): UpdateDownloadResult = withContext(Dispatchers.IO) {
        val allowed = UpdateUrls.isReleaseApkUrl(url)
        if (!allowed) {
            return@withContext UpdateDownloadResult.Failed(
                UpdateDownloadError("只从 GitHub 官方发布地址下载安装包，该地址已被拒绝。", retryable = false),
            )
        }
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) {
            return@withContext UpdateDownloadResult.Failed(UpdateDownloadError("无法创建下载目录，请检查存储空间。"))
        }
        // A cancelled writer must never delete the next attempt's temporary file.
        val part = File(dir, "$fileName.${UUID.randomUUID()}${UpdateCache.PART_SUFFIX}")
        val target = File(dir, fileName)

        try {
            FileOutputStream(part).use { sink ->
                val finalUrl = transport.stream(url, sink, token) { written ->
                    if (expectedSize > 0 && written > expectedSize) {
                        throw UpdateDownloadFailure(
                            "下载内容超过 GitHub 公布的 ${readableSizeOf(expectedSize)}，已停止。",
                            retryable = false,
                        )
                    }
                    onProgress(written, expectedSize)
                }
                // 重定向终点必须是 GitHub 或它的下载 CDN：这里是二次确认，传输层可能被替换。
                val finalHost = UpdateUrls.parse(finalUrl)?.first
                if (finalHost == null ||
                    (finalHost != UpdateUrls.GITHUB_WEB_HOST && finalHost !in UPDATE_DOWNLOAD_CDN_HOSTS)
                ) {
                    throw UpdateDownloadFailure("下载最终地址不在 GitHub 官方主机，已丢弃。", retryable = false)
                }
            }
        } catch (e: UpdateDownloadCancelledException) {
            runCatching { part.delete() }
            return@withContext UpdateDownloadResult.Cancelled
        } catch (e: UpdateDownloadFailure) {
            runCatching { part.delete() }
            return@withContext UpdateDownloadResult.Failed(
                UpdateDownloadError(e.message ?: "下载失败。", retryable = e.retryable),
            )
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) {
                runCatching { part.delete() }
                throw e
            }
            runCatching { part.delete() }
            return@withContext UpdateDownloadResult.Failed(
                UpdateDownloadError(
                    if (e is IOException) "下载中断：${e.message ?: "网络异常"}。已删除未完成的文件，请重试。"
                    else "下载失败：${e.message ?: e.javaClass.simpleName}",
                ),
            )
        }

        if(token.isCancelled) {
            part.delete()
            return@withContext UpdateDownloadResult.Cancelled
        }
        // 大小门槛：太小的一定不是 APK（常见于 404 空响应或 HTML 错误页被 CDN 包了一层）。
        val size = part.length()
        if (size < minBytes) {
            runCatching { part.delete() }
            return@withContext UpdateDownloadResult.Failed(
                UpdateDownloadError("下载到的文件只有 ${readableSizeOf(size)}，不像是完整的安装包，已删除。"),
            )
        }
        if (expectedSize > 0 && size != expectedSize) {
            runCatching { part.delete() }
            return@withContext UpdateDownloadResult.Failed(
                UpdateDownloadError(
                    "文件大小不符：GitHub 公布 ${readableSizeOf(expectedSize)}，实际收到 ${readableSizeOf(size)}，已删除。",
                ),
            )
        }
        val html = UpdateContentCheck.looksLikeHtmlFile(part)
        if (html != null) {
            runCatching { part.delete() }
            return@withContext UpdateDownloadResult.Failed(
                UpdateDownloadError("下载到的不是安装包（$html），已删除。可能是网络被拦截或返回了网页。", retryable = false),
            )
        }
        if (!UpdateContentCheck.hasZipLocalFileHeader(part) && !UpdateContentCheck.hasEndOfCentralDirectory(part)) {
            // APK 一定是 ZIP 容器。只看到一段看不出结构的字节时宁可拒绝，也不交给安装器去解析。
            runCatching { part.delete() }
            return@withContext UpdateDownloadResult.Failed(
                UpdateDownloadError("下载到的文件不是有效的 APK 压缩结构，已删除。", retryable = true),
            )
        }

        val digest = sha256Of(part)
        if(token.isCancelled) {
            part.delete()
            return@withContext UpdateDownloadResult.Cancelled
        }
        val pinned = ReleaseDigest.hex(expectedSha256)
        if (pinned != null && !digest.equals(pinned, ignoreCase = true)) {
            runCatching { part.delete() }
            return@withContext UpdateDownloadResult.Failed(
                UpdateDownloadError(
                    "文件 SHA-256 与 GitHub 公布值不一致（期望 ${ReleaseDigest.short(pinned)}，实际 ${ReleaseDigest.short(digest)}），已删除。",
                    retryable = false,
                ),
            )
        }

        runCatching { if (target.exists()) target.delete() }
        if (!part.renameTo(target)) {
            runCatching { part.delete() }
            return@withContext UpdateDownloadResult.Failed(UpdateDownloadError("无法保存下载的安装包（重命名失败）。"))
        }
        // 走到这里就是成功；所有异常分支都已经提前 return。
        UpdateDownloadResult.Success(target, size, digest)
    }

    companion object {
        /** 一个正常的星栞 APK 远大于此值；低于它的只可能是错误页或空响应。 */
        const val MIN_APK_BYTES = 64L * 1024L

        fun sha256Of(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            FileInputStream(file).use { input: InputStream ->
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    digest.update(buffer, 0, read)
                }
            }
            return com.glasssign.app.plugins.Hex.encode(digest.digest())
        }

        /** 清掉缓存里所有旧的更新包（只在这个子目录内，绝不越过它）。 */
        fun sweep(dir: File, keep: File? = null) {
            val files = runCatching { dir.listFiles() }.getOrNull() ?: return
            for (file in files) {
                if (keep != null && file.absolutePath == keep.absolutePath) continue
                if (file.name.startsWith(UpdateCache.APK_PREFIX)) runCatching { file.delete() }
            }
        }
    }
}

/**
 * "这段字节是不是网页/错误页"的判断，抽成纯函数以便用真实字节测。
 *
 * 只读文件头几百字节：HTML 错误页的特征一定在开头，而完整读一个 20 MB 文件去嗅探是没有意义的。
 */
object UpdateContentCheck {

    /** 文件头是 HTML/XML（大小写不敏感，允许 BOM 与空白）时返回中文说明，否则 `null`。 */
    fun looksLikeHtmlFile(file: File): String? {
        val head = readHead(file, 1024) ?: return null
        return looksLikeHtml(head)
    }

    fun looksLikeHtml(head: ByteArray): String? {
        val text = String(head, Charsets.ISO_8859_1)
            .trimStart('\uFEFF', ' ', '\t', '\r', '\n')
            .lowercase()
        if (text.isEmpty()) return "内容为空"
        if (text.startsWith("<!doctype html") || text.startsWith("<html")) return "内容为网页"
        if (text.startsWith("<?xml") && text.contains("<html")) return "内容为网页"
        if (text.startsWith("{") || text.startsWith("[")) return "内容是接口 JSON 而不是文件"
        if (text.contains("<html") && text.contains("</body")) return "内容为网页"
        return null
    }

    /** ZIP 本地文件头 `PK\u0003\u0004`。 */
    fun hasZipLocalFileHeader(file: File): Boolean {
        val head = readHead(file, 4) ?: return false
        return head.size >= 4 &&
            head[0] == 0x50.toByte() && head[1] == 0x4B.toByte() &&
            head[2] == 0x03.toByte() && head[3] == 0x04.toByte()
    }

    /** 末尾的中央目录结尾标记 `PK\u0005\u0006`；文件尾部读不到时返回 false。 */
    fun hasEndOfCentralDirectory(file: File): Boolean {
        val length = file.length()
        if (length < 22L) return false
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val tail = ByteArray(minOf(66L, length).toInt())
                raf.seek(length - tail.size)
                raf.readFully(tail)
                var i = 0
                while (i + 3 < tail.size) {
                    if (tail[i] == 0x50.toByte() && tail[i + 1] == 0x4B.toByte() &&
                        tail[i + 2] == 0x05.toByte() && tail[i + 3] == 0x06.toByte()
                    ) return@use true
                    i++
                }
                false
            }
        }.getOrDefault(false)
    }

    private fun readHead(file: File, count: Int): ByteArray? = runCatching {
        val size = minOf(count.toLong(), file.length()).toInt()
        if (size <= 0) return@runCatching ByteArray(0)
        FileInputStream(file).use { input ->
            val buffer = ByteArray(size)
            var offset = 0
            while (offset < size) {
                val read = input.read(buffer, offset, size - offset)
                if (read < 0) break
                offset += read
            }
            if (offset == size) buffer else buffer.copyOf(offset)
        }
    }.getOrNull()
}

/** "18.42 MB" / "812 KB"；未知大小按文案说明，不显示 "0 B"。 */
internal fun readableSizeOf(bytes: Long): String = when {
    bytes <= 0 -> "大小未知"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
    else -> String.format(java.util.Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0))
}
