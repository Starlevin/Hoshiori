package com.glasssign.app.data

/*
 * Pure, Android-free mapping from today's stored state to what the home page says.
 *
 * WHY THIS FILE EXISTS (real-device feedback, v0.7.4, 2026-11-02): the second 森空岛 account was re-bound, the
 * official app showed a successful check-in, and the home page still read like one generic error. The problem was
 * not a missing status but a collapsed one: UiState only carried jobRunning/waitingNetwork, the home summary
 * counted present().group=="success" across ALL records, the same generic failure card was used for 游戏签到,
 * 社区任务 and 授权/角色检查, and every cause reached the user as one transient Snackbar.
 *
 * Contract of this file:
 *  - NO android.* import. The only org.json use is [readBook]/[serializeBook]; everything else is pure and
 *    unit-testable on the JVM.
 *  - It never writes state, never submits a request and never infers success from "the official app said so". An
 *    unknown submission stays [RowState.UNCERTAIN]; an accepted-but-unverified one stays [RowState.SUBMITTED].
 *  - 游戏签到 / 社区任务 / 授权与角色检查 are three separate lanes. A community-task failure can never mark a game
 *    failed, and an authorization/discovery failure is never called a game 签到失败 (see present()).
 */

/** Per-game/role row state, in the exact vocabulary the home page renders. */
object RowState {
    /** Running locally right now (a claimed `running` record while the worker is active). */
    const val RUNNING="running"
    /** Confirmed today: `success`/`already` with a provenance. Never an unverified submission. */
    const val COMPLETED="completed"
    /** Accepted by the interface but not independently confirmed; NOT success. */
    const val SUBMITTED="submitted"
    /** Unknown outcome; must never be shown as success. */
    const val UNCERTAIN="uncertain"
    /** Needs the user: authorization, captcha, or an actionable failure. */
    const val ATTENTION="attention"
    /** Waiting for network/system before anything could be sent. */
    const val WAITING="waiting"
    /** Enabled game/role that has not run today yet. */
    const val PENDING="pending"
}

/** The specific fix offered for a row. Every value maps to an action the user can take today. */
object FixAction {
    /** Read-only `LocalSignEngine.reconcile` (queries official state, never resubmits). */
    const val RECONCILE="reconcile"
    /** `LocalSignEngine.verify` / discovery: re-read authorization and roles, never submits a check-in. */
    const val VERIFY="verify"
    /** `LocalSignEngine.run` for that account: a real retry, offered only where a retry is provably safe. */
    const val RETRY="retry"
    /** Records page: inspect the real rows and their provenance. */
    const val RECORDS="records"
    /** Account detail page: manage games/roles, switches, re-authorization. */
    const val ACCOUNT="account"
}

/** Which lane a row belongs to. The three lanes are never merged into one counter. */
object Lane {
    /** 游戏签到: real game/role targets. */
    const val GAME="game"
    /** 社区任务 (社区奖励): 森空岛社区检票 / 塔吉多、库街区社区签到. */
    const val COMMUNITY="community"
    /** 授权与角色检查: the read-only discovery step plus the stored account auth state. */
    const val AUTH="auth"
}

/** One rendered row: a game/role task, a community task, or an authorization item. */
data class StatusRow(
    /** "accountId|targetId" for game/community rows, "auth-<accountId>" for authorization rows. */
    val key:String,
    val accountId:String,
    val targetId:String,
    val lane:String,
    /** Game id from the binding ("" for community/auth rows). */
    val gameId:String,
    /** Short, user-facing title: the binding name, never a raw target id. */
    val title:String,
    val provider:String,
    val state:String,
    /** Short state word (1-5 CJK glyphs). */
    val label:String,
    /** One sentence: what happened, the specific cause, and what it does NOT affect. */
    val detail:String,
    /** Provenance sentence ("官方成功响应", "本机当天完成记录", ...); "" when there is no record. */
    val source:String,
    val fix:String?,
    /** True for rows derived from an account's stored auth state (no per-target record exists). */
    val fromAccount:Boolean=false,
) {
    /** Stable identity for diffing; immune to in-place UiState copies of unchanged data. */
    val signature:String get()="$key|$lane|$state|$title|$label|$detail|$source|$fix"
}

