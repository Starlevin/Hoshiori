package com.glasssign.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glasssign.app.local.GlassQuality
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazePerformanceMode
import dev.chrisbanes.haze.glass.GlassMemberTransition
import dev.chrisbanes.haze.glass.GlassStyle
import dev.chrisbanes.haze.glass.OpticalSizeValue
import dev.chrisbanes.haze.glass.RefractionProfile
import dev.chrisbanes.haze.glass.hazeGlass
import dev.chrisbanes.haze.glass.hazeGlassContainer
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

private val DockTitles = listOf("首页", "账号", "记录", "设置")
private val DockIcons = listOf(HoshioriIcons.Home, HoshioriIcons.Accounts, HoshioriIcons.Records, HoshioriIcons.Settings)
private val ShellShape = RoundedCornerShape(36.dp)
private val LensShape = RoundedCornerShape(50)
private val TrackPadding = 5.dp
private val LensGap = 2.dp
private val LensInset = 5.dp
private val LiftRoom = 22.dp
private val MergeDistance = 30.dp

@Composable
internal fun DockGlass(
    selected: Int,
    onSelect: (Int) -> Unit,
    mode: GlassQuality,
    motion: Boolean,
    input: HazeInput.Sources?,
    modifier: Modifier = Modifier,
    onOverlapChange: (Float) -> Unit = {},
    singleLens: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val height = liquidDockHeight(density.fontScale) + 4.dp
    val full = mode == GlassQuality.FULL && input != null
    val combine = full && !singleLens
    val movement = remember(motion) {
        if (motion) spring<Float>(dampingRatio = .84f, stiffness = Spring.StiffnessMedium) else snap()
    }
    val active = animateFloatAsState(selected.toFloat(), movement, label = "selected-dock-slot")
    val rebound = remember { Animatable(selected.toFloat()) }
    val scope = rememberCoroutineScope()
    var draggedSlot by remember { mutableStateOf<Float?>(null) }
    var reboundTarget by remember { mutableStateOf<Int?>(null) }
    var settleJob by remember { mutableStateOf<Job?>(null) }
    val pressSignals = remember { MutableInteractionSource() }
    val currentSelect by rememberUpdatedState(onSelect)
    val currentSelected by rememberUpdatedState(selected)

    fun resetDrag() {
        settleJob?.cancel()
        settleJob = null
        draggedSlot = null
        reboundTarget = null
    }

    fun settle(target: Int) {
        val release = draggedSlot ?: return
        settleJob?.cancel()
        settleJob = scope.launch {
            rebound.snapTo(release)
            reboundTarget = target
            draggedSlot = null
            if (target != currentSelected) currentSelect(target)
            rebound.animateTo(target.toFloat(), movement)
            reboundTarget = null
        }
    }

    LaunchedEffect(selected, motion, mode) {
        if (!motion || reboundTarget?.let { it != selected } == true) resetDrag()
    }

    Box(
        modifier.fillMaxWidth().onSizeChanged { onOverlapChange(it.height.toFloat()) }
            .navigationBarsPadding().padding(horizontal = 18.dp).padding(bottom = GlassTokens.lift),
        contentAlignment = Alignment.BottomCenter,
    ) {
        BoxWithConstraints(
            Modifier.widthIn(max = GlassTokens.maxWidth).fillMaxWidth().height(height + LiftRoom)
                .testTag("floating-dock").selectableGroup(),
        ) {
            val cell = (maxWidth - TrackPadding * 2) / DockSlots
            val lensWidth = (cell - LensGap * 2).coerceAtLeast(1.dp)
            val lensHeight = height - LensInset * 2
            val cellPx = with(density) { cell.toPx() }
            val dockBase = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(height)
            val navTint = LocalAppearance.current.navigationTint
            val shell = if (navTint) scheme.surfaceContainerHigh else scheme.surface
            val lensFill = if (navTint) scheme.secondaryContainer else scheme.surfaceContainerHighest

            fun travellingSlot(): Float = draggedSlot ?: if (reboundTarget != null) rebound.value else active.value

            fun lensOffset(travelling: Boolean, lift: Boolean): Modifier =
                Modifier.align(Alignment.BottomStart).offset {
                    val slot = clampWaterDropSlot(if (travelling) travellingSlot() else active.value)
                    val separation = abs(slot - active.value)
                    val rise = if (lift) LiftRoom * (separation / .95f).coerceIn(0f, 1f) else 0.dp
                    IntOffset((TrackPadding + LensGap + cell * slot).roundToPx(), -(LensInset + rise).roundToPx())
                }.size(lensWidth, lensHeight)

            if (full) {
                val material = rememberDockGlassStyle(lens = true)
                val baseMaterial = rememberDockGlassStyle(lens = false)
                val press = pressSignals.takeIf { motion }
                val hasCompanion = combine && (draggedSlot != null || reboundTarget != null)
                Box(dockBase.hazeGlass(input, baseMaterial, HazePerformanceMode.Default))
                Box(
                    Modifier.fillMaxSize().testTag("waterdrop-dock").then(
                        if (combine) Modifier.hazeGlassContainer(mergeDistance = MergeDistance) else Modifier,
                    ),
                ) {
                    Box(
                        lensOffset(travelling = !combine, lift = false).testTag("selected-capsule")
                            .hazeGlass(input, material, HazePerformanceMode.Default, interactionSource = press,
                                memberTransition = GlassMemberTransition.None),
                    )
                    if (hasCompanion) {
                        Box(
                            lensOffset(travelling = true, lift = true).testTag("waterdrop-follower")
                                .hazeGlass(input, material, HazePerformanceMode.Default, interactionSource = press,
                                    memberTransition = GlassMemberTransition.None),
                        )
                    }
                }
            } else {
                val lite = mode != GlassQuality.OFF
                Box(
                    dockBase.shadow(6.dp, ShellShape).background(shell.copy(alpha = if (lite) .87f else 1f), ShellShape)
                        .border(1.dp, scheme.outlineVariant.copy(alpha = .65f), ShellShape)
                        .testTag(if (lite) "dock-glass-lite" else "dock-glass-off"),
                )
                Box(lensOffset(travelling = true, lift = false).background(lensFill, LensShape).testTag("selected-capsule"))
            }

            Row(
                dockBase.padding(horizontal = TrackPadding).dockDrag(
                    motion, selected, cellPx, rtl, pressSignals,
                    onStart = { resetDrag() },
                    onMove = { draggedSlot = it },
                    onEnd = { settle(it) },
                ),
            ) {
                DockTitles.forEachIndexed { index, title ->
                    val isSelected = index == selected
                    val color = if (isSelected || full) scheme.onSurface else scheme.onSurfaceVariant
                    Column(
                        Modifier.weight(1f).fillMaxHeight().testTag("nav-$index")
                            .selectable(
                                selected = isSelected,
                                interactionSource = null,
                                indication = null,
                                role = Role.Tab,
                                onClick = {
                                    resetDrag()
                                    if (!isSelected) currentSelect(index)
                                },
                            ).semantics { contentDescription = title },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(DockIcons[index], contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
                        Text(title, Modifier.clearAndSetSemantics { }, color = color, fontSize = 11.sp, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberDockGlassStyle(lens: Boolean): GlassStyle {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < .5f
    val navTint = LocalAppearance.current.navigationTint
    val tone = if (navTint) scheme.surfaceContainerHigh else scheme.surface
    return remember(dark, lens, tone) {
        val base = if (lens) GlassStyle.clear else GlassStyle.regular
        base.then {
            shape(if (lens) LensShape else ShellShape)
            optics {
                copy(
                    blurRadius = OpticalSizeValue.Fixed(if (lens) .35.dp else 2.4.dp),
                    refractionDisplacement = if (lens) 23.dp else 12.dp,
                    refractionStrength = if (lens) .94f else .68f,
                    refractionProfile = RefractionProfile.Edge(if (lens) 14.dp else 10.dp),
                    refractionHeightFraction = if (lens) .32f else .2f,
                    depth = OpticalSizeValue.Fixed(if (lens) .84f else .48f),
                )
            }
            val tintAlpha = if (lens) {
                if (dark) .14f else .31f
            } else {
                if (dark) .56f else .61f
            }
            tint(tone.copy(alpha = tintAlpha))
            if (lens && !dark) whitePoint(.39f)
            ambientResponse(if (lens) .21f else .17f)
            specularIntensity(if (lens) .68f else .48f)
            edgeSoftness(1.dp)
            if (lens) pressed {
                scale(.98f)
                refractionMultiplier(1.12f)
                lightingIntensity(.82f)
            }
        }
    }
}

@Composable
private fun Modifier.dockDrag(
    enabled: Boolean,
    selected: Int,
    cellPx: Float,
    rtl: Boolean,
    pressSignals: MutableInteractionSource,
    onStart: () -> Unit,
    onMove: (Float) -> Unit,
    onEnd: (Int) -> Unit,
): Modifier {
    val start by rememberUpdatedState(onStart)
    val move by rememberUpdatedState(onMove)
    val end by rememberUpdatedState(onEnd)
    return pointerInput(enabled, selected, cellPx, rtl) {
        if (!enabled || cellPx <= 0f) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val press = PressInteraction.Press(down.position.copy(x = cellPx / 2))
            pressSignals.tryEmit(press)
            var complete = false
            try {
                val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                if (drag != null) {
                    start()
                    fun position(x: Float) =
                        waterDropTravelCells(selected, (x - down.position.x) / cellPx * if (rtl) -1f else 1f)
                    var last = position(drag.position.x)
                    move(last)
                    complete = horizontalDrag(drag.id) { change ->
                        last = position(change.position.x)
                        move(last)
                        change.consume()
                    }
                    end(if (complete) waterDropRestSlot(last) else selected)
                }
            } finally {
                pressSignals.tryEmit(if (complete) PressInteraction.Release(press) else PressInteraction.Cancel(press))
            }
        }
    }
}
