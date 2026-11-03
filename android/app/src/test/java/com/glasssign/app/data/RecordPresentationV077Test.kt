package com.glasssign.app.data

import org.junit.Assert.*
import org.junit.Test

/**
 * Pure-JVM test for the v0.7.7 presentation contract: seven semantic states, their fixed labels, the wire groups the
 * UI switches on, and the rule that provenance comes from explicit metadata only.
 *
 * No android.*: [present] reads strings only.
 */
class RecordPresentationV077Test {

    private fun record(
        targetId:String="genshin|u|s",
        targetName:String="原神 · 合成旅行者",
        status:String="failed",
        message:String="",
        resultSource:String="unknown",
        meta:ResultMeta=ResultMeta.UNKNOWN,
    )=CheckRecord("fixture-account",targetId,targetName,status,message,1,"2026-11-03T08:10:00+08:00","2026-11-03T08:09:00+08:00",resultSource,meta)

    // ---------------------------------------------------------------- labels

    @Test fun officialSuccessUsesTheSuccessLabel() {
        val p=present(record(status="success",message="签到成功，官方状态已确认",resultSource="official",
            meta=ResultMeta(source="official",verificationMethod="info",verifiedAt="2026-11-03T08:10:00+08:00")))
        assertEquals("签到成功",p.label)
        assertEquals(SignState.SUCCESS,p.state)
        assertEquals("success",p.group)
        assertEquals("官方成功响应 / 状态确认",p.source)
    }

    @Test fun alreadySignedUsesTheAlreadyLabel() {
        val p=present(record(status="already",message="官方响应确认今日已签",resultSource="official"))
        assertEquals("已签到",p.label)
        assertEquals(SignState.ALREADY,p.state)
        assertEquals("success",p.group)
    }

    @Test fun acceptedSubmissionIsNeverCountedOrLabelledAsSuccess() {
        val p=present(record(status="success",message="接口已接受提交",resultSource="accepted",
            meta=ResultMeta(source="accepted",providerCode="0")))
        assertEquals("已提交等待确认",p.label)
        assertEquals(SignState.ACCEPTED,p.state)
        assertEquals("submitted",p.group)
        assertFalse(isCompletedToday(record(status="success",resultSource="accepted")))
        assertTrue(isAwaitingConfirmation(record(status="success",resultSource="accepted")))
        assertTrue("technical fallback names the source",p.detail.contains("source accepted"))
    }

    @Test fun verifyingIsTransientAndNeverClaimsANewResult() {
        // No stored proof yet: the row says a read-only check is running and claims nothing.
        val bare=present(record(status="verifying",message="正在只读核对官方状态"))
        assertEquals("正在核验",bare.label)
        assertEquals(SignState.VERIFYING,bare.state)
        assertEquals("running",bare.group)
        assertFalse(isCompletedToday(record(status="verifying")))
        // With an earlier trusted proof the read-only marker must NOT erase it: the proof stays visible.
        val proven=present(record(status="verifying",message="正在只读核对官方状态",resultSource="official",meta=ResultMeta(source="official",verificationMethod="info")))
        assertEquals("正在核验",proven.label)
        assertEquals(SignState.VERIFYING,proven.state)
        assertEquals("running",proven.group)
    }

    @Test fun definiteFailureUsesItsOwnLabelOnlyWhenRetryableInformationExists() {
        val plain=present(record(status="failed",message="官方响应未确认成功",targetId="genshin|u|s"))
        assertEquals("失败",plain.label)
        assertEquals(SignState.FAILED,plain.state)
        assertEquals("attention",plain.group)
        assertFalse(plain.source.contains("可安全重试"))
        val retryable=present(record(status="failed",message="连接中断",meta=ResultMeta(source="unknown",retryable=true)))
        assertEquals("失败",retryable.label)
        assertTrue("explicit retryable is stated",retryable.source.contains("可安全重试"))
    }

    @Test fun authorizationRowsUseAuthRequiredAndNeverClaimASignIn() {
        val noRoles=present(record(targetId="no-roles",targetName="森空岛绑定角色",status="failed",message="授权失效，请重新导入"))
        assertEquals("需要重新授权",noRoles.label)
        assertEquals(SignState.AUTH_REQUIRED,noRoles.state)
        assertEquals("attention",noRoles.group)
        assertTrue(noRoles.isAuthorizationIssue)
        assertTrue(noRoles.source.contains("不提交签到"))

        val captcha=present(record(status="needs_action",message="请在官方应用完成人机验证"))
        assertEquals("需授权",captcha.label)
        assertEquals(SignState.AUTH_REQUIRED,captcha.state)
        assertEquals("attention",captcha.group)
    }

    @Test fun unknownOutcomeIsNeverSuccessAndNeverAuthFamily() {
        for(r in listOf(
            record(status="uncertain",message="提交后状态未确认，请在官方应用核对"),
            record(status="needs_action",message="上次执行中断，结果不确定；请先到官方应用核对"),
        )) {
            val p=present(r)
            assertEquals("未知",p.label)
            assertEquals(SignState.UNKNOWN,p.state)
            assertEquals("uncertain",p.group)
            assertFalse("an unknown submission is not an authorization problem",p.isAuthorizationIssue)
            assertFalse(isCompletedToday(r))
        }
    }

