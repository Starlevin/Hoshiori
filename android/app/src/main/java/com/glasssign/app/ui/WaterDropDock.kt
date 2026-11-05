package com.glasssign.app.ui

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.glasssign.app.local.GlassQuality
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeSourceRetention

/** Bridges legacy Dock comparisons to the same renderer used by the app. */
@Composable
fun WaterDropDock(
    selected: Int,
    onSelect: (Int) -> Unit,
    backdrop: PageBackdropState,
    policy: GlassPolicy,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onOverlapChange: (Float) -> Unit = {},
    onRenderError: (String) -> Unit = {},
) {
    if (!enabled) return
    val supported = Build.VERSION.SDK_INT >= 33
    LaunchedEffect(supported) {
        if (!supported) onRenderError(describeWaterDropFailure(WaterDropFailure.Unsupported))
    }
    if (!supported || !backdrop.hazeSourceAttached) {
        WaterDropFallback(selected, onSelect, modifier, onOverlapChange)
        return
    }
    val input = remember(backdrop) {
        HazeInput.Sources(backdrop.hazeState(), retention = HazeSourceRetention.ClearWhenUnavailable)
    }
    DockGlass(selected, onSelect, GlassQuality.FULL, policy.motion, input, modifier, onOverlapChange)
}

@Composable
internal fun WaterDropFallback(
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier,
    onOverlapChange: (Float) -> Unit,
) {
    DockGlass(selected, onSelect, GlassQuality.OFF, false, null, modifier, onOverlapChange)
}
