package com.glasssign.app.ui

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import com.glasssign.app.data.RoleChoice
import com.glasssign.app.login.PendingLogin
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*

class BindingV070Test {
    @get:Rule val rule=createReleaseComposeRule()
    @Test fun roleSelectionRequiresSeparateReviewAndFinalConfirmationAndDoesNotSign() {
        var saved=0
        rule.setContent {
            var p by remember{mutableStateOf(PendingLogin("synthetic","miyoushe","合成账号",listOf("合成角色"),"授权成功，可以读取角色",listOf("genshin"),roleChoices=listOf(RoleChoice("genshin|fixture|server","genshin","原神 · 合成角色")),providerUserId="fixture-main"))}
            GlassSignTheme(false){RoleBindingSheet(p,false,{_,_->},{id,value->p=p.copy(selectedRoleIds=if(value)p.selectedRoleIds+id else p.selectedRoleIds-id)},{p=p.copy(autoSignEnabled=it)},{p=p.copy(confirming=!p.confirming)},{saved++},{},{})}
        }
        rule.onNodeWithTag("binding-step").assertTextEquals("选择游戏 / 角色")
        capture("selection")
        rule.onNodeWithTag("complete-binding").assertDoesNotExist()
        assertEquals(0,saved)
        rule.onNodeWithTag("review-binding").performScrollTo().performClick()
        rule.onNodeWithTag("binding-step").assertTextEquals("确认绑定")
        capture("confirmation")
        assertEquals(0,saved)
        rule.onNodeWithTag("complete-binding").performScrollTo().performClick()
        rule.runOnIdle{assertEquals(1,saved)}
    }
    private fun capture(name:String) {
        val i=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        if(androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("capture070")!="true")return
        rule.waitForIdle()
        Thread.sleep(900) // Wait for SurfaceFlinger after Compose has become idle.
        val bitmap=i.uiAutomation.takeScreenshot()
        check(bitmap!=null)
        val file=java.io.File(i.targetContext.filesDir,"v070-synthetic-$name.png")
        file.outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        bitmap.recycle()
    }

}
