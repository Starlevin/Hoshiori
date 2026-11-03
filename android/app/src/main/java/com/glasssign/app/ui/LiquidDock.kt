package com.glasssign.app.ui

import android.animation.ValueAnimator
import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.view.accessibility.AccessibilityManager
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

class PageBackdropState(val cached:GraphicsLayer,val capture:Boolean=true,val upstream:LayerBackdrop?=null) {
    var ready by mutableStateOf(false)
        internal set
    var captureRevision by mutableIntStateOf(0)
        internal set
}

fun Modifier.backdropSource(layer:GraphicsLayer,enabled:Boolean):Modifier=if(!enabled)this else drawWithContent {
    layer.record {this@drawWithContent.drawContent()};drawLayer(layer)
}
fun Modifier.backdropSource(state:PageBackdropState):Modifier=if(!state.capture)this else if(state.upstream!=null)
    layerBackdrop(state.upstream).drawWithContent {
        @Suppress("UNUSED_VARIABLE") val revision=state.captureRevision
        drawContent()
        if(!state.ready&&size.width>0f&&size.height>0f)state.ready=true
    }
else drawWithContent {
    @Suppress("UNUSED_VARIABLE") val revision=state.captureRevision
    state.cached.record {this@drawWithContent.drawContent()}
    drawLayer(state.cached)
    // One transition after the first real page recording, never an every-frame invalidation loop.
    if(!state.ready&&size.width>0f&&size.height>0f)state.ready=true
}

