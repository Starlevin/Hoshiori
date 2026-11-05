package com.glasssign.app.ui.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Strict semantic versions. Every rejection here is a case where a loose parser would have produced a
 * confident, wrong "update available" — which is the failure mode this feature can least afford.
 */
class UpdateVersionTest {

    @Test
    fun parsesPlainAndVPrefixedTags() {
        assertEquals(UpdateVersion(0, 7, 6), UpdateVersion.parse("0.7.6"))
        assertEquals(UpdateVersion(0, 7, 6), UpdateVersion.parse("v0.7.6"))
        assertEquals(UpdateVersion(0, 7, 6), UpdateVersion.parse("V0.7.6"))
        assertEquals(UpdateVersion(12, 0, 0), UpdateVersion.parse(" 12.0.0 "))
        assertEquals("0.7.6", UpdateVersion.parse("v0.7.6")!!.toString())
    }

    @Test
    fun rejectsLooseOrDebuggishTags() {
        // The debug suffix is the exact string BuildConfig.VERSION_NAME carries in debug builds; it is
        // a build flavour, not a release, so it must never parse as a comparable version.
        assertNull(UpdateVersion.parse("0.7.6-debug"))
        assertNull(UpdateVersion.parse("0.7.6-unsigned"))
        assertNull(UpdateVersion.parse("0.7"))
        assertNull(UpdateVersion.parse("0.7.6.1"))
        assertNull(UpdateVersion.parse("latest"))
        assertNull(UpdateVersion.parse(""))
        assertNull(UpdateVersion.parse(null))
        assertNull(UpdateVersion.parse("0.8.0+build5"))
        assertNull(UpdateVersion.parse("v0.7.6-"))
        assertNull(UpdateVersion.parse("0.7.6-beta."))
    }

    @Test
    fun installedVersionToleratesTheDebugFlavourButNothingElse() {
        // The INSTALLED side must be comparable even in a debug build...
        assertEquals(UpdateVersion(0, 7, 6), UpdateVersion.parseInstalled("0.7.6-debug"))
        assertEquals(UpdateVersion(0, 7, 6), UpdateVersion.parseInstalled("0.7.6"))
        // ...but junk is still junk.
        assertNull(UpdateVersion.parseInstalled("nightly"))
        assertNull(UpdateVersion.parseInstalled("0.7.6-whatever"))
        // An arbitrary pre-release is NOT an installed identity: this is the baseline of the update
        // comparison, so letting any suffix in would shift whether a release counts as newer.
        assertNull(UpdateVersion.parseInstalled("0.7.6-rc1"))
        assertNull(UpdateVersion.parseInstalled("0.7.6-beta"))
        assertNull(UpdateVersion.parseInstalled("0.7.6-alpha.1"))
        assertNull(UpdateVersion.parseInstalled("0.7.6-whatever.debug"))
        // A pre-release glued to a flavour is still not an installed version.
        assertNull(UpdateVersion.parseInstalled("0.7.6-beta-debug"))
        // Real build flavours remain acceptable, including dotted ones.
        assertEquals(UpdateVersion(0, 7, 6), UpdateVersion.parseInstalled("0.7.6-debug.signed"))
        assertEquals(UpdateVersion(0, 7, 6), UpdateVersion.parseInstalled("v0.7.6-debug"))
        assertNull(UpdateVersion.parseInstalled(""))
        assertNull(UpdateVersion.parseInstalled("0.7"))
    }

