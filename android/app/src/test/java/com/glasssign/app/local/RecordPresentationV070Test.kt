package com.glasssign.app.local

import com.glasssign.app.data.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Pure-JVM regression test for the real-device wording bug: a record whose target is
 * "授权与角色检查" (provider tagged / 异环, raw message "社区接口状态异常") was shown as "签到失败", and a
 * skland row with "授权失效，请重新导入" was shown as a sign failure. Neither row ever submitted a
 * check-in, so neither may be labelled as a sign result.
 *
 * No Android runtime is used on purpose: [present] only reads CheckRecord strings, and the app module does
 * NOT set `returnDefaultValues`, so this file must not touch android.* / org.json at all.
 *
 * Run with: android\gradlew -p android :app:testDebugUnitTest --tests "*RecordPresentation*"
 */
class RecordPresentationV070Test {

    private fun record(
        targetId: String = "game|1",
        targetName: String = "异环 · 合成角色",
        status: String = "failed",
        message: String = "",
        resultSource: String = "unknown",
    ) = CheckRecord(
        accountId = "fixture-account",
        targetId = targetId,
        targetName = targetName,
        status = status,
        message = message,
        attempts = 1,
        updatedAt = "2026-11-02T08:10:00+08:00",
        startedAt = "2026-11-02T08:09:00+08:00",
        resultSource = resultSource,
    )

    /** The exact pair the user saw on the device (values reconstructed from the bug report). */
    private val realDeviceRows = listOf(
        // tagged / 异环, discovery step: no check-in request is ever made by this step.
        record(targetId = "discovery", targetName = "授权与角色检查", status = "failed", message = "社区接口状态异常"),
        // skland, 401 from Protocol.kt: SignError("auth","授权失效，请重新导入") -> needs_action.
        record(
            targetId = "arknights|fixture-uid|fixture-gid",
            targetName = "明日方舟 · 合成博士",
            status = "needs_action",
            message = "授权失效，请重新导入",
        ),
    )

    @Test fun realDeviceAuthorizationRowsAreNeverLabelledAsASignIn() {
        for (r in realDeviceRows) {
            val p = present(r)
            assertFalse("label must not claim a sign-in: ${r.targetName} / ${r.message} -> ${p.label}", p.label.contains("签到"))
            assertFalse("a failed authorization row can never be success: ${p.label}", p.group == "success")
            assertNotEquals("the old misleading label must be gone", "签到失败", p.label)
            assertTrue("group must be one the UI handles", p.group in setOf("success", "attention", "uncertain", "submitted", "pending", "running"))
            assertTrue("authorization rows need action", p.group == "attention")
            assertTrue("source must not contradict the label", p.source.isNotBlank())
            assertTrue(p.isAuthorizationIssue)
        }
    }

    @Test fun discoveryRowSaysRoleCheckAndReimportRowSaysReauthorize() {
        // The discovery row provably submits nothing, so it may say so.
        val discovery = present(realDeviceRows[0])
        assertEquals("授权或角色检查未通过", discovery.label)
        assertTrue(discovery.source.contains("不提交签到"))

        // The skland row is a real sign target: it must ask for re-authorization but must NOT claim that
        // nothing was submitted, because a check-in may already have been attempted.
        val reimport = present(realDeviceRows[1])
        assertEquals("需要重新授权", reimport.label)
        assertTrue("must ask for re-authorization", reimport.source.contains("重新导入") || reimport.source.contains("重新授权"))
        assertFalse("a sign target cannot prove no submission", reimport.source.contains("未产生签到提交"))
        assertTrue(reimport.source.contains("核对官方记录"))
    }

    @Test fun signTargetWithAuthorizationFailureNeverClaimsNoSubmissionAndNeverSaysSignFailed() {
        // A real game target (not discovery / no-roles): a submission may have happened, so the wording must
        // stay conservative and must not reuse the "签到失败" label.
        val row = record(targetName = "异环 · 合成角色", status = "failed", message = "授权失效，请重新导入")
        val p = present(row)
        assertFalse(p.label.contains("签到"))
        assertEquals("attention", p.group)
        assertEquals("需要重新授权", p.label)
        assertTrue(p.source.contains("核对官方记录"))
        assertFalse("must not assert that no submission happened", p.source.contains("未产生签到提交"))
    }