    @Test fun everySemanticStateMapsIntoTheSixWireGroups() {
        val states=mapOf(
            SignState.SUCCESS to "success",
            SignState.ALREADY to "success",
            SignState.ACCEPTED to "submitted",
            SignState.VERIFYING to "running",
            SignState.FAILED to "attention",
            SignState.AUTH_REQUIRED to "attention",
            SignState.UNKNOWN to "uncertain",
        )
        val allowed=setOf("success","attention","uncertain","submitted","pending","running")
        for((state,group) in states) {
            assertEquals(group,SignState.groupOf(state))
            assertTrue(group,group in allowed)
        }
    }

    // ---------------------------------------------------------------- explicit metadata only

    @Test fun newResultsAreNotInferredFromMessageSubstrings() {
        // The historical heuristic treated "接受" as accepted and "本机" as a local completion. With explicit
        // metadata those substrings must not change the classification.
        val officialWithWording=present(record(status="success",message="官方接口已接受签到，但官方状态已确认",resultSource="official"))
        assertEquals(SignState.SUCCESS,officialWithWording.state)
        assertEquals("签到成功",officialWithWording.label)
        val accepted=present(record(status="success",message="签到成功，官方状态已确认",resultSource="accepted"))
        assertEquals(SignState.ACCEPTED,accepted.state)
        assertEquals("已提交等待确认",accepted.label)
    }

    @Test fun metadataVerificationMethodAndHttpStatusAppearInTheDetail() {
        val p=present(record(status="failed",message="社区返回 HTTP 402",
            meta=ResultMeta(source="unknown",providerCode="protocol",httpStatus=402)))
        assertEquals("失败",p.label)
        assertTrue(p.detail,p.detail.contains("HTTP 402"))
        assertTrue(p.detail,p.detail.contains("code protocol"))
        assertFalse("a 402 is not an authorization row",p.isAuthorizationIssue)
    }

    @Test fun localCacheHitIsDisplayedAsALocallyCachedProof() {
        val p=present(record(status="already",message="本机已有当天完成记录（来源：official），本次未重新提交",
            resultSource="local_cache",meta=ResultMeta(source="local_cache",verificationMethod="local_cache")))
        assertEquals("已签到",p.label)
        assertEquals(SignState.ALREADY,p.state)
        assertTrue(p.source,p.source.contains("未重新查询官方"))
    }

    @Test fun legacyRowsKeepTheirHistoricalInterpretation() {
        // Rows written before schema 6: the wording is the only evidence available, and it stays a display decision.
        assertEquals(SignState.ACCEPTED,present(record(status="success",message="官方接口已接受签到")).state)
        assertEquals(SignState.ALREADY,present(record(status="already",message="已签到；本机已有当天成功记录")).state)
        assertFalse(
            "a legacy row must be labelled as a locally cached proof, not as an official confirmation",
            present(record(status="already",message="本机已有当天成功记录",resultSource="legacy_local")).label=="已签到"&&
                present(record(status="already",message="本机已有当天成功记录",resultSource="legacy_local")).source.contains("官方明确"),
        )
    }

    @Test fun communityRowsKeepTheirOwnVocabularyInEveryState() {
        val failed=present(record(targetId="community|1",targetName="塔吉多 · 社区签到",status="failed",message="塔吉多接口未确认成功"))
        assertEquals("社区任务未完成",failed.label)
        assertEquals(SignState.FAILED,failed.state)
        assertFalse(failed.label.contains("签到失败"))
        assertEquals("社区已提交等待确认",present(record(targetId="island|1",targetName="森空岛 · 社区检票",status="success",resultSource="accepted")).label)
        assertEquals("社区任务已完成",present(record(targetId="island|1",targetName="森空岛 · 社区检票",status="already",resultSource="official")).label)
        assertEquals("社区结果未知",present(record(targetId="community|1",targetName="塔吉多 · 社区签到",status="uncertain")).label)
    }

    @Test fun recordPresentationKeepsItsOriginalPositionalConstructor() {
        val legacy=RecordPresentation("待签到","pending","尚无完成记录")
        assertEquals("待签到",legacy.label)
        assertEquals("pending",legacy.group)
        assertFalse(legacy.isAuthorizationIssue)
        assertEquals(SignState.UNKNOWN,legacy.state)
        assertEquals("",legacy.detail)
    }

    @Test fun resultMetaRoundTripsAndDegradesSafely() {
        val meta=ResultMeta("official","0",200,"info","2026-11-03T08:10:00+08:00",false,"exec-1")
        assertEquals(meta,ResultMeta.decode(meta.encode()))
        assertEquals(ResultMeta.UNKNOWN,ResultMeta.decode(null))
        assertEquals(ResultMeta.UNKNOWN,ResultMeta.decode(""))
        assertEquals("broken metadata never throws",ResultMeta.UNKNOWN,ResultMeta.decode("{"))
        assertTrue(ResultMeta.UNKNOWN.isBlank)
    }
}
