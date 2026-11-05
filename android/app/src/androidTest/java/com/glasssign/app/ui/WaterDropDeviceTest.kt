package com.glasssign.app.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.filters.SdkSuppress
import com.glasssign.app.MainActivity
import com.glasssign.app.local.LabStore
import com.glasssign.app.ui.background.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

@SdkSuppress(minSdkVersion=33)
class WaterDropDeviceTest {
    @get:Rule val rule=createAndroidComposeRule<MainActivity>()

    @Test fun realGpuDragSeparatesMembersAndCancelDoesNotNavigate() {
        var selection=0
        var commits=0
        rule.activityRule.scenario.onActivity { activity -> activity.setContent {
            GlassSignTheme(false) {
                var selected by remember{mutableIntStateOf(0)}
                val backdrop=rememberPageBackdrop(true)
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().hazePageSource(backdrop,true).background(Color(0xFF23895B))) {
                        Text("GPU capture must remain visible",color=Color.White)
                    }
                    LiquidDock(selected,{selected=it;selection=it;commits++},backdrop,GlassPolicy(true,true,"GPU test"),
                        modifier=Modifier.align(Alignment.BottomCenter),waterDropEnabled=true,
                        onWaterDropError={throw AssertionError("Haze rendering failed")})
                }
            }
        } }
        rule.waitUntil(12_000){rule.onAllNodesWithTag("waterdrop-dock").fetchSemanticsNodes().size==1}
        val first=rule.onNodeWithTag("nav-0")
        val last=rule.onNodeWithTag("nav-3").fetchSemanticsNode().boundsInRoot.center
        val firstBounds=first.fetchSemanticsNode().boundsInRoot
        assertTrue(firstBounds.width>=48*rule.activity.resources.displayMetrics.density)
        assertTrue(firstBounds.height>=48*rule.activity.resources.displayMetrics.density)
        first.performTouchInput{down(center)}
        first.performTouchInput{advanceEventTime(200);moveTo(last-firstBounds.topLeft)}
        rule.waitForIdle()
        val anchor=rule.onNodeWithTag("selected-capsule").fetchSemanticsNode().boundsInRoot
        val follower=rule.onNodeWithTag("waterdrop-follower").fetchSemanticsNode().boundsInRoot
        assertTrue("Two actual laid-out members must move beyond the merge range",follower.left-anchor.right>20*rule.activity.resources.displayMetrics.density)
        val image=rule.onRoot().captureToImage().toPixelMap()
        assertTrue("Sampling must not erase the page",image[image.width/2,image.height/2].green>.45f)
        first.performTouchInput{cancel()}
        rule.waitForIdle()
        assertEquals(0,selection)
        assertEquals(0,commits)
        rule.onNodeWithTag("nav-3").performClick()
        rule.waitForIdle()
        assertEquals(3,selection)
        assertEquals(1,commits)
    }

    @Test fun productionPagesRenderWithTheRealPersistedExperiment() {
        val context=rule.activity.applicationContext
        val lab=LabStore(context)
        val background=BackgroundPreferencesStore(context)
        val originalQuality=lab.glassQuality()
        val originalBackground=background.read()
        try {
            lab.setGlassQuality(com.glasssign.app.local.GlassQuality.FULL)
            background.save(BackgroundPreferences(BackgroundSource.starfield,.15f,0f))
            rule.activityRule.scenario.recreate()
            rule.waitUntil(15_000){rule.onAllNodesWithTag("waterdrop-dock").fetchSemanticsNodes().size==1}
            rule.onNodeWithTag("nav-3").performClick()
            rule.waitForIdle()
            capture(context,"production-settings-idle.png")
            Thread.sleep(700)
            val fourth=rule.onNodeWithTag("nav-3")
            val bounds=fourth.fetchSemanticsNode().boundsInRoot
            val first=rule.onNodeWithTag("nav-0").fetchSemanticsNode().boundsInRoot.center
            val pitch=bounds.width
            fourth.performTouchInput{down(center)}
            // Stop near the bridge, then move far enough for the actual Haze members to separate.
            fourth.performTouchInput{advanceEventTime(250);moveTo(center-Offset(pitch*1.08f,0f))}
            rule.waitForIdle()
            capture(context,"production-settings-bridge.png")
            Thread.sleep(700)
            fourth.performTouchInput{advanceEventTime(350);moveTo(first-bounds.topLeft)}
            rule.waitForIdle()
            capture(context,"production-settings-separated.png")
            Thread.sleep(700)
            fourth.performTouchInput{up()}
            rule.waitForIdle()
            rule.onNodeWithTag("nav-0").assertIsSelected()
            capture(context,"production-home-rejoined.png")
            Thread.sleep(700)
        } finally {
            lab.setGlassQuality(originalQuality)
            background.save(originalBackground)
            rule.activityRule.scenario.recreate()
        }
    }

    private fun capture(context:Context,name:String) {
        val directory=File(context.getExternalFilesDir(null),"qa-v080").apply{mkdirs()}
        File(directory,name).outputStream().use {
            assertTrue(rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it))
        }
    }
}
