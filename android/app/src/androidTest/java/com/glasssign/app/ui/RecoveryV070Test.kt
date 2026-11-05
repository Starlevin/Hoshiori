package com.glasssign.app.ui

import androidx.compose.ui.test.*
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*

class RecoveryV070Test {
    @get:Rule val rule=createReleaseComposeRule()
    @Test fun failedMigrationShowsPreservationAndRetryInsteadOfDestructiveReset() {
        var retries=0
        rule.setContent{GlassSignTheme(true){RecoveryScreen{retries++}}}
        rule.onNodeWithText("升级尚未完成").assertIsDisplayed()
        rule.onNodeWithText("原账号、授权与历史仍保留。请勿卸载应用或清除数据。").assertIsDisplayed()
        rule.onNodeWithTag("retry-migration").performScrollTo().performClick()
        rule.runOnIdle{assertEquals(1,retries)}
    }
}
