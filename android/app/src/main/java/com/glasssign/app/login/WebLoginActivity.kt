package com.glasssign.app.login

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Message
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.glasssign.app.ui.Cyan
import com.glasssign.app.ui.Faint
import com.glasssign.app.ui.GlassSignTheme
import com.glasssign.app.ui.Muted
import com.glasssign.app.ui.Oled
import com.glasssign.app.ui.Rose
import com.glasssign.app.ui.White
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONTokener
import java.util.UUID
import kotlin.coroutines.resume

/**
 * In-app official login.
 *
 * The user signs in on the platform's own web page inside this activity and nothing else: no
 * password field of our own, no JavaScript bridge, no scraping of another app. After login the
 * WebView's own session (cookies and, for SPA platforms, the localStorage entries the official
 * front end maintains) is mapped to a local credential and handed back in memory.
 *
 * A login is only reported as successful when the required session fields actually exist; the
 * caller then still has to read the real character list before an account is bound.
 */
@SuppressLint("SetJavaScriptEnabled")
class WebLoginActivity : ComponentActivity() {

    companion object {
        const val EXTRA_PROVIDER = "provider"
        const val EXTRA_NICKNAME = "nickname"
        const val EXTRA_DEVICE_ID = "device_id"
        const val EXTRA_ERROR = "login_error"
        private const val TIMEOUT_MS = 3 * 60 * 1000L
        private const val POLL_MS = 3_000L

        fun intent(context: Context, provider: String, nickname: String, deviceId: String): Intent =
            Intent(context, WebLoginActivity::class.java)
                .putExtra(EXTRA_PROVIDER, provider)
                .putExtra(EXTRA_NICKNAME, nickname)
                .putExtra(EXTRA_DEVICE_ID, deviceId)
    }

    private lateinit var platform: LoginPlatform
    private lateinit var nickname: String
    private lateinit var deviceId: String

    private var web: WebView? = null
    private var popup: WebView? = null
    private var popupHost: android.widget.FrameLayout? = null
    private var poll: Job? = null
    private var closed = false

