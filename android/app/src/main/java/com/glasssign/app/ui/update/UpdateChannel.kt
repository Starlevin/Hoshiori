package com.glasssign.app.ui.update

import org.json.JSONObject

/**
 * The two delivery channels, and the one thing that distinguishes them.
 *
 * GitHub and 夸克 are NOT two sources of version information. They are two doors to the same release:
 * the version, tag, notes and digest always come from the official GitHub metadata
 * ([UpdateReleaseService]); the 夸克 channel only replaces *where the browser goes*, using a permanent
 * directory link the repository owner maintains in `assets/update-channel.json`.
 *
 * A SECOND SOURCE WOULD BE A LIE THE CARD CANNOT DETECT: if 夸克 were allowed to name its own version,
 * the card could show "0.8.2" from one channel and "0.8.1" from the other, and the sha256 printed for
 * the file would belong to a release the other channel knows nothing about. So 夸克 never contributes
 * metadata, only a URL.
 */
enum class UpdateChannel(val label: String, val description: String) {
    GITHUB(
        label = "GitHub",
        description = "备用发布页，可在浏览器中手动下载安装包。",
    ),
    QUARK(
        label = "夸克",
        description = "浏览器打开夸克网盘的永久目录，手动取回同一版本的 APK。",
    ),
}

/**
 * Parsed `assets/update-channel.json`.
 *
 * [quarkUrl] is `null` when the owner has not published the directory link yet. That is a normal,
 * expected state — NOT an error to hide and NOT something to paper over with a placeholder URL. The
 * card then says 「夸克通道暂未发布」 and offers no button, because a fabricated link would send a user
 * to a stranger's share page believing it is 星栞's.
 */
data class UpdateChannelConfig(
    val quarkUrl: String?,
    /** Free-form note from the asset, shown verbatim under the 夸克 channel. */
    val quarkNote: String? = null,
) {
    val quarkConfigured: Boolean get() = quarkUrl != null && UpdateUrls.isQuarkLink(quarkUrl)

    companion object {
        /** Name of the bundled, read-only asset. Editing it requires a new build: no remote config. */
        const val ASSET_NAME = "update-channel.json"

        /** Nothing configured. Used when the asset is missing or unreadable. */
        val EMPTY = UpdateChannelConfig(quarkUrl = null, quarkNote = null)

        /**
         * Parses the asset. Unparseable JSON yields [EMPTY] instead of an exception: a broken
         * configuration must degrade to 「暂未发布」, never to a crash on the settings page.
         */
        fun parse(raw: String?): UpdateChannelConfig {
            val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return EMPTY
            val json = runCatching { JSONObject(text) }.getOrNull() ?: return EMPTY
            val quark = json.optJSONObject("quark")
            val url = quark?.optString("url", "")?.trim()?.takeIf { it.isNotEmpty() }
            val note = quark?.optString("note", "")?.trim()?.takeIf { it.isNotEmpty() }
            return UpdateChannelConfig(quarkUrl = url, quarkNote = note)
        }
    }
}
