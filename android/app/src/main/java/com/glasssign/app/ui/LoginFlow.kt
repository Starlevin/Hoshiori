package com.glasssign.app.ui

import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glasssign.app.data.Account
import com.glasssign.app.data.GameCatalog
import androidx.compose.ui.text.style.TextOverflow
import com.glasssign.app.login.LoginPlatforms
import com.glasssign.app.login.PendingLogin

private val loginShape = RoundedCornerShape(15.dp)

/**
 * Entry point: pick a game, authenticate its account system, then select all desired games.
 * Manual JSON import stays available, but only as a developer escape hatch.
 */
@Composable
fun LoginStartDialog(
    editing: Account?,
    busy: Boolean,
    initialGameId: String = "",
    onDismiss: () -> Unit,
    onWebLogin: (gameId: String, nickname: String) -> Unit,
    onAdvancedImport: () -> Unit,
) {
    var gameId by remember(editing?.id,initialGameId) { mutableStateOf(editing?.gameId?.takeIf{it.isNotEmpty()} ?: initialGameId.takeIf{GameCatalog.find(it)!=null} ?: GameCatalog.firstFor(editing?.provider ?: "miyoushe").id) }
    val colors = androidx.compose.material3.MaterialTheme.colorScheme
    val game = requireNotNull(GameCatalog.find(gameId))
    val provider = game.provider
    var nickname by remember { mutableStateOf(editing?.nickname.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    val platform = LoginPlatforms.of(provider)
    val supported = LoginPlatforms.supportsLogin(provider)

    GlassDialog(
        title = if (editing == null) "绑定游戏" else "重新登录",
        subtitle = "同一账号体系登录一次，再勾选多个游戏",
        dismissible = !busy,
        onDismiss = onDismiss,
    ) {
        Text("选择游戏", color = Muted, fontSize = 11.sp)
        Spacer(Modifier.height(8.dp))
        GameCatalog.all.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                row.forEach { item ->
                    val meta=providerMeta(item.provider).copy(color=colors.primary)
                    val selected=gameId==item.id
                    val locked=editing!=null && editing.provider!=item.provider
                    Column(Modifier.weight(1f).padding(bottom=8.dp)
                        .background(if(selected)meta.color.copy(alpha=0.14f) else colors.surfaceContainerLow,loginShape)
                        .border(1.dp,if(selected)meta.color.copy(alpha=0.6f) else colors.outlineVariant,loginShape)
                        .clickable(enabled=!locked&&!busy){gameId=item.id;error=null}.padding(12.dp)) {
                        Text(item.title,color=if(locked)Faint else White,fontSize=12.sp,fontWeight=FontWeight.SemiBold,maxLines=1,overflow=TextOverflow.Ellipsis)
                        Text(if(selected)"已选择" else "从此游戏开始",color=if(selected)meta.color else Faint,fontSize=10.sp)
                    }
                }
            }
        }
        Spacer(Modifier.height(2.dp))
        OutlinedTextField(
            value = nickname,
            onValueChange = { nickname = it.take(40); error = null },
            label = { Text("账号备注") },
            placeholder = { Text("例如：我的${game.title}账号") },
            singleLine = true,
            enabled = !busy,
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = White, unfocusedTextColor = White,
                focusedContainerColor = Color.White.copy(alpha = 0.045f), unfocusedContainerColor = colors.surfaceContainerLow,
                focusedBorderColor = Cyan.copy(alpha = 0.8f), unfocusedBorderColor = colors.outlineVariant,
                cursorColor = Cyan, focusedLabelColor = Cyan, unfocusedLabelColor = Muted,
                focusedPlaceholderColor = Faint, unfocusedPlaceholderColor = Faint,
            ),
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )

        Spacer(Modifier.height(10.dp))
        NoteRow(Icons.Rounded.Info, "通过${platform.title}登录一次，随后勾选这个账号下要绑定的多个游戏。" + if(provider=="tagged") "异环使用账号登录后交换社区授权，不再等待网页令牌。" else "", Faint)

        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = Rose, fontSize = 11.sp, lineHeight = 16.sp)
        }

        Spacer(Modifier.height(14.dp))
        Button(
            onClick = {
                if (!supported) {
                    error = "该平台暂不支持网页登录，请使用下方导入"
                    return@Button
                }
                onWebLogin(gameId, nickname.trim().ifBlank { game.title })
            },
            enabled = !busy && supported,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Cyan, contentColor = Oled,
                disabledContainerColor = Color.White.copy(alpha = 0.08f), disabledContentColor = Faint,
            ),
        ) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(16.dp), color = Oled, strokeWidth = 2.dp)
            } else {
                Icon(Icons.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(7.dp))
                Text(if(provider=="tagged")"登录异环账号" else "继续登录并选择游戏", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            if(provider=="tagged")"在星栞中填写的账号信息仅发送官方账号服务；密码、验证码不保存。读取角色后再由你确认绑定。" else "星栞不提供自己的密码输入框，也不会索取验证码。登录完成后会自动读取角色，由你确认后才完成绑定。",
            color = Faint, fontSize = 9.sp, lineHeight = 13.sp,
        )
        Spacer(Modifier.height(6.dp))
        TextButton(onClick = onAdvancedImport, enabled = !busy, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
            Text("开发者选项：导入已有授权", color = Faint, fontSize = 11.sp)
        }
    }
}

