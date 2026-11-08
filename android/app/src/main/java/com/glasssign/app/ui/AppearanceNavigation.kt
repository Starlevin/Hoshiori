package com.glasssign.app.ui

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Local appearance navigation state. Stored as its `name` so `rememberSaveable` only has to keep a
 * String (an enum would need a custom Saver) and rotation never falls back to the overview.
 */
internal enum class AppearanceSection { Overview, Presets, Colors, Typography, LauncherIcon }

/**
 * Full-screen host for every appearance control.
 *
 * The screen deliberately does not use `GlassDialog`: a full-height Material 3 surface gives the
 * sub pages room to scroll and keeps the top bar (back / title / close) fixed above the content.
 *
 * Back handling lives in ONE place: the dialog's own `onDismissRequest`, with `dismissOnBackPress`
 * on. A separate `BackHandler` outside the dialog would compete with it (the dialog window can
 * consume the system back before that handler runs, which made back appear to do nothing), so the
 * router here is the single source of truth: a sub page returns to the overview, the overview closes.
 * The top-left arrow calls the same router.
 */
@Composable
fun AppearanceSettingsScreen(onDismiss: () -> Unit) {
    val settings = LocalAppearance.current
    val controller = LocalAppearanceController.current
    // The controller re-reads the whole appearance file; writing through it keeps one source of
    // truth for every page (settings, colors, glass, typography).
    fun update(next: AppearanceSettings) { controller?.save(next) }

    var sectionName by rememberSaveable { mutableStateOf(AppearanceSection.Overview.name) }
    val section = AppearanceSection.valueOf(sectionName)
    var confirmingReset by rememberSaveable { mutableStateOf(false) }
    fun goBack() { sectionName = AppearanceSection.Overview.name }

    Dialog(
        onDismissRequest = { if (section == AppearanceSection.Overview) onDismiss() else goBack() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
        ),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                AppearanceTopBar(
                    section = section,
                    onBack = ::goBack,
                    onClose = onDismiss,
                )
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (section) {
                        AppearanceSection.Overview -> AppearanceOverview(
                            settings = settings,
                            onOpen = { sectionName = it.name },
                            onResetRequest = { confirmingReset = true },
                        )
                        else -> AppearanceSubPage(
                            section = section,
                            onBack = ::goBack,
                            modifier = Modifier.fillMaxSize(),
                            onGoToSection = { sectionName = it.name },
                        )
                    }
                }
            }
        }
    }

    if (confirmingReset) {
        AppearanceResetDialog(
            onCancel = { confirmingReset = false },
            onConfirm = {
                // Only AppearanceSettings is rewritten. Background preferences share the same
                // preference file but live under their own keys, so they survive untouched (see
                // AppearanceResetDialog text).
                update(AppearanceSettings())
                confirmingReset = false
            },
        )
    }
}

/**
 * Sub page container. Each page owns its own scrolling and its own section headings; this only
 * provides the Box so a page can use a LazyColumn or a Column with a shared modifier root.
 */
@Composable
internal fun AppearanceSubPage(section: AppearanceSection, onBack: () -> Unit, modifier: Modifier = Modifier, onGoToSection: (AppearanceSection) -> Unit = {}) {
    Box(modifier) {
        when (section) {
            AppearanceSection.Overview -> AppearanceOverview(
                settings = LocalAppearance.current,
                onOpen = {},
                onResetRequest = {},
            )
            AppearanceSection.Presets -> AppearancePresetsPage(onOpenColors = { onGoToSection(AppearanceSection.Colors) })
            AppearanceSection.Colors -> AppearanceColorsPage()
            AppearanceSection.Typography -> AppearanceTypographyPage()
            AppearanceSection.LauncherIcon -> AppearanceLauncherIconPage()
        }
    }
}