    /**
     * 预览构建的 versionName 是 `0.7.9-preview.3`。必须能解析，否则预览安装永远报「版本号不可解析」；
     * 而且预览序号要参与比较，`-preview.10` 必须比 `-preview.9` 新。
     */
    @Test
    fun thePreviewBuildsOwnVersionIsComparable() {
        val p3 = UpdateVersion.parseInstalled("0.7.9-preview.3")!!
        assertEquals(0, p3.major)
        assertEquals(7, p3.minor)
        assertEquals(9, p3.patch)
        assertTrue(p3.isPreview)
        assertFalse(p3.isFinal)

        // 序号按数值比较，不是字符串比较。
        assertTrue(p3 < UpdateVersion.parseInstalled("0.7.9-preview.10")!!)
        assertTrue(UpdateVersion.parseInstalled("0.7.9-preview.9")!! < UpdateVersion.parseInstalled("0.7.9-preview.10")!!)
        // 不带序号的预览排在同版本所有带序号的预览之前。
        assertTrue(UpdateVersion.parseInstalled("0.7.9-preview")!! < p3)
        // 预览排在最终正式版之前：预览用户可以前进到 0.7.9。
        assertTrue(p3 < UpdateVersion.parseInstalled("0.7.9")!!)

        assertTrue(UpdateVersion.parseInstalled("v0.7.9-preview.3")!!.isPreview)
        // 非法标识仍然拒绝。
        assertNull(UpdateVersion.parseInstalled("0.7.9-preview.x"))
        assertNull(UpdateVersion.parseInstalled("0.7.9-preview.3.debug"))
    }

    @Test
    fun aPreviewTagParsesWithItsTailButIsNotFinal() {
        val tag = UpdateVersion.parse("v0.8.0-preview.1")!!
        assertFalse(tag.isFinal)
        assertTrue(tag.isPreview)
        assertEquals(listOf("preview", "1"), tag.preRelease)
    }

    @Test
    fun ordersNumericallyNotLexically() {
        // The classic bug: "0.7.10" < "0.7.9" as strings.
        assertTrue(UpdateVersion.parse("0.7.10")!! > UpdateVersion.parse("0.7.9")!!)
        assertTrue(UpdateVersion.parse("0.10.0")!! > UpdateVersion.parse("0.9.9")!!)
        assertTrue(UpdateVersion.parse("1.0.0")!! > UpdateVersion.parse("0.99.99")!!)
    }

    @Test
    fun preReleaseSortsBelowItsRelease() {
        val beta = UpdateVersion.parse("0.8.0-beta")!!
        val final = UpdateVersion.parse("0.8.0")!!
        assertTrue(beta < final)
        assertTrue(beta.compareTo(final) < 0)
        assertFalse(final.isFinal.not())
        assertFalse(beta.isFinal)
    }

    @Test
    fun equalVersionsAreEqual() {
        assertEquals(0, UpdateVersion.parse("v0.7.6")!!.compareTo(UpdateVersion.parse("0.7.6")!!))
    }
}

/** The rules that decide "is this the official upgrade?" — the heart of the feature. */
class ReleasePolicyTest {

    private fun release(
        tag: String,
        draft: Boolean = false,
        prerelease: Boolean = false,
        assets: List<UpdateAsset> = listOf(apk(tag)),
    ) = UpdateRelease(
        tag = tag,
        name = null,
        htmlUrl = "https://github.com/Starlevin/Hoshiori/releases/tag/$tag",
        body = "notes",
        publishedAt = "2026-11-01T00:00:00Z",
        draft = draft,
        prerelease = prerelease,
        assets = assets,
    )

    private fun apk(
        tag: String,
        name: String = "Hoshiori-$tag.apk",
        digest: String? = "sha256:" + "a".repeat(64),
    ) = UpdateAsset(
        name = name,
        sizeBytes = 12_345_678L,
        browserDownloadUrl = "https://github.com/Starlevin/Hoshiori/releases/download/$tag/$name",
        sha256 = digest,
    )

    @Test
    fun newerReleaseIsSelected() {
        val (candidate, _) = ReleasePolicy.select("0.7.6", listOf(release("v0.7.7")))
        assertNotNull(candidate)
        assertEquals("0.7.7", candidate!!.version.toString())
        assertEquals("v0.7.7", candidate.tag)
    }

    // --- 预览通道 ---------------------------------------------------------------------------------

