package com.glasssign.app.ui

import com.glasssign.app.data.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Pure-JVM feedback test (no android.*, no org.json, no real account fixture).
 *
 * It pins the answer to the v0.7.4 device report: the official app showed a successful check-in, the home page still
 * read like one generic error. The PRIMARY sample is the combination the lead read from the real device records and
 * handed over as the acceptance case (values reconstructed, no account identifiers):
 *
 *   原神 / 崩铁 / 绝区零     already  / official   -> 游戏已完成
 *   第二森空岛 明日方舟      success  / accepted   -> 提交已接受（不计为已签到）
 *   第二森空岛 社区检票      success  / accepted   -> 社区已提交（社区通道，独立统计）
 *   另一个森空岛 授权检查     needs_action / unknown -> 授权待处理（不是“游戏签到失败”）
 *   异环 游戏                already  / official   -> 游戏已完成
 *   异环 社区签到(塔吉多)     failed   / unknown    -> 社区任务未完成（不影响已完成的游戏）
 *
 * Every assert below is about what the user READS, i.e. the same mapping the home page renders.
 */
class SignFeedbackTest {

    // ---------------------------------------------------------------- fixtures (synthetic only)

    private val day = "2026-11-02"

    private fun account(
        id: String,
        provider: String = "miyoushe",
        nickname: String,
        enabled: Boolean = true,
        authState: String = "valid",
        authMessage: String = "",
        roleScope: Boolean = true,
        bindingPending: Boolean = false,
    ) = AuthAccount(id, provider, nickname, enabled, authState, authMessage, bindingPending, roleScope, emptyList())

    private fun binding(accountId: String, targetId: String, name: String, gameId: String = "") =
        GameBinding(accountId, targetId, gameId, name, true, true)

    private fun record(
        accountId: String,
        targetId: String,
        targetName: String,
        status: String,
        message: String = "",
        resultSource: String = "unknown",
        attempts: Int = 1,
    ) = CheckRecord(
        accountId = accountId, targetId = targetId, targetName = targetName, status = status,
        message = message, attempts = attempts,
        updatedAt = "${day}T08:10:00+08:00", startedAt = "${day}T08:09:00+08:00", resultSource = resultSource,
    )

    private fun feedback(
        accounts: List<AuthAccount>,
        tasks: List<GameBinding>,
        records: List<CheckRecord>,
        running: Boolean = false,
        queued: Boolean = false,
    ) = summarize(
        day = day, accounts = accounts, tasks = tasks, records = records,
        accountNames = accounts.associate { it.id to it.nickname },
        running = running, queued = queued,
    )

    // ---------------------------------------------------------------- the real-device combination

