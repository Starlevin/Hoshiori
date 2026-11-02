package com.glasssign.app.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * The size + digest + manifest + signature gate.
 *
 * Why these cases are meaningful and not a mirror of the implementation:
 *  - The digest assertions use REAL SHA-256 values of REAL byte strings ("hello" ->
 *    2cf24dba…9824). If [Crypto] or the comparison logic were wrong, the "matches" case would fail
 *    too, so "it always fails" or "it always passes" cannot both survive.
 *  - Every negative case flips exactly ONE field, so a pass would prove that field is not checked.
 *  - The result object is the only thing the install path reads (`installable`), so asserting it
 *    is false is asserting that a failed verification cannot reach the installer.
 */
class PluginVerifierTest {

    private val hello = "hello".toByteArray()
    private val helloBang = "hello!".toByteArray()

    /** Same shape as the pinned LingQian spec, but with a 5-byte artifact we can hash by hand. */
    private val spec = PluginArtifact(
        sizeBytes = hello.size.toLong(),
        sha256 = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
        packageName = "com.qinghan.aicheckin",
        versionCode = 1L,
        versionName = "v0.1.0",
        signingCertSha256 = "d001e1da5be239c3fdcee2aa3beb937c0da90cc1aba6e522cde9e7fa237cdf85",
        downloadUrl = PluginSpec.DOWNLOAD_URL,
    )

    private fun goodFacts(bytes: ByteArray = hello) = ApkFacts(
        sizeBytes = bytes.size.toLong(),
        sha256 = Crypto.sha256(bytes),
        packageName = "com.qinghan.aicheckin",
        versionCode = 1L,
        versionName = "v0.1.0",
        signingCertSha256 = listOf("d001e1da5be239c3fdcee2aa3beb937c0da90cc1aba6e522cde9e7fa237cdf85"),
    )

    // --- the hash function itself --------------------------------------------------------------

    @Test
    fun sha256OfRealBytesMatchesThePublishedValue() {
        // Ground truth, not a self-reference: SHA-256("hello") is a well-known constant.
        assertEquals(
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            Crypto.sha256(ByteArrayInputStream(hello)),
        )
        assertEquals(
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            Crypto.sha256(hello),
        )
        // One extra byte must change the digest completely (avalanche): this is what catches a
        // truncated or padded download.
        assertNotEquals(Crypto.sha256(hello), Crypto.sha256(helloBang))
    }

    @Test
    fun aSingleFlippedByteFailsTheDigestCheck() {
        // "hello" vs "hello!" = one appended byte; the size differs too, so remove that difference
        // by measuring an explicitly equal-sized but different payload.
        val tampered = byteArrayOf('h'.code.toByte(), 'e'.code.toByte(), 'l'.code.toByte(), 'l'.code.toByte(), 'o'.code.toByte())
        tampered[0] = 'j'.code.toByte() // "jello": same length, one byte different
        val facts = goodFacts(tampered)

        val report = PluginVerifier.verifyDownloaded(facts, spec)
        assertTrue("a flipped byte must fail", report is VerifyReport.Failed)
        assertFalse("a failed verification is not installable", report.installable)
        val fields = (report as VerifyReport.Failed).reasons.map { it.field }
        assertEquals("only the digest field may differ", listOf(VerifyField.SHA256), fields)
    }

    // --- size -----------------------------------------------------------------------------------

    @Test
    fun aSizeMismatchFailsAndSaysExpectedVersusActual() {
        // Perfect digest of real bytes, but one byte more than the pinned size.
        val facts = goodFacts().copy(sizeBytes = hello.size + 1L)
        val report = PluginVerifier.verifyDownloaded(facts, spec)
        assertTrue(report is VerifyReport.Failed)
        assertFalse(report.installable)
        val reason = (report as VerifyReport.Failed).reasons.single()
        assertEquals(VerifyField.SIZE, reason.field)
        assertTrue("the message must state the expectation: ${reason.sentence()}", reason.sentence().contains("不符"))
        assertTrue("expected size must be visible: ${reason.sentence()}", reason.sentence().contains("5 字节"))
        assertTrue("actual size must be visible: ${reason.sentence()}", reason.sentence().contains("6 字节"))
    }

    // --- manifest identity ----------------------------------------------------------------------

    @Test
    fun aDifferentPackageNameIsRejected() {
        val report = PluginVerifier.verifyDownloaded(goodFacts().copy(packageName = "com.example.evil"), spec)
        assertSingleFailure(report, VerifyField.PACKAGE)
        assertTrue((report as VerifyReport.Failed).detail.contains("com.example.evil"))
    }

    @Test
    fun aMissingPackageNameIsRejectedNotIgnored() {
        // `null` here means "the APK manifest could not be read". Skipping the check would install
        // whatever the installer could open, so absence must fail closed.
        val report = PluginVerifier.verifyDownloaded(goodFacts().copy(packageName = null), spec)
        assertSingleFailure(report, VerifyField.PACKAGE)
        assertTrue((report as VerifyReport.Failed).detail.contains("缺失"))
    }

    @Test
    fun aDifferentVersionCodeIsRejected() {
        val report = PluginVerifier.verifyDownloaded(goodFacts().copy(versionCode = 2L), spec)
        assertSingleFailure(report, VerifyField.VERSION_CODE)
        assertTrue((report as VerifyReport.Failed).detail.contains("期望 1"))
    }

