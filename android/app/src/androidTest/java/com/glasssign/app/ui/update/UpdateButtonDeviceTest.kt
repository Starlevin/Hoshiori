package com.glasssign.app.ui.update

import androidx.activity.compose.setContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.glasssign.app.MainActivity
import com.glasssign.app.ui.GlassSignTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class UpdateButtonDeviceTest {
    @get:Rule val rule=createAndroidComposeRule<MainActivity>()

    @Test fun theLabelAndRightEdgeBothStartExactlyOneAction() {
        var clicks=0
        rule.activityRule.scenario.onActivity { activity -> activity.setContent {
            GlassSignTheme(false) {
                PrimaryButton("下载安装包",Icons.Rounded.Download,"download-area"){clicks++}
            }
        } }
        rule.onNodeWithText("下载安装包").performClick()
        rule.onNodeWithTag("download-area").performTouchInput {
            click(Offset(width-10f,height/2f))
        }
        rule.runOnIdle { assertEquals(2,clicks) }
    }
}