    @Test fun allFourProvidersRoleEnumerationFailuresAreNotSignFailures() {
        val rows = listOf(
            record(targetName = "米游社角色绑定", status = "failed", message = "米游社接口未确认成功"),
            record(targetName = "森空岛绑定角色", status = "failed", message = "授权失效，请重新导入"),
            record(targetName = "塔吉多游戏角色", status = "failed", message = "塔吉多角色查询未完成（500），请检查授权或官方绑定状态"),
            record(targetName = "库街区游戏角色", status = "failed", message = "库街区角色查询失败"),
            record(targetName = "异环 · 合成角色", status = "failed", message = "塔吉多角色查询未完成（401）"),
            record(targetName = "明日方舟 · 合成博士", status = "needs_action", message = "请在官方应用绑定角色"),
            record(targetName = "异环 · 合成角色", status = "failed", message = "Cookie 已失效，缺少续期授权"),
            record(targetName = "绝区零 · 合成角色", status = "needs_action", message = "森空岛授权失效或角色查询失败"),
            // SignRepository.allowRejectedAuthRetry rewrites the row after re-authorization.
            record(targetName = "原神 · 合成旅行者", status = "failed", message = "授权已更新；前次请求被明确拒绝，可安全重试"),
        )
        for (r in rows) {
            val p = present(r)
            assertFalse("${r.targetName} / ${r.message} -> ${p.label}", p.label.contains("签到"))
            assertEquals("${r.targetName} / ${r.message}", "attention", p.group)
            assertTrue(p.isAuthorizationIssue)
        }
    }

    // ---------------------------------------------------------------- genuine sign rows keep their wording

    @Test fun officialSuccessStillSaysSignedOnce() {
        val official = present(record(status = "success", message = "签到成功，官方状态已确认", resultSource = "official"))
        assertEquals("已签到", official.label)
        assertEquals("success", official.group)
        assertEquals("官方成功响应 / 状态确认", official.source)
        assertFalse(official.isAuthorizationIssue)
    }

    @Test fun acceptedSubmissionStillSaysSubmissionAccepted() {
        val accepted = present(record(status = "success", message = "官方接口已接受签到，请以官方记录为准", resultSource = "accepted"))
        assertEquals("提交已接受", accepted.label)
        assertEquals("submitted", accepted.group)
    }

    @Test fun alreadySignedStaysSigned() {
        assertEquals("已签到", present(record(status = "already", message = "官方响应确认今日已签", resultSource = "official")).label)
        assertEquals("本机当天完成记录", present(record(status = "already", message = "已签到；本机已有当天成功记录，未重复提交")).source)
    }

    @Test fun legacyLocalRecordStaysDistinguished() {
        val legacy = present(record(status = "already", message = "已签到；本机已有当天成功记录，未重复提交", resultSource = "legacy_local"))
        assertEquals("本机已有完成记录", legacy.label)
        assertEquals("submitted", legacy.group)
        assertEquals("旧版本地完成记录；原始官方确认来源未知", legacy.source)
        assertFalse(legacy.isAuthorizationIssue)
    }

    @Test fun plainSignFailureKeepsTheExistingFailureWording() {
        val plain = present(record(targetName = "原神 · 合成旅行者", status = "failed", message = "官方响应未确认成功，请在官方应用核对"))
        assertEquals("签到失败", plain.label)
        assertEquals("attention", plain.group)
        assertEquals("官方未确认成功，请查看原因", plain.source)
        assertFalse(plain.isAuthorizationIssue)
    }

    @Test fun bareCommunityStatusCodeAloneIsNotTreatedAsAuthorizationForGameRows() {
        // Protocol.kt also answers "社区接口状态异常" for a POST check-in rejection; a plain game row must keep
        // the failure wording rather than be reclassified as an authorization problem.
        val game = present(record(targetId = "game|1289|fixture", targetName = "异环 · 合成角色", status = "failed", message = "社区接口状态异常"))
        assertEquals("签到失败", game.label)
    }

