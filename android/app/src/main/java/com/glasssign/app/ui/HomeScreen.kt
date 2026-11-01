package com.glasssign.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glasssign.app.data.*
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun HomeScreen(
    state:com.glasssign.app.UiState,onRefresh:()->Unit,onRunAll:()->Unit,onRunAccount:(String)->Unit,
    onVerify:(Account)->Unit,onAdd:()->Unit,onAddGame:(String)->Unit,onEdit:(Account)->Unit,
    onToggle:(Account,Boolean)->Unit,onDelete:(Account)->Unit,onSettings:()->Unit,onHistory:()->Unit,onDismissMessage:(Long)->Unit,
    onManage:(Account)->Unit,
) {
    var tab by rememberSaveable{mutableStateOf(0)}
    var appearance by rememberSaveable{mutableStateOf(false)}
    val accounts=state.overview?.accounts.orEmpty()
    val records=state.overview?.records.orEmpty()
    val grouped=remember(records){records.groupBy{it.accountId}}
    val gameAccounts=remember(accounts){accounts.flatMap{a->a.boundGameIds().map{it to a.id}}.groupBy({it.first},{it.second})}
    val recordsByGame=remember(records,accounts){
        val providers=accounts.associate{it.id to it.provider}
        records.mapNotNull{record->providers[record.accountId]?.let{provider->gameForTask(provider,record.targetId)}?.let{it to record}}.groupBy({it.first},{it.second})
    }
    val summary=remember(records){records.groupingBy{it.status}.eachCount()}
    val scrollStates=listOf(rememberLazyListState(),rememberLazyListState(),rememberLazyListState())
    val titles=listOf("首页","绑定","记录")
    val icons=listOf(Icons.Rounded.Home,Icons.Rounded.Link,Icons.Rounded.History)
    val scheme=MaterialTheme.colorScheme
    val settings=LocalAppearance.current
    val navColor=if(settings.navigationTint)lerp(scheme.surface,scheme.primary,.075f) else scheme.surface
    Scaffold(containerColor=scheme.background,snackbarHost={
        state.message?.let{message->MessageBar(message,onDismissMessage)}
    },bottomBar={
        NavigationBar(containerColor=navColor,tonalElevation=0.dp) {
            titles.forEachIndexed{index,title->
                NavigationBarItem(selected=tab==index,onClick={tab=index},icon={Icon(icons[index],title)},label={Text(title)})
            }
        }
    }) {insets->
        key(tab) {
            LazyColumn(Modifier.fillMaxSize().padding(insets),state=scrollStates[tab],
                contentPadding=PaddingValues(horizontal=18.dp,vertical=16.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                item("header") {
                    PageHeader(if(tab==0)"星栞" else if(tab==1)"游戏绑定" else "签到记录",
                        if(tab==0)"本机执行 · ${state.overview?.date.orEmpty()}" else if(tab==1)"一个账号可勾选多个游戏" else "官方结果与本地记录",
                        onSettings,{appearance=true})
                }
                when(tab) {
                    0 -> {
                        item("overview"){DashboardSummary(state,accounts.size,summary)}
                        item("run"){
                            PrimaryGlassButton(
                                text=if(state.waitingNetwork)"已排队，等待执行" else "一键签到",
                                caption=if(state.waitingNetwork)"等待联网、重试退避或系统调度" else "检查所有已启用游戏，今日成功项自动跳过",
                                loading=state.jobRunning||state.submitting,enabled=!state.jobRunning&&!state.submitting&&!state.waitingNetwork,onClick=onRunAll)
                        }
                        item("plan"){
                            GlassCard(contentPadding=16.dp){
                                Row(verticalAlignment=Alignment.CenterVertically){
                                    Icon(Icons.Rounded.Schedule,null,tint=Cyan,modifier=Modifier.size(21.dp));Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)){
                                        Text("每日计划",color=White,fontWeight=FontWeight.SemiBold)
                                        Text(if(state.scheduleEnabled)"每天 ${state.dailyTime} · 北京时间" else "手动签到，计划未开启",color=Muted,fontSize=12.sp)
                                    }
                                    TextButton(onClick=onSettings){Text(if(state.scheduleEnabled)"管理" else "设置")}
                                }
                                state.overview?.lastFinishedAt?.let{last->Spacer(Modifier.height(12.dp));DividerLine();Spacer(Modifier.height(12.dp));KeyValueRow(Icons.Rounded.Update,"上次执行",formatLastRun(last))}
                            }
                        }
                        item("shortcuts"){
                            Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){
                                ShortcutCard("管理绑定","${accounts.size} 条账号绑定",Icons.Rounded.Link,Modifier.weight(1f)){tab=1}
                                ShortcutCard("执行记录","今天 ${records.size} 项任务",Icons.Rounded.History,Modifier.weight(1f)){tab=2}
                            }
                        }
                        if(accounts.isEmpty())item("empty"){
                            Text("先到绑定页选择游戏，登录并确认角色后即可签到。",color=Muted,fontSize=13.sp,lineHeight=20.sp)
                        }
                        item("refresh"){TextButton(onClick=onRefresh,modifier=Modifier.fillMaxWidth(),enabled=!state.loading){Icon(Icons.Rounded.Refresh,null,modifier=Modifier.size(17.dp));Spacer(Modifier.width(6.dp));Text(if(state.loading)"正在刷新…" else "刷新本地状态")}}
                    }
                    1 -> {
                        item("games-title"){SectionHeader("选择游戏","登录一次，勾选同账号下多个游戏"){TextButton(onClick=onAdd){Icon(Icons.Rounded.Add,null,modifier=Modifier.size(17.dp));Text("添加")}}}
                        items(GameCatalog.all.chunked(2),key={row->"games-"+row.first().id}) {row->
                            Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                                row.forEach {game->
                                    GameTile(game,gameAccounts[game.id].orEmpty().toSet(),recordsByGame[game.id].orEmpty(),state.jobRunning||state.submitting,Modifier.weight(1f)){onAddGame(game.id)}
                                }
                            }
                        }
                        if(accounts.isNotEmpty())item("bound-title"){SectionHeader("已绑定账号","${accounts.size} 条绑定 · 暂停后仍可手动检查")}
                        if(accounts.isEmpty())item("empty"){EmptyNotice("尚未绑定游戏","登录后会显示真实角色，确认后才启用签到。",Icons.Rounded.Link)}
                        items(accounts,key={it.id}) {account->
                            AccountCard(account,grouped[account.id].orEmpty(),state.jobRunning||state.submitting||state.verifyingId==account.id,
                                {onRunAccount(account.id)},{onVerify(account)},{onEdit(account)},{onManage(account)},{onToggle(account,it)},{onDelete(account)})
                        }
                    }
                    2 -> {
                        item("record-actions"){
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
                                TextButton(onClick=onHistory){Icon(Icons.Rounded.CalendarMonth,null,modifier=Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text("历史记录")}
                                TextButton(onClick=onRefresh){Icon(Icons.Rounded.Refresh,null,modifier=Modifier.size(18.dp));Text("刷新")}
                            }
                        }
                        if(records.isEmpty())item("empty"){EmptyNotice("今天还没有签到记录","检查完成后，各游戏结果会独立显示在这里。",Icons.Rounded.History)}
                        items(records,key={it.accountId+"|"+it.targetId}) {record->
                            GlassCard(contentPadding=16.dp){
                                Row(verticalAlignment=Alignment.CenterVertically){Text(record.targetName,color=White,fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f));Spacer(Modifier.width(8.dp));StatusChip(record.status)}
                                if(record.message.isNotBlank()){Spacer(Modifier.height(8.dp));Text(record.message,color=Muted,fontSize=12.sp,lineHeight=18.sp)}
                                Spacer(Modifier.height(8.dp));Text("${formatLastRun(record.updatedAt)} · 第 ${record.attempts} 次",color=Faint,fontSize=11.sp)
                            }
                        }
                    }
                }
                item("footer"){Text("Hoshiori ${com.glasssign.app.BuildConfig.VERSION_NAME} · 授权与记录保存在本机",color=Faint,fontSize=10.sp,modifier=Modifier.padding(vertical=4.dp))}
            }
        }
    }
    if(appearance)AppearanceDialog{appearance=false}
}

