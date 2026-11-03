package com.glasssign.app.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.glasssign.app.ui.background.BackgroundPreferences
import com.glasssign.app.ui.background.BackgroundRepository
import com.glasssign.app.ui.background.BackgroundSource
import com.glasssign.app.ui.background.HoshioriBackground
import com.glasssign.app.ui.background.LocalBackgroundEnabled
import com.glasssign.app.ui.background.decodePreview
import com.glasssign.app.ui.background.persistPickedImage
import android.content.SharedPreferences
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.ui.Modifier
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import android.os.Build
import androidx.compose.ui.graphics.luminance
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Legacy UI names resolve to current Material roles, so existing login/import screens stay readable.
// One role per name; every role below now carries a measured contrast guarantee from appearanceScheme
// (>= 4.5 against the surface it is painted on), so these aliases inherit it automatically.
val Oled:Color @Composable get()=MaterialTheme.colorScheme.background
val Deep:Color @Composable get()=MaterialTheme.colorScheme.surface
val Panel:Color @Composable get()=MaterialTheme.colorScheme.surfaceContainer
val White:Color @Composable get()=MaterialTheme.colorScheme.onSurface
val Muted:Color @Composable get()=MaterialTheme.colorScheme.onSurfaceVariant
val Faint:Color @Composable get()=MaterialTheme.colorScheme.onSurfaceVariant
val Cyan:Color @Composable get()=MaterialTheme.colorScheme.primary
val Lilac:Color @Composable get()=MaterialTheme.colorScheme.secondary
val Mint:Color @Composable get()=MaterialTheme.colorScheme.tertiary
// Amber is painted on cards (`surface`) and inline values, so it is keyed on `surface`, not on
// `background`: with the tinted/soft styles the two differ, and the old check could pick the pale amber
// for a light card (contrast 1.9:1). Both branches keep their hue; the light branch is >= 4.5 on white.
val Amber:Color @Composable get()=if(MaterialTheme.colorScheme.surface.luminance()>.5f)Color(0xFF8A5300) else Color(0xFFFFC078)
val Rose:Color @Composable get()=MaterialTheme.colorScheme.error
val Slate:Color @Composable get()=MaterialTheme.colorScheme.outline

val GlassShape = RoundedCornerShape(18.dp)
val ChipShape = RoundedCornerShape(999.dp)

val LocalReduceMotion = staticCompositionLocalOf { false }

/**
 * Page wallpaper state for the rest of the UI.
 *
 * [LocalBackgroundPreferences] is the committed preference value; the dialog holds a draft while a slider
 * is being dragged and publishes the draft through [LocalBackgroundPreview] only, so the page preview and
 * the slider share one value without writing the preferences file per pixel.
 * [LocalBackgroundRepository] is null outside [GlassSignTheme] (previews, tests) and every consumer treats
 * null as "no background".
 */
val LocalBackgroundPreferences = staticCompositionLocalOf { BackgroundPreferences() }
val LocalBackgroundPreview = staticCompositionLocalOf<BackgroundPreferences?> { null }
val LocalBackgroundRepository = staticCompositionLocalOf<BackgroundRepository?> { null }
val LocalBackgroundBindings = staticCompositionLocalOf<BackgroundBindings?> { null }

/** Everything that writes background state, provided by [GlassSignTheme] and used only by AppearanceDialog. */
class BackgroundBindings(
    /** Persists a final value (mask/blur/source). */
    val commit: (BackgroundPreferences) -> Unit,
    /** Shows an uncommitted value on the page while a slider is moving. */
    val preview: (BackgroundPreferences) -> Unit,
    /** Goes back to the committed value when the drag ends or is cancelled. */
    val clearPreview: () -> Unit,
)

data class ProviderMeta(
    val id: String,
    val title: String,
    val games: String,
    val color: Color,
    val icon: ImageVector,
    val fields: List<String>,
    val sample: String,
    val help: String,
)

/**
 * Custom page background section of [AppearanceDialog].
 *
 * Uses the system picker only (PhotoPicker on API 33+, the document picker before it), so the app needs no
 * storage permission and no URI permission survives the session: the bytes are copied into the private
 * store first. The sliders preview through [BackgroundBindings.preview] during the drag and write once on
 * release, exactly like the existing text-scale slider.
 */
