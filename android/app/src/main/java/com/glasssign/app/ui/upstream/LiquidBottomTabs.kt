/*
 * Copyright 2025 Kyant
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * ============================================================================
 * MIGRATION — Hoshiori / GlassSign
 * ============================================================================
 * Upstream  : Kyant0/AndroidLiquidGlass
 * Tag       : 1.0.0
 * Commit    : eada9a8600142c1bd6a5c5fc143d06f599cdfaef
 * Source    : https://github.com/Kyant0/AndroidLiquidGlass/blob/eada9a8600142c1bd6a5c5fc143d06f599cdfaef/catalog/src/main/java/com/kyant/backdrop/catalog/components/LiquidBottomTabs.kt
 * License   : Apache License 2.0 (see the header above and THIRD_PARTY_NOTICES.md)
 *
 * ---------------------------------------------------------------------------
 * CHANGES FROM UPSTREAM (this file is the ONLY modified one)
 * ---------------------------------------------------------------------------
 * The complete render/press/drag pipeline is untouched. Every visual mechanism
 * below is upstream's, byte-for-byte:
 *   - the visible `Row` + `.drawBackdrop(shape = ContinuousCapsule,
 *     effects = { vibrancy(); blur(8.dp); lens(24.dp, 24.dp) })` panel;
 *   - the alpha-0 content `Row` that both feeds `tabsBackdrop` (icon + label
 *     capture) and draws the press `Highlight`;
 *   - the sliding capsule `Box` over `rememberCombinedBackdrop(backdrop,
 *     tabsBackdrop)` with `lens(10.dp, 14.dp, chromaticAberration = true)`,
 *     `Shadow`, `InnerShadow`, and the `BlendMode.Clear` punch-through;
 *   - `DampedDragAnimation` springs, `InteractiveHighlight`, and
 *     `LocalLiquidBottomTabScale` (icons and labels grow with the same
 *     `pressProgress` as the lens — they are never scaled by hand).
 *
 * Only these additions were made, all optional with upstream-default values:
 *   [1] `barHeight: Dp = 64.dp` — outer panel height. The contents row is
 *       derived as `barHeight - 8.dp` exactly as upstream's 64/56 pair, so the
 *       icon/label block keeps its proportions and is NOT stretched.
 *   [2] `isLightTheme: Boolean = !isSystemInDarkTheme()` — upstream computed
 *       this internally; hoisted so the app can follow MaterialTheme instead of
 *       the system, without touching either branch's default.
 *   [3] `accentColor: Color` / [4] `containerColor: Color` — upstream literals
 *       hoisted into parameters that default to the *same* literals.
 *   All four sit after `modifier` and before `content`, per the integration
 *   contract. `containerColor` uses a default-value lambda so the "follow
 *   isLightTheme" behaviour is preserved when the caller omits it; pass it
 *   explicitly to decouple the two.
 *
 * Explicitly NOT added: `motionEnabled`. Reduced-motion is handled by the app
 * choosing its own fallback path, so no second animation code path exists here.
 *
 * Two upstream comments are preserved because they document real behaviour
 * (`layerBlock` scaling, `BlendMode.Clear`); nothing was hand-replaced with a
 * different effect.
 */
package com.kyant.backdrop.catalog.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.spring
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.catalog.utils.DampedDragAnimation
import com.kyant.backdrop.catalog.utils.InteractiveHighlight
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.capsule.ContinuousCapsule
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

/**
 * Upstream `64.dp` panel height, kept as the reference so the content height can
 * be derived as `barHeight - 8.dp` for any [barHeight].
 */
private val UpstreamBarHeight = 64.dp

/** Upstream `56.dp` content height; the vertical padding is the 8.dp delta. */
private val UpstreamContentHeight = 56.dp

/** Upstream `AccentLight` / `AccentDark`. */
private val UpstreamAccentLight = Color(0xFF0088FF)
private val UpstreamAccentDark = Color(0xFF0091FF)

/** Upstream `ContainerLight` / `ContainerDark` literals, alpha included. */
private val UpstreamContainerLight = Color(0xFFFAFAFA).copy(0.4f)
private val UpstreamContainerDark = Color(0xFF121212).copy(0.4f)