data class GlassPolicy(val blur:Boolean,val motion:Boolean,val reason:String)
fun glassPolicy(api:Int,lowRam:Boolean,powerSave:Boolean,accessible:Boolean,reduceMotion:Boolean,animators:Boolean,mode:String):GlassPolicy {
    val reason=when {
        mode=="off"->"玻璃已关闭";mode=="light"->"轻量材质";api<31->"系统不支持实时模糊"
        lowRam->"低内存设备";powerSave->"系统省电模式";accessible->"辅助功能高对比";else->"实时背景采样"
    }
    return GlassPolicy(mode=="standard"&&api>=31&&!lowRam&&!powerSave&&!accessible,!reduceMotion&&animators,reason)
}
@Composable fun currentGlassPolicy():GlassPolicy {
    val context=LocalContext.current
    val powerManager=context.getSystemService(PowerManager::class.java)
    val accessibility=context.getSystemService(AccessibilityManager::class.java)
    var explored by remember{mutableStateOf(accessibility.isTouchExplorationEnabled)}
    var power by remember{mutableStateOf(powerManager.isPowerSaveMode)}
    DisposableEffect(accessibility) {
        val listener=AccessibilityManager.TouchExplorationStateChangeListener{explored=it}
        accessibility.addTouchExplorationStateChangeListener(listener)
        onDispose{accessibility.removeTouchExplorationStateChangeListener(listener)}
    }
    DisposableEffect(powerManager) {
        val receiver=object:android.content.BroadcastReceiver(){override fun onReceive(c:Context,i:android.content.Intent){power=powerManager.isPowerSaveMode}}
        androidx.core.content.ContextCompat.registerReceiver(context,receiver,android.content.IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose{context.unregisterReceiver(receiver)}
    }
    return glassPolicy(Build.VERSION.SDK_INT,context.getSystemService(ActivityManager::class.java).isLowRamDevice,power,explored,
        LocalReduceMotion.current,ValueAnimator.areAnimatorsEnabled(),LocalAppearance.current.glassMode)
}

object GlassTokens {
    val corner=34.dp;val margin=18.dp;val blur=18.dp;val lift=12.dp;val maxWidth=520.dp
    val lensInset=6.dp;val lensGap=3.dp
}

/**
 * Optical magnitudes for the dock material. The pipeline is adapted from the Apache-2.0 upstream `backdrop`
 * (see [OpticalPass]); the magnitudes below are tuned to the reference capture, not copied from upstream's
 * catalog demo, and every one of them is a dp value resolved to px at draw time so the look is density
 * independent. Reference behaviour being matched: a thick lens whose bend is concentrated in a narrow rim
 * (a calm middle), a visible sub-pixel rim rainbow, and a rim light that reads as surface rather than paint.
 */
internal object GlassOptics {
    /** Depth of the refracting bevel as a fraction of lens height. Keep it small: the bend must be a rim. */
    const val BaseBandRatio=.16f
    /** Hard cap on the bevel, so a tall bar can never bend its whole interior. */
    val BaseBandMax=9.dp
    /** Cap while dragging, so the stretched capsule still shows a flat middle. */
    val DragBandMax=6.dp
    /** Floor, so the bevel never collapses below one pixel at small sizes. */
    val BandMin=3.dp
    /** Peak displacement of the bar: a thick lens, still a fraction of its own bevel x 3. */
    const val BaseBendRatio=2.4f
    /** Peak signed displacement of the travelling capsule: negative bends the rim outwards (raised lens). */
    const val LensBendRatio=2.0f
    /** Dispersion on the travelling lens: the reference's rainbow rim is clearly visible while it moves. */
    const val LensDispersion=.9f
    /** Faint dispersion on the bar so its static rim also separates colours, below the capsule's. */
    const val BarDispersion=.35f
    /** Upstream highlight defaults (45 deg, falloff 1). */
    const val EdgeLightAngleDegrees=45f
    const val EdgeLightFalloff=1f
    const val LitAlpha=.5f
    const val AmbientAlpha=.38f
    /** Light on the bar vs on the moving capsule: the capsule is the thicker, brighter piece of glass. */
    const val BarEdgeLight=.18f
    const val CapsuleEdgeLight=.30f
    /** Tint over the sampled backdrop. Low on purpose: text behind the bar must stay readable. */
    const val BarTintAlpha=.22f
    const val CapsuleTintAlpha=.075f
}

/**
 * Bevel depth in px for a lens of [heightPx], honouring the ratio limit and both absolute bounds.
 * Pure so the clamp can be pinned without a device: the shipped defect was a fixed band that exceeded a
 * short capsule and bent all of it, and the opposite defect (a band too thin to read as thick glass) is
 * equally testable here.
 */
internal fun glassBandPx(heightPx:Float,drag:Boolean,density:Float):Float {
    if(heightPx<=0f||density<=0f) return 1f
    val ratio=heightPx*GlassOptics.BaseBandRatio
    val cap=(if(drag)GlassOptics.DragBandMax else GlassOptics.BaseBandMax).value*density
    val floor=GlassOptics.BandMin.value*density
    return ratio.coerceAtMost(cap).coerceIn(floor,heightPx*.5f)
}

/**
 * Peak displacement in px for a lens of [heightPx]: proportional to its bevel, so the lens thickness and the
 * bend can never disagree. Never exceeds the bevel it is produced by.
 */
internal fun glassBendPx(heightPx:Float,drag:Boolean,density:Float):Float {
    if(heightPx<=0f||density<=0f) return 0f
    val band=glassBandPx(heightPx,drag,density)
    val ratio=if(drag)GlassOptics.LensBendRatio else GlassOptics.BaseBendRatio
    return (band*ratio).coerceAtMost(heightPx*.4f).coerceAtLeast(0f)
}

/** Upstream `circleMap`: displacement falls off as a circular profile across the bevel, not a sine. */
internal fun glassCircleMap(x:Float):Float {
    val t=x.coerceIn(0f,1f)
    return 1f-Math.sqrt((1f-t*t).toDouble()).toFloat()
}
@Composable fun liquidDockHeight(fontScale:Float)=if(fontScale>1.3f)88.dp else 72.dp

/**
 * Total vertical space the dock overlay occupies, in px, from the screen's bottom edge up:
 * the bar itself, plus the transparent [GlassTokens.lift] gap, plus the navigation-bar inset the dock
 * applies via `navigationBarsPadding()`.
 *
 * Why a pure function: the page list needs exactly this number as its bottom `contentPadding`, and the
 * hard-coded 160.dp it used before was wrong for gesture navigation (small inset), for a tall bar at
 * large font scale, and in landscape where the dock is capped. Keeping it pure means the arithmetic is
 * pinned by JVM tests instead of by a screenshot.
 *
 * [navBottomPx] is the navigation-bar inset ALREADY resolved to px. It is added exactly once here and the
 * dock applies it via `navigationBarsPadding()`; callers must therefore NOT add it again, or the last card
 * would stop a nav-bar too early.
 */
internal fun dockOverlapPx(barHeightPx:Float,liftPx:Float,navBottomPx:Float):Float =
    (barHeightPx.coerceAtLeast(0f)+liftPx.coerceAtLeast(0f)+navBottomPx.coerceAtLeast(0f))

/**
 * Bottom content padding for the page list, derived from the measured dock overlap.
 *
 * [gapPx] is a small breathing space so the last card clears the dock's shadow instead of touching it.
 * The result is never below [minPx], because a zero or collapsed dock (hidden for a modal, or mid-cold-
 * start before the first layout) must not remove the list's bottom padding entirely.
 */
internal fun dockContentPaddingPx(overlapPx:Float,gapPx:Float,minPx:Float):Float {
    val floor=if(minPx.isFinite())minPx.coerceAtLeast(0f) else 0f
    if(!overlapPx.isFinite()) return floor
    val safeGap=if(gapPx.isFinite())gapPx.coerceAtLeast(0f) else 0f
    return (overlapPx.coerceAtLeast(0f)+safeGap).coerceAtLeast(floor)
}

/** Small breathing space between the last card and the dock's shadow. */
internal val DockContentGap=8.dp

/**
 * Floor for the list's bottom padding while the dock has not measured itself yet (first frame, or the
 * dock hidden behind a modal). One bar height at the 200% font scale is the tallest the dock can get, so
 * the very first layout can never place the last card under it, and no nav inset is added here: the dock
 * reports its own inset once it has one, which is what keeps the inset from being counted twice.
 */
internal val DockContentMin=96.dp

/** Bottom content padding for the page list, in Dp, from the px the dock reported. */
internal fun dockBottomPaddingPx(overlapPx:Float,density:Density):Dp {
    val d=if(density.density>0f)density.density else 1f
    val gap=DockContentGap.value*d
    val min=DockContentMin.value*d
    return (dockContentPaddingPx(overlapPx,gap,min)/d).dp
}

@Composable fun rememberBackdrop():PageBackdropState=rememberPageBackdrop(true)
@Composable fun rememberPageBackdrop(blurEnabled:Boolean):PageBackdropState {
    val layer=rememberGraphicsLayer()
    val upstream=rememberLayerBackdrop(layer)
    return remember(layer,blurEnabled,upstream){PageBackdropState(layer,blurEnabled,upstream)}
}

private val titles=listOf("首页","账号","记录","设置")
// Icons come from the shared 24px rounded-stroke set owned by ui/HoshioriIcons.kt (root agent's scope);
// this dock only picks the tint, so the selected colour keeps flowing through MaterialTheme.
private val icons=listOf(HoshioriIcons.Home,HoshioriIcons.Accounts,HoshioriIcons.Records,HoshioriIcons.Settings)

/** Number of dock slots; the drag math, the clamp and the semantics all read this one value. */
private const val DockSlots=4

/**
 * Distance, in cells, the capsule must be pulled away from the slot it started on (its own travel and
 * the finger's travel both counted, see [dockReleaseSlot]) before a release selects another slot.
 * 0.5 = "past the neighbour's centre", so the nearest slot always wins and one flick never skips one.
 */
private const val DockCommitDistance=.5f

/**
 * @param onOverlapChange reports the dock's actual visible overlay height in px, measured after layout,
 *   including the navigation-bar inset and the transparent lift gap. The page list uses it as its bottom
 *   content padding so the last card can scroll entirely above the dock. It is reported only when the
 *   value actually changes (integer px), so scrolling and dragging never cause a write.
 *
 * [onOverlapChange] is deliberately the LAST parameter: existing call sites pass a positional `Modifier`
 * sixth, and inserting the callback before it would silently bind that Modifier to the wrong parameter.
 */
@Composable fun LiquidDock(selected:Int,onSelect:(Int)->Unit,source:GraphicsLayer,policy:GlassPolicy,enabled:Boolean=true,modifier:Modifier=Modifier,onOverlapChange:(Float)->Unit={}) {
    Dock(selected,onSelect,source,policy,enabled,modifier,onOverlapChange)
}
@Composable fun LiquidDock(selected:Int,onSelect:(Int)->Unit,backdrop:PageBackdropState,policy:GlassPolicy,enabled:Boolean=true,modifier:Modifier=Modifier,onOverlapChange:(Float)->Unit={}) {
    // Do not build a native sampling effect over an empty first-frame RenderNode. Keep the dock
    // visible and interactive using its opaque fallback until the page has actually been recorded.
    var sampledFrameReady by remember(backdrop){mutableStateOf(false)}
    LaunchedEffect(backdrop.ready,policy.blur) {
        sampledFrameReady=false
        if(backdrop.ready&&policy.blur) {
            // Recording a display list is earlier than its first presented GPU frame. Let that frame
            // finish before introducing a native effect that reads it; the opaque dock stays usable.
            withFrameNanos { }
            // Invalidate the captured draw node itself, not merely the host View: a host invalidation
            // can keep reusing the first recorded display list without refreshing the shared input.
            backdrop.captureRevision++
            withFrameNanos { }
            sampledFrameReady=true
        }
    }
    // Material activation replaces the provisional layer once, so its first opaque display list
    // cannot retain an empty shared-input cache. This key is stable throughout navigation/dragging.
    key(sampledFrameReady) {
        if(sampledFrameReady&&policy.blur&&policy.motion&&Build.VERSION.SDK_INT>=33&&backdrop.upstream!=null&&enabled)
            UpstreamLiquidDock(selected,onSelect,backdrop.upstream,modifier,onOverlapChange)
        else Dock(selected,onSelect,backdrop.cached,policy.copy(blur=policy.blur&&sampledFrameReady),enabled,modifier,onOverlapChange)
    }
}
@Composable fun LiquidDock(selected:Int,onSelect:(Int)->Unit,source:GraphicsLayer,policy:GlassPolicy,modifier:Modifier) {
    Dock(selected,onSelect,source,policy,true,modifier)
}

@Composable private fun Dock(selected:Int,onSelect:(Int)->Unit,source:GraphicsLayer,policy:GlassPolicy,enabled:Boolean,modifier:Modifier,onOverlapChange:(Float)->Unit={}) {
    val colors=MaterialTheme.colorScheme
    val dark=colors.background.luminance()<.5f
    val off=LocalAppearance.current.glassMode=="off"
    val height=liquidDockHeight(LocalDensity.current.fontScale)
    val shape=RoundedCornerShape(GlassTokens.corner)
    var position by remember{mutableStateOf(Offset.Zero)}
    val current=selected.coerceIn(0,DockSlots-1)
    val scope=rememberCoroutineScope()
    val density=LocalDensity.current
    // Same spec as the old `animateFloatAsState`; snap() with motion off, so reduce-motion still drags.
    val motionSpec=if(policy.motion)spring<Float>(dampingRatio=.73f,stiffness=480f) else snap()
    /*
     * Capsule position in cells: the ONLY thing that moves the capsule. It is an Animatable we drive
     * ourselves instead of `animateFloatAsState(selected)`, because three sources have to share one value:
     *  - idle: a LaunchedEffect keyed on the selection animates to it (exactly the previous spring/snap,
     *    so tap navigation, state restore and the a11y tree are unchanged);
     *  - drag: the pointer coroutine snaps it to the finger, making the capsule continuous across the four
     *    slots without ever rebuilding a page or recomposing the bar;
     *  - release / cancel: one spring (snap without motion) from where the finger left it.
     * The only composition consumer is the capsule's offset lambda, so reads happen during placement.
     */
    val capsule=remember{Animatable(current.toFloat())}
    val dragging=remember{DockDrag(scope)}
    val onSettleNow by rememberUpdatedState<(Float)->Unit> {
        dragging.settleTo(capsule,it,motionSpec,policy.motion)
    }
    LaunchedEffect(current,dragging.generation) {
        if(dragging.engaged) return@LaunchedEffect
        if(capsule.value!=current.toFloat()) onSettleNow(current.toFloat())
    }
    // Handles a selection that changed while the dock was not composed (tab restore), where the effect
    // above cannot see it. Deliberately inert while a spring is running or a finger owns the capsule.
    SideEffect {if(!dragging.engaged&&!capsule.isRunning) onSettleNow(current.toFloat())}
    /*
     * Lens thickness under the finger. The press is tracked on the dock's OWN interaction source, which the
     * gesture layer below feeds; collectIsPressedAsState only flips once per press, so this costs one
     * recomposition per press (not per frame) and never touches the page. The value itself is a plain field,
     * so the swell is rendered by the capsule's layer lambda and not by this composition.
     */
    val dockInteraction=remember{MutableInteractionSource()}
    val dockPressed by dockInteraction.collectIsPressedAsState()
    LaunchedEffect(dockPressed) {
        if(dockPressed) {
            val start=withFrameNanos{it}
            // Cheap, bounded ramp (160ms, frame clock, no infinite animation) so the swell reads as a bulge
            // appearing under the finger instead of popping on.
            while(true) {
                val t=((withFrameNanos{it}-start)/1_000_000f/160f).coerceIn(0f,1f)
                dragging.bulge=if(policy.motion)t else 1f
                if(t>=1f) break
            }
        } else {
            dragging.bulge=0f
        }
    }
    // The border stays as the non-blur (API<31 / light material) rim. With blur on, the SDF edge light above
    // supplies the rim, so this hairline only adds the thin hairline the reference keeps at the very edge.
    /*
     * Rim strategy. The reported defect was a stark, uniform WHITE outline around the whole bar: a 1.dp
     * white `border` drew a complete ring, and the SDF edge light then drew a second, brighter ring on top
     * of it. Two white rings at full circumference read as paint, not glass.
     *
     * Now they are mutually exclusive and one-directional:
     *  - with blur on, the SDF edge light is the only rim (it is already directional: brightness follows
     *    |dot(grad(SDF), lightDir)|, so one side is lit and the opposite side stays dark);
     *  - with blur off (API<31, or "off" mode), a faint hairline keeps the shape legible, never full white.
     * The container gradient is top-light/bottom-dark, so even that hairline is not uniform.
     */
    val edge=Brush.linearGradient(listOf(
        Color.White.copy(alpha=if(dark).16f else .26f),
        colors.outlineVariant.copy(alpha=.10f),
        Color.White.copy(alpha=if(dark).05f else .10f),
    ))
    // Actual overlay height, reported after layout. `navigationBarsPadding()` resolves the inset inside the
    // layout phase, so it is read from the same node the padding is applied to instead of from the window
    // insets: that way the number is exactly what the dock occupies, adds the inset once, and stays correct
    // for gesture navigation, a tall bar at large font scale and the landscape width cap.
    val navBottomPx=WindowInsets.navigationBars.getBottom(density).toFloat()
    val liftPx=with(density){GlassTokens.lift.toPx()}
    val reportOverlap by rememberUpdatedState(onOverlapChange)
    // Last reported integer px, so the callback fires on a real change only (never per frame).
    // Plain holder (no delegate): an IntArray cell needs no getValue/setValue operators, so this compiles without
    // androidx.compose.runtime.{get,set}Value imports; `remember` keeps the same instance across recompositions.
    val reportedOverlap=remember{intArrayOf(-1)}
    Box(modifier.zIndex(1f).fillMaxWidth().onSizeChanged { size ->
        if(size.height!=reportedOverlap[0]) {reportedOverlap[0]=size.height;reportOverlap(size.height.toFloat())}
    }.navigationBarsPadding().padding(horizontal=GlassTokens.margin).padding(bottom=GlassTokens.lift),contentAlignment=Alignment.Center) {
        BoxWithConstraints(Modifier.widthIn(max=GlassTokens.maxWidth).fillMaxWidth().height(height)
            .onGloballyPositioned{position=it.positionInRoot()}
            .graphicsLayer{alpha=if(enabled)1f else 0f}
            .shadow(if(off)0.dp else 12.dp,shape,clip=false)
            .graphicsLayer{compositingStrategy=CompositingStrategy.Offscreen}.clip(shape)
            .then(if(enabled)Modifier else Modifier.semantics{invisibleToUser()})
            .testTag("floating-dock")) {
            if(policy.blur) SampledGlass(source,{position},GlassTokens.blur.value,GlassTokens.corner.value,true,Modifier.matchParentSize())
            // Smoked dark tint over the sample. Lower than a "frosted card" because the reference lets the
            // page text behind the bar stay legible; the darkening comes from this veil, not from opacity alone.
            Box(Modifier.matchParentSize().background(colors.surface.copy(alpha=if(off)1f else if(policy.blur)GlassOptics.BarTintAlpha else .97f)))
            // Directional top highlight only (no full ring). The dark veil below it is what gives the bar its
            // body, so the highlight is a sheen rather than an outline.
            if(!off)Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.White.copy(alpha=if(dark).07f else .16f),Color.Transparent,Color.Black.copy(alpha=.06f)))))
            // Hairline ONLY when there is no SDF edge light, so the two never stack into a white outline.
            if(!off&&!policy.blur)Box(Modifier.matchParentSize().border(1.dp,edge,shape))
            if(!off&&policy.blur)GlassEdgeLight(GlassTokens.corner.value,GlassOptics.BarEdgeLight,Modifier.matchParentSize())
            if(enabled) {
                val cellWidth=(maxWidth-12.dp)/4
                val cellPx=with(density){cellWidth.toPx()}
                val padPx=with(density){6.dp.toPx()}
                val onSelectNow by rememberUpdatedState(onSelect)
                val lensShape=RoundedCornerShape(50)
                Box(Modifier.padding(top=6.dp).offset{IntOffset((padPx+cellPx*capsule.value).roundToInt(),0)}
                    .width(cellWidth).height(height-12.dp).graphicsLayer {
                        dragging.stretch(cellPx,capsule.value)
                        scaleX=dragging.scaleX;scaleY=dragging.scaleY
                    }.clip(lensShape).testTag("selected-capsule")) {
                    // Drag highlight: rides with the capsule, independent of the page sample.
                    Box(Modifier.matchParentSize().graphicsLayer {
                        dragging.stretch(cellPx,capsule.value)
                        alpha=dragging.highlight
                    }.background(Brush.horizontalGradient(listOf(Color.Transparent,Color.White.copy(alpha=if(dark).26f else .5f),Color.Transparent))))
                    if(policy.blur) CapsuleGlass(source,{
                        position+with(density){Offset((6.dp+cellWidth*capsule.value).toPx(),6.dp.toPx())}
                    },3.5f,density.density,{dragging.bulge},Modifier.matchParentSize())
                    // Selected item is a small accent-tinted capsule, not a white translucent block: the page
                    // sample stays visible through it, the accent only tints it.
                    Box(Modifier.matchParentSize().background(if(off)colors.primaryContainer else colors.primary.copy(alpha=if(policy.blur)GlassOptics.CapsuleTintAlpha else .12f)))
                    // Inner sheen: top-only, so the capsule reads as a lit convex surface rather than a
                    // white ring. With blur on, the SDF edge light below supplies the rim and this border is
                    // omitted entirely -- two rings were what produced the stark full-white outline.
                    if(!off)Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.White.copy(alpha=if(dark).09f else .18f),Color.Transparent))))
                    if(!off&&!policy.blur)Box(Modifier.matchParentSize().border(.5.dp,Brush.linearGradient(listOf(Color.White.copy(alpha=if(dark).20f else .30f),Color.Transparent,Color.White.copy(alpha=.06f))),lensShape))
                    if(!off&&policy.blur)GlassEdgeLight(50f,GlassOptics.CapsuleEdgeLight,Modifier.matchParentSize())
                }
                // Keep input on the ancestor of the selectable cells so physical taps reach them.
                Row(Modifier.fillMaxSize()
                    .dockDrag(enabled,current,dragging,cellPx,padPx,dockInteraction,
                        onDrag={fingers->scope.launch(start=kotlinx.coroutines.CoroutineStart.UNDISPATCHED){capsule.snapTo(fingers)}},
                        onSettle=onSettleNow,
                        onSelect={if(it!=current)onSelectNow(it)})
                    .testTag("dock-gesture-layer").padding(horizontal=6.dp)) {
                    titles.forEachIndexed {index,title->DockCell(index,title,selected==index,policy.motion,icons[index],onSelect)}
                }
            }
        }
    }
}

