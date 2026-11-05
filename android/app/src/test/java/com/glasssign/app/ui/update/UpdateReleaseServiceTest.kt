package com.glasssign.app.ui.update

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    private fun service(body: String) = UpdateReleaseService(transport = { _: String, _: String? -> UpdateHttpReply(body, null) })

    private fun serviceFailing(e: Exception) = UpdateReleaseService(transport = { _: String, _: String? -> throw e })

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
        val service = UpdateReleaseService(transport = { _: String, _: String? -> throw kotlinx.coroutines.CancellationException("stop") })
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
 * The channel-aware check: preview installs check the preview channel, official installs the official
 * one, and a 304 answer still counts as a successful (cheap) check.
 */
class UpdateCheckOutcomeTest {

    private val officialRelease = """
        {
          "tag_name": "v0.7.10",
          "name": "星栞 0.7.10",
          "html_url": "https://github.com/Starlevin/Hoshiori/releases/tag/v0.7.10",
          "draft": false,
          "prerelease": false,
          "assets": [
            {"name": "Hoshiori-v0.7.10.apk", "size": 18000000,
             "browser_download_url": "https://github.com/Starlevin/Hoshiori/releases/download/v0.7.10/Hoshiori-v0.7.10.apk",
             "digest": "sha256:${"a".repeat(64)}"}
          ]
        }
    """.trimIndent()

    private val previewRelease = """
        {
          "tag_name": "v0.7.10",
          "name": "星栞 0.7.10 预览",
          "html_url": "https://github.com/Starlevin/Hoshiori/releases/tag/v0.7.10",
          "draft": false,
          "prerelease": false,
          "assets": [
            {"name": "Hoshiori-v0.7.10-preview.apk", "size": 18000000,
             "browser_download_url": "https://github.com/Starlevin/Hoshiori/releases/download/v0.7.10/Hoshiori-v0.7.10-preview.apk",
             "digest": "sha256:${"b".repeat(64)}"}
          ]
        }
    """.trimIndent()

    @Test
    fun officialChannelRefusesAPreviewAsset() {
        val service = UpdateReleaseService(
            transport = { _: String, _: String? -> UpdateHttpReply(previewRelease, "\"etag-1\"") },
            channel = UpdateReleaseChannel.OFFICIAL,
        )
        val result = service.checkWithMeta("0.7.9-preview.3")
        // Judged as the official channel: only a plain asset is acceptable, so a preview-only release
        // yields "有新版本但资源不完整" rather than offering a file for another channel.
        assertTrue(result.check is UpdateCheck.UpToDate)
        assertEquals(NoUpdateReason.MISSING_APK_OR_DIGEST, (result.check as UpdateCheck.UpToDate).reason)
        assertTrue(result.reached)
        assertEquals("\"etag-1\"", result.etag)
    }

    @Test
    fun previewChannelAcceptsOnlyPreviewAssets() {
        val service = UpdateReleaseService(
            transport = { _: String, _: String? -> UpdateHttpReply(previewRelease, null) },
            channel = UpdateReleaseChannel.PREVIEW,
        )
        val result = service.checkWithMeta("0.7.9-preview.3")
        assertTrue(result.check is UpdateCheck.Available)
        val candidate = (result.check as UpdateCheck.Available).candidate
        assertEquals(UpdateReleaseChannel.PREVIEW, candidate.channel)
        assertEquals("Hoshiori-v0.7.10-preview.apk", candidate.apk.name)
    }

    private fun previewReleaseJson(tag: String, assetName: String, prerelease: Boolean = true): String = """
        {
          "tag_name": "$tag",
          "name": "星栞 $tag",
          "html_url": "https://github.com/Starlevin/Hoshiori/releases/tag/$tag",
          "draft": false,
          "prerelease": $prerelease,
          "assets": [
            {"name": "$assetName", "size": 18000000,
             "browser_download_url": "https://github.com/Starlevin/Hoshiori/releases/download/$tag/$assetName",
             "digest": "sha256:${"b".repeat(64)}"}
          ]
        }
    """.trimIndent()

