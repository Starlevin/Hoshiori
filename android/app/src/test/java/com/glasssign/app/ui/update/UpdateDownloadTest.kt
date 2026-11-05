package com.glasssign.app.ui.update

import com.glasssign.app.plugins.Crypto
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 下载层：用脚本化传输 + 真实临时目录，观察的是文件系统的事后状态，不是调用次数。
 *
 * 固定的不变量：
 *  - 取消/失败/HTML/大小不符之后，目录里不会留下半截 `.part`，也不会有最终 `.apk`；
 *  - 只有字节完整、摘要对得上、看起来是 ZIP 容器的文件才会被改名为最终文件名；
 *  - 进度只在写入真实字节之后上报，且不会超过声明大小。
 */
class UpdateDownloadTest {

    @get:Rule val temp = TemporaryFolder()

    private lateinit var dir: File
    private lateinit var payload: ByteArray

    private val url = "https://github.com/Starlevin/Hoshiori/releases/download/v0.7.9/Hoshiori-v0.7.9.apk"

    @Before
    fun setUp() {
        dir = temp.newFolder("updates")
        // 一段"像 APK"的字节：ZIP 本地文件头 + 随机体，尾部带中央目录标记。
        payload = ByteArray(70_000)
        Random(7L).nextBytes(payload)
        payload[0] = 0x50; payload[1] = 0x4B; payload[2] = 0x03; payload[3] = 0x04
        payload[payload.size - 22] = 0x50; payload[payload.size - 21] = 0x4B
        payload[payload.size - 20] = 0x05; payload[payload.size - 19] = 0x06
    }

    @After
    fun tearDown() {
        temp.delete()
    }

    /** 按 [chunk] 交付字节，可在第 N 字节后取消，或直接抛异常。 */
    private class ScriptedTransport(
        private val bytes: ByteArray,
        private val chunk: Int = 3_000,
        private val cancelAfter: Long = -1L,
        private val failure: IOException? = null,
        private val finalUrl: String? = null,
    ) : UpdateDownloadTransport {
        var opened = 0
        var lastProgress = 0L

        override fun stream(
            url: String,
            sink: OutputStream,
            token: UpdateDownloadToken,
            onProgress: (Long) -> Unit,
        ): String {
            opened++
            if (failure != null) throw failure
            var written = 0L
            while (written < bytes.size) {
                if (cancelAfter in 0..written) throw UpdateDownloadCancelledException()
                if (token.isCancelled) throw UpdateDownloadCancelledException()
                val next = minOf(bytes.size.toLong(), written + chunk).toInt()
                sink.write(bytes, written.toInt(), next - written.toInt())
                written = next.toLong()
                lastProgress = written
                onProgress(written)
            }
            return finalUrl ?: url
        }
    }

    private fun downloader(
        expectedSize: Long = payload.size.toLong(),
        expectedSha: String? = Crypto.sha256(payload),
        transport: UpdateDownloadTransport? = null,
    ) = if (transport == null) {
        UpdateDownloader(expectedSize = expectedSize, expectedSha256 = expectedSha)
    } else {
        UpdateDownloader(transport = transport, expectedSize = expectedSize, expectedSha256 = expectedSha)
    }

    private fun filesInDir() = dir.listFiles().orEmpty().map { it.name }.sorted()

    @Test
    fun aCompleteDownloadIsHashedAndRenamed() = runBlocking {
        val transport = ScriptedTransport(payload, finalUrl = "https://objects.githubusercontent.com/x/apk")
        var lastProgress = 0L
        val result = downloader(transport = transport).download(url, dir, "hoshiori-update-0.7.9.apk", UpdateDownloadToken()) { bytes, _ ->
            lastProgress = bytes
        }
        assertTrue(result is UpdateDownloadResult.Success)
        val success = result as UpdateDownloadResult.Success
        assertEquals(payload.size.toLong(), success.sizeBytes)
        assertEquals(Crypto.sha256(payload), success.sha256)
        assertTrue(success.file.isFile)
        // 最终文件名不存在 `.part` 残留。
        assertEquals(listOf("hoshiori-update-0.7.9.apk"), filesInDir())
        assertEquals(payload.size.toLong(), lastProgress)
    }