@Composable private fun RowScope.DockCell(index:Int,title:String,selected:Boolean,motion:Boolean,icon:ImageVector,onSelect:(Int)->Unit) {
    val colors=MaterialTheme.colorScheme
    val interaction=remember{MutableInteractionSource()}
    val pressed by interaction.collectIsPressedAsState()
    val scale=animateFloatAsState(if(pressed&&motion).94f else 1f,if(motion)spring(stiffness=700f) else snap(),label="dock-press")
    Column(Modifier.weight(1f).fillMaxHeight().heightIn(min=48.dp).graphicsLayer{scaleX=scale.value;scaleY=scale.value}
        .selectable(selected,role=Role.Tab,interactionSource=interaction,indication=null,onClick={onSelect(index)})
        .semantics{contentDescription=title;stateDescription=if(selected)"已选中" else "未选中"}.testTag("nav-$index"),
        verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally) {
        Icon(icon,null,tint=if(selected)colors.primary else colors.onSurfaceVariant,modifier=Modifier.size(23.dp))
        Spacer(Modifier.height(3.dp))
        Text(title,style=MaterialTheme.typography.labelMedium,color=if(selected)colors.onSurface else colors.onSurfaceVariant,maxLines=1)
    }
}

// -----------------------------------------------------------------------------------------------
// Drag state and pure release math
// -----------------------------------------------------------------------------------------------

