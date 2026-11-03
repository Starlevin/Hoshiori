package com.glasssign.app.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.glasssign.app.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*

class NavigationV070Test {
    @get:Rule val rule=createAndroidComposeRule<MainActivity>()
    @Test fun fourPagesMoveFloatingCapsuleAndAddStartsWithPlatformNotGame() {
        rule.onNodeWithTag("floating-dock").assertIsDisplayed()
        val before=rule.onNodeWithTag("selected-capsule").fetchSemanticsNode().boundsInRoot.left
        rule.onNodeWithTag("nav-3").performClick()
        rule.onNodeWithTag("page-3").assertIsDisplayed()
        val after=rule.onNodeWithTag("selected-capsule").fetchSemanticsNode().boundsInRoot.left
        assertTrue(after>before)
        rule.onNodeWithTag("nav-2").performClick();rule.onNodeWithTag("page-2").assertIsDisplayed()
        rule.onNodeWithTag("nav-1").performClick();rule.onNodeWithTag("page-1").assertIsDisplayed()
        rule.onNodeWithTag("nav-1").assertIsSelected().assertHasClickAction()
        rule.onNodeWithTag("add-account").performClick()
        rule.onNodeWithTag("binding-step").assertTextEquals("选择平台")
        rule.onNodeWithTag("floating-dock").assertDoesNotExist()
        rule.onNodeWithTag("platform-miyoushe").performClick()
        rule.onNodeWithTag("binding-step").assertTextEquals("登录账号")
        rule.onNodeWithTag("login-account").assertIsDisplayed()
        rule.onNodeWithTag("review-binding").assertDoesNotExist()
        rule.onNodeWithTag("complete-binding").assertDoesNotExist()
    }
}
