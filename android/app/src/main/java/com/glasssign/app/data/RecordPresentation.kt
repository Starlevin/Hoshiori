package com.glasssign.app.data

/**
 * A record's user-facing wording.
 *
 * CONTRACT (other files compile against these properties, do not rename):
 *  - [label]     the short status word shown on the record row / as the detail dialog title.
 *  - [group]     the UI's colour/filter bucket. ONE of "success" / "attention" / "uncertain" / "submitted" /
 *                "pending" / "running" — ui/HoshioriScreen.kt switches on exactly these values.
 *  - [source]    provenance sentence shown in the record detail dialog.
 *  - [state]     the SEMANTIC state (see [SignState]); the group above is its UI mapping.
 *  - [detail]    one-line explanation + the technical fallback (HTTP status / provider code / source).
 */
data class RecordPresentation(
    val label:String,
    val group:String,
    val source:String,
    val isAuthorizationIssue:Boolean=false,
    /** The seven semantic states of v0.7.7; defaults to UNKNOWN so old positional constructions keep compiling. */
    val state:String=SignState.UNKNOWN,
    /** "", "HTTP 402", "code 10001", "source accepted" … never a secret. */
    val detail:String="",
)

/**
 * The seven semantic states a stored result can be shown as. This is the vocabulary the presentation, the home page
 * and the notification share; [group] remains the UI paint bucket.
 *
 *  - [SUCCESS]        official confirmation of a real check-in (success/verified).
 *  - [ALREADY]        official endpoint reported the day was already signed.
 *  - [ACCEPTED]       the interface accepted the submission, but no independent confirmation exists.
 *  - [VERIFYING]      a read-only check/claim is in progress; transient, never erases an earlier proof.
 *  - [FAILED]         a definite failure with no submission ambiguity.
 *  - [AUTH_REQUIRED]  authorization/captcha/role step needs the user; no claim is made about a submission.
 *  - [UNKNOWN]        the outcome of a possible submission is unknown; never success, never auto-retried.
 */
object SignState {
    const val SUCCESS="success"
    const val ALREADY="already"
    const val ACCEPTED="accepted"
    const val VERIFYING="verifying"
    const val FAILED="failed"
    const val AUTH_REQUIRED="auth_required"
    const val UNKNOWN="unknown"

    /** The single UI bucket for each semantic state. The renderer may only switch on these six values. */
    fun groupOf(state:String):String = when(state) {
        SUCCESS,ALREADY -> "success"
        ACCEPTED -> "submitted"
        VERIFYING -> "running"
        FAILED -> "attention"
        AUTH_REQUIRED -> "attention"
        else -> "uncertain"
    }
}

/** The fixed label per semantic state (the requested wording, unchanged across lanes). */
internal fun labelOf(state:String):String = when(state) {
    SignState.SUCCESS -> "签到成功"
    SignState.ALREADY -> "已签到"
    SignState.ACCEPTED -> "已提交等待确认"
    SignState.VERIFYING -> "正在核验"
    SignState.FAILED -> "失败"
    SignState.AUTH_REQUIRED -> "需授权"
    else -> "未知"
}

/** The read-only authorization/role discovery step: `LocalSignEngine` builds this target. */
const val AUTHORIZATION_CHECK_TARGET="discovery"
const val AUTHORIZATION_CHECK_NAME="授权与角色检查"

private const val STATUS_ALREADY="already"
private const val STATUS_SUCCESS="success"
private const val STATUS_UNCERTAIN="uncertain"
private const val STATUS_NEEDS_ACTION="needs_action"
private const val STATUS_RUNNING="running"
private const val STATUS_VERIFYING="verifying"
private const val STATUS_FAILED="failed"
private const val STATUS_WAITING_NETWORK="waiting_network"

internal const val SOURCE_OFFICIAL="official"
internal const val SOURCE_ACCEPTED="accepted"
internal const val SOURCE_LOCAL_CACHE="local_cache"
internal const val SOURCE_LEGACY_LOCAL="legacy_local"
internal const val SOURCE_LEGACY="legacy"

