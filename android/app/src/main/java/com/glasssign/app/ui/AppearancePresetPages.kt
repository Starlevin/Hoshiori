package com.glasssign.app.ui

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/*
 * AppearancePresetPages.kt — 契约第 3 节 B 组：主题预设页 + 主题色页。
 *
 * 设计要点：
 *  - 「风格优先」：经典黑白（colorStyle=neutral）固定第一个、也是默认；深蓝/柔紫是可选风格。
 *  - 每张预设卡都有纯 Compose 静态绘制的微型预览：颜色来自实际主题配色函数，
 *    刻意**不读 MaterialTheme**，因为预览要展示「选它之后会变成什么样」，而当前主题可能还是别的风格。
 *  - 主题色页的所有写入口（预设色 / 最近使用 / HEX / RGB）统一走 presetApplyColor，
 *    它保证 neutral / soft 下选色会自动切到 vivid，避免「点了颜色没反应」。
 *  - 只新建本文件：不引用 QCard / QSwitch；同包只用 ChoiceRows / SettingSwitch / RgbColorPicker /
 *    GlassShape / LocalAppearance / LocalAppearanceController / HoshioriIcons.* + Icons.Rounded.Check。
 */

// ---------------------------------------------------------------------------------------------
// 颜色常量：调色板控件的常量（实际配色与缩略预览共用 appearanceScheme）
// ---------------------------------------------------------------------------------------------

private val PresetBadgeInk = Color(0xFF10131B)
private val PresetBadgePaper = Color(0xFFFFFFFF)

/** 预设卡片固定宽度：156*2 + 12 间距 + 32 边距 = 356，恰好放进 360dp 窄屏；更大屏由 FlowRow 换行。 */

/** 主题色页的 9 个预设色（7 个原有色 + 2 个中性）。 */
private val PresetSwatches: List<Int> = listOf(
    0xFF4263C7.toInt(),
    0xFF7553A8.toInt(),
    0xFFB4466D.toInt(),
    0xFFB6533F.toInt(),
    0xFF9D641F.toInt(),
    0xFF24795D.toInt(),
    0xFF19757A.toInt(),
    0xFF33363C.toInt(),
    0xFF747E8F.toInt(),
)

private val PresetNeutralSwatches: Set<Int> = setOf(0xFF33363C.toInt(), 0xFF747E8F.toInt())

// ---------------------------------------------------------------------------------------------
// 小工具
// ---------------------------------------------------------------------------------------------

/** 写色统一入口之外的三个纯函数小工具（全部 private，避免与 A/C 撞名）。 */
private fun presetHex(argb: Int) = "#%06X".format(argb and 0xFFFFFF)

/** 浅化：往白里 lerp，用于「淡色底」。 */
private fun presetInkOn(background: Color): Color =
    if (background.luminance() > .45f) PresetBadgeInk else PresetBadgePaper

/** 解析用户输入的 HEX（允许 # 前缀、3/6 位），合法返回不透明的 ARGB，否则 null。 */
private fun presetParseHex(raw: String): Int? {
    val cleaned = raw.trim().removePrefix("#").removePrefix("＃").uppercase()
    if (cleaned.length == 3) {
        val expanded = cleaned.map { "$it$it" }.joinToString("")
        return expanded.toLongOrNull(16)?.let { 0xFF000000L or it }?.toInt()
    }
    if (cleaned.length != 6) return null
    val value = cleaned.toLongOrNull(16) ?: return null
    return (0xFF000000L or value).toInt()
}

/**
 * 统一写色入口（契约「关键规则」）。
 *
 * neutral / soft 下当前样式几乎不体现色相（neutral 不用 seed，soft 会用 lerp 把色相洗掉），
 * 所以选任何颜色都同时切到 `vivid`，让所选颜色立刻生效；其它样式保持原样式不变。
 * 每次都把颜色记进 `customColors`（去重、保留最近 8 个）。
 */
private fun presetApplyColor(
    settings: AppearanceSettings,
    update: (AppearanceSettings) -> Unit,
    argb: Int,
) {
    val resolveStyle = if (settings.colorStyle in setOf("neutral", "monochrome", "soft")) "vivid" else settings.colorStyle
    update(
        settings.copy(
            colorStyle = resolveStyle,
            seed = argb,
            dynamicColor = false,
            customColors = (settings.customColors + argb).distinct().takeLast(8),
        )
    )
}

