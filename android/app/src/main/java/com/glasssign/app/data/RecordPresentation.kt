package com.glasssign.app.data

/**
 * A record's user-facing wording.
 *
 * CONTRACT (other files compile against these three properties, do not rename):
 *  - [label]  short status word shown on the record row / as the detail dialog title.
 *  - [group]  one of "success" / "attention" / "uncertain" / "submitted" / "pending" / "running";
 *             ui/HoshioriScreen.kt switches on exactly these values for colour and the filter chips.
 *  - [source] provenance sentence shown in the record detail dialog.
 */
data class RecordPresentation(val label:String,val group:String,val source:String,val isAuthorizationIssue:Boolean=false)

/** The read-only authorization/role discovery step: `LocalSignEngine.kt:24` builds this target. */
internal const val AUTHORIZATION_CHECK_TARGET="discovery"
internal const val AUTHORIZATION_CHECK_NAME="授权与角色检查"

private const val STATUS_ALREADY="already"
private const val STATUS_SUCCESS="success"
private const val STATUS_UNCERTAIN="uncertain"
private const val STATUS_NEEDS_ACTION="needs_action"
private const val STATUS_RUNNING="running"
private const val STATUS_FAILED="failed"
private const val STATUS_WAITING_NETWORK="waiting_network"

private const val SOURCE_OFFICIAL="official"
private const val SOURCE_ACCEPTED="accepted"
private const val SOURCE_LEGACY_LOCAL="legacy_local"

/** Baseline unknown-submission markers, unchanged. */
private val UNKNOWN_MARKERS=listOf("不确定","未确认","中断")
/** Role lookup / binding phrases produced by the adapters' role enumeration. */
private val ROLE_CHECK_MARKERS=listOf("角色查询","角色绑定","绑定角色","角色读取")
/** Authorization phrases: 401 / auth-kind failures and the rejected-auth rewrite. */
private val AUTHORIZATION_MARKERS=listOf("授权","重新导入","重新登录","重新授权")
private const val STATE_ANOMALY_MARKER="社区接口状态异常"

/**
 * Community-reward tasks. Providers build them as targets in a reserved id namespace, outside the game
 * namespace (`island|…` 森空岛社区检票 / `community|…` 塔吉多·库街区 社区签到, see Providers.kt). They are a
 * DIFFERENT product from a game check-in: a community-task failure must never be able to make a finished game
 * look failed, and its wording must not claim a game was signed. Kept here because Providers.kt is outside this
 * change's scope; [isCommunityTarget] is the single place both the data layer and the home feedback agree on.
 */
internal fun isCommunityTarget(targetId:String):Boolean = targetId.startsWith("community|") || targetId.startsWith("island|")
/** Stable kind key: "game" / "community" / "auth" (== authorization & role discovery). */
internal fun targetKind(targetId:String):String = when {
    targetId==AUTHORIZATION_CHECK_TARGET -> "auth"
    isCommunityTarget(targetId) -> "community"
    else -> "game"
}
internal const val KIND_GAME="game"
internal const val KIND_COMMUNITY="community"
internal const val KIND_AUTH="auth"

fun present(record:CheckRecord):RecordPresentation {
    // Community rows keep their own vocabulary; an unknown submission is still never shown as success, so the
    // unknown check is repeated inside the community branch instead of short-circuiting its labels here.
    // The `!isCommunityTarget` guard applies to BOTH alternatives (parenthesized: `&&` binds tighter than `||`).
    val unknown=!isCommunityTarget(record.targetId) && (
        record.status==STATUS_UNCERTAIN ||
            (record.status==STATUS_NEEDS_ACTION&&UNKNOWN_MARKERS.any{record.message.contains(it)})
        )
    return when {
        unknown -> RecordPresentation("结果待确认","uncertain","先前提交结果未知；不会自动重发")
        // Community tasks are neither a game check-in nor an authorization problem: keep their own vocabulary
        // (a community failure stays visible, but can never be read as "游戏签到失败").
        isCommunityTarget(record.targetId) -> communityPresentation(record)
        // Non-sign rows: only these can truthfully say no submission was made.
        record.status in setOf(STATUS_FAILED,STATUS_NEEDS_ACTION)&&isNonSignTarget(record) -> nonSignPresentation(record)
        // Sign targets that failed on authorization: a submission may or may not have happened, so state
        // only what is known (authorization needs attention) and never assert "未产生签到提交".
        record.status in setOf(STATUS_FAILED,STATUS_NEEDS_ACTION)&&hasAuthorizationSignal(record) -> RecordPresentation(
            if(isReimportRequired(record.message))"需要重新授权" else "需要处理",
            "attention",
            if(isReimportRequired(record.message))"授权已失效，需重新授权；无法确认是否已签到，请核对官方记录后再决定是否重试"
            else "授权或角色状态异常，无法确认是否已签到；请核对官方记录，不要直接重试",
            isAuthorizationIssue=true
        )
        record.status==STATUS_NEEDS_ACTION -> RecordPresentation("需要处理","attention","需要授权或用户操作")
        record.status==STATUS_ALREADY&&record.resultSource==SOURCE_LEGACY_LOCAL -> RecordPresentation("本机已有完成记录","submitted","旧版本地完成记录；原始官方确认来源未知")
        record.status==STATUS_ALREADY -> RecordPresentation("已签到","success",if(record.message.contains("本机")||record.message.contains("保护"))"本机当天完成记录" else "官方明确已签到")
        record.status==STATUS_SUCCESS -> if(record.resultSource==SOURCE_ACCEPTED || record.message.contains("接受"))RecordPresentation("提交已接受","submitted","接口接受提交；尚无独立状态复核") else RecordPresentation("已签到","success","官方成功响应 / 状态确认")
        record.status==STATUS_RUNNING -> RecordPresentation("正在签到","running","本地执行")
        record.status==STATUS_WAITING_NETWORK -> RecordPresentation("等待网络","attention","尚未成功发起查询，网络恢复后可重试")
        record.status==STATUS_FAILED -> RecordPresentation("签到失败","attention","官方未确认成功，请查看原因")
        else -> RecordPresentation("待签到","pending","尚无完成记录")
    }
}