@Composable
private fun PageHeader(title:String,subtitle:String,onSettings:()->Unit,onAppearance:()->Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom=4.dp),verticalAlignment=Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title,color=White,fontSize=25.sp,fontWeight=FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp));Text(subtitle,color=Muted,fontSize=12.sp)
        }
        IconButton(onClick=onAppearance){Icon(Icons.Rounded.Palette,"外观主题",tint=Muted)}
        IconButton(onClick=onSettings){Icon(Icons.Rounded.Settings,"本地计划设置",tint=Muted)}
    }
}

@Composable
private fun DashboardSummary(state:com.glasssign.app.UiState,accounts:Int,summary:Map<String,Int>) {
    val ok=(summary["success"] ?: 0)+(summary["already"] ?: 0)
    val action=summary["needs_action"] ?: 0
    val failed=summary["failed"] ?: 0
    val title=when{state.jobRunning||state.submitting->"正在检查签到";state.waitingNetwork->"等待系统执行";accounts==0->"准备开始";ok>0->"已完成 $ok 项签到";else->"今天，准备就绪"}
    GlassCard(contentPadding=18.dp) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            Surface(shape=RoundedCornerShape(14.dp),color=MaterialTheme.colorScheme.primaryContainer){
                Icon(if(ok>0)Icons.Rounded.CheckCircle else Icons.Rounded.Bolt,null,tint=MaterialTheme.colorScheme.onPrimaryContainer,modifier=Modifier.padding(12.dp).size(24.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title,color=White,fontSize=19.sp,fontWeight=FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp));Text(if(accounts==0)"绑定游戏，开启你的每日签到" else "$accounts 条绑定 · 各游戏结果独立保存",color=Muted,fontSize=12.sp)
            }
        }
        Spacer(Modifier.height(18.dp));DividerLine();Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            SummaryNumber("成功 / 已签",ok,Mint)
            SummaryNumber("需处理",action,Amber)
            SummaryNumber("失败",failed,Rose)
        }
    }
}
@Composable
private fun SummaryNumber(label:String,value:Int,color:Color) {
    Column{Text(value.toString(),color=color,fontSize=29.sp,fontWeight=FontWeight.SemiBold);Text(label,color=Muted,fontSize=11.sp)}
}
@Composable
private fun ShortcutCard(title:String,subtitle:String,icon:androidx.compose.ui.graphics.vector.ImageVector,modifier:Modifier,onClick:()->Unit) {
    GlassCard(modifier.clickable(onClick=onClick),contentPadding=15.dp) {
        Icon(icon,null,tint=Cyan,modifier=Modifier.size(23.dp));Spacer(Modifier.height(13.dp));Text(title,color=White,fontWeight=FontWeight.Medium,fontSize=14.sp);Spacer(Modifier.height(4.dp));Text(subtitle,color=Muted,fontSize=11.sp)
    }
}
@Composable
private fun GameTile(game:GameChoice,accounts:Set<String>,records:List<CheckRecord>,busy:Boolean,modifier:Modifier,onClick:()->Unit) {
    val meta=providerMeta(game.provider)
    val count=accounts.size
    val completed=accounts.count{id->records.filter{it.accountId==id}.let{it.isNotEmpty()&&it.all{r->r.status in setOf("success","already")}}}
    val label=when {
        count==0->"点击绑定"
        busy->"正在检查…"
        completed==count->"已签到"
        records.any{it.status in setOf("needs_action","failed")}->"需处理"
        records.any{it.status=="waiting_network"}->"等待网络"
        completed>0->"$completed/$count 个账号已签到"
        else->"已绑定 $count 个账号 · 待签到"
    }
    val labelColor=when {count==0->Muted;completed==count->Mint;records.any{it.status in setOf("needs_action","failed")}->Amber;else->Cyan}
    GlassCard(modifier.clickable(onClick=onClick),contentPadding=14.dp) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            ProviderBadge(meta,32.dp);Spacer(Modifier.weight(1f));Icon(Icons.Rounded.Add,null,tint=Cyan,modifier=Modifier.size(18.dp))
        }
        Spacer(Modifier.height(12.dp));Text(game.title,color=White,fontWeight=FontWeight.Medium,fontSize=13.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp));Text(label,color=labelColor,fontSize=11.sp,maxLines=2)
    }
}
@Composable
private fun EmptyNotice(title:String,description:String,icon:androidx.compose.ui.graphics.vector.ImageVector) {
    GlassCard(contentPadding=22.dp) {
        Icon(icon,null,tint=Muted,modifier=Modifier.size(30.dp));Spacer(Modifier.height(14.dp));Text(title,color=White,fontSize=16.sp,fontWeight=FontWeight.Medium);Spacer(Modifier.height(6.dp));Text(description,color=Muted,fontSize=12.sp,lineHeight=19.sp)
    }
}
private fun authLabel(state:String)=when(state){"valid"->"授权有效";"pending"->"待确认";"needs_action"->"需处理";"failed"->"授权异常";else->"待验证"}
@Composable private fun authColor(state:String)=when(state){"valid"->Mint;"pending"->Cyan;"needs_action"->Amber;"failed"->Rose;else->Muted}

