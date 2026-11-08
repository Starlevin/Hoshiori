package com.glasssign.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
internal val LocalReduceMotionAction = staticCompositionLocalOf<((Boolean)->Unit)?> { null }

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
            preview = withContext(Dispatchers.Default) { decodePreview(context, 640)?.asImageBitmap() }
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
            runCatching { persistPickedImage(repo, uri, committed) }
                .onSuccess { saved ->
                    bindings?.commit(saved)
                    message = "图片已保存到本机"
                    refreshPreview()
                }
                .onFailure { message = it.message ?: "无法保存所选图片" }
            busy = false
        }
    }

    fun chooseImage() {
        if (!busy && repository != null) {
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
    }

    fun clearBackground() {
        // Set `busy` synchronously, before any coroutine is launched: a second tap in the same frame must
        // not start a concurrent clear while the first one is still deleting the private copy.
        if (busy) return
        busy = true
        message = null
        draft = null
        scope.launch {
            val result = if (repository != null && committed.source == BackgroundSource.custom) {
                runCatching { repository.clear(committed) }
            } else {
                Result.success(committed.copy(source = BackgroundSource.none))
            }
            busy = false
            result.onSuccess { cleared ->
                bindings?.commit(cleared)
                preview = null
                message = "已恢复为无背景"
            }.onFailure {
                message = it.message ?: "清除背景失败"
                // The delete failed, so a custom photo may still be on disk: put the state back honestly
                // instead of leaving the page claiming "no background" while the wallpaper is still there.
                preview = null
                refreshPreview()
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PreferenceGroup("背景样式") {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BackgroundChoiceCard(
                        modifier = Modifier.weight(1f),
                        title = "无背景",
                        description = "使用当前主题底色",
                        selected = committed.source == BackgroundSource.none,
                        enabled = !busy,
                        plain = true,
                        thumbnail = null,
                        onClick = ::clearBackground,
                    )
                    BackgroundChoiceCard(
                        modifier = Modifier.weight(1f),
                        title = "自选图片",
                        description = if (committed.source == BackgroundSource.custom) "已设置 · 点击更换" else "从相册选择",
                        selected = committed.source == BackgroundSource.custom,
                        enabled = !busy && repository != null,
                        plain = false,
                        thumbnail = if (committed.source == BackgroundSource.custom) preview else null,
                        onClick = ::chooseImage,
                    )
                }
                if (busy) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(
                            "正在处理图片…",
                            style = MaterialTheme.typography.bodySmall,
                            color = Muted,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                }
            }
        }

        if (shown.source == BackgroundSource.custom) {
            PreferenceGroup("当前图片") {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (preview != null) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ) {
                            Image(
                                bitmap = preview!!,
                                contentDescription = "当前背景预览",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                            )
                        }
                    } else {
                        Box(
                            Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                                .clip(RoundedCornerShape(16.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .semantics { contentDescription = "正在载入背景预览" },
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = ::clearBackground, enabled = !busy) { Text("清除背景") }
                        TextButton(onClick = ::chooseImage, enabled = !busy && repository != null) { Text("更换图片") }
                    }
                }
            }

            PreferenceGroup("图片显示效果") {
                Column(
                    Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    val mask = shown.maskStrength
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("背景遮罩", style = MaterialTheme.typography.bodyMedium)
                        androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                        Text((mask * 100).toInt().toString() + "%", style = MaterialTheme.typography.labelLarge, color = Muted)
                    }
                    Slider(
                        value = mask,
                        valueRange = BackgroundPreferences.MaskRange,
                        steps = 16,
                        modifier = Modifier.semantics {
                            contentDescription = "背景遮罩，当前 ${(mask * 100).toInt()}%"
                        },
                        onValueChange = { value -> draft = shown.copy(maskStrength = value).also { bindings?.preview(it) } },
                        onValueChangeFinished = {
                            val finished = draft ?: shown
                            draft = null
                            bindings?.commit(finished)
                        },
                    )

                    val blur = shown.blurDp
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("背景模糊", style = MaterialTheme.typography.bodyMedium)
                        androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                        Text(blur.toInt().toString() + " dp", style = MaterialTheme.typography.labelLarge, color = Muted)
                    }
                    Slider(
                        value = blur,
                        valueRange = BackgroundPreferences.BlurRange,
                        steps = 9,
                        modifier = Modifier.semantics {
                            contentDescription = "背景模糊，当前 ${blur.toInt()} dp"
                        },
                        onValueChange = { value -> draft = shown.copy(blurDp = value).also { bindings?.preview(it) } },
                        onValueChangeFinished = {
                            val finished = draft ?: shown
                            draft = null
                            bindings?.commit(finished)
                        },
                    )
                    Text(
                        "遮罩与模糊只作用于图片，不影响文字和卡片。",
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted,
                    )
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Lock, contentDescription = null, tint = Muted, modifier = Modifier.size(16.dp))
            Text(
                "图片仅保存在本机，不上传；清除后恢复默认背景。",
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
            )
        }
        message?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

