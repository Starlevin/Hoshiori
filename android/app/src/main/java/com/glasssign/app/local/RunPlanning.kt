package com.glasssign.app.local

import com.glasssign.app.data.CheckRecord
import com.glasssign.app.data.GameBinding
import com.glasssign.app.data.ResultMeta
import com.glasssign.app.data.isCommunityTarget

/*
 * Pure run-planning policy (no android.*, no network, no database handle).
 *
 * WHY THIS FILE EXISTS (v0.7.7 P0): `LocalSignEngine.run()` claimed the "discovery" step, called
 * `adapter.targets()` — which renews tokens (米游社 getCookieAccountInfoBySToken, 森空岛 /auth/refresh,
 * 塔吉多 refreshToken) — and only then noticed that every task of the account was already done. So each click
 * produced a discovery GET plus a token POST for an account with nothing left to do, which is exactly the
 * "重复验证 / 重复授权请求" the user reported.
 *
 * The fix is a decision made BEFORE any network call, from data that is already on disk:
 *  1. the account's known scope — the enabled bindings, plus the stored community/no-role targets
 *     (`discovered_roles`, which is what the last role enumeration returned);
 *  2. today's records and the guard rows (the submit protection that survives history deletion);
 *  3. the explicit metadata of those rows.
 *
 * Contract of this file:
 *  - It never invents a target. A target that is not in the trusted known scope is simply not planned; a game the
 *    user just enabled (in the scope, no record, no guard) is therefore always planned and never skipped.
 *  - "Blocked" means: a submission for this (day, account, target) must not happen again today. Only explicitly
 *    submitted/confirmed/unknown states block; a definite failure with `retryable=true` does not.
 *  - "Satisfied" (nothing left to do) additionally requires an accepted-or-official proof; an unknown submission
 *    keeps the account "open" so the home page can offer the read-only reconcile instead of a silent skip.
 */

/** One task the account owns today, from the trusted known scope only. */
data class PlannedTask(
    val target:SignTarget,
    /** Game id from the binding (`""` for community/no-role targets). */
    val gameId:String,
    /** False when the binding is explicitly disabled (shown, never signed). */
    val enabled:Boolean,
    /** False when automatic execution is off for this binding; a manual run still executes it. */
    val autoSign:Boolean,
    /** True when the target came from `discovered_roles` and never had a binding row (community / no-role). */
    val unbound:Boolean,
)

/** Why a planned task is not executed right now. Every value is a distinct, user-visible reason. */
object TaskSkip {
    /** The binding is disabled by the user. */
    const val DISABLED="disabled"
    /** Scheduled runs only: the binding is enabled but automatic signing is off. */
    const val AUTO_OFF="auto_off"
    /** A claim on the same (day, account, target) already blocks another submission. */
    const val GUARDED="guarded"
    /** The task is kept visible for a read-only follow-up, not for a new submission. */
    const val AWAITING_CONFIRMATION="awaiting_confirmation"
}

/** The result of planning one account, computed with zero network access. */
data class AccountRunPlan(
    val accountId:String,
    /** True when the account's scope is trusted (explicit enabled bindings exist). */
    val trustedScope:Boolean,
    /** Tasks that still need work, in execution order. */
    val tasks:List<PlannedTask>,
    /** Targets deliberately skipped, with the reason. */
    val skipped:List<Pair<PlannedTask,String>>,
    /** True when a stored record/guard already proves the day is handled for every owned target. */
    val nothingTodo:Boolean,
    /** Number of owned targets whose stored result is an official or accepted proof. */
    val satisfiedCount:Int,
) {
    val hasWork:Boolean get()=tasks.isNotEmpty()
    /** A read-only reconcile candidate: stored but not confirmed (uncertain / accepted / needs the official check). */
    val awaitingConfirmation:List<PlannedTask> get()=skipped.filter{it.second==TaskSkip.AWAITING_CONFIRMATION}.map{it.first}
    /** True when the only remaining action would be a read-only status check, i.e. no submission is needed. */
    val queryOnly:Boolean get()=!hasWork && awaitingConfirmation.isNotEmpty()
}

/** One account as the planner sees it (an `Account` reduced to what matters here). */
data class AccountPlanInput(
    val id:String,
    /** Game ids the user selected for this account (empty = legacy account without an explicit scope). */
    val gameIds:List<String>,
    /** `Account.roleScope`: true once explicit bindings were confirmed by the user. */
    val roleScope:Boolean,
)

/**
 * Builds the known scope of one account: enabled/disabled bindings + stored discovered roles.
 * Community targets (`community|…`, `island|…`) and the `no-roles` placeholder are kept as their own tasks because
 * they are real work items, but they are never converted into game rows.
 */
