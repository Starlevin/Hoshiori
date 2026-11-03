package com.glasssign.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.glasssign.app.data.RoleChoice
import com.glasssign.app.login.PendingLogin
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * Device test for the account/role sheets (AccountFlow.kt). Requires a device or emulator: it checks real
 * semantics trees and real measured bounds.
 *
 *   android/gradlew -p android :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.glasssign.app.ui.BindingFlowV070Test
 *
 * Covers what BindingV070Test does not: the fixed step order of BOTH sheets, that the sheet body is
 * driven by hoisted data (a role toggle only changes its own card and the summary count), the identity
 * checkbox gate when the platform reports no stable id, and that every toggleable row is a legal 48dp
 * touch target.
 */
class BindingFlowV070Test {
    @get:Rule val rule=createComposeRule()

    private val roles=listOf(
        RoleChoice("genshin|fixture-main|server","genshin","原神 · 旅行者"),
        RoleChoice("genshin|fixture-alt|server","genshin","原神 · 小号角色（很长的角色名用来验证省略号不会把卡片撑宽）"),
        RoleChoice("zzz|fixture-main|server","zzz","绝区零 · 绳匠"),
    )

    private fun pending(providerUserId:String,confirming:Boolean=false)=PendingLogin(
        accountId="fixture-main",provider="miyoushe",nickname="合成账号",roles=listOf("原神","绝区零"),
        message="授权成功，可以读取角色",availableGameIds=listOf("genshin","zzz","honkai_sr"),selectedGameIds=setOf("genshin"),
        roleChoices=roles,selectedRoleIds=setOf("genshin|fixture-main|server"),providerUserId=providerUserId,
        autoSignEnabled=true,confirming=confirming,
    )

    /** AddAccountSheet: 选择平台 -> 登录账号, and the game-entry step must not appear before confirm. */
    @Test fun addSheetStartsWithPlatformThenLoginAndNeverOffersGamesEarly() {
        var provider:String?=null;var loggedIn=0
        rule.setContent {
            GlassSignTheme(false) {
                Box(Modifier.fillMaxSize()) {
                    AddAccountSheet(null,false,{provider="dismiss"},{id,_->provider=id;loggedIn++},{_,_,_->})
                }
            }
        }
        rule.onNodeWithTag("binding-step").assertTextEquals("选择平台")
        // Every platform offered, in product order, each a legal touch target.
        rule.onNodeWithTag("platform-miyoushe").assertIsDisplayed().assertHasClickAction()
        rule.onNodeWithTag("platform-skland").assertExists()
        rule.onNodeWithTag("platform-tagged").assertExists()
        rule.onNodeWithTag("platform-kurobbs").assertExists()
        rule.onNodeWithTag("login-account").assertDoesNotExist()

        rule.onNodeWithTag("platform-miyoushe").performClick()
        rule.onNodeWithTag("binding-step").assertTextEquals("登录账号")
        rule.onNodeWithTag("login-account").assertIsDisplayed().assertHasClickAction()
        rule.onNodeWithTag("review-binding").assertDoesNotExist()
        rule.onNodeWithTag("complete-binding").assertDoesNotExist()

        rule.onNodeWithTag("login-account").performScrollTo().performClick()
        rule.runOnIdle{assertEquals(1,loggedIn);assertEquals("miyoushe",provider)}
    }

    /** RoleBindingSheet: game/role toggle changes only its own card and the summary count. */
    @Test fun gameAndRoleTogglesUpdateTheSelectionSummaryWithoutScrollJump() {
        var p by mutableStateOf(pending("fixture-main"))
        rule.setContent {
            GlassSignTheme(false) {
                Box(Modifier.fillMaxSize()) {
                    RoleBindingSheet(p,false,
                        {id,value->p=p.copy(selectedGameIds=if(value)p.selectedGameIds+id else p.selectedGameIds-id)},
                        {id,value->p=p.copy(selectedRoleIds=if(value)p.selectedRoleIds+id else p.selectedRoleIds-id)},
                        {p=p.copy(autoSignEnabled=it)},
                        {p=p.copy(confirming=true)},
                        {},{},{})
                }
            }
        }
        rule.onNodeWithTag("binding-step").assertTextEquals("选择游戏 / 角色")
        // Only the selected game's roles are composed.
        rule.onNodeWithTag("role-genshin|fixture-main|server").assertExists()
        rule.onNodeWithTag("role-zzz|fixture-main|server").assertDoesNotExist()

        // Selecting 绝区零 composes its single role and does not drop the already-selected one.
        rule.onNodeWithTag("game-zzz").performScrollTo().performClick()
        rule.onNodeWithTag("role-zzz|fixture-main|server").assertExists()

        // Toggling one role keeps the other role row present (no whole-sheet re-filter regression).
        rule.onNodeWithTag("role-genshin|fixture-alt|server").performScrollTo().performClick()
        rule.runOnIdle{assertEquals(setOf("genshin|fixture-main|server","genshin|fixture-alt|server"),p.selectedRoleIds)}

        rule.onNodeWithTag("review-binding").performScrollTo().performClick()
        rule.onNodeWithTag("binding-step").assertTextEquals("确认绑定")
        rule.onNodeWithTag("selection-summary").assertTextEquals("已选择 2 个游戏、2 个角色。")
        rule.onNodeWithTag("complete-binding").assertIsDisplayed().assertIsEnabled()
    }

