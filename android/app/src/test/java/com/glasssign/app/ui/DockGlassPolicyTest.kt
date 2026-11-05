package com.glasssign.app.ui

import com.glasssign.app.local.GlassQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DockGlassPolicyTest {
    @Test fun fullKeepsOpticsWhenSystemMotionIsDisabled() {
        val still = GlassPolicy(blur = true, motion = false, reason = "test")
        val rendering = dockRendering(GlassQuality.FULL, 33, true, false, false, still)
        assertEquals(GlassQuality.FULL, rendering.mode)
        assertFalse(rendering.motion)
    }

    @Test fun incapableRenderersFallBackWithoutDiscardingTheRequestedMode() {
        val available = GlassPolicy(blur = true, motion = true, reason = "test")
        assertEquals(GlassQuality.LITE, dockRendering(GlassQuality.FULL, 32, true, false, false, available).mode)
        assertEquals(GlassQuality.LITE, dockRendering(GlassQuality.FULL, 35, false, false, false, available).mode)
        val saver = available.copy(blur = false, reason = "省电模式")
        assertEquals("省电模式", dockRendering(GlassQuality.FULL, 35, true, false, false, saver).fallback)
    }

    @Test fun accessibilityAndUserTransparencySettingsChooseAnOpaqueDock() {
        val available = GlassPolicy(blur = true, motion = true, reason = "test")
        assertEquals(GlassQuality.OFF, dockRendering(GlassQuality.FULL, 36, true, true, false, available).mode)
        assertEquals(GlassQuality.OFF, dockRendering(GlassQuality.LITE, 36, true, false, true, available).mode)
        val accessibility = available.copy(opaqueForAccessibility = true)
        assertEquals(GlassQuality.OFF, dockRendering(GlassQuality.FULL, 36, true, false, false, accessibility).mode)
    }
}