fun planAccount(
    account:AccountPlanInput,
    bindings:List<GameBinding>,
    discoveredRoles:List<com.glasssign.app.data.RoleChoice>,
    records:List<CheckRecord>,
    guards:List<CheckRecord>,
    scheduled:Boolean,
):AccountRunPlan {
    val ownBindings=bindings.filter{it.accountId==account.id}
    val trustedScope=account.roleScope && ownBindings.isNotEmpty()
    val tasks=LinkedHashMap<String,PlannedTask>()
    for(binding in ownBindings) {
        tasks[binding.targetId]=PlannedTask(
            target=SignTarget(binding.targetId,binding.name),
            gameId=binding.gameId,
            enabled=binding.enabled,
            autoSign=binding.autoSignEnabled,
            unbound=false,
        )
    }
    // Stored community / no-role targets of THIS account (roleChoices(id) is already scoped). A game target
    // discovered but never bound is NOT added: only the user's explicit binding selection decides which games sign.
    for(role in discoveredRoles) {
        if(role.targetId in tasks)continue
        val community=isCommunityTarget(role.targetId)||role.targetId=="no-roles"
        if(!community)continue
        tasks[role.targetId]=PlannedTask(
            target=SignTarget(role.targetId,role.name),
            gameId=role.gameId,
            enabled=true,
            autoSign=true,
            unbound=true,
        )
    }
    val recordByTarget=records.filter{it.accountId==account.id}.associateBy{it.targetId}
    val guardByTarget=guards.filter{it.accountId==account.id}.associateBy{it.targetId}
    val planned=ArrayList<PlannedTask>(tasks.size)
    val skipped=ArrayList<Pair<PlannedTask,String>>(tasks.size)
    var satisfied=0
    for(task in tasks.values) {
        val skip=skipReason(task,recordByTarget[task.target.id],guardByTarget[task.target.id],scheduled)
        if(skip==null)planned.add(task) else skipped.add(task to skip)
        val evidence=evidenceOf(recordByTarget[task.target.id],guardByTarget[task.target.id])
        if(evidence!=null&&evidence.isSatisfied)satisfied++
    }
    // "Nothing to do" = the scope is trusted AND every owned target has accepted-or-official evidence. An UNKNOWN
    // outcome is not evidence of completion, so that account stays open (queryOnly) and the UI offers the read-only
    // reconcile instead of a silent skip. Accepted evidence is HANDLED (no submission) but still only "submitted",
    // which is why the accepted example below reports nothingTodo with satisfiedCount == 0.
    val handled=tasks.keys.count{(recordByTarget[it]?:guardByTarget[it])?.let{evidenceOf(it,null)}?.let{it.isSatisfied||it.source==SOURCE_ACCEPTED}==true}
    val nothingTodo=trustedScope&&planned.isEmpty()&&tasks.isNotEmpty()&&handled==tasks.size
    return AccountRunPlan(account.id,trustedScope,planned,skipped,nothingTodo,satisfied)
}

/** The reason a planned task must not be submitted now, or null when it may run. */
fun skipReason(task:PlannedTask,record:CheckRecord?,guard:CheckRecord?,scheduled:Boolean):String? {
    if(!task.enabled)return TaskSkip.DISABLED
    if(scheduled&&!task.autoSign)return TaskSkip.AUTO_OFF
    val stored=record ?: guard
    if(stored!=null && stored.status in setOf("failed","waiting_network") && !stored.retryable)return TaskSkip.GUARDED
    val evidence=evidenceOf(record,guard) ?: return null
    if(!evidence.blocksSubmission)return null
    return if(evidence.awaitingConfirmation)TaskSkip.AWAITING_CONFIRMATION else TaskSkip.GUARDED
}

/**
 * What a stored row proves. [blocksSubmission] mirrors [SignRepository.claim]'s block list; [isSatisfied] is the
 * stronger "this task is handled today and needs no submission".
 */
data class StoredEvidence(
    val status:String,
    val source:String,
    val blocksSubmission:Boolean,
    val isSatisfied:Boolean,
    val awaitingConfirmation:Boolean,
)

/** A record wins over a guard: the record is the current row, the guard is the fallback after history deletion. */
fun evidenceOf(record:CheckRecord?,guard:CheckRecord?):StoredEvidence? {
    val row=record ?: guard ?: return null
    // Explicit metadata first, then the declared column, then the legacy interpretation of an older row.
    val source=row.resultMeta.source.ifBlank{row.resultSource}.ifBlank{SOURCE_UNKNOWN}
    // An ACCEPTED submission blocks a second POST just like a confirmed one; it simply is not "satisfied".
    val accepted=source==SOURCE_ACCEPTED
    val blocks=row.status in BLOCKING_STATUSES || accepted || (source in CONFIRMED_SOURCES && row.status in setOf("success","already"))
    // "Satisfied" = a CONFIRMED completion: a success/already row that no accepted-submission provenance covers.
    val satisfied=row.status in setOf("success","already")&&source!=SOURCE_ACCEPTED
    // "Awaiting confirmation" = stored, must not be resubmitted, but not confirmed either.
    val awaiting=blocks&&!satisfied
    return StoredEvidence(row.status,source,blocks,satisfied,awaiting)
}

// Sources that prove a COMPLETED check-in. `accepted` is deliberately absent: an accepted submission is
// handled (it blocks a second POST) but the day is not confirmed, so it must never count as satisfied.
private val CONFIRMED_SOURCES=setOf(SOURCE_OFFICIAL,SOURCE_LEGACY_LOCAL)

/**
 * Compatibility mapper for rows written before `result_meta` existed: the message wording is consulted ONLY here,
 * and only to describe an already-stored row. It never creates or upgrades a new result.
 */
fun legacySourceOf(row:CheckRecord):String {
    // A DECLARED non-legacy provenance always wins.
    if(row.resultSource.isNotBlank()&&row.resultSource !in setOf("unknown",SOURCE_LEGACY))
        return row.resultSource
    if(row.resultSource==SOURCE_LEGACY&&row.resultMeta.source.isNotBlank())return row.resultMeta.source
    // Otherwise the row predates explicit metadata and only its wording survives. This mapping is CONSERVATIVE: it can
    // describe a local completion (the device really did write that sentence), but it never promotes a row to
    // "accepted" or "official", because an old success may have come from the official endpoint just as easily.
    return if(row.status=="already"&&row.message.contains("本机")) SOURCE_LEGACY_LOCAL
    else if(row.status in setOf("success","already")) SOURCE_LEGACY
    else SOURCE_UNKNOWN
}

/** Explicit provenance of a stored row, or the compatible legacy interpretation. Never inferred for new results. */
fun provenanceOf(row:CheckRecord):ResultMeta =
    if(!row.resultMeta.isBlank)row.resultMeta else ResultMeta(source=legacySourceOf(row))
