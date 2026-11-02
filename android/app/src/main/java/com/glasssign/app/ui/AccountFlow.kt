package com.glasssign.app.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.SecureFlagPolicy
import com.glasssign.app.HomeViewModel
import com.glasssign.app.data.*
import com.glasssign.app.login.LoginPlatforms
import com.glasssign.app.login.PendingLogin
import org.json.JSONObject

internal fun safeLabel(value:String)=Regex("[0-9]{5,}").replace(value){maskedIdentity(it.value)}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun AddAccountSheet(existing:Account?,busy:Boolean,onDismiss:()->Unit,onLogin:(String,String)->Unit,onImport:(String,String,JSONObject)->Unit) {
    var provider by remember{mutableStateOf(existing?.provider)}
    var alias by remember{mutableStateOf(existing?.nickname.orEmpty())}
    var importing by remember{mutableStateOf(false)}
    var json by remember{mutableStateOf("")}
    var error by remember{mutableStateOf<String?>(null)}
    ModalBottomSheet(onDismissRequest={if(!busy)onDismiss()},sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal=24.dp).padding(bottom=32.dp).imePadding(),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(if(provider==null)"选择平台" else "登录账号",style=MaterialTheme.typography.headlineSmall,modifier=Modifier.testTag("binding-step"))
            Text("选择平台 → 登录账号 → 选择游戏/角色 → 完成绑定",style=MaterialTheme.typography.bodySmall,color=Muted)
            if(provider==null) {
                Text("同一平台可添加多个独立账号。",color=Muted)
                Providers.forEach {meta->
                    OutlinedCard(onClick={provider=meta.id},modifier=Modifier.fillMaxWidth().testTag("platform-${meta.id}")) {
                        Row(Modifier.padding(18.dp),verticalAlignment=Alignment.CenterVertically) {
                            Icon(meta.icon,null,tint=meta.color);Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text(meta.title,style=MaterialTheme.typography.titleMedium)
                                Text(GameCatalog.all.filter{it.provider==meta.id}.joinToString(" · "){it.title},style=MaterialTheme.typography.bodySmall,color=Muted)
                                Text(if(meta.id=="tagged")"账号 / 短信登录" else if(LoginPlatforms.supportsLogin(meta.id))"官方网页登录 · 支持本人授权导入" else "仅支持本人授权导入",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)
                            }
                            Icon(Icons.Rounded.ChevronRight,null)
                        }
                    }
                }
            } else {
                val id=provider!!;val meta=providerMeta(id)
                Text(meta.title,style=MaterialTheme.typography.titleLarge)
                OutlinedTextField(alias,{alias=it.take(40)},label={Text("账号备注（例如主号 / 小号）")},singleLine=true,modifier=Modifier.fillMaxWidth())
                Text(if(id=="tagged")"在星栞的账号表单登录；信息仅发送官方账号服务，密码与验证码不保存。" else "登录页使用独立的临时网页会话。请选择本次账号，原账号的已保存授权不会清除。",color=Muted)
                Text("登录后将显示脱敏身份，再选择游戏与角色。授权成功不代表已经签到。",color=Muted)
                if(LoginPlatforms.supportsLogin(id))Button(onClick={onLogin(id,alias.ifBlank{meta.title})},enabled=!busy,modifier=Modifier.fillMaxWidth().heightIn(min=50.dp).testTag("login-account")){Text("登录账号")}
                TextButton(onClick={importing=true},enabled=!busy){Text("使用本人授权导入")}
                if(existing==null)TextButton(onClick={provider=null}){Text("重新选择平台")}
            }
        }
    }
    if(importing&&provider!=null)GlassDialog(title="导入${providerMeta(provider!!).title}授权",subtitle="仅在本机加密保存，不接受密码或验证码",secure=true,dismissible=!busy,onDismiss={importing=false}) {
        Text(providerMeta(provider!!).help,color=Muted)
        OutlinedTextField(json,{json=it.take(65536);error=null},label={Text("本人授权 JSON")},visualTransformation=PasswordVisualTransformation(),minLines=3,maxLines=6,modifier=Modifier.fillMaxWidth())
        error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
        Button(onClick={try{val parsed=JSONObject(json);onImport(provider!!,alias.ifBlank{providerMeta(provider!!).title},parsed);json="";importing=false}catch(_:Exception){error="授权格式无效，请检查 JSON；不要填写密码或验证码"}},enabled=!busy&&json.isNotBlank()){Text("读取账号与角色")}
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun RoleBindingSheet(p:PendingLogin,busy:Boolean,onGame:(String,Boolean)->Unit,onRole:(String,Boolean)->Unit,onAuto:(Boolean)->Unit,onReview:()->Unit,onConfirm:()->Unit,onCancel:()->Unit,onOther:()->Unit) {
    var identityChecked by remember(p.accountId){mutableStateOf(false)}
    ModalBottomSheet(onDismissRequest={if(!busy)onCancel()},sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal=24.dp).padding(bottom=32.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(if(p.confirming)"确认绑定" else if(p.existingAccount)"管理游戏与角色" else "选择游戏 / 角色",style=MaterialTheme.typography.headlineSmall,modifier=Modifier.testTag("binding-step"))
            Text("${providerMeta(p.provider).title} · ${safeLabel(p.nickname)}",style=MaterialTheme.typography.titleMedium)
            Text("账号 ${maskedIdentity(p.providerUserId)}",color=Muted)
            if(p.providerUserId.isBlank()) {
                Text("平台未提供稳定身份，无法自动判重；请核对本次角色与账号。",style=MaterialTheme.typography.bodySmall,color=Muted)
                Row(Modifier.fillMaxWidth().toggleable(identityChecked,role=Role.Checkbox,onValueChange={identityChecked=it}),verticalAlignment=Alignment.CenterVertically){Checkbox(identityChecked,null);Text("我已核对这是本次要绑定的账号")}
            }
            if(!p.existingAccount)TextButton(onClick=onOther,enabled=!busy){Text("切换其他账号")}
            if(p.confirming) {
                Text("已选择 ${p.selectedGameIds.size} 个游戏、${p.selectedRoleIds.size} 个角色。",style=MaterialTheme.typography.titleMedium)
                p.roleChoices.filter{it.targetId in p.selectedRoleIds}.forEach{Text(safeLabel(it.name),color=Muted)}
                Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("每日自动签到");Text("全局计划需在设置中开启",style=MaterialTheme.typography.bodySmall,color=Muted)};Switch(p.autoSignEnabled,onAuto,enabled=!busy)}
                Text("完成绑定只保存选择，不会强制执行签到。",color=Muted)
                Button(onClick=onConfirm,enabled=!busy&&p.ready,modifier=Modifier.fillMaxWidth().heightIn(min=50.dp).testTag("complete-binding")){Text(if(busy)"正在保存…" else if(p.existingAccount)"保存修改" else "完成绑定")}
                TextButton(onClick=onReview,enabled=!busy){Text("返回选择")}
            } else {
                Text(p.message,color=if(p.roles.isEmpty())MaterialTheme.colorScheme.error else Muted)
                GameCatalog.all.filter{it.provider==p.provider}.forEach {game->
                    val available=game.id in p.availableGameIds
                    val checked=game.id in p.selectedGameIds
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().toggleable(checked,enabled=available&&!busy,role=Role.Checkbox,onValueChange={onGame(game.id,it)}).padding(12.dp),verticalAlignment=Alignment.CenterVertically) {
                            Checkbox(checked,null,enabled=available&&!busy);Text(game.title,modifier=Modifier.weight(1f))
                            if(!available)Text("未读取到角色",style=MaterialTheme.typography.labelSmall,color=Muted)
                        }
                        if(available&&checked)p.roleChoices.filter{it.gameId==game.id}.forEach{role->
                            Row(Modifier.fillMaxWidth().toggleable(role.targetId in p.selectedRoleIds,enabled=!busy,role=Role.Checkbox,onValueChange={onRole(role.targetId,it)}).padding(start=24.dp,end=12.dp,bottom=8.dp),verticalAlignment=Alignment.CenterVertically) {
                                Checkbox(role.targetId in p.selectedRoleIds,null,enabled=!busy)
                                Column {Text(safeLabel(role.name));Text("角色 ${maskedIdentity(role.targetId.split('|').getOrElse(if(p.provider in listOf("tagged","kurobbs"))2 else 1){""})}",style=MaterialTheme.typography.labelSmall,color=Muted)}
                            }
                        }
                    }
                }
                Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("每日自动签到");Text("不改变系统全局计划",style=MaterialTheme.typography.bodySmall,color=Muted)};Switch(p.autoSignEnabled,onAuto,enabled=!busy)}
                Button(onClick=onReview,enabled=!busy&&p.ready&&p.roleChoices.isNotEmpty()&&(p.providerUserId.isNotBlank()||identityChecked),modifier=Modifier.fillMaxWidth().heightIn(min=50.dp).testTag("review-binding")){Text("下一步 · 确认绑定")}
            }
            TextButton(onClick=onCancel,enabled=!busy){Text("取消，不更改现有账号")}
        }
    }
}
