package com.glasssign.app.ui.update

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * What this *particular* installation is, and how it relates to an official release. Read from
 * `BuildConfig`/`PackageManager` at call time — nothing is hard-coded and nothing is assumed.
 *
 * THE ONE FACT THAT PREVENTS A BROKEN UPGRADE: a debug build has applicationId `com.glasssign.app`
 * **plus** `.debug` and its own signing key. Android therefore treats it as a DIFFERENT app: a release
 * APK cannot install over it, it cannot share the debug build's data, and uninstalling one does not
 * remove the other. The card must say that plainly before the user downloads 20 MB.
 */
data class AppInstallInfo(
    val versionName: String,
    val versionCode: Long,
    val applicationId: String,
    val isDebug: Boolean,
    /** The id the official release APK will carry: the debug suffix removed. */
    val releaseApplicationId: String,
) {
    /** True when installing a release APK means "another app", not "an upgrade of this one". */
    val separateFromRelease: Boolean
        get() = isDebug || applicationId != releaseApplicationId

    val versionLabel: String get() = versionName

    companion object {
        /**
         * `com.glasssign.app.debug` → `com.glasssign.app`.
         *
         * Derived rather than hard-coded because `applicationIdSuffix` is a build-file fact: if the
         * suffix ever changes, the card's sentence must follow it, not repeat a stale string.
         */
        fun releaseIdFor(applicationId: String): String =
            applicationId.removeSuffix(".debug").takeIf { it.isNotEmpty() } ?: applicationId
    }
}

/**
 * Every Intent the update card can fire. There are exactly two, both plain browser navigations:
 *
 *  - the official APK's release download page (GitHub), and
 *  - the 夸克 permanent directory link.
 *
 * WHAT IS DELIBERATELY ABSENT: no `DownloadManager` request, no automatic file write, no
 * `PackageInstaller` session, no `ACTION_INSTALL_PACKAGE`. The card never saves an APK and never
 * starts an install; the browser saves the file and the USER opens it, after which the *system*
 * installer owns the confirmation dialogs. That is why this feature needs no new permission at all —
 * `INTERNET` (metadata read) and the existing browser launch are already declared.
 */
class UpdateIntents(private val context: Context) {

    /** The release download URL, opened in whatever browser the user chose. */
    fun openApk(url: String): Intent {
        if (!UpdateUrls.isReleaseApkUrl(url)) {
            throw IllegalArgumentException("仅允许打开 https 的 GitHub 下载地址：$url")
        }
        return Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** The 夸克 permanent directory link. */
    fun openQuark(url: String): Intent {
        if (!UpdateUrls.isQuarkLink(url)) {
            throw IllegalArgumentException("仅允许打开 https 的夸克链接：$url")
        }
        return Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** True when some activity can answer the Intent, checked WITHOUT launching it (API 30+ aware). */
    fun canOpen(intent: Intent): Boolean = runCatching {
        intent.resolveActivity(context.packageManager) != null
    }.getOrDefault(false)
}