/** "授权失效，请重新导入" (Protocol.kt 401 / auth kind) means the user must re-authorize. */
private fun isReimportRequired(message:String)=listOf("重新导入","重新登录","重新授权","授权失效","授权已更新").any{message.contains(it)}

/**
 * Providers' "no roles found" placeholder targets (Providers.kt `ifEmpty{listOf(SignTarget("no-roles",...))}`).
 * They are produced by the role enumeration, i.e. BEFORE any check-in request exists, so they may say so.
 * Kept in one list because Providers.kt belongs to another change scope.
 */
private val LAST_RESORT_TARGETS=listOf("米游社角色绑定","森空岛绑定角色","塔吉多游戏角色","库街区游戏角色","角色待读取")

/**
 * True only when the row provably never submitted a check-in: the discovery step, or a provider's
 * "no roles" placeholder target ("米游社角色绑定" / "森空岛绑定角色" / "塔吉多游戏角色" / "库街区游戏角色").
 * A message phrase alone is never enough for this claim.
 */
internal fun isNonSignTarget(record:CheckRecord):Boolean {
    if(record.targetId==AUTHORIZATION_CHECK_TARGET) return true
    val target=record.targetName
    return target.contains(AUTHORIZATION_CHECK_NAME) || target.contains("角色检查") ||
        target.contains("角色绑定") || target.contains("绑定角色") ||
        LAST_RESORT_TARGETS.any{target.contains(it)}
}

/** A failed/needs_action row that is about authorization or role resolution (wording only). */
internal fun hasAuthorizationSignal(record:CheckRecord):Boolean {
    val target=record.targetName
    val message=record.message
    if(ROLE_CHECK_MARKERS.any{message.contains(it)}) return true
    return AUTHORIZATION_MARKERS.any{message.contains(it)}
}

private fun nonSignPresentation(record:CheckRecord):RecordPresentation {
    val reauth=isReimportRequired(record.message)
    return if(reauth) RecordPresentation(
        "需要重新授权","attention",
        "授权已失效，该步骤不提交签到；请在官方页面重新导入本人授权",isAuthorizationIssue=true)
    else RecordPresentation(
        "授权或角色检查未通过","attention",
        "授权/角色读取未完成，该步骤不提交签到；请在官方应用核对授权与角色绑定",isAuthorizationIssue=true)
}

/**
 * Community-reward rows (森空岛社区检票 / 塔吉多·库街区社区签到). Same truthful states as a game row, but the
 * wording names the community task, so a failed community task can never read as "游戏签到失败" and a finished
 * community task never reads as a finished game. Groups stay inside the six values the UI already switches on.
 */
private fun communityPresentation(record:CheckRecord):RecordPresentation {
    val reauth=isReimportRequired(record.message)
    return when(record.status) {
        STATUS_ALREADY -> RecordPresentation("社区任务已完成","success","官方明确该社区任务今日已完成")
        STATUS_SUCCESS -> if(record.resultSource==SOURCE_ACCEPTED || record.message.contains("接受"))
            RecordPresentation("社区已提交","submitted","接口接受社区任务提交；尚无独立状态复核")
        else RecordPresentation("社区任务已完成","success","官方成功响应 / 状态确认")
        STATUS_RUNNING -> RecordPresentation("社区任务进行中","running","本地执行")
        STATUS_UNCERTAIN -> RecordPresentation("社区结果待确认","uncertain","社区任务提交结果未知；不会自动重发，请到官方应用核对")
        STATUS_NEEDS_ACTION -> if(UNKNOWN_MARKERS.any{record.message.contains(it)})RecordPresentation("社区结果待确认","uncertain","社区任务提交结果未知；不会自动重发，请到官方应用核对")
        else RecordPresentation(if(reauth)"社区任务需重新授权" else "社区任务需处理","attention",
            if(reauth)"社区任务授权已失效；这不影响今日已完成的游戏签到" else "社区任务需要授权或用户操作；游戏签到结果不受影响",
            isAuthorizationIssue=reauth)
        STATUS_FAILED,STATUS_WAITING_NETWORK -> RecordPresentation("社区任务未完成","attention",
            "社区奖励任务未完成；不影响同日已完成的游戏签到，可稍后重试社区任务")
        else -> RecordPresentation("社区任务待执行","pending","尚未执行社区任务")
    }
}

/** Approval-scoped row arithmetic shared by the home summary and the repository-backed tests. */
internal fun targetKey(accountId:String,targetId:String)=accountId+"|"+targetId

/**
 * True when a record counts as "this game/role is done for today". Only the two confirmed groups count; a
 * submitted-but-unverified row (`submitted`) and an unknown submission (`uncertain`) never do.
 */
internal fun isCompletedToday(record:CheckRecord):Boolean = present(record).group=="success"

/** Kept for callers/tests that only need the classification. */
internal fun authorizationIssue(record:CheckRecord):Boolean =
    record.status in setOf(STATUS_FAILED,STATUS_NEEDS_ACTION) && (isNonSignTarget(record) || hasAuthorizationSignal(record))
