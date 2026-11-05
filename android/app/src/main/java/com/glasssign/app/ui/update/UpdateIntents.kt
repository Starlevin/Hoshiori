package com.glasssign.app.ui.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/**
 * What this *particular* installation is, and how it relates to an official release. Read from
 * `BuildConfig`/`PackageManager` at call time — nothing is hard-coded and nothing is assumed.
 *
 * THE ONE FACT THAT PREVENTS A BROKEN UPGRADE: a debug build has applicationId `com.glasssign.app`
 * **plus** `.debug` and its own signing key. Android therefore treats it as a DIFFERENT app: a release
 * APK cannot install over it, it cannot share the debug build's data, and uninstalling one does not
 * remove the other. The card must say that plainly before the user downloads 20 MB.
 */
/**
 * 本次安装是什么，以及它和发布包的关系。调用时从 `BuildConfig`/`PackageManager` 读取，不硬编码。
 *
 * debug 构建的 applicationId 带 `.debug` 且签名不同，Android 把它当成另一个应用：正式包无法覆盖它。
 * 卡片要在用户下载 20 MB 之前把这件事说清楚。
 */
data class AppInstallInfo(
    val versionName: String,
    val versionCode: Long,
    val applicationId: String,
    val isDebug: Boolean,
    /** 正式包使用的 applicationId：去掉 debug 后缀。 */
    val releaseApplicationId: String,
) {
    /** True when installing a release APK means "another app", not "an upgrade of this one". */
    val separateFromRelease: Boolean
        get() = isDebug || applicationId != releaseApplicationId

    /** 这条安装查哪条通道。与包名无关，由版本名决定（见 [UpdateReleaseChannel.ofVersionName]）。 */
    val channel: UpdateReleaseChannel?
        get() = if (isDebug) null else UpdateReleaseChannel.ofVersionName(versionName)

    /** 这条安装是不是预览构建。 */
    val isPreview: Boolean get() = channel == UpdateReleaseChannel.PREVIEW

    val versionLabel: String get() = versionName

    companion object {
        /**
         * `com.glasssign.app.debug` → `com.glasssign.app`。
         *
         * 由构建文件的后缀推导，而不是写死：后缀变了，卡片的话也要跟着变。
         */
        fun releaseIdFor(applicationId: String): String {
            val trimmed = applicationId.trim()
            return trimmed.removeSuffix(".debug").takeIf { it.isNotEmpty() } ?: trimmed
        }
    }
}

/**
 * Every Intent the update card can fire.
 *
 * TWO CLASSES OF ACTION, deliberately kept apart:
 *  1. EXPLICITLY EXTERNAL — the GitHub release page and the 夸克 directory. These are plain browser
 *     navigations, used when the in-app route is unavailable. The card must never claim they finish
 *     the update by themselves.
 *  2. SYSTEM HAND-OFF — [install]. It hands ONE verified file to the platform package installer
 *     through a `content://` URI. It does not install silently: the system shows its own confirmation
 *     page, and on API 26+ the user must also have granted "install unknown apps" for this app.
 *
 * WHAT IS STILL ABSENT: no `PackageInstaller.Session` write, no `INSTALL_PACKAGES` permission (a normal
 * app cannot hold it), no `DownloadManager` request, no silent install, no `ACTION_DELETE_PACKAGE`.
 *
 * PROVIDER OWNERSHIP: the update card has its OWN FileProvider,
 * authority = `${packageName}.updates.fileprovider`, declared in the manifest with
 * `res/xml/update_download_paths.xml`, which exposes exactly `<cache-path name="updates" path="updates/"/>`.
 * It is deliberately NOT the plugin page's provider (`${packageName}.plugins.fileprovider`, which exposes
 * `cache/plugins/`): the two features share no directory and no authority, so a mistake in one paths file
 * can never widen the other. The downloaded APK lives at `<cacheDir>/updates/`, which is the only tree
 * this authority can address.
 */
class UpdateIntents(private val context: Context) {

    /** The release page in a browser — the explicit fallback when in-app download is not possible. */
    fun openApk(url: String): Intent {
        if (!UpdateUrls.isReleaseApkUrl(url)) {
            throw IllegalArgumentException("仅允许打开 https 的 GitHub 下载地址：$url")
        }
        return Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** The release notes page (tag page), also a browser navigation. */
    fun openReleasePage(url: String): Intent {
        if (!UpdateUrls.isReleasePageUrl(url)) {
            throw IllegalArgumentException("仅允许打开 GitHub 发布页：$url")
        }
        return Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** The 夸克 permanent directory link. Browser only; 星栞 never reads from it. */
    fun openQuark(url: String): Intent {
        if (!UpdateUrls.isQuarkLink(url)) {
            throw IllegalArgumentException("仅允许打开 https 的夸克链接：$url")
        }
        return Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /**
     * The system's "install unknown apps" page for THIS app.
     *
     * Needed before a `content://` APK is accepted. minSdk is 26, so `ACTION_MANAGE_UNKNOWN_APP_SOURCES`
     * always exists; it only opens the system settings screen, the user still decides, and 星栞 never
     * learns whether the toggle was flipped.
     */
    fun unknownSourcesSettings(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    /** True when this app may currently hand an APK to the system installer. */
    fun canInstallPackages(): Boolean =
        runCatching { context.packageManager.canRequestPackageInstalls() }.getOrDefault(false)

    /** `${packageName}.updates.fileprovider` for THIS install. */
    private val providerAuthority: String get() = authorityFor(context.packageName)

    /**
     * The system install page for the verified file.
     *
     * `ACTION_INSTALL_PACKAGE` is the documented action for "install this one package"; `ACTION_VIEW`
     * with the APK MIME type is both the historical and the still-accepted route (and is what some
     * newer package installers answer). Both carry the same content URI; the caller decides which one
     * to start and falls back when the first is not resolvable.
     *
     * The URI MUST come from [FileProvider]: a raw `file://` URI is rejected on API 24+, while a
     * `content://` URI from our own provider only needs the one-time read grant below.
     */
    fun installPackage(apk: File): Intent =
        installBase(apk, Intent(Intent.ACTION_INSTALL_PACKAGE)).apply {
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            putExtra(Intent.EXTRA_RETURN_RESULT, true)
            putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, context.packageName)
        }

    /** Fallback shape of [installPackage]; answered by the package installer on every version we ship. */
    fun installView(apk: File): Intent = installBase(apk, Intent(Intent.ACTION_VIEW))

    private fun installBase(apk: File, base: Intent): Intent {
        val uri = FileProvider.getUriForFile(context, providerAuthority, apk)
        return base.apply {
            setDataAndType(uri, APK_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /** True when some activity can answer the Intent, checked WITHOUT launching it (API 30+ aware). */
    fun canOpen(intent: Intent): Boolean = runCatching {
        intent.resolveActivity(context.packageManager) != null
    }.getOrDefault(false)

    companion object {
        const val APK_MIME = "application/vnd.android.package-archive"

        /** Suffix of the provider that is allowed to hand ONE verified APK to the system installer. */
        const val FILE_PROVIDER_SUFFIX = ".updates.fileprovider"

        /**
         * The authority string. Derived from the runtime package name on purpose: the release build
         * ships `com.glasssign.app` and the preview build `com.glasssign.app.preview`, and each needs its
         * own provider. Hard-coding one of them would make the other throw `IllegalArgumentException`
         * the moment the install button is pressed.
         */
        fun authorityFor(packageName: String): String = packageName + FILE_PROVIDER_SUFFIX
    }
}