/** 当前风格的中文名（总览/预设页顶部共用文案口径）。 */
private fun presetStyleName(settings: AppearanceSettings): String = when {
    settings.colorStyle == "neutral" -> "经典黑白"
    settings.colorStyle == "tinted" && settings.seed == 0xFF4263C7.toInt() && !settings.dynamicColor -> "深蓝"
    settings.colorStyle == "soft" -> "柔紫"
    settings.dynamicColor -> "跟随壁纸"
    settings.colorStyle == "tinted" -> "深蓝 · 自定义色"
    else -> "自选"
}

/** 当前风格的一句副标题（顶部说明卡第二行）。 */
private fun presetStyleCaption(settings: AppearanceSettings): String = when {
    settings.colorStyle == "neutral" -> "中性黑白 · 默认风格，无彩色"
    settings.colorStyle == "tinted" && settings.seed == 0xFF4263C7.toInt() && !settings.dynamicColor -> "冷调蓝 · 可选风格"
    settings.colorStyle == "soft" -> "低饱和紫 · 可选风格"
    settings.dynamicColor -> "系统动态取色 · 主色来自壁纸"
    else -> "使用你当前的主题色 ${presetHex(settings.seed)}"
}

// ---------------------------------------------------------------------------------------------
// 统一的小组件（本文件 private）
// ---------------------------------------------------------------------------------------------

/**
 * 页内说明行。宿主顶栏已经显示分区标题，这里不再重复大标题，只保留一句说明。
 */
@Composable
private fun PresetPageHeader(subtitle: String) {
    Text(
        subtitle,
        modifier = Modifier.fillMaxWidth(),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 分组标题（在卡片外面）：labelMedium + Muted，与总览页的分组标题一致。 */
@Composable
private fun PresetGroupTitle(title: String, modifier: Modifier = Modifier) {
    Text(
        title,
        modifier = modifier.padding(start = 4.dp, bottom = 2.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 分组卡片：Surface(GlassShape) + surface 底 + 半透明 outlineVariant 描边 + 16dp 内边距。 */
@Composable
private fun PresetGroup(title: String? = null, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        if (title != null) PresetGroupTitle(title, Modifier.fillMaxWidth())
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = GlassShape,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f)),
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                content()
            }
        }
    }
}

/** 通用说明行：小圆点 + bodySmall，用于分组内的一句提示（不依赖任何外部组件）。 */
@Composable
private fun PresetNote(text: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier
                .padding(top = 6.dp)
                .size(4.dp)
                .background(MaterialTheme.colorScheme.outline, CircleShape)
        )
        Text(
            text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 开关行：标题 + 说明（weight 1f 换行）+ Switch，整行至少 56dp、48dp 触摸目标。 */
@Composable
private fun PresetSwitchRow(title: String, description: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClickLabel = title) { onChecked(!checked) }
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            Switch(checked = checked, onCheckedChange = onChecked, modifier = Modifier.semantics { contentDescription = title })
        }
    }
}

// ---------------------------------------------------------------------------------------------
// 微型静态预览
// ---------------------------------------------------------------------------------------------

/** 一套迷你预览配色。故意是普通 data class（不是 @Composable 计算的 MaterialTheme 值）。 */
private data class PresetMiniPalette(
    val page: Color,
    val card: Color,
    val accent: Color,
    val line: Color,
    val text: Color,
    val border: Color,
)

/** Static preview uses the actual theme palette in the current light/dark mode. */
private fun presetMiniPalette(preset: String, dark: Boolean, seed: Int): PresetMiniPalette {
    val selectedSeed=when(preset){"tinted"->0xFF4263C7.toInt();"soft"->0xFF7553A8.toInt();else->seed}
    val settings=AppearanceSettings(colorStyle=if(preset=="wallpaper")"neutral" else preset,seed=selectedSeed)
    val scheme=appearanceScheme(settings,dark,null)
    return PresetMiniPalette(scheme.background,scheme.surface,scheme.primary,scheme.onSurface,
        scheme.onSurfaceVariant,scheme.outlineVariant)
}

