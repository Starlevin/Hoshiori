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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
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

/** Native Material 3 roles with four visible color styles; no Flutter code or shaders are used. */
internal fun appearanceScheme(settings:AppearanceSettings,dark:Boolean,wallpaper:ColorScheme?):ColorScheme {
    val seed=Color(settings.seed)
    val monochrome=settings.colorStyle=="monochrome"
    val neutral=if(dark)Color(0xFF101114) else Color(0xFFF7F8FC)
    val paper=if(dark)Color(0xFF1B1D23) else Color.White
    val text=if(dark)Color(0xFFE7E9F0) else Color(0xFF19202C)
    val variant=if(dark)Color(0xFFADB3C0) else Color(0xFF586174)
    var primary=when {
        monochrome -> if(dark)Color(0xFFE4E5E8) else Color(0xFF33363C)
        wallpaper!=null -> if(settings.colorStyle=="soft")lerp(wallpaper.primary,wallpaper.onSurfaceVariant,.22f) else wallpaper.primary
        settings.colorStyle=="soft" -> lerp(lerp(seed,Color(0xFF80828B),.28f),if(dark)Color.White else Color.Black,if(dark).4f else .2f)
        dark -> lerp(seed,Color.White,.48f)
        else -> lerp(seed,Color.Black,.13f)
    }
    // Guard custom/wallpaper seeds against unreadable accent text on the page background.
    repeat(12) {
        val ratio=(maxOf(primary.luminance(),neutral.luminance())+.05f)/(minOf(primary.luminance(),neutral.luminance())+.05f)
        if(ratio<4.6f)primary=lerp(primary,if(dark)Color.White else Color.Black,.13f)
    }
    val tint=when(settings.colorStyle){"tinted"->.085f;"soft"->.025f;else->0f}
    val black=dark&&settings.pureBlack
    val background=if(black)Color.Black else if(monochrome)neutral else lerp(neutral,primary,tint)
    val surface=if(black)Color(0xFF101010) else if(monochrome)paper else lerp(paper,primary,tint*.65f)
    val secondary=if(monochrome)primary else if(wallpaper!=null)wallpaper.secondary else lerp(primary,if(dark)Color(0xFFC8A6E4) else Color(0xFF79559B),.35f)
    val base=if(dark)darkColorScheme() else lightColorScheme()
    return base.copy(
        primary=primary,onPrimary=readableOn(primary),
        primaryContainer=lerp(surface,primary,if(dark).2f else .12f),onPrimaryContainer=text,
        secondary=secondary,onSecondary=readableOn(secondary),
        secondaryContainer=lerp(surface,secondary,.14f),onSecondaryContainer=text,
        tertiary=if(dark)Color(0xFF7ED6B0) else Color(0xFF216B4D),
        background=background,onBackground=text,surface=surface,onSurface=text,
        surfaceVariant=lerp(surface,text,.045f),onSurfaceVariant=variant,
        surfaceContainerLowest=background,surfaceContainerLow=surface,
        surfaceContainer=lerp(surface,text,.025f),surfaceContainerHigh=lerp(surface,text,.055f),
        surfaceContainerHighest=lerp(surface,text,.08f),
        outline=if(dark)Color(0xFF778090) else Color(0xFF747E8F),
        outlineVariant=if(dark)Color(0xFF363B46) else Color(0xFFDCE0EA),
        surfaceTint=primary,
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
    GlassDialog(title="外观与主题",subtitle="即时预览 · 只影响本机界面",onDismiss=onDismiss) {
        Text("液态玻璃",style=MaterialTheme.typography.titleMedium)
        ChoiceRows(listOf("standard" to "标准","light" to "轻量","off" to "关闭"),settings.glassMode){update(settings.copy(glassMode=it))}
        Text("标准使用真实页面背景采样；低内存、省电及辅助功能模式自动回退。",style=MaterialTheme.typography.bodySmall,color=Muted)
        Text("主题模式",style=MaterialTheme.typography.titleMedium)
        ChoiceRows(listOf("system" to "跟随系统","light" to "浅色","dark" to "深色"),settings.mode){update(settings.copy(mode=it))}
        SettingSwitch("纯黑背景","在深色模式下使用 OLED 黑色背景",settings.pureBlack){update(settings.copy(pureBlack=it))}
        DividerLine(Modifier.padding(vertical=12.dp))
        Text("主题色",style=MaterialTheme.typography.titleMedium)
        if(Build.VERSION.SDK_INT>=31)SettingSwitch("壁纸动态取色","从 Android 系统配色中选择主色",settings.dynamicColor){update(settings.copy(dynamicColor=it))}
        val swatches=listOf(0xFF4263C7,0xFF7553A8,0xFFB4466D,0xFFB6533F,0xFF9D641F,0xFF24795D,0xFF19757A)
        Row(Modifier.fillMaxWidth().padding(vertical=10.dp),horizontalArrangement=Arrangement.SpaceBetween) {
            swatches.forEach { argb->
                val value=argb.toInt()
                Box(Modifier.size(32.dp).background(Color(value),CircleShape)
                    .border(if(!settings.dynamicColor&&settings.seed==value)3.dp else 0.dp,MaterialTheme.colorScheme.onSurface,CircleShape)
                    .clickable{update(settings.copy(seed=value,dynamicColor=false))})
            }
        }
        if(settings.customColors.isNotEmpty()) {
            Text("最近自定义颜色",style=MaterialTheme.typography.bodySmall,color=Muted)
            settings.customColors.chunked(7).forEach {row->
                Row(Modifier.padding(vertical=8.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    row.forEach{value->Box(Modifier.size(28.dp).background(Color(value),CircleShape).border(1.dp,MaterialTheme.colorScheme.outlineVariant,CircleShape).clickable{update(settings.copy(seed=value,dynamicColor=false))})}
                }
            }
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value=custom,onValueChange={custom=it.take(7);customError=null},label={Text("自定义色 #RRGGBB")},singleLine=true,modifier=Modifier.weight(1f))
            TextButton(onClick={
                val value=custom.removePrefix("#").takeIf{it.matches(Regex("[0-9a-fA-F]{6}"))}?.toLongOrNull(16)
                if(value==null)customError="请输入 6 位十六进制色值" else update(settings.copy(seed=(0xFF000000L or value).toInt(),dynamicColor=false,customColors=(settings.customColors+(0xFF000000L or value).toInt()).distinct().takeLast(8)))
            },modifier=Modifier.padding(top=12.dp)){Text("应用")}
        }
        customError?.let{Text(it,color=MaterialTheme.colorScheme.error)}
        Spacer(Modifier.height(12.dp))
        Text("配色风格",style=MaterialTheme.typography.titleMedium)
        ChoiceRows(listOf("vivid" to "鲜明","tinted" to "色调","soft" to "柔和","monochrome" to "单色"),settings.colorStyle){update(settings.copy(colorStyle=it))}
        DividerLine(Modifier.padding(vertical=12.dp))
        Text("表面层次",style=MaterialTheme.typography.titleMedium)
        ChoiceRows(listOf("none" to "简洁","standard" to "标准","ultra" to "增强"),settings.surfaceEffect){update(settings.copy(surfaceEffect=it))}
        Text("对应玻璃效果的静态层次档位。仅改变表面、边框和高光，不启用实时模糊。",style=MaterialTheme.typography.bodySmall,color=Muted)
        SettingSwitch("导航色彩融合","底部导航使用淡主题色背景",settings.navigationTint){update(settings.copy(navigationTint=it))}
        DividerLine(Modifier.padding(vertical=12.dp))
        SettingSwitch("自定义文字缩放","关闭时跟随系统字体大小",settings.scaleEnabled){update(settings.copy(scaleEnabled=it))}
        if(settings.scaleEnabled) {
            Text("${(settings.textScale*100).toInt()}%",color=Muted)
            Slider(value=settings.textScale,onValueChange={update(settings.copy(textScale=it))},valueRange=.85f..1.4f,steps=10)
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
            TextButton(onClick={update(AppearanceSettings())}){Text("恢复默认")}
            Button(onClick=onDismiss){Text("完成")}
        }
    }
}

@Composable
private fun ChoiceRows(options:List<Pair<String,String>>,selected:String,onSelect:(String)->Unit) {
    options.chunked(if(options.size==3)3 else 2).forEach { row->
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            row.forEach{(value,label)->FilterChip(selected=selected==value,onClick={onSelect(value)},label={Text(label)},modifier=Modifier.weight(1f))}
        }
    }
}

@Composable
private fun SettingSwitch(title:String,description:String,checked:Boolean,onChecked:(Boolean)->Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical=8.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)){Text(title,style=MaterialTheme.typography.bodyLarge);Text(description,style=MaterialTheme.typography.bodySmall,color=Muted)}
        Switch(checked=checked,onCheckedChange=onChecked)
    }
}


/** Choose by measured contrast, not a brightness heuristic that fails on pastel dark accents. */
internal fun readableOn(background:Color):Color {
    val ink=Color(0xFF10131B)
    val light=1.05f/(background.luminance()+.05f)
    val dark=(background.luminance()+.05f)/(ink.luminance()+.05f)
    return if(dark>=light)ink else Color.White
}
