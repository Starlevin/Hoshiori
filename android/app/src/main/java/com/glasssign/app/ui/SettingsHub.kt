package com.glasssign.app.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.PowerManager
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.glasssign.app.UiState

internal enum class SettingsSection(val title:String) {
    HOME("设置"),AUTOMATION("自动签到"),DIAGNOSTICS("运行与权限"),
    PRIVACY("数据与隐私"),UPDATES("版本与更新"),ABOUT("关于星栞")
}

internal fun themeDisplayName(settings:AppearanceSettings)=when {
    settings.colorStyle in listOf("neutral","monochrome")->"经典黑白"
    settings.dynamicColor->"跟随壁纸"
    settings.colorStyle=="tinted" && settings.seed==0xFF4263C7.toInt()->"深蓝"
    settings.colorStyle=="soft" && settings.seed==0xFF7553A8.toInt()->"柔紫"
    else->"自定义"
}

@Composable
internal fun SettingsHub(scheduleSummary:String,onAppearance:()->Unit,onSection:(SettingsSection)->Unit,onPlugins:()->Unit) {
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(22.dp)) {
        PreferenceGroup("使用偏好") {
            PreferenceRow("外观与主题","主题、配色与页面背景",HoshioriIcons.Appearance,onAppearance,
                themeDisplayName(LocalAppearance.current))
            PreferenceDivider()
            PreferenceRow("自动签到","管理每日计划与参与范围",HoshioriIcons.Automation,
                {onSection(SettingsSection.AUTOMATION)},scheduleSummary)
        }
        PreferenceGroup("运行与扩展") {
            PreferenceRow("运行与权限","通知、电池与任务诊断",HoshioriIcons.Activity,{onSection(SettingsSection.DIAGNOSTICS)})
            PreferenceDivider()
            PreferenceRow("插件与扩展","管理按需安装的功能",HoshioriIcons.Puzzle,onPlugins)
        }
        PreferenceGroup("应用与数据") {
            PreferenceRow("数据与隐私","本机授权与账号管理",HoshioriIcons.Shield,{onSection(SettingsSection.PRIVACY)})
            PreferenceDivider()
            PreferenceRow("版本与更新","查看版本与下载通道",HoshioriIcons.System,{onSection(SettingsSection.UPDATES)})
            PreferenceDivider()
            PreferenceRow("关于星栞","应用信息与星海书签",HoshioriIcons.Info,{onSection(SettingsSection.ABOUT)})
        }
        Text("由手机本地执行，每日记录留在本机。",modifier=Modifier.padding(horizontal=6.dp),
            style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun SettingsSectionHeader(title:String,onBack:()->Unit) {
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        TextButton(onClick=onBack,contentPadding=PaddingValues(horizontal=0.dp),modifier=Modifier.heightIn(min=48.dp)) {
            Icon(HoshioriIcons.Back,null,Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp));Text("返回设置")
        }
        Text(title,style=MaterialTheme.typography.headlineLarge)
    }
}

@Composable
internal fun GroupedDiagnostics(s:UiState,onRefresh:()->Unit,onSettings:(String)->Unit) {
    val context=LocalContext.current
    val network=context.getSystemService(ConnectivityManager::class.java)
    val connected=network.getNetworkCapabilities(network.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)==true
    val notifications=androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
    val exempt=context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(20.dp)) {
        PreferenceGroup("系统权限") {
            PreferenceRow("签到通知",if(notifications)"已允许通知" else "未允许，签到结果仍会保存在记录中",
                HoshioriIcons.Bell,{onSettings("notification")})
            PreferenceDivider()
            PreferenceRow("电池与后台",if(exempt)"未限制此应用" else "受系统优化管理，后台可能延迟",
                HoshioriIcons.Battery,{onSettings("battery")})
            PreferenceDivider()
            PreferenceRow("网络连接",if(connected)"系统已验证联网" else "尚未验证联网",
                HoshioriIcons.System,{onSettings("network")})
        }
        PreferenceGroup("运行状态") {
            Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text(if(s.jobRunning)"当前任务正在执行" else if(s.waitingNetwork)"等待网络或系统调度" else "当前没有执行中任务",
                    style=MaterialTheme.typography.titleSmall)
                Text("需要处理授权的账号：${s.overview?.accounts.orEmpty().count{!it.bindingPending&&it.authState!="valid"}}",
                    style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Text("系统联网状态不代表社区接口已通过验证。",style=MaterialTheme.typography.bodySmall,
                    color=MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick=onRefresh){Text("刷新运行状态")}
            }
        }
    }
}
