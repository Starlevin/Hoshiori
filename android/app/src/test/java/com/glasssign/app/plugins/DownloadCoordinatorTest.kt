package com.glasssign.app.plugins

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * The download → verify → atomic-rename pipeline, driven by a scripted source and a REAL temporary
 * directory (`TemporaryFolder` is JUnit4, no Android needed).
 *
 * Why these cases are worth running rather than mocking:
 *  - They observe the actual filesystem after the fact: "a cancelled download leaves no `.part`" and
 *    "a failed verification never produces a final `.apk`" are properties of files, not of call
 *    counts, so a passing test cannot be a mirror of the implementation's internal bookkeeping.
 *  - The "good" case uses a *self-consistent* artifact spec (size/digest/package/version/cert all
 *    derived from the bytes the fake source emits), so a verifier that always fails would break the
 *    positive case too. The negative case then flips exactly one byte and expects a failure.
 *  - No socket, no okhttp, no Compose, no android.* — the same code path the app uses, minus the
 *    transport.
 */
class DownloadCoordinatorTest {

    @get:Rule val temp = TemporaryFolder()

    private lateinit var dir: File
    private lateinit var payload: ByteArray
    private lateinit var spec: PluginArtifact

    @Before
    fun setUp() {
        dir = temp.newFolder("plugins")
        payload = "synthetic-apk-bytes-for-the-download-test".toByteArray()
        // The artifact the fake source is about to serve, described exactly. Nothing is "close
        // enough": every field is computed from the same bytes.
        //
        // Package/version/certificate are left unpinned here because this file tests the
        // transfer/atomicity/cleanup contract against plain bytes, not the manifest identity: the
        // metadata reader is the injected seam, and [unsignedReader] below reports nothing, which
        // must NOT be mistaken for a pass when the spec does pin a package (see
        // [anArtifactWithADifferentPinnedPackageIsRejectedAfterTheTransfer]).
        spec = PluginArtifact(
            sizeBytes = payload.size.toLong(),
            sha256 = Crypto.sha256(payload),
            packageName = null,
            versionCode = null,
            versionName = null,
            signingCertSha256 = null,
            downloadUrl = PluginSpec.DOWNLOAD_URL,
        )
    }

    @After
    fun tearDown() {
        temp.delete()
    }

    // --- fakes ----------------------------------------------------------------------------------

    /** The injected platform seam: production is `PackageManager`, tests are explicit. */
    private class FakeMetadata(private val facts: ApkFacts?) : ApkMetadataReader {
        override fun read(file: File): ApkFacts? = facts
    }

    /** An archive the platform could not read at all: no package, no version, no certificate. */
    private fun unsignedReader() = FakeMetadata(ApkFacts(0, "", null, null, null, emptyList()))

    /**
     * The real `java.io` behaviour, spelled out instead of imported so this test depends only on
     * what it actually exercises: an atomic rename on one filesystem, a delete, and a length.
     *
     * `java.io.File` does not override `equals`, so each operation re-derives the handle from its
     * path — the same identity the operating system uses. A double comparing object identity would
     * "fail" an ordinary rename and hide the real behaviour.
     */
    private class JavaIoFileSystem : PluginFileSystem {
        override fun file(name: String) = File(name)
        override fun exists(file: File) = File(file.path).exists()
        override fun mkdirs(dir: File) = File(dir.path).let { it.mkdirs() || it.isDirectory }
        override fun openWrite(file: File, append: Boolean): OutputStream = FileOutputStream(file.path, append)
        override fun openRead(file: File): InputStream = FileInputStream(file.path)
        override fun length(file: File) = File(file.path).length()
        override fun delete(file: File) = File(file.path).let { !it.exists() || it.delete() }
        override fun rename(from: File, to: File) = File(from.path).renameTo(File(to.path))
    }

    /** Cancel token: the coordinator only needs something it can hand to the source. */
    private class Token : DownloadHandle {
        @Volatile var cancelled = false
        override fun cancel() { cancelled = true }
    }

    /** Serves [bytes] in [chunk]s, honouring the cancel token, and reports progress as it writes. */
    private class ScriptedSource(
        private val bytes: ByteArray,
        private val chunk: Int = 7,
        private val cancelAfterBytes: Long = -1,
        private val finalUrl: String = PluginSpec.DOWNLOAD_URL,
    ) : DownloadSource {
        var opened = 0

        override fun open(expectedUrl: String, sink: OutputStream, handle: DownloadHandle, onProgress: (Long) -> Unit): String {
            opened++
            var written = 0L
            while (written < bytes.size) {
                if (cancelAfterBytes >= 0 && written >= cancelAfterBytes) throw PluginCancelledException()
                val to = minOf(bytes.size.toLong(), written + chunk).toInt()
                sink.write(bytes, written.toInt(), to - written.toInt())
                written = to.toLong()
                onProgress(written)
            }
            return finalUrl
        }
    }

