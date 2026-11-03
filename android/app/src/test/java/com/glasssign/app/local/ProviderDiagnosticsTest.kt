package com.glasssign.app.local

import com.glasssign.app.data.GameCatalog
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Pins the diagnostic contract restored for the v0.7 on-device audit: an HTTP-level rejection must
 * stay *attributable* (method + host + status) and must never echo a body, query, header or token.
 * A 2xx JSON reply must keep the original adapter business handling untouched.
 * No socket, no credential, no Gradle: every case runs against the fake transport or the pure classifier.
 */
class ProviderDiagnosticsTest {

    // Isolate role discovery from the independently covered refresh-token exchange and community task.
    private val credentials = """{"_accessToken":"fixture-access-token","refreshToken":"fixture-refresh-token","deviceId":"fixture-device","gameIds":["1289"],"communityCheckin":false}"""

    /** Answers a fixed JSON body and cannot describe HTTP-level failures, like a synthetic test double. */
    private class Fake(private val reply: JSONObject) : Transport {
        val calls = mutableListOf<Pair<String, String>>()
        override fun call(method: String, url: String, headers: Map<String, String>, query: Map<String, String>, body: String?, form: Map<String, String>?): JSONObject {
            calls.add(method to url)
            return reply
        }
    }

    /** Fails like the real transport after a non-2xx reply, without any network. */
    private class Rejecting(private val status: Int, private val host: String) : Transport {
        var calls = 0
        override fun call(method: String, url: String, headers: Map<String, String>, query: Map<String, String>, body: String?, form: Map<String, String>?): JSONObject {
            calls++
            throw classifyStatus(method, host, status)
        }
    }

    // --- transport classification -----------------------------------------------------------------

    @Test fun non2xxStatusesMapToTheDocumentedOutcome() {
        val redirect = classifyStatus("GET", "bbs-api.tajiduo.com", 302)
        assertEquals("protocol", redirect.kind)
        assertEquals("failed", redirect.status)
        assertTrue("a redirect is a safety stop, not a retry prompt", redirect.message.orEmpty().contains("跳转"))

        val unauthorized = classifyStatus("GET", "bbs-api.tajiduo.com", 401)
        assertEquals("auth", unauthorized.kind)
        assertEquals("needs_action", unauthorized.status)

        for (code in listOf(403, 412)) {
            val captcha = classifyStatus("GET", "bbs-api.tajiduo.com", code)
            assertEquals("captcha", captcha.kind)
            assertEquals("needs_action", captcha.status)
        }

        // v0.7.7 P0: 429 is a rate limit, not an authorization event and not a retryable failure.
        val limited = classifyStatus("GET", "bbs-api.tajiduo.com", 429)
        assertEquals("rate_limit", limited.kind)
        assertEquals("failed", limited.status)

        for (code in listOf(404, 418, 451, 470)) {
            val unexpected = classifyStatus("GET", "bbs-api.tajiduo.com", code)
            assertEquals("protocol", unexpected.kind)
            assertEquals("failed", unexpected.status)
            val message = unexpected.message.orEmpty()
            assertTrue("the status must survive: $message", message.contains("HTTP $code"))
            assertTrue("the request must be identifiable: $message", message.contains("GET bbs-api.tajiduo.com"))
        }
    }

    @Test fun serverErrorsKeepTheirDirectionOfUncertainty() {
        val onRead = classifyStatus("GET", "bbs-api.tajiduo.com", 503)
        assertEquals("network", onRead.kind)
        assertEquals("waiting_network", onRead.status)
        assertTrue(onRead.message.orEmpty().contains("HTTP 503"))

        val onSubmit = classifyStatus("POST", "bbs-api.tajiduo.com", 503)
        assertEquals("uncertain", onSubmit.kind)
        assertEquals("uncertain", onSubmit.status)
        assertTrue("an unconfirmed submission must say so", onSubmit.message.orEmpty().contains("未确认"))
        assertTrue(onSubmit.message.orEmpty().contains("HTTP 503"))
    }

    @Test fun rejectionTextCarriesNoCredentialMaterial() {
        val described = HttpRejection("GET", "bbs-api.tajiduo.com", 470).describe()
        assertEquals("GET bbs-api.tajiduo.com · HTTP 470", described)
        assertTrue("exactly one separator keeps the line readable in a record", described.count { it == '·' } == 1)
        assertFalse("no query material is representable", described.contains("?"))
        assertFalse("no token-shaped value is representable", described.contains("token"))

        // v0.7.7 P0: a rejected request keeps its PATH (attribution) but never its query or any user identifier.
        val attributable = HttpRejection("POST", "bbs-api.tajiduo.com", 402, "/apihub/awapi/sign")
        assertEquals("POST bbs-api.tajiduo.com/apihub/awapi/sign · HTTP 402", attributable.describe())
        assertEquals("/apihub/awapi/sign", safeEndpoint("/apihub/awapi/sign?gameId=1289&roleId=50000001"))

        val message = classifyStatus("GET", "bbs-api.tajiduo.com", 470).message.orEmpty()
        assertFalse(message.contains("fixture-refresh-token"))
        assertFalse(message.contains("fixture-device"))
    }