    @Test fun needsActionSignRowKeepsNeedsHandling() {
        val captcha = present(record(targetName = "原神 · 合成旅行者", status = "needs_action", message = "请在官方应用完成人机验证"))
        assertEquals("需要处理", captcha.label)
        assertEquals("attention", captcha.group)
        assertEquals("需要授权或用户操作", captcha.source)
    }

    // ---------------------------------------------------------------- preserved unknown/progress distinctions

    @Test fun uncertainStillMeansResultPending() {
        for (r in listOf(
            record(status = "uncertain", message = "提交后状态未确认，请在官方应用核对"),
            record(status = "needs_action", message = "提交结果不确定，未重发"),
            record(status = "needs_action", message = "上次执行中断，结果不确定；请先到官方应用核对"),
        )) {
            val p = present(r)
            assertEquals("结果待确认", p.label)
            assertEquals("uncertain", p.group)
            // An unknown submission must not be redirected into the authorization family by the new rule.
            assertFalse(p.isAuthorizationIssue)
        }
    }

    @Test fun inProgressAndWaitingStatesAreUnchanged() {
        assertEquals("正在签到", present(record(status = "running")).label)
        assertEquals("running", present(record(status = "running")).group)
        val waiting = present(record(targetName = "异环 · 合成角色", status = "waiting_network", message = "社区暂不可用，等待网络恢复后重试"))
        assertEquals("等待网络", waiting.label)
        assertEquals("attention", waiting.group)
        assertEquals("待签到", present(record(status = "queued")).label)
        assertEquals("pending", present(record(status = "queued")).group)
    }

    // ---------------------------------------------------------------- counter / filter compatibility

    @Test
    fun attentionFamilyCountsAuthorizationRowsAsNeedingUserActionNotAsConfirmedSignIns() {
        // Mirrors SignScheduler.notifyResult: ok = success, action = attention, uncertain = uncertain+submitted.
        val presented = (realDeviceRows + listOf(
            record(status = "success", message = "签到成功，官方状态已确认", resultSource = "official"),
            record(status = "failed", message = "官方响应未确认成功，请在官方应用核对"),
        )).map(::present)
        assertEquals("only the real success counts as confirmed", 1, presented.count { it.group == "success" })
        // Two authorization rows + the plain failed row all need user action, and none may read as a sign-in.
        assertEquals("authorization and failure rows must stay in the attention family", 3, presented.count { it.group == "attention" })
        assertEquals(0, presented.count { it.group in setOf("uncertain", "submitted") })
    }

    @Test fun dataClassKeepsItsOriginalThreeArgumentConstruction() {
        // ui/HoshioriScreen.kt and SignScheduler.kt only read label/group/source; the constructor must stay
        // backward compatible for any existing positional call.
        val legacy = RecordPresentation("待签到", "pending", "尚无完成记录")
        assertEquals("待签到", legacy.label)
        assertEquals("pending", legacy.group)
        assertEquals("尚无完成记录", legacy.source)
        assertFalse("isAuthorizationIssue defaults to false", legacy.isAuthorizationIssue)
    }

    @Test fun classificationDoesNotDependOnAnyLaterAdapterWording() {
        // Committed baseline (bebb61a) produces exactly these two strings. The fix must classify them as
        // authorization outcomes even if a future adapter appends a diagnostic suffix to the message, so the
        // rule matches the message prefix/substring and the target id only.
        val augmentedTagged = record(
            targetId = "discovery",
            targetName = "授权与角色检查",
            status = "failed",
            message = "社区接口状态异常（GET bbs-api.tajiduo.com · HTTP 418）",
        )
        assertFalse(present(augmentedTagged).label.contains("签到"))
        assertEquals("attention", present(augmentedTagged).group)

        // Baseline role-enumeration failure for 异环: only the auth/role word identifies it; no host/status suffix.
        val roleLookup = record(
            targetId = "game|1289|fixture",
            targetName = "异环 · 合成角色",
            status = "failed",
            message = "塔吉多角色查询未完成（500），请检查授权或官方绑定状态",
        )
        assertFalse(present(roleLookup).label.contains("签到"))
        assertEquals("attention", present(roleLookup).group)
        assertTrue(present(roleLookup).isAuthorizationIssue)
    }
}
