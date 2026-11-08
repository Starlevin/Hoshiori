package com.glasssign.app.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.glasssign.app.plugins.PluginIntents
import com.glasssign.app.plugins.PluginSpec
import com.glasssign.app.plugins.PluginState
import com.glasssign.app.plugins.PluginsViewModel
import com.glasssign.app.plugins.VerifyReport

/**
 * 设置 → 插件：领签的「应用内下载」。
 *
 * 2026-11-07 产品决策：删除「实验室」（平台适配器开关、玻璃实验、诊断导出都不再占用设置一级入口），
 * 改为插件页。四平台内置适配器的暂停/恢复能力在 [PluginAdapterSection] 里保留，诊断摘要仍由
 * 「运行与权限」页提供，Liquid Glass 已固定为默认增强、无调节入口。
 *
 * 下载/验签/安装全部复用既有的 [PluginsViewModel]（固定 GitHub 版本、SHA-256 + 包名 + versionCode
 * + 签名证书核验、系统安装页确认，绝不静默安装）。本页面不发定时器、不在打开时自动下载。
 */
@Composable
internal fun PluginsPage(onRefresh: () -> Unit, onDiagnostics: () -> Unit) {
    val context = LocalContext.current
    val vm: PluginsViewModel = viewModel()
    val state by vm.state.collectAsState()
    val message: (String) -> Unit = { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    val intents = remember(context) { PluginIntents(context) }
    val settingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        vm.refresh()
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        PreferenceGroup("领签 · 独立第三方应用") {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(PluginSpec.TITLE, style = MaterialTheme.typography.titleMedium)
                Text(
                    "来源 GitHub 发布页 · v0.1.0 · ${PluginSpec.SIZE_LABEL} · 包名 ${PluginSpec.PACKAGE_NAME}",
                    style = MaterialTheme.typography.bodySmall, color = Muted,
                )
                Text(PluginSpec.EXPERIMENTAL_NOTE, style = MaterialTheme.typography.bodySmall, color = Muted)
                Text(
                    PluginSpec.STANDALONE_NOTE,
                    style = MaterialTheme.typography.bodySmall, color = Muted,
                )
                TextButton(onClick = { vm.openSource(message) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Rounded.OpenInNew, null, Modifier.size(16.dp))
                    Text("  在浏览器打开来源页")
                }
            }
        }

        PreferenceGroup("下载与安装") {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                // 当前状态（由本机测量得出，不推断）。
                Text(state.title, style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("plugin-state"))
                Text(state.subtitle, style = MaterialTheme.typography.bodySmall, color = Muted)
                val running = state.download as? PluginState.Download.Running
                if (running != null) {
                    LinearProgressIndicator(
                        progress = { running.percent / 100f },
                        modifier = Modifier.fillMaxWidth().height(4.dp).padding(vertical = 6.dp),
                    )
                    Text(running.label, style = MaterialTheme.typography.bodySmall, color = Muted)
                }
                val failed = state.installedCheck as? VerifyReport.Failed
                if (failed != null) Text("验签未通过：${failed.detail}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                state.actionError?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error) }

                if (state.canDownload) {
                    Button(onClick = { vm.download() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .testTag("plugin-download")) {
                        Text(if (state.canRetry) "重新下载固定版本（HTTPS）" else "下载固定版本（${PluginSpec.SIZE_LABEL}，HTTPS）")
                    }
                }
                if (state.canCancel) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text("下载中，可随时取消", style = MaterialTheme.typography.bodySmall, color = Muted,
                            modifier = Modifier.weight(1f))
                        TextButton(onClick = { vm.cancelDownload() }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("取消", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                if (state.canInstall) {
                    OutlinedButton(onClick = {
                        if (!intents.canInstallPackages()) {
                            val settings = intents.unknownSourcesSettings()
                            if (settings != null) {
                                settingsLauncher.launch(settings)
                                message("请在系统设置中允许本应用安装应用，然后返回重试")
                            } else message("请手动在系统设置中允许本应用安装应用")
                        } else vm.install(message)
                    }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("plugin-install")) {
                        Text("通过系统安装页安装")
                    }
                }
                if (state.canOpen && state.enabledActive) {
                    OutlinedButton(onClick = { vm.open(message) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text("打开领签")
                    }
                }
                if (state.canUninstallViaSystem) {
                    TextButton(onClick = { vm.uninstallViaSystem(message) }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("系统卸载页面", color = Muted)
                    }
                }
                if (state.canCleanDownload) {
                    TextButton(onClick = { vm.cleanDownload() }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("清理已下载文件", color = Muted)
                    }
                }
                Text("下载后校验文件大小、SHA-256、包名、版本与签名证书；安装由系统安装页确认，星栞不会静默安装或卸载。",
                    style = MaterialTheme.typography.bodySmall, color = Muted)
            }
        }

        PreferenceGroup("星栞内入口") {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("启用「打开领签」按钮", style = MaterialTheme.typography.titleSmall)
                        Text(
                            when {
                                !state.installed -> "需要先安装并通过验签"
                                !state.signatureOk -> "已安装但验签未通过，不能启用"
                                state.enabledActive -> "已启用"
                                else -> "未启用：星栞内不显示入口按钮"
                            },
                            style = MaterialTheme.typography.bodySmall, color = Muted,
                        )
                    }
                    Switch(checked = state.enabledActive, onCheckedChange = { vm.setEnabled(it, message) },
                        enabled = state.canToggleEnabled, modifier = Modifier.testTag("plugin-enable"))
                }
                Text(PluginSpec.DISABLE_NOTE, style = MaterialTheme.typography.bodySmall, color = Muted)
            }
        }

        // 保留原实验室里的平台适配器暂停/恢复能力（只影响本机任务，不删账号/记录）。
        PluginAdapterSection(onRefresh)
        TextButton(onClick = onDiagnostics, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("运行与权限 · 本机诊断")
        }
    }
}

/**
 * 内置平台适配器的暂停/恢复：原「实验室」里唯一与签到业务直接相关的能力，迁到插件页保留。
 * 停用只跳过任务，账号、授权、角色与历史记录全部保留。
 */
@Composable
private fun PluginAdapterSection(onRefresh: () -> Unit) {
    val context = LocalContext.current
    val services = remember(context.applicationContext) { com.glasssign.app.local.LocalServices.get(context) }
    var states by remember { mutableStateOf(services.registry.snapshot()) }
    androidx.compose.runtime.DisposableEffect(services) {
        val listener = services.registry.listen { states = services.registry.snapshot() }
        onDispose { services.registry.stopListening(listener) }
    }
    PreferenceGroup("内置平台适配器") {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            states.forEachIndexed { index, p ->
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(p.manifest.title, style = MaterialTheme.typography.titleSmall)
                        Text(if (p.enabled) "已启用 · ${p.enabledLabel}" else "已暂停：手动、自动及排队任务都会跳过",
                            style = MaterialTheme.typography.bodySmall, color = Muted)
                    }
                    Switch(checked = p.enabled, onCheckedChange = { value ->
                        if (services.registry.setEnabled(p.manifest.id, value)) {
                            states = services.registry.snapshot()
                            onRefresh()
                        }
                    }, enabled = p.compatible,
                        modifier = Modifier.testTag("plugin-adapter-${p.manifest.id}"))
                }
                if (index < states.lastIndex) PreferenceDivider()
            }
            Text("停用只暂停任务；账号、授权、角色及签到记录全部保留。",
                style = MaterialTheme.typography.bodySmall, color = Muted,
                modifier = Modifier.padding(vertical = 8.dp))
        }
    }
}
