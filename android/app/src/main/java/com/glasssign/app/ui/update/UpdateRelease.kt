package com.glasssign.app.ui.update

/**
 * One `application/vnd.android.package-archive` asset of a GitHub release, reduced to the facts the
 * card is allowed to show.
 *
 * [sha256] comes from GitHub's own `digest` field (`sha256:<64 hex>`), NOT a value this app computed:
 * nothing here downloads the APK, so the card labels it as the publisher's claim. It is shown because
 * it lets a user check the file after their browser saves it, and refused when malformed because a
 * digest that cannot be compared is worse than no digest.
 */
data class UpdateAsset(
    val name: String,
    val sizeBytes: Long,
    val browserDownloadUrl: String,
    val sha256: String?,
)

/** A GitHub release after parsing; [version] is null when the tag is not `X.Y.Z`. */
data class UpdateRelease(
    val tag: String,
    val name: String?,
    val htmlUrl: String,
    val body: String?,
    val publishedAt: String?,
    val draft: Boolean,
    val prerelease: Boolean,
    val assets: List<UpdateAsset>,
) {
    val version: UpdateVersion? get() = UpdateVersion.parse(tag)
}

/**
 * A release that survived [ReleasePolicy]: a real, non-draft, non-pre-release, strictly-newer
 * semantic version with a downloadable APK that carries a usable `sha256` digest.
 *
 * [channel] records WHICH channel accepted it. The card must never hand a preview APK to a release
 * install (different applicationId, different certificate), so the channel travels with the
 * candidate instead of being re-guessed at the install button.
 */
data class UpdateCandidate(
    val version: UpdateVersion,
    val tag: String,
    val name: String,
    val htmlUrl: String,
    val notes: String?,
    val publishedAt: String?,
    val apk: UpdateAsset,
    val channel: UpdateReleaseChannel = UpdateReleaseChannel.OFFICIAL,
)

/** Why the "本次检查没有可用更新" answer was given. Every reason is shown to the user verbatim. */
enum class NoUpdateReason {
    /** The channel answered, but nothing is newer than the running build. */
    UP_TO_DATE,

    /** A newer version exists but every release of it is a draft, a pre-release, or malformed. */
    ONLY_UNUSABLE_RELEASES,

    /** A newer version exists but has no APK asset, or none with a comparable sha256 digest. */
    MISSING_APK_OR_DIGEST,

    /** The GitHub answer could not be understood as a release list at all. */
    UNREADABLE_PAYLOAD,
}

/** The single outcome the card renders. [message] is user-facing and never softened. */
sealed class UpdateCheck {
    data class Available(val candidate: UpdateCandidate) : UpdateCheck()

    data class UpToDate(val current: String, val reason: NoUpdateReason, val detail: String) : UpdateCheck()

    /**
     * The channel could not be trusted or reached. [kind] drives the icon, [message] the sentence.
     * [retryable] only tells the UI whether offering 「重试」 makes sense; nothing is retried silently.
     */
    data class Failed(val kind: UpdateFailure, val message: String) : UpdateCheck()
}

enum class UpdateFailure {
    /** No usable network (airplane mode, no route, DNS dead). */
    OFFLINE,

    /** Connect/read timeout. */
    TIMEOUT,

    /** GitHub answered 403/429 — the anonymous rate limit. */
    RATE_LIMITED,

    /** GitHub answered 404 for the repository/release endpoint: nothing is published there yet. */
    NOT_PUBLISHED,

    /** Any other non-2xx answer. */
    HTTP,

    /** TLS/host/certificate problem. */
    TLS,

    /** The 夸克 launch could not be resolved on this device (no browser). */
    NO_BROWSER,

    /** The 夸克 channel is not configured yet — the card says so instead of inventing a link. */
    NOT_CONFIGURED,
}

/**
 * 决定「这是不是一个可用的升级」的纯函数，按通道给出不同答案：
 *
 * - 草稿永远不算。Tag 必须是严格语义版本。
 * - 版本必须严格高于当前安装版（含预览序号比较），所以重发的旧版本不会触发降级提示。
 * - APK 必须是这个通道能装的文件：`*-debug.apk`、非 `.apk` 一律拒绝；正式通道只认不带 `preview`
 *   的文件名，预览通道只认带 `preview` 的文件名。
 * - 资源必须带格式正确的 SHA-256 摘要，否则报「资源不完整」而不是悄悄提供。
 * - 预览通道额外接受「更高的最终正式版」：预览用户可以前进到正式版，反之不行。
 */
object ReleasePolicy {

    /** The only URL prefix a download button may use. Checked again by [UpdateUrls]. */
    const val RELEASE_DOWNLOAD_PREFIX = "https://github.com/"