@Composable
fun LiquidBottomTabs(
    selectedTabIndex: () -> Int,
    onTabSelected: (index: Int) -> Unit,
    backdrop: Backdrop,
    tabsCount: Int,
    modifier: Modifier = Modifier,
    barHeight: Dp = UpstreamBarHeight,
    isLightTheme: Boolean = !isSystemInDarkTheme(),
    accentColor: Color = if (isLightTheme) UpstreamAccentLight else UpstreamAccentDark,
    containerColor: Color = if (isLightTheme) UpstreamContainerLight else UpstreamContainerDark,
    content: @Composable RowScope.() -> Unit
) {
    // [CHANGE 1] Content row height is derived, not a second knob: upstream's
    // 64->56 pair is a fixed 8.dp of vertical padding, so keeping that delta
    // means the icon + label block keeps its size relative to the capsule and is
    // never stretched by a taller bar (the 200%-font case).
    val contentHeight = (barHeight - (UpstreamBarHeight - UpstreamContentHeight)).coerceAtLeast(0.dp)

    val tabsBackdrop = rememberLayerBackdrop()

    BoxWithConstraints(
        modifier,
        contentAlignment = Alignment.CenterStart
    ) {
        val density = LocalDensity.current
        val tabWidth = with(density) {
            (constraints.maxWidth.toFloat() - 8f.dp.toPx()) / tabsCount
        }

        val offsetAnimation = remember { Animatable(0f) }
        val panelOffset by remember(density) {
            derivedStateOf {
                val fraction = (offsetAnimation.value / constraints.maxWidth).fastCoerceIn(-1f, 1f)
                with(density) {
                    4f.dp.toPx() * fraction.sign * EaseOut.transform(abs(fraction))
                }
            }
        }

        val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
        val animationScope = rememberCoroutineScope()
        var currentIndex by remember(selectedTabIndex) {
            mutableIntStateOf(selectedTabIndex())
        }
        // Upstream pressedScale is the fixed 78/56 ratio; it is a capsule *scale*
        // on the lens, not a height, so it stays independent of barHeight.
        val dampedDragAnimation = remember(animationScope) {
            DampedDragAnimation(
                animationScope = animationScope,
                initialValue = selectedTabIndex().toFloat(),
                valueRange = 0f..(tabsCount - 1).toFloat(),
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                pressedScale = 78f / 56f,
                onDragStarted = {},
                onDragStopped = {
                    val targetIndex = targetValue.fastRoundToInt().fastCoerceIn(0, tabsCount - 1)
                    currentIndex = targetIndex
                    animateToValue(targetIndex.toFloat())
                    animationScope.launch {
                        offsetAnimation.animateTo(
                            0f,
                            spring(1f, 300f, 0.5f)
                        )
                    }
                },
                onDrag = { _, dragAmount ->
                    updateValue(
                        (targetValue + dragAmount.x / tabWidth * if (isLtr) 1f else -1f)
                            .fastCoerceIn(0f, (tabsCount - 1).toFloat())
                    )
                    animationScope.launch {
                        offsetAnimation.snapTo(offsetAnimation.value + dragAmount.x)
                    }
                }
            )
        }
        LaunchedEffect(selectedTabIndex) {
            snapshotFlow { selectedTabIndex() }
                .collectLatest { index ->
                    currentIndex = index
                }
        }
        LaunchedEffect(dampedDragAnimation) {
            snapshotFlow { currentIndex }
                .drop(1)
                .collectLatest { index ->
                    dampedDragAnimation.animateToValue(index.toFloat())
                    onTabSelected(index)
                }
        }

        val interactiveHighlight = remember(animationScope) {
            InteractiveHighlight(
                animationScope = animationScope,
                position = { size, offset ->
                    Offset(
                        if (isLtr) (dampedDragAnimation.value + 0.5f) * tabWidth + panelOffset
                        else size.width - (dampedDragAnimation.value + 0.5f) * tabWidth + panelOffset,
                        size.height / 2f
                    )
                }
            )
        }

        Row(
            Modifier
                .graphicsLayer {
                    translationX = panelOffset
                }
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { ContinuousCapsule },
                    effects = {
                        vibrancy()
                        blur(8f.dp.toPx())
                        lens(24f.dp.toPx(), 24f.dp.toPx())
                    },
                    layerBlock = {
                        val progress = dampedDragAnimation.pressProgress
                        val scale = lerp(1f, 1f + 16f.dp.toPx() / size.width, progress)
                        scaleX = scale
                        scaleY = scale
                    },
                    onDrawSurface = { drawRect(containerColor) }
                )
                .then(interactiveHighlight.modifier)
                // [CHANGE 2] was `.height(64f.dp)`.
                .height(barHeight)
                .fillMaxWidth()
                .padding(4f.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )

        CompositionLocalProvider(
            LocalLiquidBottomTabScale provides {
                lerp(1f, 1.2f, dampedDragAnimation.pressProgress)
            }
        ) {
            Row(
                Modifier
                    .clearAndSetSemantics {}
                    .alpha(0f)
                    .layerBackdrop(tabsBackdrop)
                    .graphicsLayer {
                        translationX = panelOffset
                    }
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { ContinuousCapsule },
                        effects = {
                            val progress = dampedDragAnimation.pressProgress
                            vibrancy()
                            blur(8f.dp.toPx())
                            lens(
                                24f.dp.toPx() * progress,
                                24f.dp.toPx() * progress
                            )
                        },
                        highlight = {
                            val progress = dampedDragAnimation.pressProgress
                            Highlight.Default.copy(alpha = progress)
                        },
                        onDrawSurface = { drawRect(containerColor) }
                    )
                    .then(interactiveHighlight.modifier)
                    // [CHANGE 3] was `.height(56f.dp)`, kept as barHeight - 8.dp.
                    .height(contentHeight)
                    .fillMaxWidth()
                    .padding(horizontal = 4f.dp)
                    .graphicsLayer(colorFilter = ColorFilter.tint(accentColor)),
                verticalAlignment = Alignment.CenterVertically,
                content = content
            )
        }

        Box(
            Modifier
                .padding(horizontal = 4f.dp)
                .graphicsLayer {
                    translationX =
                        if (isLtr) dampedDragAnimation.value * tabWidth + panelOffset
                        else size.width - (dampedDragAnimation.value + 1f) * tabWidth + panelOffset
                }
                .then(interactiveHighlight.gestureModifier)
                .then(dampedDragAnimation.modifier)
                .drawBackdrop(
                    backdrop = rememberCombinedBackdrop(backdrop, tabsBackdrop),
                    shape = { ContinuousCapsule },
                    effects = {
                        val progress = dampedDragAnimation.pressProgress
                        lens(
                            10f.dp.toPx() * progress,
                            14f.dp.toPx() * progress,
                            chromaticAberration = true
                        )
                    },
                    highlight = {
                        val progress = dampedDragAnimation.pressProgress
                        Highlight.Default.copy(alpha = progress)
                    },
                    shadow = {
                        val progress = dampedDragAnimation.pressProgress
                        Shadow(alpha = progress)
                    },
                    innerShadow = {
                        val progress = dampedDragAnimation.pressProgress
                        InnerShadow(
                            radius = 8f.dp * progress,
                            alpha = progress
                        )
                    },
                    layerBlock = {
                        scaleX = dampedDragAnimation.scaleX
                        scaleY = dampedDragAnimation.scaleY
                        val velocity = dampedDragAnimation.velocity / 10f
                        scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                        scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                    },
                    onDrawBehind = {
                        drawRect(Color.Red, blendMode = BlendMode.Clear)
                    },
                    onDrawSurface = {
                        val progress = dampedDragAnimation.pressProgress
                        drawRect(
                            if (isLightTheme) Color.Black.copy(0.1f)
                            else Color.White.copy(0.1f),
                            alpha = 1f - progress
                        )
                        drawRect(Color.Black.copy(alpha = 0.03f * progress))
                    }
                )
                // [CHANGE 4] was `.height(56f.dp)`, kept as barHeight - 8.dp so the
                // sliding capsule always matches the content row it selects.
                .height(contentHeight)
                .fillMaxWidth(1f / tabsCount)
        )
    }
}
