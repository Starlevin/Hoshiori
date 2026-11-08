package com.glasssign.app.ui

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/** Independently implemented theme preferences. FlClash feature mapping is documented in docs. */
@Immutable
data class AppearanceSettings(
    val glassMode:String="standard",val mode:String="system",val pureBlack:Boolean=false,val colorStyle:String="neutral",
    val seed:Int=0xFF4263C7.toInt(),val dynamicColor:Boolean=false,
    val surfaceEffect:String="ultra",val navigationTint:Boolean=false,
    val scaleEnabled:Boolean=false,val textScale:Float=1f,val font:String="system",val customColors:List<Int> = emptyList(),
)

class AppearanceController(context:Context) {
    private val preferences=context.applicationContext.getSharedPreferences("appearance_v1",Context.MODE_PRIVATE)
    init {
        if(preferences.getInt("theme_catalog_version",0)<1) {
            val wasDefaultBlue=preferences.getString("color_style","vivid")=="vivid" &&
                preferences.getInt("seed",0xFF4263C7.toInt())==0xFF4263C7.toInt() &&
                !preferences.getBoolean("dynamic",false)
            preferences.edit().apply {
                putInt("theme_catalog_version",1)
                if(wasDefaultBlue)putString("color_style","neutral")
            }.apply()
        }
    }
    fun read()=AppearanceSettings(
        glassMode=preferences.getString("glass_mode","standard").orEmpty().takeIf{it in listOf("standard","light","off")} ?: "standard",
        mode=preferences.getString("mode","system").orEmpty().takeIf{it in listOf("system","light","dark")} ?: "system",
        pureBlack=preferences.getBoolean("pure_black",false),
        colorStyle=preferences.getString("color_style","neutral").orEmpty().takeIf{it in listOf("neutral","vivid","tinted","soft","monochrome")} ?: "neutral",
        seed=preferences.getInt("seed",0xFF4263C7.toInt()),dynamicColor=preferences.getBoolean("dynamic",false),
        surfaceEffect=preferences.getString("surface_effect","ultra").orEmpty().takeIf{it in listOf("none","standard","ultra")} ?: "ultra",
        navigationTint=preferences.getBoolean("navigation_tint",false),
        scaleEnabled=preferences.getBoolean("scale_enabled",false),textScale=preferences.getFloat("text_scale",1f).coerceIn(.85f,1.4f),
        font=preferences.getString("font","system").orEmpty().takeIf{it in listOf("system","sans","serif","mono")} ?: "system",
        customColors=preferences.getString("custom_colors","").orEmpty().split(',').mapNotNull{it.toLongOrNull(16)?.toInt()}.distinct().takeLast(8),
    )
    fun save(value:AppearanceSettings) {
        preferences.edit().putString("glass_mode",value.glassMode).putString("mode",value.mode).putBoolean("pure_black",value.pureBlack)
            .putString("color_style",value.colorStyle).putInt("seed",value.seed).putBoolean("dynamic",value.dynamicColor)
            .putString("surface_effect",value.surfaceEffect).putBoolean("navigation_tint",value.navigationTint)
            .putBoolean("scale_enabled",value.scaleEnabled).putFloat("text_scale",value.textScale.coerceIn(.85f,1.4f))
            .putString("font",value.font).putString("custom_colors",value.customColors.distinct().takeLast(8).joinToString(","){it.toUInt().toString(16)}).apply()
    }
    fun listen(listener:SharedPreferences.OnSharedPreferenceChangeListener)=preferences.registerOnSharedPreferenceChangeListener(listener)
    fun stop(listener:SharedPreferences.OnSharedPreferenceChangeListener)=preferences.unregisterOnSharedPreferenceChangeListener(listener)
}

val LocalAppearance=staticCompositionLocalOf { AppearanceSettings() }
internal val LocalAppearanceController=staticCompositionLocalOf<AppearanceController?> {null}

/**
 * Appearance settings plus the controller.
 *
 * The listener below re-reads the whole preference file, so it is kept only for changes that did not
 * originate here (another activity, cleared data) and the drag path never reaches it any more: see the
 * `draftScale` slider in [AppearanceDialog], which commits once on release instead of once per pixel.
 */
@Composable
internal fun rememberAppearance():Pair<AppearanceSettings,AppearanceController> {
    val context=LocalContext.current
    val controller=remember(context.applicationContext){AppearanceController(context)}
    var settings by remember(controller){mutableStateOf(controller.read())}
    DisposableEffect(controller) {
        val listener=SharedPreferences.OnSharedPreferenceChangeListener { _,_->settings=controller.read() }
        controller.listen(listener);onDispose{controller.stop(listener)}
    }
    return settings to controller
}

/** WCAG contrast ratio; the single helper shared by the scheme builder and the JVM contrast tests. */
internal fun contrastRatio(a:Color,b:Color):Float {
    val first=a.luminance();val second=b.luminance()
    return (maxOf(first,second)+.05f)/(minOf(first,second)+.05f)
}

