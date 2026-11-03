package com.glasssign.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

/**
 * Optics of the adopted upstream-adapted lens. Two halves:
 *
 *  - pure magnitude math, which runs on every API level and pins the clamps that keep the bend a rim;
 *  - real pixels through [OpticalPass] and [EdgeLightPass], which need API 33+ because the effects are
 *    Android AGSL `RuntimeShader`s.
 *
 * Deliberately device-free: `createComposeRule` only. It does not assert "looks like the video"; it pins
 * the measurable properties that were wrong before (a bevel that could swallow a short capsule, a bend
 * larger than its own bevel, a rim light that was a straight white gradient) and the ones a regression
 * would silently undo.
 */
class LiquidGlassOpticsV076Test {
    @get:Rule val rule=createComposeRule()

    private companion object {
        const val d=2.6f
        /**
         * OpticalPass chains a Gaussian behind the lens, and `RenderEffect.createBlurEffect(0f, …)` throws
         * IllegalArgumentException on API 33+ ("radius > 0"). Production tolerates that only because the
         * call site wraps it in runCatching; a test that passes 0 here would error instead of asserting.
         */
        const val BLUR_PX=0.01f
    }

    private val dense=GlassOptics.BaseBandMax.value*d

    // ---------------------------------------------------------------------------------------------
    // Pure magnitude math
    // ---------------------------------------------------------------------------------------------

    /** The bevel is a rim, never the whole lens: this is the defect the previous sin-band shipped. */
    @Test fun bandStaysARimForEveryLensHeight() {
        for(hDp in listOf(1f,8f,20f,40f,56f,60f,72f,88f,120f,400f)) {
            val h=hDp*d
            for(drag in listOf(true,false)) {
                val band=glassBandPx(h,drag,d)
                assertTrue("band must be positive for ${hDp}dp",band>0f)
                assertTrue("band $band must leave interior free for ${hDp}dp (drag=$drag)",band<=h*.5f)
                // A rim on a normal dock/lens means at most a fifth of its own height.
                if(hDp>=20f) assertTrue("band $band is not a rim on ${hDp}dp (drag=$drag)",band<=h*.2f+0.01f)
            }
        }
    }

    /** An 18dp band on a 46dp capsule used to bend all of it; the drag cap must stay under 8dp. */
    @Test fun dragBandIsCappedSoTheCapsuleKeepsAFlatMiddle() {
        val band=glassBandPx(46f*d,true,d)
        assertTrue("drag band $band must stay under 8dp",band<8f*d)
        assertTrue("drag cap must be tighter than the static cap",glassBandPx(1000f,true,d)<glassBandPx(1000f,false,d))
    }

    /** The bevel has a floor, so a small lens still refracts instead of turning into a flat blur. */
    @Test fun bandHasAFloorOnSmallLenses() {
        val band=glassBandPx(12f*d,false,d)
        assertTrue("a 12dp lens must still bend at least 1dp",band>=1f*d)
    }

    /** The displacement is produced by the bevel, so it can never be larger than the bevel it comes from. */
    @Test fun bendIsDerivedFromTheBandAndNeverExceedsItsLens() {
        for(hDp in listOf(20f,46f,60f,72f,88f,300f)) {
            val h=hDp*d
            for(drag in listOf(true,false)) {
                val band=glassBandPx(h,drag,d)
                val bend=glassBendPx(h,drag,d)
                assertTrue("bend $bend must not exceed the lens for ${hDp}dp",bend<=h*.4f+0.01f)
                val ratio=if(drag)GlassOptics.LensBendRatio else GlassOptics.BaseBendRatio
                assertTrue("bend $bend must come from band $band x $ratio",abs(bend-band*ratio)<=0.01f)
            }
        }
    }

    /** Degenerate input must not produce NaN/huge uniforms that a GPU would turn into a black dock. */
    @Test fun opticsAreSafeAtZeroAndNegativeSizes() {
        assertEquals(1f,glassBandPx(0f,false,d),0f)
        assertEquals(0f,glassBendPx(0f,false,d),0f)
        assertEquals(1f,glassBandPx(-5f,true,d),0f)
        assertEquals(0f,glassBendPx(100f,false,0f),0f)
    }

