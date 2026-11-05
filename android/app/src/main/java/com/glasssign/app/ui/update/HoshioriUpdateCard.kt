package com.glasssign.app.ui.update

import android.content.ActivityNotFoundException
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.glasssign.app.ui.QCard

private const val TAG = "hoshiori-update"

/** Shown when the owner has not published the 夸克 directory link yet. Never replaced by a guess. */
internal const val QUARK_NOT_PUBLISHED =
    "夸克通道暂未发布：永久目录链接尚未配置，这不是错误，稍后会随新版本提供。"

/** 应用内直下不可用时的固定说法：不伪装成"正在下载"。 */
internal const val EXTERNAL_ONLY_NOTE =
    "备用下载入口：网络受限时，可通过浏览器或夸克获取安装包。"

/**
 * 「检查更新」 for 星栞, for the settings page. Self-contained: owns its ViewModel, its network call, its
 * download and its installer hand-off, so the host only places it:
 *
 * ```
 * HoshioriUpdateCard()
 * ```
 *
 * WHAT IT DOES NOW (and what it still refuses to do):
 *  - Entering the app starts ONE background check per process, throttled to once per 24 h
 *    ([UpdateAutoCheck]). There is no request from opening the settings page, and a user who is
 *    already up to date is never nagged.
 *  - 「下载安装包」 downloads the official GitHub release asset inside the app, with progress, cancel and
 *    retry. The bytes land in this app's private `cache/updates/` and nothing else can read them.
 *  - Before anything is offered to the installer the file is re-measured: size, SHA-256 (when GitHub
 *    published one), not-HTML, package name, higher versionCode, and the signing certificate must match
 *    the certificate this app is INSTALLED with.
 *  - 「安装」 hands that one file to the SYSTEM installer through a `content://` URI. The system shows its
 *    own confirmation page; 星栞 never installs silently. If "install unknown apps" is off, the system
 *    settings page is opened instead and the card says so.
 *  - When in-app download is not possible, the card says so plainly and offers the GitHub release page
 *    and the 夸克 directory as EXPLICITLY external routes. It never pretends to download from 夸克.
 *
 * BOTH CHANNELS SHARE ONE RELEASE ANSWER: the version, notes and `sha256` shown are always GitHub's
 * official metadata; 夸克 only changes which external page a button opens.
 */
