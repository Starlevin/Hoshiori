package com.glasssign.app.ui.update

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The check pipeline with the network replaced by a scripted answer, so every failure sentence is
 * pinned: a user must be able to tell "no network" from "rate limited" from "nothing published yet".
 */
class UpdateReleaseServiceTest {

    private val goodRelease = """
        {
          "tag_name": "v0.7.7",
          "name": "星栞 0.7.7",
          "html_url": "https://github.com/Starlevin/Hoshiori/releases/tag/v0.7.7",
          "body": "修复若干问题",
          "published_at": "2026-11-01T10:00:00Z",
          "draft": false,
          "prerelease": false,
          "assets": [
            {
              "name": "Hoshiori-v0.7.7.apk",
              "size": 12345678,
              "browser_download_url": "https://github.com/Starlevin/Hoshiori/releases/download/v0.7.7/Hoshiori-v0.7.7.apk",
              "digest": "sha256:${"b".repeat(64)}"
            }
          ]
        }
    """.trimIndent()

    private fun service(body: String) = UpdateReleaseService(transport = { body })

    private fun serviceFailing(e: Exception) = UpdateReleaseService(transport = { throw e })

    @Test
    fun newerReleaseBecomesAnAvailableCandidate() {
        val check = service(goodRelease).check("0.7.6")
        assertTrue(check is UpdateCheck.Available)
        val candidate = (check as UpdateCheck.Available).candidate
        assertEquals("0.7.7", candidate.version.toString())
        assertEquals("星栞 0.7.7", candidate.name)
        assertTrue(UpdateUrls.isReleaseApkUrl(candidate.apk.browserDownloadUrl))
    }

    @Test
    fun sameVersionIsUpToDate() {
        val check = service(goodRelease).check("0.7.7")
        assertTrue(check is UpdateCheck.UpToDate)
        assertEquals(NoUpdateReason.UP_TO_DATE, (check as UpdateCheck.UpToDate).reason)
        assertTrue(check.detail.contains("已是最新版本"))
    }

    @Test
    fun offlineIsNamedAsOffline() {
        val check = serviceFailing(UnknownHostException("api.github.com")).check("0.7.6")
        assertTrue(check is UpdateCheck.Failed)
        assertEquals(UpdateFailure.OFFLINE, (check as UpdateCheck.Failed).kind)
        assertTrue(check.message.contains("无网络"))
    }

    @Test
    fun connectRefusalIsAlsoOffline() {
        val check = serviceFailing(ConnectException("refused")).check("0.7.6")
        assertEquals(UpdateFailure.OFFLINE, (check as UpdateCheck.Failed).kind)
    }

    @Test
    fun timeoutIsDistinctFromOffline() {
        val check = serviceFailing(SocketTimeoutException("read timed out")).check("0.7.6")
        assertEquals(UpdateFailure.TIMEOUT, (check as UpdateCheck.Failed).kind)
        assertTrue(check.message.contains("超时"))
    }

    @Test
    fun rateLimitIsReportedWithTheCode() {
        for (code in listOf(403, 429)) {
            val check = serviceFailing(UpdateHttpException(code)).check("0.7.6")
            assertEquals(UpdateFailure.RATE_LIMITED, (check as UpdateCheck.Failed).kind)
            assertTrue(check.message.contains(code.toString()))
        }
    }

    @Test
    fun notFoundMeansNotPublishedYet() {
        val check = serviceFailing(UpdateHttpException(404)).check("0.7.6")
        assertEquals(UpdateFailure.NOT_PUBLISHED, (check as UpdateCheck.Failed).kind)
        assertTrue(check.message.contains("暂未发布"))
    }

    @Test
    fun otherHttpCodesAreHttpFailures() {
        val check = serviceFailing(UpdateHttpException(500)).check("0.7.6")
        assertEquals(UpdateFailure.HTTP, (check as UpdateCheck.Failed).kind)
        assertTrue(check.message.contains("500"))
    }

    @Test
    fun tlsProblemIsNamed() {
        val check = serviceFailing(SSLHandshakeException("cert")).check("0.7.6")
        assertEquals(UpdateFailure.TLS, (check as UpdateCheck.Failed).kind)
    }

    @Test
    fun htmlInsteadOfJsonIsAReadableFailure() {
        val check = service("\u0021DOCTYPE html <html>busy</html>").check("0.7.6")
        assertTrue(check is UpdateCheck.Failed)
        assertEquals(UpdateFailure.HTTP, (check as UpdateCheck.Failed).kind)
    }

