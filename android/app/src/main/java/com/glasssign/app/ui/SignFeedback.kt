package com.glasssign.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.glasssign.app.AuthFix
import com.glasssign.app.UiState
import com.glasssign.app.data.*

@Composable
private fun QCard(modifier:Modifier,content:@Composable ColumnScope.()->Unit) {
    Box(modifier) { QCard(content=content) }
}

/*
 * The home page's explicit feedback layer (v0.7.4 device feedback: "官方App显示签到成功，但星栞首页报错，
 * 反馈不明显").
 *
 * Design constraints taken from the task and from the existing code:
 *  - Scannable, not one long column: ONE status card that may own the live progress bar, plus at most three compact
 *    rows (游戏 / 社区任务 / 授权), each collapsible. Every row is a fixed height, no per-frame computation: all
 *    derivations happen once per state change in `rememberPageUiState` / data/HomeFeedback.kt.
 *  - Colour + icon + short text on every row, so the four states ("执行中 / 已完成 / 部分需处理 / 结果待确认") are
 *    distinguishable without reading a paragraph, and without relying on colour alone.
 *  - 游戏签到, 社区任务 and 授权与角色检查 are three separate lanes. A failed community task is always visible, but
 *    it can never turn a completed game row red (data/HomeFeedback.kt keeps the lanes apart; this file only paints).
 *  - The fix entries are the ones that already exist in the ViewModel: 只读核对 (`reconcile`), 验证授权
 *    (`verifyAccount`, read-only), 查看记录 (records page) and 管理游戏与角色. No new network path is invented.
 *  - testTags: the existing home-cta / page-N / nav-N tags are untouched; the additions are
 *    "home-feedback", "home-run-result", "home-fix", "home-tone-<id>", "feedback-fix-<action>".
 */

/** One state word + icon + colour for the whole feedback layer. The group vocabulary is RecordPresentation's. */
internal data class Tone(val label:String,val icon:ImageVector,val color:Color)

@Composable
internal fun toneFor(group:String):Tone {
    val c=MaterialTheme.colorScheme
    return when(group) {
        RowState.COMPLETED, "success" -> Tone("已完成",Icons.Rounded.CheckCircle,signStateColor("success"))
        RowState.SUBMITTED, "submitted" -> Tone("已提交等待确认",Icons.Rounded.Outbox,signStateColor("accepted"))
        RowState.UNCERTAIN, "uncertain" -> Tone("结果未知",Icons.Rounded.HelpOutline,signStateColor("unknown"))
        RowState.RUNNING, "running" -> Tone("执行中",Icons.Rounded.Sync,c.secondary)
        RowState.WAITING -> Tone("等待网络",Icons.Rounded.CloudOff,Amber)
        RowState.ATTENTION -> Tone("需要处理",Icons.Rounded.ErrorOutline,c.error)
        RowState.PENDING -> Tone("待签到",Icons.Rounded.RadioButtonUnchecked,Muted)
        else -> Tone("待签到",Icons.Rounded.RadioButtonUnchecked,Muted)
    }
}

private fun phaseGroup(phase:String):String = when(phase) {
    Phase.RUNNING -> RowState.RUNNING
    Phase.DONE -> RowState.COMPLETED
    Phase.PARTIAL, Phase.QUEUED -> RowState.UNCERTAIN
    Phase.PROBLEM, Phase.PAUSED -> RowState.ATTENTION
    else -> RowState.PENDING
}

/**
 * The status card. Replaces the old single headline + generic 失败 text.
 *
 * The primary button keeps the baseline behaviour (立即签到 / 查看今日记录 / 处理未完成项 / 管理账号) including the
 * `home-cta` tag and the enabled conditions, so existing expectations still hold; it only gains a specific hint
 * line that says what is actually blocked and why.
 */