    /** The exact scenario from the delivery brief: 5 finished games, 2 problems, 3 lanes. */
    private fun deviceCombination():HomeFeedback {
        val accounts = listOf(
            account("miyoushe-primary", "miyoushe", "主号 · 合成验证"),
            account("skland-bound", "skland", "森空岛 · 合成验证"),
            account("skland-legacy", "skland", "森空岛旧号 · 合成验证", authState = "needs_action", authMessage = "授权失效，请重新导入"),
            account("tagged-nte", "tagged", "塔吉多 · 合成验证"),
        )
        val tasks = listOf(
            binding("miyoushe-primary", "genshin|fixture-main|server", "原神 · 合成旅行者", "genshin"),
            binding("miyoushe-primary", "honkai_sr|fixture-main|server", "崩铁 · 合成开拓者", "honkai_sr"),
            binding("miyoushe-primary", "zzz|fixture-main|server", "绝区零 · 合成绳匠", "zzz"),
            binding("skland-bound", "arknights|fixture-uid|fixture-gid", "明日方舟 · 合成博士", "arknights"),
            binding("tagged-nte", "game|1289|fixture-role", "异环 · 合成角色", "nte"),
        )
        val records = listOf(
            record("miyoushe-primary", "genshin|fixture-main|server", "原神 · 合成旅行者", "already", "官方响应确认今日已签", "official"),
            record("miyoushe-primary", "honkai_sr|fixture-main|server", "崩铁 · 合成开拓者", "already", "官方响应确认今日已签", "official"),
            record("miyoushe-primary", "zzz|fixture-main|server", "绝区零 · 合成绳匠", "already", "官方响应确认今日已签", "official"),
            // 第二森空岛 re-bound successfully: accepted by the interface, no independent status verification.
            record("skland-bound", "arknights|fixture-uid|fixture-gid", "明日方舟 · 合成博士", "success", "官方接口已接受签到，请以官方记录为准", "accepted"),
            record("skland-bound", "island|1", "森空岛 · 社区检票", "success", "官方接口已接受签到，请以官方记录为准", "accepted"),
            // The older skland account still has the discovery failure (authorization), not a sign failure.
            record("skland-legacy", "discovery", "授权与角色检查", "needs_action", "授权失效，请重新导入", "unknown"),
            record("tagged-nte", "game|1289|fixture-role", "异环 · 合成角色", "already", "游戏状态确认今日已签", "official"),
            record("tagged-nte", "community|1", "塔吉多 · 社区签到", "failed", "塔吉多接口未确认成功", "unknown"),
        )
        return feedback(accounts, tasks, records)
    }

    // ===== 1. games succeeded + community failed =====================================================

    @Test fun finishedGamesAreReportedAsDoneEvenWhenACommunityTaskFailed() {
        val f = deviceCombination()
        // The game lane is COMPLETE; the community failure may not touch it.
        assertEquals(5, f.gameRows.size)
        // 原神 / 崩铁 / 绝区零 / 异环 all carry already+official; 明日方舟 is success+accepted (submitted).
        assertEquals("four already/official games", 4, f.gameRows.count { it.state == RowState.COMPLETED && it.source == "官方明确已签到" })
        assertEquals("an accepted game submission must NOT be shown as 已签到", 1, f.gameRows.count { it.state == RowState.SUBMITTED })
        assertEquals("the tagged game is completed", 1, f.gameRows.count { it.state == RowState.COMPLETED && it.title.contains("异环") })
        assertEquals("no game row may be marked failed by the community failure", 0, f.gameRows.count { it.state == RowState.ATTENTION })
        assertEquals("0 pending games", 0, f.pendingCount)
        assertEquals(5, f.taskCount)
        // The headline count uses ONLY confirmed games (community tasks and accepted submissions are separate).
        assertTrue(f.headline, f.headline == "已签到 4 / 5 个游戏")
        assertEquals(4, f.completedCount)
    }

    @Test fun communityFailureStaysVisibleInItsOwnLane() {
        val f = deviceCombination()
        assertEquals("one community row from 森空岛 + one from 塔吉多", 2, f.communityRows.size)
        assertEquals(RowState.SUBMITTED, f.communityRows.first { it.targetId.startsWith("island|") }.state)
        val failed = f.communityRows.first { it.targetId.startsWith("community|") }
        assertEquals(RowState.ATTENTION, failed.state)
        // The row is a community row (lane + title), and its detail names the failure and the retry scope. Its short
        // label is the shared "未完成" wording; the lane heading supplies "社区任务", so the pair never reads as a game.
        assertEquals(com.glasssign.app.data.Lane.COMMUNITY, failed.lane)
        assertTrue("the community title must name the community task: ${failed.title}", failed.title.contains("社区"))
        assertTrue("the row must say the finished games are unaffected: ${failed.detail}", failed.detail.contains("不影响") || failed.detail.contains("可安全重试这一项"))
        // The community failure is its own lane, so the page can never hide it, and never green it.
        assertTrue(f.breakdown, f.breakdown.contains("社区任务 已完成 0·待复核 1·未完成 1"))
        // A fully-split breakdown: the accepted community submission is not a completion, the failed one is open.
        assertEquals("a submitted community row is not a completed community task", 0,
            f.communityRows.count { it.state == RowState.COMPLETED })
    }

