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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.glasssign.app.ui.QCard

private const val TAG = "hoshiori-update"

/** Shown when the owner has not published the 夸克 directory link yet. Never replaced by a guess. */
internal const val QUARK_NOT_PUBLISHED =
    "夸克通道暂未发布：永久目录链接尚未配置，这不是错误，稍后会随新版本提供。"

/**
 * 「检查更新」 for 星栞, for the settings page. Self-contained: owns its ViewModel, its network call and
 * its two browser intents, so the host only places it:
 *
 * ```
 * HoshioriUpdateCard()
 * ```
 *
 * WHAT IT WILL NOT DO, AND SAYS SO ON SCREEN:
 *  - It never downloads an APK and never installs one. The button opens a browser; the browser saves
 *    the file and the user opens it, after which the SYSTEM installer asks for confirmation.
 *  - It needs no new permission: the metadata read uses the already-declared `INTERNET`, and opening
 *    a browser is a plain `ACTION_VIEW`.
 *  - It never checks on its own. Opening the settings page sends no request; the user presses the
 *    button.
 *  - It never invents a 夸克 link. Without `assets/update-channel.json` configuration the 夸克 chip says
 *    「暂未发布」 and offers nothing.
 *
 * BOTH CHANNELS SHARE ONE RELEASE ANSWER: the version, notes and `sha256` shown are always GitHub's
 * official metadata; 夸克 only changes which browser page the button opens.
 */
@Composable
fun HoshioriUpdateCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val vm: UpdateViewModel = viewModel(
        key = "$TAG-viewmodel",
        factory = updateViewModelFactory(context),
    )
    val state by vm.state.collectAsState()

    // One-shot feedback. `report` also covers "no browser on this device".
    state.toast?.let { message ->
        LaunchedEffect(message) {
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
            vm.clearToast()
        }
    }

    QCard {
        Text("检查更新", style = MaterialTheme.typography.titleLarge)
        Text(
            "当前版本 ${state.install.versionLabel}（构建 ${state.build.versionCode}${if (state.build.isDebug) " · debug" else ""}）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.testTag("$TAG-version"),
        )

        ChannelRow(
            state = state,
            onSelect = vm::selectChannel,
        )

        when (val result = state.result) {
            null -> Text(
                if (state.busy) "正在读取 GitHub 正式发布信息…" else "手动检查正式版更新，或通过夸克网盘下载。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            is UpdateCheck.Available -> AvailableBlock(state = state, candidate = result.candidate)
            is UpdateCheck.UpToDate -> NoteLine(
                icon = Icons.Rounded.Info,
                tint = MaterialTheme.colorScheme.primary,
                text = result.detail,
            )
            is UpdateCheck.Failed -> {
                NoteLine(
                    icon = iconFor(result.kind),
                    tint = MaterialTheme.colorScheme.error,
                    text = result.message,
                )
                // The 夸克 directory does not depend on GitHub being reachable. Offer it explicitly
                // instead of leaving the user with only an error and no route to the new build.
                if (state.quarkAvailable && state.channel != UpdateChannel.QUARK) {
                    TextButton(
                        onClick = { vm.selectChannel(UpdateChannel.QUARK) },
                        modifier = Modifier.testTag("$TAG-fallback-quark"),
                    ) { Text("改用夸克通道手动打开") }
                }
            }
        }

        // Debug/release mismatch: stated BEFORE the user spends time downloading a file that cannot
        // replace this app and cannot share its data.
        if (state.build.isDebug) {
            NoteLine(
                icon = Icons.Rounded.Warning,
                tint = MaterialTheme.colorScheme.tertiary,
                text = "当前是 debug 包（${state.build.applicationId}）：正式版应用 ID 为 ${state.install.releaseApplicationId}，" +
                    "系统把它当作另一个应用，二者不能互相覆盖、不能自动共享本机数据。需要保留数据时请继续使用正式通道安装的版本。",
            )
        } else {
            NoteLine(
                icon = Icons.Rounded.Info,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                text = "正式版使用同一签名，新的正式 APK 可直接覆盖安装并保留本机数据；不需要卸载。",
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { vm.check() },
                enabled = !state.busy,
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
                TextButton(onClick = { vm.cancel() }, modifier = Modifier.testTag("$TAG-cancel")) {
                    Text("取消")
                }
            }
        }

        PrimaryAction(state = state, onNeedUrl = vm::requestUrl, onOpen = { url -> openInBrowser(context, url) { vm.report(it) } })
    }
}