    @Test
    fun cancellingLeavesNeitherPartNorApk() = runBlocking {
        val transport = ScriptedTransport(payload, cancelAfter = 5_000)
        val token = UpdateDownloadToken()
        val result = downloader(transport = transport).download(url, dir, "hoshiori-update-0.7.9.apk", token)
        // 传输在 5000 字节处抛取消；结果必须是 Cancelled，而不是"下载失败"。
        assertTrue(result is UpdateDownloadResult.Cancelled)
        assertEquals(emptyList<String>(), filesInDir())
        assertEquals(1, transport.opened)
    }

    @Test
    fun anExternalCancelTokenStopsTheTransfer() = runBlocking {
        val transport = ScriptedTransport(payload, cancelAfter = 1_000)
        val token = UpdateDownloadToken()
        token.cancel()
        val result = downloader(transport = transport).download(url, dir, "hoshiori-update-0.7.9.apk", token)
        assertTrue(result is UpdateDownloadResult.Cancelled)
        assertEquals(emptyList<String>(), filesInDir())
    }

    @Test fun cancellingAtEndOfStreamDoesNotPromoteTheFile() = runBlocking {
        val transport=UpdateDownloadTransport { address,sink,token,progress ->
            sink.write(payload);progress(payload.size.toLong());token.cancel();address
        }
        val result=downloader(transport=transport).download(url,dir,"hoshiori-update-0.7.9.apk",UpdateDownloadToken())
        assertTrue(result is UpdateDownloadResult.Cancelled)
        assertEquals(emptyList<String>(),filesInDir())
    }

    @Test fun cancelledWriterCannotDeleteTheNextAttemptsFile() = runBlocking {
        val firstReady=CountDownLatch(1);val secondReady=CountDownLatch(1)
        val releaseFirst=CountDownLatch(1);val releaseSecond=CountDownLatch(1)
        val firstToken=UpdateDownloadToken()
        val name="hoshiori-update-0.7.9.apk"
        val old=async(Dispatchers.IO) {
            downloader(transport=UpdateDownloadTransport { _,sink,_,_ ->
                sink.write(payload,0,100);firstReady.countDown()
                check(releaseFirst.await(5,TimeUnit.SECONDS))
                throw IOException("cancelled old connection")
            }).download(url,dir,name,firstToken)
        }
        try {
            assertTrue(firstReady.await(5,TimeUnit.SECONDS))
            firstToken.cancel()
            val next=async(Dispatchers.IO) {
                downloader(transport=UpdateDownloadTransport { address,sink,_,progress ->
                    sink.write(payload);secondReady.countDown()
                    check(releaseSecond.await(5,TimeUnit.SECONDS))
                    progress(payload.size.toLong());address
                }).download(url,dir,name,UpdateDownloadToken())
            }
            assertTrue(secondReady.await(5,TimeUnit.SECONDS))
            releaseFirst.countDown();old.await()
            releaseSecond.countDown()
            assertTrue(next.await() is UpdateDownloadResult.Success)
            assertEquals(listOf(name),filesInDir())
        } finally { releaseFirst.countDown();releaseSecond.countDown() }
    }

    @Test
    fun htmlInsteadOfAnApkIsRefusedAndDeleted() = runBlocking {
        val html = "<!DOCTYPE html><html><body>rate limited</body></html>".toByteArray()
        val transport = ScriptedTransport(html)
        // 大小未公布（-1）、没有摘要、不设最小字节闸门：只剩内容检查这一道，正是要测的那道。
        val result = UpdateDownloader(transport = transport, expectedSize = -1L, expectedSha256 = null, minBytes = 0L)
            .download(url, dir, "hoshiori-update-0.7.9.apk", UpdateDownloadToken())
        assertTrue(result is UpdateDownloadResult.Failed)
        val failed = result as UpdateDownloadResult.Failed
        assertTrue(failed.error.message.contains("不是安装包"))
        assertFalse(failed.error.retryable)
        assertEquals(emptyList<String>(), filesInDir())
    }