// ---------------------------------------------------------------- top bar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppearanceTopBar(section: AppearanceSection, onBack: () -> Unit, onClose: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val title = if (section == AppearanceSection.Overview) "外观与主题" else section.label()
    Column(Modifier.fillMaxWidth()) {
        TopAppBar(
            // Only the sub pages get a leading arrow; the overview leads with the title itself.
            navigationIcon = {
                if (section != AppearanceSection.Overview) {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(HoshioriIcons.Back, contentDescription = "返回外观总览",
                            modifier = Modifier.size(22.dp), tint = colors.onSurface)
                    }
                }
            },
            title = {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            },
            actions = {
                IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Rounded.Close, contentDescription = "关闭",
                        modifier = Modifier.size(22.dp), tint = colors.onSurface)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = colors.background,
                titleContentColor = colors.onSurface,
                navigationIconContentColor = colors.onSurface,
                actionIconContentColor = colors.onSurface,
            ),
        )
        // Very light divider: it separates the fixed bar from the scrolling content without
        // painting a heavy band under the status bar.
        HorizontalDivider(thickness = 1.dp, color = colors.outlineVariant.copy(alpha = .35f))
    }
}

@Composable
private fun AppearanceSection.label(): String = when (this) {
    AppearanceSection.Overview -> "外观与主题"
    AppearanceSection.Presets -> "主题与色彩"
    AppearanceSection.Colors -> "自定义色"
    AppearanceSection.Typography -> "显示与辅助"
    AppearanceSection.LauncherIcon -> "应用图标"
}

// ---------------------------------------------------------------- overview

private class AppearanceEntry(
    val section: AppearanceSection,
    val title: String,
    val description: String,
    val icon: ImageVector,
    val trailing: String,
)

@Composable
private fun AppearanceOverview(
    settings: AppearanceSettings,
    onOpen: (AppearanceSection) -> Unit,
    onResetRequest: () -> Unit,
) {
    val entries = listOf(
        // 2026-11-07 产品决策：删除液态玻璃调节、页面背景（遮罩未做好）、显示与辅助里的动效/层次块。
        // 外观一级只保留普通用户真正要用的三项；默认视觉（经典黑白 + 增强卡片）即用户当前所见，不另加开关。
        AppearanceEntry(
            AppearanceSection.Presets, "主题与色彩", "经典黑白、深蓝、柔紫与自定义色",
            HoshioriIcons.Appearance, settings.styleLabel(),
        ),
        AppearanceEntry(
            AppearanceSection.Typography, "显示与辅助", "字体、文字缩放与预览",
            HoshioriIcons.Typography, settings.typographyLabel(),
        ),
        AppearanceEntry(
            AppearanceSection.LauncherIcon, "应用图标", "桌面图标底图，可单独选深色或浅色",
            HoshioriIcons.System, launcherIconSummary(),
        ),
    )
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { AppearanceSummaryCard(settings) }
        item { AppearanceGroupHeader("外观") }
        items(entries.take(1), key = { it.section.name }) { entry ->
            AppearanceEntryRow(entry, onClick = { onOpen(entry.section) })
        }
        item { AppearanceGroupHeader("个性化") }
        items(entries.drop(1), key = { it.section.name }) { entry ->
            AppearanceEntryRow(entry, onClick = { onOpen(entry.section) })
        }
        item {
            AppearanceEntryRow(
                AppearanceEntry(
                    AppearanceSection.Overview, "恢复默认", "只重置外观设置，不删除页面背景、应用图标、账号与签到记录",
                    HoshioriIcons.System, "重置",
                ),
                onClick = onResetRequest,
                showChevron = false,
            )
        }
        item {
            Text(
                "仅保存在本机，不联网、不上传。",
                style = MaterialTheme.typography.bodySmall, color = Muted,
                modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 4.dp),
            )
        }
    }
}

/** P2 §5 grouping header: lower information density on the first-level appearance screen. */
@Composable
private fun AppearanceGroupHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 6.dp, top = 4.dp),
    )
}

/** Style-first summary: the user sees the current look before any control. */
@Composable
private fun AppearanceSummaryCard(settings: AppearanceSettings) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = GlassShape,
        color = colors.surface,
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = .5f)),
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("当前外观", style = MaterialTheme.typography.labelMedium, color = Muted)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    settings.styleLabel(),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
                )
                Surface(shape = CircleShape, color = colors.primaryContainer) {
                    Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                        Icon(HoshioriIcons.Appearance, contentDescription = null,
                            modifier = Modifier.size(20.dp), tint = colors.onPrimaryContainer)
                    }
                }
            }
            Text(
                "${settings.colorValueLabel()} · ${settings.modeLabel()} · 字体 ${settings.fontLabel()} · 缩放 ${settings.scalePercent()}",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurface,
            )
            Text(
                "仅影响本机界面，不上传、不做云端同步。",
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
            )
        }
    }
}