/**
 * 迷你「页面片段」：外层背景色块 + 一块卡片 + 强调条 + 小标题条 + 两根正文条。
 *
 * 全部是 Box + background，没有任何图片/Shader/动画，纯静态。高度 96dp 左右，
 * 内层尺寸用 dp 写死，所以放大字号也不会把预览撑变形（预览本身不含文字）。
 */
@Composable
private fun PresetMiniPreview(palette: PresetMiniPalette, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(96.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(palette.page)
            .border(1.dp, palette.border, RoundedCornerShape(12.dp))
            .padding(10.dp)
    ) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // 迷你页头的标题条（与卡片同色，但更短，读起来像「页面标题」）
            Box(
                Modifier
                    .fillMaxWidth(.62f)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(palette.line)
            )
            // 迷你卡片
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(palette.card)
                    .border(1.dp, palette.border, RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 8.dp)
            ) {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    // 强调条：primary 色，32dp x 5dp 圆角
                    Box(
                        Modifier
                            .width(32.dp)
                            .height(5.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(palette.accent)
                    )
                    // 小标题条（12dp 宽的那根）
                    Box(
                        Modifier
                            .width(12.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(palette.line)
                    )
                    // 两根更细的正文条
                    Box(
                        Modifier
                            .fillMaxWidth(.74f)
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(palette.text.copy(alpha = .55f))
                    )
                    Box(
                        Modifier
                            .fillMaxWidth(.48f)
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(palette.text.copy(alpha = .38f))
                    )
                }
            }
        }
    }
}

/**
 * 一张预设卡：微型预览 + 标签 + 描述 + 选中徽标/「当前已选」行。
 *
 * 选中态用卡片外的 2dp primary 描边 + 右上角 16dp Check 徽标表达；未选中用 1dp outlineVariant。
 * 整卡可点击，`heightIn(min = 120.dp)` 而不是固定高度，放大字号时只会变高不会裁切。
 */
@Composable
private fun PresetCard(
    label: String,
    description: String,
    palette: PresetMiniPalette,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val borderColor = when {
        selected -> scheme.primary
        else -> scheme.outlineVariant
    }
    val borderWidth = if (selected) 2.dp else 1.dp
    val contentAlpha = if (enabled) 1f else .55f
    val descriptionText = if (enabled) description else "$description · 需要 Android 12+"
    Column(
        modifier
            .fillMaxWidth()
            .heightIn(min = 120.dp)
            .clip(GlassShape)
            .background(scheme.surface)
            .border(borderWidth, borderColor, GlassShape)
            .clickable(enabled = enabled, onClickLabel = label, onClick = onClick)
            .padding(10.dp)
            .semantics {
                contentDescription = "主题预设 $label，$description，${if (selected) "当前已选" else "未选中"}${if (enabled) "" else "，不可用"}"
            },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.fillMaxWidth()) {
            PresetMiniPreview(palette)
            if (selected) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(16.dp)
                        .background(scheme.primary, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.Check,
                        contentDescription = null,
                        modifier = Modifier.size(11.dp),
                        tint = scheme.onPrimary,
                    )
                }
            }
        }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.titleSmall,
                color = scheme.onSurface.copy(alpha = contentAlpha),
            )
            Text(
                descriptionText,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant.copy(alpha = contentAlpha),
            )
        }

    }
}

// ---------------------------------------------------------------------------------------------
// 页面 1：主题预设
// ---------------------------------------------------------------------------------------------

/** 一个预设的定义：id 与 [PresetCard] 一一对应（id 只用于配色与选中判断，不是 colorStyle 字符串以外的新枚举）。 */
private data class PresetOption(
    val id: String,
    val label: String,
    val description: String,
)