/**
 * Gesture ownership and capsule stretch for a drag.
 *
 * Not snapshot state, on purpose: the pointer coroutine writes it and the capsule's graphicsLayer lambda
 * reads it per frame, so following a finger costs a layer update rather than a recomposition (and never a
 * page rebuild). It lives in one `remember` in [Dock], so a restarted gesture loop sees the same instance.
 */
private class DockDrag(private val scope:CoroutineScope) {
    /**
     * True from the first post-slop move until the return animation has finished. The gesture coroutine
     * only ever claims it; [settleTo] clears it from the frame scope that finishes the return.
     */
    @Volatile private var engagedFlag:Boolean=false
    val engaged:Boolean get()=engagedFlag
    fun claim() {engagedFlag=true}
    /** Bumped on every settle, so [Dock]'s idle selection effect re-runs once the capsule is home. */
    @Volatile var generation:Int=0
    @Volatile var scaleX:Float=1f
    @Volatile var scaleY:Float=1f
    @Volatile var highlight:Float=0f
    /**
     * 0..1 thickness of the lens under the finger. Written on the pointer coroutine and on press changes,
     * read only inside a graphicsLayer lambda, so a press or a drag never recomposes the dock.
     */
    var bulge by mutableFloatStateOf(0f)

    /**
     * Stretch and highlight for the capsule's CURRENT cell position: its distance from the slot it is
     * nearest to. A capsule standing on a slot is never scaled, so this reads as "being pulled" and cannot
     * wiggle while the finger rests between two slots.
     */
    fun stretch(cellPx:Float,position:Float) {
        if(!engagedFlag||cellPx<=0f) {scaleX=1f;scaleY=1f;highlight=0f;return}
        val pull=abs(position-position.roundToInt()).coerceIn(0f,1f)
        scaleX=1f+pull*.05f
        scaleY=1f-pull*.03f
        highlight=(pull*.38f).coerceIn(0f,.38f)
    }

