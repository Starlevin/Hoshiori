package com.glasssign.app.ui.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 安装前校验：每一条都是一个"拒绝"的回归测试。
 *
 * 这些用例不做任何下载与安装，只喂"测量到的事实"，因此能在普通 JVM 上跑完整门禁。
 */
class UpdateInstallVerifierTest {

    private val official = AppInstallInfo(
        versionName = "0.7.9",
        versionCode = 21,
        applicationId = "com.glasssign.app",
        isDebug = false,
        releaseApplicationId = "com.glasssign.app",
    )

    private val preview = AppInstallInfo(
        versionName = "0.7.9-preview.3",
        versionCode = 21,
        applicationId = "com.glasssign.app.preview",
        isDebug = false,
        releaseApplicationId = "com.glasssign.app.preview",
    )

    private val ourCert = "a".repeat(64)
    private val otherCert = "b".repeat(64)
    private val digest = "sha256:" + "c".repeat(64)

    private fun installed(code: Long = 21L, cert: String = ourCert) = UpdateInstalledFacts(
        packageName = "com.glasssign.app",
        versionCode = code,
        versionName = "0.7.9",
        signingCertSha256 = listOf(cert),
    )

    private fun apk(
        packageName: String? = "com.glasssign.app",
        versionCode: Long? = 22L,
        certs: List<String> = listOf(ourCert),
        size: Long = 18_000_000L,
        sha: String = "c".repeat(64),
    ) = UpdateApkFacts(
        sizeBytes = size,
        sha256 = sha,
        packageName = packageName,
        versionCode = versionCode,
        versionName = "0.8.0",
        signingCertSha256 = certs,
    )

    @Test
    fun aMatchingHigherSignedApkPasses() {
        val report = UpdateInstallVerifier.verify(
            install = official,
            installed = installed(),
            apk = apk(),
            expectedSize = 18_000_000L,
            expectedSha256 = digest,
        )
        assertTrue(report.passed)
        assertTrue(report.problems.isEmpty())
    }

    @Test
    fun thePreviewPackageIsNeededOnAPreviewInstall() {
        // 正式包的 facts 在预览安装上必须被拒绝：包名不同就是一个不同的 Android 应用。
        val report = UpdateInstallVerifier.verify(
            install = preview,
            installed = UpdateInstalledFacts("com.glasssign.app.preview", 21L, "0.7.9", listOf(ourCert)),
            apk = apk(packageName = "com.glasssign.app"),
            expectedSize = 18_000_000L,
            expectedSha256 = digest,
        )
        assertFalse(report.passed)
        assertTrue((report as UpdateVerifyReport.Failed).problems.any { it.issue == UpdateVerifyIssue.PACKAGE_MISMATCH })
        assertTrue(report.detail.contains("com.glasssign.app.preview"))
    }

    @Test
    fun equalOrLowerVersionCodeIsRefused() {
        for (code in listOf(21L, 20L)) {
            val report = UpdateInstallVerifier.verify(
                install = official,
                installed = installed(),
                apk = apk(versionCode = code),
                expectedSize = 18_000_000L,
                expectedSha256 = null,
            )
            assertFalse("versionCode=$code 必须被拒绝", report.passed)
            assertTrue((report as UpdateVerifyReport.Failed).problems.any { it.issue == UpdateVerifyIssue.VERSION_NOT_HIGHER })
        }
    }

    @Test
    fun aDifferentSignatureIsRefusedWithBothFingerprintsMentioned() {
        val report = UpdateInstallVerifier.verify(
            install = official,
            installed = installed(),
            apk = apk(certs = listOf(otherCert)),
            expectedSize = 18_000_000L,
            expectedSha256 = null,
        )
        assertFalse(report.passed)
        val problems = (report as UpdateVerifyReport.Failed).problems
        assertEquals(UpdateVerifyIssue.SIGNATURE_MISMATCH, problems.single().issue)
        assertTrue(problems.single().sentence().contains("签名"))
    }