    /**
     * 预览通道以前永远查不到东西：`select` 无条件过滤掉 prerelease 与带后缀的版本。
     * GitHub 上的预览发布基本都标着 prerelease，所以预览安装此前一直被告知「已是最新」。
     */
    @Test
    fun thePreviewChannelFindsAPrereleasePreviewBuild() {
        val preview = release(
            "v0.7.9-preview.4",
            prerelease = true,
            assets = listOf(apk("v0.7.9-preview.4", name = "Hoshiori-v0.7.9-preview.4.apk")),
        )
        val (candidate, _) = ReleasePolicy.select("0.7.9-preview.3", listOf(preview), UpdateReleaseChannel.PREVIEW)
        assertNotNull(candidate)
        assertEquals("0.7.9-preview.4", candidate!!.version.toString())
        assertEquals(UpdateReleaseChannel.PREVIEW, candidate.channel)
    }

    /** 预览序号参与比较：preview.10 比 preview.9 新。 */
    @Test
    fun previewNumbersAreComparedNumerically() {
        val nine = release("v0.7.9-preview.9", assets = listOf(apk("v0.7.9-preview.9", name = "Hoshiori-v0.7.9-preview.9.apk")))
        val ten = release("v0.7.9-preview.10", assets = listOf(apk("v0.7.9-preview.10", name = "Hoshiori-v0.7.9-preview.10.apk")))
        val (candidate, _) = ReleasePolicy.select("0.7.9-preview.9", listOf(nine, ten), UpdateReleaseChannel.PREVIEW)
        assertEquals("0.7.9-preview.10", candidate!!.version.toString())
    }

    /** 预览用户可以前进到同版本的最终正式版。 */
    @Test
    fun thePreviewChannelAlsoOffersTheFinalRelease() {
        val final = release("v0.7.9", assets = listOf(apk("v0.7.9", name = "Hoshiori-v0.7.9.apk")))
        val (candidate, _) = ReleasePolicy.select("0.7.9-preview.3", listOf(final), UpdateReleaseChannel.PREVIEW)
        assertNotNull(candidate)
        assertTrue(candidate!!.version.isFinal)
    }

    /** 正式通道仍然只认最终正式版：预览版本不算升级。 */
    @Test
    fun theOfficialChannelStillRefusesPreviewReleases() {
        val preview = release(
            "v0.8.0-preview.1",
            prerelease = true,
            assets = listOf(apk("v0.8.0-preview.1", name = "Hoshiori-v0.8.0-preview.1.apk")),
        )
        val (candidate, _) = ReleasePolicy.select("0.7.9", listOf(preview), UpdateReleaseChannel.OFFICIAL)
        assertNull(candidate)
    }

    /** 预览通道也不接受别的预发布（beta）：那不是预览通道发布的产物。 */
    @Test
    fun thePreviewChannelStillRefusesOtherPrereleases() {
        val beta = release(
            "v0.8.0-beta.1",
            prerelease = true,
            assets = listOf(apk("v0.8.0-beta.1", name = "Hoshiori-v0.8.0-beta.1.apk")),
        )
        val (candidate, _) = ReleasePolicy.select("0.7.9-preview.3", listOf(beta), UpdateReleaseChannel.PREVIEW)
        assertNull(candidate)
    }

    /** 通道与包名解耦：正式包名的预览构建也必须能查到预览发布。 */
    @Test
    fun thePreviewChannelIsIndependentOfThePackageName() {
        val preview = release(
            "v0.7.9-preview.4",
            prerelease = true,
            assets = listOf(apk("v0.7.9-preview.4", name = "Hoshiori-v0.7.9-preview.4.apk")),
        )
        val build = UpdateBuildInfo("0.7.9-preview.3", 21, "com.glasssign.app", isDebug = false)
        val (candidate, _) = ReleasePolicy.select(build.versionName, listOf(preview), build.channel!!)
        assertNotNull(candidate)
    }

    /**
     * The user-visible regression: a 0.7.4 install must be told about 0.7.5. Before the digest-field
     * fix this returned "resource incomplete" for a perfectly published release.
     */
    @Test
    fun aV074InstallFindsThePublishedV075() {
        val (candidate, _) = ReleasePolicy.select("0.7.4", listOf(release("v0.7.5")))
        assertNotNull(candidate)
        assertEquals("0.7.5", candidate!!.version.toString())
        assertTrue(ReleasePolicy.isReleaseApk(candidate.apk.name))
        assertTrue(ReleaseDigest.isWellFormed(candidate.apk.sha256))
        // And the asset's download URL is one the card is allowed to hand to a browser.
        assertTrue(UpdateUrls.isReleaseApkUrl(candidate.apk.browserDownloadUrl))
    }

