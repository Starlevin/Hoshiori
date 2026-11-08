package com.glasssign.app.login

/**
 * How the official login session is turned into a local credential.
 *
 * [WEB_SESSION] – the user signs in on the official web page inside our own WebView; the session
 * cookies / web storage entries listed below are read back after login and mapped to the exact
 * fields the provider adapter already consumes. Nothing is scraped from another app, and no
 * password or verification code ever leaves the official page.
 */
enum class LoginMode { WEB_SESSION, IMPORT_ONLY }

/**
 * Some communities only serve their real web app to desktop browsers and answer a phone user
 * agent with an "install our app" landing page, which has no login form at all. [MOBILE] keeps the
 * touch-friendly phone layout (correct for mihoyo's account page); [DESKTOP] is the fallback that
 * makes the others hand out their normal web page, login button included.
 */
enum class UserAgent { MOBILE, DESKTOP }

/**
 * @param startUrl      official login entry opened in the in-app WebView
 * @param allowedHosts  navigation whitelist (bare domain, matches sub-domains too)
 * @param cookieProbe   URL whose cookie jar exposes the session (native CookieManager, includes HttpOnly)
 * @param cookieNames   whitelist of cookie names that may be persisted
 * @param storageKeys   localStorage keys read from the official origin after login
 * @param userAgent     which browser the page should think it is talking to (see [UserAgent])
 * @param note          shown under the platform name in the picker
 */
data class LoginPlatform(
    val id: String,
    val title: String,
    val startUrl: String,
    val allowedHosts: List<String>,
    val cookieProbe: String,
    val cookieNames: List<String>,
    val storageKeys: List<String>,
    val mode: LoginMode,
    val note: String,
    val userAgent: UserAgent = UserAgent.DESKTOP,
    /** True only when the in-app login page has actually been shown to reach a sign-in form. */
    val verified: Boolean = true,
)

object LoginPlatforms {
    private const val MIYOUSHE = "miyoushe"
    private const val SKLAND = "skland"
    private const val TAGGED = "tagged"
    private const val KUROBBS = "kurobbs"

    /**
     * mihoyo account web SDK reads `account_id_v2 | account_id | ltuid_v2 | ltuid`
     * (verified 2026-11-01 against webstatic.mihoyo.com account SDK). The v2 family is preferred;
     * `stoken` / `mid` are kept because the adapter renews an expired cookie_token with them.
     */
    private val MIYOUSHE_COOKIES = listOf(
        "account_id", "account_id_v2", "ltuid", "ltuid_v2",
        "cookie_token", "cookie_token_v2", "ltoken", "ltoken_v2",
        "stoken", "stoken_v2", "mid", "account_mid_v2", "ltmid_v2",
    )

    /**
     * skland web persists its session in localStorage
     * (verified 2026-11-01 against www.skland.com bundles: SK_OAUTH_CRED_KEY / SK_TOKEN_CACHE_KEY /
     * SK_SHUMEI_DEVICE_ID_KEY). Reading the site's own device id keeps the signed context consistent
     * with the cred the server issued.
     */
    private val SKLAND_KEYS = listOf(
        "SK_OAUTH_CRED_KEY", "SK_TOKEN_CACHE_KEY", "SK_SHUMEI_DEVICE_ID_KEY",
    )