    // ===== 2. authorization check failed ============================================================

    @Test fun authorizationFailureIsNeverCalledAGameSignFailure() {
        val f = deviceCombination()
        assertEquals("exactly one authorization item", 1, f.authRows.size)
        val auth = f.authRows.single()
        assertEquals(RowState.ATTENTION, auth.state)
        assertEquals("需要重新授权", auth.label)
        assertFalse("an authorization row may not say 签到失败: ${auth.label}", auth.label.contains("签到"))
        assertTrue(auth.detail, auth.detail.contains("不提交签到"))
        assertTrue("must explain the other accounts/games are unaffected: ${auth.detail}", auth.detail.contains("不受影响"))
        assertEquals("the fix must be a re-authorization entry", FixAction.ACCOUNT, auth.fix)
        // The account's own rows are untouched by the authorization problem.
        assertEquals(4, f.completedCount)
    }

    @Test fun healthyAccountsProduceNoAuthorizationAlarm() {
        // The renewed 第二森空岛 account is valid, so no auth row -- even though an OLD discovery failure could still
        // be on disk. The account state is authoritative (the v0.7.4 "still broken after re-binding" bug).
        val accounts = listOf(account("skland-fixed", "skland", "森空岛已修复", authState = "valid"))
        val tasks = listOf(binding("skland-fixed", "arknights|u|g", "明日方舟 · 合成博士", "arknights"))
        val records = listOf(
            record("skland-fixed", "discovery", "授权与角色检查", "needs_action", "授权失效，请重新导入", "unknown"),
            record("skland-fixed", "arknights|u|g", "明日方舟 · 合成博士", "success", "官方接口已接受签到", "accepted"),
        )
        val f = feedback(accounts, tasks, records)
        assertEquals("a stale discovery record may not keep a re-authorized account red", 0, f.authRows.size)
        assertEquals(RowState.SUBMITTED, f.gameRows.single().state)
        assertTrue(f.phaseLine, f.phaseLine.contains("提交已接受"))
    }

    // ===== 3. uncertain / submitted =================================================================

    @Test fun uncertainIsNeverSuccessAndOffersOnlyAReadOnlyCheck() {
        val accounts = listOf(account("a", "miyoushe", "主号"))
        val tasks = listOf(binding("a", "genshin|u|s", "原神 · 合成旅行者", "genshin"))
        val f = feedback(accounts, tasks, listOf(
            record("a", "genshin|u|s", "原神 · 合成旅行者", "uncertain", "提交后状态未确认，请在官方应用核对"),
        ))
        val row = f.gameRows.single()
        assertEquals(RowState.UNCERTAIN, row.state)
        assertEquals("结果待确认", row.label)
        assertEquals("the only safe action is the read-only reconcile", FixAction.RECONCILE, row.fix)
        assertFalse(row.detail, row.detail.contains("已完成"))
        assertEquals(0, f.completedCount)
        assertEquals(1, f.counts["uncertain"])
        assertEquals(Phase.PROBLEM, f.phase)
        assertEquals("result summary must list it as unconfirmed", true, f.breakdown.contains("结果待确认 1"))
    }

    @Test fun submittedIsOnlySubmittedNeverCompleted() {
        val accounts = listOf(account("a", "skland", "森空岛"))
        val tasks = listOf(binding("a", "arknights|u|g", "明日方舟 · 合成博士", "arknights"))
        val f = feedback(accounts, tasks, listOf(
            record("a", "arknights|u|g", "明日方舟 · 合成博士", "success", "官方接口已接受签到，请以官方记录为准", "accepted"),
        ))
        val row = f.gameRows.single()
        assertEquals(RowState.SUBMITTED, row.state)
        assertEquals("已提交等待确认", row.label)
        assertEquals(0, f.completedCount)
        assertEquals("headline must show 0 signed", "已签到 0 / 1 个游戏", f.headline)
        assertTrue(f.breakdown, f.breakdown.contains("提交已接受 1"))
        // An accepted submission cannot make the headline claim that every check-in is complete.
        assertEquals(Phase.PARTIAL, f.phase)
        assertFalse("a submitted row must not be counted as completed", f.attention)
    }