@Composable
private fun AccountCard(account:Account,records:List<CheckRecord>,busy:Boolean,onCheck:()->Unit,onVerify:()->Unit,onEdit:()->Unit,onManage:()->Unit,onToggle:(Boolean)->Unit,onDelete:()->Unit) {
    var menu by remember{mutableStateOf(false)}
    val pending=account.bindingPending||account.authState=="pending"
    val games=account.boundGameIds().mapNotNull{GameCatalog.find(it)?.title}
    val title=games.joinToString(" · ").ifBlank{"账号绑定"}
    GlassCard(contentPadding=16.dp) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            ProviderBadge(providerMeta(account.provider));Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(title,color=White,fontWeight=FontWeight.SemiBold,fontSize=15.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
                Spacer(Modifier.height(3.dp));Text(account.nickname,color=Muted,fontSize=12.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
            }
            Switch(checked=account.enabled,onCheckedChange=onToggle,enabled=!busy&&!pending)
            Box {
                IconButton(onClick={menu=true},enabled=!busy){Icon(Icons.Rounded.MoreVert,"账号操作",tint=Muted)}
                DropdownMenu(expanded=menu,onDismissRequest={menu=false}) {
                    DropdownMenuItem(text={Text("管理游戏")},onClick={menu=false;onManage()})
                    DropdownMenuItem(text={Text("重新登录")},onClick={menu=false;onEdit()})
                    DropdownMenuItem(text={Text("删除绑定",color=Rose)},onClick={menu=false;onDelete()})
                }
            }
        }
        Spacer(Modifier.height(10.dp));GlassChip(authLabel(account.authState),authColor(account.authState))
        if(account.authMessage.isNotBlank()){Spacer(Modifier.height(8.dp));Text(account.authMessage,color=Muted,fontSize=12.sp,lineHeight=18.sp,maxLines=3,overflow=TextOverflow.Ellipsis)}
        if(records.isNotEmpty()){
            Spacer(Modifier.height(12.dp));DividerLine();Spacer(Modifier.height(10.dp))
            records.take(2).forEach{record->Row(Modifier.padding(vertical=4.dp),verticalAlignment=Alignment.CenterVertically){Text(record.targetName,color=White,fontSize=12.sp,modifier=Modifier.weight(1f),maxLines=1,overflow=TextOverflow.Ellipsis);Spacer(Modifier.width(8.dp));Text(statusLabel(record.status),color=statusColor(record.status),fontSize=11.sp)}}
            if(records.size>2)Text("另有 ${records.size-2} 项，前往记录页查看",color=Muted,fontSize=11.sp)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            TextButton(onClick=onCheck,enabled=!busy&&!pending){Text(if(records.isNotEmpty()&&records.all{it.status in setOf("success","already")})"已签到 · 再检查" else "立即检查")}
            TextButton(onClick=onVerify,enabled=!busy){Text(if(pending)"继续确认" else "验证授权")}
            if(busy){Spacer(Modifier.weight(1f));CircularProgressIndicator(Modifier.padding(8.dp).size(18.dp),strokeWidth=2.dp)}
        }
    }
}
@Composable
private fun MessageBar(message:Message,onDismiss:(Long)->Unit) {
    Snackbar(modifier=Modifier.padding(14.dp),containerColor=MaterialTheme.colorScheme.surfaceContainerHigh,contentColor=White,action={TextButton(onClick={onDismiss(message.at)}){Text("知道了")}}) {
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            Icon(if(message.error)Icons.Rounded.ErrorOutline else Icons.Rounded.CheckCircle,null,tint=if(message.error)Rose else Mint,modifier=Modifier.size(19.dp))
            Text(message.text,fontSize=12.sp,lineHeight=18.sp)
        }
    }
}
private fun formatLastRun(value:String):String=runCatching {
    val zone=ZoneId.of("Asia/Shanghai");val date=OffsetDateTime.parse(value).atZoneSameInstant(zone)
    if(date.toLocalDate()==java.time.LocalDate.now(zone))"今天 "+date.format(DateTimeFormatter.ofPattern("HH:mm")) else date.format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
}.getOrElse{value.take(16)}

private fun Account.boundGameIds():List<String> = gameIds.ifEmpty {gameId.takeIf{it.isNotBlank()}?.let{listOf(it)}.orEmpty()}.distinct()

/** Community reward tasks are intentionally excluded from a game's daily sign status. */
private fun gameForTask(provider:String,target:String):String? {
    val parts=target.split('|')
    if(provider in setOf("miyoushe","skland"))return parts.firstOrNull()?.takeIf{id->GameCatalog.find(id)?.provider==provider}
    if(parts.size<3||parts[0]!="game")return null
    return GameCatalog.all.firstOrNull{it.provider==provider&&it.protocolId==parts[1]}?.id
}
