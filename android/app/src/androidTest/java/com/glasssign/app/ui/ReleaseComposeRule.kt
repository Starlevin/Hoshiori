package com.glasssign.app.ui

import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.glasssign.app.MainActivity

/** Hosts isolated Compose checks in the actual Release activity without shipping a test activity. */
internal fun createReleaseComposeRule():ComposeContentTestRule {
    val host=createAndroidComposeRule<MainActivity>()
    return object:ComposeContentTestRule by host {
        override fun setContent(composable:@Composable ()->Unit) {
            host.activityRule.scenario.onActivity { activity -> activity.setContent(content = composable) }
            host.waitForIdle()
        }
    }
}