    /**
     * 签名必须"签名者集合完全相同"，不是有交集就算：系统对多签名 APK 的覆盖安装要求集合一致，
     * 只要求交集会让这种包装过本地检查、再去撞系统的拒绝。
     */
    @Test
    fun anExtraSignerIsRefusedEvenWhenOurCertIsPresent() {
        val report = UpdateInstallVerifier.verify(
            install = official,
            installed = installed(),
            apk = apk(certs = listOf(ourCert, otherCert)),
            expectedSize = 18_000_000L,
            expectedSha256 = null,
        )
        assertFalse("多带一个签名者必须拒绝", report.passed)
        assertEquals(UpdateVerifyIssue.SIGNATURE_MISMATCH, (report as UpdateVerifyReport.Failed).problems.single().issue)
    }

    @Test
    fun aMissingSignerIsRefused() {
        val multi = UpdateInstalledFacts("com.glasssign.app", 21L, "0.7.9", listOf(ourCert, otherCert))
        val report = UpdateInstallVerifier.verify(
            install = official,
            installed = multi,
            apk = apk(certs = listOf(ourCert)),
            expectedSize = 18_000_000L,
            expectedSha256 = null,
        )
        assertFalse("少一个签名者必须拒绝", report.passed)
        assertEquals(UpdateVerifyIssue.SIGNATURE_MISMATCH, (report as UpdateVerifyReport.Failed).problems.single().issue)
    }

    @Test
    fun theSameMultiSignerSetPasses() {
        val multi = UpdateInstalledFacts("com.glasssign.app", 21L, "0.7.9", listOf(ourCert, otherCert))
        val report = UpdateInstallVerifier.verify(
            install = official,
            installed = multi,
            apk = apk(certs = listOf(otherCert, ourCert)),
            expectedSize = 18_000_000L,
            expectedSha256 = null,
        )
        assertTrue("同样的两个签名者、顺序不同也算相同", report.passed)
    }

    @Test
    fun aDuplicateSignerIsNotCountedAsAnExtra() {
        val report = UpdateInstallVerifier.verify(
            install = official,
            installed = installed(),
            apk = apk(certs = listOf(ourCert, ourCert)),
            expectedSize = 18_000_000L,
            expectedSha256 = null,
        )
        assertTrue(report.passed)
    }

    @Test
    fun anUnsignedApkIsRefusedEvenWithAValidDigest() {
        val report = UpdateInstallVerifier.verify(
            install = official,
            installed = installed(),
            apk = apk(certs = emptyList()),
            expectedSize = 18_000_000L,
            expectedSha256 = digest,
        )
        assertFalse(report.passed)
        assertEquals(UpdateVerifyIssue.SIGNATURE_MISMATCH, (report as UpdateVerifyReport.Failed).problems.single().issue)
    }

    @Test
    fun anUnreadableInstalledCertificateMeansRefusalNotOptimism() {
        val report = UpdateInstallVerifier.verify(
            install = official,
            installed = null,
            apk = apk(),
            expectedSize = 18_000_000L,
            expectedSha256 = digest,
        )
        assertFalse(report.passed)
        assertTrue((report as UpdateVerifyReport.Failed).problems.any { it.issue == UpdateVerifyIssue.SIGNATURE_MISMATCH })
    }

    /**
     * 多签名 APK：签名者集合必须与当前安装**完全相同**。多带一个不是"其中一个匹配就算通过"，
     * 系统对多签名覆盖安装的要求就是集合一致，放过去只会让用户在系统页看到一次失败。
     */
    @Test
    fun anExtraCertificateIsRejectedNotAcceptedBecauseOursIsPresent() {
        val report = UpdateInstallVerifier.verify(
            install = official,
            installed = installed(),
            apk = apk(certs = listOf(otherCert, ourCert)),
            expectedSize = 18_000_000L,
            expectedSha256 = digest,
        )
        assertFalse("多一个签名者必须拒绝", report.passed)
    }

