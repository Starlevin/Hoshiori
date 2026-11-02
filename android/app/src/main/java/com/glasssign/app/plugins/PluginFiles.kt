package com.glasssign.app.plugins

import android.content.Context
import java.io.File

/**
 * The plugin cache directory: `<cacheDir>/plugins/`.
 *
 * WHY cacheDir AND NOWHERE ELSE (and why FileProvider exposes exactly this tree): the verified APK
 * is a third-party artifact handed to the system installer through a content URI. Keeping it under
 * `cache/plugins/` means (a) Android may evict it when storage is short, which is fine because the
 * download can be repeated, and (b) the FileProvider path file can name one subdirectory instead of
 * the app's files root. `filesDir` and the app root are deliberately never exposed; see
 * `res/xml/plugin_download_paths.xml`.
 *
 * [metadata] is the only source of manifest/certificate facts; nothing here parses an APK by hand.
 */
class PluginFiles(
    private val context: Context,
    private val metadata: ApkMetadataReader?,
) {

    fun dir(): File = File(context.cacheDir, PluginSpec.CACHE_DIR_NAME).apply { mkdirs() }

    fun finalFile(): File = File(dir(), PluginSpec.APK_FILE)

    fun partFile(): File = File(dir(), PluginSpec.APK_FILE + PluginSpec.PART_SUFFIX)

    /**
     * True only when the final file exists and is non-empty. Presence is NOT a verdict: the verdict
     * comes from [reverifyOnDisk], which re-hashes the file every time.
     */
    fun finalExists(): Boolean = finalFile().let { it.isFile && it.length() > 0 }

    /**
     * Re-verifies the file on disk from scratch. Used on every resume, because `cacheDir` can be
     * evicted, restored from a backup or replaced between two visits — so a remembered "verified"
     * flag would be a claim about the past, not about the file in front of us.
     *
     * Returns `null` when there is no file at all (the caller then reports "未下载").
     */
    fun reverifyOnDisk(): VerifyReport? {
        val file = finalFile()
        if (!file.isFile || file.length() == 0L) return null
        val facts = runCatching {
            val platform = metadata?.read(file)
            ApkFacts(
                sizeBytes = file.length(),
                sha256 = Crypto.sha256(file.inputStream()),
                packageName = platform?.packageName,
                versionCode = platform?.versionCode,
                versionName = platform?.versionName,
                signingCertSha256 = platform?.signingCertSha256.orEmpty(),
            )
        }.getOrElse {
            // An unreadable archive fails closed on the digest field, which is the field that matters.
            return VerifyReport.Failed(
                listOf(VerifyFailure(VerifyField.SHA256, PluginSpec.shortSha(PluginSpec.SHA256), "无法读取"))
            )
        }
        return PluginVerifier.verifyDownloaded(facts)
    }

    /** Deletes both the final APK and any leftover `.part`; returns true when the tree is clean. */
    fun clean(): Boolean {
        val a = runCatching { finalFile().delete() }.getOrDefault(true)
        val b = runCatching { partFile().delete() }.getOrDefault(true)
        return a && b
    }

    /** Drops a stale `.part` left behind by a process that died mid-transfer. */
    fun sweepPart() {
        runCatching { if (partFile().exists()) partFile().delete() }
    }
}
