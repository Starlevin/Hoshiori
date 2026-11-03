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
 * VERBATIM MIGRATION — Hoshiori / GlassSign
 * ============================================================================
 * Upstream  : Kyant0/AndroidLiquidGlass
 * Tag       : 1.0.0
 * Commit    : eada9a8600142c1bd6a5c5fc143d06f599cdfaef
 * Source    : https://github.com/Kyant0/AndroidLiquidGlass/blob/eada9a8600142c1bd6a5c5fc143d06f599cdfaef/catalog/src/main/java/com/kyant/backdrop/catalog/components/LiquidBottomTab.kt
 * License   : Apache License 2.0 (see the header above and THIRD_PARTY_NOTICES.md)
 *
 * CHANGES FROM UPSTREAM: NONE.
 * This file is byte-for-byte upstream except for this comment block. The
 * package name is intentionally kept as `com.kyant.backdrop.catalog.components`
 * so that the sibling migrated files and the app adapter need no rewrites, and
 * so that audit against the pinned commit stays a pure diff.
 *
 * Upstream `LocalLiquidBottomTabScale` is the mechanism that makes the icon and
 * label of the *selected* tab grow with the liquid lens (it is provided by
 * LiquidBottomTabs around the hidden, tinted content row). Do not replace it
 * with a hand-written scale: the whole point of the migration is that this
 * grows with `pressProgress` of the same spring that drives the lens.
 */
package com.kyant.backdrop.catalog.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.kyant.capsule.ContinuousCapsule

internal val LocalLiquidBottomTabScale =
    staticCompositionLocalOf { { 1f } }

@Composable
fun RowScope.LiquidBottomTab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val scale = LocalLiquidBottomTabScale.current
    Column(
        modifier
            .clip(ContinuousCapsule)
            .clickable(
                interactionSource = null,
                indication = null,
                role = Role.Tab,
                onClick = onClick
            )
            .fillMaxHeight()
            .weight(1f)
            .graphicsLayer {
                val scale = scale()
                scaleX = scale
                scaleY = scale
            },
        verticalArrangement = Arrangement.spacedBy(2f.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content
    )
}
