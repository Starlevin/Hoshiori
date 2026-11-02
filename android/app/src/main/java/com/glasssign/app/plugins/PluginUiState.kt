package com.glasssign.app.plugins

/**
 * What the plugin page is allowed to say, derived from measured facts only.
 *
 * WHY A SEALED-ENOUGH ENUM INSTEAD OF A HANDFUL OF BOOLEANS: the page has several states that look
 * similar on screen (未下载 / 下载中 / 下载失败 / 已验证待安装 / 已安装未启用 / 已启用 / 已安装但
 * 签名不符 / 已安装未检测到). With independent booleans it is easy to render "已启用" while the
 * package is gone, or to keep a green tick after a failed re-verification. [PluginUiState] is the
 * single place those combinations collapse, and it never upgrades "unknown" to "good": an installed
 * package whose signature could not be read maps to [PluginStateName.INSTALLED_UNVERIFIED], never
 * to "可打开".
 */
enum class PluginStateName {
    /** Nothing on disk, nothing installed. The default after a fresh install of 星栞. */
    NOT_DOWNLOADED,

    /** Bytes are arriving. The only state with a progress bar. */
    DOWNLOADING,

    /** Transfer or verification failed; the temporary file has been removed. */
    DOWNLOAD_FAILED,

    /** A file passed every check (size, SHA-256, package, version, certificate) and waits for the system installer. */
    VERIFIED_PENDING_INSTALL,

    /** Not installed, but a verified file exists, so "安装" is the next step. */
    INSTALLED_DISABLED,

    /** Installed, signature verified, switch ON: 星栞's own button is available. */
    ENABLED,

    /** Installed, signature verified, switch OFF: hidden entry, nothing else changes. */
    INSTALLED_NOT_ENABLED,

    /** Installed, but the runtime signature/version check failed (or could not be read). */
    INSTALLED_UNVERIFIED,
}

data class PluginUiState(
    val installed: Boolean,
    /** Result of re-verifying the INSTALLED package right now; null when not installed. */
    val installedCheck: VerifyReport?,
    /** True when a verified APK is waiting on disk for the system installer. */
    val verifiedApk: Boolean,
    val download: PluginState.Download,
    /** The user's stored switch position (may be true while the app is not installed). */
    val enabledStored: Boolean,
    /** Last error surfaced by an action (open/uninstall/install permission); UI-only. */
    val actionError: String? = null,
) {
    val signatureOk: Boolean get() = installedCheck is VerifyReport.Passed

    /** The switch only takes effect when the app is really there and really signed. */
    val enabledActive: Boolean get() = enabledStored && installed && signatureOk

    val state: PluginStateName
        get() = when {
            download is PluginState.Download.Running -> PluginStateName.DOWNLOADING
            download is PluginState.Download.Failed -> PluginStateName.DOWNLOAD_FAILED
            verifiedApk && !installed -> PluginStateName.VERIFIED_PENDING_INSTALL
            installed && !signatureOk -> PluginStateName.INSTALLED_UNVERIFIED
            installed && enabledActive -> PluginStateName.ENABLED
            installed -> PluginStateName.INSTALLED_NOT_ENABLED
            else -> PluginStateName.NOT_DOWNLOADED
        }

    /** Chinese headline shown in the status card. */
    val title: String
        get() = when (state) {
            PluginStateName.NOT_DOWNLOADED -> "未下载"
            PluginStateName.DOWNLOADING -> "正在下载"
            PluginStateName.DOWNLOAD_FAILED -> "下载失败"
            PluginStateName.VERIFIED_PENDING_INSTALL -> "已验证待安装"
            PluginStateName.INSTALLED_DISABLED -> "未安装"
            PluginStateName.ENABLED -> "已启用"
            PluginStateName.INSTALLED_NOT_ENABLED -> "已安装 · 未启用"
            PluginStateName.INSTALLED_UNVERIFIED -> "已安装但签名或版本不符"
        }

    /** Second line of the status card; explains the derived state, not the raw flags. */
    val subtitle: String
        get() = when (state) {
            PluginStateName.NOT_DOWNLOADED -> "尚未下载任何文件。星栞不会自动下载、不会自动安装，也不需要云端。"
            PluginStateName.DOWNLOADING -> (download as PluginState.Download.Running).label
            PluginStateName.DOWNLOAD_FAILED -> (download as PluginState.Download.Failed).reason
            PluginStateName.VERIFIED_PENDING_INSTALL ->
                "文件大小、SHA-256、包名、版本与签名证书全部匹配固定版本，可交给系统安装页。"
            PluginStateName.INSTALLED_DISABLED -> "尚未检测到领签。可通过系统安装页安装已验证的固定版本。"
            PluginStateName.ENABLED -> "星栞内“打开领签”入口可用。领签自身后台仍由领签自己管理。"
            PluginStateName.INSTALLED_NOT_ENABLED -> "已安装且验签通过。启用只影响星栞内的入口按钮。"
            PluginStateName.INSTALLED_UNVERIFIED ->
                "已安装的应用未通过验签，星栞拒绝打开。请通过系统界面卸载后重新安装固定版本。"
        }

    // --- action gates: the UI reads these, never the raw flags -------------------------------

    /** Download is offered when nothing usable is installed; a broken install may still be re-downloaded. */
    val canDownload: Boolean get() = state != PluginStateName.DOWNLOADING && (!installed || !signatureOk)

    val canCancel: Boolean get() = state == PluginStateName.DOWNLOADING

    val canRetry: Boolean get() = state == PluginStateName.DOWNLOAD_FAILED

    /** Only a verified file, and only while the app is not installed, may go to the system installer. */
    val canInstall: Boolean get() = verifiedApk && !installed

    /** Opening requires a POSITIVE runtime verification — absence of a verdict is not permission. */
    val canOpen: Boolean get() = installed && signatureOk

    /** Enabling requires the same positive verification; disabling is always allowed. */
    val canToggleEnabled: Boolean get() = if (enabledStored) true else installed && signatureOk

    /** Uninstall always goes through the system UI; 星栞 never uninstalls anything itself. */
    val canUninstallViaSystem: Boolean get() = installed

    val canCleanDownload: Boolean get() = verifiedApk || state == PluginStateName.DOWNLOAD_FAILED

    companion object {
        /** The honest starting point: no file, no install, switch off, no pending download. */
        val INITIAL = PluginUiState(
            installed = false,
            installedCheck = null,
            verifiedApk = false,
            download = PluginState.Download.Idle,
            enabledStored = false,
        )

        /**
         * Builds the state from four independent sources of truth: the filesystem (verified APK),
         * PackageManager (installed + runtime signature), the download coordinator and the
         * preference store. None of them may be inferred from another.
         */
        fun derive(
            installed: Boolean,
            installedCheck: VerifyReport?,
            verifiedApk: Boolean,
            download: PluginState.Download,
            enabledStored: Boolean,
        ): PluginUiState = PluginUiState(
            installed = installed,
            installedCheck = installedCheck,
            verifiedApk = verifiedApk,
            download = download,
            enabledStored = enabledStored,
        )
    }
}