    @Test fun syntheticTransportAddsNoDetailAndKeepsDiscoveryWorking() {
        val reply = JSONObject("""{"code":0,"data":{"roles":[{"roleId":"50000001","roleName":"合成角色"}]}}""")
        val transport = Fake(reply)
        assertEquals("a transport that cannot explain itself yields no suffix", "", rejectionDetail(transport))
        val targets = TaggedAdapter(transport).targets(JSONObject(credentials)) {}
        assertEquals(listOf("game|1289|50000001"), targets.map { it.id })
        assertEquals(listOf("GET" to "https://bbs-api.tajiduo.com/usercenter/api/v2/getGameRoles"), transport.calls)
    }

    // --- adapter behaviour around failures and discovery -------------------------------------------

    @Test fun aRejectedLookupFailsLoudlyAfterASingleReadOnlyRequest() {
        val transport = Rejecting(470, "bbs-api.tajiduo.com")
        val error = try {
            TaggedAdapter(transport).targets(JSONObject(credentials)) {}
            null
        } catch (e: SignError) {
            e
        }
        assertNotNull("a rejected lookup must not degrade into a silent fallback target", error)
        assertEquals("failed", error?.status)
        assertEquals("a rejected read is a `failed` protocol stop, not a retry", "protocol", error?.kind)
        assertTrue("the HTTP status survives into the recorded message", error?.message.orEmpty().contains("HTTP 470"))
        assertEquals("no submission may follow a rejected lookup", 1, transport.calls)
    }

    @Test fun a2xxBusinessCodeStillUsesTheOriginalAdapterMapping() {
        // A JSON reply with a non-zero business code is an HTTP 200 success: the adapter, not the
        // transport, decides the outcome. 401 stays `auth`, 403 stays `captcha`, 402 stays `protocol`
        // (an unexplained business rejection must not force a re-login).
        val auth = try {
            TaggedAdapter(Fake(JSONObject("""{"code":401}"""))).targets(JSONObject(credentials)) {}
            null
        } catch (e: SignError) { e }
        assertEquals("auth", auth?.kind)

        val captcha = try {
            TaggedAdapter(Fake(JSONObject("""{"code":403}"""))).targets(JSONObject(credentials)) {}
            null
        } catch (e: SignError) { e }
        assertEquals("captcha", captcha?.kind)

        val unexplained402 = try {
            TaggedAdapter(Fake(JSONObject("""{"code":402}"""))).targets(JSONObject(credentials)) {}
            null
        } catch (e: SignError) { e }
        assertEquals("a business 402 is not proof of an expired session", "protocol", unexplained402?.kind)

        val other = try {
            TaggedAdapter(Fake(JSONObject("""{"code":-1}"""))).targets(JSONObject(credentials)) {}
            null
        } catch (e: SignError) { e }
        assertEquals("protocol", other?.kind)
        val message = other?.message.orEmpty()
        assertTrue(
            "a 2xx reply adds no HTTP suffix because the transport was never rejected: $message",
            message.contains("业务码 -1") && !message.contains("HTTP"),
        )
    }

    // --- role discovery ---------------------------------------------------------------------------

    @Test fun aRoleWithoutNicknameStaysSelectableInsteadOfDisappearing() {
        val reply = JSONObject("""{"code":0,"data":{"roles":[{"roleId":"50000999"},{"roleId":"50000998","roleName":"合成角色"}]}}""")
        val transport = Fake(reply)
        val targets = TaggedAdapter(transport).targets(JSONObject(credentials)) {}
        assertEquals("an unnamed role is still discovered", listOf("game|1289|50000999", "game|1289|50000998"), targets.map { it.id })
        assertEquals("an unnamed role is labelled by its own id", "异环 · 50000999", targets[0].name)
        assertEquals("a named role keeps the official name", "异环 · 合成角色", targets[1].name)
        assertEquals("discovery maps the role to the NTE game", "nte", GameCatalog.gameForTarget("tagged", targets[0].id))
        assertEquals("discovery issues exactly one read-only request", 1, transport.calls.size)
    }

    @Test fun configuredRolesAreLabelledWithoutLosingTheirIdentity() {
        // Legacy pinned role ids keep the target id intact while still showing a readable name.
        val c = JSONObject(credentials).put("roleIds", org.json.JSONArray(listOf("50000777")))
        val transport = Fake(JSONObject("{}"))
        val targets = TaggedAdapter(transport).targets(c) {}
        assertEquals(listOf("game|1289|50000777"), targets.map { it.id })
        assertEquals("异环 · 50000777", targets[0].name)
        assertEquals("pinned roles are read without a role lookup", 0, transport.calls.size)
    }

    @Test fun anEmptyRoleListStillYieldsAnExplicitFallbackTarget() {
        // The fallback is not a bound role, so the engine reports "no role" instead of a silent success.
        val reply = JSONObject("""{"code":0,"data":{"roles":[]}}""")
        val targets = TaggedAdapter(Fake(reply)).targets(JSONObject(credentials)) {}
        assertEquals(listOf("no-roles"), targets.map { it.id })
        assertNull(GameCatalog.gameForTarget("tagged", "no-roles"))
    }
}
