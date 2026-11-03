package com.glasssign.app.ui.update

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * GitHub's release JSON → [UpdateRelease]. Strict about shape, tolerant about optional fields.
 *
 * Deliberately free of any transport or platform import: this is the layer where the
 * "which release is official?" rules are fed their data, so it must be runnable in a plain JVM test
 * against literal payloads (see `UpdateReleaseTest`).
 *
 * FIELD NAMES ARE GITHUB'S OWN. They were verified against a real published release, because a wrong
 * key fails silently: the parse succeeds, the field comes back null, and the release is then refused
 * as "incomplete" forever. That is exactly the bug that `sha256_digest` used to cause — the real field
 * is **`digest`** (`"digest": "sha256:<64 hex>"`).
 */
object ReleaseJson {

    /**
     * Parses `GET /repos/{owner}/{repo}/releases` (an array) or `/releases/latest` (one object).
     * Throws [IOException] when the payload is not one of those two shapes.
     */
    fun parseReleases(raw: String): List<UpdateRelease> {
        val text = raw.trim()
        if (text.isEmpty()) return emptyList()
        return try {
            if (text.startsWith("[")) {
                val arr = JSONArray(text)
                (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::parseOne) }
            } else if (text.startsWith("{")) {
                listOf(parseOne(JSONObject(text)))
            } else {
                throw IOException("发布信息不是有效的 JSON")
            }
        } catch (e: org.json.JSONException) {
            throw IOException("发布信息格式无法解析", e)
        }
    }

    private fun parseOne(o: JSONObject): UpdateRelease {
        val assetsJson = o.optJSONArray("assets")
        val assets = if (assetsJson == null) emptyList() else (0 until assetsJson.length()).mapNotNull { i ->
            assetsJson.optJSONObject(i)?.let { a ->
                UpdateAsset(
                    // GitHub's own field name: browser_download_url
                    name = a.optString("name", ""),
                    sizeBytes = a.optLong("size", 0L),
                    browserDownloadUrl = a.optString("browser_download_url", ""),
                    sha256 = digestOf(a),
                )
            }
        }
        return UpdateRelease(
            tag = o.optString("tag_name", ""),
            name = o.optString("name", "").takeIf { it.isNotBlank() },
            htmlUrl = o.optString("html_url", ""),
            body = o.optString("body", "").takeIf { it.isNotBlank() },
            publishedAt = o.optString("published_at", "").takeIf { it.isNotBlank() },
            // Only a literal boolean true counts as true; a missing field is NOT a draft.
            draft = o.optBoolean("draft", false),
            prerelease = o.optBoolean("prerelease", false),
            assets = assets,
        )
    }

    /**
     * The asset's `digest`, which GitHub documents as `"digest": "sha256:<64 hex>"`.
     *
     * `sha256_digest` is read afterwards purely as a compatibility fallback for an older payload shape;
     * it is NOT the API's field and must never be the only key consulted.
     */
    private fun digestOf(asset: JSONObject): String? =
        asset.optString(FIELD_DIGEST, "").takeIf { it.isNotBlank() }
            ?: asset.optString(FIELD_LEGACY_DIGEST, "").takeIf { it.isNotBlank() }

    /** GitHub's field for an asset's hash. Pinned as a constant so a typo is a compile error. */
    const val FIELD_DIGEST = "digest"

    /** Compatibility only; not part of the public API. */
    const val FIELD_LEGACY_DIGEST = "sha256_digest"
}
