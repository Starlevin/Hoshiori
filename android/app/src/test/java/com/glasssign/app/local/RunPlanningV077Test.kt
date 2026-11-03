package com.glasssign.app.local

import com.glasssign.app.data.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Pure-JVM test for the v0.7.7 run-planning policy — the fix for the main critical bug: a click used to call
 * `adapter.targets()` (role discovery, which renews tokens with a request) and only afterwards notice that every task
 * was already done.
 *
 * No android.*, no network, no database: [planAccount] is a pure function of already-stored rows.
 */
class RunPlanningV077Test {

    private val day = "2026-11-03"

    private fun binding(account:String,target:String,game:String="genshin",enabled:Boolean=true,auto:Boolean=true) =
        GameBinding(account,target,game,target,enabled,auto)

    private fun record(account:String,target:String,status:String,source:String="unknown",meta:ResultMeta=ResultMeta.UNKNOWN) =
        CheckRecord(account,target,target,status,"",1,"${day}T08:10:00+08:00","",source,meta)

    private fun guardRow(account:String,target:String,status:String,source:String="unknown") =
        CheckRecord(account,target,"",status,"",1,"","",source,ResultMeta(source=source))

    private val account = AccountPlanInput("acct",listOf("genshin"),true)

    // ---------------------------------------------------------------- the critical skip

    @Test fun fullyCompletedAccountHasNothingTodoAndIsNotPlanned() {
        val bindings = listOf(binding("acct","genshin|u|s"),binding("acct","island|1",""))
        val roles = listOf(RoleChoice("island|1","","森空岛 · 社区检票"))
        val records = listOf(
            record("acct","genshin|u|s","already","official",ResultMeta(source="official")),
            record("acct","island|1","success","accepted",ResultMeta(source="accepted")),
        )
        val plan = planAccount(account,bindings,roles,records,emptyList(),scheduled=false)
        assertTrue(plan.trustedScope)
        assertFalse(plan.hasWork)
        assertTrue("every owned task has accepted-or-official evidence",plan.nothingTodo)
        assertEquals(1,plan.satisfiedCount)
        assertEquals(2,plan.skipped.size)
    }

    @Test fun aFreshlyEnabledGameIsAlwaysPlannedAndNeverSkipped() {
        val bindings = listOf(binding("acct","genshin|u|s"),binding("acct","zzz|u|s","zzz"))
        val records = listOf(record("acct","genshin|u|s","already","official"))
        val plan = planAccount(account,bindings,emptyList(),records,emptyList(),scheduled=false)
        assertTrue(plan.hasWork)
        assertEquals(listOf("zzz|u|s"),plan.tasks.map{it.target.id})
        assertFalse(plan.nothingTodo)
    }

    @Test fun aGameTheUserNeverEnabledIsNotInventedFromRecordsOrDiscovery() {
        val bindings = listOf(binding("acct","genshin|u|s"))
        // A discovered but unbound GAME role must not be signed: only explicit bindings define the scope.
        val roles = listOf(RoleChoice("honkai_sr|u|s","honkai_sr","崩铁 · 合成开拓者"))
        val records = listOf(record("acct","zzz|u|s","failed"))
        val plan = planAccount(account,bindings,roles,records,emptyList(),scheduled=false)
        assertEquals(listOf("genshin|u|s"),plan.tasks.map{it.target.id})
        assertEquals("an unbound role adds no task",1,plan.tasks.size+plan.skipped.size)
    }

    @Test fun storedUnknownOutcomeBlocksSubmissionButKeepsTheAccountOpen() {
        val bindings = listOf(binding("acct","genshin|u|s"))
        val records = listOf(record("acct","genshin|u|s","uncertain"))
        val plan = planAccount(account,bindings,emptyList(),records,emptyList(),scheduled=false)
        assertFalse("nothing may be submitted",plan.hasWork)
        assertFalse("an unknown outcome is not 'nothing to do'",plan.nothingTodo)
        assertTrue(plan.queryOnly)
        assertEquals(listOf("genshin|u|s"),plan.awaitingConfirmation.map{it.target.id})
        assertEquals(TaskSkip.AWAITING_CONFIRMATION,plan.skipped.single().second)
    }

    @Test fun acceptedSubmissionCountsAsHandledButNotAsCompleted() {
        val bindings = listOf(binding("acct","arknights|u|g","arknights"))
        val records = listOf(record("acct","arknights|u|g","success","accepted",ResultMeta(source="accepted")))
        val evidence = evidenceOf(records.single(),null)!!
        assertTrue("accepted must block a second submission",evidence.blocksSubmission)
        assertFalse("accepted is not a completed check-in",evidence.isSatisfied)
        val plan = planAccount(account,bindings,emptyList(),records,emptyList(),scheduled=false)
        assertFalse(plan.hasWork)
        assertTrue(plan.queryOnly)
        assertEquals("accepted evidence is handled (blocks, not satisfied)",0,plan.satisfiedCount)
        assertTrue("an accepted submission is handled, so no discovery runs again",plan.nothingTodo)
    }

    @Test fun guardsKeepTheProtectionAfterHistoryDeletion() {
        val bindings = listOf(binding("acct","genshin|u|s"))
        val guards = listOf(guardRow("acct","genshin|u|s","already",SOURCE_OFFICIAL))
        val plan = planAccount(account,bindings,emptyList(),emptyList(),guards,scheduled=false)
        assertFalse("a deleted history row must not unlock a submission",plan.hasWork)
        assertTrue(plan.nothingTodo)
    }