    /** 预览序号参与比较：`0.7.9-preview.4` 比 `0.7.9-preview.3` 新。 */
    @Test
    fun aHigherPreviewNumberIsAnUpdate() {
        val service = UpdateReleaseService(
            transport = { _: String, _: String? ->
                UpdateHttpReply(previewReleaseJson("v0.7.9-preview.4", "Hoshiori-v0.7.9-preview.4.apk"), null)
            },
            channel = UpdateReleaseChannel.PREVIEW,
        )
        val check = service.check("0.7.9-preview.3")
        assertTrue(check is UpdateCheck.Available)
        assertEquals("0.7.9-preview.4", (check as UpdateCheck.Available).candidate.version.toString())
    }

    /** 预览序号按数值比较：`preview.10` 比 `preview.9` 新，不是字符串比较。 */
    @Test
    fun previewNumbersCompareNumerically() {
        val service = UpdateReleaseService(
            transport = { _: String, _: String? ->
                UpdateHttpReply(previewReleaseJson("v0.7.9-preview.10", "Hoshiori-v0.7.9-preview.10.apk"), null)
            },
            channel = UpdateReleaseChannel.PREVIEW,
        )
        val check = service.check("0.7.9-preview.9")
        assertTrue(check is UpdateCheck.Available)
        assertEquals("0.7.9-preview.10", (check as UpdateCheck.Available).candidate.version.toString())
    }

    /** 同一版本号下，预览构建可以前进到最终正式版。 */
    @Test
    fun previewInstallCanMoveToTheFinalReleaseOfTheSameVersion() {
        val service = UpdateReleaseService(
            transport = { _: String, _: String? ->
                UpdateHttpReply(previewReleaseJson("v0.7.10", "Hoshiori-v0.7.10.apk", prerelease = false), null)
            },
            channel = UpdateReleaseChannel.PREVIEW,
        )
        val check = service.check("0.7.10-preview.3")
        assertTrue(check is UpdateCheck.Available)
        val candidate = (check as UpdateCheck.Available).candidate
        assertTrue(candidate.version.isFinal)
        assertEquals("Hoshiori-v0.7.10.apk", candidate.apk.name)
    }

    /** 预览通道读的是 `/releases` 列表，正式通道读 `/releases/latest`。 */
    @Test
    fun thePreviewChannelQueriesTheReleaseListNotLatest() {
        assertEquals(UpdateUrls.LATEST_RELEASE_URL, UpdateReleaseService.endpointFor(UpdateReleaseChannel.OFFICIAL))
        assertEquals(UpdateUrls.RELEASES_LIST_URL, UpdateReleaseService.endpointFor(UpdateReleaseChannel.PREVIEW))
        assertFalse(UpdateUrls.RELEASES_LIST_URL.endsWith("/latest"))
    }

    @Test
    fun officialChannelAcceptsThePlainAsset() {
        val service = UpdateReleaseService(
            transport = { _: String, _: String? -> UpdateHttpReply(officialRelease, null) },
            channel = UpdateReleaseChannel.OFFICIAL,
        )
        assertEquals(true, service.check("0.7.9") is UpdateCheck.Available)
        assertEquals(UpdateReleaseChannel.OFFICIAL, (service.check("0.7.9") as UpdateCheck.Available).candidate.channel)
    }

    /** 没有可复用的正文时，304 不能凭空报"已是最新"，必须当成本次没拿到结果。 */
    @Test
    fun notModifiedWithoutACachedBodyIsNotReportedAsUpToDate() {
        val service = UpdateReleaseService(
            transport = { _: String, _: String? -> UpdateHttpReply("", "\"etag-9\"", notModified = true) },
        )
        val result = service.checkWithMeta("0.7.9", etag = "\"etag-9\"")
        assertFalse(result.reached)
        assertTrue(result.check is UpdateCheck.Failed)
        assertTrue(result.failureSentence!!.contains("没有可复用的结果"))
    }

