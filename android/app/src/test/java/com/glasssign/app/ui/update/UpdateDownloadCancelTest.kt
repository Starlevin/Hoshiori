package com.glasssign.app.ui.update

import java.io.File
import java.io.OutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 取消下载与"最终提交"的竞态。
 *
 * 下载成功后还要算哈希、校验包名/版本号/签名，这几步的收尾可能晚于用户点的「取消」。
 * 这些用例卡住的正是那一刻：取消之后，任何成功结果都不允许再把状态写回 Verified。
 *
 * 不依赖 `kotlinx-coroutines-test`（本项目没有这个测试依赖），只用真实线程与轮询等待。
 */
class UpdateDownloadCancelTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val executor = Executors.newFixedThreadPool(4)

    @After
    fun tearDown() {
        executor.shutdownNow()
    }

    private val cert = "a".repeat(64)

    /** 假 APK 的真实摘要：下载器会按内容重算，期望值必须对得上。 */
    private val fakeApkSha256: String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(FAKE_APK)
        .joinToString("") { "%02x".format(it) }

    private val candidate = UpdateCandidate(
        version = UpdateVersion.parse("0.8.0")!!,
        tag = "v0.8.0",
        name = "星栞 0.8.0",
        htmlUrl = "https://github.com/Starlevin/Hoshiori/releases/tag/v0.8.0",
        notes = null,
        publishedAt = null,
        apk = UpdateAsset(
            "Hoshiori-v0.8.0.apk",
            APK_BYTES,
            "https://github.com/Starlevin/Hoshiori/releases/download/v0.8.0/Hoshiori-v0.8.0.apk",
            "sha256:$fakeApkSha256",
        ),
    )

    /** 校验会通过的读取器：包名、versionCode、签名都对得上。 */
    private val passingReader = object : UpdateApkReader {
        override fun readApk(file: File, sha256: String): UpdateApkFacts = UpdateApkFacts(
            sizeBytes = file.length(),
            sha256 = sha256,
            packageName = "com.glasssign.app",
            versionCode = 22L,
            versionName = "0.8.0",
            signingCertSha256 = listOf(cert),
        )

        override fun readInstalled(): UpdateInstalledFacts =
            UpdateInstalledFacts("com.glasssign.app", 21L, "0.7.9-preview.3", listOf(cert))
    }

    @Test
    fun cancelBeforeVerificationNeverBecomesVerified() {
        val gate = CompletableDeferred<Unit>()
        val vm = racingViewModel(gate, passingReader)
        vm.seedForTest(candidate)
        vm.startDownload()
        // 等到下载器真的进来了，再模拟用户取消。
        waitForState(vm) { it.download is UpdateDownloadState.Running }

        vm.cancelDownload()
        gate.complete(Unit)
        Thread.sleep(300)

        assertNull("取消后不允许出现 Verified", vm.state.value.verified)
        assertTrue(vm.state.value.download is UpdateDownloadState.Idle)
    }

    @Test
    fun cancelRightAfterTheDownloadFinishedStillDoesNotRestoreVerified() {
        val gate = CompletableDeferred<Unit>()
        val vm = racingViewModel(gate, passingReader)
        vm.seedForTest(candidate)
        vm.startDownload()
        gate.complete(Unit)
        // 立刻取消：此刻下载/哈希/校验可能已经在另一个线程里跑起来了。
        vm.cancelDownload()
        Thread.sleep(300)

        assertNull(vm.state.value.verified)
        assertTrue(vm.state.value.download is UpdateDownloadState.Idle)
    }

    @Test
    fun aVerificationErrorLandsAsFailedNotAsDownloading() {
        val gate = CompletableDeferred<Unit>()
        gate.complete(Unit)
        val throwingReader = object : UpdateApkReader {
            override fun readApk(file: File, sha256: String): UpdateApkFacts = throw IllegalStateException("坏文件")
            override fun readInstalled(): UpdateInstalledFacts =
                UpdateInstalledFacts("com.glasssign.app", 21L, "0.7.9", listOf(cert))
        }
        val vm = racingViewModel(gate, throwingReader)
        vm.seedForTest(candidate)
        vm.startDownload()
        waitForState(vm) { it.download !is UpdateDownloadState.Running }

        val download = vm.state.value.download
        assertTrue("校验异常必须落成失败，实际是 $download", download is UpdateDownloadState.Failed)
        assertNull(vm.state.value.verified)
    }

    @Test
    fun aSuccessfulDownloadWithoutCancellationDoesBecomeVerified() {
        val gate = CompletableDeferred<Unit>()
        gate.complete(Unit)
        val vm = racingViewModel(gate, passingReader)
        vm.seedForTest(candidate)
        vm.startDownload()
        waitForState(vm) { it.verified != null }

        assertTrue("未取消时应当正常进入 Verified", vm.state.value.verified != null)
    }

    private fun waitForState(vm: UpdateViewModel, predicate: (UpdateUiState) -> Boolean): Boolean {
        repeat(400) {
            if (predicate(vm.state.value)) return true
            Thread.sleep(10)
        }
        return false
    }

    private fun racingViewModel(gate: CompletableDeferred<Unit>, reader: UpdateApkReader): UpdateViewModel {
        val vm = UpdateViewModel(
            build = UpdateBuildInfo("0.7.9-preview.3", 21, "com.glasssign.app", isDebug = false),
            configLoader = { UpdateChannelConfig.EMPTY },
            service = { UpdateReleaseService(transport = { _: String, _: String? -> UpdateHttpReply("[]", null) }) },
            checkStore = MapUpdateCheckStore(),
            clock = { 1_000L },
            cacheDir = { UpdateCache.dir(temp.root) },
            downloaderFactory = { c ->
                UpdateDownloader(
                    transport = GatedTransport(gate),
                    expectedSize = c.apk.sizeBytes,
                    expectedSha256 = c.apk.sha256,
                )
            },
            apkReader = reader,
        )
        // 不依赖 Android 主线程调度器：用真实线程池跑 viewModelScope 的等价物。
        vm.scope = CoroutineScope(executor.asCoroutineDispatcher() + SupervisorJob())
        return vm
    }

    /** 传输层在 gate 打开前一直阻塞，把"下载"停在半路。 */
    private class GatedTransport(private val gate: CompletableDeferred<Unit>) : UpdateDownloadTransport {
        override fun stream(
            url: String,
            sink: OutputStream,
            token: UpdateDownloadToken,
            onProgress: (Long) -> Unit,
        ): String {
            runBlocking { gate.await() }
            sink.write(FAKE_APK)
            onProgress(FAKE_APK.size.toLong())
            return url
        }
    }

    private companion object {
        /** 一个足够长、带 ZIP 本地文件头的假 APK。 */
        val FAKE_APK: ByteArray = byteArrayOf(0x50, 0x4B, 0x03, 0x04) + ByteArray(80_000)
        val APK_BYTES: Long = FAKE_APK.size.toLong()
    }
}
