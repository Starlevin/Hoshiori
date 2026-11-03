package com.glasssign.app.ui.update

/**
 * Strict semantic versions, and nothing else.
 *
 * WHY "STRICT" IS THE WHOLE POINT: this card decides whether to tell a user that a newer 星栞 exists.
 * A loose parser (`"0.7.10".substringAfterLast('.') > "0.7.9"` style) happily compares `0.7.10` as
 * smaller than `0.7.9`, accepts `1.0.0-rc1` as `1.0.0`, and turns a typo into a confident wrong
 * answer. So a tag that is not exactly `MAJOR.MINOR.PATCH` (optionally `-pre.release` build) is
 * refused, and anything refused can never win a comparison: an unparseable candidate is not
 * "probably newer", it is unusable and the card says so.
 *
 * ORDERING: semver 2.0.0 precedence. A version WITH a pre-release tag sorts BEFORE the same version
 * without one (`0.8.0-beta` < `0.8.0`); the GitHub/夸克 release channel only ever publishes plain
 * `X.Y.Z` tags, and the pre-release form exists here so the comparison is honest rather than
 * silently treating `0.8.0-beta` as final.
 */
data class UpdateVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val preRelease: List<String> = emptyList(),
) : Comparable<UpdateVersion> {

    /** True when this is `X.Y.Z` with no `-...` suffix. */
    val isFinal: Boolean get() = preRelease.isEmpty()

    override fun compareTo(other: UpdateVersion): Int {
        compareValues(major, other.major).let { if (it != 0) return it }
        compareValues(minor, other.minor).let { if (it != 0) return it }
        compareValues(patch, other.patch).let { if (it != 0) return it }
        // Pre-release has LOWER precedence than the corresponding release.
        if (preRelease.isEmpty() && other.preRelease.isEmpty()) return 0
        if (preRelease.isEmpty()) return 1
        if (other.preRelease.isEmpty()) return -1
        val n = minOf(preRelease.size, other.preRelease.size)
        for (i in 0 until n) {
            val a = preRelease[i]
            val b = other.preRelease[i]
            val an = a.toIntOrNull()
            val bn = b.toIntOrNull()
            if (an != null && bn != null) {
                compareValues(an, bn).let { if (it != 0) return it }
            } else if (an != null) {
                return -1 // numeric identifiers rank below alphanumeric ones
            } else if (bn != null) {
                return 1
            } else {
                val c = a.compareTo(b)
                if (c != 0) return c
            }
        }
        return compareValues(preRelease.size, other.preRelease.size)
    }

    /** `X.Y.Z` / `X.Y.Z-tag` with no `v`, no build metadata. Safe to show to the user. */
    override fun toString(): String =
        "$major.$minor.$patch" + if (preRelease.isEmpty()) "" else "-" + preRelease.joinToString(".")

    companion object {
        /**
         * Strict `X.Y.Z` with an optional semver pre-release tail.
         *
         * The pre-release identifiers are held to the semver 2.0.0 alphabet BUT the toolchain suffixes
         * this build actually uses are excluded: `-debug`, `-debug.signed`, `-alpha.debug` and similar
         * are build flavours, not releases, so they must not parse. Accepting them would let this app's
         * own `BuildConfig.VERSION_NAME` (`0.7.6-debug`) masquerade as a comparable release version.
         */
        private val STRICT = Regex(
            """^[vV]?(\d{1,5})\.(\d{1,5})\.(\d{1,5})(?:-([0-9A-Za-z][0-9A-Za-z.\-]{0,63}))?$"""
        )

        /** Identifiers that mark a local build flavour rather than a published release. */
        private val BUILD_FLAVOURS = setOf("debug", "signed", "unsigned", "benchmark", "profile", "release")

        /**
         * Parses a semantic version, tolerating the leading `v` GitHub uses in tags (`v0.7.6`).
         *
         * Returns `null` for anything that is not a strict semantic version, including `0.7.6-debug`
         * (this app's own debug `versionName`), `0.7`, `latest` and `0.7.6+build`.
         */
        fun parse(raw: String?): UpdateVersion? {
            val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val m = STRICT.matchEntire(text) ?: return null
            val (a, b, c) = m.destructured
            val tail = m.groupValues[4]
            val pre = if (tail.isEmpty()) emptyList() else tail.split('.')
            // A trailing dot or empty identifier ("0.8.0-beta.") is not a version.
            if (pre.any { it.isEmpty() }) return null
            // A build flavour is not a release: `0.7.6-debug` must never compare as a version.
            if (pre.any { it.lowercase() in BUILD_FLAVOURS }) return null
            return UpdateVersion(a.toInt(), b.toInt(), c.toInt(), pre)
        }

        /** `X.Y.Z` with no pre-release part at all — the only shape a running build may report. */
        private val FINAL_ONLY = Regex("""^[vV]?(\d{1,5})\.(\d{1,5})\.(\d{1,5})$""")

        /**
         * The version to COMPARE for a running build.
         *
         * ACCEPTS EXACTLY TWO SHAPES, and nothing else:
         *  1. a plain final `X.Y.Z` (optionally `v`-prefixed), and
         *  2. a final `X.Y.Z` followed by a real build flavour (`-debug`, `-debug.signed`), which is
         *     what `BuildConfig.VERSION_NAME` carries in a debug build.
         *
         * ANY OTHER PRE-RELEASE IS REFUSED. This is deliberately stricter than [parse]: a running
         * install is a shipped artifact, so `0.7.6-whatever`, `0.7.6-rc1` and `0.7.6-beta` are not
         * valid identifiers for it. Accepting them would let an arbitrary suffix smuggle in a
         * comparable version, and — because this is the baseline of the update comparison — silently
         * shift whether a release counts as newer.
         */
        fun parseInstalled(versionName: String): UpdateVersion? {
            val text = versionName.trim()
            if (text.isEmpty()) return null
            // Shape 1: a plain final version.
            FINAL_ONLY.matchEntire(text)?.let { m ->
                val (a, b, c) = m.destructured
                return UpdateVersion(a.toInt(), b.toInt(), c.toInt())
            }
            // Shape 2: final version + a recognised build flavour only.
            val dash = text.indexOf('-')
            if (dash <= 0) return null
            val head = text.substring(0, dash)
            val flavour = text.substring(dash + 1).split('.')
            if (flavour.isEmpty() || flavour.any { it.isEmpty() }) return null
            if (flavour.any { it.lowercase() !in BUILD_FLAVOURS }) return null
            // The head must itself be final; `0.7.6-beta-debug` is not an installed version.
            val m = FINAL_ONLY.matchEntire(head) ?: return null
            val (a, b, c) = m.destructured
            return UpdateVersion(a.toInt(), b.toInt(), c.toInt())
        }
    }
}

/**
 * The comparison the card is allowed to act on. Kept as a pure function so the JVM test can pin the
 * "never downgrade, never nag, never lie" rules without a network or a context.
 */
object UpdateVersionPolicy {

    /**
     * What to say about [candidate] relative to [current].
     *
     * @return `+1` when the candidate is strictly newer, `0` when equal, `-1` when older.
     * @throws IllegalArgumentException when either side is not a strict semantic version; a
     *          malformed number must be reported, never silently treated as "newest".
     */
    fun compare(current: String, candidate: String): Int {
        // The installed side tolerates BuildConfig's `-debug` flavour; the published side does not.
        val now = UpdateVersion.parseInstalled(current)
        requireNotNull(now) { "当前版本不是严格语义版本：$current" }
        val next = UpdateVersion.parse(candidate)
        requireNotNull(next) { "发布版本不是严格语义版本：$candidate" }
        return next.compareTo(now).coerceIn(-1, 1)
    }

    /** True only when [candidate] is strictly newer than [current]. Equal is NOT an update. */
    fun isUpgrade(current: String, candidate: String): Boolean = compare(current, candidate) > 0
}
