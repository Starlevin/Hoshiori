package com.glasssign.app.ui

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.glasssign.app.*
import com.glasssign.app.data.*
import com.glasssign.app.local.SignRepository
import java.time.*
import java.time.format.DateTimeFormatter

@Composable fun HoshioriScreen(vm:HomeViewModel,onLogin:(String,String,String?)->Unit) {
    val s=vm.state;val accounts=s.overview?.accounts.orEmpty().filter{!it.bindingPending};val records=s.overview?.records.orEmpty()
    var tab by rememberSaveable{mutableIntStateOf(0)};var detail by rememberSaveable{mutableStateOf<String?>(null)}
    var adding by rememberSaveable{mutableStateOf(false)};var renew by rememberSaveable{mutableStateOf<String?>(null)}
    var appearance by remember{mutableStateOf(false)};var plan by remember{mutableStateOf(false)}
    var danger by remember{mutableStateOf<Pair<Account,String>?>(null)};var rename by remember{mutableStateOf<Account?>(null)}
    var record by remember{mutableStateOf<TimelineRecord?>(null)}
    var resultFilter by rememberSaveable{mutableStateOf("all")};var providerFilter by rememberSaveable{mutableStateOf("all")}
    var accountFilter by rememberSaveable{mutableStateOf("all")};var gameFilter by rememberSaveable{mutableStateOf("all")}
    val byId=remember(accounts){accounts.associateBy{it.id}}
    val visible=remember(s.timeline,resultFilter,providerFilter,accountFilter,gameFilter,byId){s.timeline.filter{e->val a=byId[e.record.accountId];(resultFilter=="all"||present(e.record).group==resultFilter)&&(providerFilter=="all"||a?.provider==providerFilter)&&(accountFilter=="all"||e.record.accountId==accountFilter)&&(gameFilter=="all"||a?.let{GameCatalog.gameForTarget(it.provider,e.record.targetId)}==gameFilter)}.sortedWith(compareByDescending<TimelineRecord>{it.day}.thenByDescending{it.record.updatedAt})}
    val source=rememberGraphicsLayer();val policy=currentGlassPolicy();val colors=MaterialTheme.colorScheme
    val scroll=listOf(rememberLazyListState(),rememberLazyListState(),rememberLazyListState(),rememberLazyListState())
    val detailScroll=rememberLazyListState()
    LaunchedEffect(detail){if(detail!=null)detailScroll.scrollToItem(0)}
    val a=byId[detail];val context=LocalContext.current
    val permission=androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()){}
    LaunchedEffect(s.focusAccountId){s.focusAccountId?.let{tab=1;detail=it;vm.clearFocus()}}
    val modal=adding||s.pendingLogin!=null||appearance||plan||danger!=null||rename!=null||record!=null
    androidx.activity.compose.BackHandler(enabled=!modal&&(tab!=0||detail!=null)){if(detail!=null)detail=null else tab=0}
    Box(Modifier.fillMaxSize().background(colors.background)) {
        Box(Modifier.fillMaxSize().backdropSource(source,policy.blur)) {
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(colors.primaryContainer.copy(alpha=.38f),colors.background,colors.secondaryContainer.copy(alpha=.18f)))))
            key(tab,detail) {
                LazyColumn(state=if(tab==1&&a!=null)detailScroll else scroll[tab],modifier=Modifier.fillMaxSize().testTag("page-$tab"),contentPadding=PaddingValues(start=20.dp,end=20.dp,top=12.dp,bottom=160.dp+WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                    item("header") {
                        Row(Modifier.statusBarsPadding().fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                            if(tab==1&&a!=null)IconButton(onClick={detail=null}){Icon(Icons.Rounded.ArrowBack,"返回账号列表")}
                            Column(Modifier.weight(1f)) {
                                Text(when(tab){0->"星栞";1->if(a==null)"账号" else "账号详情";2->"记录";else->"设置"},style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.SemiBold)
                                Text(when(tab){0->"今日签到 · ${s.overview?.date.orEmpty()}";1->if(a==null)"${accounts.map{it.provider}.distinct().size} 个平台 · ${accounts.size} 个账号 · ${s.bindings.count{it.enabled&&it.accountId in byId}} 项游戏/角色" else providerMeta(a.provider).title;2->"每次执行都有独立结果";else->"本机计划 · 运行诊断 · 外观"},style=MaterialTheme.typography.bodySmall,color=Muted)
                            }
                            if(tab==1&&a==null)FilledTonalIconButton(onClick={renew=null;adding=true},modifier=Modifier.sizeIn(minWidth=48.dp,minHeight=48.dp).testTag("add-account")){Icon(Icons.Rounded.Add,"新增账号")}
                        }
                    }
                    when(tab) {
                        0 -> {
                            val tasks=s.bindings.filter{it.enabled&&byId[it.accountId]?.enabled==true}.distinctBy{it.accountId to it.targetId}
                            val completed=tasks.count{b->records.any{it.accountId==b.accountId&&it.targetId==b.targetId&&present(it).group=="success"}}
                            val attention=records.any{present(it).group in listOf("attention","uncertain","submitted")}
                            val allDone=tasks.isNotEmpty()&&completed==tasks.size&&!attention
                            if(accounts.isEmpty())item("empty"){QCard{Text("还没有绑定账号",style=MaterialTheme.typography.headlineSmall);Text("绑定一次，每日由手机本地签到。",color=Muted);Button(onClick={tab=1;detail=null},modifier=Modifier.fillMaxWidth().heightIn(min=50.dp)){Text("去绑定账号")}}}
                            else {
                                item("summary"){QCard{
                                    Text(if(s.jobRunning||s.submitting)"正在签到" else if(s.waitingNetwork)"等待系统执行" else if(accounts.none{it.enabled})"签到已暂停" else if(tasks.isEmpty())"角色待同步" else "已成功 $completed / ${tasks.size} 项",style=MaterialTheme.typography.headlineMedium)
                                    Text("按游戏与角色统计 · 社区奖励另记",color=Muted)
                                    if(accounts.any{account->s.bindings.none{it.accountId==account.id}})Text("部分旧账号角色待读取。到账号详情管理游戏，不以账号数量代替签到项。",style=MaterialTheme.typography.bodySmall,color=Muted)
                                    Button(onClick={if(accounts.none{it.enabled}){tab=1;detail=null} else if(allDone||attention)tab=2 else vm.runNow()},enabled=!s.jobRunning&&!s.submitting&&!s.waitingNetwork,modifier=Modifier.fillMaxWidth().heightIn(min=54.dp).testTag("home-cta")){Text(if(s.jobRunning||s.submitting)"正在签到…" else if(accounts.none{it.enabled})"管理账号" else if(allDone)"查看今日记录" else if(attention)"处理未完成项" else "立即签到")}
                                }}
                                items(accounts.filter{it.authState in listOf("needs_action","failed")},key={"auth-${it.id}"}){account->QCard({tab=1;detail=account.id}){Text("${providerMeta(account.provider).title} · ${safeLabel(account.nickname)}需要处理",color=colors.error);Text("点击修复授权",style=MaterialTheme.typography.bodySmall,color=Muted)}}
                                items(tasks,key={it.accountId+"|"+it.targetId}){b->QCard({tab=1;detail=b.accountId}){Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(safeLabel(b.name),style=MaterialTheme.typography.titleMedium);Text(safeLabel(byId[b.accountId]?.nickname.orEmpty()),style=MaterialTheme.typography.bodySmall,color=Muted)};QResult(records.firstOrNull{it.accountId==b.accountId&&it.targetId==b.targetId})}}}
                            }
                            item("plan"){QCard({tab=3}){Text("每日计划",style=MaterialTheme.typography.titleMedium);Text(if(s.scheduleEnabled)"已开启 · ${s.dailyTime} 后由系统安排" else "未开启 · 可在设置中安排",color=Muted);Text("最近实际执行：${qTime(s.overview?.lastFinishedAt)}",style=MaterialTheme.typography.bodySmall,color=Muted);Text("下次预计：${qNext(s)}",style=MaterialTheme.typography.bodySmall,color=Muted)}}
                        }
                        1 -> if(a==null) {
                            if(accounts.isEmpty())item("empty"){QCard{Text("让账号各自独立",style=MaterialTheme.typography.headlineSmall);Text("点击右上角 +，选择平台后登录。一个账号可管理多个游戏与角色。",color=Muted)}}
                            items(accounts,key={it.id}){account->QAccount(account,s.bindings.filter{it.accountId==account.id}){detail=account.id}}
                            items(s.overview?.accounts.orEmpty().filter{it.bindingPending},key={"pending-${it.id}"}){account->QCard{Text("未完成的绑定 · ${safeLabel(account.nickname)}");Text("尚未启用签到",color=Muted);TextButton(onClick={vm.verifyAuthorization(account.id)}){Text("继续读取角色")};TextButton(onClick={vm.discardDraft(account.id)}){Text("取消这次绑定")}}}
                        } else {
                            item("identity"){QAccount(a,s.bindings.filter{it.accountId==a.id}){}}
                            item("actions"){QCard{
                                QSwitch("启用此账号签到","关闭后手动与自动队列均跳过",a.enabled){vm.setEnabled(a.id,it)}
                                QSwitch("参加每日自动签到","需开启全局计划；关闭后仍可手动签到",a.autoSignEnabled){vm.autoEnabled(a.id,it)}
                                OutlinedButton(onClick={vm.manageGames(a.id)},enabled=!s.loginBusy,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("manage-games")){Text("管理游戏与角色")}
                                TextButton(onClick={vm.verifyAuthorization(a.id)}){Text("验证授权（不签到）")}
                                val done=records.filter{it.accountId==a.id}.let{it.isNotEmpty()&&it.all{r->present(r).group=="success"}}
                                Button(onClick={if(done)tab=2 else vm.runNow(listOf(a.id))},enabled=a.enabled&&!s.jobRunning&&!s.submitting&&!s.waitingNetwork,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text(if(done)"已签到 · 查看记录" else "立即签到")}
                            }}
                            items(s.bindings.filter{it.accountId==a.id},key={it.targetId}){b->QCard{QSwitch(safeLabel(b.name),"${GameCatalog.find(b.gameId)?.title} · 独立角色签到项",b.enabled){vm.bindingEnabled(a.id,b.targetId,it)}}}
                            item("maintenance"){QCard{
                                Text("账号维护",style=MaterialTheme.typography.titleMedium)
                                TextButton(onClick={renew=a.id;adding=true}){Text("重新授权")}
                                TextButton(onClick={rename=a}){Text("编辑备注")}
                                TextButton(onClick={danger=a to "revoke"}){Text("解除授权",color=colors.error)}
                                TextButton(onClick={danger=a to "history"}){Text("删除此账号历史",color=colors.error)}
                                TextButton(onClick={danger=a to "delete"}){Text("删除账号与全部本机数据",color=colors.error)}
                            }}
                        }
                        2 -> {
                            item("filters"){Column {
                                Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf("all" to "全部","success" to "成功","attention" to "需要处理","uncertain" to "待确认").forEach{(id,label)->FilterChip(resultFilter==id,{resultFilter=id},label={Text(label)})}}
                                Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                                    QFilter("平台",providerFilter,listOf("all" to "全部平台")+Providers.map{it.id to it.title}){providerFilter=it}
                                    QFilter("账号",accountFilter,listOf("all" to "全部账号")+accounts.map{it.id to safeLabel(it.nickname)}){accountFilter=it}
                                    QFilter("游戏",gameFilter,listOf("all" to "全部游戏")+GameCatalog.all.map{it.id to it.title}){gameFilter=it}
                                }
                            }}
                            if(visible.isEmpty())item("empty"){QCard{Text("暂无符合条件的记录",style=MaterialTheme.typography.titleMedium);Text("签到后按日期、账号与游戏保留结果。",color=Muted)}}
                            items(visible,key={it.day+"|"+it.record.accountId+"|"+it.record.targetId}){entry->val r=entry.record;QCard({record=entry}){Text("${entry.day} · ${safeLabel(byId[r.accountId]?.nickname.orEmpty())}",style=MaterialTheme.typography.bodySmall,color=Muted);Row{Text(safeLabel(r.targetName),modifier=Modifier.weight(1f),style=MaterialTheme.typography.titleMedium);Spacer(Modifier.width(8.dp));QResult(r)};Text(safeLabel(r.message),style=MaterialTheme.typography.bodySmall,color=Muted);Text("开始 ${qTime(r.startedAt)}\n结束 / 更新 ${qTime(r.updatedAt)}",style=MaterialTheme.typography.labelSmall,color=Muted);if(present(r).group=="attention")TextButton(onClick={tab=1;detail=r.accountId}){Text("修复账号")}}}
                            item("retry"){Column{if(s.timeline.any{it.record.status in listOf("failed","waiting_network")})OutlinedButton(onClick={vm.runNow()},enabled=!s.jobRunning&&!s.submitting&&!s.waitingNetwork){Text("重试可安全重试的未完成项")};Text("失败重试遵守间隔与最多3次限制；待确认提交不重发，点击记录可校验官方状态。",style=MaterialTheme.typography.bodySmall,color=Muted)}}
                        }
                        3 -> {
                            item("automatic"){QCard{Text("自动签到",style=MaterialTheme.typography.titleLarge);QSwitch("每日自动签到","手机本地执行；系统可能延迟",s.scheduleEnabled){vm.saveSchedule(it,s.dailyTime)};TextButton(onClick={plan=true}){Text("每日运行偏好 · ${s.dailyTime}")};Text("本次开始：${if(s.jobRunning)qTime(s.overview?.lastStartedAt) else "当前未执行"}",color=Muted);Text("最近实际执行：${qTime(s.overview?.lastFinishedAt)}",color=Muted);Text("下次预计：${qNext(s)}",color=Muted);TextButton(onClick={tab=1;detail=null}){Text("按账号 / 游戏管理")}}}
                            item("diagnostics"){QDiagnostics(s,policy,{vm.refresh(false)}){action->if(action=="notification"&&android.os.Build.VERSION.SDK_INT>=33)permission.launch(android.Manifest.permission.POST_NOTIFICATIONS) else runCatching{context.startActivity(Intent(if(action=="battery")Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS else if(action=="notification")Settings.ACTION_APP_NOTIFICATION_SETTINGS else Settings.ACTION_WIRELESS_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,context.packageName))}}}
                            item("appearance"){QCard{Text("外观",style=MaterialTheme.typography.titleLarge);TextButton(onClick={appearance=true},modifier=Modifier.testTag("appearance")){Text("主题与液态玻璃")};QSwitch("减少动效","关闭弹性过渡，同时尊重系统动画设置",s.reduceMotion){vm.setReduceMotion(it)};Text("当前材质：${policy.reason}",style=MaterialTheme.typography.bodySmall,color=Muted)}}
                            item("privacy"){QCard{Text("数据与隐私",style=MaterialTheme.typography.titleLarge);Text("授权通过本机 Keystore 加密。密码、验证码不保存；不需要云服务器。",color=Muted);Text("升级自动迁移本机数据，失败保留原数据与私有检查点。暂不支持跨设备授权备份；卸载或清数据会丢失本机密钥。",style=MaterialTheme.typography.bodySmall,color=Muted);TextButton(onClick={tab=1;detail=null}){Text("授权管理与历史清理")}}}
                            item("about"){Text("星栞 Hoshiori ${BuildConfig.VERSION_NAME}\n本机执行 · 无自建服务器\n第三方实号及长期后台表现以实际验收为准",style=MaterialTheme.typography.bodySmall,color=Muted)}
                        }
                    }
                    item("end"){Spacer(Modifier.height(12.dp))}
                }
            }
        }
        if(!modal&&WindowInsets.ime.getBottom(LocalDensity.current)==0)LiquidDock(tab,{tab=it;detail=null},source,policy,Modifier.align(Alignment.BottomCenter))
        s.message?.let{message->Snackbar(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(16.dp),action={TextButton(onClick={vm.dismissMessage(message.at)}){Text("知道了")}}){Text(message.text)}}
        if(s.loginBusy&&s.pendingLogin==null)Surface(Modifier.align(Alignment.Center),shape=RoundedCornerShape(24.dp)){Column(Modifier.padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally){CircularProgressIndicator();Spacer(Modifier.height(12.dp));Text("正在读取账号与角色…")}}
    }
    if(adding)AddAccountSheet(byId[renew],s.loginBusy,{adding=false},{provider,alias->adding=false;onLogin(provider,alias,renew)},{provider,alias,c->adding=false;vm.beginLogin(provider,alias,c,renew)})
    s.pendingLogin?.let{p->RoleBindingSheet(p,s.loginBusy,vm::togglePendingGame,vm::togglePendingRole,vm::pendingAuto,vm::reviewBinding,vm::confirmLogin,vm::cancelLogin){val original=vm.replacementForPending();vm.cancelLogin();onLogin(p.provider,p.nickname,original)}}
    if(appearance)AppearanceDialog{appearance=false}
    if(plan)SettingsDialog(scheduleEnabled=s.scheduleEnabled,dailyTime=s.dailyTime,reduceMotion=s.reduceMotion,busy=s.jobRunning,onDismiss={plan=false},onSave={enabled,time->vm.saveSchedule(enabled,time);plan=false;if(enabled&&android.os.Build.VERSION.SDK_INT>=33)permission.launch(android.Manifest.permission.POST_NOTIFICATIONS)},onClear=vm::clearCredentials,onReduceMotion=vm::setReduceMotion)
    rename?.let{account->var alias by remember(account.id){mutableStateOf(account.nickname)};AlertDialog(onDismissRequest={rename=null},title={Text("编辑账号备注")},text={OutlinedTextField(alias,{alias=it.take(40)},label={Text("备注")})},confirmButton={TextButton(onClick={vm.rename(account.id,alias);rename=null},enabled=alias.isNotBlank()){Text("保存")}},dismissButton={TextButton(onClick={rename=null}){Text("取消")}})}
    danger?.let{(account,kind)->AlertDialog(onDismissRequest={danger=null},title={Text(when(kind){"revoke"->"解除授权？";"history"->"删除历史明细？";else->"删除账号？"})},text={Text("${providerMeta(account.provider).title} · ${safeLabel(account.nickname)}\n"+when(kind){"revoke"->"仅清除加密授权，保留账号、角色、暂停偏好与历史。";"history"->"只删除该账号历史明细，不删除授权或改变计划。当天提交保护仍保留，未知结果不会重发。";else->"删除此账号及所属角色、授权和全部本机记录。其他账号不受影响。"})},confirmButton={TextButton(onClick={when(kind){"revoke"->vm.revoke(account.id);"history"->vm.clearRecords(account.id);else->{vm.deleteAccount(account.id,account.nickname);detail=null}};danger=null}){Text("确认",color=colors.error)}},dismissButton={TextButton(onClick={danger=null}){Text("取消")}})}
    record?.let{entry->val r=entry.record;val account=byId[r.accountId];val p=present(r);AlertDialog(onDismissRequest={record=null},title={Text(p.label)},text={Column(verticalArrangement=Arrangement.spacedBy(10.dp)){Text("${entry.day} · ${safeLabel(account?.nickname.orEmpty())}");Text(safeLabel(r.targetName));Text(p.source);Text(safeLabel(r.message));Text("开始：${qTime(r.startedAt)}\n结束 / 最近状态：${qTime(r.updatedAt)}\n提交尝试：${r.attempts}")}},confirmButton={TextButton(onClick={record=null}){Text("关闭")}},dismissButton={Column{if(account!=null&&entry.day==s.overview?.date)TextButton(onClick={vm.reconcile(account.id,r.targetId);record=null}){Text("校验状态（不提交）")};if(account!=null)TextButton(onClick={record=null;tab=1;detail=account.id}){Text("修复账号")};if(account!=null)TextButton(onClick={runCatching{context.startActivity(Intent(Intent.ACTION_VIEW,android.net.Uri.parse(com.glasssign.app.login.LoginPlatforms.of(account.provider).startUrl)))}}){Text("打开官方页面")}}})}
}

