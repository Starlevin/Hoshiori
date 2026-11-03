package com.glasssign.app.plugins

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * The smallest possible surface over "bytes on disk". Exists so [DownloadCoordinator] can be
 * exercised in a plain JVM test against a real temporary directory without pulling android.* in.
 */
interface PluginFileSystem {
    fun file(name: String): File
    fun exists(file: File): Boolean
    fun mkdirs(dir: File): Boolean
    fun openWrite(file: File, append: Boolean): OutputStream
    fun openRead(file: File): InputStream
    fun length(file: File): Long
    fun delete(file: File): Boolean
    /** Atomic when the platform can do it (`File.renameTo` on the same filesystem is). */
    fun rename(from: File, to: File): Boolean
}

/** A transfer that can be cancelled. Cancellation must not be retried or reported as a network error. */
interface DownloadHandle {
    fun cancel()
}

/**
 * One HTTP source. The production implementation is [PluginHttpSource] (okhttp, blocking, meant to be
 * driven from `Dispatchers.IO`); the test implementation is a scripted in-memory stream. The URL is
 * passed in so the implementation can pin the request and audit the final URL after redirects.
 */
interface DownloadSource {
    /**
     * Streams [expectedUrl] into [sink]. Progress must be reported with [onProgress] using the byte
     * count actually written. Returns the final URL the bytes really came from, so the coordinator
     * can reject a redirect that left the pinned host.
     *
     * @throws DownloadRejected for a policy stop (host/scheme/size) — never retried.
     * @throws IOException for transport problems.
     * @throws PluginCancelledException when [handle] cancelled the transfer.
     */
    fun open(expectedUrl: String, sink: OutputStream, handle: DownloadHandle, onProgress: (Long) -> Unit): String
}

class DownloadRejected(message: String) : IOException(message)

/** Internal marker so a cancelled transfer never surfaces as "网络中断". */
class PluginCancelledException : IOException("cancelled")

/**
 * Downloads the pinned file into `cacheDir/plugins/`, then verifies it, then — and only then —
 * renames `<name>.apk.part` to `<name>.apk`.
 *
 * Note on the synthetic fixtures: when the pinned artifact describes plain bytes rather than a real
 * APK (as the JVM tests do), the manifest/certificate checks find nothing and therefore fail. The
 * tests account for that by describing an artifact with no manifest expectations, so the positive
 * case still exercises the size + digest gates on real bytes.
 *
 * INVARIANTS THE TESTS PIN:
 *  1. The `.part` file never survives a failure or a cancellation. A half-written APK that looks
 *     installable is worse than no file at all.
 *  2. The final file only ever holds bytes that passed [PluginVerifier]: the rename happens *after*
 *     verification, so a failed candidate is deleted rather than renamed.
 *  3. A verification failure reports which field failed (via [VerifyReport.detail]) and yields no
 *     installable result.
 *  4. The class is synchronous and publishes nothing itself; the caller decides which thread runs it
 *     and where progress goes (see `PluginsViewModel`, which runs it on `Dispatchers.IO`).
 */