    private fun coordinator(
        source: DownloadSource,
        artifact: PluginArtifact = spec,
        metadata: ApkMetadataReader = unsignedReader(),
    ) = DownloadCoordinator(
        source = source,
        fs = JavaIoFileSystem(),
        metadata = metadata,
        finalName = PluginSpec.APK_FILE,
        artifact = artifact,
    )

    // --- cancel -----------------------------------------------------------------------------------

    @Test
    fun cancellingDeletesThePartFileAndLeavesNoFinalApk() {
        val source = ScriptedSource(payload, chunk = 4, cancelAfterBytes = 8)
        val result = coordinator(source).download(dir, Token()) { }

        assertTrue("a cancel is reported as a cancel", result.cancelled)
        assertFalse("a cancelled transfer has nothing installable", result.ok)
        assertNull("a cancel is not an error state to retry", result.apk)
        assertEquals(PluginState.FAIL_CANCELLED, result.failedReason)
        assertFalse("no final apk", File(dir, PluginSpec.APK_FILE).exists())
        assertFalse("no .part left behind", File(dir, PluginSpec.APK_FILE + PluginSpec.PART_SUFFIX).exists())
        assertEquals("nothing else was written", 0, dir.listFiles().orEmpty().size)
    }

    // --- transport failures ------------------------------------------------------------------------

    @Test
    fun aTransportFailureDeletesThePartFileAndKeepsTheReason() {
        val source = object : DownloadSource {
            override fun open(expectedUrl: String, sink: OutputStream, handle: DownloadHandle, onProgress: (Long) -> Unit): String {
                sink.write(payload, 0, payload.size / 2)
                onProgress((payload.size / 2).toLong())
                throw IOException("连接被重置")
            }
        }
        val result = coordinator(source).download(dir, Token()) { }
        assertFalse(result.ok)
        assertEquals("连接被重置", result.failedReason)
        assertFalse("half a file must not stay", File(dir, PluginSpec.APK_FILE + PluginSpec.PART_SUFFIX).exists())
        assertEquals(0, dir.listFiles().orEmpty().size)
    }

    @Test
    fun aRedirectAwayFromThePinnedHostFailsEverything() {
        val source = ScriptedSource(payload, finalUrl = "https://untrusted.example/x.apk")
        val result = coordinator(source).download(dir, Token()) { }
        assertFalse(result.ok)
        assertTrue(
            "the message must name the host, not echo the url: ${result.failedReason}",
            result.failedReason.orEmpty().contains("untrusted.example"),
        )
        assertFalse(File(dir, PluginSpec.APK_FILE).exists())
        assertFalse(File(dir, PluginSpec.APK_FILE + PluginSpec.PART_SUFFIX).exists())
    }

    @Test
    fun githubReleaseCdnIsAcceptedOnlyWhenTheBytesMatch() {
        val url="https://release-assets.githubusercontent.com/github-production-release-asset/file.apk"
        val result=coordinator(ScriptedSource(payload,finalUrl=url)).download(dir,Token()){}
        assertTrue(result.failedReason,result.ok)
        org.junit.Assert.assertArrayEquals(payload,result.apk!!.readBytes())
        val corrupted=payload.copyOf().also{it[0]=(it[0].toInt() xor 1).toByte()}
        val rejected=coordinator(ScriptedSource(corrupted,finalUrl=url)).download(dir,Token()){}
        assertFalse(rejected.ok)
        assertTrue(rejected.failedReason.orEmpty().contains("SHA"))
    }

    @Test
    fun aPlainHttpRedirectIsRefused() {
        val source = ScriptedSource(payload, finalUrl = "http://github.com/LingQian.apk")
        val result = coordinator(source).download(dir, Token()) { }
        assertFalse("cleartext must never be accepted", result.ok)
        assertEquals(0, dir.listFiles().orEmpty().size)
    }

    // --- verification -------------------------------------------------------------------------------

    @Test
    fun bytesThatPassEveryCheckBecomeTheFinalApk() {
        val source = ScriptedSource(payload)
        val result = coordinator(source).download(dir, Token()) { }

        assertTrue("the good payload must pass", result.ok)
        assertNull(result.failedReason)
        val finalFile = File(dir, PluginSpec.APK_FILE)
        assertTrue(finalFile.isFile)
        assertEquals(payload.size.toLong(), finalFile.length())
        assertFalse("the temporary must be gone after the rename", File(dir, PluginSpec.APK_FILE + PluginSpec.PART_SUFFIX).exists())
    }