/** The readable, scannable result of one finished run. */
data class RunResult(
    /** 1-based sequence of finished runs observed since install; 0 = none observed yet. */
    val sequence:Int,
    /** `scheduled` / `manual` from `SignScheduler`, "" when unknown. */
    val trigger:String,
    val finishedAt:String,
    val title:String,
    /** One readable line per distinct finding; never a generic "失败". */
    val summary:List<String>,
    val error:Boolean,
)

/** Phase of today, as a single value the UI can switch on. */
object Phase {
    const val RUNNING="running"
    const val QUEUED="queued"
    const val DONE="done"
    const val PARTIAL="partial"
    const val PROBLEM="problem"
    const val PAUSED="paused"
    const val IDLE="idle"
}

/**
 * Everything the home page derives from the real state: one pure value, computed once per state change.
 * The composable only formats it; nothing here is recomputed per frame.
 */
data class HomeFeedback(
    val day:String,
    /** Progress headline: "已签到 3 / 5 个游戏" (games only, enabled bindings only). */
    val headline:String,
    /** One-line phase sentence: "部分需要处理 · 已签到 3 / 5 个游戏 · 结果待确认 1". */
    val phaseLine:String,
    val phase:String,
    val runRunning:Boolean,
    val runQueued:Boolean,
    val counts:Map<String,Int>,
    val breakdown:String,
    val gameRows:List<StatusRow>,
    val communityRows:List<StatusRow>,
    val authRows:List<StatusRow>,
    /** Accounts the user enabled whose roles were never synchronized (never counted as pending tasks). */
    val unsynchronizedAccounts:List<String>,
    /** Short, specific next step; "" when the primary action is simply the run itself. */
    val primaryHint:String,
    /** The last finished run, when it produced something worth reading; null when clean/absent. */
    val result:RunResult?=null,
) {
    val running:Boolean get()=phase==Phase.RUNNING
    val done:Boolean get()=phase==Phase.DONE
    val attention:Boolean get()=(counts["hasAction"]?:0)==1
    val allPaused:Boolean get()=phase==Phase.PAUSED
    val taskCount:Int get()=counts["games"]?:0
    val completedCount:Int get()=counts["completed"]?:0
    val pendingCount:Int get()=counts["pending"]?:0
    /** True when the only remaining work is a safe retry of a definite failure. */
    val retryOnly:Boolean get()=(counts["retryable"]?:0)>0&&(counts["uncertain"]?:0)==0&&(counts["authProblem"]?:0)==0&&(counts["communityAttention"]?:0)==0
    val rows:List<StatusRow> get()=gameRows+communityRows+authRows
    val keys:Map<String,StatusRow> get()=rows.associateBy{it.key}
    val title:String get()=when(phase) {
        Phase.RUNNING->"正在核对与签到"
        Phase.QUEUED->"等待系统执行"
        Phase.DONE->"今日已全部完成"
        Phase.PARTIAL->"部分完成，仍在处理"
        Phase.PROBLEM->"结果需确认或修复"
        Phase.PAUSED->"签到已暂停"
        else->"今日未签到"
    }
    /** Fingerprint of everything the user reads; used only for debug/assertions. */
    val fingerprint:String get()=listOf(day,phase,runRunning,runQueued,headline,breakdown,primaryHint,
        unsynchronizedAccounts.joinToString(","),result?.let{it.sequence.toString()+it.finishedAt+it.title+it.summary.joinToString("|")}.orEmpty())
        .plus(rows.map{it.signature}).joinToString("#")
}