class DownloadCoordinator(
    private val source: DownloadSource,
    private val fs: PluginFileSystem,
    private val metadata: ApkMetadataReader,
    private val finalName: String = PluginSpec.APK_FILE,
    private val artifact: PluginArtifact = PluginArtifact.of(PluginSpec),
) {
    /** `<name>.apk.part` — the only name a transfer is ever allowed to write. */
    fun partName(finalName: String = this.finalName) = finalName + PluginSpec.PART_SUFFIX

    /** Result of one attempt. [failedReason] is shown to the user verbatim. */
    data class Result(
        val apk: File?,
        val ok: Boolean,
        val failedReason: String? = null,
        val cancelled: Boolean = false,
    )

    /** Deletes a leftover `.part` file (e.g. from a process that died mid-download). */
    fun sweepPart(dir: File) {
        runCatching { fs.delete(fs.file(join(dir, partName(finalName)))) }
    }

    /**
     * Runs one download attempt to completion.
     *
     * @param dir the plugin cache directory
     * @param handle handed to the source before any byte is read, so a cancel from the UI reaches it
     * @param onProgress called on the calling thread; the caller decides where the value is published
     */
    fun download(
        dir: File,
        handle: DownloadHandle,
        onProgress: (Long) -> Unit,
    ): Result {
        if (!fs.mkdirs(dir) && !fs.exists(dir)) {
            return Result(null, false, "无法创建插件缓存目录（${dir.name}）")
        }
        val part = fs.file(join(dir, partName(finalName)))
        val target = fs.file(join(dir, finalName))
        // A previous run may have died between writes; never append to unknown bytes.
        runCatching { fs.delete(part) }
        val finalUrl: String
        try {
            val written = fs.openWrite(part, false)
            written.use { sink ->
                val expected = artifact.downloadUrl
                finalUrl = source.open(expected, sink, handle) { seen -> onProgress(seen) }
            }
        } catch (e: PluginCancelledException) {
            runCatching { fs.delete(part) }
            return Result(null, false, PluginState.FAIL_CANCELLED, cancelled = true)
        } catch (e: DownloadRejected) {
            runCatching { fs.delete(part) }
            return Result(null, false, e.message ?: PluginState.FAIL_HOST)
        } catch (e: IOException) {
            runCatching { fs.delete(part) }
            return Result(null, false, (e.message ?: PluginState.FAIL_IO))
        } catch (e: Exception) {
            runCatching { fs.delete(part) }
            return Result(null, false, "下载异常：${e.javaClass.simpleName}")
        }

        // Redirect audit: a 302 away from the pinned host would silently turn this into "download
        // from wherever GitHub points", so the final URL is checked with the same rule as the request.
        val originalHost=HostPolicy.allows(finalUrl, artifact.downloadHost, artifact.downloadScheme, artifact.downloadPort)
        val cdnHost=HostPolicy.hostOf(finalUrl)
        val officialCdn=artifact.downloadHost==PluginSpec.DOWNLOAD_HOST && cdnHost in PluginSpec.DOWNLOAD_CDN_HOSTS &&
            HostPolicy.allows(finalUrl,cdnHost,artifact.downloadScheme,artifact.downloadPort)
        if (!originalHost && !officialCdn) {
            runCatching { fs.delete(part) }
            return Result(null, false, PluginState.FAIL_HOST + "（最终地址 ${HostPolicy.describeHost(finalUrl)}）")
        }

        // Size gate before hashing: cheap, and it keeps a runaway transfer from being hashed at all.
        val actualSize = fs.length(part)
        if (artifact.sizeBytes > 0 && actualSize > artifact.sizeBytes) {
            runCatching { fs.delete(part) }
            return Result(null, false, PluginState.FAIL_OVERFLOW)
        }

        val facts = measure(part)
        val report = PluginVerifier.verifyDownloadedWith(facts, artifact)
        if (report !is VerifyReport.Passed) {
            runCatching { fs.delete(part) }
            return Result(null, false, PluginState.FAIL_VERIFY + report.detail)
        }

        // Verified: only now may the file take its final name.
        runCatching { fs.delete(target) }
        if (!fs.rename(part, target)) {
            runCatching { fs.delete(part) }
            return Result(null, false, "无法保存已核验的安装包（重命名失败）")
        }
        return Result(target, true)
    }

    /**
     * Measures a candidate. The byte size and the SHA-256 are measured HERE (over the real file);
     * the manifest identity and the signing certificate come from the injected [ApkMetadataReader],
     * which is `PackageManager` in production and a fake in tests. Nothing is parsed by hand.
     */
    private fun measure(file: File): ApkFacts {
        val size = fs.length(file)
        val digest = fs.openRead(file).use { input -> Crypto.sha256(input) }
        val platform = metadata.read(file)
        return ApkFacts(
            sizeBytes = size,
            sha256 = digest,
            packageName = platform?.packageName,
            versionCode = platform?.versionCode,
            versionName = platform?.versionName,
            signingCertSha256 = platform?.signingCertSha256.orEmpty(),
        )
    }

    /** Deletes one file under the plugin dir; returns true when nothing is left behind. */
    fun delete(name: String, dir: File): Boolean = runCatching { fs.delete(fs.file(join(dir, name))) }.getOrDefault(false)

    private fun join(dir: File, name: String): String = File(dir, name).path
}

/**
 * URL policy shared by the request and the redirect audit. Pure string logic so the rule itself is
 * unit-testable without a URI parser difference between the JVM and Android.
 */
object HostPolicy {
    fun schemeOf(url: String): String = url.substringBefore("://", "").lowercase()

    /** Host part without userinfo and without port. A userinfo (`user@host`) is refused upstream. */
    fun hostOf(url: String): String {
        val afterScheme = url.substringAfter("://", "")
        val authority = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
        val withoutUser = authority.substringAfterLast('@')
        return when {
            withoutUser.startsWith("[") -> withoutUser.substringBefore(']') + "]"
            else -> withoutUser.substringBefore(':')
        }.lowercase()
    }

    fun portOf(url: String): Int {
        val afterScheme = url.substringAfter("://", "")
        val authority = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
        if (authority.contains('@')) return -1
        if (authority.startsWith("[")) {
            val rest = authority.substringAfter("]", "")
            return if (rest.startsWith(":")) rest.drop(1).toIntOrNull() ?: -1 else -1
        }
        if (!authority.contains(':')) return -1
        return authority.substringAfterLast(':').toIntOrNull() ?: -1
    }

    fun allows(url: String, host: String, scheme: String, port: Int): Boolean {
        if (!url.contains("://")) return false
        if (schemeOf(url) != scheme.lowercase()) return false
        if (url.substringAfter("://").substringBefore('/').contains('@')) return false // no userinfo
        if (hostOf(url) != host.lowercase()) return false
        val p = portOf(url)
        return p == -1 || p == port
    }

    /** Host only, for messages: never a query string or a token-shaped path segment. */
    fun describeHost(url: String): String = hostOf(url).ifBlank { "未知主机" }
}