@Composable
internal fun BackgroundSettingsSection() {
    val context = LocalContext.current
    val repository = LocalBackgroundRepository.current
    val bindings = LocalBackgroundBindings.current
    val committed = LocalBackgroundPreview.current ?: LocalBackgroundPreferences.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf<BackgroundPreferences?>(null) }
    var preview by remember { mutableStateOf<ImageBitmap?>(null) }
    val shown = draft ?: committed

    fun refreshPreview() {
        scope.launch {
            preview = withContext(Dispatchers.Default) { decodePreview(context, 480)?.asImageBitmap() }
        }
    }
    LaunchedEffect(committed.source) {
        if (committed.source == BackgroundSource.custom) refreshPreview() else preview = null
    }
    DisposableEffect(Unit) { onDispose { preview = null } }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val repo = repository
        if (uri == null || repo == null) return@rememberLauncherForActivityResult
        busy = true
        message = null
        scope.launch {
            val result = runCatching { persistPickedImage(repo, uri, committed) }
            busy = false
            result.onSuccess { saved ->
                // Commit right away: the bytes are already in the private store, so the page can switch
                // before the user releases any slider.
                bindings?.commit(saved)
                message = "已保存到本机，可随时清除"
                refreshPreview()
            }.onFailure { message = it.message ?: "无法保存所选图片" }
        }
    }

    Text("页面背景", style = MaterialTheme.typography.titleMedium)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = committed.source == BackgroundSource.none,
            onClick = {
                message = null
                scope.launch {
                    val repo = repository
                    if (repo != null) {
                        busy = true
                        bindings?.commit(runCatching { repo.clear(committed) }.getOrDefault(committed))
                        busy = false
                    } else {
                        bindings?.commit(committed.copy(source = BackgroundSource.none))
                    }
                    preview = null
                }
            },
            label = { Text("无背景") },
            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
        )
        FilterChip(
            selected = committed.source == BackgroundSource.starfield,
            onClick = {
                message = null
                // No file copy and no disk read: the bundled artwork is decoded in the page composable once.
                bindings?.commit(committed.copy(source = BackgroundSource.starfield))
            },
            label = { Text("星海") },
            enabled = !busy,
            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
        )
        FilterChip(
            selected = committed.source == BackgroundSource.custom,
            onClick = {
                if (repository == null) return@FilterChip
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            label = { Text(if (committed.source == BackgroundSource.custom) "换图" else "自选") },
            enabled = !busy && repository != null,
            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
        )
    }
    Text(
        "「星海」为内置插画，只是背景选项，不会改动你已选的主题色。自选图片使用系统照片选择器读取，不需要存储权限；" +
            "图片只保存在本应用私有目录，不上传，清除后恢复默认背景。",
        style = MaterialTheme.typography.bodySmall, color = Muted,
    )
    if (busy) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Text("正在处理图片…", style = MaterialTheme.typography.bodySmall, color = Muted)
        }
    }
    message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Muted) }

    if (shown.source != BackgroundSource.none) {
        Spacer(Modifier.height(8.dp))
        preview?.let { bitmap ->
            // Preview decodes the stored copy once and is reused while the source does not change; it is
            // never decoded per frame and released with the dialog.
            Surface(shape = GlassShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                androidx.compose.foundation.Image(bitmap, contentDescription = "当前背景预览",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f))
            }
        }
        // One draft for both sliders: showing `draft ?: committed` for each would let the first drag reset
        // the other value to the committed one.
        val mask = shown.maskStrength
        Text("遮罩强度 ${(mask * 100).toInt()}%", color = Muted, style = MaterialTheme.typography.bodyLarge)
        Slider(
            value = mask, valueRange = BackgroundPreferences.MaskRange, steps = 16,
            onValueChange = { value -> draft = shown.copy(maskStrength = value).also { bindings?.preview(it) } },
            onValueChangeFinished = {
                val finished = draft ?: shown
                draft = null
                bindings?.commit(finished)
            },
        )
        val blur = shown.blurDp
        Text("背景模糊 ${blur.toInt()} dp", color = Muted, style = MaterialTheme.typography.bodyLarge)
        Slider(
            value = blur, valueRange = BackgroundPreferences.BlurRange, steps = 9,
            onValueChange = { value -> draft = shown.copy(blurDp = value).also { bindings?.preview(it) } },
            onValueChangeFinished = {
                val finished = draft ?: shown
                draft = null
                bindings?.commit(finished)
            },
        )
        Text("遮罩与模糊只作用于背景图片，文字和卡片保持原有对比度。", style = MaterialTheme.typography.bodySmall, color = Muted)
    }
}

