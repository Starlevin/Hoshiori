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
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Settings
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class PageBackdropState(val cached:GraphicsLayer,val capture:Boolean=true) {
    var ready by mutableStateOf(false)
        internal set
    var captureRevision by mutableIntStateOf(0)
        internal set
}

fun Modifier.backdropSource(layer:GraphicsLayer,enabled:Boolean):Modifier=if(!enabled)this else drawWithContent {
    layer.record {this@drawWithContent.drawContent()};drawLayer(layer)
}
fun Modifier.backdropSource(state:PageBackdropState):Modifier=if(!state.capture)this else drawWithContent {
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
@Composable fun liquidDockHeight(fontScale:Float)=if(fontScale>1.3f)88.dp else 72.dp
@Composable fun rememberBackdrop():PageBackdropState=rememberPageBackdrop(true)
@Composable fun rememberPageBackdrop(blurEnabled:Boolean):PageBackdropState {
    val layer=rememberGraphicsLayer()
    return remember(layer,blurEnabled){PageBackdropState(layer,blurEnabled)}
}

private val titles=listOf("首页","账号","记录","设置")
private val icons=listOf(Icons.Rounded.Home,Icons.Rounded.Person,Icons.Rounded.History,Icons.Rounded.Settings)

/** Number of dock slots; the drag math, the clamp and the semantics all read this one value. */
private const val DockSlots=4

/**
 * Distance, in cells, the capsule must be pulled away from the slot it started on (its own travel and
 * the finger's travel both counted, see [dockReleaseSlot]) before a release selects another slot.
 * 0.5 = "past the neighbour's centre", so the nearest slot always wins and one flick never skips one.
 */
private const val DockCommitDistance=.5f

@Composable fun LiquidDock(selected:Int,onSelect:(Int)->Unit,source:GraphicsLayer,policy:GlassPolicy,enabled:Boolean=true,modifier:Modifier=Modifier) {
    Dock(selected,onSelect,source,policy,enabled,modifier)
}
@Composable fun LiquidDock(selected:Int,onSelect:(Int)->Unit,backdrop:PageBackdropState,policy:GlassPolicy,enabled:Boolean=true,modifier:Modifier=Modifier) {
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
        Dock(selected,onSelect,backdrop.cached,policy.copy(blur=policy.blur&&sampledFrameReady),enabled,modifier)
    }
}
@Composable fun LiquidDock(selected:Int,onSelect:(Int)->Unit,source:GraphicsLayer,policy:GlassPolicy,modifier:Modifier) {
    Dock(selected,onSelect,source,policy,true,modifier)
}

@Composable private fun Dock(selected:Int,onSelect:(Int)->Unit,source:GraphicsLayer,policy:GlassPolicy,enabled:Boolean,modifier:Modifier) {
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
    val edge=Brush.linearGradient(listOf(Color.White.copy(alpha=if(dark).38f else .8f),colors.outlineVariant.copy(alpha=.15f),Color.White.copy(alpha=if(dark).14f else .45f)))
    Box(modifier.zIndex(1f).fillMaxWidth().navigationBarsPadding().padding(horizontal=GlassTokens.margin).padding(bottom=GlassTokens.lift),contentAlignment=Alignment.Center) {
        BoxWithConstraints(Modifier.widthIn(max=GlassTokens.maxWidth).fillMaxWidth().height(height)
            .onGloballyPositioned{position=it.positionInRoot()}
            .graphicsLayer{alpha=if(enabled)1f else 0f}
            .shadow(if(off)0.dp else 12.dp,shape,clip=false)
            .graphicsLayer{compositingStrategy=CompositingStrategy.Offscreen}.clip(shape)
            .then(if(enabled)Modifier else Modifier.semantics{invisibleToUser()})
            .testTag("floating-dock")) {
            if(policy.blur) SampledGlass(source,{position},18f,34f,true,Modifier.matchParentSize())
            Box(Modifier.matchParentSize().background(colors.surface.copy(alpha=if(off)1f else if(policy.blur).40f else .97f)))
            if(!off)Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.White.copy(alpha=if(dark).10f else .24f),Color.Transparent,Color.White.copy(alpha=.035f)))).border(1.dp,edge,shape))
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
                    if(policy.blur) SampledGlass(source,{
                        position+with(density){Offset((6.dp+cellWidth*capsule.value).toPx(),6.dp.toPx())}
                    },3.5f,1000f,true,Modifier.matchParentSize())
                    Box(Modifier.matchParentSize().background(if(off)colors.primaryContainer else colors.primary.copy(alpha=if(policy.blur).095f else .12f)))
                    if(!off)Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.White.copy(alpha=if(dark).15f else .44f),Color.Transparent)))
                        .border(1.dp,Brush.linearGradient(listOf(Color.White.copy(alpha=if(dark).48f else .85f),colors.primary.copy(alpha=.10f),Color.White.copy(alpha=.18f))),lensShape))
                }
                // Keep input on the ancestor of the selectable cells so physical taps reach them.
                Row(Modifier.fillMaxSize()
                    .dockDrag(enabled,current,dragging,cellPx,padPx,
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
            if(!released) {
                // Cancelled, or a press that never crossed slop. A tap leaves the capsule untouched (so it
                // behaves exactly as before this layer existed); a cancelled drag returns to its slot and
                // switches nothing.
                if(dragging) settleCb(originSlot.toFloat())
            }
        }
    }
}