    @Test fun unknownOutcomeNeedsActionStaysUncertain() {
        val accounts = listOf(account("a", "miyoushe", "主号"))
        val tasks = listOf(binding("a", "genshin|u|s", "原神 · 合成旅行者", "genshin"))
        val f = feedback(accounts, tasks, listOf(
            record("a", "genshin|u|s", "原神 · 合成旅行者", "needs_action", "上次执行中断，结果不确定；请先到官方应用核对"),
        ))
        assertEquals(RowState.UNCERTAIN, f.gameRows.single().state)
        assertEquals(0, f.completedCount)
        assertEquals(1, f.counts["uncertain"])
    }

    @Test fun runningRowWithoutAnActiveWorkerBecomesUncertainNotSuccess() {
        val accounts = listOf(account("a", "miyoushe", "主号"))
        val tasks = listOf(binding("a", "genshin|u|s", "原神 · 合成旅行者", "genshin"))
        val interrupted = feedback(accounts, tasks, listOf(record("a", "genshin|u|s", "原神 · 合成旅行者", "running")))
        assertEquals("a killed run has an unknown outcome", RowState.UNCERTAIN, interrupted.gameRows.single().state)
        assertEquals(FixAction.RECONCILE, interrupted.gameRows.single().fix)
        // While the worker is active the same row reads 执行中.
        val active = feedback(accounts, tasks, listOf(record("a", "genshin|u|s", "原神 · 合成旅行者", "running")), running = true)
        assertEquals(RowState.RUNNING, active.gameRows.single().state)
        assertEquals(Phase.RUNNING, active.phase)
        assertTrue(active.title, active.title.contains("正在"))
    }

    // ===== 4. everything signed =====================================================================

    @Test fun allGamesSignedIsACleanCompletedState() {
        val accounts = listOf(account("a", "miyoushe", "主号"), account("b", "skland", "森空岛"))
        val tasks = listOf(
            binding("a", "genshin|u|s", "原神 · 合成旅行者", "genshin"),
            binding("b", "arknights|u|g", "明日方舟 · 合成博士", "arknights"),
        )
        val allOfficial = feedback(accounts, tasks, listOf(
            record("a", "genshin|u|s", "原神 · 合成旅行者", "success", "签到成功，官方状态已确认", "official"),
            record("b", "arknights|u|g", "明日方舟 · 合成博士", "already", "官方提示今日已签", "official"),
        ))
        assertEquals(Phase.DONE, allOfficial.phase)
        assertEquals("已签到 2 / 2 个游戏", allOfficial.headline)
        assertFalse("a fully signed day is not an attention state", allOfficial.attention)
        assertEquals("今日已全部完成", allOfficial.title)
        assertEquals(0, allOfficial.counts["hasAction"])
        // A clean run still produces a readable summary, and it does not invent a problem.
        val result = runResult(allOfficial, "scheduled", "${day}T08:12:00+08:00", 1)
        assertNotNull(result)
        assertFalse(result!!.error)
        assertTrue(result.title, result.title.contains("完成"))
        assertTrue(result.summary.first(), result.summary.first().contains("2 / 2"))
    }