private val PresetOptions = listOf(
    PresetOption("neutral", "经典黑白", "无彩色 · 默认"),
    PresetOption("tinted", "深蓝", "冷调蓝 · 可选"),
    PresetOption("soft", "柔紫", "低饱和紫 · 可选"),
    PresetOption("vivid", "自选", "使用当前主题色"),
    PresetOption("wallpaper", "跟随壁纸", "系统动态取色"),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AppearancePresetsPage() {
    val settings = LocalAppearance.current
    val controller = LocalAppearanceController.current
    fun update(next: AppearanceSettings) { controller?.save(next) }

    val dynamicSupported = Build.VERSION.SDK_INT >= 31
    val selectedId = presetSelectedId(settings)
    /*
     * Which side of the mini preview to draw. Keyed on the resolved page background, not on
     * `settings.mode`: with 跟随系统 the mode is still "system" while the app can be dark, and that
     * is exactly the case a light preview would misrepresent.
     */
    val darkPreview = MaterialTheme.colorScheme.background.luminance() < .5f

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PresetPageHeader(
            subtitle = "选择整体配色，浅色和深色可独立设置。",
        )

        // ---- 当前风格说明卡（风格优先：第一眼就说明默认是经典黑白） ----
        PresetStyleSummary(settings)

        // Two equal columns use the available width; selection does not change card height.
        PresetGroupTitle("风格", Modifier.fillMaxWidth())
        PresetOptions.chunked(2).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                row.forEach { option ->
                    val enabled = option.id != "wallpaper" || dynamicSupported
                    val selected = option.id == selectedId
                    PresetCard(
                        modifier = Modifier.weight(1f),
                        label = option.label,
                        description = option.description,
                        palette = remember(option.id,darkPreview,settings.seed) {
                            presetMiniPalette(option.id,darkPreview,settings.seed)
                        },
                        selected = selected,
                        enabled = enabled,
                        onClick = {
                            when (option.id) {
                                // 经典黑白：只动 colorStyle / dynamicColor，保留 seed 与 customColors
                                "neutral" -> update(settings.copy(colorStyle = "neutral", dynamicColor = false))
                                "tinted" -> update(
                                    settings.copy(
                                        colorStyle = "tinted",
                                        seed = 0xFF4263C7.toInt(),
                                        dynamicColor = false,
                                    )
                                )
                                "soft" -> update(
                                    settings.copy(
                                        colorStyle = "soft",
                                        seed = 0xFF7553A8.toInt(),
                                        dynamicColor = false,
                                    )
                                )
                                "vivid" -> update(settings.copy(colorStyle = "vivid", dynamicColor = false))
                                "wallpaper" -> update(settings.copy(colorStyle = "vivid", dynamicColor = true))
                                else -> Unit
                            }
                        },
                    )
                }
                if(row.size==1) Spacer(Modifier.weight(1f))
            }
        }
        PresetNote("深蓝、柔紫是可选风格，随时可以点回经典黑白；换风格不会清除你的页面背景。")

        // ---- 明暗模式 ----
        PresetGroup(title = "明暗模式") {
            Text(
                "决定页面是浅色还是深色纸面，和上面的风格独立。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PresetModeChip("跟随系统", HoshioriIcons.System, "system", settings.mode, Modifier.weight(1f)) { value ->
                    update(settings.copy(mode = value))
                }
                PresetModeChip("浅色", HoshioriIcons.Sun, "light", settings.mode, Modifier.weight(1f)) { value ->
                    update(settings.copy(mode = value))
                }
                PresetModeChip("深色", HoshioriIcons.Moon, "dark", settings.mode, Modifier.weight(1f)) { value ->
                    update(settings.copy(mode = value))
                }
            }
        }

        // ---- OLED 纯黑 ----
        PresetGroup(title = "屏幕") {
            PresetSwitchRow(
                title = "OLED 纯黑",
                description = "仅深色模式生效：页面与卡片使用纯黑；自选图片背景仍按背景设置显示。",
                checked = settings.pureBlack,
                onChecked = { checked -> update(settings.copy(pureBlack = checked)) },
            )
        }

        // ---- 底部说明 ----
        Text(
            "默认样式为经典黑白；深蓝、柔紫为可选风格，可随时切回。",
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 当前选中预设的 id；深蓝要靠 seed 判断，避免「自选」用了同色时两边同时高亮。 */
private fun presetSelectedId(settings: AppearanceSettings): String = when {
    settings.colorStyle == "neutral" && !settings.dynamicColor -> "neutral"
    settings.colorStyle == "tinted" && settings.seed == 0xFF4263C7.toInt() && !settings.dynamicColor -> "tinted"
    settings.colorStyle == "soft" && settings.seed == 0xFF7553A8.toInt() && !settings.dynamicColor -> "soft"
    settings.dynamicColor -> "wallpaper"
    else -> "vivid"
}

/** 顶部「当前风格」说明卡：风格名 + 风格说明 + 一句引导。 */
@Composable
private fun PresetStyleSummary(settings: AppearanceSettings) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = GlassShape,
        color = scheme.surface,
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = .5f)),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(36.dp)
                        .background(scheme.primaryContainer, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(HoshioriIcons.Appearance, contentDescription = null, modifier = Modifier.size(20.dp), tint = scheme.onPrimaryContainer)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "当前风格：${presetStyleName(settings)}",
                        style = MaterialTheme.typography.titleMedium,
                        color = scheme.onSurface,
                    )
                    Text(
                        presetStyleCaption(settings),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                "强调色可在「主题色」中进一步调整。",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 明暗模式选项：FilterChip + 图标，文字按内容换行（Chip 的 label 默认不换行，
 * 这里用 Text 的默认 softWrap 并给足够高度）；每项 `heightIn(min = 48.dp)`。
 */
@Composable
private fun PresetModeChip(
    label: String,
    icon: ImageVector,
    value: String,
    selected: String,
    modifier: Modifier = Modifier,
    onSelect: (String) -> Unit,
) {
    FilterChip(
        selected = selected == value,
        onClick = { onSelect(value) },
        modifier = modifier.heightIn(min = 48.dp).semantics { contentDescription = "明暗模式 $label" },
        label = {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 2,
                overflow = TextOverflow.Visible,
            )
        },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
    )
}

