package com.glasssign.app.plugins

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.security.MessageDigest

/**
 * Thin adapter over `PackageManager`, the only Android API that can answer "is 领签 installed right
 * now, and who signed it". Kept deliberately dumb: it measures, [PluginVerifier] decides.
 *
 * `GET_SIGNING_CERTIFICATES` exists from API 28; below that `GET_SIGNATURES` is the only option and
 * a build without signatures yields an EMPTY list. Empty is treated as "not verifiable" by
 * [PluginVerifier.verifyInstalled], so on Android 8.x a package with no readable signature is
 * refused rather than assumed good. minSdk is 26, so both branches are reachable.
 */
class PackageInspector(private val context: Context) {

    private val pm: PackageManager get() = context.packageManager

    /** Null when the package is not installed for this user. */
    fun installed(): InstalledFacts? {
        val info = runCatching { readPackageInfo() }.getOrNull() ?: return null
        val signers = runCatching { signerDigests(info) }.getOrDefault(emptyList())
        return InstalledFacts(
            packageName = info.packageName,
            versionCode = longVersionCode(info),
            versionName = info.versionName,
            signingCertSha256 = signers,
        )
    }

    /** Verification of the INSTALLED copy, re-run every time the page resumes. */
    fun verifyInstalled(): VerifyReport? {
        val facts = installed() ?: return null
        return PluginVerifier.verifyInstalled(facts)
    }

    fun isInstalled(): Boolean = installed() != null

    @Suppress("DEPRECATION")
    private fun readPackageInfo(): android.content.pm.PackageInfo {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(PluginSpec.PACKAGE_NAME, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            pm.getPackageInfo(PluginSpec.PACKAGE_NAME, flags)
        }
    }

    @Suppress("DEPRECATION")
    private fun longVersionCode(info: android.content.pm.PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()

    @Suppress("DEPRECATION")
    private fun signerDigests(info: android.content.pm.PackageInfo): List<String> {
        val signatures: Array<Signature>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners
        } else {
            info.signatures
        }
        return signatures.orEmpty().mapNotNull { signature ->
            runCatching {
                Hex.encode(MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()))
            }.getOrNull()
        }
    }

    /** False only when the platform positively cannot resolve the launch intent. */
    fun hasLaunchIntent(): Boolean =
        runCatching { pm.getLaunchIntentForPackage(PluginSpec.PACKAGE_NAME) != null }.getOrDefault(false)
}