    @Test fun localOnlyCompletionIsSubmittedNotSuccess() {
        val accounts = listOf(account("a", "miyoushe", "主号"))
        val tasks = listOf(binding("a", "genshin|u|s", "原神 · 合成旅行者", "genshin"))
        val f = feedback(accounts, tasks, listOf(
            record("a", "genshin|u|s", "原神 · 合成旅行者", "already", "已签到；本机已有当天成功记录，未重复提交", "legacy_local"),
        ))
        // The requested contract: the label is a completed check-in, but the SOURCE must not claim a fresh official
        // query — it is this device's own record.
        assertEquals(RowState.COMPLETED, f.gameRows.single().state)
        assertEquals(1, f.completedCount)
        assertTrue(f.gameRows.single().source, f.gameRows.single().source.contains("本机"))
    }

    // ===== 5. partial success =======================================================================

    @Test fun partialSuccessSeparatesDoneFromUnfinished() {
        val accounts = listOf(account("a", "miyoushe", "主号"))
        val tasks = listOf(
            binding("a", "genshin|u|s", "原神 · 合成旅行者", "genshin"),
            binding("a", "zzz|u|s", "绝区零 · 合成绳匠", "zzz"),
        )
        val f = feedback(accounts, tasks, listOf(
            record("a", "genshin|u|s", "原神 · 合成旅行者", "success", "签到成功，官方状态已确认", "official"),
            record("a", "zzz|u|s", "绝区零 · 合成绳匠", "failed", "官方响应未确认成功，请在官方应用核对"),
        ))
        assertEquals(1, f.completedCount)
        assertEquals(1, f.counts["attention"])
        assertEquals("已签到 1 / 2 个游戏", f.headline)
        assertEquals(Phase.PROBLEM, f.phase)
        val failedRow = f.gameRows.first { it.targetId.startsWith("zzz") }
        assertEquals(RowState.ATTENTION, failedRow.state)
        assertEquals("an unexplained response must be checked without resubmission", FixAction.RECONCILE, failedRow.fix)
        assertTrue("the finished game keeps its own row", f.gameRows.any { it.state == RowState.COMPLETED })
        assertTrue("the row must state what is known", failedRow.detail.contains("尚未确认"))
    }

    @Test fun communityFailureDoesNotTurnAPartialDayIntoAGameFailure() {
        val accounts = listOf(account("a", "tagged", "塔吉多"))
        val tasks = listOf(
            binding("a", "game|1289|role", "异环 · 合成角色", "nte"),
            binding("a", "community|1", "塔吉多 · 社区签到", ""),
        )
        val f = feedback(accounts, tasks, listOf(
            record("a", "game|1289|role", "异环 · 合成角色", "already", "游戏状态确认今日已签", "official"),
            record("a", "community|1", "塔吉多 · 社区签到", "failed", "塔吉多接口未确认成功"),
        ))
        assertEquals(1, f.completedCount)
        // "attention" counts GAME rows only, so the community failure never lands in it.
        assertEquals(0, f.counts["attention"])
        assertEquals("the community task is its own row", 1, f.counts["community"])
        assertEquals(1, f.counts["communityAttention"])
        assertEquals("only the game counts in the headline", "已签到 1 / 1 个游戏", f.headline)
        assertEquals(RowState.COMPLETED, f.gameRows.single().state)
        assertEquals(RowState.ATTENTION, f.communityRows.single().state)
        // The day is NOT clean: the phase must stay problem because a community item still needs the user.
        assertEquals(Phase.PROBLEM, f.phase)
    }