private val inkInk=Color(0xFF10131B)
private val inkGray=Color(0xFF757575)

/**
 * Ink colour for [background], chosen by measured contrast.
 *
 * Replaces the old `if(dark>=light)` test. Those two numbers were not both real contrast ratios: the
 * "light" branch was only the ratio of white over the background when white happened to be the lighter
 * side, so the comparison could pick white for a mid-tone or light background. Here every candidate gets
 * its own `contrastRatio(candidate, background)` and the highest wins, so a light background always
 * gets the dark ink and a dark background always gets white.
 *
 * Guarantee (asserted by ThemeContrastV070Test over a luminance sweep): whenever any of
 * {ink, gray, black, white} reaches 4.5:1 against [background], the returned colour reaches 4.5:1 as
 * well. Pure black/white are only reachable when nothing else does better, so the previous visual
 * result is preserved everywhere it was already correct.
 */
internal fun readableOn(background:Color):Color {
    var best=inkInk;var bestRatio=contrastRatio(inkInk,background)
    for(candidate in arrayOf(inkGray,Color.Black,Color.White)) {
        val ratio=contrastRatio(candidate,background)
        if(ratio>bestRatio){best=candidate;bestRatio=ratio}
    }
    return best
}

/** A single ink colour readable on EVERY one of [backgrounds] (page gradient + cards on top of it). */
internal fun readableOnIn(backgrounds:List<Color>,target:Float=4.5f):Color {
    var best=Color.White;var bestWorst=0f
    for(candidate in arrayOf(inkInk,inkGray,Color.Black,Color.White)) {
        var worst=Float.MAX_VALUE
        for(background in backgrounds)worst=minOf(worst,contrastRatio(candidate,background))
        if(worst>bestWorst){best=candidate;bestWorst=worst}
    }
    return best
}

/**
 * Moves [color] toward [toward] until it reaches [target] against every [surfaces] colour.
 *
 * Bounded: at most [steps] lerps of 10 %, then gives up and returns the last candidate. The contrast
 * tests cover every style/dark/seed/pureBlack combination, so a give-up would be caught there rather
 * than silently shipped. This is the "repair" step task-3 asks for instead of lowering the target.
 */
private fun repairContrast(color:Color,toward:Color,surfaces:List<Color>,target:Float,steps:Int=24):Color {
    var current=color
    if(surfaces.all{contrastRatio(current,it)>=target})return current
    repeat(steps) {
        current=lerp(current,toward,.1f)
        if(surfaces.all{contrastRatio(current,it)>=target})return current
    }
    return current
}

/**
 * Native Material 3 roles with four visible color styles; no Flutter code or shaders are used.
 *
 * Allocation: exactly one [ColorScheme] (the `.copy()` result); the four repair/a11y helpers allocate a
 * new Color only for the roles that actually fail their ratio, which for the built-in palette is only
 * `error`/`onError` (mid-tone Material red on a near-white surface). No local lambdas or captures per
 * call, so a call is safe to keep inside a composition `remember`.
 *
 * Contrast guarantees (proven for dark x 9 seeds x 4 styles x pureBlack by ThemeContrastV070Test):
 *  - primary/onPrimary, secondary/onSecondary, surface/onSurface >= 4.5 (unchanged behaviour, now also
 *    covered for every style including monochrome)
 *  - onSurfaceVariant >= 4.5 against BOTH surface and background (secondary text is 11sp = normal text)
 *  - error >= 4.5 against surface, onError >= 4.5 against error
 * Palette character is untouched: primary, seed swatches, background and the four styles keep the same
 * math; only onSurfaceVariant and error/onError may be nudged when a combination would fail AA.
 */