    @Test fun aDefiniteFailureIsNotTreatedAsCompleted() {
        val bindings = listOf(binding("acct","genshin|u|s"))
        val plan = planAccount(account,bindings,emptyList(),listOf(record("acct","genshin|u|s","failed").copy(resultMeta=com.glasssign.app.data.ResultMeta(retryable=true))),emptyList(),scheduled=false)
        assertTrue("a plain failure is never 'done'",plan.hasWork)
        assertEquals(listOf("genshin|u|s"),plan.tasks.map{it.target.id})
        assertFalse(plan.nothingTodo)
    }

    @Test fun disabledBindingsAndScheduledAutoOffAreSkippedWithDistinctReasons() {
        val bindings = listOf(
            binding("acct","genshin|u|s",enabled=false),
            binding("acct","shz|u|s","zzz",enabled=true,auto=false),
        )
        val manual = planAccount(account,bindings,emptyList(),emptyList(),emptyList(),scheduled=false)
        assertEquals(listOf(TaskSkip.DISABLED),manual.skipped.map{it.second})
        assertEquals(listOf("shz|u|s"),manual.tasks.map{it.target.id})
        val scheduled = planAccount(account,bindings,emptyList(),emptyList(),emptyList(),scheduled=true)
        assertFalse(scheduled.hasWork)
        assertEquals(setOf(TaskSkip.DISABLED,TaskSkip.AUTO_OFF),scheduled.skipped.map{it.second}.toSet())
    }

    @Test fun anAccountWithoutConfirmedBindingsIsNotTrusted() {
        val plan = planAccount(AccountPlanInput("acct",emptyList(),false),emptyList(),emptyList(),emptyList(),emptyList(),scheduled=false)
        assertFalse(plan.trustedScope)
        assertFalse(plan.nothingTodo)
        assertTrue(plan.skipped.isEmpty())
    }

    // ---------------------------------------------------------------- provenance helpers

    @Test fun legacyWordingIsOnlyInterpretedForAlreadyStoredRows() {
        assertEquals("a legacy success is never promoted to accepted",SOURCE_LEGACY,legacySourceOf(record("a","t","success","unknown").copy(message="官方接口已接受签到")))
        assertEquals(SOURCE_LEGACY_LOCAL,legacySourceOf(record("a","t","already","unknown").copy(message="本机已有当天成功记录")))
        assertEquals(SOURCE_LEGACY,legacySourceOf(record("a","t","success","legacy")))
        assertEquals("a declared non-legacy column wins",SOURCE_OFFICIAL,legacySourceOf(record("a","t","success","official")))
        assertEquals(SOURCE_OFFICIAL,legacySourceOf(record("a","t","success","legacy").copy(
            resultMeta=ResultMeta(source="official"))))
    }

    @Test fun provenanceReadsMetadataFirstThenFallsBack() {
        val explicit = CheckRecord("a","t","t","success","",1,"","",SOURCE_OFFICIAL,ResultMeta(source=SOURCE_ACCEPTED))
        assertEquals(SOURCE_ACCEPTED,provenanceOf(explicit).source)
        val legacyRow = CheckRecord("a","t","t","success","官方接口已接受签到",1,"","",SOURCE_UNKNOWN)
        assertEquals("legacy rows stay legacy; only stored evidence is described",SOURCE_LEGACY,provenanceOf(legacyRow).source)
    }

    @Test fun storedEvidenceDistinguishesBlockingFromCompletion() {
        assertNull(evidenceOf(null,null))
        val unknown = evidenceOf(record("a","t","uncertain"),null)!!
        assertTrue(unknown.blocksSubmission)
        assertFalse(unknown.isSatisfied)
        assertTrue(unknown.awaitingConfirmation)
        val done = evidenceOf(record("a","t","already",SOURCE_OFFICIAL),null)!!
        assertTrue(done.blocksSubmission)
        assertTrue(done.isSatisfied)
        assertFalse(done.awaitingConfirmation)
        val failure = evidenceOf(record("a","t","failed"),null)!!
        assertFalse("a definite failure does not block",failure.blocksSubmission)
    }

    @Test fun verifyingBlocksANewSubmission() {
        val bindings = listOf(binding("acct","genshin|u|s"))
        val plan = planAccount(account,bindings,emptyList(),listOf(record("acct","genshin|u|s","verifying")),emptyList(),scheduled=false)
        assertFalse(plan.hasWork)
        assertTrue("verifying" in BLOCKING_STATUSES)
    }

    // ---------------------------------------------------------------- scheduler retry policy

    @Test fun retryPolicyIsExplicitAndNeverAutomaticForUnknownFailures() {
        assertTrue(SchedulePolicy.shouldRetryRun(waiting=true,retryPending=false,attempt=0))
        assertTrue(SchedulePolicy.shouldRetryRun(waiting=false,retryPending=true,attempt=0))
        assertFalse("a plain failure is not a retry reason",SchedulePolicy.shouldRetryRun(waiting=false,retryPending=false,attempt=0))
        assertFalse("bounded attempts",SchedulePolicy.shouldRetryRun(waiting=true,retryPending=false,attempt=3))
        assertTrue(SchedulePolicy.canCompleteDay(day,day,waiting=false,retryPending=false))
        assertFalse(SchedulePolicy.canCompleteDay(day,day,waiting=false,retryPending=true))
    }
}
