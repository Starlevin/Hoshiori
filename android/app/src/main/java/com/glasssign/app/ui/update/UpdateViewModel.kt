package com.glasssign.app.ui.update

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/** Where the current build came from, injected so the JVM state tests need no Android. */
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

    companion object {
        /** Release `versionName`s never carry the `-debug` suffix; strip it so the compare is honest. */
        fun stripDebug(value: String): String = value.removeSuffix("-debug")
    }
}

/**
 * State for [HoshioriUpdateCard]. Pure data: the composable renders it and nothing else.
 *
 * [busy] is the single-flight flag. It is set BEFORE any coroutine starts and cleared in `finally`,
 * so a double tap on 「检查更新」 cannot produce two requests, and the button is disabled while it is
 * true instead of pretending to be ready.
 */
data class UpdateUiState(
    val build: UpdateBuildInfo,
    val channel: UpdateChannel = UpdateChannel.GITHUB,
    val quarkUrl: String? = null,
    val quarkNote: String? = null,
    val busy: Boolean = false,
    /** `null` until the user asks for a check. Opening the settings page performs NO request. */
    val result: UpdateCheck? = null,
    /** One-shot sentence for a toast; the card clears it after showing it. */
    val toast: String? = null,
) {
    val install: AppInstallInfo get() = build.toInstallInfo()

    val candidate: UpdateCandidate? get() = (result as? UpdateCheck.Available)?.candidate

    /** The 夸克 channel is only offered when the owner actually configured a link. */
    val quarkAvailable: Boolean get() = quarkUrl != null && UpdateUrls.isQuarkLink(quarkUrl)

    /**
     * Which URL the primary button opens on the ACTIVE channel; `null` when nothing to open.
     *
     * THE 夸克 FALLBACK IS INDEPENDENT OF GITHUB ON PURPOSE: when the metadata request fails (no
     * network, blocked host, rate limit), the 夸克 directory link is still a valid manual route to the
     * same release, so it must remain openable. It is a fixed asset value — not something the failed
     * request could invalidate.
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

/**
 * Drives [HoshioriUpdateCard]. The only place that touches the network, and the only place that
 * knows about threads.
 *
 * THREADING CONTRACT:
 *  - every blocking call (the GitHub request) runs on [Dispatchers.IO];
 *  - every state write happens on the caller's (main) dispatcher, so Compose never sees a half-updated
 *    pair of fields;
 *  - a check is single-flight: [check] returns immediately when [UpdateUiState.busy], and the job is
 *    kept so [cancel] can drop it. Cancelling is NOT reported as a network error.
 *
 * HONEST DEFAULTS: constructing this ViewModel performs no request. Nothing is checked, downloaded or
 * installed until the user presses the button, and even then only a browser opens.
 */
class UpdateViewModel(
    private val build: UpdateBuildInfo,
    private val configLoader: () -> UpdateChannelConfig,
    private val service: () -> UpdateReleaseService,
) : ViewModel() {

    private val _state = MutableStateFlow(UpdateUiState(build = build))
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    private var job: Job? = null

    init {
        // Read the bundled channel config once. This is a few hundred bytes from the APK, not IO on
        // the settings page's frame, and it decides whether a 夸克 button may be rendered at all.
        val config = runCatching { configLoader() }.getOrDefault(UpdateChannelConfig.EMPTY)
        _state.update {
            it.copy(
                quarkUrl = config.quarkUrl?.takeIf { url -> UpdateUrls.isQuarkLink(url) },
                quarkNote = config.quarkNote,
            )
        }
    }

    /**
     * Checks the official channel once. A second call while one is in flight is ignored — that is the
     * anti-double-click guarantee, enforced here rather than by a disabled button alone (a fast
     * double tap can queue two clicks before recomposition).
     */
    fun check() {
        if (_state.value.busy) return
        // Enter the busy state BEFORE launching, so the guard above is already effective even if a
        // second click arrives before recomposition disables the button.
        _state.update { it.copy(busy = true, toast = null) }
        job = viewModelScope.launch {
            val outcome = try {
                withContext(Dispatchers.IO) { service().check(build.versionName) }
            } catch (e: Exception) {
                // Cancellation is rethrown so it never surfaces as a network sentence. `check` itself
                // already converts transport problems into UpdateCheck.Failed, so reaching here with
                // an IOException means the service call itself threw before mapping.
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (e is IOException) UpdateReleaseService.failureOf(e)
                else UpdateCheck.Failed(UpdateFailure.OFFLINE, "无法完成检查：${e.message ?: e.javaClass.simpleName}")
            }
            // A cancelled coroutine never reaches this line, so `cancel()` owns the busy reset there.
            _state.update { it.copy(busy = false, result = outcome) }
        }
    }

    /** Drops an in-flight check. The user cancelled, so no message pretends the network failed. */
    fun cancel() {
        job?.cancel()
        job = null
        // Called from the UI (main) thread, which is also where every other write happens.
        _state.update { it.copy(busy = false) }
    }

    /** Switches the visible channel. Never starts a request: both channels share one release result. */
    fun selectChannel(channel: UpdateChannel) {
        _state.update { it.copy(channel = channel, toast = null) }
    }

    /** The user pressed the primary action. Returns the URL to open, or `null` with a toast set. */
    fun requestUrl(): String? {
        val current = _state.value
        when (current.channel) {
            // The 夸克 directory is a fixed, configured link: it opens regardless of whether the
            // metadata request succeeded, which is exactly why it survives a GitHub outage.
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
                        else -> "请先检查更新，确认有新版本后再下载。"
                    }
                    _state.update { it.copy(toast = why) }
                }
                return url
            }
        }
    }

    /** Consumes the one-shot toast so it is not shown again on recomposition. */
    fun clearToast() {
        if (_state.value.toast != null) _state.update { it.copy(toast = null) }
    }

    /** A failure has to be REPORTED (browser missing), not swallowed. */
    fun report(problem: String) {
        _state.update { it.copy(toast = problem) }
    }

    override fun onCleared() {
        job?.cancel()
        super.onCleared()
    }

    companion object {
        /** Production wiring: `BuildConfig` + the bundled asset + okhttp. */
        fun production(context: Context): UpdateViewModel = UpdateViewModel(
            build = UpdateBuildInfo(
                versionName = UpdateBuildInfo.stripDebug(com.glasssign.app.BuildConfig.VERSION_NAME),
                versionCode = com.glasssign.app.BuildConfig.VERSION_CODE.toLong(),
                applicationId = com.glasssign.app.BuildConfig.APPLICATION_ID,
                isDebug = com.glasssign.app.BuildConfig.DEBUG,
            ),
            configLoader = {
                val raw = runCatching {
                    context.assets.open(UpdateChannelConfig.ASSET_NAME)
                        .bufferedReader(Charsets.UTF_8)
                        .use { it.readText() }
                }.getOrNull()
                UpdateChannelConfig.parse(raw)
            },
            service = { UpdateReleaseService() },
        )
    }
}