/** Baseline unknown-submission markers: used ONLY to read rows written by older versions. */
private val UNKNOWN_MARKERS=listOf("不确定","未确认","中断")
/** Role lookup phrases produced by the adapters' role enumeration. */
private val ROLE_CHECK_MARKERS=listOf("角色查询","角色绑定","绑定角色","角色读取")
/** Authorization phrases: 401 / auth-kind failures and the rejected-auth rewrite. */
private val AUTHORIZATION_MARKERS=listOf("授权","重新导入","重新登录","重新授权")

/**
 * The explicit provenance of a stored row. New rows carry [CheckRecord.resultMeta]; rows written by older versions
 * are described by the compatibility mapping in [legacySourceOf] — this is the ONLY place message wording is read,
 * and it never creates or upgrades a result.
 */
internal fun sourceOf(record:CheckRecord):String {
    val explicit=if(record.resultMeta.source.isNotBlank())record.resultMeta.source else record.resultSource
    if(explicit.isNotBlank()&&explicit!="unknown")return explicit
    return when {
        record.status==STATUS_ALREADY&&record.message.contains("本机") -> SOURCE_LEGACY_LOCAL
        record.status==STATUS_SUCCESS&&record.message.contains("接受") -> SOURCE_ACCEPTED
        record.status in setOf(STATUS_SUCCESS,STATUS_ALREADY) -> SOURCE_LEGACY
        else -> "unknown"
    }
}

/** Technical fallback line: HTTP status / provider code / source. Bounded, secret-free. */
internal fun technicalDetail(record:CheckRecord):String {
    val meta=record.resultMeta
    val parts=ArrayList<String>(3)
    meta.httpStatus?.let{parts.add("HTTP $it")}
    if(meta.providerCode.isNotBlank())parts.add("code ${meta.providerCode}")
    val source=sourceOf(record)
    if(source!="unknown"&&source.isNotBlank())parts.add("source $source")
    return parts.joinToString(" · ")
}

/** Community-reward tasks live in a reserved id namespace outside the game namespace. */
internal fun isCommunityTarget(targetId:String):Boolean = targetId.startsWith("community|") || targetId.startsWith("island|")
/** Stable kind key: "game" / "community" / "auth". */
internal fun targetKind(targetId:String):String = when {
    targetId==AUTHORIZATION_CHECK_TARGET -> "auth"
    isCommunityTarget(targetId) -> "community"
    else -> "game"
}
internal const val KIND_GAME="game"
internal const val KIND_COMMUNITY="community"
internal const val KIND_AUTH="auth"

/**
 * Maps one stored record to the seven semantic states, then to the wording the app shows.
 *
 * Rules (v0.7.7):
 *  - New rows are classified by their EXPLICIT status + provenance, never by message text.
 *  - `accepted` is 已提交等待确认 and is counted as pending confirmation, not as a completed check-in.
 *  - `verifying` is transient: it keeps showing the earlier proof, so a read-only check never looks like a reset.
 *  - A failed/unknown authorization row can never read as a sign failure.
 *  - Community rows keep their own vocabulary so a community failure never reads as "游戏签到失败".
 */
fun present(record:CheckRecord):RecordPresentation {
    val community=isCommunityTarget(record.targetId)
    val state=semanticState(record)
    val label=labelFor(state,record,community)
    val (sentence,isAuth)=sourceSentence(state,record,community)
    val group=if(state==SignState.AUTH_REQUIRED&&!community) "attention" else SignState.groupOf(state)
    return RecordPresentation(
        label=label,
        group=group,
        source=sentence,
        isAuthorizationIssue=isAuth||isNonSignTarget(record)||(record.resultMeta.isBlank&&hasAuthorizationSignal(record)),
        state=state,
        detail=technicalDetail(record),
    )
}

/**
 * The semantic state of a stored row, decided by status + explicit provenance.
 *
 * `verifying` is a transient read-only marker; when the row still carries a trusted proof the presentation shows that
 * proof (so a status check never looks like a reset) and only an unproven row shows 正在核验.
 */