/** Account view for the authorization lane (only the fields this file needs; no android dependency). */
data class AuthAccount(
    val id:String,
    val provider:String,
    val nickname:String,
    val enabled:Boolean,
    val authState:String,
    val authMessage:String,
    val bindingPending:Boolean,
    val roleScope:Boolean,
    val gameIds:List<String>,
)

/**
 * Builds the home feedback from real stored state.
 *
 * @param accounts every account row (pending drafts are ignored by every lane).
 * @param tasks enabled bindings of enabled accounts: the ONLY rows that may count as "今日应签到".
 * @param records today's records (`SignRepository.records(SignRepository.day())`), all accounts.
 * @param running the worker is running right now. This is the only liveness signal used for 执行中.
 * @param queued the work is enqueued/blocked waiting for the network.
 * @param lastStartedAt / lastFinishedAt are informational only; they never decide whether a game is completed.
 */
fun summarize(
    day:String,
    accounts:List<AuthAccount>,
    tasks:List<GameBinding>,
    records:List<CheckRecord>,
    accountNames:Map<String,String>,
    running:Boolean,
    queued:Boolean,
    lastStartedAt:String?=null,
    lastFinishedAt:String?=null,
):HomeFeedback {
    val active=accounts.filter{!it.bindingPending&&it.enabled}
    val recordByKey=records.associateBy{targetKey(it.accountId,it.targetId)}
    val runActive=running

    // ---- game lane: one row per ENABLED binding, never per historical record -----------------------------
    // A community-tagged binding is NOT a game: it belongs to the community lane only. Without this guard a stray
    // `community|…` binding would be counted in "已签到 X/Y 个游戏" and its failure would read as a game failure --
    // the exact lane confusion this feedback pass exists to remove.
    val gameRows=tasks.filter{!isCommunityTarget(it.targetId)}.distinctBy{it.accountId to it.targetId}.map{task->
        rowOf(
            accountId=task.accountId, targetId=task.targetId, lane=Lane.GAME,
            name=task.name.ifBlank{GameCatalog.find(task.gameId)?.title.orEmpty()},
            gameId=task.gameId, record=recordByKey[targetKey(task.accountId,task.targetId)],
            accountName=accountNames[task.accountId].orEmpty(), runActive=runActive,
        )
    }
    // ---- community lane: community records today + enabled community bindings ----------------------------
    val communitySeen=LinkedHashSet<String>()
    val communityRows=ArrayList<StatusRow>()
    run {
        for(r in records.filter{isCommunityTarget(it.targetId)}) {
            if(!communitySeen.add(targetKey(r.accountId,r.targetId)))continue
            // Only an active account's community task is today's status; a deleted/disabled account's history
            // stays on the records page and never pollutes the home page.
            val account=accounts.firstOrNull{it.id==r.accountId} ?: continue
            if(account.bindingPending||!account.enabled)continue
            communityRows.add(rowOf(r.accountId,r.targetId,Lane.COMMUNITY,r.targetName,gameId="",record=r,
                accountName=accountNames[r.accountId].orEmpty(),runActive=runActive))
        }
        for(task in tasks.distinctBy{it.accountId to it.targetId}) {
            if(!isCommunityTarget(task.targetId))continue
            if(!communitySeen.add(targetKey(task.accountId,task.targetId)))continue
            communityRows.add(rowOf(task.accountId,task.targetId,Lane.COMMUNITY,task.name,gameId="",record=null,
                accountName=accountNames[task.accountId].orEmpty(),runActive=runActive))
        }
    }
    // ---- authorization lane: the account's CURRENT auth state, reinforced (never overridden) by discovery --
    val authRows=ArrayList<StatusRow>()
    run {
        for(account in active) {
            val state=account.authState
            // The account row is authoritative: a stale "授权失效" record from earlier today must not keep an
            // account red after the user re-authorized (the exact v0.7.4 case: 第二森空岛 re-bound successfully
            // but the page stayed broken), and a healthy account that simply has not run yet shows no alarm.
            if(state!="pending"&&state!="needs_action"&&state!="failed"&&state!="unknown")continue
            val discovery=records.firstOrNull{it.accountId==account.id&&it.targetId==AUTHORIZATION_CHECK_TARGET&&it.status!="running"}
            val message=listOf(account.authMessage,discovery?.message.orEmpty()).firstOrNull{it.isNotBlank()}.orEmpty()
            val reauth=reauthRequired(message)
            val label=when {
                reauth -> "需要重新授权"
                account.roleScope -> "授权待验证"
                else -> "角色范围待确认"
            }
            val fix=when {
                reauth -> FixAction.ACCOUNT
                !account.roleScope&&discovery==null -> FixAction.ACCOUNT
                else -> FixAction.VERIFY
            }
            val detail=when {
                reauth -> "本账号授权已失效：${trimCause(message)}；该步骤不提交签到，其他账号与游戏的签到结果不受影响"
                !account.roleScope -> "尚未确认角色范围，不能据此判断今日签到；先管理游戏与角色"
                state=="unknown" -> "授权尚未验证：${trimCause(message)}；验证只读，不会补签"
                else -> "授权或角色读取需要确认：${trimCause(message)}；验证只读，不会补签"
            }
            authRows.add(StatusRow(
                key="auth-${account.id}", accountId=account.id, targetId=AUTHORIZATION_CHECK_TARGET, lane=Lane.AUTH,
                gameId="", title="${providerTitle(account.provider)} · ${accountName(account)}", provider=account.provider,
                state=RowState.ATTENTION, label=label, detail=detail,
                source=discovery?.let{present(it).source}.orEmpty().ifBlank{"账号授权状态：${stateText(state)}"},
                fix=fix, fromAccount=true,
            ))
        }
    }
    // ---- enabled accounts whose roles were never synchronized (informational, never a pending task) ------
    val synchronizedAccounts=tasks.map{it.accountId}.toSet()
    val unsynchronized=active.filter{account->
        account.roleScope&&account.id !in synchronizedAccounts&&records.none{it.accountId==account.id}
    }.map{accountName(it)}

    // ---- counts: the three lanes stay separate ----------------------------------------------------------
    val completed=gameRows.count{it.state==RowState.COMPLETED}
    val submitted=gameRows.count{it.state==RowState.SUBMITTED}
    val uncertain=gameRows.count{it.state==RowState.UNCERTAIN}
    val gameAttention=gameRows.count{it.state==RowState.ATTENTION||it.state==RowState.WAITING}
    val communityAttention=communityRows.count{it.state in setOf(RowState.ATTENTION,RowState.UNCERTAIN,RowState.WAITING)}
    val authProblem=authRows.size
    val retryable=gameRows.count{it.fix==FixAction.RETRY}+communityRows.count{it.fix==FixAction.RETRY}
    val pending=gameRows.count{it.state==RowState.PENDING}
    val finished=completed+submitted+uncertain
    val counts=linkedMapOf(
        "games" to gameRows.size, "completed" to completed, "submitted" to submitted, "uncertain" to uncertain,
        "pending" to pending, "attention" to gameAttention, "community" to communityRows.size,
        "communityAttention" to communityAttention, "authProblem" to authProblem, "retryable" to retryable,
        "unsynchronized" to unsynchronized.size, "finished" to finished,
        "running" to gameRows.count{it.state==RowState.RUNNING},
        "hasAction" to if(gameAttention+uncertain+authProblem+communityAttention>0)1 else 0,
    )

    val phase=when {
        runActive||gameRows.any{it.state==RowState.RUNNING} -> Phase.RUNNING
        queued -> Phase.QUEUED
        accounts.isNotEmpty()&&active.isEmpty() -> Phase.PAUSED
        gameRows.isEmpty()&&authRows.isEmpty()&&communityRows.isEmpty() -> Phase.IDLE
        gameRows.isNotEmpty()&&completed==gameRows.size&&authProblem==0&&communityRows.all{it.state==RowState.COMPLETED} -> Phase.DONE
        finished>0&&(counts["hasAction"]?:0)==1 -> Phase.PROBLEM
        finished>0 -> Phase.PARTIAL
        else -> Phase.IDLE
    }
    val headline=when {
        accounts.isNotEmpty()&&active.isEmpty() -> "签到已暂停"
        gameRows.isEmpty() -> "角色待同步"
        else -> "已签到 $completed / ${gameRows.size} 个游戏"
    }
    val breakdown=buildBreakdown(completed,submitted,uncertain,gameAttention,pending,
        communityTotal=communityRows.size,
        communityDone=communityRows.count{it.state==RowState.COMPLETED},
        communityAccepted=communityRows.count{it.state==RowState.SUBMITTED},
        communityOpen=communityRows.count{it.state in setOf(RowState.ATTENTION,RowState.UNCERTAIN,RowState.WAITING)},
        authProblem=authProblem)
    val phaseLine=listOfNotNull(titleFor(phase),headline.takeIf{gameRows.isNotEmpty()},breakdown.ifBlank{null}).joinToString(" · ")
    val primaryHint=when {
        uncertain>0&&retryable==0 -> "有待确认结果：先只读核对官方状态，不要直接重试"
        authProblem>0&&gameAttention==0&&completed>0 -> "已签到 $completed 个游戏；授权待处理不影响它们，可先修复授权再执行剩余项"
        gameAttention>0 -> "有 $gameAttention 个游戏项需要处理"
        communityAttention>0&&completed==gameRows.size&&gameRows.isNotEmpty() -> "游戏已完成；社区任务仍需处理（不影响游戏签到）"
        else -> ""
    }
    return HomeFeedback(
        day=day, headline=headline, phaseLine=phaseLine, phase=phase, runRunning=running, runQueued=queued,
        counts=counts, breakdown=breakdown, gameRows=gameRows, communityRows=communityRows, authRows=authRows,
        unsynchronizedAccounts=unsynchronized, primaryHint=primaryHint,
    )
}

