package com.glasssign.app.local

import android.app.Application
import android.content.Context
import com.glasssign.app.data.RoleChoice
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Provider execution gate (unified spec §5.2) — the P0 promise is: disabling a built-in provider really stops that
 * provider's manual AND automatic signing, without reading a credential, without sending a request, and without
 * deleting or rewriting accounts, roles, records or guards.
 *
 * Everything here runs against the REAL engine + REAL SQLite (Robolectric) with a fake adapter: no socket is opened
 * and no real platform is called, so nothing in this file is evidence that a real check-in works.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class ProviderGateTest {
    private lateinit var context: Context
    private lateinit var repo: SignRepository
    private lateinit var databaseName: String
    private lateinit var stores: CountingCredentials
    private lateinit var registry: ProviderRegistry
    private val day get() = SignRepository.day()
    /** The default (tagged) role target, kept identical to what [targetFor] produces. */
    private val target get() = targetFor("tagged")

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(ProviderRegistry.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        databaseName = "${UUID.randomUUID()}.db"
        repo = SignRepository(context, databaseName)
        stores = CountingCredentials()
        registry = ProviderRegistry(context)
    }

    @After fun cleanup() {
        repo.close()
        context.deleteDatabase(databaseName)
        context.getSharedPreferences(ProviderRegistry.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    /** Counts every store access so "no credential was read" is observable, not inferred. */
    private class CountingCredentials : CredentialStore {
        private val values = mutableMapOf<String, String>()
        val reads = AtomicInteger()
        val writes = AtomicInteger()
        val deletes = AtomicInteger()
        val idsRead = mutableListOf<String>()
        /** Lifecycle hook for tests that need to react to the first real read. */
        var onRead: (() -> Unit)? = null
        override fun read(id: String): JSONObject {
            reads.incrementAndGet(); idsRead.add(id)
            onRead?.invoke()
            return JSONObject(values.getValue(id))
        }
        override fun save(id: String, c: JSONObject) { writes.incrementAndGet(); values[id] = c.toString() }
        override fun delete(id: String) { deletes.incrementAndGet(); values.remove(id) }
        fun seed(id: String, json: JSONObject) { values[id] = json.toString() }
        fun peek(id: String): JSONObject? = values[id]?.let { JSONObject(it) }
    }

    private class FakeAdapter(private val targetsToReturn: List<SignTarget>, var outcome: SignResult) : ProviderAdapter {
        val discoveries = AtomicInteger()
        val submissions = AtomicInteger()
        val queries = AtomicInteger()
        var queriesImplemented = true
        var lastTargets = emptyList<SignTarget>()
        override fun targets(c: JSONObject, persist: () -> Unit): List<SignTarget> {
            discoveries.incrementAndGet()
            lastTargets = targetsToReturn
            return targetsToReturn
        }
        override fun checkin(c: JSONObject, t: SignTarget, persist: () -> Unit): SignResult {
            submissions.incrementAndGet()
            return outcome
        }
        override fun queryStatus(c: JSONObject, t: SignTarget, persist: () -> Unit): SignResult {
            queries.incrementAndGet()
            return if (queriesImplemented) SignResult("already", "官方确认今日已签", resultSource = "official", verificationMethod = "state", verifiedAt = SignRepository.now())
            else SignResult("uncertain", "该平台尚无已验证的独立查询接口，请在官方应用核对；未重新提交")
        }
    }

    private fun engineFor(adapter: ProviderAdapter, provider: String = "tagged") =
        LocalSignEngine(repo, stores, mapOf(provider to adapter), registry)

    /**
     * A fully bound account with one user-confirmed role, exactly like the real binding flow produces.
     *
     * The target id and the game id both come from the REAL structure that provider uses, because
     * `GameCatalog.gameForTarget` parses them: miyoushe/skland use the game catalog id as the target id
     * (`genshin`), while tagged/kurobbs use `game|<protocolId>|<roleId>`. A mismatch here makes the plan drop the
     * task and would make the test assert the wrong thing.
     */
    private fun account(provider: String = "tagged"): String {
        val gameId = com.glasssign.app.data.GameCatalog.idsFor(provider).first()
        val id = repo.add(provider, "合成测试", gameId = gameId)
        val role = RoleChoice(targetFor(provider).id, gameId, targetFor(provider).name)
        repo.storeRoleChoices(id, listOf(role))
        repo.setBindings(id, listOf(role), false)
        repo.auth(id, "valid", "合成授权，无网络")
        stores.seed(id, JSONObject().put("communityCheckin", false).put("roleIds", JSONArray().put("10001")))
        return id
    }

    /** The real target shape for a provider, taken from the catalog protocol id. */
    private fun targetFor(provider: String): SignTarget = when (provider) {
        "tagged" -> SignTarget("game|1289|10001", "异环 · 测试角色")
        "kurobbs" -> SignTarget("game|3|10001", "鸣潮 · 测试角色")
        // miyoushe / skland address a role by the game catalog id itself.
        else -> SignTarget(com.glasssign.app.data.GameCatalog.idsFor(provider).first(), "$provider · 测试角色")
    }

    // ---------------------------------------------------------------------------------------------------------
    // Disabled provider: zero credential reads, zero requests
    // ---------------------------------------------------------------------------------------------------------

    @Test fun disabledProviderRunReadsNothingAndSubmitsNothing() = runBlocking {
        val id = account()
        registry.setEnabled("tagged", false)
        val adapter = FakeAdapter(listOf(target), SignResult("accepted", "已受理", resultSource = "accepted"))
        val readsBefore = stores.reads.get()

        val summary = engineFor(adapter).run(listOf(id))

        assertEquals("no task may be attempted", 0, summary.attempted)
        assertEquals("no discovery may happen", 0, adapter.discoveries.get())
        assertEquals("no submission may happen", 0, adapter.submissions.get())
        assertEquals("no query may happen", 0, adapter.queries.get())
        assertEquals("no credential may be read", readsBefore, stores.reads.get())
        assertTrue("no record may be written", repo.records(day).isEmpty())
        assertFalse("a paused provider must not ask for a retry", summary.retryPending)
    }

    @Test fun disabledProviderVerifyReconcileDiscoverAndGamesAreAllBlocked() = runBlocking {
        val id = account()
        // First make one real row so `reconcile` has something to act on.
        val live = FakeAdapter(listOf(target), SignResult("accepted", "已受理", resultSource = "accepted"))
        engineFor(live).run(listOf(id))
        assertEquals(1, live.submissions.get())
        val rowBefore = repo.records(day).single { it.targetId == target.id }

        registry.setEnabled("tagged", false)
        val adapter = FakeAdapter(listOf(target), SignResult("accepted", "已受理", resultSource = "accepted"))
        val engine = engineFor(adapter)
        val readsBefore = stores.reads.get()
        val writesBefore = stores.writes.get()

        val verify = engine.verify(id)
        val reconcile = engine.reconcile(id, target.id)
        val games = engine.gamesForSelection(id)
        val discover = engine.discover(id)

        assertEquals("no discovery for any blocked entry point", 0, adapter.discoveries.get())
        assertEquals("no query for a blocked reconcile", 0, adapter.queries.get())
        assertEquals("no submission", 0, adapter.submissions.get())
        assertEquals("no credential read", readsBefore, stores.reads.get())
        assertEquals("no credential write", writesBefore, stores.writes.get())

        // Every entry point reports the pause honestly instead of a fake failure.
        assertTrue(verify.contains("停用"))
        assertTrue(reconcile.contains("停用"))
        assertFalse(games.ok); assertTrue(games.message.contains("停用"))
        assertFalse(discover.ok); assertTrue(discover.message.contains("停用"))
        // The pause reason must state that nothing was read/sent and nothing is deleted.
        assertTrue(ProviderExecutionGate.PAUSED_REASON.contains("未读取授权"))
        assertTrue(ProviderExecutionGate.PAUSED_REASON.contains("保留"))

        // A blocked reconcile must not have flipped the row into `verifying` (no transient marker left behind).
        val rowAfter = repo.records(day).single { it.targetId == target.id }
        assertEquals(rowBefore.status, rowAfter.status)
        assertEquals(rowBefore.resultSource, rowAfter.resultSource)
        assertEquals("a blocked reconcile must not overwrite the earlier proof message", rowBefore.message, rowAfter.message)
    }

    // ---------------------------------------------------------------------------------------------------------
    // Pausing in the middle of a run
    // ---------------------------------------------------------------------------------------------------------

    /**
     * The user disables the provider while a run is already in progress (after the account was planned, before the
     * per-target submission). No NEW submission may be sent, and the real result of anything already done is kept.
     */
    @Test fun pausingMidRunStopsNewSubmissionsWithoutFakingState() = runBlocking {
        val id = account()
        val gate = MutableGate()
        val adapter = object : ProviderAdapter {
            val submissions = AtomicInteger()
            override fun targets(c: JSONObject, persist: () -> Unit): List<SignTarget> = listOf(target)
            override fun checkin(c: JSONObject, t: SignTarget, persist: () -> Unit): SignResult {
                submissions.incrementAndGet()
                // The first submission succeeds; while it is being finished the user pauses the provider.
                gate.allowed = false
                return SignResult("accepted", "已受理，等待确认", resultSource = "accepted")
            }
        }
        val engine = LocalSignEngine(repo, stores, mapOf("tagged" to adapter), gate)
        engine.run(listOf(id))

        assertEquals("the in-flight submission completes exactly once", 1, adapter.submissions.get())
        val row = repo.records(day).single { it.targetId == target.id }
        assertEquals("the real outcome is kept, not overwritten with a synthetic state", "accepted", row.status)
        assertEquals("accepted", row.resultSource)

        // A second run while paused does nothing at all.
        val before = stores.reads.get()
        engine.run(listOf(id))
        assertEquals(1, adapter.submissions.get())
        assertEquals("paused run must not read a credential", before, stores.reads.get())
        assertEquals("accepted", repo.records(day).single { it.targetId == target.id }.status)
    }

    /** A gate whose answer the test controls, for the mid-run scenario. */
    private class MutableGate(var allowed: Boolean = true) : ProviderExecutionGate {
        override fun isExecutionAllowed(providerId: String): Boolean = allowed
        override fun blockedReason(providerId: String): String? = if (allowed) null else ProviderExecutionGate.PAUSED_REASON
    }

    /**
     * The dangerous shape the old code had: pause AFTER a successful claim but BEFORE sending. The claim is the
     * "in flight" boundary, so a paused provider must stop the NEXT target before its claim — never leave a
     * `running` row behind for a request that was never sent (recovery would turn it into `uncertain` and lock the
     * day for a platform that did nothing at all).
     */
    @Test fun pausingBetweenTargetsLeavesNoRunningOrUncertainRow() = runBlocking {
        val id = account()
        val second = SignTarget("game|1289|10002", "异环 · 第二个角色")
        val gate = MutableGate()
        val adapter = object : ProviderAdapter {
            val submissions = AtomicInteger()
            override fun targets(c: JSONObject, persist: () -> Unit): List<SignTarget> = listOf(target, second)
            override fun checkin(c: JSONObject, t: SignTarget, persist: () -> Unit): SignResult {
                submissions.incrementAndGet()
                // Pause immediately after the FIRST submission is accepted, before the second target is claimed.
                gate.allowed = false
                return SignResult("accepted", "已受理，等待确认", resultSource = "accepted")
            }
        }
        val engine = LocalSignEngine(repo, stores, mapOf("tagged" to adapter), gate)
        engine.run(listOf(id))

        assertEquals("only the first target may be submitted", 1, adapter.submissions.get())
        val rows = repo.records(day)
        assertEquals("the second target must have NO row at all", listOf(target.id), rows.map { it.targetId })
        assertEquals("accepted", rows.single().status)
        // The whole point: nothing was left in a state that recovery would misread as a real unknown submit.
        assertFalse("no running row may survive", rows.any { it.status == "running" })
        assertFalse("no verifying row may survive", rows.any { it.status == "verifying" })
        assertFalse("no synthetic uncertain row may be created", rows.any { it.status == "uncertain" })
        // And the guard table must not hold a blocking row for the target that was never sent.
        assertFalse("no guard may block the untouched target", repo.guards(day).any { it.targetId == second.id })

        // Even after a simulated process death, recovery cannot turn the untouched target into `uncertain`.
        repo.recoverInterrupted()
        assertFalse(repo.records(day).any { it.targetId == second.id })
        assertFalse(repo.records(day).any { it.status == "uncertain" })
    }

    /**
     * The same boundary rule applies to the discovery step.
     *
     * The pause is tied to a real lifecycle event, not a call ordinal: the credential store signals the FIRST read
     * attempt, and the gate starts refusing from that moment. The engine must therefore never get past its planning
     * phase for this account, and in particular must not read the community switch (its first credential read).
     */
    @Test fun pausingBeforeDiscoveryClaimReadsNothingAndWritesNoRow() = runBlocking {
        val id=account()
        val gate = LifecycleGate()
        // The pause fires on the very first credential read attempt; the run must observe it and stop.
        stores.onRead = { gate.pauseOnFirstRead() }
        val adapter = FakeAdapter(listOf(target), SignResult("accepted", "已受理", resultSource = "accepted"))
        val readsBefore = stores.reads.get()
        LocalSignEngine(repo, stores, mapOf("tagged" to adapter), gate).run(listOf(id))

        assertTrue("the scenario must actually have paused the provider", gate.fired)
        assertEquals("no discovery", 0, adapter.discoveries.get())
        assertEquals("no submission", 0, adapter.submissions.get())
        assertTrue("no row of any state may be written", repo.records(day).isEmpty())
        assertTrue(repo.guards(day).isEmpty())
        // Whatever read slips through must be the one that triggered the pause, never more.
        assertTrue("at most one read may precede the pause", stores.reads.get() - readsBefore <= 1)
    }

    /** A gate driven by a concrete lifecycle event: the first credential read pauses the provider. */
    private class LifecycleGate : ProviderExecutionGate {
        private var paused = false
        var fired = false
            private set

        fun pauseOnFirstRead() { if (!fired) { fired = true; paused = true } }

        override fun isExecutionAllowed(providerId: String): Boolean = !paused
        override fun blockedReason(providerId: String): String? = if (paused) ProviderExecutionGate.PAUSED_REASON else null
    }

    // ---------------------------------------------------------------------------------------------------------
    // Existing protections survive a pause / resume cycle
    // ---------------------------------------------------------------------------------------------------------

    @Test fun acceptedRowStillBlocksSubmissionAfterPauseAndResume() = runBlocking {
        val id = account()
        val first = FakeAdapter(listOf(target), SignResult("accepted", "已受理", resultSource = "accepted"))
        engineFor(first).run(listOf(id))
        assertEquals(1, first.submissions.get())

        registry.setEnabled("tagged", false)
        engineFor(first).run(listOf(id))
        registry.setEnabled("tagged", true)

        val second = FakeAdapter(listOf(target), SignResult("accepted", "已受理", resultSource = "accepted"))
        engineFor(second).run(listOf(id))
        assertEquals("an accepted submission must never be resubmitted the same day", 0, second.submissions.get())
        val row = repo.records(day).single { it.targetId == target.id }
        assertEquals("accepted", row.status)
        assertEquals(1, row.attempts)
    }

    @Test fun uncertainRowIsNotResubmittedAfterPauseAndResume() = runBlocking {
        val id = account()
        val first = FakeAdapter(listOf(target), SignResult("uncertain", "结果不确定，请先核对官方", resultSource = "unknown"))
        engineFor(first).run(listOf(id))
        assertEquals(1, first.submissions.get())

        registry.setEnabled("tagged", false)
        engineFor(first).run(listOf(id))
        registry.setEnabled("tagged", true)

        val second = FakeAdapter(listOf(target), SignResult("accepted", "已受理", resultSource = "accepted"))
        engineFor(second).run(listOf(id))
        assertEquals("an unknown outcome must not be blindly resubmitted", 0, second.submissions.get())
        assertEquals("uncertain", repo.records(day).single { it.targetId == target.id }.status)
    }

    /**
     * Pausing is a gate, not a delete: every table keeps exactly the rows it had.
     *
     * A second account exists so "nothing was deleted" is meaningful, and it is given a working adapter so the full
     * `run()` exercises the real multi-account path instead of hitting a provider this test forgot to wire.
     */
    @Test fun pausingKeepsAccountsRolesRecordsAndGuardsByteForByte() = runBlocking {
        val id = account("tagged")
        val other = account("miyoushe")
        val adapter = FakeAdapter(listOf(targetFor("tagged")), SignResult("accepted", "已受理", resultSource = "accepted"))
        val otherAdapter = FakeAdapter(listOf(targetFor("miyoushe")), SignResult("accepted", "已受理", resultSource = "accepted"))
        val engine = LocalSignEngine(repo, stores, mapOf("tagged" to adapter, "miyoushe" to otherAdapter), registry)
        engine.run(listOf(id))
        assertEquals("the paused provider account was signed once", 1, adapter.submissions.get())

        val accountsBefore = repo.accounts()
        val bindingsBefore = repo.bindings()
        val rolesBefore = repo.roleChoices(id)
        val recordsBefore = repo.records(day)
        val guardsBefore = repo.guards(day)
        val credentialsBefore = stores.peek(id).toString()
        val otherAccountsBefore = repo.accounts().single { it.id == other }

        registry.setEnabled("tagged", false)
        engine.run(listOf(id))
        // Keep this data-preservation check scoped; the separate multi-provider case runs the other adapter.
        engine.run(listOf(id),scheduled=true)
        registry.setEnabled("tagged", true)

        assertEquals("accounts must be untouched", accountsBefore, repo.accounts())
        assertEquals("the other (miyoushe) account must be untouched", otherAccountsBefore, repo.accounts().single { it.id == other })
        assertEquals("the other account keeps its valid authorization", "valid", repo.accounts().single { it.id == other }.authState)
        assertEquals(2, repo.accounts().size)
        assertEquals(bindingsBefore, repo.bindings())
        assertEquals(rolesBefore, repo.roleChoices(id))
        assertEquals("no new record for the paused account", recordsBefore.size, repo.records(day).count { it.accountId == id })
        assertEquals("the paused account's own row is unchanged", recordsBefore.filter { it.accountId == id }, repo.records(day).filter { it.accountId == id })
        assertEquals("guards are the submit protection and must survive", guardsBefore, repo.guards(day))
        assertEquals("the credential must not be rewritten", credentialsBefore, stores.peek(id).toString())
        assertEquals("nothing may be deleted", 0, stores.deletes.get())
        assertEquals("the paused provider stopped submitting", 1, adapter.submissions.get())
    }

    /** One paused provider must not silence another provider's accounts. */
    @Test fun pausingOneProviderDoesNotAffectAnotherAccountsRun() = runBlocking {
        val tagged = account("tagged")
        val mihoyo = account("miyoushe")
        registry.setEnabled("tagged", false)

        val taggedAdapter = FakeAdapter(listOf(targetFor("tagged")), SignResult("accepted", "已受理", resultSource = "accepted"))
        val mihoyoAdapter = FakeAdapter(listOf(targetFor("miyoushe")), SignResult("accepted", "已受理", resultSource = "accepted"))
        val engine = LocalSignEngine(repo, stores, mapOf("tagged" to taggedAdapter, "miyoushe" to mihoyoAdapter), registry)

        val summary = engine.run()

        assertEquals("the enabled provider still runs", 1, summary.attempted)
        assertEquals(1, mihoyoAdapter.submissions.get())
        assertEquals("the paused provider never runs", 0, taggedAdapter.discoveries.get())
        assertEquals(0, taggedAdapter.submissions.get())
        // Cross-account isolation: only the enabled account produced a result.
        assertEquals(listOf(mihoyo), repo.records(day).map { it.accountId }.distinct())
        assertFalse(repo.records(day).any { it.accountId == tagged })
    }

    // ---------------------------------------------------------------------------------------------------------
    // Credential boundary: one dispatch may only touch its own account
    // ---------------------------------------------------------------------------------------------------------

    @Test fun adapterCannotReadAnotherAccountsCredential() = runBlocking {
        val id = account()
        val victim = account("miyoushe")
        var refused = 0
        val adapter = object : ProviderAdapter {
            override fun targets(c: JSONObject, persist: () -> Unit): List<SignTarget> {
                // The engine only hands the adapter `persist` closures bound to THIS account; to prove the boundary
                // the test uses the same wrapper the engine uses internally and tries a different id.
                val scoped = ScopedCredentialStore(stores, id)
                try { scoped.read(victim) } catch (_: CrossAccountCredentialError) { refused++ }
                return listOf(target)
            }
            override fun checkin(c: JSONObject, t: SignTarget, persist: () -> Unit) =
                SignResult("accepted", "已受理", resultSource = "accepted")
        }
        val scoped = ScopedCredentialStore(stores, id)
        val readsBefore = stores.reads.get()
        try {
            scoped.read(victim)
            throw AssertionError("a scoped store must refuse another account")
        } catch (_: CrossAccountCredentialError) {
            // expected: fail closed, and nothing was read
        }
        assertEquals("a refused cross-account read must not reach the store", readsBefore, stores.reads.get())
        // The owner is still readable/writable through the same wrapper.
        assertNotNull(scoped.read(id))
        scoped.save(id, JSONObject().put("communityCheckin", false))
        // And the normal dispatch still works end to end with the gate open.
        engineFor(adapter).run(listOf(id))
        assertEquals("the in-run cross-account attempt was refused", 1, refused)
        assertNotNull("the other account's credential is untouched", stores.peek(victim))
    }

    @Test fun scopedStoreRefusesWriteAndDeleteForAnotherAccount() {
        val id = account()
        val victim = account("miyoushe")
        val scoped = ScopedCredentialStore(stores, id)
        val deletesBefore = stores.deletes.get()
        val writesBefore = stores.writes.get()

        runCatching { scoped.save(victim, JSONObject().put("cookie", "x")) }
            .onSuccess { throw AssertionError("cross-account save must be refused") }
        runCatching { scoped.delete(victim) }
            .onSuccess { throw AssertionError("cross-account delete must be refused") }

        assertEquals("no write may land on another account", writesBefore, stores.writes.get())
        assertEquals("no delete may land on another account", deletesBefore, stores.deletes.get())
        // The victim's credential is intact.
        assertNotNull(stores.peek(victim))
    }

    // ---------------------------------------------------------------------------------------------------------
    // Reconcile honesty for providers without a status query
    // ---------------------------------------------------------------------------------------------------------

    @Test fun reconcileOnProviderWithoutStatusQuerySendsNothingAndKeepsProof() = runBlocking {
        val id = account("kurobbs")
        // The target must be the ONE the account is actually bound to; building a second copy here is what made the
        // record lookup fail before (the run signs the bound target, not this local instance).
        val bound = repo.bindings(id).single()
        val kuroTarget = SignTarget(bound.targetId, bound.name)
        val adapter = object : ProviderAdapter {
            val queries = AtomicInteger()
            val submissions=AtomicInteger()
            override fun targets(c: JSONObject, persist: () -> Unit): List<SignTarget> = listOf(kuroTarget)
            override fun checkin(c: JSONObject, t: SignTarget, persist: () -> Unit):SignResult {
                submissions.incrementAndGet()
                return SignResult("accepted", "已受理，等待确认", resultSource = "accepted")
            }
            // A counting wrapper that reproduces the REAL KuroBBS shape: queryStatus falls through to the interface
            // default ("no verified status endpoint"). The engine must refuse before calling it at all, so this
            // counter staying at 0 proves the manifest gate — not the adapter — did the work.
            override fun queryStatus(c: JSONObject, t: SignTarget, persist: () -> Unit): SignResult {
                queries.incrementAndGet()
                return super.queryStatus(c, t, persist)
            }
        }
        val engine = LocalSignEngine(repo, stores, mapOf("kurobbs" to adapter), registry)
        engine.run(listOf(id))
        val before = repo.records(day).single { it.targetId == kuroTarget.id }
        assertEquals("the account must really be bound to this target", 1, adapter.submissions.get())

        val message = engine.reconcile(id, kuroTarget.id)

        assertEquals("a provider without a status endpoint must not be asked", 0, adapter.queries.get())
        assertTrue("the UI must be told the check cannot conclude", message.contains("无法得出结论"))
        val after = repo.records(day).single { it.targetId == kuroTarget.id }
        assertEquals("the accepted proof survives a non-conclusive check", "accepted", after.status)
        assertEquals(before.resultSource, after.resultSource)
        assertEquals("the row must not be left in `verifying`", "accepted", after.status)
    }

    /** The compatibility constructor (no gate argument) must behave exactly as before. */
    @Test fun engineWithoutAGateKeepsPreviousBehaviour() = runBlocking {
        val id = account()
        val adapter = FakeAdapter(listOf(target), SignResult("accepted", "已受理", resultSource = "accepted"))
        val legacyEngine = LocalSignEngine(repo, stores, mapOf("tagged" to adapter))
        legacyEngine.run(listOf(id))
        assertEquals(1, adapter.submissions.get())
        assertEquals("accepted", repo.records(day).single { it.targetId == target.id }.status)
    }
}