    /** Upstream's circular profile: monotonic, 0 at the rim, 1 at the depth limit, no sine. */
    @Test fun circleProfileIsTheUpstreamOne() {
        assertEquals(0f,glassCircleMap(0f),1e-5f)
        assertEquals(1f,glassCircleMap(1f),1e-5f)
        assertTrue("mid-band must sit below a linear ramp",glassCircleMap(.5f)<.5f)
        var previous=-1f
        for(i in 0..20) {
            val v=glassCircleMap(i/20f)
            assertTrue("circleMap must be monotonic at $i",v>=previous)
            previous=v
        }
        // Clamped outside its domain rather than producing NaN.
        assertEquals(1f,glassCircleMap(4f),1e-5f)
        assertEquals(0f,glassCircleMap(-4f),1e-5f)
    }

    /** Dispersion must be visible on the moving capsule and quieter on the static bar. */
    @Test fun dispersionIsStrongOnTheCapsuleAndFaintOnTheBar() {
        assertTrue("capsule dispersion must be clearly visible",GlassOptics.LensDispersion>=.8f)
        assertTrue("bar dispersion must be below the capsule",GlassOptics.BarDispersion<GlassOptics.LensDispersion)
        assertTrue("bar dispersion must still be non-zero",GlassOptics.BarDispersion>0f)
    }

    /**
     * The reference is smoked-dark but still readable through: the tint must stay a veil, not a wall, and
     * flattening it to "opaque surface with a white border" must fail this test.
     */
    @Test fun theBarTintStaysAVeilSoTextBehindItRemains() {
        assertTrue("bar tint must stay under 0.3 so text shows through",GlassOptics.BarTintAlpha<=.3f)
        assertTrue("bar tint must still darken",GlassOptics.BarTintAlpha>=.15f)
        assertTrue("capsule tint must be lighter than the bar",GlassOptics.CapsuleTintAlpha<GlassOptics.BarTintAlpha)
        // Raising white opacity is explicitly not the way to fake thickness.
        assertTrue("the lit rim must be under half opaque",GlassOptics.LitAlpha<=.5f)
        assertTrue("the capsule rim must not become a white block",GlassOptics.CapsuleEdgeLight<1f)
    }

    // ---------------------------------------------------------------------------------------------
    // Real pixels through the AGSL passes
    // ---------------------------------------------------------------------------------------------

    private fun host(tag:String,content:@androidx.compose.runtime.Composable ()->Unit) {
        rule.setContent {
            // Desktop density: the magnitude assertions below are written in dp x density.
            CompositionLocalProvider(LocalDensity provides Density(1f,d)) {
                Box(Modifier.size(200.dp,96.dp)) {
                    Box(Modifier.fillMaxSize().background(Color.Red))
                    content()
                }
            }
        }
    }

    /** Paint a vertical white/black edge to the left, then run the lens over it and read the row. */
    @Test fun lensRefractsTheRimWithoutDarkeningTheMiddle() {
        assumeTrue(android.os.Build.VERSION.SDK_INT>=33)
        val optics=OpticalPass()
        host("probe") {
            Canvas(Modifier.size(200.dp,96.dp).graphicsLayer {
                val h=size.height
                val band=glassBandPx(h,false,d)
                val bend=glassBendPx(h,false,d)
                renderEffect=optics.effect(size.width,h,List(4){16f*d},band,h,bend,d,BLUR_PX,0f)
                compositingStrategy=CompositingStrategy.Offscreen
            }.testTag("probe")) {
                // Left half white, right half red: refracted samples move across this seam.
                drawRect(Color.White,Offset.Zero,Size(size.width/2f,size.height))
                drawRect(Color.Red,Offset(size.width/2f,0f),Size(size.width/2f,size.height))
            }
        }
        val image:ImageBitmap=rule.onNodeWithTag("probe").captureToImage()
        val px=image.toPixelMap()
        assertTrue("probe must have pixels",px.width>0&&px.height>0)
        val midY=px.height/2
        // The lens must be opaque and must not collapse to black anywhere in the middle row.
        var dark=0
        for(x in 0 until px.width step 3) {
            val c=px[x,midY]
            assertTrue("lens pixel at $x must stay opaque",c.alpha>.9f)
            if(c.red<.05f&&c.green<.05f&&c.blue<.05f) dark++
        }
        assertTrue("the lens must not black out the row ($dark dark samples)",dark<=2)
        // Outside the bevel the interior must be untouched red: the sample there is the drawn content.
        val interior=px[px.width/4*3,midY]
        assertTrue("lens interior must keep the page colour",interior.red>.5f)
    }