    @Test
    fun aV075InstallIsUpToDateAgainstTheSameV075() {
        val (candidate, reason) = ReleasePolicy.select("0.7.5", listOf(release("v0.7.5")))
        assertNull(candidate)
        assertEquals(NoUpdateReason.UP_TO_DATE, reason)
    }

    @Test
    fun equalVersionIsNotAnUpdate() {
        val (candidate, reason) = ReleasePolicy.select("0.7.6", listOf(release("v0.7.6")))
        assertNull(candidate)
        assertEquals(NoUpdateReason.UP_TO_DATE, reason)
    }

    @Test
    fun olderReleaseNeverTriggersADowngrade() {
        val (candidate, reason) = ReleasePolicy.select("0.7.6", listOf(release("v0.7.5"), release("v0.6.9")))
        assertNull(candidate)
        assertEquals(NoUpdateReason.UP_TO_DATE, reason)
    }

    @Test
    fun draftIsRefused() {
        val (candidate, _) = ReleasePolicy.select("0.7.6", listOf(release("v0.7.9", draft = true)))
        assertNull(candidate)
    }

    @Test
    fun prereleaseIsRefused() {
        val (candidate, _) = ReleasePolicy.select("0.7.6", listOf(release("v0.7.9", prerelease = true)))
        assertNull(candidate)
    }

    @Test
    fun debugApkAssetIsRefused() {
        // The publisher's own debug build: different applicationId, cannot share data.
        val bad = listOf(apk("v0.7.9", name = "Hoshiori-v0.7.9-debug.apk"))
        val (candidate, reason) = ReleasePolicy.select("0.7.6", listOf(release("v0.7.9", assets = bad)))
        assertNull(candidate)
        assertEquals(NoUpdateReason.MISSING_APK_OR_DIGEST, reason)
    }

    @Test
    fun nonApkAssetIsRefused() {
        for (name in listOf("source.zip", "app.aab", "notes.txt", "app.apk.sha256")) {
            assertFalse(name, ReleasePolicy.isReleaseApk(name))
        }
        assertTrue(ReleasePolicy.isReleaseApk("Hoshiori-0.7.7.apk"))
        assertTrue(ReleasePolicy.isReleaseApk("星栞-0.7.7.APK"))
    }

    @Test
    fun missingDigestIsRefused() {
        val bad = listOf(apk("v0.7.9", digest = null))
        val (candidate, reason) = ReleasePolicy.select("0.7.6", listOf(release("v0.7.9", assets = bad)))
        assertNull(candidate)
        assertEquals(NoUpdateReason.MISSING_APK_OR_DIGEST, reason)
    }

    @Test
    fun malformedDigestIsRefused() {
        for (digest in listOf("sha256:xyz", "md5:" + "a".repeat(32), "sha256:" + "a".repeat(63))) {
            val bad = listOf(apk("v0.7.9", digest = digest))
            val (candidate, _) = ReleasePolicy.select("0.7.6", listOf(release("v0.7.9", assets = bad)))
            assertNull(candidate)
        }
    }

    @Test
    fun picksHighestUsableWhenSeveralExist() {
        val all = listOf(release("v0.7.7"), release("v0.8.1"), release("v0.8.0"))
        val (candidate, _) = ReleasePolicy.select("0.7.6", all)
        assertEquals("0.8.1", candidate!!.version.toString())
    }

    @Test
    fun newestUnusableDoesNotSilentlyOfferAnOlderOne() {
        // 0.8.1 exists but its APK is a debug build; offering 0.7.7 instead would install a version the
        // user must immediately replace.
        val all = listOf(
            release("v0.7.7"),
            release("v0.8.1", assets = listOf(apk("v0.8.1", name = "hoshiori-debug.apk"))),
        )
        val (candidate, reason) = ReleasePolicy.select("0.7.6", all)
        assertNull(candidate)
        assertEquals(NoUpdateReason.MISSING_APK_OR_DIGEST, reason)
    }

