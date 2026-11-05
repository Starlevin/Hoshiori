package com.glasssign.app.ui.update

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 当前构建的身份信息，注入进来以便 JVM 测试不需要 Android。
 *
 * [channel] 由版本名决定而不是包名：正式版与预览版现在都装成 `com.glasssign.app` + 同一张签名，
 * 只有 `versionName` 能分辨"这是预览构建"。包名只用于安装校验（APK 里的包名必须等于本次安装的 id）。
 */
data class UpdateBuildInfo(
    val versionName: String,
    val versionCode: Long,
    val applicationId: String,
    val isDebug: Boolean,
) {
    fun toInstallInfo(): AppInstallInfo = AppInstallInfo(
        versionName = versionName,
        versionCode = versionCode,
        applicationId = applicationId,
        isDebug = isDebug,
        releaseApplicationId = AppInstallInfo.releaseIdFor(applicationId),
    )

    /** 这条构建查哪条发布通道。非发布构建（debug / 未知版本名）返回 `null`。 */
    val channel: UpdateReleaseChannel?
        get() {
            if (isDebug) return null
            if (LegacyPreviewInstall.isLegacyPreview(applicationId)) return UpdateReleaseChannel.PREVIEW
            return UpdateReleaseChannel.ofVersionName(versionName)
        }

    /** 旧版独立 preview 安装：与统一包名不同，需要用户手动换成新包。 */
    val legacyPreview: Boolean get() = LegacyPreviewInstall.isLegacyPreview(applicationId)

    companion object {
        /** Release `versionName`s never carry the `-debug` suffix; strip it so the compare is honest. */
        fun stripDebug(value: String): String = value.removeSuffix("-debug")
    }
}

/** 下载状态。只有 [Verified] 会把文件交给安装器。 */
sealed class UpdateDownloadState {
    data object Idle : UpdateDownloadState()

    /** [total] 为 0 时进度未知，只能显示已下载字节数。 */
    data class Running(val bytes: Long, val total: Long) : UpdateDownloadState() {
        val percent: Int? get() = if (total > 0) ((bytes * 100) / total).toInt().coerceIn(0, 100) else null
    }

    /** 下载完成、校验通过，等用户点「安装」。 */
    data class Verified(
        val file: File,
        val sha256: String,
        val sizeBytes: Long,
        val versionName: String,
    ) : UpdateDownloadState()

    /** [retryable] 决定是否显示「重试」；校验失败不显示重试，避免把同一个坏文件再走一遍。 */
    data class Failed(val message: String, val retryable: Boolean = true) : UpdateDownloadState()
}

/** 一次检查的元信息，卡片用来解释"这是自动查的还是我点的"。 */
data class UpdateCheckMeta(
    val automatic: Boolean = false,
    /** 自动检查时判断这次要不要打扰用户的依据：有真正的新版本才提示。 */
    val shouldNotify: Boolean = false,
)

/**
 * State for [HoshioriUpdateCard]. Pure data: the composable renders it and nothing else.
 *
 * [busy] is the single-flight flag for the CHECK. It is set BEFORE any coroutine starts and cleared in
 * `finally`, so a double tap cannot produce two requests.
 */