val Providers = listOf(
    ProviderMeta(
        id = "miyoushe",
        title = "米游社",
        games = "原神 · 星穹铁道 · 绝区零",
        color = Color(0xFF4263C7),
        icon = Icons.Rounded.AutoAwesome,
        fields = listOf("cookie"),
        sample = "{\"cookie\":\"stuid=...; stoken=...; cookie_token=...\",\"games\":[\"genshin\",\"honkai_sr\",\"zzz\"]}",
        help = "从你自己已登录的米游社网页会话中取得工作 Cookie；同时提供 stoken 与 stuid 可在过期时尝试续期。不要填写账号密码。",
    ),
    ProviderMeta(
        id = "skland",
        title = "森空岛",
        games = "明日方舟 · 终末地",
        color = Color(0xFF7553A8),
        icon = Icons.Rounded.Shield,
        fields = listOf("cred", "signToken", "dId"),
        sample = "{\"cred\":\"...\",\"signToken\":\"...\",\"dId\":\"...\",\"games\":[\"arknights\",\"endfield\"],\"communityCheckin\":true}",
        help = "使用本人已授权的 cred、signToken 与匹配的设备标识 dId。App 不生成虚假设备指纹，也不绕过人机验证。",
    ),
    ProviderMeta(
        id = "tagged",
        title = "塔吉多",
        games = "异环 · 社区签到",
        color = Color(0xFF9D641F),
        icon = Icons.Rounded.Bolt,
        fields = listOf("refreshToken", "deviceId"),
        sample = "{\"refreshToken\":\"...\",\"deviceId\":\"...\",\"uid\":\"...\",\"gameIds\":[\"1289\"],\"communityCheckin\":true}",
        help = "授权续期时会把轮换后的 refreshToken 立即通过 Android Keystore 加密写回本机；仅接受本人授权。",
    ),
    ProviderMeta(
        id = "kurobbs",
        title = "库街区",
        games = "鸣潮 · 战双帕弥什",
        color = Color(0xFF24795D),
        icon = Icons.Rounded.Waves,
        fields = listOf("token"),
        sample = "{\"token\":\"...\",\"games\":[\"3\",\"2\"],\"communityCheckin\":true}",
        help = "token 用于读取本人绑定角色并执行社区/角色签到；本机会保存稳定的应用设备标识，避免每天更换指纹。",
    ),
)

fun providerMeta(id: String): ProviderMeta =
    Providers.firstOrNull { it.id == id } ?: Providers.first()

fun statusLabel(status: String): String = when (status) {
    "success" -> "已签到"
    "already" -> "已签到"
    "waiting_network" -> "待网络"
    "running" -> "执行中"
    "failed" -> "失败"
    "needs_action" -> "需处理"
    "unsupported" -> "未接入"
    else -> status
}

/**
 * Status colours are painted as chips and inline values on `surface`, so each must clear 4.5:1 there.
 * `tertiary`/`primary`/`secondary`/`error` already carry that guarantee from appearanceScheme; the two
 * literal colours below (`Amber`, `Slate`) are checked here and fall back to the readable ink if a future
 * palette change ever breaks them. `statusColor("failed")` therefore stays exactly `colorScheme.error`
 * for every built-in style, which is the destructive/`Rose` convention used elsewhere.
 */
@Composable
fun statusColor(status: String): Color {
    val surface=MaterialTheme.colorScheme.surface
    fun readable(color:Color):Color=if(contrastRatio(color,surface)>=4.5f)color else readableOn(surface)
    return when (status) {
        "success" -> readable(Mint)
        "already" -> readable(Cyan)
        "waiting_network" -> readable(Amber)
        "running" -> readable(Lilac)
        "needs_action" -> readable(Amber)
        "failed" -> readable(Rose)
        "unsupported" -> readable(Slate)
        else -> Muted
    }
}

private val AppTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 30.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.6).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 21.sp,
        lineHeight = 27.sp,
        letterSpacing = (-0.2).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 21.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 17.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        letterSpacing = 1.1.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
)