    @Test
    fun garbageTagsAreIgnoredRatherThanWinning() {
        val all = listOf(release("nightly"), release("v0.8"), release("v0.7.7"))
        val (candidate, _) = ReleasePolicy.select("0.7.6", all)
        assertEquals("0.7.7", candidate!!.version.toString())
    }

    @Test
    fun malformedCurrentVersionIsReportedNotTreatedAsNewest() {
        val (candidate, reason) = ReleasePolicy.select("not-a-version", listOf(release("v0.7.7")))
        assertNull(candidate)
        assertEquals(NoUpdateReason.UNREADABLE_PAYLOAD, reason)
    }

    @Test
    fun aDebugBuildCanStillCheckForUpdates() {
        // BuildConfig.VERSION_NAME is "0.7.6-debug" in debug; the check must still work there.
        val (candidate, _) = ReleasePolicy.select("0.7.6-debug", listOf(release("v0.7.7")))
        assertEquals("0.7.7", candidate!!.version.toString())
    }

    @Test
    fun aDebugTagIsNeverAcceptedAsARelease() {
        val (candidate, _) = ReleasePolicy.select("0.7.6", listOf(release("v0.7.7-debug")))
        assertNull(candidate)
    }

    @Test
    fun candidateCarriesNameNotesAndApk() {
        val (candidate, _) = ReleasePolicy.select("0.7.6", listOf(release("v0.7.7")))
        assertEquals("v0.7.7", candidate!!.name)
        assertEquals("notes", candidate.notes)
        assertTrue(ReleasePolicy.isReleaseApk(candidate.apk.name))
    }
}

/** JSON in, release list out. The payload shapes are GitHub's own, including the optional fields. */
class ReleaseJsonTest {

    @Test
    fun parsesSingleLatestObject() {
        val json = """
            {
              "tag_name": "v0.7.7",
              "name": "星栞 0.7.7",
              "html_url": "https://github.com/Starlevin/Hoshiori/releases/tag/v0.7.7",
              "body": "## 变更",
              "published_at": "2026-11-01T10:00:00Z",
              "draft": false,
              "prerelease": false,
              "assets": [
                {
                  "name": "Hoshiori-v0.7.7.apk",
                  "size": 12345678,
                  "browser_download_url": "https://github.com/Starlevin/Hoshiori/releases/download/v0.7.7/Hoshiori-v0.7.7.apk",
                  "digest": "sha256:${"a".repeat(64)}"
                }
              ]
            }
        """.trimIndent()
        val list = ReleaseJson.parseReleases(json)
        assertEquals(1, list.size)
        val r = list.first()
        assertEquals("v0.7.7", r.tag)
        assertFalse(r.draft)
        assertFalse(r.prerelease)
        assertEquals(1, r.assets.size)
        assertEquals(12_345_678L, r.assets.first().sizeBytes)
        assertTrue(ReleaseDigest.isWellFormed(r.assets.first().sha256))
        assertEquals("a".repeat(64), ReleaseDigest.hex(r.assets.first().sha256))
    }