    @Test
    fun caseAndSeparatorsInFingerprintsAreIgnored() {
        val pretty = ourCert.chunked(2).joinToString(":").uppercase()
        val report = UpdateInstallVerifier.verify(
            install = official,
            installed = installed(cert = pretty),
            apk = apk(),
            expectedSize = 18_000_000L,
            expectedSha256 = digest,
        )
        assertTrue(report.passed)
    }

    @Test
    fun aWrongDigestOrSizeIsReported() {
        val wrongDigest = UpdateInstallVerifier.verify(
            install = official,
            installed = installed(),
            apk = apk(sha = "d".repeat(64)),
            expectedSize = 18_000_000L,
            expectedSha256 = digest,
        )
        assertFalse(wrongDigest.passed)
        assertTrue((wrongDigest as UpdateVerifyReport.Failed).problems.any { it.issue == UpdateVerifyIssue.SIZE_MISMATCH })

        val wrongSize = UpdateInstallVerifier.verify(
            install = official,
            installed = installed(),
            apk = apk(size = 1L),
            expectedSize = 18_000_000L,
            expectedSha256 = digest,
        )
        assertFalse(wrongSize.passed)
    }

    @Test
    fun anUnreadableArchiveFailsClosed() {
        val report = UpdateInstallVerifier.verify(
            install = official,
            installed = installed(),
            apk = null,
            expectedSize = 18_000_000L,
            expectedSha256 = null,
        )
        assertFalse(report.passed)
        assertEquals(UpdateVerifyIssue.UNREADABLE, (report as UpdateVerifyReport.Failed).problems.single().issue)
    }

    @Test
    fun aMissingSizeOrDigestHintSkipsOnlyThatCheck() {
        // 没有发布摘要时，签名与包名仍然必须成立——"没给摘要"不等于"跳过一切"。
        val report = UpdateInstallVerifier.verify(
            install = official,
            installed = installed(),
            apk = apk(),
            expectedSize = 0L,
            expectedSha256 = null,
        )
        assertTrue(report.passed)
    }

    @Test
    fun theSignatureBasisSentenceSaysWhatIsActuallyCompared() {
        assertTrue(UpdateSignatureBasis.BASIS_SENTENCE.contains("当前已安装"))
        assertTrue(UpdateSignatureBasis.BASIS_SENTENCE.contains("完全一致"))
    }
}

/** 通道与"这次点击到底该走哪条路"的选择逻辑。 */
class UpdateChannelPolicyTest {

    private val quark = "https://pan.quark.cn/s/739482783865"

    private val candidate = UpdateCandidate(
        version = UpdateVersion.parse("0.8.0")!!,
        tag = "v0.8.0",
        name = "星栞 0.8.0",
        htmlUrl = "https://github.com/Starlevin/Hoshiori/releases/tag/v0.8.0",
        notes = null,
        publishedAt = null,
        apk = UpdateAsset(
            name = "Hoshiori-v0.8.0.apk",
            sizeBytes = 18_000_000L,
            browserDownloadUrl = "https://github.com/Starlevin/Hoshiori/releases/download/v0.8.0/Hoshiori-v0.8.0.apk",
            sha256 = "sha256:" + "a".repeat(64),
        ),
    )

    @Test
    fun channelComesFromTheVersionNameNotThePackageName() {
        // 正式版与预览版现在装成同一个包、用同一张签名，只有版本名能分辨。
        assertEquals(UpdateReleaseChannel.PREVIEW, UpdateReleaseChannel.ofVersionName("0.7.9-preview.3"))
        assertEquals(UpdateReleaseChannel.PREVIEW, UpdateReleaseChannel.ofVersionName("v0.8.0-preview"))
        assertEquals(UpdateReleaseChannel.OFFICIAL, UpdateReleaseChannel.ofVersionName("0.7.9"))
        assertEquals(UpdateReleaseChannel.OFFICIAL, UpdateReleaseChannel.ofVersionName("1.0.0"))
    }

