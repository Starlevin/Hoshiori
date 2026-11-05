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
 * Modified from Kyant0/AndroidLiquidGlass, tag 1.0.0, commit
 * eada9a8600142c1bd6a5c5fc143d06f599cdfaef.
 * Source: catalog/src/main/java/com/kyant/backdrop/catalog/utils/DampedDragAnimation.kt
 * Hoshiori changes: synchronous drag position, cancellable settling and pressure
 * jobs. Pointer movement bypasses the spring; release keeps the upstream optics.
 */
package com.kyant.backdrop.catalog.utils

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class DampedDragAnimation(
    private val animationScope: CoroutineScope,
    val initialValue: Float,
    val valueRange: ClosedRange<Float>,
    val visibilityThreshold: Float,
    val initialScale: Float,
    val pressedScale: Float,
) {

    private val valueAnimationSpec =
        spring(.82f, 650f, visibilityThreshold)
    private val velocityAnimationSpec =
        spring(0.5f, 300f, visibilityThreshold * 10f)
    private val pressProgressAnimationSpec =
        spring(1f, 1000f, 0.001f)
    private val scaleXAnimationSpec =
        spring(0.6f, 250f, 0.001f)
    private val scaleYAnimationSpec =
        spring(0.7f, 250f, 0.001f)

    private val valueAnimation =
        Animatable(initialValue, visibilityThreshold)
    private val velocityAnimation =
        Animatable(0f, 5f)
    private val pressProgressAnimation =
        Animatable(0f, 0.001f)
    private val scaleXAnimation =
        Animatable(initialScale, 0.001f)
    private val scaleYAnimation =
        Animatable(initialScale, 0.001f)

    private var directValue: Float? by mutableStateOf(null)
    private var directVelocity: Float by mutableStateOf(0f)
    private var lastDragNanos = 0L
    private var settleJob: Job? = null
    private var pressureJob: Job? = null
    val value: Float get() = directValue ?: valueAnimation.value
    val progress: Float get() = (value - valueRange.start) / (valueRange.endInclusive - valueRange.start)
    val targetValue: Float get() = directValue ?: valueAnimation.targetValue
    val pressProgress: Float get() = pressProgressAnimation.value
    val scaleX: Float get() = scaleXAnimation.value
    val scaleY: Float get() = scaleYAnimation.value
    val velocity: Float get() = if(directValue != null) directVelocity else velocityAnimation.value

    fun beginDrag() {
        val shown = value
        settleJob?.cancel()
        directValue = shown
        directVelocity = 0f
        lastDragNanos = System.nanoTime()
        press()
    }

    fun press() {
        pressureJob?.cancel()
        pressureJob = animationScope.launch {
            launch { pressProgressAnimation.animateTo(1f, pressProgressAnimationSpec) }
            launch { scaleXAnimation.animateTo(pressedScale, scaleXAnimationSpec) }
            launch { scaleYAnimation.animateTo(pressedScale, scaleYAnimationSpec) }
        }
    }

    fun release() {
        pressureJob?.cancel()
        pressureJob = animationScope.launch {
            launch { pressProgressAnimation.animateTo(0f, pressProgressAnimationSpec) }
            launch { scaleXAnimation.animateTo(initialScale, scaleXAnimationSpec) }
            launch { scaleYAnimation.animateTo(initialScale, scaleYAnimationSpec) }
        }
    }

    fun updateValue(value: Float) {
        val next = value.coerceIn(valueRange)
        val now = System.nanoTime()
        val dt = ((now-lastDragNanos)/1_000_000_000f).coerceIn(.008f,.05f)
        val speed = (next-this.value)/dt/(valueRange.endInclusive-valueRange.start).coerceAtLeast(1f)
        directVelocity = (directVelocity*.65f+speed*.35f).coerceIn(-5f,5f)
        directValue = next
        lastDragNanos = now
    }

    fun animateToValue(value: Float) {
        val from = this.value
        val speed = velocity
        settleJob?.cancel()
        settleJob = animationScope.launch {
            valueAnimation.snapTo(from)
            velocityAnimation.snapTo(speed)
            directValue = null
            press()
            launch { velocityAnimation.animateTo(0f, velocityAnimationSpec) }
            valueAnimation.animateTo(value.coerceIn(valueRange), valueAnimationSpec)
            release()
        }
    }

}