    private var sessionReady by mutableStateOf(false)
    private var status by mutableStateOf("正在打开官方登录页…")
    private var progress by mutableStateOf(0.08f)
    private var blocked by mutableStateOf<String?>(null)
    private var harvesting by mutableStateOf(false)
    private var agent by mutableStateOf(UserAgent.DESKTOP)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The official page may show the user's own password / SMS code: keep it out of screenshots,
        // the recent-tasks thumbnail and any screen recorder. Debug builds stay capturable so the
        // login page can be reviewed; shipped builds are always protected.
        if (true) {
            window.setFlags(
                android.view.WindowManager.LayoutParams.FLAG_SECURE,
                android.view.WindowManager.LayoutParams.FLAG_SECURE,
            )
        }
        platform = LoginPlatforms.of(intent.getStringExtra(EXTRA_PROVIDER).orEmpty())
        nickname = intent.getStringExtra(EXTRA_NICKNAME).orEmpty().ifBlank { platform.title }
        deviceId = intent.getStringExtra(EXTRA_DEVICE_ID).orEmpty().ifBlank { UUID.randomUUID().toString() }
        agent = platform.userAgent
        onBackPressedDispatcher.addCallback(this) { cancelLogin() }
        setContent {
            GlassSignTheme(reduceMotion = true) { Screen() }
        }
        android.webkit.WebStorage.getInstance().deleteAllData()
        CookieManager.getInstance().removeAllCookies {
            CookieManager.getInstance().flush();sessionReady=true
        }
        startPolling()
    }

    @Composable
    private fun Screen() {
        Box(Modifier.fillMaxSize().background(Oled)) {
            Column(Modifier.fillMaxSize()) {
                Column(Modifier.statusBarsPadding().fillMaxWidth().background(Oled).padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("登录${platform.title}", color = White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            Text(status, color = if (blocked != null) Rose else Muted, fontSize = 10.sp, lineHeight = 14.sp)
                        }
                        TextButton(onClick={
                            android.webkit.WebStorage.getInstance().deleteAllData()
                            CookieManager.getInstance().removeAllCookies {CookieManager.getInstance().flush();web?.loadUrl(platform.startUrl)}
                            status="已清除本次网页会话，请登录其他账号；已绑定授权保留"
                        }) {Text("切换账号",fontSize=11.sp)}
                        if (harvesting) {
                            CircularProgressIndicator(Modifier.size(16.dp), color = Cyan, strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        TextButton(
                            onClick = { switchAgent() },
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
                        ) {
                            Text(if (agent == UserAgent.DESKTOP) "桌面版" else "手机版", color = Cyan, fontSize = 11.sp)
                        }
                        IconButton(onClick = { cancelLogin() }) {
                            Icon(Icons.Rounded.Close, contentDescription = "取消登录", tint = Muted)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().height(2.dp),
                        color = Cyan,
                        trackColor = White.copy(alpha = 0.08f),
                    )
                }
                Box(Modifier.weight(1f)) {
                    if(sessionReady)AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { context ->
                            android.widget.FrameLayout(context).apply {
                                addView(buildWeb().also { web = it })
                                popupHost = this
                            }
                        },
                    )
                }
                Text(
                    "这是官方登录页；账号密码与验证码只在官方页面内输入。授权只在本机加密保存，" +
                        "退出登录页时清除网页会话。不记录、不上传、不截图。",
                    color = Faint,
                    fontSize = 9.sp,
                    lineHeight = 13.sp,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
    }

    private fun buildWeb(): WebView {
        val view = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(android.graphics.Color.BLACK)
            isVerticalScrollBarEnabled = true
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = false
                allowFileAccess = false
                allowContentAccess = false
                setGeolocationEnabled(false)
                saveFormData = false
                javaScriptCanOpenWindowsAutomatically = true
                setSupportMultipleWindows(true)
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                cacheMode = WebSettings.LOAD_DEFAULT
                mediaPlaybackRequiresUserGesture = true
            }
            applyAgent(this, agent)
            webViewClient = navigationClient()
            webChromeClient = chromeClient()
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
        view.loadUrl(platform.startUrl)
        return view
    }

    /**
     * Applies the browser identity to [view]. Desktop mode lays the page out at a desktop viewport
     * width and zooms the text up, because several of these sites only ship a working login form to
     * desktop user agents; pinch-zoom stays available in case a form is still too small.
     */
    private fun applyAgent(view: WebView, which: UserAgent) {
        view.settings.apply {
            userAgentString = LoginPlatforms.userAgentFor(platform, defaultUserAgent, which)
            useWideViewPort = which == UserAgent.DESKTOP
            // Desktop pages are forced to a fixed layout width and then scaled to fit; without that
            // the right edge of the header (where the log-in button sits) is clipped away.
            loadWithOverviewMode = which == UserAgent.DESKTOP
            builtInZoomControls = which == UserAgent.DESKTOP
            displayZoomControls = false
            setSupportZoom(which == UserAgent.DESKTOP)
            textZoom = 100
        }
    }

    /** Sites that answer a phone UA with an "install the app" page need this switch. */
    private fun switchAgent() {
        agent = LoginPlatforms.other(agent)
        status = if (agent == UserAgent.DESKTOP) "已切换到桌面版页面，正在重新加载…" else "已切换到手机版页面，正在重新加载…"
        web?.let { view ->
            applyAgent(view, agent)
            view.reload()
        }
    }

    private val defaultUserAgent: String by lazy { WebSettings.getDefaultUserAgent(this) }

    /** Pins the layout viewport so a desktop page renders complete instead of clipped. */
    private fun forceDesktopWidth(view: WebView) {
        val script = "(function(){try{" +
            "var m=document.querySelector('meta[name=viewport]');" +
            "if(!m){m=document.createElement('meta');m.setAttribute('name','viewport');" +
            "document.head.appendChild(m);}" +
            "if(m.getAttribute('content')!=='width=1280'){m.setAttribute('content','width=1280');" +
            "document.documentElement.style.zoom='1';}" +
            "}catch(e){}})()"
        view.evaluateJavascript(script, null)
    }

    private fun navigationClient(): WebViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            if (!url.startsWith("https://") && !url.startsWith("http://")) return true
            if (LoginPlatforms.allows(platform, url)) return false
            blocked = "已阻止跳转到非官方站点：${LoginPlatforms.hostOf(url)}"
            return true
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
            status = "官方页面加载中 · ${LoginPlatforms.redact(url)}"
            progress = 0.35f
        }

        override fun onPageFinished(view: WebView, url: String?) {
            progress = 0.9f
            // Desktop community pages lay themselves out at ~1200px and simply clip anything past
            // the right edge, which is exactly where the log-in button lives. Forcing the layout
            // viewport wide lets the whole header fit on screen.
            if (agent == UserAgent.DESKTOP && LoginPlatforms.allows(platform, url)) forceDesktopWidth(view)
            if (blocked == null) {
                status = if (agent == UserAgent.DESKTOP) {
                    "页面已自动适配宽度；请完成登录，也可切换手机版或取消后使用本人授权导入"
                } else {
                    "请在官方页面完成登录；完成后会自动继续"
                }
            }
            if (LoginPlatforms.allows(platform, url)) blocked = null
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
            if (request.isForMainFrame) status = "网络错误，请检查连接后重试（${LoginPlatforms.redact(request.url.toString())}）"
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun chromeClient(): WebChromeClient = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) { progress = newProgress / 100f }

        // Sign-in SDKs open the account page in a popup window; host it in our own WebView so the
        // session stays inside this app and every navigation is still checked.
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            val child = WebView(this@WebLoginActivity).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    allowFileAccess = false
                    allowContentAccess = false
                    saveFormData = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                }
                webViewClient = navigationClient()
                webChromeClient = this@WebLoginActivity.chromeClient()
            }
            applyAgent(child, agent)
            popup?.let { popupHost?.removeView(it); it.destroy() }
            popup = child
            popupHost?.addView(child)
            (resultMsg.obj as? WebView.WebViewTransport)?.webView = child
            resultMsg.sendToTarget()
            status = "请在官方账号窗口完成登录"
            return true
        }

        override fun onCloseWindow(window: WebView) {
            popupHost?.removeView(window)
            window.destroy()
            if (popup === window) popup = null
        }
    }

    private fun startPolling() {
        poll = lifecycleScope.launch {
            val started = System.currentTimeMillis()
            var rounds = 0
            while (isActive && !closed) {
                delay(POLL_MS)
                rounds++
                if (System.currentTimeMillis() - started > TIMEOUT_MS) {
                    fail("官方授权未返回，尚未完成绑定。请返回游戏绑定页重试。")
                    return@launch
                }
                val result = try {
                    kotlinx.coroutines.withTimeoutOrNull(5_000L) { harvest() }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    fail("官方授权返回异常，尚未完成绑定，请返回重试")
                    return@launch
                }
                when (result) {
                    is HarvestResult.Ready -> {
                        harvesting = true
                        succeed(result.credentials)
                        return@launch
                    }
                    is HarvestResult.Incomplete -> if (blocked == null) {
                        val remaining = (TIMEOUT_MS - (System.currentTimeMillis() - started)) / 1000
                        status = "${result.missing}（剩余 ${remaining.coerceAtLeast(0)} 秒，可随时取消）"
                    }
                    null -> {
                        fail("官方页面未响应，尚未完成绑定。请检查网络后重新打开登录页")
                        return@launch
                    }
                }
            }
        }
    }

    private suspend fun harvest(): HarvestResult {
        val jar = CookieJar.parse(
            CookieManager.getInstance().getCookie(platform.cookieProbe),
            platform.cookieNames,
        )
        val storage = if (platform.storageKeys.isEmpty()) emptyMap() else readStorage()
        return SessionHarvest.build(platform.id, jar, storage, deviceId)
    }

    private suspend fun readStorage(): Map<String, String> = withContext(Dispatchers.Main) {
        val view = web ?: return@withContext emptyMap()
        if (view.url.isNullOrBlank()) return@withContext emptyMap()
        val keys = platform.storageKeys.joinToString(",") { JSONObject.quote(it) }
        val script = "(function(){try{var k=[$keys],o={};" +
            "for(var i=0;i<k.length;i++){var v=window.localStorage.getItem(k[i]);if(v!==null){o[k[i]]=v;}}" +
            "return JSON.stringify(o);}catch(e){return '{}';}})()"
        val raw = suspendCancellableCoroutine<String?> { continuation ->
            view.evaluateJavascript(script) { value -> if (continuation.isActive) continuation.resume(value) }
        }
        val json = (runCatching { JSONTokener(raw).nextValue() }.getOrNull() as? String).orEmpty()
        if (json.isBlank()) return@withContext emptyMap()
        val parsed = runCatching { JSONObject(json) }.getOrNull() ?: return@withContext emptyMap()
        buildMap {
            parsed.keys().forEach { key -> parsed.optString(key).takeIf { it.isNotBlank() }?.let { put(key, it) } }
        }
    }

    private fun succeed(credentials: JSONObject) {
        if (closed) return
        closed = true
        status = "已取得官方登录会话，正在加密保存…"
        LoginBridge.deliver(LoginPayload(platform.id, nickname, credentials.toString()))
        setResult(RESULT_OK)
        teardown()
    }

    private fun fail(message: String) {
        if (closed) return
        closed = true
        status = message
        setResult(RESULT_CANCELED, Intent().putExtra(EXTRA_ERROR, message))
        teardown()
    }

    private fun cancelLogin() {
        if (closed) return
        closed = true
        LoginBridge.clear()
        setResult(RESULT_CANCELED)
        teardown()
    }

    /** Leaving the login screen must not leave a usable session behind for the next attempt. */
    private fun teardown() {
        poll?.cancel()
        poll = null
        popup?.let { runCatching { it.destroy() } }
        popup = null
        web?.let { view ->
            runCatching {
                view.loadUrl("about:blank")
                view.clearHistory()
                view.clearCache(true)
                view.removeAllViews()
                view.destroy()
            }
        }
        web = null
        runCatching {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
        }
        finish()
    }

    override fun onDestroy() {
        poll?.cancel()
        super.onDestroy()
    }
}