/**
 * One overview entry. The text column owns `weight(1f)` and nothing has a fixed height, so a large
 * system font makes the row taller instead of clipping the title or the description.
 */
@Composable
private fun AppearanceEntryRow(entry: AppearanceEntry, onClick: () -> Unit, showChevron: Boolean = true) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = GlassShape,
        color = colors.surface,
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = .5f)),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Surface(shape = CircleShape, color = colors.primaryContainer) {
                Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    Icon(entry.icon, contentDescription = null,
                        modifier = Modifier.size(22.dp), tint = colors.onPrimaryContainer)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(entry.title, style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold, color = colors.onSurface)
                Text(entry.description, style = MaterialTheme.typography.bodySmall, color = Muted)
            }
            Text(
                entry.trailing,
                modifier = Modifier.widthIn(max = 84.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = Muted,
                textAlign = TextAlign.End,
            )
            if (showChevron) {
                Icon(HoshioriIcons.ChevronRight, contentDescription = null,
                    modifier = Modifier.size(18.dp), tint = Muted)
            }
        }
    }
}

// ---------------------------------------------------------------- reset dialog

@Composable
private fun AppearanceResetDialog(onCancel: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("恢复默认外观？", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "会把主题模式恢复为跟随系统、色彩风格回到经典黑白，并重置主题色、玻璃与表面层次、字体与文字缩放。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "不会删除页面背景图片，也不会影响已登录的账号、授权与签到记录。",
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("恢复默认", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("取消", color = Muted)
            }
        },
    )
}

// ---------------------------------------------------------------- label helpers

@Composable
private fun AppearanceSettings.styleLabel(): String = when {
    dynamicColor && Build.VERSION.SDK_INT >= 31 -> "跟随壁纸"
    colorStyle == "neutral" -> "经典黑白"
    colorStyle == "tinted" && seed == 0xFF4263C7.toInt() -> "深蓝"
    colorStyle == "tinted" -> "自选配色"
    colorStyle == "soft" -> "柔紫"
    else -> "自选主题色"
}

/**
 * Value shown for the colour row and the summary line.
 *
 * The neutral style ignores `seed` entirely, so printing its #RRGGBB would advertise a blue accent the
 * screen never uses. Neutral is reported as what it actually is; every other style reports its hex.
 */
@Composable
private fun AppearanceSettings.colorValueLabel(): String = when {
    dynamicColor && Build.VERSION.SDK_INT >= 31 -> "跟随壁纸"
    colorStyle == "neutral" || colorStyle == "monochrome" -> "中性黑白"
    else -> hexLabel()
}

private fun AppearanceSettings.hexLabel(): String = "#" + (seed.toLong() and 0xFFFFFFL).toString(16).uppercase().padStart(6, '0')

private fun AppearanceSettings.modeLabel(): String = when (mode) {
    "light" -> "浅色"
    "dark" -> "深色"
    else -> "跟随系统"
}

private fun AppearanceSettings.fontLabel(): String = when (font) {
    "sans" -> "无衬线"
    "serif" -> "衬线"
    "mono" -> "等宽"
    else -> "系统"
}

private fun AppearanceSettings.scalePercent(): String = "${(textScale * 100f).toInt()}%"

private fun AppearanceSettings.typographyLabel(): String =
    if (scaleEnabled) scalePercent() else "跟随系统"

// ---------------------------------------------------------------- launcher icon page

/**
 * Short label for the overview row. Keyed only on the stored choice, so it stays a one-line value; the
 * full page states which plate that resolves to for the current page theme.
 */
@Composable
private fun launcherIconSummary(): String = when (LocalLauncherIconMode.current) {
    LauncherIconMode.FOLLOW -> "跟随应用主题"
    LauncherIconMode.DARK -> "始终深色"
    LauncherIconMode.LIGHT -> "始终浅色"
}

/**
 * "应用图标": the launcher icon plate, stored in its own preference file (`launcher_icon_v1`) and entirely
 * independent of [AppearanceSettings]. Previews are drawn from the same artwork the manifest aliases point
 * at, so what is shown here is what the launcher draws (masked by the launcher itself).
 */