/**
 * The readable result of the last finished run, or null when there is nothing to say.
 *
 * Rules: never call an unfinished/unverified item a success; a clean run still gets a one-line card (that is the
 * "一次运行结束有清晰可读结果摘要" requirement) but nothing is claimed about unknown submissions.
 */
fun runResult(feedback:HomeFeedback,trigger:String,finishedAt:String,sequence:Int,workerError:String?=null):RunResult? {
    if(sequence<=0)return null
    val completed=feedback.counts["completed"]?:0
    val games=feedback.counts["games"]?:0
    if(!workerError.isNullOrBlank()) return RunResult(
        sequence=sequence, trigger=trigger, finishedAt=finishedAt, title="上次执行未完成",
        summary=listOfNotNull(
            workerError,
            "已签到 $completed / $games 个游戏".takeIf{games>0},
            (feedback.counts["uncertain"]?:0).takeIf{it>0}?.let{"待确认 $it 项：只读核对，不会自动重发"},
        ),
        error=true,
    )
    val summary=ArrayList<String>(7)
    if(games>0)summary.add("游戏签到：$completed / $games 个已完成")
    if((feedback.counts["submitted"]?:0)>0)summary.add("提交被接受待复核 ${feedback.counts["submitted"]} 项（不计为已签到）")
    if((feedback.counts["uncertain"]?:0)>0)summary.add("结果待确认 ${feedback.counts["uncertain"]} 项（不会自动重发）")
    if((feedback.counts["attention"]?:0)>0)summary.add("游戏待处理 ${feedback.counts["attention"]} 项")
    feedback.authRows.firstOrNull()?.let{summary.add("授权待处理：${it.title}（${it.label}）")}
    if(feedback.communityRows.isNotEmpty()) {
        val done=feedback.communityRows.count{it.state==RowState.COMPLETED}
        val accepted=feedback.communityRows.count{it.state==RowState.SUBMITTED}
        val bad=feedback.communityRows.count{it.state in setOf(RowState.ATTENTION,RowState.UNCERTAIN,RowState.WAITING)}
        summary.add(
            "社区任务：已完成 $done，提交待复核 $accepted，未完成 $bad（共 ${feedback.communityRows.size} 项，不影响游戏签到）"
        )
    }
    if((feedback.counts["unsynchronized"]?:0)>0)summary.add("${feedback.counts["unsynchronized"]} 个账号尚未同步角色，未计入今日签到")
    val clean=(feedback.counts["attention"]?:0)==0&&(feedback.counts["uncertain"]?:0)==0&&
        (feedback.counts["authProblem"]?:0)==0&&(feedback.counts["communityAttention"]?:0)==0&&(games==0||completed>=games)
    return RunResult(
        sequence=sequence, trigger=trigger, finishedAt=finishedAt,
        title=if(clean)"本次执行完成：已确认项已签到" else "本次执行结果",
        summary=summary.ifEmpty{listOf("本次没有需要签到的游戏项")},
        error=false,
    )
}