/**
 * Shown after a login succeeded and the character list was read from the official API.
 * Nothing is signed and no account is activated before the user confirms here.
 */
@Composable
fun PendingLoginDialog(
    pending: PendingLogin,
    busy: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onGameSelection: (String,Boolean) -> Unit,
) {
    val meta=providerMeta(pending.provider)
    val hasRoles=pending.roles.isNotEmpty()
    val available=pending.availableGameIds.distinct()
    val selected=pending.selectedGameIds.intersect(available.toSet())
    val selectionRequired=available.isNotEmpty()
    val canConfirm=pending.ready&&(!selectionRequired||selected.isNotEmpty())
    GlassDialog(
        title=if(pending.existingAccount)"管理已绑定游戏" else if(hasRoles)"选择绑定游戏" else "未能读取角色",
        subtitle=if(pending.existingAccount)"使用现有授权，无需重新登录" else "${meta.title} · ${pending.nickname}",dismissible=pending.existingAccount&&!busy,onDismiss=onCancel,
    ) {
        NoteRow(Icons.Rounded.Lock,"授权保存在本机；勾选并确认后才更新游戏范围。",Mint)
        Spacer(Modifier.height(14.dp))
        if(hasRoles) {
            if(selectionRequired) {
                GameSelectionRows(available,selected,busy,onGameSelection)
                Spacer(Modifier.height(12.dp))
            }
            Text("账号角色清单",color=White,fontSize=13.sp,fontWeight=FontWeight.SemiBold)
            Text("以下为该账号读取到的角色；上方勾选决定哪些游戏纳入签到。",color=Muted,fontSize=11.sp,lineHeight=17.sp)
            Spacer(Modifier.height(8.dp))
            pending.roles.forEach {role->
                Row(Modifier.fillMaxWidth().padding(bottom=6.dp).background(MaterialTheme.colorScheme.surfaceContainerLow,RoundedCornerShape(12.dp)).padding(horizontal=12.dp,vertical=9.dp),verticalAlignment=Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).background(Cyan,CircleShape));Spacer(Modifier.width(9.dp));Text(role,color=White,fontSize=12.sp)
                }
            }
            if(selectionRequired&&selected.isEmpty()) {
                Spacer(Modifier.height(6.dp));Text("至少选择一个游戏才能确认。",color=Amber,fontSize=12.sp)
            }
            Spacer(Modifier.height(6.dp));Text(pending.message,color=Muted,fontSize=11.sp,lineHeight=17.sp)
        } else {
            Text(pending.message,color=Rose,fontSize=12.sp,lineHeight=18.sp)
            Spacer(Modifier.height(8.dp))
            Text("尚未读取到可确认的角色，请核对账号、角色绑定或平台返回的错误。原有账号与记录保留。",color=Muted,fontSize=11.sp,lineHeight=17.sp)
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            TextButton(onClick=onCancel,enabled=!busy,modifier=Modifier.weight(1f)){Text("返回",color=Muted)}
            if(hasRoles)Button(onClick=onConfirm,enabled=!busy&&canConfirm,modifier=Modifier.weight(1f).height(48.dp)) {
                if(busy)CircularProgressIndicator(Modifier.size(17.dp),color=MaterialTheme.colorScheme.onPrimary,strokeWidth=2.dp)
                else Text(if(pending.existingAccount)"保存游戏选择" else "确认绑定",fontWeight=FontWeight.SemiBold,fontSize=13.sp)
            }
        }
    }
}

@Composable
private fun GameSelectionRows(available:List<String>,selected:Set<String>,busy:Boolean,onSelection:(String,Boolean)->Unit) {
    Text("已选择 ${selected.size} 个游戏",color=White,fontWeight=FontWeight.SemiBold,fontSize=13.sp)
    Text("一个账号下的游戏共用本次授权，不需要逐个登录。",color=Muted,fontSize=11.sp,lineHeight=17.sp)
    Spacer(Modifier.height(8.dp))
    available.forEach {id->
        val checked=id in selected
        Row(Modifier.fillMaxWidth().padding(bottom=6.dp)
            .background(if(checked)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,RoundedCornerShape(12.dp))
            .toggleable(value=checked,enabled=!busy,role=Role.Checkbox,onValueChange={onSelection(id,it)})
            .padding(horizontal=10.dp,vertical=5.dp),verticalAlignment=Alignment.CenterVertically) {
            Checkbox(checked=checked,onCheckedChange=null,enabled=!busy)
            Spacer(Modifier.width(9.dp))
            Text(GameCatalog.find(id)?.title ?: id,color=White,fontSize=13.sp,modifier=Modifier.weight(1f))
        }
    }
}
