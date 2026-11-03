package com.glasssign.app.plugins

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.io.File

/**
 * The production [ApkMetadataReader]: `PackageManager.getPackageArchiveInfo` on the downloaded file.
 *
 * This is the ONLY place in the plugin package that looks inside an APK, and it does not parse
 * anything itself — the platform already knows how. `GET_SIGNING_CERTIFICATES` exists from API 28;
 * API 26/27 fall back to the deprecated `GET_SIGNATURES`.
 *
 * The size and the SHA-256 are measured by the caller over the real bytes; this adapter fills in only
 * what the archive manifest and the signing block can tell. It reports an empty digest on purpose so
 * a caller can never mistake this for a whole-file integrity check.
 */
class AndroidApkMetadataReader(private val packageManager: PackageManager) : ApkMetadataReader {

    override fun read(file: File): ApkFacts? {
        val info = runCatching { archiveInfo(file) }.getOrNull() ?: return null
        return ApkFacts(
            sizeBytes = file.length(),
            sha256 = "",
            packageName = info.packageName,
            versionCode = versionCodeOf(info),
            versionName = info.versionName,
            signingCertSha256 = signersOf(info),
        )
    }

    @Suppress("DEPRECATION")
    private fun archiveInfo(file: File): PackageInfo? {
        val flag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageArchiveInfo(file.path, PackageManager.PackageInfoFlags.of(flag.toLong()))
        } else {
            packageManager.getPackageArchiveInfo(file.path, flag)
        }
    }

    @Suppress("DEPRECATION")
    private fun versionCodeOf(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()

    @Suppress("DEPRECATION")
    private fun signersOf(info: PackageInfo): List<String> {
        val signatures: Array<Signature>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners
        } else {
            info.signatures
        }
        return signatures.orEmpty().mapNotNull { signature ->
            runCatching { Crypto.sha256(signature.toByteArray()) }.getOrNull()
        }
    }
}
