package com.glasssign.app.ui.update

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in device check against the real public GitHub release, without starting an installation. */
class UpdateDownloadDeviceTest {
    @Test fun realApkDownloadMatchesItsDigestAndTheInstalledSigner() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("deviceDownload") == "true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val reply=OkHttpUpdateTransport().get(UpdateUrls.LATEST_RELEASE_URL)
        val (candidate,reason)=ReleasePolicy.select("0.0.0",ReleaseJson.parseReleases(reply.body))
        assertNotNull("No usable public APK: $reason",candidate)
        val asset=candidate!!.apk
        val fileName="device-network-verification.apk"
        val dir=UpdateCache.dir(context.cacheDir)
        val transport=OkHttpUpdateDownloadTransport(
            OkHttpUpdateDownloadTransport.buildClient().newBuilder()
                .callTimeout(60,TimeUnit.SECONDS).build())
        try {
            val downloaded=UpdateDownloader(transport,asset.sizeBytes,asset.sha256)
                .download(asset.browserDownloadUrl,dir,fileName,UpdateDownloadToken())
            assertTrue("Real download failed: $downloaded",downloaded is UpdateDownloadResult.Success)
            val result=downloaded as UpdateDownloadResult.Success
            assertEquals(asset.sizeBytes,result.sizeBytes)
            assertEquals(ReleaseDigest.hex(asset.sha256),result.sha256)
            val reader=PlatformUpdateApkReader(context)
            val installed=reader.readInstalled()
            val archive=reader.readApk(result.file,result.sha256)
            assertNotNull(installed)
            assertNotNull(archive)
            assertEquals(context.packageName,archive!!.packageName)
            assertEquals(installed!!.signingCertSha256.toSet(),archive.signingCertSha256.toSet())
            val uri=UpdateIntents(context).installView(result.file).data!!
            assertEquals("content",uri.scheme)
            assertEquals(context.packageName+".updates.fileprovider",uri.authority)
            val providerHash=context.contentResolver.openInputStream(uri)!!.use { input ->
                val digest=MessageDigest.getInstance("SHA-256")
                val buffer=ByteArray(65536)
                while(true){val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n)}
                digest.digest().joinToString(""){"%02x".format(it)}
            }
            assertEquals(result.sha256,providerHash)
        } finally {
            File(dir,fileName).delete()
            dir.listFiles()?.filter{it.name.startsWith(fileName)&&it.name.endsWith(".part")}
                ?.forEach{it.delete()}
        }
    }
}
