package com.glasssign.app.ui.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import com.glasssign.app.plugins.Crypto
import java.io.File

/**
 * 安装包测量接口。抽出来是因为 `PackageManager` 在普通 JVM 测试里不可用，
 * 而"这条下载的文件到底是不是星栞、是不是同一签名者、版本有没有升高"的判断必须可测。
 */
interface UpdateApkReader {
    /** 读一个下载好的 APK 文件；读不到时返回 `null`（调用方按拒绝处理，绝不当作通过）。 */
    fun readApk(file: File, sha256: String): UpdateApkFacts?

    /** 读当前安装的这份应用；读不到时返回 `null`。 */
    fun readInstalled(): UpdateInstalledFacts?
}

/**
 * 生产实现：`PackageManager.getPackageArchiveInfo` 读下载文件，`getPackageInfo(本包名)` 读已安装信息。
 *
 * 这里不手工解析任何 APK 结构——平台已经会了。真正做判断的是 [UpdateInstallVerifier]，它只接收
 * 这里的测量结果，所以"签名相同"这类结论不会散落在两个地方。
 */
class PlatformUpdateApkReader(private val context: Context) : UpdateApkReader {

    private val pm: PackageManager get() = context.packageManager

    override fun readApk(file: File, sha256: String): UpdateApkFacts? {
        val info = runCatching { archiveInfo(file) }.getOrNull() ?: return null
        return UpdateApkFacts(
            sizeBytes = file.length(),
            sha256 = sha256,
            packageName = info.packageName,
            versionCode = versionCodeOf(info),
            versionName = info.versionName,
            signingCertSha256 = signersOf(info),
        )
    }

    override fun readInstalled(): UpdateInstalledFacts? {
        val info = runCatching { installedInfo(context.packageName) }.getOrNull() ?: return null
        return UpdateInstalledFacts(
            packageName = info.packageName,
            versionCode = versionCodeOf(info),
            versionName = info.versionName,
            signingCertSha256 = signersOf(info),
        )
    }

    @Suppress("DEPRECATION")
    private fun archiveInfo(file: File): PackageInfo? {
        val flags = signerFlags()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageArchiveInfo(file.path, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            pm.getPackageArchiveInfo(file.path, flags)
        }
    }

    @Suppress("DEPRECATION")
    private fun installedInfo(packageName: String): PackageInfo {
        val flags = signerFlags()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            pm.getPackageInfo(packageName, flags)
        }
    }

    /** API 28 起 `GET_SIGNING_CERTIFICATES`；26/27 只有已废弃的 `GET_SIGNATURES`。 */
    @Suppress("DEPRECATION")
    private fun signerFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES
        else PackageManager.GET_SIGNATURES

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