    /** 旧版独立预览包能看更新，但包名不同，只能提示手动换包。 */
    @Test
    fun theLegacyPreviewPackageIsRecognisedButNeverAutoInstalled() {
        assertTrue(LegacyPreviewInstall.isLegacyPreview("com.glasssign.app.preview"))
        assertFalse(LegacyPreviewInstall.isLegacyPreview("com.glasssign.app"))
        val legacy = UpdateUiState(
            build = UpdateBuildInfo("0.7.8-preview.2", 20, "com.glasssign.app.preview", isDebug = false),
        )
        assertTrue(legacy.legacyPreviewNeedsManualInstall)
        assertEquals(UpdateReleaseChannel.PREVIEW, legacy.releaseChannel)
    }

    @Test
    fun downloadIsInAppWhenEverythingChecksOut() {
        val target = UpdateChannelPolicy.downloadTarget(
            check = UpdateCheck.Available(candidate),
            quarkUrl = quark,
        )
        assertTrue(target is UpdateDownloadTarget.InApp)
        assertEquals(candidate.apk.browserDownloadUrl, (target as UpdateDownloadTarget.InApp).url)
    }

    @Test
    fun noAvailableCheckMeansNoDownloadButKeepsTheExternalRoute() {
        val target = UpdateChannelPolicy.downloadTarget(
            check = UpdateCheck.UpToDate("0.8.0", NoUpdateReason.UP_TO_DATE, "已是最新版本"),
            quarkUrl = quark,
        )
        assertTrue(target is UpdateDownloadTarget.ExternalOnly)
        assertEquals(quark, (target as UpdateDownloadTarget.ExternalOnly).quarkUrl)
    }

    /** 包名不再是下载门槛：是否可安装由安装阶段的包名校验决定。 */
    @Test
    fun theDownloadGateNoLongerLooksAtThePackageName() {
        val target = UpdateChannelPolicy.downloadTarget(
            check = UpdateCheck.Available(candidate),
            quarkUrl = null,
        )
        assertTrue(target is UpdateDownloadTarget.InApp)
    }

    @Test
    fun aNonGithubAssetUrlIsRefused() {
        val bad = candidate.copy(apk = candidate.apk.copy(browserDownloadUrl = "https://evil.example/app.apk"))
        val target = UpdateChannelPolicy.downloadTarget(
            check = UpdateCheck.Available(bad),
            quarkUrl = null,
        )
        assertTrue(target is UpdateDownloadTarget.ExternalOnly)
        assertTrue((target as UpdateDownloadTarget.ExternalOnly).reason.contains("官方发布地址"))
    }

    @Test
    fun anAssetWithNoSizeIsRefusedInsteadOfGuessing() {
        val bad = candidate.copy(apk = candidate.apk.copy(sizeBytes = 0L))
        val target = UpdateChannelPolicy.downloadTarget(check = UpdateCheck.Available(bad), quarkUrl = null)
        assertTrue(target is UpdateDownloadTarget.ExternalOnly)
    }

    @Test
    fun anUnconfiguredQuarkLinkIsNotOfferedAsAFallback() {
        val target = UpdateChannelPolicy.downloadTarget(check = null, quarkUrl = "http://pan.quark.cn/s/1")
        assertNull((target as UpdateDownloadTarget.ExternalOnly).quarkUrl)
    }
}

/** 24 小时限频与"要不要打扰用户"的规则。 */
class UpdateAutoCheckTest {

    private val day = UpdateAutoCheck.INTERVAL_MS
    private val now = 1_760_000_000_000L