    @Test
    fun oneFlippedByteFailsVerificationAndDeletesTheFile() {
        val tampered = payload.copyOf()
        tampered[tampered.size - 1] = (tampered[tampered.size - 1] + 1).toByte()
        val result = coordinator(ScriptedSource(tampered)).download(dir, Token()) { }

        assertFalse(result.ok)
        assertTrue(
            "the failure must name the digest: ${result.failedReason}",
            result.failedReason.orEmpty().startsWith(PluginState.FAIL_VERIFY),
        )
        assertTrue(result.failedReason.orEmpty().contains("SHA-256"))
        assertFalse("a rejected file must not remain as installable", File(dir, PluginSpec.APK_FILE).exists())
        assertFalse(File(dir, PluginSpec.APK_FILE + PluginSpec.PART_SUFFIX).exists())
    }

    @Test
    fun aShortTransferFailsOnSizeEvenWhenThePrefixHashesCleanly() {
        // The source serves a strict prefix of the pinned payload: the bytes are real, the file is
        // simply incomplete. A verifier that only checked a prefix would accept this.
        val truncated = payload.copyOf(payload.size - 3)
        val result = coordinator(ScriptedSource(truncated)).download(dir, Token()) { }

        assertFalse(result.ok)
        assertTrue("size must be the reported culprit", result.failedReason.orEmpty().contains("文件大小"))
        assertFalse(File(dir, PluginSpec.APK_FILE).exists())
    }

    @Test
    fun anArtifactWithADifferentPinnedPackageIsRejectedAfterTheTransfer() {
        // Same bytes, same digest, but the pinned package says something else. The file has no
        // readable APK manifest at all, so the package check must fail — proving that adding an
        // expectation really enables the corresponding gate (and that a missing value fails).
        val wrongPackage = spec.copy(packageName = "com.example.other")
        val result = coordinator(ScriptedSource(payload), artifact = wrongPackage).download(dir, Token()) { }

        assertFalse(result.ok)
        assertTrue(
            "the package check must be the one that fails: ${result.failedReason}",
            result.failedReason.orEmpty().contains("包名"),
        )
        assertFalse(File(dir, PluginSpec.APK_FILE).exists())
    }

    @Test
    fun anArtifactWithAPinnedSignatureRejectsAnUnsignedFixture() {
        // The production artifact pins a certificate; a synthetic file carries none. That must fail
        // on the signature field, not be skipped.
        val pinned = spec.copy(signingCertSha256 = "d0".repeat(32))
        val result = coordinator(ScriptedSource(payload), artifact = pinned).download(dir, Token()) { }

        assertFalse(result.ok)
        assertTrue(
            "the signature check must fire: ${result.failedReason}",
            result.failedReason.orEmpty().contains("签名证书"),
        )
        assertFalse(File(dir, PluginSpec.APK_FILE).exists())
    }

    // --- leftovers from a previous process ----------------------------------------------------------

    @Test
    fun aStalePartFileFromAKilledProcessIsSweptBeforeAndReplaced() {
        val part = File(dir, PluginSpec.APK_FILE + PluginSpec.PART_SUFFIX)
        part.writeBytes(ByteArray(64) { 0x7F })
        val coordinator = coordinator(ScriptedSource(payload))

        coordinator.sweepPart(dir)
        assertFalse("a sweep removes the stale temporary", part.exists())

        // Even without an explicit sweep, a new attempt must never append to unknown bytes.
        part.writeBytes(ByteArray(64) { 0x7F })
        val result = coordinator.download(dir, Token()) { }
        assertTrue(result.ok)
        assertEquals("the final file holds exactly the served bytes", payload.size.toLong(), File(dir, PluginSpec.APK_FILE).length())
        assertFalse(part.exists())
    }

    @Test
    fun progressNeverExceedsThePayloadAndIsMonotonic() {
        val seen = mutableListOf<Long>()
        val source = ScriptedSource(payload, chunk = 5)
        val result = coordinator(source).download(dir, Token()) { seen.add(it) }

        assertTrue(result.ok)
        assertTrue("progress must be reported", seen.isNotEmpty())
        assertEquals("the last sample is the whole payload", payload.size.toLong(), seen.last())
        assertEquals("monotonic", seen.sorted(), seen)
        assertTrue("never exceeds the pinned size", seen.all { it <= spec.sizeBytes })
    }

    // --- durable directory ---------------------------------------------------------------------------

    @Test
    fun aCleanRemovesTheDownloadedFileByName() {
        val coordinator = coordinator(ScriptedSource(payload))
        assertTrue(coordinator.download(dir, Token()) { }.ok)
        assertTrue(File(dir, PluginSpec.APK_FILE).exists())

        assertTrue(coordinator.delete(PluginSpec.APK_FILE, dir))
        assertFalse(File(dir, PluginSpec.APK_FILE).exists())
    }
}
