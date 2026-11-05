package com.glasssign.app.ui

import androidx.compose.runtime.BroadcastFrameClock
import com.kyant.backdrop.catalog.utils.DampedDragAnimation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DockMotionTest {
    private fun withMotion(block: (DampedDragAnimation, BroadcastFrameClock) -> Unit) {
        val clock = BroadcastFrameClock()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined + clock)
        try {
            block(DampedDragAnimation(scope, 0f, 0f..3f, .001f, 1f, 1.18f), clock)
        } finally { scope.cancel() }
    }

    @Test fun draggingTracksEveryPointerUpdateWithoutWaitingForAFrame() = withMotion { motion, _ ->
        motion.beginDrag()
        motion.updateValue(2.25f)
        assertEquals(2.25f, motion.value, .0001f)
        motion.updateValue(.75f)
        assertEquals(.75f, motion.value, .0001f)
        motion.updateValue(20f)
        assertEquals(3f, motion.targetValue, .0001f)
        motion.updateValue(-20f)
        assertEquals(0f, motion.targetValue, .0001f)
    }

    @Test fun newDragInterruptsAnOldSettleWithoutJumping() = withMotion { motion, clock ->
        motion.animateToValue(3f)
        repeat(5) { clock.sendFrame(it * 16_666_667L) }
        val shown = motion.value
        motion.beginDrag()
        assertEquals(shown, motion.value, .0001f)
        motion.updateValue(.4f)
        repeat(180) { clock.sendFrame((it + 5) * 16_666_667L) }
        assertEquals(.4f, motion.value, .0001f)
        motion.animateToValue(1f)
        repeat(180) { clock.sendFrame((it + 185) * 16_666_667L) }
        assertEquals(1f, motion.value, .001f)
        assertEquals(1f, motion.scaleX, .001f)
        assertEquals(0f, motion.pressProgress, .001f)
    }

    @Test fun compactHeightPreservesTouchTargetsAndScalesContinuously() {
        assertEquals(60f, liquidDockHeight(1f).value, .001f)
        assertTrue(liquidDockHeight(1f).value - 12f >= 48f)
        assertTrue(liquidDockHeight(1.301f).value - liquidDockHeight(1.299f).value < .1f)
        assertEquals(88f, liquidDockHeight(2f).value, .001f)
        assertEquals(88f, liquidDockHeight(3f).value, .001f)
    }
}