internal fun semanticState(record:CheckRecord):String {
    val source=sourceOf(record)
    val unknownOutcome=record.status==STATUS_UNCERTAIN||(record.status==STATUS_NEEDS_ACTION&&record.message.isLegacyUnknownWording)
    return when {
        record.status==STATUS_VERIFYING -> SignState.VERIFYING
        record.status=="accepted" || (source==SOURCE_ACCEPTED && record.status in setOf(STATUS_SUCCESS,STATUS_UNCERTAIN)) -> SignState.ACCEPTED
        unknownOutcome -> SignState.UNKNOWN
        record.status==STATUS_ALREADY -> SignState.ALREADY
        record.status==STATUS_SUCCESS&&source==SOURCE_ACCEPTED -> SignState.ACCEPTED
        record.status==STATUS_SUCCESS -> SignState.SUCCESS
        record.status==STATUS_RUNNING -> SignState.VERIFYING
        record.status in setOf(STATUS_FAILED,STATUS_NEEDS_ACTION,STATUS_WAITING_NETWORK) -> {
            val legacyAuth=record.resultMeta.isBlank && hasAuthorizationSignal(record) && isReimportRequired(record.message)
            val authIssue=record.status==STATUS_NEEDS_ACTION || record.httpStatus==401 || record.providerCode in setOf("auth","captcha","config") || legacyAuth
            when {
                authIssue -> SignState.AUTH_REQUIRED
                hasCaptchaSignal(record) -> SignState.AUTH_REQUIRED
                else -> SignState.FAILED
            }
        }
        record.status in setOf("pending","queued") -> SignState.UNKNOWN
        else -> SignState.UNKNOWN
    }
}

/** True when the row carries explicit provenance that the app may still show while a read-only check runs. */
private fun hasTrustedProof(record:CheckRecord):Boolean =
    record.resultSource in setOf(SOURCE_OFFICIAL,SOURCE_ACCEPTED,SOURCE_LEGACY_LOCAL) ||
        record.resultMeta.source in setOf(SOURCE_OFFICIAL,SOURCE_ACCEPTED,SOURCE_LEGACY_LOCAL)

/** The state a stored proof maps to on its own (used while a transient read-only marker is active). */
private fun proofState(record:CheckRecord,source:String):String=when {
    source==SOURCE_ACCEPTED -> SignState.ACCEPTED
    record.status==STATUS_SUCCESS -> SignState.SUCCESS
    else -> SignState.ALREADY
}

private val String.isLegacyUnknownWording:Boolean get()=UNKNOWN_MARKERS.any{contains(it)}
private fun hasCaptchaSignal(record:CheckRecord)=listOf("人机验证","验证码").any{record.message.contains(it)}

private fun labelFor(state:String,record:CheckRecord,community:Boolean):String=when {
    state==SignState.FAILED&&isNonSignTarget(record) -> "授权与角色检查失败"
    state==SignState.AUTH_REQUIRED&&isReimportRequired(record.message) -> if(community)"社区任务需重新授权" else "需要重新授权"
    state==SignState.AUTH_REQUIRED&&community -> "社区任务需处理"
    state==SignState.AUTH_REQUIRED -> labelOf(state)
    !community -> labelOf(state)
    // Community lane: same semantic states, community wording.
    state==SignState.SUCCESS -> "社区任务成功"
    state==SignState.ALREADY -> "社区任务已完成"
    state==SignState.ACCEPTED -> "社区已提交等待确认"
    state==SignState.VERIFYING -> "正在核验"
    state==SignState.FAILED -> "社区任务未完成"
    else -> "社区结果未知"
}

