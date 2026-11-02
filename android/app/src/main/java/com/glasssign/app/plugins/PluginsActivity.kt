package com.glasssign.app.plugins

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.glasssign.app.BuildConfig
import com.glasssign.app.ui.Amber
import com.glasssign.app.ui.Cyan
import com.glasssign.app.ui.Faint
import com.glasssign.app.ui.GlassCard
import com.glasssign.app.ui.GlassSignTheme
import com.glasssign.app.ui.Mint
import com.glasssign.app.ui.Muted
import com.glasssign.app.ui.NoteRow
import com.glasssign.app.ui.Oled
import com.glasssign.app.ui.Rose
import com.glasssign.app.ui.SectionHeader
import com.glasssign.app.ui.White

/**
 * The optional plugin page: 领签 LingQian as an INDEPENDENT third-party app.
 *
 * WHAT THIS PAGE IS HONEST ABOUT (and what every string here must keep saying):
 *  - 领签 is a separate app, not a module of 星栞: no IPC, no shared account, no shared sign plan.
 *  - 星栞 cannot start, stop or schedule anything inside it.
 *  - The enable switch controls ONE thing: whether 星栞's own "打开领签" button is offered.
 *  - Nothing is downloaded, installed, checked or verified on a timer; opening this page issues no
 *    network request at all.
 *
 * Surface rules: only [GlassSignTheme] and the shared `ui/` pieces are used, so this screen follows
 * whatever appearance the user already chose (dark/light, text scale, surface effect) without
 * reaching into the appearance controller, which is `internal` to the ui package.
 */
class PluginsActivity : ComponentActivity() {

    companion object {
        /** Explicit, non-exported component. The settings page launches exactly this. */
        fun intent(context: Context): Intent = Intent(context, PluginsActivity::class.java)
    }

    /**
     * Counts resumes so the composable can re-verify on every return to the foreground. The system
     * install page, the uninstall screen and the "install unknown apps" settings page all land here,
     * and every one of them can change the truth, so none of them may be assumed.
     */
    private var resumeTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GlassSignTheme(reduceMotion = true) {
                PluginScreen(onBack = { finish() }, resumeTick = resumeTick)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick++
    }
}

private const val TAG = "plugin"

