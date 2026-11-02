package com.glasssign.app.ui

import android.animation.ValueAnimator
import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*

/** A display-list source contains the real page ONLY, never this overlay (no feedback loop). */
fun Modifier.backdropSource(layer:GraphicsLayer,enabled:Boolean)=if(!enabled)this else drawWithContent {
    layer.record { this@drawWithContent.drawContent() };drawLayer(layer)
}

data class GlassPolicy(val blur:Boolean,val motion:Boolean,val reason:String)
fun glassPolicy(api:Int,lowRam:Boolean,powerSave:Boolean,accessible:Boolean,reduceMotion:Boolean,animators:Boolean,mode:String):GlassPolicy {
    val reason=when {mode=="off"->"玻璃已关闭";mode=="light"->"轻量材质";api<31->"系统不支持实时模糊";lowRam->"低内存设备";powerSave->"系统省电模式";accessible->"辅助功能高对比";else->"实时背景采样"}
    return GlassPolicy(mode=="standard"&&api>=31&&!lowRam&&!powerSave&&!accessible,!reduceMotion&&animators,reason)
}

@Composable fun currentGlassPolicy():GlassPolicy {
    val c=LocalContext.current
    val manager=c.getSystemService(PowerManager::class.java)
    val accessibility=c.getSystemService(AccessibilityManager::class.java)
    var explored by remember{mutableStateOf(accessibility.isTouchExplorationEnabled)}
    DisposableEffect(accessibility) {
        val listener=AccessibilityManager.TouchExplorationStateChangeListener{explored=it}
        accessibility.addTouchExplorationStateChangeListener(listener)
        onDispose{accessibility.removeTouchExplorationStateChangeListener(listener)}
    }
    var power by remember {mutableStateOf(manager.isPowerSaveMode)}
    DisposableEffect(manager) {
        val receiver=object:android.content.BroadcastReceiver(){override fun onReceive(context:Context,intent:android.content.Intent){power=manager.isPowerSaveMode}}
        androidx.core.content.ContextCompat.registerReceiver(c,receiver,android.content.IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose{c.unregisterReceiver(receiver)}
    }
    return glassPolicy(Build.VERSION.SDK_INT,c.getSystemService(ActivityManager::class.java).isLowRamDevice,power,
        explored,LocalReduceMotion.current,ValueAnimator.areAnimatorsEnabled(),LocalAppearance.current.glassMode)
}

object GlassTokens {val corner=34.dp;val margin=18.dp;val blur=18.dp;val lift=12.dp}

@Composable fun LiquidDock(selected:Int,onSelect:(Int)->Unit,source:GraphicsLayer,policy:GlassPolicy,modifier:Modifier=Modifier) {
    val colors=MaterialTheme.colorScheme
    val shape=RoundedCornerShape(GlassTokens.corner)
    var position by remember{mutableStateOf(Offset.Zero)}
    val fontScale=LocalDensity.current.fontScale
    val height=if(fontScale>1.3f)88.dp else 72.dp
    val titles=listOf("首页","账号","记录","设置")
    val icons=listOf(Icons.Rounded.Home,Icons.Rounded.Person,Icons.Rounded.History,Icons.Rounded.Settings)
    BoxWithConstraints(modifier.fillMaxWidth().padding(horizontal=GlassTokens.margin)
        .navigationBarsPadding().padding(bottom=GlassTokens.lift)
        .height(height).onGloballyPositioned{position=it.positionInRoot()}
        .shadow(if(LocalAppearance.current.glassMode=="off")0.dp else 10.dp,shape,clip=false)
        .clip(shape).testTag("floating-dock")) {
        if(policy.blur)Box(Modifier.matchParentSize().graphicsLayer {
            renderEffect=BlurEffect(GlassTokens.blur.toPx(),GlassTokens.blur.toPx(),TileMode.Clamp)
            compositingStrategy=CompositingStrategy.Offscreen
        }.drawBehind {translate(-position.x,-position.y){drawLayer(source)}})
        val off=LocalAppearance.current.glassMode=="off"
        Box(Modifier.matchParentSize().background(colors.surface.copy(alpha=if(off)1f else if(policy.blur).74f else .97f)))
        if(!off)Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.White.copy(alpha=if(colors.background.luminance()>.5f).28f else .09f),Color.Transparent))))
        Box(Modifier.matchParentSize().border(1.dp,colors.outlineVariant.copy(alpha=.6f),shape))
        val cell=(maxWidth-12.dp)/4
        val index by animateFloatAsState(selected.toFloat(),if(policy.motion)spring(dampingRatio=.76f,stiffness=550f) else snap(),label="dock-capsule")
        Box(Modifier.padding(top=6.dp).offset {IntOffset((6.dp+cell*index).roundToPx(),0)}
            .width(cell).height(height-12.dp).clip(RoundedCornerShape(28.dp))
            .background(Brush.linearGradient(listOf(colors.primaryContainer.copy(alpha=.88f),colors.secondaryContainer.copy(alpha=.72f))))
            .border(1.dp,colors.primary.copy(alpha=.16f),RoundedCornerShape(28.dp)).testTag("selected-capsule"))
        Row(Modifier.fillMaxSize().padding(horizontal=6.dp)) {
            titles.forEachIndexed { i,title ->
                val interaction=remember{MutableInteractionSource()};val pressed by interaction.collectIsPressedAsState()
                val scale by animateFloatAsState(if(pressed&&policy.motion).94f else 1f,if(policy.motion)tween(180) else snap(),label="dock-press")
                Column(Modifier.weight(1f).fillMaxHeight().graphicsLayer{scaleX=scale;scaleY=scale}
                    .selectable(selected=i==selected,role=Role.Tab,interactionSource=interaction,indication=null,onClick={onSelect(i)})
                    .semantics{contentDescription=title;stateDescription=if(i==selected)"已选中" else "未选中"}
                    .testTag("nav-$i"),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally) {
                    Icon(icons[i],null,tint=if(i==selected)colors.primary else colors.onSurfaceVariant,modifier=Modifier.size(23.dp))
                    Spacer(Modifier.height(3.dp));Text(title,style=MaterialTheme.typography.labelMedium,color=if(i==selected)colors.onSurface else colors.onSurfaceVariant,maxLines=1)
                }
            }
        }
    }
}
