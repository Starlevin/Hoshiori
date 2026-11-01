package com.glasssign.app.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Device tests added in stage A; execute only after the desktop-game guard is released. */
@RunWith(AndroidJUnit4::class)
class PendingAccountSafetyTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dbName = "pending-safety-${UUID.randomUUID()}.db"
    private lateinit var repository: SignRepository
    private lateinit var credentials: LocalCredentialStore
    private lateinit var accountId: String

    @Before fun setup() {
        repository = SignRepository(context, dbName)
        credentials = LocalCredentialStore(context)
        accountId = UUID.randomUUID().toString()
        repository.createPending("miyoushe", "合成待确认账号", accountId, null)
        credentials.save(accountId, JSONObject().put("test", "synthetic-only"))
        repository.enabled(accountId, false)
        repository.auth(accountId, "pending", "等待本人确认角色")
    }

    @After fun cleanup() {
        credentials.delete(accountId)
        repository.close()
        context.deleteDatabase(dbName)
    }

    @Test fun pendingBindingCannotBeActivatedOrSignedByAnyRunTrigger() = runBlocking {
        val reads = AtomicInteger()
        val posts = AtomicInteger()
        val adapter = object : ProviderAdapter {
            override fun targets(c: JSONObject, persist: () -> Unit): List<SignTarget> {
                reads.incrementAndGet()
                return listOf(SignTarget("game|test-role", "合成角色"))
            }
            override fun checkin(c: JSONObject, t: SignTarget, persist: () -> Unit): SignResult {
                posts.incrementAndGet()
                return SignResult("success", "仅为 mock 结果")
            }
        }
        val engine = LocalSignEngine(repository, credentials, mapOf("miyoushe" to adapter))

        repository.enabled(accountId, true)
        assertFalse(repository.accounts().single().enabled)
        assertEquals(0, engine.run().attempted)
        assertEquals(0, engine.run(listOf(accountId)).attempted)
        engine.verify(accountId)
        assertEquals("pending", repository.accounts().single().authState)
        assertEquals(0, reads.get())
        assertEquals(0, posts.get())
        assertTrue(repository.records(SignRepository.day()).isEmpty())

        // A separate confirmation transition restores normal execution.
        repository.markPendingRoles(accountId,true)
        LocalAccountBindings(repository,credentials).confirm(accountId)
        assertEquals(1, engine.run().attempted)
        assertEquals(1, reads.get())
        assertEquals(1, posts.get())
    }

    @Test fun discoveryFailureKeepsBindingPendingWithoutSigningOrActivation() = runBlocking {
        val reads = AtomicInteger()
        val adapter = object : ProviderAdapter {
            override fun targets(c: JSONObject, persist: () -> Unit): List<SignTarget> {
                reads.incrementAndGet()
                throw SignError("auth", "合成失效授权")
            }
            override fun checkin(c: JSONObject, t: SignTarget, persist: () -> Unit): SignResult =
                error("Discovery failure must never reach sign submission")
        }
        val engine = LocalSignEngine(repository, credentials, mapOf("miyoushe" to adapter))

        val outcome = engine.discover(accountId)
        assertFalse(outcome.ok)
        assertTrue(outcome.roles.isEmpty())
        assertEquals("needs_action", repository.accounts().single().authState)
        assertTrue(repository.accounts().single().bindingPending)
        repository.enabled(accountId, true)
        assertFalse(repository.accounts().single().enabled)
        assertEquals(0, engine.run(listOf(accountId)).attempted)
        assertEquals(1, reads.get())
        assertTrue(repository.records(SignRepository.day()).isEmpty())
    }
}