// ---------------------------------------------------------------------------------------------
// Pure helpers (also used by the repository-backed feedback tests)
// ---------------------------------------------------------------------------------------------

/**
 * The one-line breakdown under the headline. Explicitly separate from the lane rows so a community count can never
 * be read as a game count, and so the breakdown never hides a problem behind a green headline.
 */
private fun buildBreakdown(
    completed:Int, submitted:Int, uncertain:Int, gameAttention:Int, pending:Int,
    communityTotal:Int, communityDone:Int, communityAccepted:Int, communityOpen:Int, authProblem:Int,
):String {
    val parts=ArrayList<String>(6)
    if(submitted>0)parts.add("提交已接受 $submitted")
    if(uncertain>0)parts.add("结果待确认 $uncertain")
    if(gameAttention>0)parts.add("游戏待处理 $gameAttention")
    if(pending>0)parts.add("尚未执行 $pending")
    if(communityTotal>0)parts.add(buildString{
        append("社区任务 已完成 ").append(communityDone)
        if(communityAccepted>0)append("·待复核 ").append(communityAccepted)
        if(communityOpen>0)append("·未完成 ").append(communityOpen)
    })
    if(authProblem>0)parts.add("授权待处理 $authProblem")
    return parts.joinToString(" · ")
}

/** A stored authorization message that means "re-authorize" (Protocol.kt 401 / auth kind, and the rewrites). */
internal fun reauthRequired(message:String)=listOf("重新导入","重新登录","重新授权","授权失效","授权已更新").any{message.contains(it)}