    /**
     * The regression this guards: GitHub's asset field is `digest`, not `sha256_digest`. Reading the
     * wrong key made every REAL release look digest-less, so a genuinely newer version was reported as
     * 「资源不完整」 and never offered. Pinned against the exact shape of the published v0.7.5 release.
     */
    @Test
    fun readsGithubsRealDigestFieldNotAnInventedOne() {
        val realV075 = """
            {
              "url": "https://api.github.com/repos/Starlevin/Hoshiori/releases/175000000",
              "assets_url": "https://api.github.com/repos/Starlevin/Hoshiori/releases/175000000/assets",
              "html_url": "https://github.com/Starlevin/Hoshiori/releases/tag/v0.7.5",
              "id": 175000000,
              "tag_name": "v0.7.5",
              "target_commitish": "main",
              "name": "星栞 Hoshiori v0.7.5",
              "draft": false,
              "prerelease": false,
              "created_at": "2026-10-28T09:12:41Z",
              "published_at": "2026-10-28T09:20:05Z",
              "body": "## 星栞 0.7.5\n- 修复若干问题",
              "assets": [
                {
                  "url": "https://api.github.com/repos/Starlevin/Hoshiori/releases/assets/1",
                  "id": 1,
                  "name": "Hoshiori-0.7.5.apk",
                  "label": "",
                  "content_type": "application/vnd.android.package-archive",
                  "state": "uploaded",
                  "size": 18432127,
                  "digest": "sha256:9f2c1d4e7a3b5086c0d9e1f2a4b6c8d0e2f4a6b8c0d2e4f6a8b0c2d4e6f8a0b2",
                  "download_count": 12,
                  "browser_download_url": "https://github.com/Starlevin/Hoshiori/releases/download/v0.7.5/Hoshiori-0.7.5.apk"
                }
              ]
            }
        """.trimIndent()

        val (candidate, _) = ReleasePolicy.select(
            "0.7.4",
            ReleaseJson.parseReleases(realV075),
        )
        assertNotNull("v0.7.5 must be offered to a v0.7.4 build", candidate)
        assertEquals("0.7.5", candidate!!.version.toString())
        assertEquals("Hoshiori-0.7.5.apk", candidate.apk.name)
        assertTrue(candidate.apk.sha256!!.startsWith("sha256:"))
    }

    /** The legacy key must not resurrect the bug: it is a fallback only, never needed. */
    @Test
    fun missingDigestFieldStillFailsEvenWithUnrelatedKeys() {
        val json = """
            {"tag_name":"v0.7.5","draft":false,"prerelease":false,"assets":[
              {"name":"Hoshiori-0.7.5.apk","size":1,
               "browser_download_url":"https://github.com/Starlevin/Hoshiori/releases/download/v0.7.5/Hoshiori-0.7.5.apk"}
            ]}
        """.trimIndent()
        val (candidate, reason) = ReleasePolicy.select("0.7.4", ReleaseJson.parseReleases(json))
        assertNull(candidate)
        assertEquals(NoUpdateReason.MISSING_APK_OR_DIGEST, reason)
    }

    @Test
    fun parsesArrayAndSkipsDrafts() {
        val json = """
            [
              {"tag_name":"v0.8.0","draft":true,"prerelease":false,"assets":[]},
              {"tag_name":"v0.7.9","draft":false,"prerelease":true,"assets":[]},
              {"tag_name":"v0.7.7","draft":false,"prerelease":false,"assets":[]}
            ]
        """.trimIndent()
        val list = ReleaseJson.parseReleases(json)
        assertEquals(3, list.size)
        val (candidate, _) = ReleasePolicy.select("0.7.6", list)
        // Draft and prerelease lose; and both lack assets, so even 0.7.7 is unusable.
        assertNull(candidate)
    }

    @Test
    fun missingOptionalFieldsDoNotBreakParsing() {
        val list = ReleaseJson.parseReleases("""{"tag_name":"v0.7.7"}""")
        assertEquals(1, list.size)
        assertTrue(list.first().assets.isEmpty())
        assertNull(list.first().assets.firstOrNull())
        assertFalse(list.first().draft)
    }

    @Test(expected = java.io.IOException::class)
    fun nonJsonPayloadFailsLoudly() {
        ReleaseJson.parseReleases("<\u0021DOCTYPE html><html>rate limited</html>")
    }
}

/** Host/scheme/path allow-lists. A URL that fails here is never handed to an Intent. */
class UpdateUrlsTest {

    @Test
    fun acceptsTheOfficialEndpoints() {
        assertEquals(
            "https://api.github.com/repos/Starlevin/Hoshiori/releases/latest",
            UpdateUrls.LATEST_RELEASE_URL,
        )
        assertTrue(
            UpdateUrls.isReleasePageUrl("https://github.com/Starlevin/Hoshiori/releases/tag/v0.7.7")
        )
    }

    @Test
    fun acceptsTheQuarkPermanentLink() {
        assertTrue(UpdateUrls.isQuarkLink("https://pan.quark.cn/s/739482783865"))
    }