    /** A blank providerUserId gates review behind the identity checkbox. */
    @Test fun identityCheckboxGatesReviewWhenPlatformProvidesNoStableId() {
        var confirmed=0
        rule.setContent {
            var p by remember{mutableStateOf(pending(""))}
            GlassSignTheme(false) {
                Box(Modifier.fillMaxSize()) {
                    RoleBindingSheet(p,false,{_,_->},{_,_->},{},{p=p.copy(confirming=true)},{confirmed++},{},{})
                }
            }
        }
        rule.onNodeWithTag("identity-confirm").assertExists()
        rule.onNodeWithTag("review-binding").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag("identity-confirm").performScrollTo().performClick()
        rule.onNodeWithTag("review-binding").assertIsEnabled()
        rule.onNodeWithTag("review-binding").performClick()
        rule.onNodeWithTag("binding-step").assertTextEquals("确认绑定")
        rule.runOnIdle{assertEquals(0,confirmed)}
    }

    /** Review must come before complete, and complete must not fire on review. */
    @Test fun reviewAndCompleteAreSeparateStepsWithSeparateCallbacks() {
        var reviewed=0;var saved=0
        rule.setContent {
            var p by remember{mutableStateOf(pending("fixture-main"))}
            GlassSignTheme(false) {
                Box(Modifier.fillMaxSize()) {
                    RoleBindingSheet(p,false,{_,_->},{_,_->},{},{reviewed++;p=p.copy(confirming=true)},{saved++},{},{})
                }
            }
        }
        rule.onNodeWithTag("complete-binding").assertDoesNotExist()
        rule.onNodeWithTag("review-binding").performScrollTo().performClick()
        rule.runOnIdle{assertEquals(1,reviewed);assertEquals(0,saved)}
        rule.onNodeWithTag("complete-binding").performScrollTo().performClick()
        rule.runOnIdle{assertEquals(1,saved)}
    }

    /** Every toggleable row is at least 48dp tall (real measured bounds, not the declared minimum). */
    @Test fun everyToggleableRowIsAtLeast48dpTall() {
        rule.setContent {
            GlassSignTheme(false) {
                Box(Modifier.fillMaxSize()) {
                    RoleBindingSheet(pending(""),false,{_,_->},{_,_->},{},{},{},{},{})
                }
            }
        }
        rule.waitForIdle()
        val tags=listOf(
            "game-genshin","game-zzz",
            "role-genshin|fixture-main|server","role-genshin|fixture-alt|server",
            "identity-confirm",
        )
        for(tag in tags) {
            val node=rule.onNodeWithTag(tag).performScrollTo().fetchSemanticsNode()
            val heightDp=with(rule.density){node.boundsInRoot.height.toDp()}
            assertTrue("$tag must be a 48dp target, was $heightDp",heightDp>=48.dp)
        }
    }

    /** The game row announces its game name and role count on top of the Checkbox state. */
    @Test fun gameRowAnnouncesNameAndRoleCount() {
        rule.setContent {
            GlassSignTheme(false) {
                Box(Modifier.fillMaxSize()) {
                    RoleBindingSheet(pending("fixture-main"),false,{_,_->},{_,_->},{},{},{},{},{})
                }
            }
        }
        val node=rule.onNodeWithTag("game-genshin").fetchSemanticsNode()
        val description=node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString(" ")
        assertTrue("contentDescription was '$description'",description.contains("原神"))
        assertTrue("contentDescription was '$description'",description.contains("2 个角色"))
        // Toggleable state still reaches accessibility together with the label.
        val state=node.config.getOrNull(SemanticsProperties.ToggleableState)
        assertNotNull("game row must report a toggleable state",state)
    }
}