private fun titleFor(phase:String)=when(phase) {
    Phase.RUNNING->"正在执行"
    Phase.QUEUED->"已排队，等待网络或系统调度"
    Phase.DONE->"今日已完成"
    Phase.PARTIAL->"部分完成"
    Phase.PROBLEM->"部分需要处理"
    Phase.PAUSED->"签到已暂停"
    else->"今日未签到"
}

private fun providerTitle(provider:String)=when(provider) {
    "miyoushe"->"米游社"
    "skland"->"森空岛"
    "tagged"->"塔吉多"
    "kurobbs"->"库街区"
    else->provider
}

private fun stateText(state:String)=when(state) {
    "valid"->"可用"
    "needs_action"->"需要处理"
    "failed"->"检查未通过"
    "unknown"->"未验证"
    else->state
}

private fun accountName(account:AuthAccount)=account.nickname.ifBlank{providerTitle(account.provider)}

/** The stored auth message already carries the cause; trim it for a one-line row. */
private fun trimCause(message:String):String {
    val text=message.trim()
    if(text.isBlank())return "原因未返回"
    return if(text.length>60)text.take(58)+"…" else text
}

/**
 * One row, from either an enabled binding or a stored record. Rules:
 *  - `success`/`already` with a provenance -> COMPLETED. `success` with `accepted` -> SUBMITTED (never success).
 *  - `uncertain`, and a `needs_action` whose message says the outcome is unknown -> UNCERTAIN, never success,
 *    with a READ-ONLY reconcile where the adapter supports it (game rows) and 查看记录 otherwise.
 *  - `needs_action`/`failed` on an authorization signal -> ATTENTION with 验证授权 (read-only) / 重新授权.
 *  - a plain failure -> ATTENTION with a real retry of THAT row only (a community failure retries the community
 *    task and leaves every game row untouched).
 *  - no record for an enabled binding -> PENDING with no fix button (the run itself is the action).
 */