    @Test
    fun aDifferentVersionNameIsRejectedForTheArtifact() {
        val report = PluginVerifier.verifyDownloaded(goodFacts().copy(versionName = "v0.2.0"), spec)
        assertSingleFailure(report, VerifyField.VERSION_NAME)
    }

    // --- signature ------------------------------------------------------------------------------

    @Test
    fun aDifferentSigningCertificateIsRejected() {
        val report = PluginVerifier.verifyDownloaded(
            goodFacts().copy(signingCertSha256 = listOf("00".repeat(32))),
            spec,
        )
        assertSingleFailure(report, VerifyField.SIGNATURE)
        val detail = (report as VerifyReport.Failed).detail
        assertTrue("the refusal must mention the signature: $detail", detail.contains("签名证书"))
        assertFalse("a full certificate hash must not be echoed", detail.contains("00".repeat(32)))
    }

    @Test
    fun anUnsignedApkIsRejected() {
        val report = PluginVerifier.verifyDownloaded(goodFacts().copy(signingCertSha256 = emptyList()), spec)
        assertSingleFailure(report, VerifyField.SIGNATURE)
    }

    @Test
    fun digestComparisonIsCaseInsensitiveAndStrictAboutContent() {
        val upper = goodFacts().copy(sha256 = "2CF24DBA5FB0A30E26E83B2AC5B9E29E1B161E5C1FA7425E73043362938B9824")
        assertTrue("hex case must not matter", PluginVerifier.verifyDownloaded(upper, spec) is VerifyReport.Passed)

        // Separator tolerance is NOT claimed: the verifier compares plain hex, so a future "helpful"
        // normaliser cannot silently widen the match. `apksigner` prints colons; the constant does not.
        val colonized = goodFacts().copy(
            signingCertSha256 = listOf("d0:01:e1:da:5b:e2:39:c3:fd:ce:e2:aa:3b:eb:93:7c:0d:a9:0c:c1:ab:a6:e5:22:cd:e9:e7:fa:23:7c:df:85"),
        )
        assertTrue(PluginVerifier.verifyDownloaded(colonized, spec) is VerifyReport.Failed)

        val offByOneChar = goodFacts().copy(sha256 = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9825")
        assertTrue(PluginVerifier.verifyDownloaded(offByOneChar, spec) is VerifyReport.Failed)
    }

    // --- installed-copy verification (used before every launch) ---------------------------------

    @Test
    fun theInstalledCopyIsVerifiedAgainstTheSamePinnedKey() {
        val installed = InstalledFacts(
            packageName = "com.qinghan.aicheckin",
            versionCode = 1L,
            versionName = "v0.1.0",
            signingCertSha256 = listOf("d001e1da5be239c3fdcee2aa3beb937c0da90cc1aba6e522cde9e7fa237cdf85"),
        )
        assertTrue(PluginVerifier.verifyInstalled(installed, spec) is VerifyReport.Passed)
    }

    @Test
    fun aReSignedInstalledCopyIsNotLaunchable() {
        val installed = InstalledFacts(
            packageName = "com.qinghan.aicheckin",
            versionCode = 1L,
            versionName = "v0.1.0",
            signingCertSha256 = listOf("aa".repeat(32)),
        )
        val report = PluginVerifier.verifyInstalled(installed, spec)
        assertTrue(report is VerifyReport.Failed)
        assertEquals(VerifyField.SIGNATURE, (report as VerifyReport.Failed).reasons.single().field)
    }

    @Test
    fun anInstalledCopyWithTheWrongVersionIsRejected() {
        val installed = InstalledFacts(
            packageName = "com.qinghan.aicheckin",
            versionCode = 7L,
            versionName = "v0.1.0",
            signingCertSha256 = listOf("d001e1da5be239c3fdcee2aa3beb937c0da90cc1aba6e522cde9e7fa237cdf85"),
        )
        val report = PluginVerifier.verifyInstalled(installed, spec)
        assertTrue(report is VerifyReport.Failed)
        assertEquals(VerifyField.VERSION_CODE, (report as VerifyReport.Failed).reasons.single().field)
    }

    @Test
    fun aMissingInstalledCopyFailsClosed() {
        val report = PluginVerifier.verifyInstalled(null, spec)
        assertTrue(report is VerifyReport.Failed)
        assertFalse(report.installable)
    }

    @Test
    fun anInstalledCopyWithoutReadableSignaturesIsRejected() {
        // API 26/27 used GET_SIGNATURES; a build that reports none must not be treated as trusted.
        val installed = InstalledFacts("com.qinghan.aicheckin", 1L, "v0.1.0", emptyList())
        assertEquals(
            VerifyField.SIGNATURE,
            (PluginVerifier.verifyInstalled(installed, spec) as VerifyReport.Failed).reasons.single().field,
        )
    }

    // --- helpers --------------------------------------------------------------------------------

    private fun assertSingleFailure(report: VerifyReport, field: VerifyField) {
        assertTrue("expected a failure for $field, got $report", report is VerifyReport.Failed)
        assertFalse("a failed verification must never be installable", report.installable)
        assertEquals(listOf(field), (report as VerifyReport.Failed).reasons.map { it.field })
    }
}