    @Test
    fun refusesNonHttpsAndGarbage() {
        for (url in listOf(
            "http://pan.quark.cn/s/739482783865",
            "javascript:alert(1)",
            "file:///data/local/tmp/x.apk",
            "https://",
            "https://nodot",
            "",
            "   ",
        )) {
            assertFalse(url, UpdateUrls.isQuarkLink(url))
        }
        assertFalse(UpdateUrls.isQuarkLink(null))
    }

    @Test
    fun refusesUserInfoDisguisedAsQuark() {
        // A link that reads as pan.quark.cn but actually points at another host.
        assertFalse(UpdateUrls.isQuarkLink("https://pan.quark.cn@evil.example/s/1"))
    }

    /**
     * The host is PINNED to `pan.quark.cn`. Accepting any `https` domain would let a bad configuration
     * — or a future edit to the bundled asset — send the user to an arbitrary site wearing the words
     * "official download".
     */
    @Test
    fun refusesMaliciousOrForeignDomainsAsQuarkLinks() {
        for (url in listOf(
            "https://evil.example/s/739482783865",
            "https://pan.quark.cn.evil.example/s/739482783865",
            "https://evilpan.quark.cn/s/739482783865",
            "https://quark.cn/s/739482783865",
            "https://www.quark.cn/s/739482783865",
            "https://pan.quark.cn.evil.example/s/1",
            "https://github.com/Starlevin/Hoshiori/releases/tag/v0.7.5",
            "https://127.0.0.1/s/1",
        )) {
            assertFalse(url, UpdateUrls.isQuarkLink(url))
        }
    }

    @Test
    fun refusesQuarkHostWithoutARealSharePath() {
        for (url in listOf(
            "https://pan.quark.cn",
            "https://pan.quark.cn/",
            "https://pan.quark.cn/s/",
            "https://pan.quark.cn/s",
            "https://pan.quark.cn/s/1/2",
            "https://pan.quark.cn/other/739482783865",
        )) {
            assertFalse(url, UpdateUrls.isQuarkLink(url))
        }
    }

    @Test
    fun acceptsTheConfiguredQuarkLinkShapeOnly() {
        assertTrue(UpdateUrls.isQuarkLink("https://pan.quark.cn/s/739482783865"))
        // A trailing slash after the id is still the same directory.
        assertFalse(UpdateUrls.isQuarkLink("https://pan.quark.cn/s/739482783865/"))
    }

    @Test
    fun refusesReleasePageUrlsOutsideTheProjectPath() {
        assertFalse(
            UpdateUrls.isReleasePageUrl("https://github.com/attacker/Hoshiori/releases/tag/v0.7.7")
        )
        assertFalse(
            UpdateUrls.isReleasePageUrl("https://github.com/Starlevin/Hoshiori/releases/tag/nightly")
        )
        assertFalse(UpdateUrls.isReleasePageUrl("https://evil.example/Starlevin/Hoshiori/releases/tag/v0.7.7"))
    }

    @Test
    fun refusesCdnDownloadUrls() {
        // The API's CDN link is for a client that downloads itself; this app hands off to a browser.
        assertFalse(
            UpdateUrls.isReleaseApkUrl(
                "https://objects.githubusercontent.com/releases/download/v0.7.7/app.apk"
            )
        )
        assertFalse(
            UpdateUrls.isReleaseApkUrl(
                "https://api.github.com/repos/Starlevin/Hoshiori/releases/assets/1"
            )
        )
    }

    @Test
    fun acceptsOnlyReleaseApkPathsOnGithub() {
        assertTrue(
            UpdateUrls.isReleaseApkUrl(
                "https://github.com/Starlevin/Hoshiori/releases/download/v0.7.7/Hoshiori-v0.7.7.apk"
            )
        )
        // Debug APK: refused at the URL layer too, not only in the release filter.
        assertFalse(
            UpdateUrls.isReleaseApkUrl(
                "https://github.com/Starlevin/Hoshiori/releases/download/v0.7.7/Hoshiori-v0.7.7-debug.apk"
            )
        )
        assertFalse(
            UpdateUrls.isReleaseApkUrl(
                "https://github.com/Starlevin/Hoshiori/releases/download/v0.7.7/notes.txt"
            )
        )
    }