    @Test
    fun firstLaunchChecksAndLaterLaunchesAreThrottled() {
        val first = UpdateAutoCheck.decide(UpdateReleaseChannel.OFFICIAL, UpdateCheckMemory(), now)
        assertTrue(first is UpdateAutoCheckDecision.Check)
        assertNull((first as UpdateAutoCheckDecision.Check).etag)
    }

    @Test
    fun withInTwentyFourHoursNothingHappens() {
        val memory = UpdateCheckMemory(lastCheckMs = now - 60_000L)
        val decision = UpdateAutoCheck.decide(UpdateReleaseChannel.OFFICIAL, memory, now)
        assertTrue(decision is UpdateAutoCheckDecision.Throttled)
        assertTrue((decision as UpdateAutoCheckDecision.Throttled).nextAllowedInMs > 0)
    }

    @Test
    fun afterTwentyFourHoursItChecksAgainWithTheStoredEtag() {
        val memory = UpdateCheckMemory(lastCheckMs = now - day - 1L, etag = "\"abc\"")
        val decision = UpdateAutoCheck.decide(UpdateReleaseChannel.PREVIEW, memory, now)
        assertEquals("\"abc\"", (decision as UpdateAutoCheckDecision.Check).etag)
    }

    @Test
    fun aFailedAttemptOnlyBuysAShortCooldown() {
        // 失败的记录必须自己带着标志，策略才能给出 10 分钟冷却。
        val failedMemory = UpdateCheckMemory(lastCheckMs = now - 60_000L, lastAttemptFailed = true)
        val throttled = UpdateAutoCheck.decide(UpdateReleaseChannel.OFFICIAL, failedMemory, now)
        assertTrue(throttled is UpdateAutoCheckDecision.Throttled)

        val afterCooldown = UpdateAutoCheck.decide(
            UpdateReleaseChannel.OFFICIAL,
            failedMemory.copy(lastCheckMs = now - UpdateAutoCheck.FAILURE_COOLDOWN_MS - 1L),
            now,
        )
        assertTrue(afterCooldown is UpdateAutoCheckDecision.Check)
    }

    /** 成功的记录走 24 小时；同一时间点下失败走 10 分钟，两者必须真的不同。 */
    @Test
    fun successWaitsADayWhileFailureWaitsTenMinutes() {
        val elapsed = UpdateAutoCheck.FAILURE_COOLDOWN_MS + 1L
        val success = UpdateCheckMemory(lastCheckMs = now - elapsed, lastAttemptFailed = false)
        val failure = UpdateCheckMemory(lastCheckMs = now - elapsed, lastAttemptFailed = true)
        assertTrue(UpdateAutoCheck.decide(UpdateReleaseChannel.OFFICIAL, success, now) is UpdateAutoCheckDecision.Throttled)
        assertTrue(UpdateAutoCheck.decide(UpdateReleaseChannel.OFFICIAL, failure, now) is UpdateAutoCheckDecision.Check)
    }

    /** 失败标志必须落盘：换一个 Store 实例（模拟进程重启）也要看到 10 分钟冷却。 */
    @Test
    fun theFailureFlagSurvivesANewStoreInstance() {
        val store = MapUpdateCheckStore()
        store.write(UpdateAutoCheck.afterAttempt(store.read(), now, succeeded = false))
        assertTrue(store.read().lastAttemptFailed)

        val reopened = MapUpdateCheckStore(store.read())
        val decision = UpdateAutoCheck.decide(UpdateReleaseChannel.OFFICIAL, reopened.read(), now + 60_000L)
        assertTrue(decision is UpdateAutoCheckDecision.Throttled)
        assertTrue(
            UpdateAutoCheck.decide(
                UpdateReleaseChannel.OFFICIAL,
                reopened.read(),
                now + UpdateAutoCheck.FAILURE_COOLDOWN_MS + 1L,
            ) is UpdateAutoCheckDecision.Check,
        )
    }