@Composable
fun HoshioriUpdateCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val vm: UpdateViewModel = rememberHoshioriUpdateViewModel()
    val state by vm.state.collectAsState()

    // 兜底：宿主没接启动检查时，进设置页也能查一次。宿主接了就是同一个实例，不会重复请求。
    LaunchedEffect(Unit) { vm.autoCheck() }

    // 只有"真的有可安装的新版本"时才提示；无更新、检查失败都不打扰。
    LaunchedEffect(state.meta.automatic, state.meta.shouldNotify, state.candidate?.version?.toString()) {
        if (state.meta.automatic && state.meta.shouldNotify) vm.markNoticed()
    }

    state.toast?.let { message ->
        LaunchedEffect(message) {
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
            vm.clearToast()
        }
    }

    QCard {
        Text("检查更新", style = MaterialTheme.typography.titleLarge)
        Text(
            "当前版本 ${state.install.versionLabel}（构建 ${state.build.versionCode}" +
                "${if (state.build.isDebug) " · debug" else ""}${channelLabel(state.releaseChannel)}）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.testTag("$TAG-version"),
        )
        state.meta.checkedAt?.let { time ->
            val date=java.time.Instant.ofEpochMilli(time).atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"))
            Text("${if(state.meta.fromCache) "上次检查结果" else "检查时间"}：$date",
                style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }

        when (val result = state.result) {
            null -> Text(
                if (state.busy) "正在读取 GitHub 发布信息…" else idleHint(state),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            is UpdateCheck.Available -> AvailableBlock(state = state, candidate = result.candidate)
            is UpdateCheck.UpToDate -> NoteLine(
                icon = Icons.Rounded.Info,
                tint = MaterialTheme.colorScheme.primary,
                text = result.detail,
            )
            is UpdateCheck.Failed -> NoteLine(
                icon = iconFor(result.kind),
                tint = MaterialTheme.colorScheme.error,
                text = result.message,
            )
        }

        CheckRow(state = state, onCheck = vm::check, onCancel = vm::cancel)

        // 下载与安装：只有真的存在可安装的新版本、并且通道一致时才出现。
        if (state.candidate != null) {
            DownloadBlock(state = state, vm = vm, context = context)
        }

        ChannelFallback(state = state, onSelect = vm::selectChannel, onOpen = { url -> openExternally(context, url, vm::report) })

        InstallationBasisNote(state = state)
    }
}

/** 当前通道与"这份安装属于哪条通道"的一句话说明。 */
private fun channelLabel(channel: UpdateReleaseChannel?): String = when (channel) {
    UpdateReleaseChannel.OFFICIAL -> " · 正式通道"
    UpdateReleaseChannel.PREVIEW -> " · 预览通道"
    null -> " · 非发布构建"
}

private fun idleHint(state: UpdateUiState): String = when {
    state.legacyPreviewNeedsManualInstall ->
        "这是旧的独立预览包。新版统一装成同一个应用，需要手动换装一次，之后就能在这里直接升级。"
    state.releaseChannel == null ->
        "当前构建不是发布版本，这里只提供外部下载入口。"
    else -> when (val download = state.download) {
        is UpdateDownloadState.Failed -> download.message
        else -> "进入应用时会自动检查更新；也可以随时手动检查。"
    }
}

/** 检查按钮 + 取消，单一职责，避免与下载按钮抢焦点。 */
@Composable
private fun CheckRow(state: UpdateUiState, onCheck: () -> Unit, onCancel: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = onCheck,
            enabled = !state.busy && !state.downloading,
            modifier = Modifier.testTag("$TAG-check"),
        ) {
            if (state.busy) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("检查中")
            } else {
                Icon(Icons.Rounded.SystemUpdate, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (state.result == null) "检查更新" else "重新检查")
            }
        }
        if (state.busy) {
            TextButton(onClick = onCancel, modifier = Modifier.testTag("$TAG-cancel")) { Text("取消") }
        }
    }
}

