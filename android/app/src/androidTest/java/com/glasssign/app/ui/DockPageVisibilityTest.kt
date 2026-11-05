package com.glasssign.app.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.filters.SdkSuppress
import com.glasssign.app.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@SdkSuppress(minSdkVersion = 33)
class DockPageVisibilityTest {
    @get:Rule val rule=createAndroidComposeRule<MainActivity>()

    @Test fun holdingAndDraggingGlassKeepsThePageVisible() {
        rule.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                GlassSignTheme(false) {
                    var selected by remember { mutableIntStateOf(0) }
                    val backdrop=rememberPageBackdrop(true)
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxSize().backdropSource(backdrop).background(Color(0xFF23895B))) {
                            LazyColumn(Modifier.fillMaxSize().clipToBounds()) {
                                items(24) { index ->
                                    Text("Record $index",color=Color.White,
                                        modifier=Modifier.fillMaxWidth().height(96.dp))
                                }
                            }
                        }
                        LiquidDock(selected,{selected=it},backdrop,
                            GlassPolicy(true,true,"test"),true,Modifier.align(Alignment.BottomCenter))
                    }
                }
            }
        }
        rule.waitForIdle()
        fun assertPageVisible() {
            val pixels=rule.onRoot().captureToImage().toPixelMap()
            val center=pixels[pixels.width/2,pixels.height/2]
            assertTrue("The page must remain green while the glass is pressed: $center",
                center.green>.45f&&center.red<.3f&&center.blue<.45f)
        }
        assertPageVisible()
        val first=rule.onNodeWithTag("nav-0")
        val lastCenter=rule.onNodeWithTag("nav-3").fetchSemanticsNode().boundsInRoot.center
        first.performTouchInput { down(center) }
        rule.waitForIdle()
        assertPageVisible()
        val local=lastCenter-first.fetchSemanticsNode().boundsInRoot.topLeft
        first.performTouchInput { advanceEventTime(350);moveTo(local) }
        rule.waitForIdle()
        assertPageVisible()
        first.performTouchInput { cancel() }
        rule.waitForIdle()
        assertPageVisible()
    }
}
