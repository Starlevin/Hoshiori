package com.glasssign.app.local

import org.junit.Assert.*
import org.junit.Test

class ExperimentalUiRecoveryTest {
    @Test fun onlyRendererFailuresQualifyForAutomaticRollback() {
        val network=IllegalStateException("official request failed").apply {
            stackTrace=arrayOf(StackTraceElement("com.glasssign.app.local.Protocol","request","Protocol.kt",32))
        }
        assertFalse(ExperimentalUiRecovery.isGlassFailure(network))
        val render=IllegalStateException("renderer").apply {
            stackTrace=arrayOf(StackTraceElement("com.glasssign.app.ui.WaterDropDockKt","draw","WaterDropDock.kt",42))
        }
        assertTrue(ExperimentalUiRecovery.isGlassFailure(render))
        assertTrue(ExperimentalUiRecovery.isGlassFailure(RuntimeException("wrapped",render)))
    }
}
