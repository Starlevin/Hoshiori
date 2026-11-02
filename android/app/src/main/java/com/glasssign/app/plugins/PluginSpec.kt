package com.glasssign.app.plugins

/**
 * Every hard-coded fact about the one optional plugin 星栞 knows about: 领签 LingQian.
 *
 * WHY THESE ARE CONSTANTS AND NOT A REMOTE MANIFEST: the whole point of pinning a file hash inside
 * the app is that the *app build* decides what it is willing to install. A manifest fetched over
 * the network would let whoever serves it change the hash and the download URL in the same breath,
 * which is exactly the "download and execute whatever the server says" model this page refuses.
 * The consequence is stated honestly on screen: shipping a newer LingQian needs a new 星栞 build.
 *
 * Provenance (verified by the repository owner, see task notes):
 *  - release page  : https://github.com/Starlevin/LingQian-Android/releases/tag/v0.1.0-alpha
 *  - download URL  : https://github.com/Starlevin/LingQian-Android/releases/download/v0.1.0-alpha/LingQian-v0.1.0-debug.apk
 *  - size in bytes : 847976
 *  - SHA-256       : 7c7036d2618f20b7ce179fe2cca15664b464252a0fd54de370cb5a8b8464d787
 *  - package name  : com.qinghan.aicheckin
 *  - versionCode   : 1
 *  - versionName   : v0.1.0
 *  - signing cert  : SHA-256 of the APK's signing certificate (apksigner output)
 *                    d001e1da5be239c3fdcee2aa3beb937c0da90cc1aba6e522cde9e7fa237cdf85
 *
 * HONESTY CONSTRAINTS this object exists to keep visible to the UI layer:
 *  - 领签 is an INDEPENDENT app with its own accounts. 星栞 shares no credential, no sign-in, no
 *    schedule and no database row with it.
 *  - 星栞 cannot start, stop or schedule anything inside 领签. Nothing here is an IPC submodule.
 *  - The experimental status is the upstream README's own statement (real sign-in points unverified).
 *  - No LingQian source code is copied into this repository; only these public identifiers are.
 */
object PluginSpec {

    const val ID = "lingqian"

    /** Title in this app. */
    const val TITLE = "领签 LingQian"

    /** The independent app's own package name. */
    const val PACKAGE_NAME = "com.qinghan.aicheckin"

    /** Public release page shown to the user; opening it is a plain browser navigation. */
    const val SOURCE_URL = "https://github.com/Starlevin/LingQian-Android/releases/tag/v0.1.0-alpha"

    /** The ONLY download address this page will ever request. */
    const val DOWNLOAD_URL =
        "https://github.com/Starlevin/LingQian-Android/releases/download/v0.1.0-alpha/LingQian-v0.1.0-debug.apk"

    /** Only these host/scheme pairs may serve the artifact (checked before the request and after). */
    const val DOWNLOAD_HOST = "github.com"
    const val DOWNLOAD_SCHEME = "https"
    const val DOWNLOAD_PORT = 443

    /** Pinned byte length; a single byte more or less is a failure, not a warning. */
    const val SIZE_BYTES = 847_976L

    /** Pinned SHA-256 of the whole APK, lower-case hex, no separators. */
    const val SHA256 = "7c7036d2618f20b7ce179fe2cca15664b464252a0fd54de370cb5a8b8464d787"

    /** Pinned package manifest facts of the artifact. */
    const val EXPECTED_PACKAGE = "com.qinghan.aicheckin"
    const val EXPECTED_VERSION_CODE = 1L
    const val EXPECTED_VERSION_NAME = "0.1.0"
    val DOWNLOAD_CDN_HOSTS = setOf("objects.githubusercontent.com", "release-assets.githubusercontent.com", "github-releases.githubusercontent.com")

    /** SHA-256 of the signing certificate (not of the signature block); lower-case hex. */
    const val SIGNING_CERT_SHA256 = "d001e1da5be239c3fdcee2aa3beb937c0da90cc1aba6e522cde9e7fa237cdf85"

    /** What the user is shown next to the download button. Deliberately not "850 KB". */
    const val SIZE_LABEL = "约 0.8 MB"

    /** Shown everywhere the plugin is described; never softened. */
    const val EXPERIMENTAL_NOTE = "实验版本：支持 WorkBuddy 国内版与 Qoder CN，真实登录和积分到账仍待验证。"

    /** The sentence that must accompany the enable switch. */
    const val STANDALONE_NOTE =
        "领签是独立第三方应用：不共享星栞账号，不与星栞一键签到联动，其后台任务由领签自己管理。"

    /** The sentence that must accompany disabling. */
    const val DISABLE_NOTE = "停用仅隐藏星栞内的入口按钮，不会停止领签自身的后台任务，也不会卸载它。"

    /** Temporary suffix used while bytes are still arriving. */
    const val PART_SUFFIX = ".part"

    /** Final file name under `cacheDir/plugins/`. */
    const val APK_FILE = "lingqian-v0.1.0.apk"

    /** FileProvider authority suffix; full authority is `${applicationId}.plugins.fileprovider`. */
    const val FILE_PROVIDER_SUFFIX = ".plugins.fileprovider"

    /** SharedPreferences file owned by this page; unrelated to sign plans, accounts and the database. */
    const val PREFS_NAME = "plugins_v1"

    /** Preference key: user's enable choice. Never a claim that anything is running. */
    const val KEY_ENABLED = "lingqian_enabled"

    /** FileProvider subdirectory under the app's cache dir; the only exposed tree. */
    const val CACHE_DIR_NAME = "plugins"

    /** Short digest form for the UI, e.g. "7c7036d2…4d787". */
    fun shortSha(hex: String): String =
        if (hex.length <= 17) hex else hex.take(8) + "…" + hex.takeLast(5)

    /** Bytes as the user sees them, e.g. "828.1 KB". */
    fun readableBytes(bytes: Long): String = when {
        bytes < 0 -> "未知"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
        else -> String.format(java.util.Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0))
    }
}