private fun sourceSentence(state:String,record:CheckRecord,community:Boolean):Pair<String,Boolean> {
    val reauth=isReimportRequired(record.message)
    // A local-cache hit restates HOW this row is displayed; the original proof keeps its own verified time.
    // A row whose ONLY evidence is this device (legacy_local) or an explicit local_cache restatement is shown
    // as a locally cached proof, never as a fresh official query.
    val localCache=record.resultMeta.verificationMethod==SOURCE_LOCAL_CACHE||sourceOf(record)==SOURCE_LEGACY_LOCAL
    return when(state) {
        SignState.SUCCESS -> (if(localCache)"本机已有完成记录（沿用原证明，未重新查询官方）" else "官方成功响应 / 状态确认") to false
        SignState.ALREADY -> (if(localCache)"本机当天完成记录（沿用原证明，未重新查询官方）" else "官方明确已签到") to false
        SignState.ACCEPTED -> (if(community)"接口接受社区任务提交；尚无独立状态复核" else "接口接受提交；尚无独立状态复核") to false
        SignState.VERIFYING ->
            // Never claims a new result: the earlier proof is still the record's truth while the check runs.
            ("正在只读核验官方状态；原结果与来源保持不变" to false)
        SignState.AUTH_REQUIRED -> when {
            isNonSignTarget(record)&&reauth -> ("授权已失效，该步骤不提交签到；请在官方页面重新导入本人授权" to true)
            isNonSignTarget(record) -> ("授权/角色读取未完成，该步骤不提交签到；请在官方应用核对授权与角色绑定" to true)
            reauth -> ("授权已失效，需重新授权；无法确认是否已签到，请核对官方记录后再决定是否重试" to true)
            community -> ("社区任务需要授权或用户操作；游戏签到结果不受影响" to true)
            else -> ("授权或角色状态异常，无法确认是否已签到；请核对官方记录，不要直接重试" to true)
        }
        SignState.FAILED -> when {
            isNonSignTarget(record) -> ("授权或角色读取失败；该步骤不提交签到，不能据此认定凭据失效" to true)
            community -> (if(record.retryable)"社区任务未完成；可按限制重试，不影响已完成游戏" else "社区任务未完成；请先核对原因，不会自动重发，不影响已完成游戏") to false
            record.resultMeta.retryable -> ("官方未确认成功，原因明确且可安全重试" to false)
            else -> ("官方未确认成功，请查看原因" to false)
        }
        else -> when {
            community -> ("社区任务提交结果未知；不会自动重发，请到官方应用核对" to false)
            record.status in setOf("pending","queued") -> ("尚无完成记录" to false)
            else -> ("先前提交结果未知；不会自动重发" to false)
        }
    }
}

/** "授权失效，请重新导入" (Protocol.kt 401 / auth kind) means the user must re-authorize. */
private fun isReimportRequired(message:String)=listOf("重新导入","重新登录","重新授权","授权失效","授权已更新").any{message.contains(it)}

/** Providers' "no roles found" placeholder targets, produced BEFORE any check-in request exists. */
private val LAST_RESORT_TARGETS=listOf("米游社角色绑定","森空岛绑定角色","塔吉多游戏角色","库街区游戏角色","角色待读取")

/** True only when the row provably never submitted a check-in. */
internal fun isNonSignTarget(record:CheckRecord):Boolean {
    if(record.targetId==AUTHORIZATION_CHECK_TARGET) return true
    val target=record.targetName
    return target.contains(AUTHORIZATION_CHECK_NAME) || target.contains("角色检查") ||
        target.contains("角色绑定") || target.contains("绑定角色") ||
        LAST_RESORT_TARGETS.any{target.contains(it)}
}

/** A failed/needs_action row that is about authorization or role resolution. */
internal fun hasAuthorizationSignal(record:CheckRecord):Boolean {
    if(ROLE_CHECK_MARKERS.any{record.message.contains(it)}) return true
    return AUTHORIZATION_MARKERS.any{record.message.contains(it)}
}

/** Approval-scoped row arithmetic shared by the home summary and the repository-backed tests. */
internal fun targetKey(accountId:String,targetId:String)=accountId+"|"+targetId

/**
 * True when a record counts as "this game/role is done for today". Only official confirmation counts; an accepted
 * submission and an unknown outcome never do.
 */
internal fun isCompletedToday(record:CheckRecord):Boolean = present(record).group=="success"

/** True when the row is a submission the interface accepted but nobody confirmed (counts as pending confirmation). */
internal fun isAwaitingConfirmation(record:CheckRecord):Boolean=present(record).state==SignState.ACCEPTED

/** Kept for callers/tests that only need the classification. */
internal fun authorizationIssue(record:CheckRecord):Boolean = present(record).isAuthorizationIssue
