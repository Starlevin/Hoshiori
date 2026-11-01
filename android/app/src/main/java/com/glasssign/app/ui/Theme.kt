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
val Oled:Color @Composable get()=MaterialTheme.colorScheme.background
val Deep:Color @Composable get()=MaterialTheme.colorScheme.surface
val Panel:Color @Composable get()=MaterialTheme.colorScheme.surfaceContainer
val White:Color @Composable get()=MaterialTheme.colorScheme.onSurface
val Muted:Color @Composable get()=MaterialTheme.colorScheme.onSurfaceVariant
val Faint:Color @Composable get()=MaterialTheme.colorScheme.onSurfaceVariant
val Cyan:Color @Composable get()=MaterialTheme.colorScheme.primary
val Lilac:Color @Composable get()=MaterialTheme.colorScheme.secondary
val Mint:Color @Composable get()=MaterialTheme.colorScheme.tertiary
val Amber:Color @Composable get()=if(MaterialTheme.colorScheme.background.luminance()>.5f)Color(0xFF8A5300) else Color(0xFFFFC078)
val Rose:Color @Composable get()=MaterialTheme.colorScheme.error
val Slate:Color @Composable get()=MaterialTheme.colorScheme.outline

val GlassShape = RoundedCornerShape(18.dp)
val ChipShape = RoundedCornerShape(999.dp)

val LocalReduceMotion = staticCompositionLocalOf { false }

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

@Composable
fun statusColor(status: String): Color = when (status) {
    "success" -> Mint
    "already" -> Cyan
    "waiting_network" -> Amber
    "running" -> Lilac
    "needs_action" -> Amber
    "failed" -> Rose
    "unsupported" -> Slate
    else -> Muted
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
    val scheme=remember(settings,dark,wallpaper){appearanceScheme(settings,dark,wallpaper)}
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
    CompositionLocalProvider(LocalReduceMotion provides reduceMotion,LocalAppearance provides settings,LocalAppearanceController provides controller,LocalDensity provides scaled) {
        MaterialTheme(colorScheme=scheme,typography=typography,content=content)
    }
}
