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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.abs

class PageBackdropState(val cached:GraphicsLayer,val capture:Boolean=true)

fun Modifier.backdropSource(layer:GraphicsLayer,enabled:Boolean):Modifier=if(!enabled)this else drawWithContent {
    layer.record {this@drawWithContent.drawContent()};drawLayer(layer)
}
fun Modifier.backdropSource(state:PageBackdropState):Modifier=backdropSource(state.cached,state.capture)

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

@Composable fun LiquidDock(selected:Int,onSelect:(Int)->Unit,source:GraphicsLayer,policy:GlassPolicy,enabled:Boolean=true,modifier:Modifier=Modifier) {
    Dock(selected,onSelect,source,policy,enabled,modifier)
}
@Composable fun LiquidDock(selected:Int,onSelect:(Int)->Unit,backdrop:PageBackdropState,policy:GlassPolicy,enabled:Boolean=true,modifier:Modifier=Modifier) {
    Dock(selected,onSelect,backdrop.cached,policy,enabled,modifier)
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
    val selection=animateFloatAsState(selected.coerceIn(0,3).toFloat(),if(policy.motion)spring(dampingRatio=.73f,stiffness=480f) else snap(),label="dock-capsule")
    val edge=Brush.linearGradient(listOf(Color.White.copy(alpha=if(dark).38f else .8f),colors.outlineVariant.copy(alpha=.15f),Color.White.copy(alpha=if(dark).14f else .45f)))
    Box(modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal=GlassTokens.margin).padding(bottom=GlassTokens.lift),contentAlignment=Alignment.Center) {
        BoxWithConstraints(Modifier.widthIn(max=GlassTokens.maxWidth).fillMaxWidth().height(height)
            .onGloballyPositioned{position=it.positionInRoot()}
            .graphicsLayer{alpha=if(enabled)1f else 0f}
            .shadow(if(off)0.dp else 12.dp,shape,clip=false).clip(shape)
            .then(if(enabled)Modifier else Modifier.semantics{invisibleToUser()})
            .testTag("floating-dock")) {
            if(policy.blur) SampledGlass(source,{position},18f,34f,true,Modifier.matchParentSize())
            Box(Modifier.matchParentSize().background(colors.surface.copy(alpha=if(off)1f else if(policy.blur).40f else .97f)))
            if(!off)Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.White.copy(alpha=if(dark).10f else .24f),Color.Transparent,Color.White.copy(alpha=.035f)))).border(1.dp,edge,shape))
            if(enabled) {
                val cell=(maxWidth-12.dp)/4
                val density=LocalDensity.current
                val lensShape=RoundedCornerShape(50)
                // Offset and spring stretch are read in layout/layer phases, never by the page's composition.
                Box(Modifier.padding(top=6.dp).offset{IntOffset((6.dp+cell*selection.value).roundToPx(),0)}
                    .width(cell).height(height-12.dp).graphicsLayer {
                        val travel=abs(selection.value-selected).coerceIn(0f,1f)
                        scaleX=1f+travel*.035f;scaleY=1f-travel*.025f
                    }.clip(lensShape).testTag("selected-capsule")) {
                    if(policy.blur) SampledGlass(source,{
                        position+with(density){Offset((6.dp+cell*selection.value).toPx(),6.dp.toPx())}
                    },3.5f,1000f,true,Modifier.matchParentSize())
                    Box(Modifier.matchParentSize().background(if(off)colors.primaryContainer else colors.primary.copy(alpha=if(policy.blur).095f else .12f)))
                    if(!off)Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.White.copy(alpha=if(dark).15f else .44f),Color.Transparent)))
                        .border(1.dp,Brush.linearGradient(listOf(Color.White.copy(alpha=if(dark).48f else .85f),colors.primary.copy(alpha=.10f),Color.White.copy(alpha=.18f))),lensShape))
                }
                Row(Modifier.fillMaxSize().padding(horizontal=6.dp)) {
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