private fun rowOf(
    accountId:String, targetId:String, lane:String, name:String, gameId:String,
    record:CheckRecord?, accountName:String, runActive:Boolean,
):StatusRow {
    fun row(state:String,label:String,detail:String,source:String="",fix:String?=null)=StatusRow(
        key=targetKey(accountId,targetId).takeIf{lane!=Lane.AUTH} ?: "auth-$accountId",
        accountId=accountId, targetId=targetId, lane=lane, gameId=gameId,
        title=name.ifBlank{when(lane){Lane.COMMUNITY->"社区任务";Lane.AUTH->AUTHORIZATION_CHECK_NAME;else->"游戏签到"}},
        provider="", state=state, label=label, detail=detail, source=source, fix=fix,
    )
    val note=if(accountName.isBlank())"" else "$accountName · "
    if(record==null) return if(runActive)row(RowState.RUNNING,"执行中","本地任务正在执行；结束后这里给出确切结果")
        else row(RowState.PENDING,"待签到","今日尚未执行；${note}点击立即签到，结果按游戏逐项记录")
    val p=present(record)
    return when(record.status) {
        "running" -> if(runActive)row(RowState.RUNNING,"执行中","本地正在核对与提交；结束后给出确切结果",p.source)
            // A `running` row with no active worker is a killed run: the outcome is unknown, never "已完成".
            else row(RowState.UNCERTAIN,"结果待确认","上次执行中断，结果未知；只读核对官方状态，不会重发",p.source,FixAction.RECONCILE)
        "waiting_network" -> row(RowState.WAITING,"等待网络","网络恢复后重试；尚未确认提交成功","",FixAction.RETRY)
        "uncertain" -> row(RowState.UNCERTAIN,"结果待确认","提交结果未知，绝不会显示成功；${note}先只读核对官方状态，不会重发",p.source,
            when(lane){Lane.GAME->FixAction.RECONCILE;Lane.COMMUNITY->FixAction.RECORDS;else->FixAction.VERIFY})
        "needs_action","failed" -> {
            val unknownOutcome=record.status=="needs_action"&&UNKNOWN_MESSAGES.any{record.message.contains(it)}
            when {
                unknownOutcome -> row(RowState.UNCERTAIN,"结果待确认","提交结果未知，绝不会显示成功；先只读核对官方状态，不会重发",p.source,FixAction.RECONCILE)
                p.isAuthorizationIssue -> row(RowState.ATTENTION,if(lane==Lane.AUTH)"需要处理" else p.label,
                    "授权或角色读取未通过：${trimCause(record.message)}；"+
                        if(lane==Lane.GAME)"这不改变其他已完成的游戏" else "该步骤不提交签到，游戏签到结果不受影响",
                    p.source,FixAction.VERIFY)
                else -> row(RowState.ATTENTION,if(record.status=="failed")"未完成" else p.label,
                    "官方尚未确认完成：${trimCause(record.message)}；先查看记录核对结果，不影响其他已完成的游戏",p.source,
                    if(lane==Lane.GAME)FixAction.RECONCILE else FixAction.RECORDS)
            }
        }
        // `p.label` (from present()) already names the lane truthfully -- 社区已提交 for a community row, 提交已接受
        // for a game row -- so the home card can never relabel a community submission as a game one.
        "success" -> if(p.group=="submitted")row(RowState.SUBMITTED,p.label,
            if(lane==Lane.COMMUNITY)"接口已接受社区任务提交，尚未独立复核；不影响游戏签到" else "接口接受提交，尚未独立复核；可用只读核对确认",
            p.source, if(lane==Lane.GAME)FixAction.RECONCILE else FixAction.RECORDS)
        else row(RowState.COMPLETED,p.label,if(lane==Lane.COMMUNITY)"社区任务已完成，与游戏签到分开统计" else "官方确认今日已签到",p.source)
        "already" -> if(p.group=="success")row(RowState.COMPLETED,"已完成","官方确认今日已签到",p.source)
            else row(RowState.SUBMITTED,"已提交","本机当天已有完成记录，来源非官方确认；可在记录页核对",p.source,FixAction.RECORDS)
        else -> row(RowState.PENDING,"待签到","今日尚未执行",p.source)
    }
}