data class UpdateUiState(
    val build: UpdateBuildInfo,
    val channel: UpdateChannel = UpdateChannel.GITHUB,
    val quarkUrl: String? = null,
    val quarkNote: String? = null,
    val busy: Boolean = false,
    /** `null` 表示本次打开还没查过（或自动检查被限频跳过）。 */
    val result: UpdateCheck? = null,
    val meta: UpdateCheckMeta = UpdateCheckMeta(),
    val download: UpdateDownloadState = UpdateDownloadState.Idle,
    /** 只允许从 GitHub 官方发布地址拿到的那一个文件进入下载。 */
    val downloadTarget: UpdateDownloadTarget? = null,
    /** 一次性的提示句；卡片显示后清空。 */
    val toast: String? = null,
    /** 已经在后台自动检查过（无论第几次进入页面），避免每次进设置页都查。 */
    val autoCheckAttempted: Boolean = false,
) {
    val install: AppInstallInfo get() = build.toInstallInfo()

    val candidate: UpdateCandidate? get() = (result as? UpdateCheck.Available)?.candidate

    /** 这条构建查的发布通道（正式 / 预览）；`null` 表示不是发布构建，不做自动检查。 */
    val releaseChannel: UpdateReleaseChannel? get() = build.channel

    /** 旧版独立预览包：能看更新，但包名不同，只能提示用户手动换包。 */
    val legacyPreviewNeedsManualInstall: Boolean get() = build.legacyPreview

    /** 只有确认有新版本、并且有合法的应用内下载目标时才算"可以下载"。 */
    val inAppDownloadable: Boolean
        get() = downloadTarget is UpdateDownloadTarget.InApp

    val downloading: Boolean get() = download is UpdateDownloadState.Running

    val verified: UpdateDownloadState.Verified? get() = download as? UpdateDownloadState.Verified

    /** The 夸克 channel is only offered when the owner actually configured a link. */
    val quarkAvailable: Boolean get() = quarkUrl != null && UpdateUrls.isQuarkLink(quarkUrl)

    /** 外部渠道优先使用的地址：GitHub 发布页，其次夸克永久目录。 */
    val externalFallback: String?
        get() {
            val page = candidate?.htmlUrl
            if (page != null && UpdateUrls.isReleasePageUrl(page)) return page
            if (quarkAvailable) return quarkUrl
            return null
        }

    /**
     * Which URL the EXPLICITLY EXTERNAL button opens on the ACTIVE channel; `null` when nothing to open.
     *
     * THE 夸克 FALLBACK IS INDEPENDENT OF GITHUB ON PURPOSE: when the metadata request fails (no
     * network, blocked host, rate limit), the 夸克 directory link is still a valid manual route to the
     * same release, so it must remain openable.
     */
    val actionUrl: String?
        get() = when (channel) {
            UpdateChannel.GITHUB -> candidate?.apk?.browserDownloadUrl
                ?.takeIf { UpdateUrls.isReleaseApkUrl(it) }
            UpdateChannel.QUARK -> quarkUrl?.takeIf { UpdateUrls.isQuarkLink(it) }
        }

    /** True when the GitHub answer is missing or failed, so 夸克 is the only usable route. */
    val githubUnusable: Boolean
        get() = result == null || result is UpdateCheck.Failed
}

/** 一次检查的结果，附带限频记录需要的信息。 */
private data class CheckRun(
    val outcome: UpdateCheckOutcome,
    val etag: String?,
    val body: String? = null,
    val endpoint: String? = null,
)

/**
 * Drives [HoshioriUpdateCard]. The only place that touches the network, and the only place that
 * knows about threads.
 *
 * THREADING CONTRACT:
 *  - every blocking call (GitHub request, download, hashing, PackageManager) runs on [Dispatchers.IO];
 *  - every state write uses [MutableStateFlow.update], which is atomic, so Compose never sees a
 *    half-updated pair of fields;
 *  - a check is single-flight, and a download is single-flight with a cancel token the UI holds.
 *
 * WHAT THIS CLASS WILL NOT DO: install anything silently. [startInstall] only hands a file that passed
 * [UpdateInstallVerifier] to the system installer; the user still confirms in the system dialog.
 */