    /**
     * REGRESSION: a community-tagged target that arrives through the binding list must never be counted as a game.
     * Before this guard, `summarize` accepted every binding into the game lane, so a `community|…` row inflated
     * "已签到 X/Y 个游戏" and its failure surfaced as a game failure -- the exact mix-up this pass removes.
     */
    @Test fun aCommunityBindingNeverEntersTheGameLane() {
        val accounts = listOf(account("a", "tagged", "塔吉多"))
        val tasks = listOf(
            binding("a", "game|1289|role", "异环 · 合成角色", "nte"),
            binding("a", "community|1", "塔吉多 · 社区签到"),   // community tag leaking into the binding list
            binding("a", "island|1", "森空岛 · 社区检票"),
        )
        val f = feedback(accounts, tasks, listOf(
            record("a", "game|1289|role", "异环 · 合成角色", "already", "游戏状态确认今日已签", "official"),
            record("a", "community|1", "塔吉多 · 社区签到", "failed", "塔吉多接口未确认成功"),
            record("a", "island|1", "森空岛 · 社区检票", "success", "接口已接受", "accepted"),
        ))
        assertEquals("only the real game is a game row", 1, f.gameRows.size)
        assertEquals("game|1289|role", f.gameRows.single().targetId)
        assertEquals("the denominator is 1, not 3", 1, f.taskCount)
        assertEquals("已签到 1 / 1 个游戏", f.headline)
        assertEquals("the community failure is not a game failure", 0, f.counts["attention"])
        assertEquals("both community targets are community rows", 2, f.communityRows.size)
        assertEquals(1, f.counts["communityAttention"])
    }

    // ===== runnability scope: enabled bindings, today only ==========================================

    @Test fun onlyEnabledBindingsAndTodaysRecordsCount() {
        val accounts = listOf(account("a", "miyoushe", "启用"), account("b", "miyoushe", "暂停", enabled = false))
        val tasks = listOf(binding("a", "genshin|u|s", "原神 · 合成旅行者", "genshin"))
        val f = feedback(accounts, tasks, listOf(
            record("a", "genshin|u|s", "原神 · 合成旅行者", "success", "签到成功，官方状态已确认", "official"),
            // A record of a paused account is not today's status: it must never appear in any lane.
            record("b", "genshin|x|y", "原神 · 另一角色", "failed", "旧失败"),
        ))
        assertEquals(1, f.taskCount)
        assertEquals("paused account records are not a signed game", 1, f.completedCount)
        assertEquals("the enabled binding is the only game row", 1, f.gameRows.size)
        assertEquals("the one enabled task is confirmed done", Phase.DONE, f.phase)
        assertTrue("the disabled account must not add rows to any lane", f.rows.none { it.accountId == "b" })
    }

    @Test fun pausedAccountReportsPausedInsteadOfFailure() {
        val accounts = listOf(account("a", "miyoushe", "暂停", enabled = false))
        val f = feedback(accounts, emptyList(), emptyList())
        assertEquals(Phase.PAUSED, f.phase)
        assertEquals("签到已暂停", f.headline)
        assertFalse(f.attention)
    }

    @Test fun unsynchronizedAccountIsInformationalAndNotAPendingTask() {
        // An enabled account that HAS a role scope but whose bindings were never stored: the "角色待同步" case.
        val accounts = listOf(account("a", "miyoushe", "旧账号", roleScope = true))
        val f = feedback(accounts, emptyList(), emptyList())
        assertEquals(0, f.taskCount)
        assertEquals("角色待同步", f.headline)
        // The account is enabled with no roles yet, so it lands in the informational list (never a pending task)...
        assertEquals(1, f.unsynchronizedAccounts.size)
        // ...and a `valid` auth state raises no authorization alarm: nothing to fix, nothing red.
        assertEquals(0, f.authRows.size)
        assertFalse(f.attention)
    }

    // ===== the run result summary ===================================================================

    @Test fun runResultOfTheDeviceCombinationNamesEachProblemOnce() {
        val f = deviceCombination()
        val result = runResult(f, "manual", "${day}T08:12:00+08:00", 3)!!
        assertFalse(result.error)
        val text = result.summary.joinToString("|")
        assertTrue(text, text.contains("游戏签到：4 / 5"))
        assertTrue("accepted must be reported separately", text.contains("提交被接受待复核 1"))
        assertTrue("authorization must be named", text.contains("授权待处理"))
        assertTrue("community reported in its own lane with all three buckets: $text",
            text.contains("社区任务：已完成 0，提交待复核 1，未完成 1"))
        assertTrue("the summary must not call the community failure a game failure", !text.contains("游戏失败"))
        assertEquals(3, result.sequence)
        assertEquals("manual", result.trigger)
    }