    /**
     * One return to [target]: an elastic spring when motion is on, an immediate snap when the policy
     * disables animation. The capsule is handed back to [Dock]'s idle selection effect afterwards, which
     * is also what repairs the case where `onSelect` did not actually change the selection.
     *
     * Launched (not awaited) in the dock's scope, because the caller is a gesture callback that must not
     * block on an animation: the return has to outlive the gesture coroutine.
     */
    fun settleTo(capsule:Animatable<Float,AnimationVector1D>,target:Float,spec:AnimationSpec<Float>,motion:Boolean) {
        generation++
        val goal=target.coerceIn(0f,(DockSlots-1).toFloat())
        scope.launch {
            try {
                if(motion) capsule.animateTo(goal,spec) else capsule.snapTo(goal)
            } finally {
                engagedFlag=false
                generation++
            }
        }
    }
}

/** Centre of slot [index] in px, for a bar whose cells start at [pad]. Pure: shared by the drag and tests. */
internal fun dockSlotCentre(index:Int,cell:Float,pad:Float):Float=pad+cell*(index+.5f)

/** Slot whose centre is closest to [x] (bar coordinates, [pad] included). */
internal fun dockNearestSlot(x:Float,cell:Float,pad:Float):Int {
    if(cell<=0f) return 0
    var best=0;var bestDistance=Float.MAX_VALUE
    for(i in 0 until DockSlots) {
        val d=abs(x-dockSlotCentre(i,cell,pad))
        if(d<bestDistance){best=i;bestDistance=d}
    }
    return best
}

/**
 * Slot a release commits to. Pure, so the rule can be pinned without a device.
 *
 * @param from    slot the gesture started on. Deliberately not the current selection: that selection is
 *                written by this very callback, so the starting slot is the only stable reference.
 * @param reached capsule travel in cells, signed (negative = towards slot 0).
 * @param flick   the finger's own travel in cells, signed. Directional hint only: a quick flick that has
 *                already pulled past the neighbour's centre still commits even if the capsule lagged.
 */
internal fun dockReleaseSlot(from:Int,reached:Float,flick:Float):Int {
    if(from !in 0 until DockSlots) return from.coerceIn(0,DockSlots-1)
    val distance=abs(reached)
    val agrees=(reached>0f&&flick>0f)||(reached<0f&&flick<0f)
    val considered=if(agrees) max(distance,abs(flick)) else distance
    if(considered<DockCommitDistance) return from
    val direction=if(reached>0f) 1 else -1
    val steps=(considered+.5f).toInt()
    return (from+direction*steps).coerceIn(0,DockSlots-1)
}

/**
 * pointerInput restart keys: ONLY inputs that make the gesture meaningless. The selected slot, the
 * gesture phase, the pointer count and the capsule's live position must never appear here — a key change
 * restarts the coroutine mid-gesture, which is exactly how a swallowed or duplicated `onSelect` happens.
 */
internal fun dockGestureKey(enabled:Boolean,motion:Boolean,cellPx:Int,padPx:Int):List<Any> =
    listOf("dock-drag",enabled,motion,cellPx,padPx)

/**
 * Horizontal drag across the dock bar.
 *
 *  - A press that never crosses horizontal touch slop is left untouched: the cell under it clicks.
 *  - Once horizontal slop is crossed the gesture belongs to the dock. Every further change is consumed
 *    here, so no ancestor scrollable sees it (a vertical scroll therefore cannot switch a page), the
 *    capsule rides the finger continuously, and the travel is clamped to the bar.
 *  - Release: at most ONE `onSelect`, only when the release slot differs from the starting slot; then the
 *    capsule springs from where the finger left it to the settled slot.
 *  - Cancellation: no `onSelect` at all, and the capsule returns to the slot the gesture started on.
 *
 * Everything mutable below is local to one gesture, and [startSlot] plus the three callbacks are read
 * through `rememberUpdatedState` wrappers, so a stale lambda or a restarted loop cannot call twice.
 */
