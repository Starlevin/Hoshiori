package com.glasssign.app.ui

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

class LiquidRenderV072Test {
    @get:Rule val rule=createReleaseComposeRule()

    @Test fun nativeLensSamplesItsDrawnContentAndKeepsGaussianBlur() {
        assumeTrue(Build.VERSION.SDK_INT>=33)
        // Construct directly: a shader compile failure must fail this test instead of hiding in the UI fallback.
        val optics=OpticalPass()
        rule.setContent {
            Canvas(Modifier.size(160.dp,80.dp).graphicsLayer {
                renderEffect=optics.effect(size.width,size.height,List(4){16*density},
                    band=8*density,h=size.height,amount=8*density,density=density,blur=8*density,dispersion=0f)
                compositingStrategy=CompositingStrategy.Offscreen
            }.testTag("optical-probe")) {
                for(x in 0 until size.width.toInt() step 4)drawRect(
                    if((x/4)%2==0)Color.Red else Color.Blue,Offset(x.toFloat(),0f),Size(4f,size.height)
                )
            }
        }
        val pixels=rule.onNodeWithTag("optical-probe").captureToImage().toPixelMap()
        val center=pixels[pixels.width/2,pixels.height/2]
        assertTrue("sampled render node must remain opaque",center.alpha>.95f)
        assertTrue("blurred stripes must contain red and blue",center.red>.15f&&center.blue>.15f)
        assertTrue("Gaussian blur must mix the narrow stripes",abs(center.red-center.blue)<.25f)
        assertTrue("empty/white source must not be mistaken for the red-blue pattern",center.green<.08f)
    }
}