/** The render node actually draws the translated page crop; its effect can therefore sample that content. */
@Composable private fun SampledGlass(source:GraphicsLayer,origin:()->Offset,blurDp:Float,cornerDp:Float,refract:Boolean,modifier:Modifier) {
    val optics=if(Build.VERSION.SDK_INT>=33&&refract)remember{runCatching{OpticalPass()}.getOrNull()} else null
    Box(modifier.graphicsLayer {
        val radius=(cornerDp*density).coerceAtMost(size.height/2f)
        val blur=blurDp*density
        renderEffect=if(Build.VERSION.SDK_INT>=33&&optics!=null) {
            runCatching{optics.effect(size.width,size.height,radius,blur,density)}.getOrElse{BlurEffect(blur,blur,TileMode.Clamp)}
        } else BlurEffect(blur,blur,TileMode.Clamp)
        compositingStrategy=CompositingStrategy.Offscreen
    }.drawBehind {
        val p=origin();translate(-p.x,-p.y){drawLayer(source)}
    })
}

/** Independently implemented local lens. No Flutter/FlClash shader code is embedded or translated. */
@RequiresApi(33)
internal class OpticalPass {
    private val shader=android.graphics.RuntimeShader("""
        uniform shader scene;
        uniform float2 extent;
        uniform float radius;
        uniform float band;
        uniform float bend;
        half4 main(float2 p) {
            float2 halfSize=extent*.5;
            float2 centered=p-halfSize;
            float2 q=abs(centered)-halfSize+radius;
            float distance=length(max(q,float2(0)))+min(max(q.x,q.y),0)-radius;
            float depth=max(-distance,0);
            float profile=sin(clamp(depth/band,0,1)*3.14159265);
            float2 corner=max(q,float2(0));
            float2 normal=length(corner)>.001 ? normalize(corner)*sign(centered) :
                (q.x>q.y ? float2(sign(centered.x),0) : float2(0,sign(centered.y)));
            float2 shifted=p-normal*bend*profile;
            float2 spread=normal*.35*profile;
            half4 center=scene.eval(clamp(shifted,float2(.5),extent-.5));
            half red=scene.eval(clamp(shifted+spread,float2(.5),extent-.5)).r;
            half blue=scene.eval(clamp(shifted-spread,float2(.5),extent-.5)).b;
            return half4(min(half3(red,center.g,blue),half3(center.a)),center.a);
        }
    """.trimIndent())
    private var key:List<Float> = emptyList()
    private var cached:RenderEffect?=null
    fun effect(width:Float,height:Float,radius:Float,blur:Float,density:Float):RenderEffect {
        val next=listOf(width,height,radius,blur,density)
        if(key==next)cached?.let{return it}
        shader.setFloatUniform("extent",width,height)
        shader.setFloatUniform("radius",radius)
        shader.setFloatUniform("band",(8*density).coerceAtMost(height/2f).coerceAtLeast(1f))
        shader.setFloatUniform("bend",2.2f*density)
        val lens=android.graphics.RenderEffect.createRuntimeShaderEffect(shader,"scene")
        val gaussian=android.graphics.RenderEffect.createBlurEffect(blur,blur,android.graphics.Shader.TileMode.CLAMP)
        // Native contract: outer(inner(source)). Both operate on this node's drawn page crop.
        return android.graphics.RenderEffect.createChainEffect(lens,gaussian).asComposeRenderEffect().also{key=next;cached=it}
    }
}