@Composable private fun Modifier.dockDrag(
    enabled:Boolean,
    startSlot:Int,
    drag:DockDrag,
    cellPx:Float,
    padPx:Float,
    interactionSource:MutableInteractionSource,
    onDrag:(fingers:Float)->Unit,
    onSettle:(Float)->Unit,
    onSelect:(Int)->Unit,
):Modifier {
    val slot by rememberUpdatedState(startSlot)
    val cell by rememberUpdatedState(cellPx)
    val dragNow by rememberUpdatedState(drag)
    val dragCb by rememberUpdatedState(onDrag)
    val settleCb by rememberUpdatedState(onSettle)
    val selectCb by rememberUpdatedState(onSelect)
    val source by rememberUpdatedState(interactionSource)
    // Emit Press/Release so the lens can swell under the finger. Declarative on purpose
    // (`Modifier.pointerInput` observer form), so this cannot interfere with travel, taps, release, cancel or
    // the selectable cells.
    // PointerEventType is an @JvmInline value class; it cannot be a `when` branch condition, so compare.
    // `PointerEventType.Cancel` only exists from Compose 1.8, and this project is on 1.7.8, so cancellation is
    // detected the original way: the gesture coroutine is cancelled and we emit Cancel from a finally block.
    val pressFilter=Modifier.pointerInput(source,enabled) {
        if(!enabled) return@pointerInput
        awaitEachGesture {
            val down=awaitFirstDown(requireUnconsumed=false)
            val press=PressInteraction.Press(down.position)
            source.tryEmit(press)
            var released=false
            try {
                while(true) {
                    if(awaitPointerEvent().changes.none { it.pressed }) {
                        released=true
                        break
                    }
                }
            } finally {
                source.tryEmit(if(released)PressInteraction.Release(press) else PressInteraction.Cancel(press))
            }
        }
    }
    return pointerInput(dockGestureKey(enabled,true,cellPx.toInt(),padPx.toInt())) {
        if(!enabled) return@pointerInput
        awaitEachGesture {
            val down=awaitFirstDown(requireUnconsumed=false)
            val width=cell
            if(width<=0f) return@awaitEachGesture
            val originSlot=slot.coerceIn(0,DockSlots-1)
            // Absolute finger positions, not accumulated deltas: a change that already arrived consumed
            // on an earlier pass must not make the capsule drift away from the finger.
            val pressX=down.position.x
            // Capsule position in cells for a finger at [x], derived from this gesture's own origin, so
            // portrait, landscape, the 520dp cap and the 130% font height all work with no cached geometry.
            fun capsuleAt(x:Float):Float=
                ((originSlot*width+(x-pressX)).coerceIn(0f,(DockSlots-1)*width))/width
            var dragging=false
            var released=false
            // The capsule is claimed the moment slop is crossed, BEFORE the drag loop starts, or the first
            // `horizontalDrag` would still land in a stale composition, where its positions are ignored.
            val crossed=awaitHorizontalTouchSlopOrCancellation(down.id){change,_->
                if(!dragging) {
                    dragging=true
                    dragNow.claim()
                    change.consume()
                }
            }
            if(crossed!=null) {
                // First drag frame: take the finger's position immediately, so crossing the slop never
                // shows a delayed or jumping capsule.
                dragCb(capsuleAt(crossed.position.x))
                var lastX=crossed.position.x
                val completed=horizontalDrag(crossed.id){move->
                    lastX=move.position.x
                    dragCb(capsuleAt(move.position.x))
                    move.consume()
                }
                if(completed) {
                    released=true
                    // Track the lift position so the final capsule position is exact.
                    dragCb(capsuleAt(lastX))
                    val reached=(lastX-pressX)/width
                    val target=dockReleaseSlot(originSlot,reached,reached)
                    // Exactly one switch, and only for a real change of slot.
                    if(target!=originSlot) selectCb(target)
                    settleCb(target.toFloat())
                }
            }
            if(released) {
                source.tryEmit(PressInteraction.Release(PressInteraction.Press(Offset.Zero)))
            } else {
                // Cancelled, or a press that never crossed slop. A tap leaves the capsule untouched (so it
                // behaves exactly as before this layer existed); a cancelled drag returns to its slot and
                // switches nothing.
                if(dragging) settleCb(originSlot.toFloat())
                source.tryEmit(PressInteraction.Cancel(PressInteraction.Press(Offset.Zero)))
            }
        }
    }.then(pressFilter)
}

/** The render node actually draws the translated page crop; its effect can therefore sample that content. */
@Composable private fun SampledGlass(source:GraphicsLayer,origin:()->Offset,blurDp:Float,cornerDp:Float,refract:Boolean,modifier:Modifier) {
    val optics=if(Build.VERSION.SDK_INT>=33&&refract)remember{runCatching{OpticalPass()}.onFailure{android.util.Log.w("HoshioriGlass","Bar optics unavailable",it)}.getOrNull()} else null
    Box(modifier.graphicsLayer {
        val radius=(cornerDp*density).coerceAtMost(size.height/2f)
        val blur=blurDp*density
        renderEffect=if(Build.VERSION.SDK_INT>=33&&optics!=null) {
            runCatching {
                val h=size.height
                val band=glassBandPx(h,false,density)
                val bend=glassBendPx(h,false,density)
                optics.effect(size.width,h,List(4){radius},band,h,bend,density,blur,GlassOptics.BarDispersion)
            }.getOrElse{BlurEffect(blur,blur,TileMode.Clamp)}
        } else BlurEffect(blur,blur,TileMode.Clamp)
        // Same @RequiresApi(31) reasoning as CapsuleGlass: leave the compositing strategy at the default.
    }.drawBehind {
        val p=origin();translate(-p.x,-p.y){drawLayer(source)}
    })
}

/**
 * Travelling capsule optics: a true lens over the sampled page, dispatching on the LIVE capsule position
 * so following a finger costs GPU work only. [bulge] is 0..1 and swells the lens under the finger; it is
 * written by [DockDrag] on the pointer coroutine and read here inside the layer lambda, so a press never
 * causes a recomposition. Degrades to a plain blur (or to nothing on older APIs), which is exactly the
 * fallback the dock already shipped with.
 */