@Composable
private fun PluginScreen(onBack: () -> Unit, resumeTick: Int) {
    val context = LocalContext.current
    val vm: PluginsViewModel = viewModel()
    val state by vm.state.collectAsState()
    val message: (String) -> Unit = { text -> Toast.makeText(context, text, Toast.LENGTH_SHORT).show() }

    // The view model refreshes itself once on construction; every later resume refreshes again.
    LaunchedEffect(resumeTick) { if (resumeTick > 0) vm.refresh() }

    val settingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        vm.refresh()
    }
    val intents = remember(context) { PluginIntents(context) }

    Box(Modifier.fillMaxSize().background(Oled)) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.statusBarsPadding().fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack, modifier = Modifier.testTag("$TAG-back")) {
                    Icon(Icons.Rounded.ArrowBack, contentDescription = "返回", tint = Muted)
                }
                Column(Modifier.weight(1f)) {
                    Text("插件", color = White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Text("可选的第三方独立应用", color = Muted, fontSize = 11.sp)
                }
            }

            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Spacer(Modifier.height(2.dp))
                IdentityCard()
                StatusCard(state = state, onOpenSource = { vm.openSource(message) })
                ActionCard(
                    state = state,
                    onDownload = { vm.download() },
                    onCancel = { vm.cancelDownload() },
                    onInstall = {
                        // targetSdk 35: without REQUEST_INSTALL_PACKAGES granted in settings, the
                        // package URI is refused by the installer, so the settings page comes first.
                        if (!intents.canInstallPackages()) {
                            val settings = intents.unknownSourcesSettings()
                            if (settings != null) {
                                settingsLauncher.launch(settings)
                                message("请在系统设置中允许本应用安装应用，然后返回重试")
                            } else {
                                message("请手动在系统设置中允许本应用安装应用")
                            }
                        } else {
                            vm.install(message)
                        }
                    },
                    onOpen = { vm.open(message) },
                    onOpenStore = { vm.openStore(message) },
                    onUninstall = { vm.uninstallViaSystem(message) },
                    onClean = { vm.cleanDownload() },
                )
                EnableCard(state = state, onToggle = { value -> vm.setEnabled(value, message) })
                HonestyCard()
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun IdentityCard() {
    GlassCard {
        Text(PluginSpec.TITLE, color = White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text("来源：GitHub 发布页 · 版本 v0.1.0 · ${PluginSpec.SIZE_LABEL}", color = Muted, fontSize = 12.sp)
        Text(
            "包名 ${PluginSpec.PACKAGE_NAME} · versionCode ${PluginSpec.EXPECTED_VERSION_CODE}",
            color = Faint,
            fontSize = 11.sp,
            modifier = Modifier.testTag("$TAG-identity"),
        )
        HorizontalDivider(
            Modifier.padding(vertical = 8.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f),
        )
        Text(PluginSpec.EXPERIMENTAL_NOTE, color = Amber, fontSize = 12.sp, lineHeight = 18.sp)
        Text(
            "从领签 GitHub 发布页获取，安装前校验完整性与签名。新版本支持将随星栞更新。",
            color = Muted,
            fontSize = 11.sp,
            lineHeight = 17.sp,
        )
    }
}

@Composable
private fun StatusCard(state: PluginUiState, onOpenSource: () -> Unit) {
    val tone = when (state.state) {
        PluginStateName.ENABLED -> Mint
        PluginStateName.VERIFIED_PENDING_INSTALL -> Cyan
        PluginStateName.DOWNLOAD_FAILED, PluginStateName.INSTALLED_UNVERIFIED -> Rose
        PluginStateName.NOT_DOWNLOADED -> Muted
        else -> Amber
    }
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                state.title,
                color = tone,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f).testTag("$TAG-state"),
            )
            TextButton(onClick = onOpenSource, modifier = Modifier.testTag("$TAG-source")) {
                Text("来源页", color = Cyan, fontSize = 12.sp)
            }
        }
        Text(state.subtitle, color = Muted, fontSize = 12.sp, lineHeight = 18.sp)

        val running = state.download as? PluginState.Download.Running
        if (running != null) {
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { running.percent / 100f },
                modifier = Modifier.fillMaxWidth().height(4.dp).testTag("$TAG-progress"),
                color = Cyan,
                trackColor = White.copy(alpha = .1f),
            )
            Text(
                running.label,
                color = Muted,
                fontSize = 11.sp,
                modifier = Modifier.testTag("$TAG-progress-label"),
            )
        }

        if (state.verifiedApk) {
            Text(
                "文件 ${PluginSpec.APK_FILE} · ${PluginSpec.readableBytes(PluginSpec.SIZE_BYTES)} · " +
                    "SHA-256 ${PluginSpec.shortSha(PluginSpec.SHA256)}",
                color = Faint,
                fontSize = 11.sp,
                modifier = Modifier.testTag("$TAG-artifact"),
            )
        }

        val failed = state.installedCheck as? VerifyReport.Failed
        if (failed != null) {
            Text(
                "验签未通过：${failed.detail}",
                color = Rose,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                modifier = Modifier.testTag("$TAG-verify-error"),
            )
        }

        val error = state.actionError
        if (error != null) {
            Text(error, color = Rose, fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.testTag("$TAG-error"))
        }
    }
}