@Composable
internal fun AppearanceLauncherIconPage() {
    val mode = LocalLauncherIconMode.current
    val controller = LocalLauncherIconController.current
    val pageMode = LocalAppearance.current.mode
    val systemDark = isSystemInDarkTheme()
    val effectiveDark = LauncherIconPlan.effectiveDark(mode, pageMode, systemDark)
    val artwork = rememberBrandArtworkCrop()

    AppearanceSubScaffold(
        title = "应用图标",
        subtitle = "桌面图标可单独切换；系统应用信息图标随系统主题显示。",
    ) {
        PreferenceGroup("图标背景") {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                LauncherIconOption(
                    mode = LauncherIconMode.FOLLOW, selected = mode, artwork = artwork,
                    caption = "跟随应用主题", modifier = Modifier.weight(1f),
                ) { controller?.commit(LauncherIconMode.FOLLOW) }
                LauncherIconOption(
                    mode = LauncherIconMode.DARK, selected = mode, artwork = artwork,
                    caption = "始终深色", modifier = Modifier.weight(1f),
                ) { controller?.commit(LauncherIconMode.DARK) }
                LauncherIconOption(
                    mode = LauncherIconMode.LIGHT, selected = mode, artwork = artwork,
                    caption = "始终浅色", modifier = Modifier.weight(1f),
                ) { controller?.commit(LauncherIconMode.LIGHT) }
            }
        }
        PreferenceGroup("当前状态") {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(mode.label, style = MaterialTheme.typography.titleSmall)
                Text(
                    when (mode) {
                        LauncherIconMode.FOLLOW ->
                            if (pageMode == "system") "应用主题跟随系统，桌面图标也随系统深色/浅色切换。"
                            else "应用主题固定为${if (pageMode == "dark") "深色" else "浅色"}，桌面图标随之为${if (effectiveDark) "深色" else "浅色"}底。"
                        LauncherIconMode.DARK -> "无论应用主题如何，桌面图标都使用深色底。"
                        LauncherIconMode.LIGHT -> "无论应用主题如何，桌面图标都使用浅色底。"
                    },
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "此选项与页面深浅色分开保存，恢复默认外观不会改动它。",
                    style = MaterialTheme.typography.bodySmall, color = Muted,
                )
            }
        }
        Text(
            "桌面图标是否立即刷新由桌面应用决定，部分厂商桌面需要等待其缓存更新。" +
                "「系统应用信息」中的图标固定跟随系统主题，不受此选项影响。",
            style = MaterialTheme.typography.bodySmall, color = Muted,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

/**
 * One preview button; the whole column (72dp plate + caption) is the touch target, so it is well over
 * 48dp in both directions. The plate is the choice's own plate — FOLLOW shows the theme it currently
 * follows — and the artwork is the cropped launcher crop, so the three options read as the launcher draws
 * them. Accessibility text names the option and its selected state.
 */
@Composable
private fun LauncherIconOption(
    mode: LauncherIconMode,
    selected: LauncherIconMode,
    artwork: ImageBitmap?,
    caption: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val isSelected = mode == selected
    val previewDark = when (mode) {
        LauncherIconMode.DARK -> true
        LauncherIconMode.LIGHT -> false
        // FOLLOW previews the theme it follows right now, not the one the current selection resolves to.
        LauncherIconMode.FOLLOW -> LauncherIconPlan.effectiveDark(LauncherIconMode.FOLLOW, LocalAppearance.current.mode, isSystemInDarkTheme())
    }
    val shape = RoundedCornerShape(percent = 23)
    Column(
        modifier
            .clip(GlassShape)
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = "图标背景 $caption" + if (isSelected) "，已选中" else ""
            }
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .size(72.dp)
                .clip(shape)
                .background(brandPlate(previewDark))
                .then(
                    if (isSelected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape)
                    else Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
                ),
            contentAlignment = Alignment.Center,
        ) {
            artwork?.let { Image(it, null, Modifier.size(48.dp), contentScale = ContentScale.Fit) }
        }
        Text(
            caption,
            style = MaterialTheme.typography.labelMedium,
            color = if (isSelected) MaterialTheme.colorScheme.primary else Muted,
        )
    }
}