@Composable private fun CapsuleGlass(source:GraphicsLayer,origin:()->Offset,blurDp:Float,density:Float,bulge:()->Float,modifier:Modifier) {
    val optics=if(Build.VERSION.SDK_INT>=33)remember{runCatching{OpticalPass()}.onFailure{android.util.Log.w("HoshioriGlass","Capsule optics unavailable",it)}.getOrNull()} else null
    Box(modifier.graphicsLayer {
        val blur=blurDp*density
        renderEffect=if(Build.VERSION.SDK_INT>=33&&optics!=null) {
            runCatching {
                val h=size.height
                val band=glassBandPx(h,true,density)
                val bend=glassBendPx(h,true,density)
                // A capsule: all four radii converge on half the short side.
                val shortSide=if(size.width<size.height)size.width else size.height
                val r=shortSide/2f
                // Press: a thicker lens and a stronger, wider rim rainbow under the finger.
                val inflation=bulge()
                val swell=bend*(1f+inflation*.45f)
                val dispersion=GlassOptics.LensDispersion*(1f+inflation*1.1f)
                optics.effect(size.width,h,List(4){r},band,h,-swell,density,blur,dispersion)
            }.getOrElse{BlurEffect(blur,blur,TileMode.Clamp)}
        } else BlurEffect(blur,blur,TileMode.Clamp)
        // No CompositingStrategy here on purpose: it is @RequiresApi(31) and this function serves API 26+,
        // while graphicsLayer{} is inline so the lambda is inlined into an unannotated body and lint
        // rejects an SDK_INT guard written inside it. A renderEffect already gets its own RenderNode, so
        // the offscreen pass is not needed for the capsule lens.
    }.drawBehind {
        val p=origin();translate(-p.x,-p.y){drawLayer(source)}
    })
}

/**
 * Lit rim + inner shadow drawn from the same SDF as the refraction, so the highlight follows the real
 * rounded-rect geometry (corners included) instead of a straight gradient down the long edges.
 *
 * Split into two pass-throughs below so each one carries the `@RequiresApi(33)` it needs. Doing that by
 * hand in a single function does not lint cleanly, because `graphicsLayer {}` is inline and its lambda is
 * inlined into the caller's (unannotated) body.
 */
@Composable private fun GlassEdgeLight(cornerDp:Float,edgeAlpha:Float,modifier:Modifier) {
    if(Build.VERSION.SDK_INT<33||edgeAlpha<=0f) return
    GlassEdgeLightLayer(cornerDp,edgeAlpha,modifier,ambient=false)
    GlassEdgeLightLayer(cornerDp,edgeAlpha,modifier,ambient=true)
}

@RequiresApi(33)
@Composable private fun GlassEdgeLightLayer(cornerDp:Float,edgeAlpha:Float,modifier:Modifier,ambient:Boolean) {
    val pass=remember(ambient){runCatching{EdgeLightPass(ambient)}.onFailure{android.util.Log.w("HoshioriGlass","Edge light unavailable",it)}.getOrNull()}
    Box(modifier.graphicsLayer {
        val r=(cornerDp*density).coerceAtMost(size.height/2f)
        pass?.let{runCatching{renderEffect=it.effect(size.width,size.height,List(4){r},density)}}
        // Named `edgeAlpha` on purpose: a parameter called `alpha` is shadowed by GraphicsLayerScope.alpha,
        // so both sides of `alpha=alpha` would resolve to the receiver and the assignment would be a no-op.
        alpha=edgeAlpha
        compositingStrategy=if(ambient)CompositingStrategy.ModulateAlpha else CompositingStrategy.Offscreen
    }.border(.6.dp,Color.White,RoundedCornerShape(cornerDp.dp)))
}

/**
 * Lens refracting the sampled page crop.
 *
 * The optical core is a faithful adaptation of the Apache-2.0 upstream library `backdrop`
 * (Kyant0/AndroidLiquidGlass, `com.kyant.backdrop.internal.RoundedRectRefractionShaderString`,
 * pinned at 65ab177e90e5c1d8c62e70cf7755841982da65f6). Copyright 2025 Kyant, Apache License 2.0;
 * see assets/licenses/backdrop-apache-2.0.txt and THIRD_PARTY_NOTICES.md. Our changes: the source
 * is the already-recorded page RenderNode instead of a Compose backdrop layer, uniforms are namespaced
 * distinctly, the bevel band is expressed in dp, and dispersion reuses this shader instead of a second one.
 *
 * No FlClash (GPL) or NexioSchedule (AGPL) code is embedded or translated; only the Apache-2.0 upstream
 * is adapted, and the effective library is reimplemented over the existing native AGSL pass.
 */
@RequiresApi(33)
internal class OpticalPass {
    private val shader=android.graphics.RuntimeShader("""
        uniform shader scene;
        uniform float2 extent;
        uniform float2 inset;
        uniform float4 radii;
        uniform float refractionHeight;
        uniform float refractionAmount;
        uniform float depthEffect;
        uniform float chromaticAberration;

        float radiusAt(float2 coord,float4 r) {
            if (coord.x>=0.0) { return coord.y<=0.0 ? r.y : r.z; }
            return coord.y<=0.0 ? r.x : r.w;
        }
        float sdRoundedRect(float2 coord,float2 halfSize,float radius) {
            float2 c=abs(coord)-(halfSize-float2(radius));
            return length(max(c,float2(0.0)))-radius+min(max(c.x,c.y),0.0);
        }
        float2 gradSdRoundedRect(float2 coord,float2 halfSize,float radius) {
            float2 c=abs(coord)-(halfSize-float2(radius));
            if (c.x>=0.0||c.y>=0.0) { return sign(coord)*normalize(max(c,float2(0.0))+float2(0.00001)); }
            float g=step(c.y,c.x);
            return sign(coord)*float2(g,1.0-g);
        }
        float circleMap(float x) { return 1.0-sqrt(1.0-x*x); }

        half4 main(float2 coord) {
            float2 halfSize=extent*0.5;
            float2 centered=(coord+inset)-halfSize;
            float radius=radiusAt(coord,radii);
            float sd=sdRoundedRect(centered,halfSize,radius);
            if (-sd>=refractionHeight) { return scene.eval(coord); }
            sd=min(sd,0.0);
            float d=circleMap(1.0-(-sd)/refractionHeight)*refractionAmount;
            float gradRadius=min(radius*1.5,min(halfSize.x,halfSize.y));
            float2 grad=normalize(gradSdRoundedRect(centered,halfSize,gradRadius)+depthEffect*normalize(centered+float2(0.00001)));
            float2 refracted=clamp(coord+d*grad,float2(0.5),extent-0.5);
            if (chromaticAberration<=0.0) { return scene.eval(refracted); }
            float spread=chromaticAberration*((centered.x*centered.y)/max(halfSize.x*halfSize.y,1.0))*d;
            float2 axis=grad*spread;
            half4 c=scene.eval(refracted)*half4(0.2,0.2,0.2,0.6);
            c+=scene.eval(clamp(refracted-axis,float2(0.5),extent-0.5))*half4(0.3,0.0,0.0,0.1);
            c+=scene.eval(clamp(refracted-axis*0.5,float2(0.5),extent-0.5))*half4(0.2,0.2,0.0,0.1);
            c+=scene.eval(clamp(refracted+axis*0.5,float2(0.5),extent-0.5))*half4(0.0,0.2,0.2,0.1);
            c+=scene.eval(clamp(refracted+axis,float2(0.5),extent-0.5))*half4(0.0,0.0,0.3,0.1);
            return c;
        }
    """.trimIndent())
    private var key:List<Float> = emptyList()
    private var cached:RenderEffect?=null