internal fun LazyListScope.HomeFeedbackCards(ui:PageUiState,s:UiState,vm:VmActions,onTab:(Int)->Unit) {
    val f=ui.feedback
    item(key="feedback",contentType="feedback") {
        QCard(Modifier.testTag("home-feedback")) {
            if(f==null) {
                Text("正在读取今日状态…",style=MaterialTheme.typography.titleMedium)
            } else {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    StatusDot(phase=f.phase,group=phaseGroup(f.phase))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if(f.gameRows.isNotEmpty())f.headline else f.title,style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis)
                        if(f.gameRows.isNotEmpty())Text(f.title,style=MaterialTheme.typography.titleMedium,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
                    }
                }
                if(f.breakdown.isNotBlank())Text(f.breakdown,style=MaterialTheme.typography.bodyMedium,color=Muted,maxLines=3,overflow=TextOverflow.Ellipsis)
                // Lanes are a count + one sentence, never a wall of rows: details live in the three lane cards.
                LaneSummaryRow(f.gameRows.worstGroup(),"游戏签到",f.gameRows.summaryText(),Icons.Rounded.SportsEsports){onTab(2)}
                if(f.communityRows.isNotEmpty())LaneSummaryRow(f.communityRows.worstGroup(),"社区任务",f.communityRows.summaryText(),Icons.Rounded.CardGiftcard){onTab(2)}
                if(f.authRows.isNotEmpty())LaneSummaryRow(f.authRows.worstGroup(),"授权与角色",f.authRows.summaryText(),Icons.Rounded.Key){onTab(1)}
                if(f.primaryHint.isNotBlank())Text(f.primaryHint,style=MaterialTheme.typography.bodySmall,color=Muted,maxLines=3)
                if(f.running)LinearProgressIndicator(Modifier.fillMaxWidth().padding(top=2.dp),color=MaterialTheme.colorScheme.secondary)
                Button(onClick={
                    if(ui.accounts.none{it.enabled})onTab(1)
                    else if(f.done||f.attention)onTab(2)
                    else vm.runNow()
                },enabled=!s.jobRunning&&!s.submitting&&!s.waitingNetwork,modifier=Modifier.fillMaxWidth().heightIn(min=54.dp).testTag("home-cta")) {
                    Text(when {
                        s.jobRunning||s.submitting -> "正在签到…"
                        ui.accounts.none{it.enabled} -> "管理账号"
                        f.done -> "查看今日记录"
                        f.attention -> "处理未完成项"
                        else -> "立即签到"
                    })
                }
                if(f.unsynchronizedAccounts.isNotEmpty())Text("${f.unsynchronizedAccounts.size} 个账号尚未同步角色，未计入今日签到",style=MaterialTheme.typography.bodySmall,color=Muted,maxLines=2)
            }
        }
    }
    // ---- last run's readable result (never a transient Snackbar) -------------------------------------------
    f?.result?.let{result->
        item(key="run-result",contentType="run-result") {
            Surface(Modifier.fillMaxWidth().testTag("home-run-result"),shape=RoundedCornerShape(24.dp),
                color=MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.55f),border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant.copy(alpha=.6f))) {
                Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Icon(if(result.error)Icons.Rounded.ErrorOutline else Icons.Rounded.Checklist,null,tint=if(result.error)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,modifier=Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(result.title,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f),maxLines=2,overflow=TextOverflow.Ellipsis)
                    }
                    result.summary.forEach{line->Text("• $line",style=MaterialTheme.typography.bodySmall,color=if(result.error)MaterialTheme.colorScheme.error else Muted)}
                    Text("${if(result.trigger=="scheduled")"计划执行" else "手动执行"} · ${qTime(result.finishedAt)}",style=MaterialTheme.typography.labelSmall,color=Muted,maxLines=1,overflow=TextOverflow.Ellipsis)
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick={onTab(2)},modifier=Modifier.heightIn(min=48.dp).testTag("feedback-fix-records")){Text("查看记录")}
                        if((f.counts["authProblem"]?:0)>0)TextButton(onClick={onTab(1)},modifier=Modifier.heightIn(min=48.dp).testTag("feedback-run-auth")){Text("验证授权")}
                        if(f.retryOnly)TextButton(onClick={vm.runNow()},enabled=!s.jobRunning&&!s.submitting&&!s.waitingNetwork,modifier=Modifier.heightIn(min=48.dp).testTag("feedback-fix-retry")){Text("安全重试")}
                    }
                }
            }
        }
    }
    // ---- explicit fix entry for the last failed operation -------------------------------------------------
    ui.fix?.let{fix->
        item(key="widget-fix",contentType="fix") {
            Surface(Modifier.fillMaxWidth().testTag("home-fix"),shape=RoundedCornerShape(24.dp),color=MaterialTheme.colorScheme.errorContainer.copy(alpha=.45f),
                border=BorderStroke(1.dp,MaterialTheme.colorScheme.error.copy(alpha=.35f))) {
                Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Icon(Icons.Rounded.ErrorOutline,null,tint=MaterialTheme.colorScheme.error,modifier=Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("这一步没有完成",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f))
                        TextButton(onClick={vm.dismissFix()},modifier=Modifier.heightIn(min=48.dp)){Text("知道了")}
                    }
                    if(fix.nickname!=null||fix.provider.isNotBlank())Text(fixTitle(fix),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error,maxLines=2,overflow=TextOverflow.Ellipsis)
                    Text(fix.cause.ifBlank{"未返回具体原因"},style=MaterialTheme.typography.bodySmall,color=Muted,maxLines=4,overflow=TextOverflow.Ellipsis)
                    Text("失败只影响这一步；已完成的游戏签到保持原样。",style=MaterialTheme.typography.labelSmall,color=Muted)
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        if(fix.accountId.isNotBlank()) {
                            TextButton(onClick={vm.verifyAccount(fix.accountId)},enabled=!s.loginBusy&&!s.jobRunning&&!s.submitting,modifier=Modifier.heightIn(min=48.dp).testTag("feedback-fix-verify")){Text("验证授权（不签到）")}
                            TextButton(onClick={onTab(1)},modifier=Modifier.heightIn(min=48.dp).testTag("feedback-fix-account")){Text("账号详情")}
                        }
                        TextButton(onClick={onTab(2)},modifier=Modifier.heightIn(min=48.dp).testTag("feedback-fix-records")){Text("查看记录")}
                    }
                }
            }
        }
    }
}