@Composable internal fun QCard(onClick:(()->Unit)?=null,content:@Composable ColumnScope.()->Unit) {val c=MaterialTheme.colorScheme;Surface(Modifier.fillMaxWidth().then(if(onClick!=null)Modifier.clickable(onClick=onClick) else Modifier),shape=RoundedCornerShape(24.dp),color=c.surface.copy(alpha=.94f),contentColor=c.onSurface,border=BorderStroke(1.dp,c.outlineVariant.copy(alpha=.5f))){Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp),content=content)}}
@Composable internal fun QSwitch(title:String,description:String,checked:Boolean,onChange:(Boolean)->Unit){Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){Column(Modifier.weight(1f)){Text(title,style=MaterialTheme.typography.titleSmall);Text(description,style=MaterialTheme.typography.bodySmall,color=Muted)};Switch(checked,onChange)}}
@Composable private fun QAccount(a:Account,bindings:List<GameBinding>,onClick:()->Unit){QCard(onClick){Row(verticalAlignment=Alignment.CenterVertically){ProviderBadge(providerMeta(a.provider));Spacer(Modifier.width(12.dp));Column(Modifier.weight(1f)){Text(providerMeta(a.provider).title,style=MaterialTheme.typography.titleMedium);Text(safeLabel(a.nickname),color=Muted)};Text(if(!a.enabled)"已暂停" else if(a.authState=="valid")"授权可用" else "需要处理",style=MaterialTheme.typography.labelLarge,color=if(a.authState=="valid")MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)};Text("账号 ${maskedIdentity(a.providerUserId)}",style=MaterialTheme.typography.bodySmall,color=Muted);Text(a.gameIds.mapNotNull{GameCatalog.find(it)?.title}.joinToString(" · ").ifBlank{"游戏/角色待读取"});Text("${bindings.count{it.enabled}} 项角色签到 · ${if(a.autoSignEnabled)"参加自动计划" else "仅手动"}",style=MaterialTheme.typography.bodySmall,color=Muted);if(a.authMessage.isNotBlank())Text(safeLabel(a.authMessage),style=MaterialTheme.typography.bodySmall,color=Muted)}}
@Composable private fun QResult(r:CheckRecord?){val p=r?.let{present(it)};Text(p?.label ?: "待签到",style=MaterialTheme.typography.labelLarge,color=when(p?.group){"success"->MaterialTheme.colorScheme.primary;"attention","uncertain"->MaterialTheme.colorScheme.error;else->Muted})}
private fun qTime(value:String?):String=if(value.isNullOrBlank())"尚无记录" else runCatching{OffsetDateTime.parse(value).atZoneSameInstant(SignRepository.TZ).format(DateTimeFormatter.ofPattern("MM-dd HH:mm:ss"))}.getOrDefault(value.take(19))
private fun qNext(s:UiState):String{if(!s.scheduleEnabled)return "未开启";if(s.waitingNetwork)return "等待网络或系统调度";return runCatching{val now=ZonedDateTime.now(SignRepository.TZ);var next=now.toLocalDate().atTime(LocalTime.parse(s.dailyTime)).atZone(SignRepository.TZ);if(next<now)next=next.plusDays(1);next.format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))+" 后（非精确时间）"}.getOrDefault("等待系统安排")}
@Composable private fun QFilter(label:String,value:String,options:List<Pair<String,String>>,onChoose:(String)->Unit){var open by remember{mutableStateOf(false)};Box{OutlinedButton(onClick={open=true}){Text("$label：${options.firstOrNull{it.first==value}?.second.orEmpty()}")};DropdownMenu(open,{open=false}){options.forEach{(id,title)->DropdownMenuItem(text={Text(title)},onClick={onChoose(id);open=false})}}}}
@Composable private fun QDiagnostics(s:UiState,policy:GlassPolicy,onRefresh:()->Unit,onSettings:(String)->Unit){val c=LocalContext.current;val connected=c.getSystemService(ConnectivityManager::class.java).let{it.getNetworkCapabilities(it.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)==true};val notifications=androidx.core.app.NotificationManagerCompat.from(c).areNotificationsEnabled();val exempt=c.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(c.packageName)
    QCard{Text("运行诊断",style=MaterialTheme.typography.titleLarge);Text("通知：${if(notifications)"已允许" else "未允许，可能看不到结果通知"}");if(!notifications)TextButton(onClick={onSettings("notification")}){Text("设置通知权限")};Text("网络：${if(connected)"系统已验证联网，社区连通性以请求为准" else "尚未验证联网"}");if(!connected)TextButton(onClick={onSettings("network")}){Text("检查网络")};Text("电池限制：${if(exempt)"未限制此应用" else "受系统优化管理，后台可能延迟"}");TextButton(onClick={onSettings("battery")}){Text("查看电池优化设置")};Text("系统队列：${if(s.jobRunning)"正在执行" else if(s.waitingNetwork)"已排队 / 等待约束" else "暂无执行中任务"}");Text("授权需处理：${s.overview?.accounts.orEmpty().count{!it.bindingPending&&it.authState!="valid"}} 个账号");Text("材质：${policy.reason}",style=MaterialTheme.typography.bodySmall,color=Muted);TextButton(onClick=onRefresh){Text("刷新诊断")}}
}