@Composable
private fun ActionCard(
    state: PluginUiState,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onInstall: () -> Unit,
    onOpen: () -> Unit,
    onOpenStore: () -> Unit,
    onUninstall: () -> Unit,
    onClean: () -> Unit,
) {
    GlassCard {
        SectionHeader("下载与安装")
        if (state.canDownload) {
            OutlinedButton(onClick = onDownload, modifier = Modifier.fillMaxWidth().testTag("$TAG-download")) {
                Text("下载固定版本（${PluginSpec.SIZE_LABEL}，HTTPS）")
            }
        }
        if (state.canRetry) {
            OutlinedButton(onClick = onDownload, modifier = Modifier.fillMaxWidth().testTag("$TAG-retry")) {
                Text("重新下载")
            }
        }
        if (state.canCancel) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(Modifier.size(16.dp), color = Cyan, strokeWidth = 2.dp)
                Text("下载中，可随时取消", color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = onCancel, modifier = Modifier.testTag("$TAG-cancel")) {
                    Text("取消下载", color = Rose, fontSize = 12.sp)
                }
            }
        }
        if (state.canInstall) {
            OutlinedButton(onClick = onInstall, modifier = Modifier.fillMaxWidth().testTag("$TAG-install")) {
                Text("通过系统安装页安装")
            }
        }
        if (state.canOpen && state.enabledActive) {
            OutlinedButton(onClick = onOpen, modifier = Modifier.fillMaxWidth().testTag("$TAG-open")) {
                Icon(Icons.Rounded.OpenInNew, contentDescription = null, tint = Cyan, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("打开领签")
            }
        }
        if (state.canUninstallViaSystem) {
            OutlinedButton(onClick = onUninstall, modifier = Modifier.fillMaxWidth().testTag("$TAG-uninstall")) {
                Text("系统卸载页面")
            }
        }
        if (state.canCleanDownload) {
            TextButton(onClick = onClean, modifier = Modifier.testTag("$TAG-clean")) {
                Text("清理已下载文件", color = Muted, fontSize = 12.sp)
            }
        }
        val nothingToDo = !state.canDownload && !state.canRetry && !state.canCancel &&
            !state.canInstall && !state.canOpen && !state.installed && !state.canUninstallViaSystem
        if (nothingToDo) {
            Text("当前没有可执行的操作。", color = Muted, fontSize = 12.sp, modifier = Modifier.testTag("$TAG-idle"))
        }
        Text(
            "星栞不会静默安装，也不会静默卸载。安装由系统安装页确认；卸载只打开系统界面，不删除领签的用户数据。",
            color = Faint,
            fontSize = 10.sp,
            lineHeight = 15.sp,
        )
    }
}

@Composable
private fun EnableCard(state: PluginUiState, onToggle: (Boolean) -> Unit) {
    GlassCard {
        SectionHeader("星栞内入口", "只影响星栞自己的按钮")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("启用“打开领签”入口", color = White, fontSize = 13.sp)
                Text(
                    when {
                        !state.installed -> "需要先安装并通过验签"
                        !state.signatureOk -> "已安装但验签未通过，不能启用"
                        state.enabledActive -> "已启用：星栞内的按钮可用"
                        else -> "未启用：星栞内不显示入口按钮"
                    },
                    color = Muted,
                    fontSize = 11.sp,
                )
            }
            Switch(
                checked = state.enabledActive,
                onCheckedChange = onToggle,
                enabled = state.canToggleEnabled,
                modifier = Modifier.testTag("$TAG-enable-switch"),
            )
        }
        Text(PluginSpec.STANDALONE_NOTE, color = Faint, fontSize = 10.sp, lineHeight = 15.sp)
        Text(PluginSpec.DISABLE_NOTE, color = Faint, fontSize = 10.sp, lineHeight = 15.sp)
    }
}

@Composable
private fun HonestyCard() {
    GlassCard {
        SectionHeader("使用说明")
        NoteRow(
            Icons.Rounded.Info,
            "领签使用独立账号和签到计划，安装后请在领签内登录、设置后台任务。",
            Cyan,
        )
        Text(
            "星栞 ${BuildConfig.VERSION_NAME} · 插件登记表版本 1（领签 v0.1.0 / versionCode 1）",
            color = Faint,
            fontSize = 10.sp,
        )
    }
}