private fun fixTitle(fix:AuthFix):String {
    val provider=if(fix.provider.isBlank())"" else providerMeta(fix.provider).title+" · "
    return provider+fix.nickname.orEmpty()
}

/** Compact lane line: icon + name + one-sentence state. Always rendered for games, conditionally for the others. */
@Composable
private fun LaneSummaryRow(group:String,name:String,summary:String,icon:ImageVector,onOpen:()->Unit) {
    val tone=toneFor(group)
    Row(Modifier.fillMaxWidth().heightIn(min=36.dp),verticalAlignment=Alignment.CenterVertically) {
        Icon(icon,null,tint=tone.color,modifier=Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(name,style=MaterialTheme.typography.labelLarge,modifier=Modifier.widthIn(min=64.dp))
        Text(summary,style=MaterialTheme.typography.bodySmall,color=Muted,modifier=Modifier.weight(1f),maxLines=2,overflow=TextOverflow.Ellipsis)
        TextButton(onClick=onOpen,modifier=Modifier.heightIn(min=36.dp)){Text("详情",style=MaterialTheme.typography.labelMedium)}
    }
}

@Composable
private fun StatusDot(phase:String,group:String) {
    val tone=toneFor(group)
    val color=tone.color
    Box(Modifier.size(34.dp).background(color.copy(alpha=.14f),CircleShape).border(1.dp,color.copy(alpha=.45f),CircleShape),contentAlignment=Alignment.Center) {
        Icon(when(phase) {
            Phase.RUNNING -> Icons.Rounded.Sync
            Phase.DONE -> Icons.Rounded.CheckCircle
            Phase.QUEUED -> Icons.Rounded.CloudOff
            Phase.PROBLEM, Phase.PAUSED -> Icons.Rounded.ErrorOutline
            Phase.PARTIAL -> Icons.Rounded.HelpOutline
            else -> Icons.Rounded.RadioButtonUnchecked
        },contentDescription=null,tint=color,modifier=Modifier.size(20.dp))
    }
}

/** Short counts per lane, e.g. "已完成 2 · 待确认 1"; the lane's colour is the worst state it contains. */
internal fun List<StatusRow>.summaryText():String {
    if(isEmpty())return "今日无此项"
    val done=count{it.state==RowState.COMPLETED}
    val submitted=count{it.state==RowState.SUBMITTED}
    val uncertain=count{it.state==RowState.UNCERTAIN}
    val attention=count{it.state==RowState.ATTENTION||it.state==RowState.WAITING}
    val running=count{it.state==RowState.RUNNING}
    val pending=count{it.state==RowState.PENDING}
    // Explicit ArrayList instead of kotlin.collections.buildList, so this stays readable by the project's Kotlin.
    val parts=ArrayList<String>(6)
    if(done>0)parts.add("已完成 $done")
    if(submitted>0)parts.add("待复核 $submitted")
    if(uncertain>0)parts.add("待确认 $uncertain")
    if(attention>0)parts.add("需处理 $attention")
    if(running>0)parts.add("执行中 $running")
    if(pending>0)parts.add("未执行 $pending")
    return if(parts.isEmpty())"暂无数据" else parts.joinToString(" · ")
}

/** The lane colour: error > uncertain > running > submitted > pending > done. */
internal fun List<StatusRow>.worstGroup():String {
    if(any{it.state in setOf(RowState.ATTENTION,RowState.WAITING)})return "attention"
    if(any{it.state==RowState.UNCERTAIN})return "uncertain"
    if(any{it.state==RowState.RUNNING})return "running"
    if(any{it.state==RowState.SUBMITTED})return "submitted"
    if(any{it.state==RowState.PENDING})return "pending"
    return if(isNotEmpty())"success" else "pending"
}

// ---------------------------------------------------------------------------------------------
// Lane detail cards (one LazyColumn item each: the rows are inside, never one item per row)
// ---------------------------------------------------------------------------------------------

/**
 * The three lane cards.
 *
 * Why one LazyColumn item per lane instead of one per row: with N roles the page would otherwise produce N + 3
 * items, the classic "page is a long column" regression. Each lane emits a bounded number of rows (all game rows,
 * which is the definition of the progress, and at most [LANE_ROW_CAP] rows for the two optional lanes) so a
 * partially-failed run stays scannable and never grows the page without bound.
 */
internal fun LazyListScope.FeedbackLaneCards(ui:PageUiState,vm:VmActions,onTab:(Int)->Unit) {
    val f=ui.feedback ?: return
    if(f.gameRows.isNotEmpty()) item(key="lane-games",contentType="lane-games") {
        LaneCard("游戏签到", f.gameRows, "按已启用绑定统计；已完成的游戏不会因其他项失败而改变", testTag="home-lane-games",cap=null,
            onFix={row->when(row.fix){
                FixAction.RECONCILE -> vm.reconcile(row.accountId,row.targetId)
                FixAction.RETRY -> vm.retryAccount(row.accountId)
                FixAction.VERIFY -> vm.verifyAccount(row.accountId)
                FixAction.ACCOUNT -> onTab(1)
                else -> onTab(2)
            }},
            onRow={onTab(2)})
    }
    if(f.authRows.isNotEmpty()) item(key="lane-auth",contentType="lane-auth") {
        LaneCard("授权与角色检查", f.authRows, "只读步骤：不提交签到，也不代表游戏签到失败", testTag="home-lane-auth",cap=LANE_ROW_CAP,
            onFix={row->when(row.fix){
                FixAction.VERIFY -> vm.verifyAccount(row.accountId)
                else -> onTab(1)
            }},
            onRow={onTab(1)})
    }
    item(key="lane-community",contentType="lane-community") {
        LaneCard("社区任务", f.communityRows, "社区奖励独立统计；失败不影响上方已完成的游戏", testTag="home-lane-community",cap=LANE_ROW_CAP,
            onFix={row->when(row.fix){
                FixAction.RECONCILE -> vm.reconcile(row.accountId,row.targetId)
                FixAction.RETRY -> vm.retryAccount(row.accountId)
                FixAction.VERIFY -> vm.verifyAccount(row.accountId)
                else -> onTab(2)
            }},
            onRow={onTab(2)})
    }
}

private const val LANE_ROW_CAP=4

@Composable
private fun LaneCard(title:String,rows:List<StatusRow>,note:String,testTag:String,cap:Int?,onFix:(StatusRow)->Unit,onRow:()->Unit) {
    val group=rows.worstGroup()
    val tone=toneFor(group)
    val shown=if(cap==null||rows.size<=cap)rows else rows.sortedBy{if(it.state==RowState.COMPLETED)1 else 0}.take(cap)
    QCard(Modifier.testTag(testTag)) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            Icon(Icons.Rounded.Checklist,null,tint=tone.color,modifier=Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(title,style=MaterialTheme.typography.titleMedium,modifier=Modifier.weight(1f),maxLines=1,overflow=TextOverflow.Ellipsis)
            Text(rows.summaryText(),style=MaterialTheme.typography.labelMedium,color=tone.color,maxLines=2,modifier=Modifier.widthIn(max=SUBLABEL_MAX).semantics{stateDescription=rows.worstGroup()})
        }
        if(rows.isEmpty()) {
            Text(if(title=="社区任务")"未启用社区任务或无社区记录" else "今日无此项",style=MaterialTheme.typography.bodySmall,color=Muted)
        } else {
            shown.forEach{row->
                FeedbackRow(row,onFix={onFix(row)},onOpen=onRow)
            }
            if(shown.size<rows.size) {
                TextButton(onClick=onRow,modifier=Modifier.heightIn(min=40.dp)){Text("查看其余 ${rows.size-shown.size} 项")}
            }
            Text(note,style=MaterialTheme.typography.labelSmall,color=Muted,maxLines=2)
        }
    }
}

