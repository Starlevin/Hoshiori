package com.glasssign.app.ui.update

/**
 * The publisher's SHA-256 digest, and how it is shown. Never computed here: nothing is downloaded.
 *
 * TWO SHAPES ARE ACCEPTED, because the API and the release page disagree in form:
 *  - `sha256:<64 hex>` — GitHub's `digest` field (the authoritative one), and
 *  - bare `<64 hex>` — what a maintainer pastes into release notes.
 * Everything else (`md5:…`, a truncated hash, a filename that merely contains "sha256") is refused,
 * so a digest is only ever displayed when it is actually a comparable SHA-256.
 */
object ReleaseDigest {

    private val PREFIXED = Regex("""^sha256:[0-9a-fA-F]{64}$""")
    private val BARE = Regex("""^[0-9a-fA-F]{64}$""")

    /** True for `sha256:<64 hex>` or a bare 64-hex value. `null`/blank/`md5:`/truncated → false. */
    fun isWellFormed(raw: String?): Boolean {
        val text = raw?.trim() ?: return false
        return text.isNotEmpty() && (PREFIXED.matches(text) || BARE.matches(text))
    }

    /** Lower-case 64 hex characters of a well-formed digest; `null` otherwise. */
    fun hex(raw: String?): String? {
        if (!isWellFormed(raw)) return null
        return raw!!.trim().substringAfter(':').lowercase()
    }

    /** `d261…4d7a` for the UI; a wrong-length value is never truncated into looking valid. */
    fun short(raw: String?): String {
        val hex = hex(raw) ?: return "未提供"
        return hex.take(10) + "…" + hex.takeLast(6)
    }
}

/**
 * Every URL the update card will open, and the reasons it refuses the rest.
 *
 * NOTHING IS OPENED UNTIL IT PASSES THIS: the GitHub download URL, the release page and the 夸克
 * permanent link all arrive from a network response or a bundled asset, so each is re-validated at
 * the moment of the click. A card that "opens whatever the server said" is a redirector; this one
 * only opens `https` URLs on an allow-list of hosts, and only the three shapes below.
 */
object UpdateUrls {

    const val GITHUB_API_HOST = "api.github.com"
    const val GITHUB_WEB_HOST = "github.com"
    const val RELEASES_PATH = "/Starlevin/Hoshiori/releases"

    /** The official channel endpoint. One constant, so a test can pin it. */
    const val LATEST_RELEASE_URL = "https://$GITHUB_API_HOST/repos/Starlevin/Hoshiori/releases/latest"

    private val HTTPS = Regex("""^https://([A-Za-z0-9.\-]+)(?::(443))?(/[^\s]*)?$""")

    /** Parses `host` + `path` of an `https` URL; `null` for http, other schemes or garbage. */
    fun parse(url: String?): Pair<String, String>? {
        val text = url?.trim() ?: return null
        if (text.length > 2048) return null
        val m = HTTPS.matchEntire(text) ?: return null
        val host = m.groupValues[1].lowercase()
        if (host.isEmpty() || !host.contains('.')) return null
        val path = m.groupValues[3].ifEmpty { "/" }
        return host to path
    }

    /**
     * The browser-download URL of a release asset.
     *
     * Expected shape (and nothing else):
     * `https://github.com/Starlevin/Hoshiori/releases/download/<tag>/<file>.apk`
     * A `githubusercontent` CDN link is refused on purpose: that is the address the *API* would give a
     * client that downloads the file itself. This app hands the job to the browser, so it must offer
     * the stable release-page address instead.
     */
    fun isReleaseApkUrl(url: String?): Boolean {
        val (host, path) = parse(url) ?: return false
        if (host != GITHUB_WEB_HOST) return false
        if (!path.startsWith(RELEASES_PATH + "/download/")) return false
        val name = path.substringAfterLast('/')
        if (name.isEmpty()) return false
        return ReleasePolicy.isReleaseApk(name)
    }

    /** `https://github.com/Starlevin/Hoshiori/releases/tag/<tag>` */
    fun isReleasePageUrl(url: String?): Boolean {
        val (host, path) = parse(url) ?: return false
        if (host != GITHUB_WEB_HOST) return false
        if (!path.startsWith(RELEASES_PATH + "/tag/")) return false
        val tag = path.removePrefix(RELEASES_PATH + "/tag/")
        return tag.isNotEmpty() && !tag.contains('/') && UpdateVersion.parse(tag) != null
    }

    /** The only host a 夸克 share link may point at. */
    const val QUARK_HOST = "pan.quark.cn"

    /** 夸克's permanent share links live under this path prefix. */
    const val QUARK_PATH_PREFIX = "/s/"

    /**
     * The 夸克 permanent directory link from `assets/update-channel.json`.
     *
     * PINNED, not merely "some https URL": the card hands this to the system browser, so an arbitrary
     * host would let a bad configuration send users to any site that reads like a download page. The
     * accepted shape is exactly
     * `https://pan.quark.cn/s/<non-empty share id>`.
     *
     * An earlier revision accepted any `https` host with a dot, which meant
     * `https://evil.example/s/1` passed as a 夸克 link — the same class of mistake as trusting a
     * redirect. The userinfo check below still stands, because `https://pan.quark.cn@evil.example/…`
     * has host `evil.example` and is already rejected by the host test.
     */
    fun isQuarkLink(url: String?): Boolean {
        val text = url?.trim() ?: return false
        // Never accept a credential-bearing URL, however the host parses.
        if (text.contains('@')) return false
        val (host, path) = parse(text) ?: return false
        if (host != QUARK_HOST) return false
        if (!path.startsWith(QUARK_PATH_PREFIX)) return false
        // The share id must be non-empty; `/s/` alone is not a directory link.
        val shareId = path.removePrefix(QUARK_PATH_PREFIX)
        if (shareId.isEmpty()) return false
        // No further path segments or query noise: a share id is one segment.
        if (shareId.contains('/')) return false
        return true
    }
}