    val all: List<LoginPlatform> = listOf(
        LoginPlatform(
            id = MIYOUSHE,
            title = "米游社",
            startUrl = "https://user.mihoyo.com/#/login",
            allowedHosts = listOf("mihoyo.com", "miyoushe.com"),
            cookieProbe = "https://api-takumi.mihoyo.com",
            cookieNames = MIYOUSHE_COOKIES,
            storageKeys = emptyList(),
            mode = LoginMode.WEB_SESSION,
            note = "在官方账号页登录后，本机读取会话并加密保存；原神与崩铁、绝区零请分别绑定两个账号。",
            userAgent = UserAgent.MOBILE,
        ),
        LoginPlatform(
            id = SKLAND,
            title = "森空岛",
            startUrl = "https://www.skland.com/login",
            allowedHosts = listOf("skland.com", "hypergryph.com"),
            cookieProbe = "https://www.skland.com/",
            cookieNames = emptyList(),
            storageKeys = SKLAND_KEYS,
            mode = LoginMode.WEB_SESSION,
            note = "打开后直接弹出鹰角通行证登录框；登录后读取网页登录态，明日方舟与终末地共用同一账号。",
        ),
        LoginPlatform(
            id = TAGGED,
            title = "塔吉多",
            startUrl = "https://www.tajiduo.com/bbs/index.html",
            allowedHosts = listOf("tajiduo.com", "wanmei.com"),
            cookieProbe = "https://bbs-api.tajiduo.com",
            cookieNames = listOf("token", "access_token", "refreshToken", "refresh_token", "uid", "deviceid", "deviceId"),
            storageKeys = listOf("refreshToken", "refresh_token", "accessToken", "access_token", "token", "uid", "deviceId"),
            mode = LoginMode.WEB_SESSION,
            note = "异环使用独立的账号登录与社区授权交换；不依赖网页登录后的 Cookie 或网页存储。",
            verified = false,
        ),
        LoginPlatform(
            id = KUROBBS,
            title = "库街区",
            startUrl = "https://www.kurobbs.com/mc/home/9",
            allowedHosts = listOf("kurobbs.com", "kurogame.com"),
            cookieProbe = "https://api.kurobbs.com",
            cookieNames = listOf("user_token"),
            storageKeys = listOf("user_token", "token"),
            mode = LoginMode.WEB_SESSION,
            note = "打开后是桌面版社区，点右上角「登录」用手机号验证码登录；登录后读取网页会话。",
        ),
    )

    fun of(id: String): LoginPlatform = all.firstOrNull { it.id == id } ?: all.first()

    /** A plain Chrome UA: no client hints, nothing that identifies this app to the vendor. */
    val desktopUserAgent: String =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    /** The WebView's own mobile UA plus an app tag, used as-is for platforms that want a phone page. */
    fun mobileUserAgent(base: String): String = base + " Hoshiori/0.6"

    fun userAgentFor(platform: LoginPlatform, base: String, which: UserAgent = platform.userAgent): String =
        if (which == UserAgent.DESKTOP) desktopUserAgent else mobileUserAgent(base)

    fun other(which: UserAgent): UserAgent = if (which == UserAgent.DESKTOP) UserAgent.MOBILE else UserAgent.DESKTOP

    /**
     * 只有真正登记过的网页登录平台才返回 true。
     *
     * 2026-11-08：插件平台（领签）只做本机离线模拟，没有网页登录；它也必须返回 false，否则会
     * 误落到 [of] 的「第一个平台」兜底上，把用户带进别的平台的登录页。
     */
    fun supportsLogin(id: String): Boolean =
        all.firstOrNull { it.id == id }?.let { it.mode == LoginMode.WEB_SESSION } ?: false

    /** True when [url] is an HTTPS address on the platform's official domain set. */
    fun allows(platform: LoginPlatform, url: String?): Boolean {
        if (url == null || !url.startsWith("https://", ignoreCase = true)) return false
        val host = hostOf(url) ?: return false
        return platform.allowedHosts.any { host == it || host.endsWith(".$it") }
    }

    fun hostOf(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return runCatching { java.net.URI(url).host?.lowercase() }.getOrNull()
    }

    /** Login URLs may carry one-time codes; never write the query string to logs or the UI. */
    fun redact(url: String?): String {
        if (url.isNullOrBlank()) return ""
        val host = hostOf(url) ?: return ""
        val path = runCatching { java.net.URI(url).path }.getOrNull().orEmpty()
        return host + path
    }
}