    @Test
    fun missingApkIsAnHonestUpToDateStyleAnswer() {
        val noAssets = """{"tag_name":"v0.7.9","draft":false,"prerelease":false,"assets":[]}"""
        val check = service(noAssets).check("0.7.6")
        assertTrue(check is UpdateCheck.UpToDate)
        assertEquals(NoUpdateReason.MISSING_APK_OR_DIGEST, (check as UpdateCheck.UpToDate).reason)
        assertTrue(check.detail.contains("sha256"))
    }

    @Test
    fun cancelledCoroutineIsNotTurnedIntoANetworkError() {
        // The transport wrapper rethrows cancellation; the service must not swallow it.
        val service = UpdateReleaseService(transport = { throw kotlinx.coroutines.CancellationException("stop") })
        try {
            service.check("0.7.6")
            throw AssertionError("cancellation should propagate")
        } catch (expected: kotlinx.coroutines.CancellationException) {
            // pass
        }
    }

    @Test
    fun assertionOnUnknownExceptionStillReadsAsAFailure() {
        val check = serviceFailing(IllegalStateException("boom")).check("0.7.6")
        assertTrue(check is UpdateCheck.Failed)
        assertTrue((check as UpdateCheck.Failed).message.isNotBlank())
    }
}

/**
 * The state object's decision logic, including the rule that 夸克 survives a GitHub outage.
 * Constructed directly: no ViewModel, no Android, no coroutine.
 */
class UpdateUiStateTest {

    private val build = UpdateBuildInfo(
        versionName = "0.7.6",
        versionCode = 16,
        applicationId = "com.glasssign.app",
        isDebug = false,
    )

    private val quark = "https://pan.quark.cn/s/739482783865"

    private fun state(
        channel: UpdateChannel = UpdateChannel.GITHUB,
        result: UpdateCheck? = null,
        quarkUrl: String? = quark,
    ) = UpdateUiState(build = build, channel = channel, quarkUrl = quarkUrl, result = result)

    private val candidate = UpdateCandidate(
        version = UpdateVersion.parse("0.7.7")!!,
        tag = "v0.7.7",
        name = "星栞 0.7.7",
        htmlUrl = "https://github.com/Starlevin/Hoshiori/releases/tag/v0.7.7",
        notes = null,
        publishedAt = null,
        apk = UpdateAsset(
            name = "Hoshiori-v0.7.7.apk",
            sizeBytes = 1L,
            browserDownloadUrl = "https://github.com/Starlevin/Hoshiori/releases/download/v0.7.7/Hoshiori-v0.7.7.apk",
            sha256 = "sha256:" + "c".repeat(64),
        ),
    )

    @Test
    fun noRequestReasonAndNoActionBeforeAnyCheck() {
        val s = state()
        assertEquals(null, s.result)
        assertEquals(null, s.actionUrl)
        assertTrue(s.githubUnusable)
    }

    @Test
    fun githubChannelOpensTheReleaseApk() {
        val s = state(result = UpdateCheck.Available(candidate))
        assertEquals(candidate.apk.browserDownloadUrl, s.actionUrl)
        assertTrue(s.githubUnusable.not())
    }

    @Test
    fun quarkChannelOpensTheDirectoryEvenWhenGithubFailed() {
        // The whole point of the fallback: the directory link is a fixed asset value, so an
        // unreachable GitHub must NOT disable it.
        val failed = UpdateCheck.Failed(UpdateFailure.OFFLINE, "当前无网络")
        val s = state(channel = UpdateChannel.QUARK, result = failed)
        assertEquals(quark, s.actionUrl)
        assertTrue(s.quarkAvailable)
        assertTrue(s.githubUnusable)
    }

    @Test
    fun quarkChannelIsUnavailableWhenNotConfigured() {
        val s = state(channel = UpdateChannel.QUARK, quarkUrl = null)
        assertEquals(null, s.actionUrl)
        assertTrue(!s.quarkAvailable)
    }

    @Test
    fun malformedQuarkUrlIsTreatedAsUnconfigured() {
        val s = state(channel = UpdateChannel.QUARK, quarkUrl = "http://pan.quark.cn/s/1")
        assertEquals(null, s.actionUrl)
        assertTrue(!s.quarkAvailable)
    }

    @Test
    fun githubChannelIgnoresAnInvalidAssetUrl() {
        val bad = candidate.copy(
            apk = candidate.apk.copy(browserDownloadUrl = "https://evil.example/app.apk"),
        )
        val s = state(result = UpdateCheck.Available(bad))
        // The version is still shown, but the button has nowhere safe to go.
        assertEquals(null, s.actionUrl)
        assertEquals("0.7.7", s.candidate!!.version.toString())
    }
}
