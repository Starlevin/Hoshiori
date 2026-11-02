package com.glasssign.app.login

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionHarvestTest {

    private val miyousheCookies = LoginPlatforms.of("miyoushe").cookieNames
    private val kuroCookies = LoginPlatforms.of("kurobbs").cookieNames
    private val taggedCookies = LoginPlatforms.of("tagged").cookieNames

    private fun ready(provider: String, cookie: String = "", storage: Map<String, String> = emptyMap(), allowed: Collection<String> = emptyList()): JSONObject {
        val jar = CookieJar.parse(cookie, allowed)
        val result = SessionHarvest.build(provider, jar, storage, "local-device")
        assertTrue("expected a captured session, got $result", result is HarvestResult.Ready)
        return (result as HarvestResult.Ready).credentials
    }

    private fun missing(provider: String, cookie: String = "", storage: Map<String, String> = emptyMap(), allowed: Collection<String> = emptyList()) {
        val jar = CookieJar.parse(cookie, allowed)
        val result = SessionHarvest.build(provider, jar, storage, "local-device")
        assertTrue("expected an explicit failure, got $result", result is HarvestResult.Incomplete)
    }

    @Test fun cookieJarKeepsOnlyWhitelistedNames() {
        val jar = CookieJar.parse("account_id_v2=42; cookie_token_v2=abc; tracker=zzz; _ga=1", miyousheCookies)
        assertEquals(setOf("account_id_v2", "cookie_token_v2"), jar.keys)
        assertEquals("", CookieJar.pick(jar, "missing"))
        assertEquals("42", CookieJar.pick(jar, "account_id_v2"))
    }

    @Test fun miyousheSessionBecomesCookieCredential() {
        val c = ready(
            "miyoushe",
            "account_id_v2=9001; cookie_token_v2=tok; stoken_v2=st; mid_v2=mm; ltuid_v2=9001; mid=mm",
            allowed = miyousheCookies,
        )
        val cookie = c.getString("cookie")
        assertTrue(cookie.contains("account_id_v2=9001"))
        assertTrue(cookie.contains("cookie_token_v2=tok"))
        // renewal aliases the adapter reads
        assertTrue(cookie.contains("stuid=9001"))
        assertTrue(cookie.contains("stoken=st"))
        assertEquals("9001", c.getString("stuid"))
        assertEquals("local-device", c.getString("deviceId"))
    }

    @Test fun miyousheWithoutTokenIsNotSuccess() = missing("miyoushe", "account_id=1", allowed = miyousheCookies)

    @Test fun miyousheWithoutAccountIdIsNotSuccess() = missing("miyoushe", "cookie_token=abc", allowed = miyousheCookies)

    @Test fun sklandReadsOfficialWebStorageKeys() {
        val c = ready(
            "skland",
            storage = mapOf(
                "SK_OAUTH_CRED_KEY" to "\"cred-value\"",
                "SK_TOKEN_CACHE_KEY" to "sign-value",
                "SK_SHUMEI_DEVICE_ID_KEY" to "device-from-web",
            ),
        )
        assertEquals("cred-value", c.getString("cred"))
        assertEquals("sign-value", c.getString("signToken"))
        assertEquals("device-from-web", c.getString("dId"))
    }

    /** The signed context uses the official device id; a local/random UUID is never substituted for it. */
    @Test fun sklandWithoutOfficialDeviceIdIsNotReady() {
        val result = SessionHarvest.build(
            "skland",
            emptyMap(),
            mapOf("SK_OAUTH_CRED_KEY" to "cred", "SK_TOKEN_CACHE_KEY" to "sign"),
            "local-device",
        )
        assertTrue("a missing official device id must not become a Ready session: $result", result is HarvestResult.Incomplete)
        val missing = (result as HarvestResult.Incomplete).missing
        assertTrue("the message names the missing official identifier: $missing", missing.contains("设备标识"))
        assertFalse("the message must not announce an authorization failure", missing.contains("授权失效"))
        assertFalse("a local device id must not leak into the message", missing.contains("local-device"))
    }

    /** Same rule when the page stored the key as an empty/blank value. */
    @Test fun sklandWithBlankOfficialDeviceIdIsNotReady() {
        for (blank in listOf("", "  ", "\"\"")) {
            val result = SessionHarvest.build(
                "skland",
                emptyMap(),
                mapOf("SK_OAUTH_CRED_KEY" to "cred", "SK_TOKEN_CACHE_KEY" to "sign", "SK_SHUMEI_DEVICE_ID_KEY" to blank),
                "local-device",
            )
            assertTrue("blank device id \"$blank\" must be incomplete, got $result", result is HarvestResult.Incomplete)
        }
    }

    @Test fun sklandOfficialDeviceCacheUsesOnlyTheIdField() {
        val cache = """{"id":"device-from-web","timestamp":1793574000000}"""
        for (value in listOf(cache, JSONObject.quote(cache), "\"device-from-web\"")) {
            val webStorage = storage("cred", "sign", value)
            val credential = ready("skland", storage = webStorage)
            assertEquals("device-from-web", credential.getString("dId"))
            assertEquals("device-from-web", SessionHarvest.sklandSession(webStorage)?.dId)
        }
    }

    @Test fun sklandMalformedDeviceCacheCannotBecomeASignedSession() {
        for (value in listOf("{}", "{broken", "{\"id\":null}", "{\"id\":42}", "{\"id\":{}}", "{\"id\":\"  \"}", "[]", "null")) {
            val webStorage = storage("cred", "sign", value)
            missing("skland", storage = webStorage)
            assertNull(SessionHarvest.sklandSession(webStorage))
        }
    }

    @Test fun sklandDeviceCacheTimestampDoesNotChangeSessionIdentity() {
        val first = SessionHarvest.sklandSession(storage("c", "s", """{"id":"d","timestamp":1}"""))
        val next = SessionHarvest.sklandSession(storage("c", "s", """{"id":"d","timestamp":2}"""))
        val continuity = SessionHarvest.SklandContinuity()
        assertNull(continuity.accept(first))
        assertEquals(first, continuity.accept(next))
    }

    @Test fun sklandWithoutSignTokenIsNotSuccess() = missing("skland", storage = mapOf("SK_OAUTH_CRED_KEY" to "cred"))

    @Test fun kurobbsTokenFromCookie() {
        val c = ready("kurobbs", "user_token=kuro-token; other=1", allowed = kuroCookies)
        assertEquals("kuro-token", c.getString("token"))
        assertTrue(c.getBoolean("communityCheckin"))
    }

    @Test fun kurobbsFallsBackToWebStorage() {
        val c = ready("kurobbs", storage = mapOf("user_token" to "\"storage-token\""))
        assertEquals("storage-token", c.getString("token"))
    }

    @Test fun kurobbsWithoutTokenIsNotSuccess() = missing("kurobbs")

    @Test fun taggedPrefersRefreshTokenOnReturn() {
        val c = ready(
            "tagged",
            "uid=777; deviceid=dev",
            mapOf("refreshToken" to "refresh-1", "accessToken" to "access-1"),
            taggedCookies,
        )
        assertEquals("refresh-1", c.getString("refreshToken"))
        assertFalse(c.has("_accessToken"))
        assertEquals("777", c.getString("uid"))
        assertEquals("dev", c.getString("deviceId"))
    }

    @Test fun taggedWithoutAnyTokenIsNotSuccess() = missing("tagged", "uid=777", allowed = taggedCookies)

    @Test fun credentialsNeverCarryPasswordOrCaptchaFields() {
        val inputs = listOf(
            ready("miyoushe", "account_id=1; cookie_token=t", allowed = miyousheCookies),
            ready(
                "skland",
                storage = mapOf(
                    "SK_OAUTH_CRED_KEY" to "c",
                    "SK_TOKEN_CACHE_KEY" to "s",
                    "SK_SHUMEI_DEVICE_ID_KEY" to "official-device",
                ),
            ),
            ready("kurobbs", "user_token=t", allowed = kuroCookies),
        )
        val forbidden = setOf("password", "passwd", "pwd", "smscode", "sms_code", "captcha", "otp")
        inputs.forEach { json ->
            val keys = json.keys().asSequence().map { it.lowercase() }.toSet()
            assertTrue(keys.none { it in forbidden })
            assertEquals("web-login", json.getString("source"))
        }
    }

    @Test fun unknownProviderIsRejected() = missing("unknown", "a=b")

    @Test fun navigationWhitelistBlocksForeignHosts() {
        val miyoushe = LoginPlatforms.of("miyoushe")
        assertTrue(LoginPlatforms.allows(miyoushe, "https://user.mihoyo.com/#/login"))
        assertTrue(LoginPlatforms.allows(miyoushe, "https://api-takumi.mihoyo.com/x"))
        assertFalse(LoginPlatforms.allows(miyoushe, "https://mihoyo.com.evil.test/login"))
        assertFalse(LoginPlatforms.allows(miyoushe, "https://evilmihoyo.com/login"))
        assertFalse(LoginPlatforms.allows(miyoushe, "http://user.mihoyo.com/#/login"))
        assertFalse(LoginPlatforms.allows(miyoushe, null))
    }

    @Test fun sklandAllowsAccountDomainButNotOthers() {
        val skland = LoginPlatforms.of("skland")
        assertTrue(LoginPlatforms.allows(skland, "https://www.skland.com/"))
        assertTrue(LoginPlatforms.allows(skland, "https://as.hypergryph.com/user/oauth2/v2/grant"))
        assertFalse(LoginPlatforms.allows(skland, "https://example.com/"))
    }

    @Test fun redactDropsQueryAndFragment() {
        assertEquals("user.mihoyo.com/", LoginPlatforms.redact("https://user.mihoyo.com/?code=SECRET#/login"))
        assertEquals("", LoginPlatforms.redact(null))
    }

    // --- Skland session continuity (timing, not an authorization claim) ---------------------------

    private fun storage(cred: String?, sign: String?, did: String?): Map<String, String> = buildMap {
        cred?.let { put("SK_OAUTH_CRED_KEY", it) }
        sign?.let { put("SK_TOKEN_CACHE_KEY", it) }
        did?.let { put("SK_SHUMEI_DEVICE_ID_KEY", it) }
    }

    @Test fun sklandSessionTripleRequiresAllThreeValues() {
        assertEquals("c", SessionHarvest.sklandSession(storage("c", "s", "d"))?.cred)
        assertEquals("d", SessionHarvest.sklandSession(storage("c", "s", "d"))?.dId)
        assertNull("cred alone is not a session", SessionHarvest.sklandSession(storage("c", null, "d")))
        assertNull("a missing official device id is not a session", SessionHarvest.sklandSession(storage("c", "s", null)))
        assertNull("blank values are not a session", SessionHarvest.sklandSession(storage("c", "s", "  ")))
    }

    @Test fun sklandHandsOverOnlyAfterTwoIdenticalConsecutiveReads() {
        val c = SessionHarvest.SklandContinuity()
        val one = SessionHarvest.sklandSession(storage("c1", "s1", "d1"))
        assertNull("the first read is only a sample", c.accept(one))
        assertEquals("the second identical read confirms", one, c.accept(one))
    }

    @Test fun sklandMidRewriteSampleIsDiscarded() {
        val c = SessionHarvest.SklandContinuity()
        // A page rewrite that produced a fresh cred with the previous sign token must never be accepted.
        val torn = SessionHarvest.sklandSession(storage("c2", "s1", "d1"))
        val settled = SessionHarvest.sklandSession(storage("c2", "s2", "d1"))
        assertNull(c.accept(torn))
        assertNull("values changed, so the pair restarts", c.accept(settled))
        assertEquals("only the settled read observed twice is handed over", settled, c.accept(settled))
    }

    @Test fun sklandMissingValueRestartsThePair() {
        val c = SessionHarvest.SklandContinuity()
        val good = SessionHarvest.sklandSession(storage("c1", "s1", "d1"))
        assertNull(c.accept(good))
        assertNull("a read without the official device id clears the sample", c.accept(null))
        assertNull("the old sample cannot be reused after a gap", c.accept(good))
        assertEquals(good, c.accept(good))
    }

    @Test fun switchingAccountInvalidatesBothSampleAndConfirmation() {
        val c = SessionHarvest.SklandContinuity()
        val first = SessionHarvest.sklandSession(storage("c1", "s1", "d1"))
        val second = SessionHarvest.sklandSession(storage("c2", "s2", "d2"))
        c.accept(first); c.accept(first)
        assertEquals(first, c.confirmed())

        // Switching account: the pending sample and the confirmation both belong to the old session.
        c.reset()
        assertNull("reset clears the previous session", c.confirmed())
        assertNull("a new account starts a new pair", c.accept(second))
        assertEquals(second, c.accept(second))
        assertEquals("only the new account is confirmed", second, c.confirmed())
    }

    @Test fun aConfirmedSessionIsNotRevokedByALaterTornRead() {
        val c = SessionHarvest.SklandContinuity()
        val good = SessionHarvest.sklandSession(storage("c1", "s1", "d1"))
        c.accept(good); c.accept(good)
        val torn = SessionHarvest.sklandSession(storage("c1", null, "d1"))
        assertNull("a torn read yields no new confirmation", c.accept(torn))
        assertEquals("but the session already confirmed stays confirmed", good, c.confirmed())
    }

    @Test fun continuityStateIsNeverSerializedIntoTheCredential() {
        // The comparison lives in memory only: a built credential must not carry any continuity marker.
        val c = ready(
            "skland",
            storage = mapOf(
                "SK_OAUTH_CRED_KEY" to "cred",
                "SK_TOKEN_CACHE_KEY" to "sign",
                "SK_SHUMEI_DEVICE_ID_KEY" to "official-device",
            ),
        )
        val keys = c.keys().asSequence().toSet()
        assertEquals(setOf("cred", "signToken", "dId", "games", "communityCheckin", "source"), keys)
    }
}