    @Test
    fun tooSmallPayloadFailsTheDefaultFloor() = runBlocking {
        // 500 字节的"成功"响应几乎一定是错误页；最小字节闸门在算摘要之前就拦下来。
        val tiny = ByteArray(500) { 1 }
        val result = UpdateDownloader(transport = ScriptedTransport(tiny), expectedSize = -1L, expectedSha256 = null)
            .download(url, dir, "hoshiori-update-0.7.9.apk", UpdateDownloadToken())
        assertTrue(result is UpdateDownloadResult.Failed)
        assertTrue((result as UpdateDownloadResult.Failed).error.message.contains("不像是完整的安装包"))
        assertEquals(emptyList<String>(), filesInDir())
    }

    @Test
    fun sizeMismatchIsDeleted() = runBlocking {
        val transport = ScriptedTransport(payload)
        val result = UpdateDownloader(transport = transport, expectedSize = payload.size + 10L, expectedSha256 = null)
            .download(url, dir, "hoshiori-update-0.7.9.apk", UpdateDownloadToken())
        assertTrue(result is UpdateDownloadResult.Failed)
        assertTrue((result as UpdateDownloadResult.Failed).error.message.contains("大小不符"))
        assertEquals(emptyList<String>(), filesInDir())
    }

    @Test
    fun digestMismatchIsDeletedAndNotRetryable() = runBlocking {
        val transport = ScriptedTransport(payload)
        val result = UpdateDownloader(
            transport = transport,
            expectedSize = payload.size.toLong(),
            expectedSha256 = "sha256:" + "f".repeat(64),
        ).download(url, dir, "hoshiori-update-0.7.9.apk", UpdateDownloadToken())
        assertTrue(result is UpdateDownloadResult.Failed)
        val failed = result as UpdateDownloadResult.Failed
        assertTrue(failed.error.message.contains("SHA-256"))
        assertFalse(failed.error.retryable)
        assertEquals(emptyList<String>(), filesInDir())
    }

    @Test
    fun aRedirectAwayFromGithubIsDiscarded() = runBlocking {
        val transport = ScriptedTransport(payload, finalUrl = "https://evil.example/app.apk")
        val result = UpdateDownloader(transport = transport, expectedSize = payload.size.toLong(), expectedSha256 = null)
            .download(url, dir, "hoshiori-update-0.7.9.apk", UpdateDownloadToken())
        assertTrue(result is UpdateDownloadResult.Failed)
        assertEquals(emptyList<String>(), filesInDir())
    }

    @Test
    fun nonGithubUrlIsRefusedBeforeAnyTransfer() = runBlocking {
        val transport = ScriptedTransport(payload)
        val result = UpdateDownloader(transport = transport)
            .download("https://evil.example/app.apk", dir, "x.apk", UpdateDownloadToken())
        assertTrue(result is UpdateDownloadResult.Failed)
        assertFalse((result as UpdateDownloadResult.Failed).error.retryable)
        assertEquals(0, transport.opened)
    }

    @Test
    fun aPartialFileFromAPreviousRunIsNotAppendedTo() = runBlocking {
        File(dir, "hoshiori-update-0.7.9.apk.part").writeText("stale half-written bytes")
        val transport = ScriptedTransport(payload)
        val result = UpdateDownloader(transport = transport, expectedSize = payload.size.toLong(), expectedSha256 = null)
            .download(url, dir, "hoshiori-update-0.7.9.apk", UpdateDownloadToken())
        assertTrue(result is UpdateDownloadResult.Success)
        assertEquals(payload.size.toLong(), (result as UpdateDownloadResult.Success).sizeBytes)
    }

    @Test
    fun sweepOnlyTouchesThisFeaturesFiles() {
        val keep = File(dir, "hoshiori-update-0.7.9.apk").apply { writeText("x") }
        val stale = File(dir, "hoshiori-update-0.7.8.apk").apply { writeText("x") }
        val foreign = File(dir, "something-else.apk").apply { writeText("x") }
        UpdateDownloader.sweep(dir, keep = keep)
        assertTrue(keep.exists())
        assertFalse(stale.exists())
        assertTrue("sweep 不能删掉这个目录里不属于更新包的文件", foreign.exists())
    }

    @Test
    fun expectedSizeInTheCandidateStopsAnOversizedTransfer() = runBlocking {
        val transport = ScriptedTransport(payload, chunk = 1_000)
        // 声明大小比真实字节小：写入超过声明大小就必须立刻停止，不必等下载完。
        val result = UpdateDownloader(transport = transport, expectedSize = 1_000L, expectedSha256 = null)
            .download(url, dir, "hoshiori-update-0.7.9.apk", UpdateDownloadToken())
        assertTrue(result is UpdateDownloadResult.Failed)
        assertEquals(emptyList<String>(), filesInDir())
    }
}

