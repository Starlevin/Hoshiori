package com.glasssign.app.ui

import android.app.UiModeManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import com.glasssign.app.local.GlassQuality

internal data class DockRendering(val mode: GlassQuality, val motion: Boolean, val fallback: String? = null)

internal fun dockRendering(
    requested: GlassQuality,
    api: Int,
    accelerated: Boolean,
    reduceTransparency: Boolean,
    highContrast: Boolean,
    policy: GlassPolicy,
): DockRendering = when {
    requested == GlassQuality.OFF -> DockRendering(GlassQuality.OFF, policy.motion)
    reduceTransparency -> DockRendering(GlassQuality.OFF, policy.motion, "已减少透明效果")
    highContrast || policy.opaqueForAccessibility -> DockRendering(GlassQuality.OFF, policy.motion, "系统辅助功能要求清晰对比")
    requested == GlassQuality.LITE -> DockRendering(GlassQuality.LITE, policy.motion)
    api < 33 -> DockRendering(GlassQuality.LITE, policy.motion, "完整折射需要 Android 13")
    !accelerated -> DockRendering(GlassQuality.LITE, policy.motion, "当前窗口没有硬件加速")
    !policy.blur -> DockRendering(GlassQuality.LITE, policy.motion, policy.reason)
    else -> DockRendering(GlassQuality.FULL, policy.motion)
}

@Composable
internal fun rememberDockRendering(requested: GlassQuality): DockRendering {
    val highContrast = Build.VERSION.SDK_INT >= 34 && systemContrast() >= .5f
    return dockRendering(requested, Build.VERSION.SDK_INT, LocalView.current.isHardwareAccelerated,
        LocalAppearance.current.glassMode == "off", highContrast, currentGlassPolicy(mode = "standard"))
}

@RequiresApi(34)
@Composable
private fun systemContrast(): Float {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(UiModeManager::class.java) } ?: return 0f
    var contrast by remember(manager) { mutableFloatStateOf(manager.contrast) }
    DisposableEffect(manager) {
        val listener = UiModeManager.ContrastChangeListener { contrast = it }
        manager.addContrastChangeListener(context.mainExecutor, listener)
        onDispose { manager.removeContrastChangeListener(listener) }
    }
    return contrast
}