    /** 有缓存正文时，304 复用同一份正文重新判定：上次发现的新版本仍然是新版本。 */
    @Test
    fun notModifiedReusesTheCachedBodySoAnUninstalledUpdateIsStillAvailable() {
        val cache = UpdateCachedReply(
            etag = "\"etag-9\"",
            body = officialRelease,
            endpoint = UpdateUrls.LATEST_RELEASE_URL,
            comparedWith = "0.7.9",
        )
        var sawEtag: String? = "unset"
        val service = UpdateReleaseService(
            transport = { _: String, etag: String? ->
                sawEtag = etag
                UpdateHttpReply("", etag, notModified = true)
            },
            channel = UpdateReleaseChannel.OFFICIAL,
            cachedReply = { cache },
        )
        val result = service.checkWithMeta("0.7.9", etag = "\"etag-9\"")
        // This is the regression: a 304 used to be flattened into UpToDate, hiding an update the user
        // had already seen but not installed.
        assertTrue(result.check is UpdateCheck.Available)
        assertEquals("0.7.10", (result.check as UpdateCheck.Available).candidate.version.toString())
        assertTrue(result.reached)
        assertTrue(result.notModified)
        assertEquals("\"etag-9\"", sawEtag)
    }

    /** 缓存属于别的当前版本（应用已经升级过）时不得复用，否则会凭空报出新版本。 */
    @Test
    fun aCacheFromAnotherCurrentVersionIsNotReused() {
        val cache = UpdateCachedReply("etag-9", officialRelease, UpdateUrls.LATEST_RELEASE_URL, comparedWith = "0.7.0")
        var sawEtag: String? = "unset"
        val service = UpdateReleaseService(
            transport = { _: String, etag: String? ->
                sawEtag = etag
                UpdateHttpReply("", etag, notModified = true)
            },
            cachedReply = { cache },
        )
        val result = service.checkWithMeta("0.7.10", etag = "etag-9")
        assertFalse(result.reached)
        assertNull(sawEtag)
        assertTrue(result.check is UpdateCheck.Failed)
    }

    /** 换通道后端点不同，旧缓存不算命中。 */
    @Test
    fun aCacheFromAnotherEndpointIsNotReused() {
        val cache = UpdateCachedReply("etag-9", officialRelease, UpdateUrls.LATEST_RELEASE_URL, comparedWith = "0.7.9")
        var sawEtag: String? = "unset"
        val service = UpdateReleaseService(
            transport = { _: String, etag: String? ->
                sawEtag = etag
                UpdateHttpReply("", etag, notModified = true)
            },
            channel = UpdateReleaseChannel.PREVIEW,
            cachedReply = { cache },
        )
        service.checkWithMeta("0.7.9", etag = "etag-9")
        assertNull(sawEtag)
    }

    @Test
    fun offlineIsReportedAsNotReachedSoTheCooldownStaysShort() {
        val service = UpdateReleaseService(transport = { _: String, _: String? -> throw java.net.UnknownHostException("api.github.com") })
        val result = service.checkWithMeta("0.7.9")
        assertFalse(result.reached)
        assertEquals(UpdateFailure.OFFLINE, (result.check as UpdateCheck.Failed).kind)
    }

    @Test
    fun emptyReleaseListMeansNotPublishedRatherThanCrash() {
        val service = UpdateReleaseService(transport = { _: String, _: String? -> UpdateHttpReply("[]", null) })
        val result = service.checkWithMeta("0.7.9")
        assertTrue(result.reached)
        assertEquals(UpdateFailure.NOT_PUBLISHED, (result.check as UpdateCheck.Failed).kind)
    }

    /**
     * etag 只在有可复用正文时才作为条件请求头发出。没有缓存就必须无条件重查，
     * 否则 304 会带来一个我们无法解释的"没有变化"。
     */
    @Test
    fun theEtagIsOnlySentWhenThereIsACachedBodyToReuse() {
        var seen: String? = "unset"
        val service = UpdateReleaseService(
            transport = { _: String, etag: String? ->
                seen = etag
                UpdateHttpReply(officialRelease, null)
            },
        )
        service.checkWithMeta("0.7.9", etag = "\"abc\"")
        assertNull("没有缓存正文时不得发条件请求", seen)

        val withCache = UpdateReleaseService(
            transport = { _: String, etag: String? ->
                seen = etag
                UpdateHttpReply(officialRelease, null)
            },
            cachedReply = { UpdateCachedReply("\"abc\"", officialRelease, UpdateUrls.LATEST_RELEASE_URL, "0.7.9") },
        )
        withCache.checkWithMeta("0.7.9", etag = "\"abc\"")
        assertEquals("\"abc\"", seen)
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
