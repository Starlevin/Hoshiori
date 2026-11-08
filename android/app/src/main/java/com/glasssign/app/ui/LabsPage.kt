package com.glasssign.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.glasssign.app.BuildConfig
import com.glasssign.app.UiState
import com.glasssign.app.data.*
import com.glasssign.app.local.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun rememberProviderStates():List<ProviderState> {
    val context=LocalContext.current
    val registry=remember(context.applicationContext){LocalServices.get(context).registry}
    var states by remember(registry){mutableStateOf(registry.snapshot())}
    DisposableEffect(registry) {
        val listener=registry.listen{states=registry.snapshot()}
        onDispose{registry.stopListening(listener)}
    }
    return states
}

@Composable
internal fun rememberLabGlassState():Pair<GlassQuality,LabFlagState> {
    val context=LocalContext.current
    val labs=remember(context.applicationContext){LocalServices.get(context).labs}
    fun read()=labs.requestedGlassQuality() to requireNotNull(labs.state(LabStore.FLAG_WATER_DROP_GLASS))
    var state by remember(labs){mutableStateOf(read())}
    DisposableEffect(labs) {
        val listener=labs.listen{state=read()}
        onDispose{labs.stopListening(listener)}
    }
    return state
}

private val GlassQuality.title:String get()=when(this) {
    GlassQuality.FULL->"FULL · 完整"
    GlassQuality.LITE->"LITE · 轻量"
    GlassQuality.OFF->"OFF · 关闭"
}

@Composable
internal fun LabsPage(s:UiState,onRefresh:()->Unit,onExternalApp:()->Unit,onDiagnostics:()->Unit) {
    val context=LocalContext.current
    val services=remember(context.applicationContext){LocalServices.get(context)}
    val providers=rememberProviderStates()
    val (requestedGlass,experiment)=rememberLabGlassState()
    val rendering=rememberDockRendering(requestedGlass)
    val advancedAvailable=rendering.mode==GlassQuality.FULL
    var selectedId by rememberSaveable{mutableStateOf<String?>(null)}
    var pauseId by remember{mutableStateOf<String?>(null)}
    var notice by remember{mutableStateOf<String?>(null)}
    val selected=providers.firstOrNull{it.manifest.id==selectedId}
    fun switchProvider(id:String,enabled:Boolean) {
        if(!services.registry.setEnabled(id,enabled))notice="设置未能保存，请重试"
        else {notice=if(enabled)"适配器已启用，签到范围沿用原设置" else "适配器已暂停";onRefresh()}
    }
    Column(Modifier.fillMaxWidth().testTag("labs-page"),verticalArrangement=Arrangement.spacedBy(20.dp)) {
        if(selected!=null) {
            TextButton(onClick={selectedId=null},modifier=Modifier.heightIn(min=48.dp)){Text("返回实验室")}
            ProviderDetails(selected,s,onChange={value->if(value)switchProvider(selected.manifest.id,true) else pauseId=selected.manifest.id})
        } else {
            Text("在这里管理平台适配器、尝试新效果，或查看本机运行情况。",style=MaterialTheme.typography.bodyMedium,color=Muted)
            PreferenceGroup("插件中心 · 内置适配器") {
                providers.forEachIndexed { index,p ->
                    val count=s.overview?.accounts.orEmpty().count{it.provider==p.manifest.id}
                    PreferenceRow(p.manifest.title,"已内置 · ${p.enabledLabel} · $count 个账号",
                        HoshioriIcons.Puzzle,{selectedId=p.manifest.id},"v${p.manifest.version}")
                    if(index<providers.lastIndex)PreferenceDivider()
                }
                PreferenceDivider()
                PreferenceRow("领签 · 独立应用","单独下载和运行，后台任务由领签管理",HoshioriIcons.System,onExternalApp)
            }
            PreferenceGroup("Liquid Glass 2.0") {
                Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    ChoiceChips(GlassQuality.entries.map { it.name to it.title },requestedGlass.name) { choice ->
                        if(!services.labs.setGlassQuality(GlassQuality.valueOf(choice)))notice="玻璃设置未能保存"
                    }
                    Text("当前生效 · ${rendering.mode.title}",style=MaterialTheme.typography.titleSmall)
                    rendering.fallback?.let { Text(it,style=MaterialTheme.typography.bodySmall,color=Muted) }
                    Text("完整模式的水珠会随手指移动、拉伸并回弹，同时折射页面内容。轻量模式使用半透明底栏，关闭模式使用不透明底色。",style=MaterialTheme.typography.bodySmall,color=Muted)
                    if(!rendering.motion)Text("已减少动态效果",style=MaterialTheme.typography.bodySmall,color=Muted)
                    Text("完整模式验证 · ${experiment.effectiveValidation(BuildConfig.VERSION_NAME).label}",style=MaterialTheme.typography.labelMedium,color=Muted)
                    if(experiment.record.isComplete)Text("验证记录：${experiment.record.buildVersion} · ${experiment.record.device}\n${experiment.record.evidenceRef}",style=MaterialTheme.typography.labelSmall,color=Muted)
                    if(experiment.lastErrorAt.isNotBlank())Text("最近一次异常已回退 · ${experiment.lastErrorAt}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
                }
            }
            PreferenceGroup("诊断与反馈") {
                PreferenceRow("运行与权限","检查网络、通知和后台调度",HoshioriIcons.Activity,onDiagnostics)
                PreferenceDivider()
                LabReportActions(s,providers,requestedGlass==GlassQuality.FULL,advancedAvailable,onNotice={notice=it})
            }
        }
        notice?.let{Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.primary,modifier=Modifier.testTag("labs-notice"))}
    }
    pauseId?.let{id->
        val title=providers.firstOrNull{it.manifest.id==id}?.manifest?.title.orEmpty()
        AlertDialog(onDismissRequest={pauseId=null},title={Text("暂停$title？")},
            text={Text("该平台的手动、自动及排队任务都会跳过。已经开始提交的单项会保留实际结果，账号、角色、授权和历史记录继续保留。")},
            confirmButton={TextButton(onClick={switchProvider(id,false);pauseId=null}){Text("暂停适配器")}},
            dismissButton={TextButton(onClick={pauseId=null}){Text("取消")}})
    }
}

