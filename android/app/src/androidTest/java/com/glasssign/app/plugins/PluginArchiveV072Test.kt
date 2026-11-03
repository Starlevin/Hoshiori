package com.glasssign.app.plugins

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class PluginArchiveV072Test {
    @Test fun officialApkPassesAndroidMetadataAndPinnedByteChecks() {
        val args=InstrumentationRegistry.getArguments()
        val path=args.getString("plugin_fixture").orEmpty()
        // Optional public fixture; no APK is bundled and no account or credential is read or changed.
        assumeTrue(path.isNotBlank())
        val file=File(path)
        assertTrue(file.isFile)
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val facts=AndroidApkMetadataReader(context.packageManager).read(file)
        assertNotNull("Android must parse the official APK",facts)
        val measured=facts!!.copy(sizeBytes=file.length(),sha256=file.inputStream().use{Crypto.sha256(it)})
        val result=PluginVerifier.verifyDownloaded(measured)
        assertTrue(result.detail,result is VerifyReport.Passed)
    }
}
