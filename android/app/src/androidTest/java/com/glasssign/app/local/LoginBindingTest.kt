package com.glasssign.app.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Covers the "captured session must not become a bound account by itself" contract:
 * login → pending (disabled) → role discovery → user confirmation, plus abandon and the failure
 * path where the platform rejects the session.
 */
@RunWith(AndroidJUnit4::class)
class LoginBindingTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var repo: SignRepository
    private lateinit var secrets: LocalCredentialStore
    private lateinit var dbName: String
    private val ids = mutableListOf<String>()

    private class FakeAdapter(private val fail: Boolean = false) : ProviderAdapter {
        var checks = 0
        override fun targets(c: JSONObject, persist: () -> Unit): List<SignTarget> {
            if (fail) throw SignError("auth", "官方拒绝该会话")
            return listOf(
                SignTarget("genshin|1|cn_gf01", "原神 · 旅行者"),
                SignTarget("zzz|2|prod_gf_cn", "绝区零 · 绳匠"),
            )
        }
        override fun checkin(c: JSONObject, t: SignTarget, persist: () -> Unit): SignResult {
            checks++
            return SignResult("success", "synthetic")
        }
    }

    @Before fun setup() {
        dbName = "login-${UUID.randomUUID()}.db"
        repo = SignRepository(context, dbName)
        secrets = LocalCredentialStore(context)
    }

    @After fun clean() {
        ids.forEach { secrets.delete(it) }
        repo.close()
        context.deleteDatabase(dbName)
    }

    private fun engine(adapter: ProviderAdapter) = LocalSignEngine(repo, secrets, mapOf("miyoushe" to adapter))

    @Test fun loginStaysDisabledUntilRolesConfirmed() = runBlocking {
        // LocalServices wraps repository writes in the shared mutex; use the engine directly so the
        // test can inject a fake adapter.
        val adapter = FakeAdapter()
        val eng = engine(adapter)
        val id = UUID.randomUUID().toString()
        ids.add(id)
        secrets.save(id, JSONObject().put("cookie", "synthetic=1"))
        repo.add("miyoushe", "测试账号", id)
        repo.enabled(id, false)
        repo.auth(id, "pending", "已取得官方登录会话")

        val pending = repo.accounts().single { it.id == id }
        assertEquals("pending", pending.authState)
        assertFalse("pending accounts must not be signed", pending.enabled)

        // A run while pending must not touch the platform.
        eng.run()
        assertEquals(0, adapter.checks)

        val outcome = eng.discover(id)
        assertTrue(outcome.ok)
        assertEquals(2, outcome.roles.size)

        // Confirmation activates the account.
        repo.auth(id, "valid", "已登录并确认角色")
        repo.enabled(id, true)
        val bound = repo.accounts().single { it.id == id }
        assertEquals("valid", bound.authState)
        assertTrue(bound.enabled)
    }

    @Test fun rejectedSessionReportsNeedsActionAndReadsNoRoles() = runBlocking {
        val eng = engine(FakeAdapter(fail = true))
        val id = UUID.randomUUID().toString()
        ids.add(id)
        secrets.save(id, JSONObject().put("cookie", "expired=1"))
        repo.add("miyoushe", "过期账号", id)

        val outcome = eng.discover(id)
        assertFalse(outcome.ok)
        assertTrue(outcome.roles.isEmpty())
        assertEquals("needs_action", repo.accounts().single { it.id == id }.authState)
    }

    @Test fun abandoningLoginDeletesRowAndSecret() = runBlocking {
        val eng = engine(FakeAdapter())
        val id = UUID.randomUUID().toString()
        secrets.save(id, JSONObject().put("cookie", "synthetic=1"))
        repo.add("miyoushe", "取消账号", id)
        assertNotNull(secrets.read(id))

        secrets.delete(id)
        repo.delete(id)

        assertTrue(repo.accounts().none { it.id == id })
        try {
            secrets.read(id)
            fail("credential should be gone")
        } catch (e: SignError) {
            assertEquals("auth", e.kind)
        }
        assertEquals(0, eng.discover(id).roles.size)
    }

    @Test fun onlyMiyousheIsWiredInThisTestFixture() = runBlocking {
        // Guards against a silent adapter-map regression: unknown providers must surface clearly.
        val eng = LocalSignEngine(repo, secrets, emptyMap())
        val id = UUID.randomUUID().toString()
        ids.add(id)
        secrets.save(id, JSONObject().put("cookie", "synthetic=1"))
        repo.add("miyoushe", "无适配器", id)
        val outcome = eng.discover(id)
        assertFalse(outcome.ok)
        assertTrue(outcome.message.isNotBlank())
        assertFalse(repo.accounts().single { it.id == id }.authState == "valid")
    }
}