@Composable
private fun BackgroundChoiceCard(
    modifier: Modifier,
    title: String,
    description: String,
    selected: Boolean,
    enabled: Boolean,
    plain: Boolean,
    thumbnail: ImageBitmap?,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    // TalkBack reads the visible title and description from the Text nodes below; the card itself only
    // adds the selection state and a radio-like role, so nothing is hidden behind one merged label and
    // the user hears "已选 / 未选" as a real state rather than as part of a long sentence.
    val state = if (selected) "已选" else "未选"
    Surface(
        modifier = modifier
            .heightIn(min = 136.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics {
                role = Role.RadioButton
                contentDescription = "$title，$description，$state"
            },
        shape = RoundedCornerShape(18.dp),
        color = if (selected) colors.primary.copy(alpha = .08f) else colors.surface,
        border = BorderStroke(
            if (selected) 1.5.dp else 1.dp,
            if (selected) colors.primary.copy(alpha = .85f) else colors.outlineVariant.copy(alpha = .55f),
        ),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(11.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Box(
                Modifier.fillMaxWidth().height(68.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(colors.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    thumbnail != null -> Image(
                        bitmap = thumbnail,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    plain -> {
                        Box(
                            Modifier.fillMaxSize().background(colors.background),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Rounded.Waves, contentDescription = null, tint = colors.outline, modifier = Modifier.size(24.dp))
                        }
                    }
                    else -> Icon(
                        Icons.Rounded.AddPhotoAlternate,
                        contentDescription = null,
                        tint = colors.primary,
                        modifier = Modifier.size(28.dp),
                    )
                }
                if (selected) {
                    Surface(
                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                        shape = CircleShape,
                        color = colors.primary,
                    ) {
                        Icon(
                            Icons.Rounded.Check,
                            contentDescription = null,
                            tint = colors.onPrimary,
                            modifier = Modifier.padding(4.dp).size(14.dp),
                        )
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(description, style = MaterialTheme.typography.bodySmall, color = Muted)
            }
        }
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
    // 2026-11-08：插件平台。需先在「设置 → 插件」安装并验签领签；签到在星栞本机离线模拟执行，
    // 不读取领签的账号或令牌，也不调用官方接口。
    ProviderMeta(
        id = "lingqian",
        title = "领签 · 本地模拟",
        games = "领签任务（本机模拟）",
        color = Color(0xFF6B7280),
        icon = Icons.Rounded.Extension,
        fields = emptyList(),
        sample = "{}",
        help = "领签是独立第三方应用：星栞不读取它的账号、令牌或数据库，也不替它执行后台任务。这里的签到只是本机离线模拟，用来验证绑定与记录链路，不代表官方已签到。",
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
    val launcherStore=remember(context.applicationContext){LauncherIconStore.open(context.applicationContext)}
    var launcherMode by remember(launcherStore){mutableStateOf(launcherStore.read())}
    val launcherListener=remember(launcherStore){SharedPreferences.OnSharedPreferenceChangeListener { _,_->launcherMode=launcherStore.read() }}
    DisposableEffect(launcherStore,launcherListener) {
        launcherStore.register(launcherListener)
        onDispose{launcherStore.unregister(launcherListener)}
    }
    val launcherManager=remember(context.applicationContext){LauncherIconManager(PackageManagerSwitcher(context.applicationContext),atomicAliasSwitch(Build.VERSION.SDK_INT))}
    // The controller is re-created only with the theme, not on every icon change.
    val launcherController=remember(launcherStore,launcherManager){LauncherIconController(launcherStore,launcherManager)}
    // The launcher alias is written only from MainActivity: the login/plugin activities and previews also
    // compose this theme but must never rebind the launcher entry. A no-op when the same choice is cached.
    val hostIsMainActivity=(context as? Activity)?.javaClass?.name=="com.glasssign.app.MainActivity"
    LaunchedEffect(hostIsMainActivity,launcherMode,settings.mode,systemDark) {
        if(hostIsMainActivity)launcherManager.apply(launcherMode,settings.mode,systemDark)
    }
    CompositionLocalProvider(
        LocalReduceMotion provides reduceMotion,LocalAppearance provides settings,LocalAppearanceController provides controller,LocalDensity provides scaled,
        LocalLauncherIconMode provides launcherMode,
        LocalLauncherIconController provides launcherController,
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