/**
 * The channel selector. Two chips, both fed by the SAME release answer.
 *
 * The 夸克 chip is always visible, even before the owner configures the link: hiding it would make the
 * absence invisible, while showing it with 「暂未发布」 states exactly what is true.
 */
@Composable
private fun ChannelRow(state: UpdateUiState, onSelect: (UpdateChannel) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "下载通道",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            UpdateChannel.entries.forEach { channel ->
                // The 夸克 chip stays clickable even when GitHub cannot be reached: it is the manual
                // fallback, and disabling it would remove the only working route precisely when the
                // user needs it.
                val configured = channel != UpdateChannel.QUARK || state.quarkAvailable
                FilterChip(
                    selected = state.channel == channel,
                    onClick = { onSelect(channel) },
                    enabled = configured,
                    label = {
                        Text(
                            if (configured) channel.label else "${channel.label} · 暂未发布",
                        )
                    },
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
    }
}

/** The "a newer official version exists" block: version, notes excerpt, digest, size. */
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
            "当前 ${state.build.versionName} → 可更新至 ${candidate.version}",
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
        // The digest is GitHub's OWN claim about the published file, not something this app computed:
        // nothing is downloaded here. Labelled that way so nobody mistakes it for a local verification.
        Text(
            "GitHub 公布的 SHA-256：${ReleaseDigest.short(candidate.apk.sha256)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The one action button. It opens a browser and nothing else, and it says what will happen *before*
 * the browser appears so the user is never surprised by a download.
 */
@Composable
private fun PrimaryAction(
    state: UpdateUiState,
    onNeedUrl: () -> String?,
    onOpen: (String) -> Unit,
) {
    val label = when {
        state.channel == UpdateChannel.QUARK && !state.quarkAvailable -> "夸克通道暂未发布"
        state.channel == UpdateChannel.QUARK -> "在浏览器打开夸克目录"
        state.candidate != null -> "用浏览器下载正式 APK"
        else -> "暂无可下载的正式版本"
    }
    val enabled = !state.busy && state.actionUrl != null
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .testTag("$TAG-action"),
        shape = MaterialTheme.shapes.medium,
        color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                Icons.Rounded.Download,
                null,
                tint = if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Text(
                label,
                color = if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            if (enabled) {
                TextButton(
                    onClick = { onNeedUrl()?.let(onOpen) },
                    modifier = Modifier.testTag("$TAG-open"),
                ) { Text("打开") }
            }
        }
    }
    Text(
        "只会打开浏览器，不会自动下载或安装：文件由浏览器保存，你确认后再由系统安装器完成安装。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp),
    )
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
internal fun readableSize(bytes: Long): String = when {
    bytes <= 0 -> "大小未公布"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
    else -> String.format(java.util.Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0))
}

/**
 * The only place an Intent leaves this feature. Re-validates the URL, checks that something can
 * answer it, and reports a missing browser instead of crashing.
 */
private fun openInBrowser(context: Context, url: String, report: (String) -> Unit) {
    val intents = UpdateIntents(context)
    val intent = try {
        if (UpdateUrls.isReleaseApkUrl(url)) intents.openApk(url) else intents.openQuark(url)
    } catch (e: IllegalArgumentException) {
        report(e.message ?: "该链接不可打开。")
        return
    }
    // Android package visibility can hide a browser from resolveActivity while still allowing launch.
    // Let the system resolve the action and handle ActivityNotFoundException below.
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        report("未找到可用的浏览器，无法打开下载页。")
    } catch (e: SecurityException) {
        report("系统拒绝打开浏览器。")
    }
}

/** Factory so the host does not need to know how the ViewModel is wired. */
private fun updateViewModelFactory(context: Context): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val app = context.applicationContext
            return UpdateViewModel.production(app) as T
        }
    }