/** 「下载 / 进度 / 取消 / 重试 / 安装」这一段。 */
@Composable
private fun DownloadBlock(state: UpdateUiState, vm: UpdateViewModel, context: Context) {
    val download = state.download
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when (download) {
            is UpdateDownloadState.Running -> {
                val percent = download.percent
                Text(
                    if (percent != null) "正在下载安装包 $percent%（${readableSize(download.bytes)} / ${readableSize(download.total)}）"
                    else "正在下载安装包（已下载 ${readableSize(download.bytes)}）",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("$TAG-download-progress"),
                )
                if (percent != null) {
                    LinearProgressIndicator(
                        progress = { download.bytes.toFloat() / download.total.coerceAtLeast(1L) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                TextButton(onClick = vm::cancelDownload, modifier = Modifier.testTag("$TAG-download-cancel")) {
                    Text("取消下载")
                }
            }
            is UpdateDownloadState.Verified -> {
                NoteLine(
                    icon = Icons.Rounded.Info,
                    tint = MaterialTheme.colorScheme.primary,
                    text = "安装包已下载并通过校验：${download.versionName} · ${readableSize(download.sizeBytes)} · " +
                        "SHA-256 ${ReleaseDigest.short(download.sha256)}",
                )
                PrimaryButton(
                    label = "安装 ${download.versionName}",
                    icon = Icons.Rounded.SystemUpdate,
                    tag = "$TAG-install",
                    onClick = {
                        val message = vm.startInstall()
                        if (message != null) vm.report(message)
                    },
                )
                Text(
                    "点击后由系统安装器确认安装；星栞不会静默安装，也不会替你勾选任何选项。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            is UpdateDownloadState.Failed -> {
                NoteLine(
                    icon = Icons.Rounded.ErrorOutline,
                    tint = MaterialTheme.colorScheme.error,
                    text = download.message,
                )
                if (download.retryable) {
                    TextButton(onClick = vm::startDownload, modifier = Modifier.testTag("$TAG-download-retry")) {
                        Text("重试下载")
                    }
                }
                // 直链不可达时给出明确的外部渠道，不伪装成应用内下载。
                state.externalFallback?.let { url ->
                    TextButton(
                        onClick = { openExternally(context, url, vm::report) },
                        modifier = Modifier.testTag("$TAG-external-fallback"),
                    ) { Text("改用外部渠道打开下载页") }
                }
            }
            UpdateDownloadState.Idle -> {
                val target = state.downloadTarget
                if (target is UpdateDownloadTarget.ExternalOnly) {
                    NoteLine(
                        icon = Icons.Rounded.Info,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        text = target.reason,
                    )
                    target.quarkUrl?.let { url ->
                        TextButton(
                            onClick = { openExternally(context, url, vm::report) },
                            modifier = Modifier.testTag("$TAG-external-quark"),
                        ) { Text("在浏览器打开夸克目录") }
                    }
                } else {
                    PrimaryButton(
                        label = "下载安装包（应用内）",
                        icon = Icons.Rounded.Download,
                        tag = "$TAG-download",
                        onClick = vm::startDownload,
                    )
                    Text(
                        "安装包会下载到星栞的应用私有缓存，校验通过后才提供安装按钮。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * 外部渠道：GitHub 发布页 / 下载页与夸克永久目录。两者都是浏览器行为，文案必须说清楚。
 */
@Composable
private fun ChannelFallback(
    state: UpdateUiState,
    onSelect: (UpdateChannel) -> Unit,
    onOpen: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "外部渠道",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            UpdateChannel.entries.forEach { channel ->
                val configured = channel != UpdateChannel.QUARK || state.quarkAvailable
                FilterChip(
                    selected = state.channel == channel,
                    onClick = { onSelect(channel) },
                    enabled = configured,
                    label = { Text(if (configured) channel.label else "${channel.label} · 暂未发布") },
                    modifier = Modifier.testTag("$TAG-channel-${channel.name.lowercase()}"),
                )
            }
        }
        Text(
            when {
                state.channel == UpdateChannel.QUARK && !state.quarkAvailable ->
                    state.quarkNote ?: QUARK_NOT_PUBLISHED
                state.channel == UpdateChannel.QUARK -> state.quarkNote ?: state.channel.description
                else -> state.channel.description
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val url = state.actionUrl
        val enabled = !state.busy && url != null
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .testTag("$TAG-open-external"),
            shape = MaterialTheme.shapes.medium,
            color = if (enabled) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.Rounded.Download, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    when {
                        state.channel == UpdateChannel.QUARK && !state.quarkAvailable -> "夸克通道暂未发布"
                        state.channel == UpdateChannel.QUARK -> "在浏览器打开夸克目录"
                        state.candidate != null -> "在浏览器打开 GitHub 下载页"
                        else -> "暂无可打开的下载页"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (enabled) {
                    TextButton(
                        onClick = { url?.let(onOpen) },
                        modifier = Modifier.testTag("$TAG-open"),
                    ) { Text("打开") }
                }
            }
        }
        Text(
            EXTERNAL_ONLY_NOTE,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 「a newer official version exists」 block: version, notes excerpt, digest, size. */
@Composable
private fun AvailableBlock(state: UpdateUiState, candidate: UpdateCandidate) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "发现新版本 ${candidate.version}",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        // The card only ever shows a candidate that [ReleasePolicy] already proved to be newer, so the
        // arrow is unconditional here; the guarded compare lives in the policy, not in the UI.
        Text(
            "当前 ${state.build.versionName} → 可更新至 ${candidate.version}（${candidate.channel.label}通道）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        candidate.notes?.let { notes ->
            val trimmed = notes.trim().lines().take(4).joinToString("\n")
            Text(
                trimmed,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "文件名 ${candidate.apk.name} · ${readableSize(candidate.apk.sizeBytes)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "GitHub 公布的 SHA-256：${ReleaseDigest.short(candidate.apk.sha256)}（下载后会核对）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 安装依据与限制的固定说明。这段话是产品承诺，不是错误提示，所以永远显示，且措辞不软化。
 */
@Composable
private fun InstallationBasisNote(state: UpdateUiState) {
    when (state.releaseChannel) {
        UpdateReleaseChannel.PREVIEW -> NoteLine(
            icon = Icons.Rounded.Info,
            tint = MaterialTheme.colorScheme.tertiary,
            text = if(state.legacyPreviewNeedsManualInstall)
                "这是旧的独立预览包。新版使用正式包名，需手动安装一次，两边的数据不会自动合并。"
            else "当前为预览通道，与正式版使用相同包名和签名，可覆盖更新并保留本机数据。",
        )
        UpdateReleaseChannel.OFFICIAL -> NoteLine(
            icon = Icons.Rounded.Info,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            text = "正式版使用同一签名，新的正式 APK 可直接覆盖安装并保留本机数据；不需要卸载。",
        )
        null -> NoteLine(
            icon = Icons.Rounded.Warning,
            tint = MaterialTheme.colorScheme.tertiary,
            text = "这是非发布构建（${state.build.applicationId}）：不会从发布通道自动安装任何安装包。",
        )
    }
    NoteLine(
        icon = Icons.Rounded.Info,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        text = UpdateSignatureBasis.BASIS_SENTENCE,
    )
}

@Composable
private fun PrimaryButton(label: String, icon: ImageVector, tag: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primary,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp))
            Text(
                label,
                color = MaterialTheme.colorScheme.onPrimary,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onClick, modifier = Modifier.testTag("$tag-go")) { Text("继续") }
        }
    }
}

@Composable
private fun NoteLine(icon: ImageVector, tint: Color, text: String) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(16.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun iconFor(kind: UpdateFailure): ImageVector = when (kind) {
    UpdateFailure.OFFLINE, UpdateFailure.TIMEOUT -> Icons.Rounded.Warning
    UpdateFailure.NOT_CONFIGURED -> Icons.Rounded.Info
    UpdateFailure.NO_BROWSER -> Icons.Rounded.Cancel
    else -> Icons.Rounded.ErrorOutline
}

/** "1.2 MB" / "812 KB". A negative or zero size is reported as unknown, never as "0 B". */
internal fun readableSize(bytes: Long): String = readableSizeOf(bytes)

/**
 * 唯一一个"离开应用去网页"的地方：重新校验 URL、检查是否有应用能应答，并且在打不开时说明原因，
 * 而不是崩溃或安静地什么都不做。
 */
private fun openExternally(context: Context, url: String, report: (String) -> Unit) {
    val intents = UpdateIntents(context)
    val intent = try {
        when {
            UpdateUrls.isReleaseApkUrl(url) -> intents.openApk(url)
            UpdateUrls.isReleasePageUrl(url) -> intents.openReleasePage(url)
            UpdateUrls.isQuarkLink(url) -> intents.openQuark(url)
            else -> {
                report("该链接不可打开。")
                return
            }
        }
    } catch (e: IllegalArgumentException) {
        report(e.message ?: "该链接不可打开。")
        return
    }
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        report("未找到可用的浏览器，无法打开下载页。")
    } catch (e: SecurityException) {
        report("系统拒绝打开浏览器。")
    }
}