@Composable
private fun ProviderDetails(state:ProviderState,s:UiState,onChange:(Boolean)->Unit) {
    val p=state.manifest
    val accounts=s.overview?.accounts.orEmpty().filter{it.provider==p.id}
    val ids=accounts.map{it.id}.toSet()
    val latest=s.timeline.map{it.record}.filter{it.accountId in ids}.maxByOrNull{it.updatedAt}
    PreferenceGroup(p.title) {
        Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(state.enabledLabel,style=MaterialTheme.typography.titleMedium)
                    Text("内置 · 版本 ${p.version}",style=MaterialTheme.typography.labelMedium,color=Muted)
                }
                Switch(state.enabled,onChange,enabled=state.compatible,modifier=Modifier.testTag("labs-provider-${p.id}").semantics{contentDescription="启用${p.title}适配器"})
            }
            Text(p.games.mapNotNull{GameCatalog.find(it)?.title}.joinToString(" · "),style=MaterialTheme.typography.bodyMedium)
            Text(p.description,style=MaterialTheme.typography.bodySmall,color=Muted)
            Text("能力与权限",style=MaterialTheme.typography.titleSmall)
            Text(state.capabilityLabel,style=MaterialTheme.typography.bodySmall)
            Text(p.permissionSummary,style=MaterialTheme.typography.bodySmall,color=Muted)
            Text("只向适配器交付当前账号的授权；没有下载或执行外部脚本的权限。",style=MaterialTheme.typography.bodySmall,color=Muted)
            Text("最近运行",style=MaterialTheme.typography.titleSmall)
            Text(latest?.let{"${present(it).label} · ${it.updatedAt}"}?:"尚无运行记录",style=MaterialTheme.typography.bodySmall)
            Text("${accounts.size} 个账号 · ${s.bindings.count{it.accountId in ids}} 项游戏/角色",style=MaterialTheme.typography.bodySmall,color=Muted)
            Text("来源：星栞 Hoshiori · 随应用更新\n最低宿主 ${p.minHostVersion} · ${if(state.compatible)"当前版本兼容" else "当前版本不兼容"}",style=MaterialTheme.typography.bodySmall,color=Muted)
            Text("内置适配器不单独卸载。停用只暂停任务，恢复后仍受当天签到记录和重复提交保护约束。",style=MaterialTheme.typography.bodySmall,color=Muted)
        }
    }
}

@Composable
private fun LabReportActions(s:UiState,providers:List<ProviderState>,waterDrop:Boolean,available:Boolean,onNotice:(String)->Unit) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var pendingReport by remember{mutableStateOf("")}
    fun report()=labDiagnosticJson(LabDiagnosticInput(
        BuildConfig.VERSION_NAME,BuildConfig.VERSION_CODE,Build.VERSION.SDK_INT,
        s.overview?.accounts?.size?:0,s.bindings.size,s.overview?.records.orEmpty().map{it.status},
        providers.associate{it.manifest.id to it.enabled},waterDrop,available,s.jobRunning,s.waitingNetwork,
        s.scheduleEnabled,s.overview?.lastStartedAt,s.overview?.lastFinishedAt,s.workerError!=null))
    val exporter=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")){uri->
        if(uri!=null)scope.launch {
            val saved=withContext(Dispatchers.IO){runCatching{
                requireNotNull(context.contentResolver.openOutputStream(uri)).use{it.write(pendingReport.toByteArray(Charsets.UTF_8))}
            }.isSuccess}
            onNotice(if(saved)"本机诊断已导出" else "导出未完成，请重新选择文件")
            pendingReport=""
        }
    }
    Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text("本机诊断摘要",style=MaterialTheme.typography.titleSmall)
        Text("包含版本、调度状态和签到结果计数，不含账号标识、角色名、Cookie 或 Token。仅在你选择导出时保存文件。",style=MaterialTheme.typography.bodySmall,color=Muted)
        TextButton(onClick={
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("星栞本机诊断",report()))
            onNotice("诊断摘要已复制")
        },modifier=Modifier.heightIn(min=48.dp).testTag("labs-copy-diagnostics")){Text("复制诊断摘要")}
        TextButton(onClick={pendingReport=report();exporter.launch("hoshiori-diagnostics-${BuildConfig.VERSION_NAME}.json")},modifier=Modifier.heightIn(min=48.dp)){Text("导出诊断文件")}
    }
}
