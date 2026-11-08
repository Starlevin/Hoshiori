package com.glasssign.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
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
 * Material sub-pages of the appearance dialog: page background, typography.
 *
 * 2026-11-07 产品决策：删除「显示与辅助」里的辅助与动效 / 卡片层次整块（默认视觉即增强），
 * 删除液态玻璃调节入口，页面背景一级入口也删除（遮罩未做好）；底层偏好字段与背景渲染代码保留，
 * 不改动既有用户数据与默认视觉。
 *
 * Each page is a pure composition root — it reads [LocalAppearance] and writes through
 * [LocalAppearanceController], never through its own ViewModel or preference file. Pages draw no top bar
 * and no back affordance; the host supplies those and scrolls nothing, so every page scrolls itself via
 * [AppearanceSubScaffold].
 */

/**
 * Page background. The two choice cards, the current-photo preview and the mask/blur controls all live in
 * [BackgroundSettingsSection]; this page only supplies the one-line subtitle and leaves the title to the
 * host's fixed top bar, so the heading is not repeated and the privacy note is not duplicated.
 */
@Composable
internal fun AppearanceBackgroundPage() {
    AppearanceSubScaffold(
        title = "页面背景",
        subtitle = "选择纯净背景，或用自己的图片装饰页面。",
    ) {
        BackgroundSettingsSection()
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