// ---------------------------------------------------------------------------------------------
// 页面 2：主题色
// ---------------------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AppearanceColorsPage() {
    val settings = LocalAppearance.current
    val controller = LocalAppearanceController.current
    fun update(next: AppearanceSettings) { controller?.save(next) }
    // 所有写色都通过这一个入口：neutral / soft 下自动切 vivid
    fun applyColor(argb: Int) = presetApplyColor(settings, ::update, argb)

    // HEX 草稿：只在这里保存输入过程，点「应用」才写偏好
    var hexDraft by rememberSaveable { mutableStateOf("") }
    val hexValue = remember(hexDraft) { presetParseHex(hexDraft) }
    val hexTouched = hexDraft.isNotBlank()
    val hexError = hexTouched && hexValue == null

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PresetPageHeader(
            subtitle = "选一个主色。换色只改变强调色与浅底色调，不动页面背景。",
        )

        // ---- 当前颜色条 ----
        PresetCurrentColorBar(settings)

        // ---- 1. 预设色 ----
        PresetGroup(title = "预设色") {
            Text(
                "点一下立刻应用。最后两个是中性灰，其余是主题预设色。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PresetSwatches.forEach { argb ->
                    PresetSwatch(
                        argb = argb,
                        selected = settings.colorStyle !in setOf("neutral","monochrome") && !settings.dynamicColor && settings.seed == argb,
                        onClick = { applyColor(argb) },
                    )
                }
            }
        }

        // ---- 2. 最近使用 ----
        PresetGroup(title = "最近使用") {
            val recents = settings.customColors.asReversed()
            if (recents.isEmpty()) {
                Text(
                    "使用下面的 HEX 或 RGB 应用自定义色后，会出现在这里。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    recents.forEach { argb ->
                        PresetSwatch(
                            argb = argb,
                            selected = settings.colorStyle !in setOf("neutral","monochrome") && !settings.dynamicColor && settings.seed == argb,
                            onClick = { applyColor(argb) },
                        )
                    }
                }
            }
            PresetNote("最近使用只在本机保存，最多 8 个，不会上传。")
        }

        // ---- 3. HEX 输入 ----
        PresetGroup(title = "自定义 HEX") {
            OutlinedTextField(
                value = hexDraft,
                onValueChange = { input -> hexDraft = input.trim().take(7) },
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "自定义色 HEX 输入框" },
                singleLine = true,
                label = { Text("自定义色 #RRGGBB") },
                placeholder = { Text("#1E88E5") },
                isError = hexError,
                supportingText = {
                    when {
                        hexError -> Text("请输入 6 位十六进制色值")
                        hexValue != null -> Text("将应用 ${presetHex(hexValue)}")
                        else -> Text("支持 6 位色值，例如 #4263C7")
                    }
                },
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = {
                        val parsed = hexValue ?: return@Button
                        applyColor(parsed)
                        hexDraft = ""
                    },
                    enabled = hexValue != null,
                    modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "应用 HEX 颜色" },
                ) {
                    Text("应用")
                }
                if (hexValue != null) {
                    Box(
                        Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(Color(hexValue))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                    )
                }
            }
        }

        // ---- 4. RGB 调色（复用已有组件，自带「应用此颜色」显式提交） ----
        PresetGroup(title = "RGB 调色") {
            Text(
                "拖动三个通道的滑杆，微调到确切颜色后点「应用此颜色」。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            RgbColorPicker(seed = settings.seed) { argb -> applyColor(argb) }
        }

        // ---- 5. 跟随壁纸（动态取色，仅 Android 12+） ----
        if (Build.VERSION.SDK_INT >= 31) {
            PresetGroup(title = "跟随壁纸") {
                PresetSwitchRow(
                    title = "跟随系统壁纸取色",
                    description = "从 Android 系统配色取主色；开启时会覆盖上面的主题色，但不会修改你保存的色值。",
                    checked = settings.dynamicColor,
                    onChecked = { checked ->
                        // 关掉动态取色后回到你保存的 seed；neutral 下打开取色需要 vivid 才会生效
                        val nextStyle = when {
                            !checked -> settings.colorStyle
                            settings.colorStyle in setOf("neutral", "monochrome") -> "vivid"
                            else -> settings.colorStyle
                        }
                        update(settings.copy(dynamicColor = checked, colorStyle = nextStyle))
                    },
                )
            }
        } else {
            PresetNote("「跟随系统壁纸取色」需要 Android 12 及以上系统，本机暂不支持。")
        }

        // ---- 6. 底部说明 ----
        PresetNote("换主题色只改变强调色与浅底色调，不会清除你的页面背景，也不会改动账号。")
        PresetNote("在经典黑白下选择颜色会自动切换到「自选」风格，让所选颜色立刻生效。")
    }
}