class UpdateViewModel(
    private val build: UpdateBuildInfo,
    private val configLoader: () -> UpdateChannelConfig,
    private val service: () -> UpdateReleaseService,
    private val checkStore: UpdateCheckStore = MapUpdateCheckStore(),
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val cacheDir: (() -> File)? = null,
    private val downloaderFactory: (UpdateCandidate) -> UpdateDownloader? = { null },
    private val apkReader: UpdateApkReader? = null,
    private val installerBridge: UpdateInstallerBridge? = null,
) : ViewModel() {

    /** 协程作用域。生产用 [viewModelScope]；测试可以换成真实线程池上的作用域。 */
    internal var scope: kotlinx.coroutines.CoroutineScope = viewModelScope

    private val _state = MutableStateFlow(UpdateUiState(build = build))
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    private var checkJob: Job? = null
    private var downloadJob: Job? = null
    private var downloadToken: UpdateDownloadToken? = null

    /** 下载代号。取消或重新开始时自增，旧协程因此无法再提交 Verified。 */
    private var downloadGeneration: Int = 0

    init {
        // Read the bundled channel config once. This is a few hundred bytes from the APK and it decides
        // whether a 夸克 button may be rendered at all.
        val config = runCatching { configLoader() }.getOrDefault(UpdateChannelConfig.EMPTY)
        _state.update {
            it.copy(
                quarkUrl = config.quarkUrl?.takeIf { url -> UpdateUrls.isQuarkLink(url) },
                quarkNote = config.quarkNote,
            )
        }
    }

    // --- 检查 -----------------------------------------------------------------------------------

    /**
     * App 正常启动时的后台检查：限频（默认 24 小时），同一进程只尝试一次，不打扰无更新的用户。
     *
     * 这是一个可以放心从界面进入时调用的函数：它自己判断要不要查，`state.busy` 与正在进行的下载都
     * 会让它直接返回。返回值表示"本次是否真的发起了检查"。
     */
    fun autoCheck(): Boolean {
        val current = _state.value
        if (current.autoCheckAttempted || current.busy || downloadJob?.isActive == true) return false
        _state.update { it.copy(autoCheckAttempted = true) }
        val channel = current.releaseChannel ?: return false
        val memory = runCatching { checkStore.read() }.getOrDefault(UpdateCheckMemory())
        val decision = UpdateAutoCheck.decide(channel, memory, clock())
        if (decision !is UpdateAutoCheckDecision.Check) return false
        runCheck(automatic = true, etag = decision.etag)
        return true
    }

    /** 手动检查：永远可用，不受限频影响。一次只允许一个，防连点。 */
    fun check() {
        if (_state.value.busy) return
        val etag = runCatching { checkStore.read().etag }.getOrNull()
        runCheck(automatic = false, etag = etag)
    }

    private fun runCheck(automatic: Boolean, etag: String?) {
        _state.update { it.copy(busy = true, toast = null, meta = UpdateCheckMeta(automatic = automatic)) }
        checkJob = scope.launch {
            val run = try {
                withContext(Dispatchers.IO) {
                    val service = service()
                    CheckRun(
                        service.checkWithMeta(build.versionName, etag),
                        // 302/304 之后真正可复用的 etag 与正文由 service 内部决定，这里只记录应答里的 etag。
                        etag,
                        service.lastServedBody,
                        service.lastServedEndpoint,
                    )
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                val failed = if (e is IOException) UpdateReleaseService.failureOf(e)
                else UpdateCheck.Failed(UpdateFailure.OFFLINE, "无法完成检查：${e.message ?: e.javaClass.simpleName}")
                CheckRun(UpdateCheckOutcome(failed, reached = false, failureSentence = failed.message), etag)
            }
            // 记录限频：成功保存 etag 与正文（下次 304 才能复现结论），失败只前移时间并置失败标志。
            val store = runCatching {
                val previous = checkStore.read()
                val updated = UpdateAutoCheck.afterAttempt(
                    previous = previous,
                    nowMs = clock(),
                    succeeded = run.outcome.reached,
                    etag = run.outcome.etag ?: previous.etag,
                    body = run.body ?: previous.cachedBody,
                    endpoint = run.endpoint ?: previous.cachedEndpoint,
                    comparedWith = if (run.outcome.reached) build.versionName else previous.cachedVersion,
                )
                checkStore.write(updated)
                updated
            }.getOrNull()
            val notify = UpdateAutoCheck.shouldNotify(run.outcome.check, store?.noticedVersion)
            _state.update {
                it.copy(
                    busy = false,
                    result = run.outcome.check,
                    meta = UpdateCheckMeta(automatic = automatic, shouldNotify = notify),
                    download = if (automatic) it.download else UpdateDownloadState.Idle,
                    downloadTarget = null,
                )
            }
        }
    }

    /** Drops an in-flight check. The user cancelled, so no message pretends the network failed. */
    fun cancel() {
        checkJob?.cancel()
        checkJob = null
        _state.update { it.copy(busy = false) }
    }

    /** 用户已经看到这个版本：以后同一个版本不再主动提示。 */
    fun markNoticed() {
        val version = _state.value.candidate?.version?.toString() ?: return
        runCatching { checkStore.write(UpdateAutoCheck.markNoticed(checkStore.read(), version)) }
    }

    // --- 下载 -----------------------------------------------------------------------------------

    /**
     * 开始应用内下载。只对 GitHub 官方发布地址、且通道与本次安装一致的候选生效；
     * 其它情况把原因写清楚并把外部渠道交给界面，绝不假装能直下。
     */
    fun startDownload() {
        if (downloadJob?.isActive == true) return
        val current = _state.value
        val target = UpdateChannelPolicy.downloadTarget(
            check = current.result,
            quarkUrl = current.quarkUrl,
        )
        _state.update { it.copy(downloadTarget = target, toast = null) }
        if (target !is UpdateDownloadTarget.InApp) {
            val reason = (target as? UpdateDownloadTarget.ExternalOnly)?.reason ?: "当前没有可下载的新版本。"
            _state.update { it.copy(download = UpdateDownloadState.Failed(reason, retryable = false)) }
            return
        }
        val candidate = current.candidate
        val dir = cacheDir?.invoke()
        val downloader = candidate?.let(downloaderFactory)
        if (candidate == null || dir == null || downloader == null) {
            _state.update {
                it.copy(download = UpdateDownloadState.Failed("应用内下载不可用，请改用外部下载渠道。", retryable = false))
            }
            return
        }
        val token = UpdateDownloadToken()
        // 每次下载一个代号：取消、重新下载、离开页面都会让旧代号失效，从而挡住"取消后又被写回 Verified"。
        val generation = ++downloadGeneration
        downloadToken = token
        _state.update { it.copy(download = UpdateDownloadState.Running(0L, candidate.apk.sizeBytes)) }
        downloadJob = scope.launch {
            var committed: UpdateDownloadState = UpdateDownloadState.Idle
            try {
                val fileName = UpdateCache.apkName(candidate.version.toString())
                val result = downloader.download(target.url, dir, fileName, token) { written, total ->
                    if (isCurrent(generation)) {
                        _state.update { s ->
                            if (s.download is UpdateDownloadState.Running) {
                                val shown = if (total > 0) total else candidate.apk.sizeBytes
                                s.copy(download = UpdateDownloadState.Running(written, shown))
                            } else s
                        }
                    }
                }
                committed = try {
                    when (result) {
                        is UpdateDownloadResult.Success -> {
                            val report = verifyDownloaded(result, candidate)
                            if (report is VerifyReportView.Passed) {
                                // 只保留刚通过校验的这一份，缓存里不留别的旧安装包。
                                UpdateDownloader.sweep(dir, keep = result.file)
                                UpdateDownloadState.Verified(
                                    file = result.file,
                                    sha256 = result.sha256,
                                    sizeBytes = result.sizeBytes,
                                    versionName = candidate.version.toString(),
                                )
                            } else {
                                UpdateDownloadState.Failed((report as VerifyReportView.Failed).detail, retryable = false)
                            }
                        }
                        UpdateDownloadResult.Cancelled -> UpdateDownloadState.Idle
                        is UpdateDownloadResult.Failed ->
                            UpdateDownloadState.Failed(result.error.message, result.error.retryable)
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 校验过程自己抛了（例如读取 APK 失败）：同样要落成失败，不能停在"下载中"。
                    UpdateDownloadState.Failed("校验安装包时出错：${e.message ?: e.javaClass.simpleName}")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                committed = if (isCurrent(generation)) UpdateDownloadState.Idle else committed
                throw e
            } finally {
                // 只有仍属于这次下载才提交状态并清 token，避免取消后被后续写入覆盖。
                if (isCurrent(generation)) {
                    _state.update { it.copy(download = committed) }
                    downloadToken = null
                    downloadJob = null
                }
            }
        }
    }

    /** 这次下载是否仍然有效（没有被取消、没有被新的一次下载顶掉、没有离开页面）。 */
    private fun isCurrent(generation: Int): Boolean = generation == downloadGeneration

    /** 取消正在进行的下载。取消不报"下载失败"，半截文件由下载器删除。 */
    fun cancelDownload() {
        // 先让代号失效，再取消协程：正在哈希/校验的收尾逻辑就不会再写回 Verified。
        downloadGeneration++
        downloadToken?.cancel()
        downloadToken = null
        downloadJob?.cancel()
        downloadJob = null
        _state.update { it.copy(download = UpdateDownloadState.Idle) }
    }

    /** 自动检查时收到新版本，用户选择"稍后"：本次不再提示。 */
    fun dismissNotification() {
        val current = _state.value
        _state.update { it.copy(meta = it.meta.copy(shouldNotify = false)) }
        current.candidate?.let { markNoticedVersion(it.version.toString()) }
    }

    private fun markNoticedVersion(version: String) {
        runCatching { checkStore.write(UpdateAutoCheck.markNoticed(checkStore.read(), version)) }
    }

    /** 校验已经落盘的下载文件；返回纯逻辑报告，[VerifyReportView] 只负责翻译成文案。 */
    private suspend fun verifyDownloaded(result: UpdateDownloadResult.Success, candidate: UpdateCandidate): VerifyReportView =
        withContext(Dispatchers.IO) {
            val reader = apkReader ?: return@withContext VerifyReportView.Failed("无法读取安装包信息，已拒绝安装。")
            val installed = reader.readInstalled()
            val facts = reader.readApk(result.file, result.sha256)
            val report = UpdateInstallVerifier.verify(
                install = build.toInstallInfo(),
                installed = installed,
                apk = facts,
                expectedSize = result.sizeBytes,
                expectedSha256 = candidate.apk.sha256,
            )
            if (report is UpdateVerifyReport.Passed) VerifyReportView.Passed
            else VerifyReportView.Failed(report.detail)
        }

    // --- 安装 -----------------------------------------------------------------------------------

    /**
     * 把已校验的文件交给系统安装器。用户必须自己在系统页确认；这里不做静默安装。
     *
     * @return 给用户的一次性提示（例如"已打开系统安装页"），或 `null` 表示已把 Intent 交给系统。
     */
    fun startInstall(): String? {
        val verified = _state.value.verified
            ?: return "还没有通过校验的安装包，请先下载。"
        val bridge = installerBridge ?: return "当前版本无法启动系统安装器。"
        if (!verified.file.isFile) {
            _state.update { it.copy(download = UpdateDownloadState.Failed("安装包已被系统清理，请重新下载。", retryable = true)) }
            return "安装包已被系统清理，请重新下载。"
        }
        if (!bridge.canInstallPackages()) {
            val opened = bridge.openUnknownSourcesSettings()
            return if (opened) {
                "已打开系统的「安装未知应用」页面：允许星栞安装应用后再回来点安装。星栞不会静默安装。"
            } else {
                "无法打开「安装未知应用」设置页，请手动在系统设置中允许本应用安装应用。"
            }
        }
        return bridge.launchInstall(verified.file)
    }

    // --- 其它 -----------------------------------------------------------------------------------

    /** Switches the visible channel. Never starts a request: both channels share one release result. */
    fun selectChannel(channel: UpdateChannel) {
        _state.update { it.copy(channel = channel, toast = null) }
    }

    /** The user pressed the EXPLICITLY EXTERNAL action. Returns the URL to open, or `null` with a toast set. */
    fun requestUrl(): String? {
        val current = _state.value
        when (current.channel) {
            UpdateChannel.QUARK -> {
                val url = current.quarkUrl?.takeIf { UpdateUrls.isQuarkLink(it) }
                if (url == null) {
                    _state.update { it.copy(toast = "夸克通道暂未发布：永久目录链接尚未配置。") }
                }
                return url
            }
            UpdateChannel.GITHUB -> {
                val url = current.actionUrl
                if (url == null) {
                    val why = when {
                        current.busy -> "正在检查更新，请稍候。"
                        current.result is UpdateCheck.Failed -> "本次未能读取 GitHub 发布信息，可改用夸克通道。"
                        else -> "请先检查更新，确认有新版本后再打开下载页。"
                    }
                    _state.update { it.copy(toast = why) }
                }
                return url
            }
        }
    }

    /** 外部渠道直链：自动检查的提示里给用户一个明确的"去网页下载"。 */
    fun externalFallbackUrl(): String? = _state.value.externalFallback

    /**
     * 给宿主用的"有没有值得提示的新版本"：自动检查拿到真正的更新、且这个版本还没提示过。
     * 宿主不需要知道限频、etag 或下载状态。
     */
    fun pendingAnnouncement(): UpdateCandidate? {
        val current = _state.value
        val candidate = current.candidate ?: return null
        return candidate.takeIf { current.meta.shouldNotify || current.meta.automatic }
    }

    /** Consumes the one-shot toast so it is not shown again on recomposition. */
    fun clearToast() {
        if (_state.value.toast != null) _state.update { it.copy(toast = null) }
    }

    /** A failure has to be REPORTED (browser missing), not swallowed. */
    fun report(problem: String) {
        _state.update { it.copy(toast = problem) }
    }

    /**
     * 直接置入一个「已有新版本」的结论，跳过网络检查。
     *
     * 只给测试用：让取消/校验竞态能在不联网的情况下被驱动到"下载已完成"那一刻。
     */
    internal fun seedForTest(candidate: UpdateCandidate) {
        val target = UpdateChannelPolicy.downloadTarget(
            check = UpdateCheck.Available(candidate),
            quarkUrl = _state.value.quarkUrl,
        )
        _state.update {
            it.copy(
                result = UpdateCheck.Available(candidate),
                downloadTarget = target,
                meta = UpdateCheckMeta(automatic = false, shouldNotify = false),
            )
        }
    }

    override fun onCleared() {
        // 离开页面同样让代号失效：进行中的下载不能把结果写进一个已经没人看的状态。
        downloadGeneration++
        checkJob?.cancel()
        downloadToken?.cancel()
        downloadJob?.cancel()
        super.onCleared()
    }

    companion object {
        /**
         * 生产接线：`BuildConfig` + 内置渠道配置 + okhttp + 本安装自己的缓存目录。
         *
         * 通道由 `VERSION_NAME` 决定（[UpdateBuildInfo.channel]），不由包名决定：正式版与预览版现在
         * 都装成同一个包、用同一张签名，`0.7.9-preview.3` 查预览通道，其余查正式通道。
         * `VERSION_NAME`/`VERSION_CODE` 同时是"是否更新"的比较基准，这里不硬编码任何版本号。
         *
         * 正式包名 + 正式签名发布的构建可以原地覆盖升级：签名门槛比对的是当前已安装的证书。
         */
        fun production(
            context: Context,
            installerBridge: UpdateInstallerBridge = SystemUpdateInstallerBridge(context.applicationContext),
        ): UpdateViewModel {
            val app = context.applicationContext
            val reader = PlatformUpdateApkReader(app)
            val build = UpdateBuildInfo(
                versionName = UpdateBuildInfo.stripDebug(com.glasssign.app.BuildConfig.VERSION_NAME),
                versionCode = com.glasssign.app.BuildConfig.VERSION_CODE.toLong(),
                applicationId = com.glasssign.app.BuildConfig.APPLICATION_ID,
                isDebug = com.glasssign.app.BuildConfig.DEBUG,
            )
            val store = SharedPrefsUpdateCheckStore(app)
            val channel = build.channel ?: UpdateReleaseChannel.OFFICIAL
            return UpdateViewModel(
                build = build,
                configLoader = {
                    val raw = runCatching {
                        app.assets.open(UpdateChannelConfig.ASSET_NAME)
                            .bufferedReader(Charsets.UTF_8)
                            .use { it.readText() }
                    }.getOrNull()
                    UpdateChannelConfig.parse(raw)
                },
                service = {
                    UpdateReleaseService(
                        channel = channel,
                        cachedReply = {
                            val memory = store.read()
                            val etag = memory.etag
                            val body = memory.cachedBody
                            if (etag != null && body != null && memory.cachedEndpoint != null) {
                                UpdateCachedReply(
                                    etag = etag,
                                    body = body,
                                    endpoint = memory.cachedEndpoint,
                                    comparedWith = memory.cachedVersion.orEmpty(),
                                )
                            } else {
                                null
                            }
                        },
                    )
                },
                checkStore = store,
                // MUST stay `<cacheDir>/updates/`: that is exactly what `res/xml/update_download_paths.xml`
                // exposes to the `${packageName}.updates.fileprovider` authority.
                cacheDir = { UpdateCache.dir(app.cacheDir) },
                downloaderFactory = { candidate ->
                    UpdateDownloader(
                        expectedSize = candidate.apk.sizeBytes,
                        expectedSha256 = candidate.apk.sha256,
                    )
                },
                apkReader = reader,
                installerBridge = installerBridge,
            )
        }
    }
}

/**
 * 把纯逻辑校验报告翻译成一句话面文案，避免 ViewModel 里出现 if/else 文案分支。
 * 与 [UpdateVerifyReport] 一一对应，只是换成 UI 需要的形状。
 */
sealed class VerifyReportView {
    data object Passed : VerifyReportView()
    data class Failed(val detail: String) : VerifyReportView()
}