    /**
     * @param h       lens height in px (kept as a key: the band already scales with it)
     * @param radii   corner radii as top-left, top-right, bottom-right, bottom-left in px
     * @param amount  signed displacement peak in px; negative pulls the rim outwards (a raised lens)
     */
    fun effect(width:Float,height:Float,radii:List<Float>,band:Float,h:Float,amount:Float,density:Float,blur:Float,dispersion:Float):RenderEffect {
        val next=listOf(width,height)+radii+listOf(band,h,amount,density,blur,dispersion)
        if(key==next)cached?.let{return it}
        shader.setFloatUniform("extent",width,height)
        // A lens is applied per draw node, so the only node carrying the shader is the dock itself: no
        // offset is needed, and the band alone decides how deep the bend reaches. That keeps a tall bar
        // from bending its interior (a calm middle) while still concentrating the bend on the rim.
        shader.setFloatUniform("inset",0f,0f)
        shader.setFloatUniform("radii",radii[0],radii[1],radii[2],radii[3])
        shader.setFloatUniform("refractionHeight",band.coerceAtLeast(1f))
        shader.setFloatUniform("refractionAmount",amount)
        shader.setFloatUniform("depthEffect",if(dispersion>0f)1f else 0f)
        shader.setFloatUniform("chromaticAberration",dispersion)
        val lens=android.graphics.RenderEffect.createRuntimeShaderEffect(shader,"scene")
        val gaussian=android.graphics.RenderEffect.createBlurEffect(blur,blur,android.graphics.Shader.TileMode.CLAMP)
        // Native contract: outer(inner(source)). Both operate on this node's drawn page crop.
        return android.graphics.RenderEffect.createChainEffect(lens,gaussian).asComposeRenderEffect().also{key=next;cached=it}
    }
}

/**
 * Edge light for the glass rim, adapted from the Apache-2.0 upstream `backdrop` highlight pair
 * (DefaultHighlightShaderString / AmbientHighlightShaderString, Copyright 2025 Kyant, Apache-2.0).
 *
 * Difference from a flat white border: brightness is `|dot(grad(SDF), normal)|^falloff`, so the rim is
 * lit only where the surface normal faces the light, and the inner shadow is one-sided (ambient). That
 * is what makes a rim read as glass instead of as paint. Values follow the upstream defaults: 45 degrees,
 * falloff 1, white at alpha .5 for the lit half, white at .38 for the ambient half.
 */
@RequiresApi(33)
internal class EdgeLightPass(private val ambient:Boolean) {
    private val shader=android.graphics.RuntimeShader("""
        uniform shader content;
        uniform float ambientPass;
        uniform float2 extent;
        uniform float4 radii;
        uniform float angle;
        uniform float falloff;
        layout(color) uniform half4 light;

        float radiusAt(float2 coord,float4 r) {
            if (coord.x>=0.0) { return coord.y<=0.0 ? r.y : r.z; }
            return coord.y<=0.0 ? r.x : r.w;
        }
        float sdRoundedRect(float2 coord,float2 halfSize,float radius) {
            float2 c=abs(coord)-(halfSize-float2(radius));
            return length(max(c,float2(0.0)))-radius+min(max(c.x,c.y),0.0);
        }
        float2 gradSdRoundedRect(float2 coord,float2 halfSize,float radius) {
            float2 c=abs(coord)-(halfSize-float2(radius));
            if (c.x>=0.0||c.y>=0.0) { return sign(coord)*normalize(max(c,float2(0.0))+float2(0.00001)); }
            float g=step(c.y,c.x);
            return sign(coord)*float2(g,1.0-g);
        }

        half4 main(float2 coord) {
            float2 halfSize=extent*0.5;
            float2 centered=coord-halfSize;
            float radius=radiusAt(coord,radii);
            float gradRadius=min(radius*1.5,min(halfSize.x,halfSize.y));
            float2 grad=gradSdRoundedRect(centered,halfSize,gradRadius);
            float d=dot(grad,float2(cos(angle),sin(angle)));
            float intensity=pow(abs(d),falloff);
            half4 rim=ambientPass>0.5 ? half4(step(0.0,d)*light.a*intensity) : light*intensity;
            return rim*content.eval(coord).a;
        }
    """.trimIndent())
    private var key:List<Float> = emptyList()
    private var cached:RenderEffect?=null

    fun effect(width:Float,height:Float,radii:List<Float>,density:Float):RenderEffect {
        val next=listOf(width,height)+radii+listOf(density)
        if(key==next)cached?.let{return it}
        shader.setFloatUniform("extent",width,height)
        shader.setFloatUniform("radii",radii[0],radii[1],radii[2],radii[3])
        // Fully qualified: these live on GlassOptics, not in this class's scope.
        shader.setFloatUniform("angle",GlassOptics.EdgeLightAngleDegrees*(PI.toFloat()/180f))
        shader.setFloatUniform("ambientPass",if(ambient)1f else 0f)
        shader.setFloatUniform("falloff",GlassOptics.EdgeLightFalloff)
        val a=if(ambient)GlassOptics.AmbientAlpha else GlassOptics.LitAlpha
        shader.setFloatUniform("light",a,a,a,a)
        return android.graphics.RenderEffect.createRuntimeShaderEffect(shader,"content").asComposeRenderEffect().also{key=next;cached=it}
    }
}
