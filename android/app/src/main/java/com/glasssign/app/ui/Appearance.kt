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
    val glassMode:String="standard",val mode:String="system",val pureBlack:Boolean=false,val colorStyle:String="vivid",
    val seed:Int=0xFF4263C7.toInt(),val dynamicColor:Boolean=false,
    val surfaceEffect:String="none",val navigationTint:Boolean=false,
    val scaleEnabled:Boolean=false,val textScale:Float=1f,val font:String="system",val customColors:List<Int> = emptyList(),
)

class AppearanceController(context:Context) {
    private val preferences=context.applicationContext.getSharedPreferences("appearance_v1",Context.MODE_PRIVATE)
    fun read()=AppearanceSettings(
        glassMode=preferences.getString("glass_mode","standard").orEmpty().takeIf{it in listOf("standard","light","off")} ?: "standard",
        mode=preferences.getString("mode","system").orEmpty().takeIf{it in listOf("system","light","dark")} ?: "system",
        pureBlack=preferences.getBoolean("pure_black",false),
        colorStyle=preferences.getString("color_style","vivid").orEmpty().takeIf{it in listOf("vivid","tinted","soft","monochrome")} ?: "vivid",
        seed=preferences.getInt("seed",0xFF4263C7.toInt()),dynamicColor=preferences.getBoolean("dynamic",false),
        surfaceEffect=preferences.getString("surface_effect","none").orEmpty().takeIf{it in listOf("none","standard","ultra")} ?: "none",
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
    // True OLED: when pureBlack is on in dark mode, background AND surfaces are pure
    // black (not the old 0xFF101010 grey), so OLED pixels actually turn off. The page
    // gradient is flattened by the same change (see pageGradientBrush): its two ends
    // become black once primaryContainer/secondaryContainer are black. Only the accent
    // (primary/secondary, selected state, buttons, accent text) keeps the user colour.
    val black=dark&&settings.pureBlack
    val background=if(black)Color.Black else if(monochrome)neutral else lerp(neutral,primary,tint)
    val surface=if(black)Color.Black else if(monochrome)paper else lerp(paper,primary,tint*.65f)
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
        primaryContainer=if(black)Color.Black else lerp(surface,primary,if(dark).2f else .12f),onPrimaryContainer=onSurface,
        secondary=secondary,onSecondary=readableOn(secondary),
        secondaryContainer=if(black)Color.Black else lerp(surface,secondary,.14f),onSecondaryContainer=onSurface,
        tertiary=if(dark)Color(0xFF7ED6B0) else Color(0xFF216B4D),
        background=background,onBackground=onSurface,surface=surface,onSurface=onSurface,
        surfaceVariant=if(black)Color.Black else lerp(surface,onSurface,.045f),onSurfaceVariant=variant,
        surfaceContainerLowest=background,surfaceContainerLow=surface,
        surfaceContainer=if(black)Color.Black else lerp(surface,onSurface,.025f),
        surfaceContainerHigh=if(black)Color.Black else lerp(surface,onSurface,.055f),
        surfaceContainerHighest=if(black)Color.Black else lerp(surface,onSurface,.08f),
        outline=if(dark)Color(0xFF778090) else Color(0xFF747E8F),
        outlineVariant=if(dark)Color(0xFF363B46) else Color(0xFFDCE0EA),
        surfaceTint=primary,
        error=error,onError=onError,
    )
}

internal fun appearanceFont(settings:AppearanceSettings)=when(settings.font){"sans"->FontFamily.SansSerif;"serif"->FontFamily.Serif;"mono"->FontFamily.Monospace;else->FontFamily.Default}

