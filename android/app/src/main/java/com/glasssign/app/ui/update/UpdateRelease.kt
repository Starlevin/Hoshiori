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
 */
data class UpdateCandidate(
    val version: UpdateVersion,
    val tag: String,
    val name: String,
    val htmlUrl: String,
    val notes: String?,
    val publishedAt: String?,
    val apk: UpdateAsset,
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
 * Everything that decides "is this release the official upgrade?" in one pure function.
 *
 * THE RULES, EACH ONE A REFUSAL RATHER THAN A HINT:
 *  1. `draft == true` — never shown: it is an unpublished draft and half-finished by definition.
 *  2. `prerelease == true` — never shown on the official channel: a beta is not an upgrade.
 *  3. Tag must be a strict semantic version. `nightly`, `v0.8`, `0.8.0-debug` are ignored.
 *  4. Version must be STRICTLY newer than the running build, compared by semver precedence, so a
 *     re-published older release can never trigger a downgrade prompt.
 *  5. The APK must be a real asset. A `*-debug.apk` is refused explicitly (it carries a different
 *     applicationId and cannot share data with a release install); so is a non-`.apk` file.
 *  6. The asset must carry a well-formed SHA-256 digest (GitHub's `digest` field, either
 *     `sha256:<hex>` or bare hex), because the card promises a verifiable download. Without it the
 *     release is reported as unusable instead of being offered silently.
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
    ): Pair<UpdateCandidate?, NoUpdateReason> {
        // A debug build's own `0.7.6-debug` is accepted here: that is the INSTALLED side, and refusing
        // it would make the feature unable to check anything in a debug build. The published side stays
        // strict — see `UpdateVersion.parse`, which rejects a `-debug` tag outright.
        val current = UpdateVersion.parseInstalled(currentVersion)
            ?: return null to NoUpdateReason.UNREADABLE_PAYLOAD

        // 1+3: strict tag, not draft, not prerelease.
        val parsed = releases.mapNotNull { release ->
            val v = release.version ?: return@mapNotNull null
            if (release.draft || release.prerelease || !v.isFinal) return@mapNotNull null
            release to v
        }
        // 4: strictly newer only. Equal or older releases are not "updates", not even with new notes.
        val newer = parsed.filter { (_, v) -> v > current }
        if (newer.isEmpty()) return null to NoUpdateReason.UP_TO_DATE

        // Prefer the newest usable release. If the newest newer release is unusable we deliberately do
        // NOT fall back to an older one: offering 0.8 while 0.8.1 is known to exist would install a
        // version the user must then replace. The card says "有新版本但资源不完整" instead.
        val newest = newer.maxByOrNull { it.second }!!
        val apk = newest.first.assets
            .filter { isReleaseApk(it.name) }
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
        ) to NoUpdateReason.UP_TO_DATE
    }

    /**
     * `星栞-0.7.6.apk` / `Hoshiori-v0.7.6.apk` are fine; `hoshiori-debug.apk`, `*-debug.apk`,
     * `*.aab`, `*.zip` and `*.apk.sha256` are not. Case-insensitive because release uploads keep
     * whatever the uploader typed.
     */
    fun isReleaseApk(name: String): Boolean {
        val n = name.trim().lowercase()
        if (!n.endsWith(".apk")) return false
        if (n.contains("debug")) return false
        if (n.contains("unsigned")) return false
        return true
    }
}