private val UNKNOWN_MESSAGES=listOf("不确定","未确认","中断")

// ---------------------------------------------------------------------------------------------
// Run bookkeeping (best-effort SharedPreferences string; the only org.json use in this file)
// ---------------------------------------------------------------------------------------------

/** Preferences key holding [RunBook] for the home page. */
const val HOME_FEEDBACK_PREFS="home_feedback_v1"

/**
 * Counts finished runs so a run that starts AND ends inside one background window still produces a readable result
 * summary. Why this is conservative:
 *  - WorkManager only reports whether the unique work is RUNNING/ENQUEUED; an enqueue with `KEEP` creates no new
 *    WorkInfo, so "a new run started" cannot be observed reliably;
 *  - the worker writes `last_started` before running and `last_finished` after it, so a NEW finished timestamp is
 *    the one reliable "a run ended" edge;
 *  - the first observation after install/upgrade only ADOPTS the stored timestamp, so nothing is invented for a run
 *    the user may already have seen;
 *  - a start/finish pair that does not belong to the same run (finished < started) is ignored.
 *
 * Pure: the caller passes and receives the serialized form, so this is unit-tested on the JVM without android.*.
 */
data class RunBook(val sequence:Int=0,val lastSeenFinished:String="")

fun emptyBook():RunBook=RunBook()

fun readBook(stored:String?):RunBook {
    if(stored.isNullOrBlank())return emptyBook()
    return runCatching{
        val o=org.json.JSONObject(stored)
        RunBook(o.optInt("run_seq",0),o.optString("last_seen_finished",""))
    }.getOrDefault(emptyBook())
}

/** Returns the new book. [RunBook.sequence] only grows, and only because a new finished timestamp was observed. */
fun observeRun(book:RunBook,storedStarted:String?,storedFinished:String?,running:Boolean):RunBook {
    val started=storedStarted.orEmpty()
    val finished=storedFinished.orEmpty()
    if(finished.isBlank())return book
    if(finished==book.lastSeenFinished)return book
    // This run has not ended yet: the worker wrote `last_started` after the previous `last_finished`.
    if(started>finished)return book
    if(running&&started.isNotBlank())return book
    if(book.lastSeenFinished.isBlank())return RunBook(book.sequence,finished) // adopt, never invent
    return if(finished>book.lastSeenFinished)RunBook(book.sequence+1,finished) else book
}

fun serializeBook(book:RunBook):String=runCatching{
    org.json.JSONObject().put("run_seq",book.sequence).put("last_seen_finished",book.lastSeenFinished).toString()
}.getOrDefault("")
