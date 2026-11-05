package com.glasssign.app.ui.update

/**
 * 严格语义版本。用严格解析是因为比较结果会变成「有没有新版本」的结论：宽松解析会把 `0.7.10`
 * 判成小于 `0.7.9`，把 `1.0.0-rc1` 当 `1.0.0`。解析不出来的版本一律不可用，绝不猜。
 *
 * 排序按 semver 2.0.0 优先级：带预发布后缀的排在同名正式版之前（`0.8.0-beta` < `0.8.0`），
 * 数字标识按数值比较（`preview.10` > `preview.9`）。
 */
data class UpdateVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val preRelease: List<String> = emptyList(),
) : Comparable<UpdateVersion> {

    /** True when this is `X.Y.Z` with no `-...` suffix. */
    val isFinal: Boolean get() = preRelease.isEmpty()

    /** 预览版本：`0.7.9-preview.3`。 */
    val isPreview: Boolean get() = preRelease.firstOrNull()?.lowercase() == PREVIEW_ID

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
         * The preview channel's identifier in a version name or tag (`preview`, `preview.3`).
         * `0.7.9-preview.3` is the third preview build of 0.7.9.
         */
        const val PREVIEW_ID = "preview"

        /** True when the name marks a preview build (`0.7.9-preview.3`, `v0.8.0-preview`). */
        fun isPreviewVersionName(name: String?): Boolean {
            val text = name?.trim()?.lowercase() ?: return false
            return text.contains("-$PREVIEW_ID") || text.contains(".$PREVIEW_ID")
        }

        /**
         * Parses a preview build's version name or tag into a comparable [UpdateVersion].
         *
         * `0.7.9-preview.3` → `UpdateVersion(0, 7, 9, ["preview", "3"])`. The numeric tail is the
         * preview build number, so `-preview.10` sorts above `-preview.9` (numeric, not lexical), and
         * every preview sorts below the final `0.7.9` — which is exactly why a preview install can
         * move forward to the final release of the same version.
         *
         * Returns `null` for anything that is not `X.Y.Z-preview[.N]`, including a debug build.
         */
        fun parsePreview(raw: String?): UpdateVersion? {
            val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val m = STRICT.matchEntire(text) ?: return null
            val (a, b, c) = m.destructured
            val tail = m.groupValues[4]
            if (tail.isEmpty()) return null
            val ids = tail.split('.')
            if (ids.isEmpty() || ids.first().lowercase() != PREVIEW_ID) return null
            if (ids.drop(1).any { it.isEmpty() || it.toIntOrNull() == null }) return null
            if (ids.any { it.lowercase() in BUILD_FLAVOURS }) return null
            return UpdateVersion(a.toInt(), b.toInt(), c.toInt(), listOf(PREVIEW_ID) + ids.drop(1))
        }

        /**
         * Parses a semantic version, tolerating the leading `v` GitHub uses in tags (`v0.7.6`).
         *
         * Returns `null` for anything that is not a strict semantic version, including `0.7.6-debug`
         * (this app's own debug `versionName`), `0.7`, `latest` and `0.7.6+build`. A preview tag parses
         * (with its tail) so the preview channel can compare it; whether it is acceptable is decided by
         * [ReleasePolicy], not here.
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
         * Accepts: a final `X.Y.Z`; a final `X.Y.Z` plus build flavour (`-debug`); and a PREVIEW build
         * (`0.7.9-preview.3`). A preview is compared by its preview number, so `-preview.10` is newer
         * than `-preview.9`, and any preview is older than the same final version — a preview install can
         * therefore move forward to `0.7.9` itself.
         *
         * Any other pre-release is refused: `0.7.6-whatever`, `0.7.6-rc1` are not identifiers a shipped
         * install may carry, and accepting them would shift the comparison baseline.
         */
        fun parseInstalled(versionName: String): UpdateVersion? {
            val text = versionName.trim()
            if (text.isEmpty()) return null
            // Shape 1: a plain final version.
            FINAL_ONLY.matchEntire(text)?.let { m ->
                val (a, b, c) = m.destructured
                return UpdateVersion(a.toInt(), b.toInt(), c.toInt())
            }
            // Shape 2/3: final version + a tail.
            val dash = text.indexOf('-')
            if (dash <= 0) return null
            val head = text.substring(0, dash)
            val tail = text.substring(dash + 1).split('.')
            if (tail.isEmpty() || tail.any { it.isEmpty() }) return null
            val m = FINAL_ONLY.matchEntire(head) ?: return null
            val (a, b, c) = m.destructured
            // Build flavour: `0.7.6-debug`, `0.7.6-debug.signed` (`0.7.6-beta-debug` is not one).
            if (tail.all { it.lowercase() in BUILD_FLAVOURS }) return UpdateVersion(a.toInt(), b.toInt(), c.toInt())
            // Preview: `0.7.9-preview`, `0.7.9-preview.3` — compared by preview number.
            val lowered = tail.map { it.lowercase() }
            if (lowered.firstOrNull() == PREVIEW_ID && lowered.drop(1).all { it.toIntOrNull() != null }) {
                return UpdateVersion(a.toInt(), b.toInt(), c.toInt(), listOf(PREVIEW_ID) + lowered.drop(1))
            }
            return null
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
