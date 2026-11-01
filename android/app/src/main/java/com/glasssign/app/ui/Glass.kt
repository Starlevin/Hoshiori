package com.glasssign.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Flat Material surfaces: no full-window offscreen layer, blur, continuous animation or shader. */
@Composable
fun AmbientBackground(modifier:Modifier=Modifier,tint:Color=Cyan,secondary:Color=Lilac) {
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
}

@Composable
fun GlassCard(modifier:Modifier=Modifier,tint:Color=Cyan,contentPadding:Dp=18.dp,content:@Composable ColumnScope.()->Unit) {
    val scheme=MaterialTheme.colorScheme
    val effect=LocalAppearance.current.surfaceEffect
    val amount=when(effect){"standard"->.035f;"ultra"->.085f;else->0f}
    val background=lerp(scheme.surface,scheme.primary,amount)
    val border=when(effect){"ultra"->scheme.primary.copy(alpha=.4f);"standard"->scheme.outlineVariant;else->scheme.outlineVariant.copy(alpha=.65f)}
    Surface(modifier.fillMaxWidth(),shape=GlassShape,color=background,border=androidx.compose.foundation.BorderStroke(1.dp,border),tonalElevation=0.dp,shadowElevation=0.dp) {
        Column(Modifier.padding(contentPadding),content=content)
    }
}

@Composable
fun GlassChip(text:String,color:Color,modifier:Modifier=Modifier,icon:ImageVector?=null) {
    Row(modifier.clip(ChipShape).background(color.copy(alpha=.1f)).padding(horizontal=9.dp,vertical=5.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(5.dp)) {
        if(icon!=null)Icon(icon,null,tint=color,modifier=Modifier.size(13.dp))
        Text(text,color=color,fontSize=11.sp,fontWeight=FontWeight.Medium,maxLines=1)
    }
}
@Composable fun StatusChip(status:String) {GlassChip(statusLabel(status),statusColor(status))}

@Composable
fun PrimaryGlassButton(text:String,caption:String,loading:Boolean,enabled:Boolean,modifier:Modifier=Modifier,onClick:()->Unit) {
    val scheme=MaterialTheme.colorScheme
    val background=if(enabled||loading)scheme.primary else scheme.surfaceContainerHighest
    val foreground=if(enabled||loading)scheme.onPrimary else scheme.onSurfaceVariant
    Surface(modifier.fillMaxWidth().heightIn(min=78.dp).clip(GlassShape).clickable(enabled=enabled&&!loading,onClick=onClick),shape=GlassShape,color=background) {
        Row(Modifier.padding(horizontal=20.dp,vertical=17.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
            Column(Modifier.weight(1f)) {
                Text(if(loading)"本地正在检查…" else text,color=foreground,fontSize=18.sp,fontWeight=FontWeight.SemiBold)
                Spacer(Modifier.height(3.dp))
                Text(if(loading)"完成后在记录页查看结果" else caption,color=foreground.copy(alpha=.8f),fontSize=11.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
            }
            if(loading)CircularProgressIndicator(Modifier.size(23.dp),color=foreground,strokeWidth=2.dp)
            else Icon(Icons.AutoMirrored.Rounded.ArrowForward,null,tint=foreground)
        }
    }
}

@Composable
fun SectionHeader(title:String,subtitle:String?=null,action: @Composable (RowScope.() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title,color=White,fontSize=18.sp,fontWeight=FontWeight.SemiBold)
            if(subtitle!=null){Spacer(Modifier.height(3.dp));Text(subtitle,color=Muted,fontSize=12.sp)}
        }
        if(action!=null)Row(verticalAlignment=Alignment.CenterVertically,content=action)
    }
}

@Composable
fun KeyValueRow(icon:ImageVector,label:String,value:String) {
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
        Icon(icon,null,tint=Muted,modifier=Modifier.size(18.dp))
        Text(label,color=Muted,fontSize=12.sp)
        Text(value,color=White,fontSize=12.sp,fontWeight=FontWeight.Medium,maxLines=2,overflow=TextOverflow.Ellipsis,modifier=Modifier.weight(1f),textAlign=androidx.compose.ui.text.style.TextAlign.End)
    }
}
@Composable
fun StatPill(label:String,value:Int,color:Color) {
    Column(Modifier.clip(RoundedCornerShape(12.dp)).background(color.copy(alpha=.075f)).padding(horizontal=14.dp,vertical=10.dp)) {
        Text(value.toString(),color=color,fontSize=22.sp,fontWeight=FontWeight.SemiBold)
        Text(label,color=Muted,fontSize=11.sp)
    }
}
@Composable fun DividerLine(modifier:Modifier=Modifier) {HorizontalDivider(modifier,color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.65f))}
@Composable
fun ProviderBadge(meta:ProviderMeta,size:Dp=40.dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primaryContainer),contentAlignment=Alignment.Center) {
        Icon(meta.icon,null,tint=MaterialTheme.colorScheme.onPrimaryContainer,modifier=Modifier.size(size*.52f))
    }
}

@Composable
fun GlassDialog(title:String,secure:Boolean=false,subtitle:String?=null,dismissible:Boolean=true,onDismiss:()->Unit,content:@Composable ColumnScope.()->Unit) {
    Dialog(onDismissRequest={if(dismissible)onDismiss()},properties=DialogProperties(securePolicy=if(secure)androidx.compose.ui.window.SecureFlagPolicy.SecureOn else androidx.compose.ui.window.SecureFlagPolicy.Inherit,dismissOnBackPress=dismissible,dismissOnClickOutside=dismissible,usePlatformDefaultWidth=false)) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal=18.dp,vertical=20.dp).imePadding(),contentAlignment=Alignment.Center) {
            val maxDialogHeight=maxHeight*.92f
            Surface(Modifier.fillMaxWidth().heightIn(max=maxDialogHeight),shape=RoundedCornerShape(24.dp),color=MaterialTheme.colorScheme.surface,tonalElevation=0.dp) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(title,color=White,fontSize=21.sp,fontWeight=FontWeight.SemiBold)
                            if(subtitle!=null){Spacer(Modifier.height(4.dp));Text(subtitle,color=Muted,fontSize=12.sp)}
                        }
                        IconButton(onClick=onDismiss,enabled=dismissible){Icon(Icons.Rounded.Close,"关闭",tint=Muted)}
                    }
                    Spacer(Modifier.height(16.dp));content()
                }
            }
        }
    }
}
@Composable
fun NoteRow(icon:ImageVector,text:String,tint:Color=Cyan) {
    Row(verticalAlignment=Alignment.Top,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        Icon(icon,null,tint=tint,modifier=Modifier.size(16.dp));Text(text,color=Muted,fontSize=12.sp,lineHeight=18.sp,modifier=Modifier.weight(1f))
    }
}
@Composable
fun EmptyStateIllustration(modifier:Modifier=Modifier) {
    Box(modifier.size(54.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.primaryContainer),contentAlignment=Alignment.Center) {
        Text("签",color=MaterialTheme.colorScheme.onPrimaryContainer,fontSize=24.sp,fontWeight=FontWeight.SemiBold)
    }
}
@Composable
fun BoxScope.GlassGlow(modifier:Modifier=Modifier,color:Color=Cyan) {
    Box(modifier.size(96.dp).clip(CircleShape).background(color.copy(alpha=.035f)))
}
