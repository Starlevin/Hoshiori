package com.glasssign.app.ui

import androidx.compose.ui.unit.Density
import org.junit.Assert.*
import org.junit.Test

class DockLayoutV077Test {
    @Test fun gestureAndThreeButtonInsetsAreCountedOnce() {
        assertEquals(96f,dockOverlapPx(72f,8f,16f),0f)
        assertEquals(128f,dockOverlapPx(72f,8f,48f),0f)
    }
    @Test fun largeFontsIncreaseActualReservedArea() {
        val normal=dockContentPaddingPx(dockOverlapPx(72f,8f,24f),8f,96f)
        val large=dockContentPaddingPx(dockOverlapPx(110f,8f,24f),8f,96f)
        assertTrue(large>normal)
        assertEquals(150f,large,0f)
    }
    @Test fun lastCardHasClearanceAboveOverlay() {
        for(height in listOf(72f,96f,160f,240f))assertTrue(dockContentPaddingPx(height,8f,96f)>=height+8f)
    }
    @Test fun unmeasuredDockKeepsSafeFirstFrameFloor() {
        assertEquals(96f,dockContentPaddingPx(0f,8f,96f),0f)
        assertEquals(96f,dockContentPaddingPx(Float.NaN,8f,96f),0f)
    }
    @Test fun densityConversionDoesNotAddAnotherInset() {
        assertEquals(120f,dockBottomPaddingPx(224f,Density(2f)).value,0f)
    }
    @Test fun stateColorsHaveSeparateSemanticRoles() {
        assertEquals(StatusTone.GreenSuccess,statusTone("success"))
        assertEquals(StatusTone.TealAlready,statusTone("already"))
        assertEquals(StatusTone.AmberAccepted,statusTone("accepted"))
        assertEquals(StatusTone.GrayUnknown,statusTone("unknown"))
        assertEquals(StatusTone.RedFailed,statusTone("failed"))
    }
}