    @Test fun workerErrorBecomesAnExplicitCauseNotAGenericMessage() {
        val f = deviceCombination()
        val result = runResult(f, "scheduled", "${day}T08:13:00+08:00", 2, workerError = "任务等待超时，请查看记录；结果不确定的任务不会自动重发")!!
        assertTrue(result.error)
        assertTrue(result.summary.first(), result.summary.first().contains("超时"))
        assertEquals("上次执行未完成", result.title)
    }

    @Test fun noRunObservedMeansNoResultCard() {
        assertNull(runResult(deviceCombination(), "manual", "${day}T08:12:00+08:00", 0))
    }

    // ===== run bookkeeping (pure) ===================================================================

    @Test fun runBookCountsOnlyNewFinishedTimestamps() {
        val empty = emptyBook()
        // First observation after install: adopt, do not invent a result.
        val adopted = observeRun(empty, "${day}T08:00:00+08:00", "${day}T08:10:00+08:00", running = false)
        assertEquals(0, adopted.sequence)
        assertEquals("${day}T08:10:00+08:00", adopted.lastSeenFinished)
        // The same timestamp never counts twice (refresh loop).
        assertEquals(0, observeRun(adopted, "${day}T08:00:00+08:00", "${day}T08:10:00+08:00", running = false).sequence)
        // A new finished timestamp is one run.
        val second = observeRun(adopted, "${day}T09:00:00+08:00", "${day}T09:10:00+08:00", running = false)
        assertEquals(1, second.sequence)
        // While the worker is still running, the previous finished value is not a new run.
        val queued = observeRun(second, "${day}T10:00:00+08:00", "${day}T09:10:00+08:00", running = true)
        assertEquals(1, queued.sequence)
        assertEquals("a start/finish pair of different runs is ignored", 1,
            observeRun(second, "${day}T10:00:00+08:00", "${day}T08:10:00+08:00", running = false).sequence)
    }

    @Test fun runBookRoundTripsThroughItsSerializedForm() {
        val book = RunBook(7, "${day}T08:10:00+08:00")
        assertEquals(book, readBook(serializeBook(book)))
        assertEquals(emptyBook(), readBook(null))
        assertEquals("garbage is ignored, never a crash", emptyBook(), readBook("{"))
    }

    // ===== lane arithmetic used by the cards ========================================================

    @Test fun summariesNeverReportAPartialLaneAsAllDone() {
        // summaryText/worstGroup live in ui/SignFeedback.kt (a Compose file), so this test re-states the rule they
        // must obey, on the same data: a lane containing anything not completed is never all-done.
        val f = deviceCombination()
        assertEquals(4, f.completedCount)
        assertEquals(1, f.counts["submitted"])
        assertEquals(2, f.counts["community"])
        assertTrue("the games lane is not all-done while a submission is unverified",
            f.gameRows.any { it.state != RowState.COMPLETED })
        assertTrue("the community lane contains a visible failure",
            f.communityRows.any { it.state == RowState.ATTENTION })
    }

    @Test fun presentKeepsTheVocrCommitContractForTheExistingRows() {
        // RecordPresentationV070Test owns the detailed contract; this guards the three properties the UI reads.
        val p = present(record("a", "community|1", "塔吉多 · 社区签到", "failed", "塔吉多接口未确认成功"))
        assertEquals("社区任务未完成", p.label)
        assertEquals("attention", p.group)
        assertFalse(p.label.contains("签到失败"))
        val q = present(record("a", "discovery", "授权与角色检查", "needs_action", "授权失效，请重新导入"))
        assertFalse(q.label.contains("签到"))
        assertEquals("attention", q.group)
        assertTrue(q.isAuthorizationIssue)
    }
}
