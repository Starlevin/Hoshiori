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
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glasssign.app.local.GlassQuality
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazePerformanceMode
import dev.chrisbanes.haze.glass.GlassStyle
import dev.chrisbanes.haze.glass.OpticalSizeValue
import dev.chrisbanes.haze.glass.RefractionProfile
import dev.chrisbanes.haze.glass.hazeGlass
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private val DockTitles = listOf("首页", "账号", "记录", "设置")
private val DockIcons = listOf(HoshioriIcons.Home, HoshioriIcons.Accounts, HoshioriIcons.Records, HoshioriIcons.Settings)
private val ShellShape = RoundedCornerShape(36.dp)
private val LensShape = RoundedCornerShape(50)
private val TrackPadding = 5.dp
private val LensGap = 2.dp
private val LensInset = 5.dp

@Composable
internal fun DockGlass(
    selected: Int,
    onSelect: (Int) -> Unit,
    mode: GlassQuality,
    motion: Boolean,
    input: HazeInput.Sources?,
    modifier: Modifier = Modifier,
    onOverlapChange: (Float) -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val height = liquidDockHeight(density.fontScale) + 4.dp
    val full = mode == GlassQuality.FULL && input != null
    val movement = remember(motion) {
        if (motion) spring<Float>(dampingRatio = .84f, stiffness = Spring.StiffnessMedium) else snap()
    }
    val active = remember { Animatable(selected.toFloat()) }
    val scope = rememberCoroutineScope()
    var draggedSlot by remember { mutableStateOf<Float?>(null) }
    var dragging by remember { mutableStateOf(false) }
    var settleJob by remember { mutableStateOf<Job?>(null) }
    val pressSignals = remember { MutableInteractionSource() }
    val pressing by pressSignals.collectIsPressedAsState()
    val pressed = full && motion && pressing
    val pressScale = animateFloatAsState(if (pressed) .98f else 1f, movement, label = "glass-press-size")
    val dragStretch = animateFloatAsState(
        if (full && motion && dragging) 1f else 0f,
        movement,
        label = "glass-drag-stretch",
    )
    val currentSelect by rememberUpdatedState(onSelect)
    val currentSelected by rememberUpdatedState(selected)

    fun resetDrag() {
        settleJob?.cancel()
        settleJob = null
        draggedSlot = null
        dragging = false
    }

    fun settle(target: Int) {
        val release = draggedSlot ?: return
        settleJob?.cancel()
        settleJob = scope.launch {
            active.snapTo(release)
            draggedSlot = null
            dragging = false
            if (target != currentSelected) currentSelect(target)
            else active.animateTo(target.toFloat(), movement)
        }
    }

    LaunchedEffect(selected, motion, mode) {
        if (!motion) resetDrag()
        if (draggedSlot == null) active.animateTo(selected.toFloat(), movement)
    }

    Box(
        modifier.fillMaxWidth().onSizeChanged { onOverlapChange(it.height.toFloat()) }
            .navigationBarsPadding().padding(horizontal = 18.dp).padding(bottom = GlassTokens.lift),
        contentAlignment = Alignment.BottomCenter,
    ) {
        BoxWithConstraints(
            Modifier.widthIn(max = GlassTokens.maxWidth).fillMaxWidth().height(height)
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

            val lensMaterial = if (full) {
                val material = rememberDockGlassStyle(lens = true, pressed = pressed, moving = dragging)
                val baseMaterial = rememberDockGlassStyle(lens = false)
                Box(dockBase.hazeGlass(input, baseMaterial, HazePerformanceMode.Default))
                Modifier.hazeGlass(input, material, HazePerformanceMode.Default,
                    interactionSource = pressSignals.takeIf { motion })
            } else {
                val lite = mode != GlassQuality.OFF
                Box(
                    dockBase.shadow(6.dp, ShellShape).background(shell.copy(alpha = if (lite) .87f else 1f), ShellShape)
                        .border(1.dp, scheme.outlineVariant.copy(alpha = .65f), ShellShape)
                        .testTag(if (lite) "dock-glass-lite" else "dock-glass-off"),
                )
                Modifier.background(lensFill, LensShape)
            }

            Layout(
                modifier = dockBase.then(if (full) Modifier.testTag("waterdrop-dock") else Modifier),
                content = { Box(Modifier.testTag("selected-capsule").then(lensMaterial)) },
            ) { children, constraints ->
                val trackWidth = constraints.maxWidth
                val trackHeight = constraints.maxHeight
                val slot = clampWaterDropSlot(draggedSlot ?: active.value)
                // Measure the single lens at its actual size. Its label positions stay on the fixed grid.
                val stretch = if (full && motion) maxOf(dragStretch.value, abs(active.velocity) / 7f).coerceIn(0f, 1f) else 0f
                val width = (lensWidth.toPx() * (1f + .24f * stretch) * pressScale.value)
                    .roundToInt().coerceIn(0, trackWidth)
                val heightPx = (lensHeight.toPx() * (1f + .07f * stretch) * pressScale.value)
                    .roundToInt().coerceIn(0, trackHeight)
                val center = TrackPadding.toPx() + cellPx * (slot + .5f)
                val start = (center - width / 2f).roundToInt().coerceIn(0, trackWidth - width)
                val lens = children.single().measure(Constraints.fixed(width, heightPx))
                layout(trackWidth, trackHeight) {
                    lens.placeRelative(start, (trackHeight - heightPx) / 2)
                }
            }

            Row(
                dockBase.padding(horizontal = TrackPadding).dockDrag(
                    motion, selected, cellPx, rtl,
                    onStart = { resetDrag(); dragging = true },
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
                                interactionSource = pressSignals,
                                indication = null,
                                role = Role.Tab,
                                onClick = {
                                    resetDrag()
                                    if (!isSelected) currentSelect(index)
                                    else if (active.value != index.toFloat()) {
                                        settleJob = scope.launch { active.animateTo(index.toFloat(), movement) }
                                    }
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
private fun rememberDockGlassStyle(lens: Boolean, pressed: Boolean = false, moving: Boolean = false): GlassStyle {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < .5f
    val navTint = LocalAppearance.current.navigationTint
    val tone = if (navTint) scheme.surfaceContainerHigh else scheme.surface
    return remember(dark, lens, tone, pressed, moving) {
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
            specularIntensity(if (!lens) .48f else if (pressed || moving) .84f else .68f)
            edgeSoftness(1.dp)
            if (lens) {
                chromaticAberrationStrength(if (moving) .12f else .05f)
                pressed {
                    refractionMultiplier(1.12f)
                    lightingIntensity(.82f)
                }
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
    onStart: () -> Unit,
    onMove: (Float) -> Unit,
    onEnd: (Int) -> Unit,
): Modifier {
    val start by rememberUpdatedState(onStart)
    val move by rememberUpdatedState(onMove)
    val end by rememberUpdatedState(onEnd)
    val selection by rememberUpdatedState(selected)
    return pointerInput(enabled, cellPx, rtl) {
        if (!enabled || cellPx <= 0f) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val origin = selection
            val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
            if (drag != null) {
                start()
                fun position(x: Float) =
                    waterDropTravelCells(origin, (x - down.position.x) / cellPx * if (rtl) -1f else 1f)
                var last = position(drag.position.x)
                move(last)
                val complete = horizontalDrag(drag.id) { change ->
                    last = position(change.position.x)
                    move(last)
                    change.consume()
                }
                end(if (complete && selection == origin) waterDropRestSlot(last) else selection)
            }
        }
    }
}