@Composable
fun AppearanceDialog(onDismiss:()->Unit) {
    val settings=LocalAppearance.current
    val controller=LocalAppearanceController.current
    fun update(next:AppearanceSettings){controller?.save(next)}
    var custom by remember(settings.seed){mutableStateOf("#"+settings.seed.toUInt().toString(16).takeLast(6).uppercase())}
    var customError by remember {mutableStateOf<String?>(null)}
    // Draft value while the thumb is moving; the preferences are written once, on release. Before this,
    // every drag pixel ran SharedPreferences.apply() on the main thread, woke the change listener which
    // ran a full AppearanceController.read() over ~12 keys, and rebuilt the whole ColorScheme — for a
    // value that is displayed as an integer percentage.
    var draftScale by remember{mutableStateOf<Float?>(null)}
    GlassDialog(title="外观与主题",subtitle="即时预览 · 只影响本机界面",onDismiss=onDismiss) {
        Text("液态玻璃",style=MaterialTheme.typography.titleMedium)
        ChoiceRows(listOf("standard" to "标准","light" to "轻量","off" to "关闭"),settings.glassMode){update(settings.copy(glassMode=it))}
        Text("标准使用真实页面背景采样；低内存、省电及辅助功能模式自动回退。",style=MaterialTheme.typography.bodySmall,color=Muted)
        Text("主题模式",style=MaterialTheme.typography.titleMedium)
        ChoiceRows(listOf("system" to "跟随系统","light" to "浅色","dark" to "深色"),settings.mode){update(settings.copy(mode=it))}
        SettingSwitch("OLED 纯黑","深色下背景与卡片均为纯黑，页面渐变压平，只有选中、按钮与点缀文字保留主题色",settings.pureBlack){update(settings.copy(pureBlack=it))}
        DividerLine(Modifier.padding(vertical=12.dp))
        Text("主题色",style=MaterialTheme.typography.titleMedium)
        if(Build.VERSION.SDK_INT>=31)SettingSwitch("壁纸动态取色","从 Android 系统配色中选择主色",settings.dynamicColor){update(settings.copy(dynamicColor=it))}
        val swatches=listOf(0xFF4263C7,0xFF7553A8,0xFFB4466D,0xFFB6533F,0xFF9D641F,0xFF24795D,0xFF19757A)
        // The colour disc stays 32dp; the tap target around it grows to 40dp so the old 32dp row of
        // seven circles is not the smallest control in the dialog.
        Row(Modifier.fillMaxWidth().padding(vertical=10.dp),horizontalArrangement=Arrangement.SpaceBetween) {
            swatches.forEach { argb->
                val value=argb.toInt()
                val selected=!settings.dynamicColor&&settings.seed==value
                Box(Modifier.size(40.dp).clickable{update(settings.copy(seed=value,dynamicColor=false))}
                    .semantics{contentDescription="主题色 #"+value.toUInt().toString(16).takeLast(6).uppercase()+(if(selected)"，当前已选" else "")},
                    contentAlignment=Alignment.Center) {
                    Box(Modifier.size(32.dp).background(Color(value),CircleShape)
                        .border(if(selected)3.dp else 0.dp,MaterialTheme.colorScheme.onSurface,CircleShape))
                }
            }
        }
        if(settings.customColors.isNotEmpty()) {
            Text("最近自定义颜色",style=MaterialTheme.typography.bodySmall,color=Muted)
            settings.customColors.chunked(7).forEach {row->
                Row(Modifier.padding(vertical=8.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    row.forEach{value->Box(Modifier.size(38.dp).clickable{update(settings.copy(seed=value,dynamicColor=false))},contentAlignment=Alignment.Center){Box(Modifier.size(28.dp).background(Color(value),CircleShape).border(1.dp,MaterialTheme.colorScheme.outlineVariant,CircleShape))}}
                }
            }
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value=custom,onValueChange={custom=it.take(7);customError=null},label={Text("自定义色 #RRGGBB")},singleLine=true,modifier=Modifier.weight(1f))
            TextButton(onClick={
                val value=custom.removePrefix("#").takeIf{it.matches(Regex("[0-9a-fA-F]{6}"))}?.toLongOrNull(16)
                if(value==null)customError="请输入 6 位十六进制色值" else update(settings.copy(seed=(0xFF000000L or value).toInt(),dynamicColor=false,customColors=(settings.customColors+(0xFF000000L or value).toInt()).distinct().takeLast(8)))
            },modifier=Modifier.heightIn(min=48.dp).padding(top=12.dp)){Text("应用")}
        }
        customError?.let{Text(it,color=MaterialTheme.colorScheme.error)}
        // Real colour picker: RGB sliders with a live preview, not only a hex field.
        // Default seed is unchanged (blue-purple 0xFF4263C7); the seven presets below,
        // including teal 0xFF19757A, are kept as selectable options, and a picked colour
        // is added to the recent-custom list exactly like a hex-typed one.
        Text("颜色选择器（拖动取色）",style=MaterialTheme.typography.bodySmall,color=Muted)
        var pickerRed by remember(settings.seed){mutableStateOf((settings.seed shr 16) and 0xFF)}
        var pickerGreen by remember(settings.seed){mutableStateOf((settings.seed shr 8) and 0xFF)}
        var pickerBlue by remember(settings.seed){mutableStateOf(settings.seed and 0xFF)}
        val pickerColor=Color(pickerRed,pickerGreen,pickerBlue)
        Row(Modifier.fillMaxWidth().padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(40.dp).background(pickerColor,CircleShape).border(1.dp,MaterialTheme.colorScheme.outlineVariant,CircleShape))
            Text("#%02X%02X%02X".format(pickerRed,pickerGreen,pickerBlue),style=MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.weight(1f))
            // CI fix: 0xFF000000 is a Long literal in Kotlin, so `or` with Int shifts
            // failed compileReleaseKotlin at line 286. Build the ARGB value with Int
            // shifts only: 0xFF shl 24 is an Int (negative, as all opaque ARGB ints are).
            val pickerArgb=(0xFF shl 24) or (pickerRed shl 16) or (pickerGreen shl 8) or pickerBlue
            TextButton(onClick={update(settings.copy(seed=pickerArgb,dynamicColor=false,customColors=(settings.customColors+pickerArgb).distinct().takeLast(8)))},modifier=Modifier.heightIn(min=48.dp)){Text("应用此颜色")}
        }
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("红",style=MaterialTheme.typography.bodyMedium,modifier=Modifier.width(20.dp))
            Slider(value=pickerRed.toFloat(),onValueChange={pickerRed=it.toInt()},valueRange=0f..255f,modifier=Modifier.weight(1f))
            Text(pickerRed.toString(),style=MaterialTheme.typography.bodyMedium,modifier=Modifier.width(36.dp))
        }
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("绿",style=MaterialTheme.typography.bodyMedium,modifier=Modifier.width(20.dp))
            Slider(value=pickerGreen.toFloat(),onValueChange={pickerGreen=it.toInt()},valueRange=0f..255f,modifier=Modifier.weight(1f))
            Text(pickerGreen.toString(),style=MaterialTheme.typography.bodyMedium,modifier=Modifier.width(36.dp))
        }
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("蓝",style=MaterialTheme.typography.bodyMedium,modifier=Modifier.width(20.dp))
            Slider(value=pickerBlue.toFloat(),onValueChange={pickerBlue=it.toInt()},valueRange=0f..255f,modifier=Modifier.weight(1f))
            Text(pickerBlue.toString(),style=MaterialTheme.typography.bodyMedium,modifier=Modifier.width(36.dp))
        }
        Spacer(Modifier.height(12.dp))
        Text("配色风格",style=MaterialTheme.typography.titleMedium)
        ChoiceRows(listOf("vivid" to "鲜明","tinted" to "色调","soft" to "柔和","monochrome" to "单色"),settings.colorStyle){update(settings.copy(colorStyle=it))}
        DividerLine(Modifier.padding(vertical=12.dp))
        Text("表面层次",style=MaterialTheme.typography.titleMedium)
        ChoiceRows(listOf("none" to "简洁","standard" to "标准","ultra" to "增强"),settings.surfaceEffect){update(settings.copy(surfaceEffect=it))}
        Text("对应玻璃效果的静态层次档位。仅改变表面、边框和高光，不启用实时模糊。",style=MaterialTheme.typography.bodySmall,color=Muted)
        SettingSwitch("导航色彩融合","底部导航使用淡主题色背景",settings.navigationTint){update(settings.copy(navigationTint=it))}
        DividerLine(Modifier.padding(vertical=12.dp))
        BackgroundSettingsSection()
        DividerLine(Modifier.padding(vertical=12.dp))
        SettingSwitch("自定义文字缩放","关闭时跟随系统字体大小",settings.scaleEnabled){update(settings.copy(scaleEnabled=it))}
        if(settings.scaleEnabled) {
            val shown=draftScale?:settings.textScale
            // Lining/tabular digits keep the three-digit label from changing width mid-drag (proportional
            // numerals make "88%" narrower than "106%", which reflows the row under the thumb).
            Text("${(shown*100).toInt()}%",color=Muted,
                style=MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings="tnum"),
                modifier=Modifier.semantics{contentDescription="文字缩放 ${(shown*100).toInt()}%"})
            Slider(value=shown,onValueChange={draftScale=it},onValueChangeFinished={
                val committed=draftScale;draftScale=null
                if(committed!=null&&committed!=settings.textScale)update(settings.copy(textScale=committed))
            },valueRange=.85f..1.4f,steps=10)
            Text("松手后保存；拖动过程中只更新上面的百分比。",style=MaterialTheme.typography.bodySmall,color=Muted)
        }
        Text("字体",style=MaterialTheme.typography.titleMedium)
        ChoiceRows(listOf("system" to "系统","sans" to "无衬线","serif" to "衬线","mono" to "等宽"),settings.font){update(settings.copy(font=it))}
        Spacer(Modifier.height(12.dp))
        Surface(color=MaterialTheme.colorScheme.primaryContainer,shape=GlassShape) {
            Column(Modifier.padding(16.dp)) {
                Text("星栞",style=MaterialTheme.typography.titleMedium)
                Text("主题预览 · 签到结果保存在本机",style=MaterialTheme.typography.bodyMedium)
            }
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            TextButton(onClick={draftScale=null;update(AppearanceSettings())},modifier=Modifier.heightIn(min=48.dp)){Text("恢复默认")}
            Button(onClick=onDismiss,modifier=Modifier.heightIn(min=48.dp)){Text("完成")}
        }
    }
}

@Composable
private fun ChoiceRows(options:List<Pair<String,String>>,selected:String,onSelect:(String)->Unit) {
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
private fun SettingSwitch(title:String,description:String,checked:Boolean,onChecked:(Boolean)->Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min=48.dp).padding(vertical=8.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
        Column(Modifier.weight(1f)){Text(title,style=MaterialTheme.typography.bodyLarge);Text(description,style=MaterialTheme.typography.bodySmall,color=Muted)}
        Switch(checked=checked,onCheckedChange=onChecked,modifier=Modifier.semantics{contentDescription=title})
    }
}
