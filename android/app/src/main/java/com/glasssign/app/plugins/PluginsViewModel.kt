package com.glasssign.app.plugins

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Holds the page's state across configuration changes and owns the download coroutine.
 *
 * THREADING RULES (the reason this class exists at all):
 *  - Every blocking call (socket, hash, PackageManager) runs on [Dispatchers.IO].
 *  - State is published on [Dispatchers.Main], so Compose never reads a half-updated pair of
 *    booleans and no `StateFlow` value is mutated from two threads.
 *  - `onCleared` cancels the download, which makes the coordinator delete its `.part` file. A
 *    finished, verified APK is deliberately NOT deleted.
 *
 * Every state transition is decided by a measurement made *at that moment*:
 *  - after a download: re-read the file from disk and re-verify;
 *  - on resume: ask PackageManager again (the user may have installed or uninstalled from the
 *    system screen while this activity was in the background);
 *  - before opening: verify AGAIN, because the installed copy is not the file we downloaded.
 */
class PluginsViewModel(application: android.app.Application) : androidx.lifecycle.AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val metadata = AndroidApkMetadataReader(appContext.packageManager)
    private val files = PluginFiles(appContext, metadata)
    private val inspector = PackageInspector(appContext)
    private val prefs = PluginPrefs(SharedPrefsKeyValueStore(appContext))
    private val intents = PluginIntents(appContext)

    private val coordinator = DownloadCoordinator(
        source = PluginHttpSource(),
        fs = RealPluginFileSystem(),
        metadata = metadata,
    )

    private val _state = MutableStateFlow(PluginUiState.INITIAL)
    val state: StateFlow<PluginUiState> = _state.asStateFlow()

    private var downloadJob: Job? = null
    private var cancelToken: DownloadCancelToken? = null

    /** Set once the user cancels, so a cooperative cancellation is not reported as a network error. */
    @Volatile private var cancelRequested = false

    init {
        // Default posture: NOTHING happens on open. No file is downloaded, nothing is installed and
        // no request is made — the page only reads local facts.
        files.sweepPart()
        refresh()
    }

    /** Re-reads the four independent sources and re-derives the state. Never downloads. */
    fun refresh() {
        viewModelScope.launch {
            val (installedCheck, verifiedApk) = withContext(Dispatchers.IO) {
                val installedReport = inspector.verifyInstalled()
                // A stale switch must not keep claiming "已启用" for an app that is gone or re-signed.
                prefs.reconcile(installedReport)
                val disk = if (files.finalExists()) files.reverifyOnDisk() else null
                val ok = disk is VerifyReport.Passed
                if (disk != null && !ok) {
                    // The file on disk is no longer the pinned artifact: drop it rather than leave a
                    // file that some other path might hand to the installer.
                    runCatching { files.clean() }
                }
                installedReport to ok
            }
            val previous = _state.value
            _state.value = PluginUiState.derive(
                installed = installedCheck != null,
                installedCheck = installedCheck,
                verifiedApk = verifiedApk,
                // A finished/failed download keeps its message until the next explicit action.
                download = if (downloadJob?.isActive == true) previous.download else PluginState.Download.Idle,
                enabledStored = prefs.enabled(),
            )
        }
    }

    /** Starts (or restarts) the download. Refuses when a download is already running. */
    fun download() {
        if (downloadJob?.isActive == true) return
        cancelRequested = false
        val token = DownloadCancelToken()
        cancelToken = token
        _state.value = _state.value.copy(
            download = PluginState.Download.Running(0, PluginSpec.SIZE_BYTES),
            actionError = null,
        )
        downloadJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                coordinator.download(files.dir(), token) { seen ->
                    // Progress is published straight into the holder; the Compose layer collects the
                    // flow on the main thread, so no UI code runs on this thread.
                    _state.value = _state.value.copy(
                        download = PluginState.Download.Running(seen, PluginSpec.SIZE_BYTES)
                    )
                }
            }
            // Re-measure everything: the file we just wrote is the only evidence that matters.
            val verifiedApk = withContext(Dispatchers.IO) { files.finalExists() && files.reverifyOnDisk() is VerifyReport.Passed }
            val downloadState: PluginState.Download = when {
                result.ok && verifiedApk -> PluginState.Download.Idle
                result.cancelled || cancelRequested -> PluginState.Download.Idle
                result.ok -> PluginState.Download.Failed("核验失败：已保存的文件未通过复检，已删除")
                else -> PluginState.Download.Failed(result.failedReason ?: PluginState.FAIL_IO)
            }
            _state.value = PluginUiState.derive(
                installed = _state.value.installed,
                installedCheck = _state.value.installedCheck,
                verifiedApk = verifiedApk,
                download = downloadState,
                enabledStored = prefs.enabled(),
            )
            cancelToken = null
        }
    }

    /** Cancels a running transfer. The coordinator deletes the `.part` file on its way out. */
    fun cancelDownload() {
        if (downloadJob?.isActive != true) return
        cancelRequested = true
        cancelToken?.cancel()
        // Wait for the coordinator to close its file before allowing another attempt on the same path.
    }

    /** Hands the verified file to the SYSTEM installer. Never a silent install. */
    fun install(onMessage: (String) -> Unit) {
        val current = _state.value
        if (!current.canInstall) {
            onMessage("没有可安装的已核验文件，请先下载")
            return
        }
        if (!intents.canInstallPackages()) {
            val intent = intents.unknownSourcesSettings()
            if (intent == null || !launch(intent)) {
                onMessage("无法打开“安装未知应用”设置页，请手动在系统设置中允许本应用安装应用")
                return
            }
            onMessage("已打开系统设置：请允许本应用安装应用，然后返回重试安装。星栞不会静默安装。")
            return
        }
        val apk = files.finalFile()
        // Guard the race where cache eviction removed the file between the state read and this call.
        if (!apk.isFile) {
            _state.value = current.copy(actionError = "安装包已被系统清理，请重新下载")
            onMessage("安装包已被系统清理，请重新下载")
            return
        }
        if (!launch(intents.install(apk))) {
            onMessage("系统没有可用的安装页面（可能与系统版本有关）")
        }
    }

    /** Hides the 星栞 entry button again (e.g. the user turned it off from the settings page). */
    fun disableEntry() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { prefs.disable() }
            _state.value = _state.value.copy(enabledStored = false)
        }
    }

    /** Opens the independent app, after verifying the INSTALLED copy again. */
    fun open(onMessage: (String) -> Unit) {
        if(!prefs.enabled()) {onMessage("请先启用领签入口");return}
        viewModelScope.launch {
            val report = withContext(Dispatchers.IO) { inspector.verifyInstalled() }
            // The launch intent can only be resolved for a package this app may SEE. On API 30+ that
            // is what the <queries><package com.qinghan.aicheckin/></queries> entry in the manifest is
            // for: remove it and `getLaunchIntentForPackage` returns null even with the app installed.
            // Nothing else about 领签 is queried, and no broad QUERY_ALL_PACKAGES is requested.
            if (report !is VerifyReport.Passed) {
                val why = if (report == null) "未安装" else (report as VerifyReport.Failed).detail
                _state.value = _state.value.copy(
                    installed = report != null,
                    installedCheck = report,
                    actionError = "未通过验签，已拒绝打开：$why",
                )
                onMessage("未通过验签，已拒绝打开领签")
                return@launch
            }
            val intent = intents.launch()
            if (intent == null) {
                _state.value = _state.value.copy(actionError = "系统未找到领签的启动页面，可能已被卸载或停用")
                onMessage("系统未找到领签的启动页面")
                return@launch
            }
            if (!launch(intent)) {
                _state.value = _state.value.copy(actionError = "启动领签失败，请从系统桌面打开")
                onMessage("启动领签失败")
            }
        }
    }

    /**
     * Opens the SYSTEM uninstall confirmation for 领签; 星栞 never removes the app or its data itself.
     * The user can cancel there, and the next resume re-measures whatever really happened.
     *
     * `canResolveUninstall()` can be a false negative (package-visibility filtering, an unusual ROM),
     * so a `false` result must not block the attempt: it only decides whether the dialog is tried
     * first. If the dialog cannot be started — either not resolvable or `startActivity` threw — the
     * app-details screen is opened instead, and that screen carries the same system uninstall button.
     */
    fun uninstallViaSystem(onMessage: (String) -> Unit) {
        if (intents.canResolveUninstall() && launch(intents.uninstallRequest())) return
        if (launch(intents.appDetails())) {
            onMessage("已打开系统应用详情页，可在其中卸载领签")
        } else {
            onMessage("无法打开系统应用详情或卸载页面")
        }
    }

    /** Applies the switch through the verification gate in [PluginPrefs]. */
    fun setEnabled(requested: Boolean, onMessage: (String) -> Unit) {
        viewModelScope.launch {
            val report = withContext(Dispatchers.IO) { inspector.verifyInstalled() }
            val stored = withContext(Dispatchers.IO) {
                if (requested) prefs.enable(report) else { prefs.disable(); false }
            }
            if (requested && !stored) {
                onMessage("未安装或未通过验签，无法启用星栞内入口")
            }
            _state.value = _state.value.copy(
                installed = report != null,
                installedCheck = report,
                enabledStored = stored,
            )
        }
    }

    /** Deletes the downloaded artifact (verified or not) and any leftover part file. */
    fun cleanDownload() {
        viewModelScope.launch {
            val verifiedApk = withContext(Dispatchers.IO) {
                runCatching { files.clean() }
                files.finalExists()
            }
            _state.value = PluginUiState.derive(
                installed = _state.value.installed,
                installedCheck = _state.value.installedCheck,
                verifiedApk = verifiedApk,
                download = PluginState.Download.Idle,
                enabledStored = prefs.enabled(),
            )
        }
    }

    fun openSource(onMessage: (String) -> Unit) {
        if (!launch(intents.source())) onMessage("没有可用的浏览器打开来源页")
    }

    /**
     * Offers to find 领签 in the user's app store (the independent app is published there too).
     * This is a market search Intent, not a download of anything: 星栞 fetches no APK here, and the
     * pinned GitHub artifact remains the only thing this app ever verifies or installs.
     *
     * If no store answers — no store installed, or package visibility filtering hides it — the
     * source page opens instead, so the user always ends up somewhere they can act.
     */
    fun openStore(onMessage: (String) -> Unit) {
        if (launch(intents.storeSearch())) return
        if (!launch(intents.source())) onMessage("没有可用的应用商店或浏览器")
    }

    private fun launch(intent: android.content.Intent): Boolean = runCatching { appContext.startActivity(intent) }
        .onFailure { e ->
            // ActivityNotFoundException / SecurityException: report, never swallow.
            _state.value = _state.value.copy(actionError = "无法打开系统页面：${e.javaClass.simpleName}")
        }
        .isSuccess

    override fun onCleared() {
        cancelRequested = true
        cancelToken?.cancel()
        downloadJob?.cancel()
        downloadJob = null
        // Only the temporary file goes; a verified APK stays usable for the next visit.
        runCatching { files.sweepPart() }
        super.onCleared()
    }

    /** Exposed for diagnostics/tests: the exact file the install path would hand to the system. */
    fun pendingApk(): File = files.finalFile()
}
