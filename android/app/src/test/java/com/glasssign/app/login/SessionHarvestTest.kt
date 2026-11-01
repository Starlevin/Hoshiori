package com.glasssign.app.login

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test fun sklandFallsBackToLocalDeviceWhenWebHasNone() {
        val c = ready("skland", storage = mapOf("SK_OAUTH_CRED_KEY" to "cred", "SK_TOKEN_CACHE_KEY" to "sign"))
        assertEquals("local-device", c.getString("dId"))
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
            ready("skland", storage = mapOf("SK_OAUTH_CRED_KEY" to "c", "SK_TOKEN_CACHE_KEY" to "s")),
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
}