/** 内容嗅探与文件名策略的最小单元测试，纯字节，无网络。 */
class UpdateContentCheckTest {

    @get:Rule val temp = TemporaryFolder()

    @Test
    fun htmlAndJsonAreNamed() {
        assertNotNull(UpdateContentCheck.looksLikeHtml("<!DOCTYPE html><html></html>".toByteArray()))
        assertNotNull(UpdateContentCheck.looksLikeHtml("<html><body>502</body></html>".toByteArray()))
        assertNotNull(UpdateContentCheck.looksLikeHtml("{\"message\":\"rate limited\"}".toByteArray()))
        assertNotNull(UpdateContentCheck.looksLikeHtml(ByteArray(0)))
        assertNull(UpdateContentCheck.looksLikeHtml(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 1, 2, 3)))
    }

    @Test
    fun zipMarkersAreDetectedOnRealFiles() {
        val apk = temp.newFile("ok.apk")
        FileOutputStream(apk).use { out ->
            out.write(byteArrayOf(0x50, 0x4B, 0x03, 0x04))
            out.write(ByteArray(100))
            out.write(byteArrayOf(0x50, 0x4B, 0x05, 0x06))
        }
        assertTrue(UpdateContentCheck.hasZipLocalFileHeader(apk))
        assertTrue(UpdateContentCheck.hasEndOfCentralDirectory(apk))
        assertNull(UpdateContentCheck.looksLikeHtmlFile(apk))

        val notApk = temp.newFile("bad.apk").apply { writeText("hello") }
        assertFalse(UpdateContentCheck.hasZipLocalFileHeader(notApk))
        assertFalse(UpdateContentCheck.hasEndOfCentralDirectory(notApk))
    }

    @Test
    fun cacheFileNamesCarryTheVersionAndStayInsideTheDirectory() {
        assertEquals("hoshiori-update-0.7.9.apk", UpdateCache.apkName("0.7.9"))
        // 文件名由服务器给的版本号拼成，所以它必须只能是一个平铺的文件名：
        // 不含路径分隔符，唯一可能出现 ".." 的位置在固定前缀里（那个前缀本身不会上跳）。
        val suspicious = UpdateCache.apkName("../../etc/passwd")
        assertFalse(suspicious.contains("/"))
        assertFalse(suspicious.contains("\\"))
        // 三段式上跳（`/..`、`\..`）在这一个文件名里是构造不出来的；单独的 ".." 只是合法文件名的一部分。
        assertFalse(suspicious.contains("/.."))
        assertFalse(suspicious.contains("\\.."))
        assertTrue(suspicious.startsWith(UpdateCache.APK_PREFIX))
        assertTrue(suspicious.endsWith(UpdateCache.APK_SUFFIX))
    }

    /**
     * 目录与 authority 是主代理那边的集成约定，所以这里把两侧的字面量都钉死：
     * 缓存目录必须是 `<cacheDir>/updates/`，authority 必须是 `${packageName}.updates.fileprovider`。
     * 任意一边被改名，FilesProvider 就会找不到文件、安装按钮直接抛异常，而这类错误很难在设备上排查。
     */
    @Test
    fun theCacheDirectoryMatchesTheFileProviderPath() {
        val cacheRoot = temp.newFolder("cache")
        val dir = UpdateCache.dir(cacheRoot)
        assertEquals(File(cacheRoot, "updates"), dir)
        assertEquals("updates", dir.name)
        assertEquals("updates", UpdateCache.DIR_NAME)

        assertEquals("com.glasssign.app.updates.fileprovider", UpdateIntents.authorityFor("com.glasssign.app"))
        assertEquals(
            "com.glasssign.app.preview.updates.fileprovider",
            UpdateIntents.authorityFor("com.glasssign.app.preview"),
        )
        // 绝不能退回到插件页的 provider：两者的 paths 文件暴露的是不同目录。
        assertFalse(UpdateIntents.authorityFor("com.glasssign.app").endsWith(".plugins.fileprovider"))
    }
}