internal fun appearanceScheme(settings:AppearanceSettings,dark:Boolean,wallpaper:ColorScheme?):ColorScheme {
    if(settings.colorStyle=="neutral")return neutralAppearanceScheme(dark,settings.pureBlack)
    val seed=Color(settings.seed)
    val monochrome=settings.colorStyle=="monochrome"
    val neutral=if(dark)Color(0xFF101114) else Color(0xFFF7F8FC)
    val paper=if(dark)Color(0xFF1B1D23) else Color.White
    val text=if(dark)Color(0xFFE7E9F0) else Color(0xFF19202C)
    var primary=when {
        monochrome -> if(dark)Color(0xFFE4E5E8) else Color(0xFF33363C)
        wallpaper!=null -> if(settings.colorStyle=="soft")lerp(wallpaper.primary,wallpaper.onSurfaceVariant,.22f) else wallpaper.primary
        settings.colorStyle=="soft" -> lerp(lerp(seed,Color(0xFF80828B),.28f),if(dark)Color.White else Color.Black,if(dark).4f else .2f)
        dark -> lerp(seed,Color.White,.48f)
        else -> lerp(seed,Color.Black,.13f)
    }
    // Guard custom/wallpaper seeds against unreadable accent text on the page background. `neutral` is
    // constant per theme, so its luminance is computed once instead of six times per iteration.
    val neutralLuminance=neutral.luminance()
    repeat(12) {
        val proportion=(maxOf(primary.luminance(),neutralLuminance)+.05f)/(minOf(primary.luminance(),neutralLuminance)+.05f)
        if(proportion<4.6f)primary=lerp(primary,if(dark)Color.White else Color.Black,.13f)
    }
    val tint=when(settings.colorStyle){"tinted"->.085f;"soft"->.025f;else->0f}
    // OLED 纯黑（2026-11-07 产品决策）：只把页面背景压成纯黑，卡片 / 输入框 / 各层容器仍保留
    // 可区分的深灰层级，不把整个界面抹成同一块黑。pureBlack 不再参与 surface / container 的计算。
    val oled=dark&&settings.pureBlack
    val background=if(oled)Color.Black else if(monochrome)neutral else lerp(neutral,primary,tint)
    val surface=if(monochrome)paper else lerp(paper,primary,tint*.65f)
    // Page title and card body must agree on which side is darker: one ink for background AND surface.
    val onSurface=readableOnIn(listOf(surface,background))
    // Secondary text (`Muted`) is bodySmall 11sp/17sp, i.e. normal-size text: AA needs 4.5:1 against the
    // card it sits on AND the page gradient behind the cards. Only moved toward onSurface when needed.
    val variant=repairContrast(if(dark)Color(0xFFADB3C0) else Color(0xFF586174),onSurface,listOf(surface,background),4.5f)
    val secondary=if(monochrome)primary else if(wallpaper!=null)wallpaper.secondary else lerp(primary,if(dark)Color(0xFFC8A6E4) else Color(0xFF79559B),.35f)
    val base=if(dark)darkColorScheme() else lightColorScheme()
    // error is destructive text on `surface` and, as a button/badge fill, carries onError. The Material
    // default red is kept unless it misses 4.5 against this style's surface; repair moves it away from
    // the surface (darker on a light surface, lighter on a dark one), preserving the red hue.
    val away=if(dark)Color.White else Color.Black
    val error=repairContrast(base.error,away,listOf(surface),4.5f)
    val onError=repairContrast(readableOn(error),away,listOf(error),4.5f)
    return base.copy(
        primary=primary,onPrimary=readableOn(primary),
        primaryContainer=lerp(surface,primary,if(dark).2f else .12f),onPrimaryContainer=onSurface,
        secondary=secondary,onSecondary=readableOn(secondary),
        secondaryContainer=lerp(surface,secondary,.14f),onSecondaryContainer=onSurface,
        tertiary=if(dark)Color(0xFF7ED6B0) else Color(0xFF216B4D),
        background=background,onBackground=onSurface,surface=surface,onSurface=onSurface,
        surfaceVariant=lerp(surface,onSurface,.045f),onSurfaceVariant=variant,
        surfaceContainerLowest=background,surfaceContainerLow=surface,
        surfaceContainer=lerp(surface,onSurface,.025f),
        surfaceContainerHigh=lerp(surface,onSurface,.055f),
        surfaceContainerHighest=lerp(surface,onSurface,.08f),
        outline=if(dark)Color(0xFF778090) else Color(0xFF747E8F),
        outlineVariant=if(dark)Color(0xFF363B46) else Color(0xFFDCE0EA),
        surfaceTint=primary,
        error=error,onError=onError,
    )
}

internal fun appearanceFont(settings:AppearanceSettings)=when(settings.font){"sans"->FontFamily.SansSerif;"serif"->FontFamily.Serif;"mono"->FontFamily.Monospace;else->FontFamily.Default}

@Composable
fun AppearanceDialog(onDismiss:()->Unit) = AppearanceSettingsScreen(onDismiss)

@Composable
internal fun ChoiceRows(options:List<Pair<String,String>>,selected:String,onSelect:(String)->Unit) {
    options.chunked(if(options.size==3)3 else 2).forEach { row->
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            row.forEach{(value,label)->FilterChip(selected=selected==value,onClick={onSelect(value)},label={Text(label)},modifier=Modifier.weight(1f).heightIn(min=48.dp))}
        }
    }
}

/**
 * Switch row: text column keeps its weight so the description wraps instead of being squeezed, and the
 * row is at least a 48dp target. Sizes are unchanged — the row gets taller, the font never gets smaller.
 */
@Composable
internal fun SettingSwitch(title:String,description:String,checked:Boolean,onChecked:(Boolean)->Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min=48.dp).padding(vertical=8.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
        Column(Modifier.weight(1f)){Text(title,style=MaterialTheme.typography.bodyLarge);Text(description,style=MaterialTheme.typography.bodySmall,color=Muted)}
        Switch(checked=checked,onCheckedChange=onChecked,modifier=Modifier.semantics{contentDescription=title})
    }
}