/** 当前主题色一览：色块 + HEX + 风格名 + 是否跟随壁纸。 */
@Composable
private fun PresetCurrentColorBar(settings: AppearanceSettings) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = GlassShape,
        color = scheme.surface,
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = .5f)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(if(settings.colorStyle in setOf("neutral","monochrome"))scheme.primary else Color(settings.seed))
                    .border(1.dp, scheme.outlineVariant, CircleShape)
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    when {
                        settings.colorStyle in setOf("neutral","monochrome")->"当前使用经典黑白"
                        settings.dynamicColor->"当前跟随壁纸取色"
                        else->"当前主题色 ${presetHex(settings.seed)}"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = scheme.onSurface,
                )
                Text(
                    "风格：${presetStyleName(settings)}${if (settings.dynamicColor) " · 动态取色会覆盖上面的色值" else ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 单个色块：外层 48dp 触摸目标（圆形），内层 36dp 色盘；
 * 选中时 3dp onSurface 描边 + 中心 14dp Check（白/黑按色块亮度自动选）。
 */
@Composable
private fun PresetSwatch(argb: Int, selected: Boolean, onClick: () -> Unit) {
    val color = Color(argb)
    val ink = presetInkOn(color)
    val hex = presetHex(argb)
    val neutralTag = if (argb in PresetNeutralSwatches) "，中性色" else ""
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(onClickLabel = "应用主题色 $hex", onClick = onClick)
            .semantics { contentDescription = "主题色 $hex$neutralTag${if (selected) "，当前已选" else ""}" },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(color)
                .border(
                    width = if (selected) 3.dp else 1.dp,
                    color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant,
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(
                    Icons.Rounded.Check,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = ink,
                )
            }
        }
    }
}