    /** 成功之后失败标志要清掉，否则会一直按 10 分钟冷却反复请求。 */
    @Test
    fun aSuccessClearsTheFailureFlag() {
        val previous = UpdateCheckMemory(lastCheckMs = now, lastAttemptFailed = true, etag = "\"old\"")
        val success = UpdateAutoCheck.afterAttempt(previous, now, succeeded = true, etag = "\"new\"")
        assertFalse(success.lastAttemptFailed)
        assertEquals("\"new\"", success.etag)
    }

    @Test
    fun anUnknownChannelNeverAutoChecks() {
        val decision = UpdateAutoCheck.decide(null, UpdateCheckMemory(), now)
        assertTrue(decision is UpdateAutoCheckDecision.Inactive)
    }

    @Test
    fun aClockMovedBackwardsDoesNotCauseAStorm() {
        val memory = UpdateCheckMemory(lastCheckMs = now + day)
        val decision = UpdateAutoCheck.decide(UpdateReleaseChannel.OFFICIAL, memory, now)
        assertTrue(decision is UpdateAutoCheckDecision.Throttled)
    }

    @Test
    fun onlyARealNewerVersionIsWorthInterruptingTheUser() {
        val candidate = UpdateCandidate(
            version = UpdateVersion.parse("0.8.0")!!,
            tag = "v0.8.0",
            name = "星栞 0.8.0",
            htmlUrl = "https://github.com/Starlevin/Hoshiori/releases/tag/v0.8.0",
            notes = null,
            publishedAt = null,
            apk = UpdateAsset("Hoshiori-v0.8.0.apk", 1L, "https://github.com/a/b.apk", "sha256:" + "a".repeat(64)),
        )
        assertTrue(UpdateAutoCheck.shouldNotify(UpdateCheck.Available(candidate), noticedVersion = null))
        assertFalse(UpdateAutoCheck.shouldNotify(UpdateCheck.Available(candidate), noticedVersion = "0.8.0"))
        // 无更新 / 检查失败都不打扰。
        assertFalse(UpdateAutoCheck.shouldNotify(UpdateCheck.UpToDate("0.8.0", NoUpdateReason.UP_TO_DATE, "最新"), null))
        assertFalse(UpdateAutoCheck.shouldNotify(UpdateCheck.Failed(UpdateFailure.OFFLINE, "无网络"), null))
        assertFalse(UpdateAutoCheck.shouldNotify(null, null))
    }

    @Test
    fun aSuccessfulAttemptStoresTheEtagAFailedOneKeepsTheOldOne() {
        val previous = UpdateCheckMemory(lastCheckMs = 0L, etag = "\"old\"", noticedVersion = "0.7.8")
        val success = UpdateAutoCheck.afterAttempt(previous, now, succeeded = true, etag = "\"new\"")
        assertEquals(now, success.lastCheckMs)
        assertEquals("\"new\"", success.etag)
        assertEquals("0.7.8", success.noticedVersion)

        val failure = UpdateAutoCheck.afterAttempt(previous, now, succeeded = false, etag = null)
        assertEquals(now, failure.lastCheckMs)
        assertEquals("\"old\"", failure.etag)
    }

    @Test
    fun noticingAVersionIsRememberedInTheStore() {
        val store = MapUpdateCheckStore()
        store.write(UpdateAutoCheck.markNoticed(store.read(), "0.8.0"))
        assertEquals("0.8.0", store.read().noticedVersion)
    }
}

/** 卡片状态对象的派生判断（不需要 ViewModel，也不需要 Android）。 */
class UpdateUiStateDownloadTest {

    private fun state(
        versionName: String = "0.7.9",
        isDebug: Boolean = false,
        result: UpdateCheck? = null,
        download: UpdateDownloadState = UpdateDownloadState.Idle,
    ) = UpdateUiState(
        build = UpdateBuildInfo(versionName, 21, "com.glasssign.app", isDebug = isDebug),
        result = result,
        download = download,
    )

