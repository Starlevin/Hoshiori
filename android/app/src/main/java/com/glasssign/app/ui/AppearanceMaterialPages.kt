package com.glasssign.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Material sub-pages of the appearance dialog: glass/motion, page background, typography.
 *
 * Each page is a pure composition root — it reads [LocalAppearance] and writes through
 * [LocalAppearanceController], never through its own ViewModel or preference file. Pages draw no top bar
 * and no back affordance; the host supplies those and scrolls nothing, so every page scrolls itself via
 * [AppearanceSubScaffold].
 */

/** Glass material, surface depth, navigation tint and the motion notice. */
@Composable
internal fun AppearanceGlassPage() {
    val settings = LocalAppearance.current
    val controller = LocalAppearanceController.current
    fun update(next: AppearanceSettings) { controller?.save(next) }

    AppearanceSubScaffold(
        title = "玻璃与动效",
        subtitle = "调整界面对比度、表面层次和动画。",
    ) {
        PreferenceGroup("玻璃材质") {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                SettingToggleRow(
                    title = "减少透明效果",
                    description = "使用不透明的导航底色",
                    checked = settings.glassMode == "off",
                    onChecked = { update(settings.copy(glassMode = if (it) "off" else "standard")) },
                )
                GlassHint("在设置的实验室中选择 Liquid Glass 2.0 的完整、轻量或关闭模式。")
            }
        }
        PreferenceGroup("表面层次") {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                ChoiceChips(
                    options = listOf("none" to "简洁", "standard" to "标准", "ultra" to "增强"),
                    selected = settings.surfaceEffect,
                ) { update(settings.copy(surfaceEffect = it)) }
                GlassHint("只改变卡片表面、边框与高光的层次，不启用实时模糊。")
            }
        }
        PreferenceGroup("导航") {
            SettingToggleRow(
                title = "导航色彩融合",
                description = "底部导航使用淡主题色背景",
                checked = settings.navigationTint,
            ) { update(settings.copy(navigationTint = it)) }
        }
        PreferenceGroup("动效") {
            /*
             * The switch is bound to the two CompositionLocals, never to a local ViewModel:
             *  - `LocalReduceMotion` is the committed value (it also folds in the system "remove
             *    animations" developer/accessibility setting, so what the user sees here is what the
             *    dock and the glass effects actually use);
             *  - `LocalReduceMotionAction` is the nullable setter the owner of that preference supplies.
             *    It is null outside the screen that hosts the appearance dialog (previews, tests), and
             *    in that case the row is shown disabled instead of pretending to be interactive.
             */
            val reduceMotion = LocalReduceMotion.current
            val setReduceMotion = LocalReduceMotionAction.current
            SettingToggleRow(
                title = "减少动态效果",
                description = if (setReduceMotion == null) {
                    "需要在设置里打开外观才能调整"
                } else {
                    "关闭弹性、缩放与过渡动画，保留必要的内容变化"
                },
                checked = reduceMotion,
                onChecked = { value -> setReduceMotion?.invoke(value) },
                enabled = setReduceMotion != null,
            )
            GlassHint(
                "开启后底部导航与卡片不再有弹性位移和缩放过渡，只保留直接的内容切换；" +
                    "系统关闭动画时同样生效。",
            )
        }
        GlassHint("以上设置只保存在本机，重启后保持。")
    }
}

/**
 * Page background. All picking, previewing, masking and blurring is [BackgroundSettingsSection] — this page
 * only frames it and adds the two privacy notes, so no launcher and no repository access lives here.
 */
@Composable
internal fun AppearanceBackgroundPage() {
    AppearanceSubScaffold(
        title = "页面背景",
        subtitle = "只影响页面装饰，不会清除或修改你已保存的账号。",
    ) {
        Surface(
            Modifier.fillMaxWidth(),
            shape = GlassShape,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f)),
        ) {
            Column(Modifier.padding(16.dp)) { BackgroundSettingsSection() }
        }
        GlassHint("图片用系统照片选择器读取，不需要存储权限；文件只保存在本应用私有目录，不上传。")
        GlassHint("换主题色或切换风格都不会清除这张背景图。")
    }
}

/** Text scale (live draft, commit on release) and font family, plus a rendered sample. */
@Composable
internal fun AppearanceTypographyPage() {
    val settings = LocalAppearance.current
    val controller = LocalAppearanceController.current
    fun update(next: AppearanceSettings) { controller?.save(next) }

    var draftScale by remember { mutableStateOf<Float?>(null) }
    val shown = draftScale ?: settings.textScale
    val percent = (shown * 100).toInt()

    AppearanceSubScaffold(
        title = "字体与排版",
        subtitle = "文字缩放与字体只影响本机界面。",
    ) {
        PreferenceGroup("文字缩放") {
            SettingToggleRow(
                title = "自定义文字缩放",
                description = "关闭时跟随系统字体大小",
                checked = settings.scaleEnabled,
            ) { update(settings.copy(scaleEnabled = it)) }
            if (settings.scaleEnabled) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(
                        "$percent%",
                        style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
                        modifier = Modifier.semantics { contentDescription = "文字缩放 $percent%" },
                    )
                    Slider(
                        value = shown,
                        onValueChange = { draftScale = it },
                        onValueChangeFinished = {
                            // One write per gesture: the drag only moves the number, the release commits.
                            val committed = draftScale
                            draftScale = null
                            if (committed != null && committed != settings.textScale) {
                                update(settings.copy(textScale = committed))
                            }
                        },
                        valueRange = .85f..1.4f,
                        steps = 10,
                    )
                    GlassHint("觉得字太小时可以调大；预览会立即更新。")
                }
            }
        }
        PreferenceGroup("字体") {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                ChoiceChips(
                    options = listOf("system" to "系统", "sans" to "无衬线", "serif" to "衬线", "mono" to "等宽"),
                    selected = settings.font,
                ) { update(settings.copy(font = it)) }
                GlassHint("等宽字体更容易对齐数字；衬线字体更接近印刷书籍。")
            }
        }
        PreferenceGroup("预览") {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text("星栞 Hoshiori", style = MaterialTheme.typography.titleMedium)
                Text("签到结果保存在本机，不会上传到云端。", style = MaterialTheme.typography.bodyMedium)
                Text("# 0123-4567 · 今日已签到 · 2026-11-05", style = MaterialTheme.typography.bodySmall, color = Muted)
                Text("已签到 / 待处理 / 失败", style = MaterialTheme.typography.titleLarge)
            }
            GlassHint("文字会随缩放变大，卡片跟着变高，长句自动换行。")
        }
    }
}

/** Section-level explanatory line: small, muted, wraps freely, indented to the card's inner margin. */
@Composable
private fun GlassHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = Muted,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
}