    /**
     * Picks the best usable release. Returns either a candidate or a reason, never a "best effort"
     * guess: sorting happens after filtering, so an unusable newer release cannot mask an older
     * usable one, and a usable release cannot be hidden by an unusable newer one.
     */
    fun select(
        currentVersion: String,
        releases: List<UpdateRelease>,
        channel: UpdateReleaseChannel = UpdateReleaseChannel.OFFICIAL,
    ): Pair<UpdateCandidate?, NoUpdateReason> {
        // The INSTALLED side may be a debug or preview build; the published side stays strict below.
        val current = UpdateVersion.parseInstalled(currentVersion)
            ?: return null to NoUpdateReason.UNREADABLE_PAYLOAD

        val parsed = releases.mapNotNull { release ->
            val v = release.version ?: return@mapNotNull null
            if (release.draft) return@mapNotNull null
            if (!channelAcceptsRelease(v, release.prerelease, channel)) return@mapNotNull null
            release to v
        }
        // Strictly newer only. Equal or older releases are not "updates", not even with new notes.
        val newer = parsed.filter { (_, v) -> v > current }
        if (newer.isEmpty()) return null to NoUpdateReason.UP_TO_DATE

        // Prefer the newest usable release. If the newest newer release is unusable we deliberately do
        // NOT fall back to an older one: offering 0.8 while 0.8.1 is known to exist would install a
        // version the user must then replace. The card says "有新版本但资源不完整" instead.
        val newest = newer.maxByOrNull { it.second }!!
        val apk = newest.first.assets
            .filter { assetAcceptableFor(it.name, newest.second, channel) }
            .firstOrNull { ReleaseDigest.isWellFormed(it.sha256) }
            ?: return null to NoUpdateReason.MISSING_APK_OR_DIGEST

        return UpdateCandidate(
            version = newest.second,
            tag = newest.first.tag,
            name = newest.first.name?.takeIf { it.isNotBlank() } ?: newest.first.tag,
            htmlUrl = newest.first.htmlUrl,
            notes = newest.first.body?.takeIf { it.isNotBlank() },
            publishedAt = newest.first.publishedAt,
            apk = apk,
            channel = channel,
        ) to NoUpdateReason.UP_TO_DATE
    }

    /**
     * 这个 release 属于该通道吗？
     *
     * 正式通道：只认非 prerelease 的最终版本——beta 不是升级。
     * 预览通道：认预览版本（`0.8.0-preview.2`，带不带 prerelease 标记都算，GitHub 上这两者常不一致），
     * 也认最终正式版本（预览用户可以前进到正式版）；`0.8.0-beta.1` 这类别的预发布仍然拒绝。
     */
    fun channelAcceptsRelease(version: UpdateVersion, prerelease: Boolean, channel: UpdateReleaseChannel): Boolean =
        when (channel) {
            UpdateReleaseChannel.OFFICIAL -> !prerelease && version.isFinal
            UpdateReleaseChannel.PREVIEW ->
                if (version.isFinal) true else !prerelease || version.isPreview
        }

    /**
     * 预览通道既可以接受带 `preview` 的资源，也可以接受最终正式版的普通资源。
     *
     * 预览安装前进到正式版时，那个 release 的资源名不带 `preview`，但它是这条通道合法的终点。
     */
    private fun assetAcceptableFor(assetName: String, release: UpdateVersion, channel: UpdateReleaseChannel): Boolean =
        when (channel) {
            UpdateReleaseChannel.OFFICIAL -> isReleaseApk(assetName) && !isPreviewAssetName(assetName)
            UpdateReleaseChannel.PREVIEW -> isReleaseApk(assetName)
        }

    /**
     * `星栞-0.7.6.apk` / `Hoshiori-v0.7.6.apk` are fine; `hoshiori-debug.apk`, `*-debug.apk`,
     * `*.aab`, `*.zip` and `*.apk.sha256` are not. Case-insensitive because release uploads keep
     * whatever the uploader typed.
     *
     * A filename containing `preview` marks a preview-channel build. Note that a PREVIEW BUILD of this
     * app also carries `-debug` in `versionName`, but the release asset must not: the word "debug" in an
     * asset name means the Android debug build type, which uses another applicationId.
     */
    fun isReleaseApk(name: String): Boolean {
        val n = name.trim().lowercase()
        if (!n.endsWith(".apk")) return false
        if (n.contains("debug")) return false
        if (n.contains("unsigned")) return false
        return true
    }

    /** True when the file name itself announces a preview-channel build. */
    fun isPreviewAssetName(name: String): Boolean =
        name.trim().lowercase().contains("preview")

    /**
     * The name check for ONE channel: a preview asset is only acceptable on the preview channel and a
     * plain asset only on the official channel. The reverse pairing is refused rather than "reported
     * as incomplete", because it is a real file for a different Android application.
     */
    fun isReleaseApkForChannel(name: String, channel: UpdateReleaseChannel): Boolean =
        isReleaseApk(name) && (isPreviewAssetName(name) == (channel == UpdateReleaseChannel.PREVIEW))
}
