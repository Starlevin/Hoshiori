package com.glasssign.app.local

import org.junit.Assert.assertEquals
import org.junit.Test

/** Runs against Android org.json, whose slash escaping differs from the JVM test library. */
class SklandSigningAndroidTest {
    @Test fun slashInOfficialDeviceIdMatchesWebSignature() {
        val context = Signing.sklandContext("1", "1700000000", "device/a+b==", "1.5.1")
        assertEquals("""{"platform":"1","timestamp":"1700000000","dId":"device/a+b==","vName":"1.5.1"}""", context)
        // Independent Python hmac/hashlib vector using compact json.dumps, not the production helper.
        assertEquals("5dc13fa12ebda862bd803c21009e37c8", Signing.skland(
            "/api/v1/game/player/binding", "", "1700000000", context, "sign-token"))
    }

    @Test fun contextStillEscapesQuotesBackslashesAndControlCharacters() {
        val context = Signing.sklandContext("3", "1700000000", "a\\/b\"c\nd", "1.0.0")
        assertEquals("""{"platform":"3","timestamp":"1700000000","dId":"a\\/b\"c\nd","vName":"1.0.0"}""", context)
    }
}