    /** The edge light must be a rim: bright near the border, quiet in the middle. */
    @Test fun edgeLightLightsTheRimAndLeavesTheInteriorAlone() {
        assumeTrue(android.os.Build.VERSION.SDK_INT>=33)
        val pass=EdgeLightPass(false)
        host("rim") {
            Box(Modifier.size(200.dp,96.dp).graphicsLayer {
                renderEffect=pass.effect(size.width,size.height,List(4){16f*d},d)
                alpha=GlassOptics.LitAlpha
                compositingStrategy=CompositingStrategy.Offscreen
            }.testTag("rim"))
        }
        val px=rule.onNodeWithTag("rim").captureToImage().toPixelMap()
        var rimBright=0f
        // Top edge: within the first few pixels of the illuminated side.
        for(x in px.width/4 until px.width*3/4) {
            val c=px[x,1]
            rimBright=maxOf(rimBright,c.alpha*(c.red+c.green+c.blue)/3f)
        }
        val center=px[px.width/2,px.height/2]
        val centerLum=center.alpha*(center.red+center.green+center.blue)/3f
        // A bright rim on a transparent field is only meaningful if the surface itself contributes light.
        if(rimBright<=.02f) {
            throw AssertionError("the rim was not lit at all (max alpha-luma $rimBright): a white-plus highlight cannot be verified")
        }
        assertTrue("the rim must be lit (was $rimBright)",rimBright>.05f)
        assertTrue("the interior must stay much darker than the rim ($centerLum vs $rimBright)",
            centerLum<rimBright*.5f)
    }

    /**
     * The ambient half is one-sided by construction: it must not mirror the lit half. One test, one
     * `setContent` — `ComposeTestRule.setContent` may be called only once per test, so both rim layers are
     * mounted in a single composition (side by side) and read from one capture.
     */
    @Test fun litAndAmbientRimsAreNotTheSameLayer() {
        assumeTrue(android.os.Build.VERSION.SDK_INT>=33)
        // Both variants must build; two fresh handles are never identical, so the real comparison is pixel-based.
        val (litTop,litBottom,ambientTop,ambientBottom)=bothRimEdges()
        // The lit pass peaks where the surface normal faces the light: its two sides differ.
        assertTrue("lit rim must differ between its sides (top=$litTop bottom=$litBottom)",
            abs(litTop-litBottom)>.02f)
        // The ambient half is an inner shadow, so it cannot reproduce the lit pass's top/bottom split.
        val litSplit=litTop-litBottom
        val ambientSplit=ambientTop-ambientBottom
        assertTrue("the ambient pass must not mirror the lit split (lit=$litSplit ambient=$ambientSplit)",
            abs(litSplit-ambientSplit)>.02f)
    }

    /**
     * Mounts the lit and the ambient rim layer side by side in ONE composition and returns
     * (litTop, litBottom, ambientTop, ambientBottom) from a single capture, so a test still calls
     * `setContent` exactly once.
     */
    private data class RimEdges(val litTop:Float,val litBottom:Float,val ambientTop:Float,val ambientBottom:Float)

    private fun bothRimEdges():RimEdges {
        host("edge-pair") {
            Row(Modifier.size(200.dp,96.dp)) {
                for(ambient in listOf(false,true)) {
                    val pass=EdgeLightPass(ambient)
                    val tag=if(ambient)"bit-ambient" else "bit-lit"
                    Box(Modifier.size(100.dp,96.dp).graphicsLayer {
                        val r=16f*d
                        renderEffect=pass.effect(size.width,size.height,List(4){r},d)
                        alpha=GlassOptics.LitAlpha
                        compositingStrategy=CompositingStrategy.Offscreen
                    }.testTag(tag))
                }
            }
        }
        fun edges(tag:String):Pair<Float,Float> {
            val p=rule.onNodeWithTag(tag).captureToImage().toPixelMap()
            fun lum(x:Int,y:Int):Float { val c=p[x,y]; return c.alpha*(c.red+c.green+c.blue)/3f }
            var top=0f;var bottom=0f
            for(x in 1 until p.width-1) { top=maxOf(top,lum(x,1)); bottom=maxOf(bottom,lum(x,p.height-2)) }
            return top to bottom
        }
        val (litTop,litBottom)=edges("bit-lit")
        val (ambientTop,ambientBottom)=edges("bit-ambient")
        return RimEdges(litTop,litBottom,ambientTop,ambientBottom)
    }
}