/** One row: state icon + colour bar, the short state word, the game/role name, and a specific fix button. */
@Composable
private fun FeedbackRow(row:StatusRow,onFix:()->Unit,onOpen:()->Unit) {
    val tone=toneFor(row.state)
    Row(Modifier.fillMaxWidth().heightIn(min=44.dp),verticalAlignment=Alignment.CenterVertically) {
        Box(Modifier.size(28.dp).background(tone.color.copy(alpha=.14f),CircleShape),contentAlignment=Alignment.Center) {
            Icon(tone.icon,contentDescription=null,tint=tone.color,modifier=Modifier.size(16.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Text(safeLabel(row.title),style=MaterialTheme.typography.titleSmall,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.weight(1f,fill=false))
                Spacer(Modifier.width(6.dp))
                Text(tone.label,style=MaterialTheme.typography.labelSmall,color=tone.color,maxLines=1)
            }
            Text(row.detail,style=MaterialTheme.typography.bodySmall,color=Muted,maxLines=2,overflow=TextOverflow.Ellipsis,modifier=Modifier.testTag("feedback-row-${row.key}"))
        }
        if(row.fix!=null) {
            val text=when(row.fix) {
                FixAction.RECONCILE -> "核对"
                FixAction.VERIFY -> "验证"
                FixAction.RETRY -> "重试"
                FixAction.ACCOUNT -> "管理"
                else -> "记录"
            }
            TextButton(onClick=if(row.fix==FixAction.RECORDS)onOpen else onFix,modifier=Modifier.heightIn(min=40.dp).testTag("feedback-fix-${row.key}-${row.fix}")){Text(text,style=MaterialTheme.typography.labelMedium)}
        }
    }
}