    private val candidate = UpdateCandidate(
        version = UpdateVersion.parse("0.8.0")!!,
        tag = "v0.8.0",
        name = "星栞 0.8.0",
        htmlUrl = "https://github.com/Starlevin/Hoshiori/releases/tag/v0.8.0",
        notes = null,
        publishedAt = null,
        apk = UpdateAsset(
            "Hoshiori-v0.8.0.apk",
            18_000_000L,
            "https://github.com/Starlevin/Hoshiori/releases/download/v0.8.0/Hoshiori-v0.8.0.apk",
            "sha256:" + "a".repeat(64),
        ),
    )

    @Test
    fun theReleaseChannelFollowsTheVersionName() {
        assertEquals(UpdateReleaseChannel.OFFICIAL, state().releaseChannel)
        assertEquals(UpdateReleaseChannel.PREVIEW, state(versionName = "0.7.9-preview.3").releaseChannel)
        // debug 构建不自动检查：它不是发布产物。
        assertNull(state(isDebug = true).releaseChannel)
    }

    /**
     * 正在组装的预览包以正式 applicationId + 正式签名发布，versionName 为 `0.7.9-preview.3`。
     * 它必须查预览通道（否则永远收不到预览更新），并且能被原地覆盖升级。
     */
    @Test
    fun thePreviewBuildShippedWithTheReleaseIdChecksThePreviewChannel() {
        val shipped = UpdateUiState(
            build = UpdateBuildInfo("0.7.9-preview.3", 21, "com.glasssign.app", isDebug = false),
        )
        assertEquals(UpdateReleaseChannel.PREVIEW, shipped.releaseChannel)
        assertEquals("com.glasssign.app", shipped.install.releaseApplicationId)
        assertFalse(shipped.install.separateFromRelease)
        // 预览序号参与比较：0.7.9-preview.3 < 0.7.9-preview.4，且都小于 0.7.9 本身。
        val current = UpdateVersion.parseInstalled(shipped.build.versionName)!!
        assertTrue(current < UpdateVersion.parseInstalled("0.7.9-preview.4")!!)
        assertTrue(current < UpdateVersion.parseInstalled("0.7.9")!!)
    }

    @Test
    fun aPreviewInstallIsNotASeparateAppFromItsOwnChannel() {
        val preview = state(versionName = "0.7.9-preview.3").install
        assertFalse(preview.separateFromRelease)
        assertTrue(preview.isPreview)
    }

    @Test
    fun onlyVerifiedDownloadsEnableTheInstallButton() {
        val verified = UpdateDownloadState.Verified(file = java.io.File("x.apk"), sha256 = "a".repeat(64), sizeBytes = 1L, versionName = "0.8.0")
        assertEquals(verified, state(result = UpdateCheck.Available(candidate), download = verified).verified)
        assertNull(state(download = UpdateDownloadState.Running(1L, 2L)).verified)
        assertNull(state(download = UpdateDownloadState.Failed("boom")).verified)
    }

    @Test
    fun progressPercentageNeverDividesByZero() {
        assertNull(UpdateDownloadState.Running(10L, 0L).percent)
        assertEquals(50, UpdateDownloadState.Running(50L, 100L).percent)
        assertEquals(100, UpdateDownloadState.Running(150L, 100L).percent)
    }

    @Test
    fun theExternalFallbackPrefersTheReleasePageThenQuark() {
        val withPage = state(result = UpdateCheck.Available(candidate))
            .copy(quarkUrl = "https://pan.quark.cn/s/739482783865")
        assertEquals(candidate.htmlUrl, withPage.externalFallback)

        val withoutPage = state(result = null).copy(quarkUrl = "https://pan.quark.cn/s/739482783865")
        assertEquals("https://pan.quark.cn/s/739482783865", withoutPage.externalFallback)

        assertNull(state().externalFallback)
    }
}