    @Test
    fun digestFormattingNeverLaundersBadInput() {
        assertEquals("未提供", ReleaseDigest.short(null))
        assertEquals("未提供", ReleaseDigest.short("sha256:short"))
        val good = "sha256:" + "abcdef0123456789".repeat(4)
        assertEquals("abcdef0123…456789", ReleaseDigest.short(good))
        assertEquals("abcdef0123456789".repeat(4), ReleaseDigest.hex(good))
        // The same digest pasted as bare hex must format identically, or a release note's copy of the
        // hash would render as "未提供" while the API's copy rendered fine.
        val bare = "abcdef0123456789".repeat(4)
        assertEquals("abcdef0123…456789", ReleaseDigest.short(bare))
        assertEquals("abcdef0123456789".repeat(4), ReleaseDigest.hex(bare))
        assertTrue(ReleaseDigest.isWellFormed(bare))
        // Still not a free pass: wrong length and non-sha256 algorithms stay refused.
        assertFalse(ReleaseDigest.isWellFormed("abcdef0123456789".repeat(3)))
        assertFalse(ReleaseDigest.isWellFormed("md5:" + "a".repeat(32)))
    }
}

/** The bundled channel config, including the "not configured yet" state. */
class UpdateChannelConfigTest {

    @Test
    fun parsesTheConfiguredQuarkLink() {
        val json = """
            {"schemaVersion":1,"quark":{"url":"https://pan.quark.cn/s/739482783865","note":"备注"}}
        """.trimIndent()
        val config = UpdateChannelConfig.parse(json)
        assertEquals("https://pan.quark.cn/s/739482783865", config.quarkUrl)
        assertEquals("备注", config.quarkNote)
        assertTrue(config.quarkConfigured)
    }

    @Test
    fun emptyOrBrokenConfigMeansNotPublishedNotCrash() {
        for (raw in listOf(null, "", "   ", "not json at all", """{"quark":{}}""", """{"quark":{"url":""}}""")) {
            val config = UpdateChannelConfig.parse(raw)
            assertNull(config.quarkUrl)
            assertFalse(config.quarkConfigured)
        }
    }

    @Test
    fun nonHttpsConfiguredLinkIsTreatedAsNotConfigured() {
        val config = UpdateChannelConfig.parse("""{"quark":{"url":"http://pan.quark.cn/s/1"}}""")
        assertFalse(config.quarkConfigured)
    }
}

/** Debug vs release install identity: the sentence that stops a wasted 20 MB download. */
class AppInstallInfoTest {

    @Test
    fun debugBuildIsASeparateApp() {
        val info = AppInstallInfo(
            versionName = "0.7.6",
            versionCode = 16,
            applicationId = "com.glasssign.app.debug",
            isDebug = true,
            releaseApplicationId = AppInstallInfo.releaseIdFor("com.glasssign.app.debug"),
        )
        assertEquals("com.glasssign.app", info.releaseApplicationId)
        assertTrue(info.separateFromRelease)
    }

    @Test
    fun releaseBuildUpgradesInPlace() {
        val info = AppInstallInfo(
            versionName = "0.7.6",
            versionCode = 16,
            applicationId = "com.glasssign.app",
            isDebug = false,
            releaseApplicationId = AppInstallInfo.releaseIdFor("com.glasssign.app"),
        )
        assertFalse(info.separateFromRelease)
    }

    @Test
    fun debugSuffixIsStrippedFromTheVersionName() {
        assertEquals("0.7.6", UpdateBuildInfo.stripDebug("0.7.6-debug"))
        assertEquals("0.7.6", UpdateBuildInfo.stripDebug("0.7.6"))
        // Only the exact trailing marker is removed, never text in the middle of a version.
        assertEquals("0.7.6-debug-tools", UpdateBuildInfo.stripDebug("0.7.6-debug-tools"))
    }
}