@Composable
fun GlassSignTheme(reduceMotion: Boolean, content: @Composable () -> Unit) {
    val (settings,controller)=rememberAppearance()
    val systemDark=isSystemInDarkTheme()
    val dark=when(settings.mode){"light"->false;"dark"->true;else->systemDark}
    val context=LocalContext.current
    val view=LocalView.current
    SideEffect {
        var current:Context=context
        while(current is ContextWrapper && current !is Activity)current=current.baseContext
        (current as? Activity)?.window?.let{window->
            WindowCompat.getInsetsController(window,view).apply {
                isAppearanceLightStatusBars=!dark
                isAppearanceLightNavigationBars=!dark
            }
        }
    }
    val wallpaper=if(settings.dynamicColor&&Build.VERSION.SDK_INT>=31) {
        if(dark)dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else null
    // The scheme depends on exactly these fields: every one of them is a palette input (asserted by
    // AppearanceSchemeV070Test) and `font`/`scaleEnabled`/`textScale` are deliberately NOT keys. The old
    // key was the whole `settings` object, so a font pick or a text-scale commit rebuilt the entire
    // ColorScheme (one allocation plus the lerp/repair calls) although the palette cannot change.
    // `glassMode`, `surfaceEffect` and `navigationTint` are material/paint settings read by Glass.kt and
    // LiquidDock.kt, not palette inputs, so they are not keys either (LocalAppearance still carries the
    // whole settings object to those consumers). Not claimed as a frame-rate fix: the real-device
    // baseline already shows the app is idle between interactions.
    val scheme=remember(dark,settings.colorStyle,settings.seed,settings.pureBlack,settings.dynamicColor,wallpaper) {
        appearanceScheme(settings,dark,wallpaper)
    }
    val font=appearanceFont(settings)
    val typography=remember(font){AppTypography.let{t->t.copy(
        displayLarge=t.displayLarge.copy(fontFamily=font),displayMedium=t.displayMedium.copy(fontFamily=font),displaySmall=t.displaySmall.copy(fontFamily=font),
        headlineLarge=t.headlineLarge.copy(fontFamily=font),headlineMedium=t.headlineMedium.copy(fontFamily=font),headlineSmall=t.headlineSmall.copy(fontFamily=font),
        titleLarge=t.titleLarge.copy(fontFamily=font),titleMedium=t.titleMedium.copy(fontFamily=font),titleSmall=t.titleSmall.copy(fontFamily=font),
        bodyLarge=t.bodyLarge.copy(fontFamily=font),bodyMedium=t.bodyMedium.copy(fontFamily=font),bodySmall=t.bodySmall.copy(fontFamily=font),
        labelLarge=t.labelLarge.copy(fontFamily=font),labelMedium=t.labelMedium.copy(fontFamily=font),labelSmall=t.labelSmall.copy(fontFamily=font),
    )}}
    val density=LocalDensity.current
    val scaled=remember(density,settings.scaleEnabled,settings.textScale){Density(density.density,density.fontScale*if(settings.scaleEnabled)settings.textScale else 1f)}
    val backgroundRepository=remember(context.applicationContext){BackgroundRepository(context.applicationContext)}
    // `commit` is a plain (non-suspend) callback because the dialog calls it from slider handlers; the
    // preference write is a small file write on IO, so it is launched instead of awaited.
    val backgroundScope=rememberCoroutineScope()
    var backgroundPreferences by remember(backgroundRepository){mutableStateOf(backgroundRepository.readPreferences())}
    // Preview never persists: the drag path writes this state only, the release writes the file once.
    var backgroundPreview by remember(backgroundRepository){mutableStateOf<BackgroundPreferences?>(null)}
    // Owns a decoded bitmap, so it must be released with the theme, not with the page composable.
    DisposableEffect(backgroundRepository){onDispose{backgroundRepository.release()}}
    // Cold start only: repair an interrupted copy and drop a `custom` whose file no longer decodes.
    LaunchedEffect(backgroundRepository){backgroundPreferences=backgroundRepository.recover()}
    val changeListener=remember(backgroundRepository){SharedPreferences.OnSharedPreferenceChangeListener { _,_->backgroundPreferences=backgroundRepository.readPreferences() }}
    DisposableEffect(backgroundRepository,changeListener) {
        val prefs=context.applicationContext.getSharedPreferences("appearance_v1",Context.MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(changeListener)
        onDispose{prefs.unregisterOnSharedPreferenceChangeListener(changeListener)}
    }
    // Only the boolean reaches consumers (card alpha in HoshioriScreen), so a mask slider drag does not
    // recompose the page: LocalBackgroundEnabled is a separate, stable-valued CompositionLocal.
    val backgroundEnabled=(backgroundPreview?:backgroundPreferences).enabled
    CompositionLocalProvider(
        LocalReduceMotion provides reduceMotion,LocalAppearance provides settings,LocalAppearanceController provides controller,LocalDensity provides scaled,
        LocalBackgroundRepository provides backgroundRepository,
        LocalBackgroundPreferences provides backgroundPreferences,
        LocalBackgroundPreview provides backgroundPreview,
        LocalBackgroundEnabled provides backgroundEnabled,
        LocalBackgroundBindings provides BackgroundBindings(
            commit={value->backgroundPreferences=value;backgroundPreview=null;backgroundScope.launch{backgroundRepository.savePreferences(value)}},
            preview={value->backgroundPreview=value},
            clearPreview={backgroundPreview=null},
        ),
    ) {
        MaterialTheme(colorScheme=scheme,typography=typography){CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides scheme.onBackground,content=content)}
    }
}
