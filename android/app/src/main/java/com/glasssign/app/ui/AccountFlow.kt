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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
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
    // `GameCatalog.all.filter{...}` ran once per provider row on every recomposition of this sheet (4 rows
    // x a 9-entry list, each producing a throwaway List + joined String). The catalog is a singleton and
    // cannot change at runtime, so the per-provider game titles are computed once per composition scope.
    val providerGames=remember{Providers.associate{meta->meta.id to GameCatalog.all.filter{it.provider==meta.id}.joinToString(" · "){it.title}}}
    ModalBottomSheet(onDismissRequest={if(!busy)onDismiss()},sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal=24.dp).padding(bottom=32.dp).imePadding(),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            AccountStepTitle(if(provider==null)"选择平台" else "登录账号")
            Text("选择平台 → 登录账号 → 选择游戏/角色 → 完成绑定",style=MaterialTheme.typography.bodySmall,color=Muted)
            if(provider==null) {
                Text("同一平台可添加多个独立账号。",color=Muted)
                Providers.forEach {meta->
                    OutlinedCard(onClick={provider=meta.id},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("platform-${meta.id}")) {
                        Row(Modifier.padding(18.dp),verticalAlignment=Alignment.CenterVertically) {
                            Icon(meta.icon,null,tint=meta.color);Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text(meta.title,style=MaterialTheme.typography.titleMedium,maxLines=1,overflow=TextOverflow.Ellipsis)
                                Text(providerGames[meta.id].orEmpty(),style=MaterialTheme.typography.bodySmall,color=Muted,maxLines=2,overflow=TextOverflow.Ellipsis)
                                Text(if(meta.id=="tagged")"账号 / 短信登录" else if(LoginPlatforms.supportsLogin(meta.id))"官方网页登录 · 支持本人授权导入" else "仅支持本人授权导入",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary,maxLines=1,overflow=TextOverflow.Ellipsis)
                            }
                            Icon(Icons.Rounded.ChevronRight,null)
                        }
                    }
                }
            } else {
                val id=provider!!;val meta=providerMeta(id)
                Text(meta.title,style=MaterialTheme.typography.titleLarge,maxLines=1,overflow=TextOverflow.Ellipsis)
                OutlinedTextField(alias,{alias=it.take(40)},label={Text("账号备注（例如主号 / 小号）")},singleLine=true,modifier=Modifier.fillMaxWidth())
                Text(if(id=="tagged")"在星栞的账号表单登录；信息仅发送官方账号服务，密码与验证码不保存。" else "登录页使用独立的临时网页会话。请选择本次账号，原账号的已保存授权不会清除。",color=Muted)
                Text("登录后将显示脱敏身份，再选择游戏与角色。授权成功不代表已经签到。",color=Muted)
                if(LoginPlatforms.supportsLogin(id))Button(onClick={onLogin(id,alias.ifBlank{meta.title})},enabled=!busy,modifier=Modifier.fillMaxWidth().heightIn(min=50.dp).testTag("login-account")){Text("登录账号")}
                TextButton(onClick={importing=true},enabled=!busy,modifier=Modifier.heightIn(min=48.dp)){Text("使用本人授权导入")}
                if(existing==null)TextButton(onClick={provider=null},modifier=Modifier.heightIn(min=48.dp)){Text("重新选择平台")}
            }
        }
    }
    if(importing&&provider!=null)GlassDialog(title="导入${providerMeta(provider!!).title}授权",subtitle="仅在本机加密保存，不接受密码或验证码",secure=true,dismissible=!busy,onDismiss={importing=false}) {
        Text(providerMeta(provider!!).help,color=Muted)
        OutlinedTextField(json,{json=it.take(65536);error=null},label={Text("本人授权 JSON")},visualTransformation=PasswordVisualTransformation(),minLines=3,maxLines=6,modifier=Modifier.fillMaxWidth())
        error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
        Button(onClick={try{val parsed=JSONObject(json);onImport(provider!!,alias.ifBlank{providerMeta(provider!!).title},parsed);json="";importing=false}catch(_:Exception){error="授权格式无效，请检查 JSON；不要填写密码或验证码"}},enabled=!busy&&json.isNotBlank(),modifier=Modifier.heightIn(min=48.dp)){Text("读取账号与角色")}
    }
}

/** Step title: `heading()` so TalkBack announces the step level before the first row of that step. */
@Composable
private fun AccountStepTitle(text:String) {
    Text(text,style=MaterialTheme.typography.headlineSmall,modifier=Modifier.testTag("binding-step").semantics{heading()})
}

/**
 * A game card: the game row itself is the toggle, and only the selected game's roles are composed.
 *
 * The per-game role list and the selected-role set are computed for ALL games before any card is emitted
 * (`rolesByGame`, `selectedRoleIds`) and each card runs inside `key(game.id)`. Toggling one role used to
 * re-filter `p.roleChoices` once per game AND rebuild every card's subtree (no stable key, so Compose
 * could not reuse the other cards' slots); now the toggled game's card is the only one whose content
 * changes and the filters are `O(roles)` once per selection change instead of `O(games x roles)`.
 */
@Composable
private fun GameCard(
    game:GameChoice,roles:List<RoleChoice>,available:Boolean,checked:Boolean,busy:Boolean,selectedRoleIds:Set<String>,
    onGame:(String,Boolean)->Unit,onRole:(String,Boolean)->Unit,
) {
    val selectedCount=remember(roles,selectedRoleIds){roles.count{it.targetId in selectedRoleIds}}
    OutlinedCard(Modifier.fillMaxWidth()) {
        // 48dp minimum target for the game row; the previous padding(12.dp) around a 40dp checkbox row
        // could land below that when the label is a single short line.
        Row(Modifier.fillMaxWidth().heightIn(min=48.dp)
            .toggleable(checked,enabled=available&&!busy,role=Role.Checkbox,onValueChange={onGame(game.id,it)})
            .padding(horizontal=12.dp,vertical=8.dp)
            // Descendant semantics stay merged (the checkbox already contributes ToggleableState and Role
            // through `toggleable`); this only adds the hierarchy the checkbox alone cannot express:
            // which game this row belongs to and how many of its roles are selected.
            .semantics{contentDescription="游戏：${game.title}，${roles.size} 个角色，已选 $selectedCount 个"}
            .testTag("game-${game.id}"),verticalAlignment=Alignment.CenterVertically) {
            Checkbox(checked,null,enabled=available&&!busy)
            Text(game.title,modifier=Modifier.weight(1f),maxLines=1,overflow=TextOverflow.Ellipsis)
            if(!available)Text("未读取到角色",style=MaterialTheme.typography.labelSmall,color=Muted,maxLines=1,overflow=TextOverflow.Ellipsis)
        }
        if(available&&checked)roles.forEach{role->
            val roleChecked=role.targetId in selectedRoleIds
            Row(Modifier.fillMaxWidth().heightIn(min=48.dp)
                .toggleable(roleChecked,enabled=!busy,role=Role.Checkbox,onValueChange={onRole(role.targetId,it)})
                .padding(start=24.dp,end=12.dp).padding(vertical=8.dp)
                .testTag("role-${role.targetId}"),verticalAlignment=Alignment.CenterVertically) {
                Checkbox(roleChecked,null,enabled=!busy)
                Column(Modifier.weight(1f)) {
                    Text(safeLabel(role.name),maxLines=1,overflow=TextOverflow.Ellipsis)
                    Text("角色 ${maskedIdentity(role.targetId.split('|').getOrElse(if(role.gameId in listOf("nte","wuwa","pgr"))2 else 1){""})}",style=MaterialTheme.typography.labelSmall,color=Muted,maxLines=1,overflow=TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun RoleBindingSheet(p:PendingLogin,busy:Boolean,onGame:(String,Boolean)->Unit,onRole:(String,Boolean)->Unit,onAuto:(Boolean)->Unit,onReview:()->Unit,onConfirm:()->Unit,onCancel:()->Unit,onOther:()->Unit) {
    var identityChecked by remember(p.accountId){mutableStateOf(false)}
    // Hoisted once per (roleChoices, provider) instead of once per game card per recomposition: the two
    // former `p.roleChoices.filter{...}` calls ran inside the game loop, so one checkbox tap re-ran the
    // filter O(games) times over an O(roles) list. GameCatalog.all is a singleton, so the provider slice
    // is recomputed only when the provider changes, and the roles map only when the role list changes.
    val games=remember(p.provider){GameCatalog.all.filter{it.provider==p.provider}}
    val rolesByGame=remember(p.roleChoices){p.roleChoices.groupBy{it.gameId}}
    val selectedRoleIds=p.selectedRoleIds
    // Same identity semantics as the account card and the detail header (data/AccountDisplay.kt): official
    // name+tag first, the typed remark as its own line, masked platform UID as a supplement. The draft account id
    // is never shown, and roles are only counted once they have been read.
    val identity=remember(p.provider,p.nickname,p.providerUserId,p.roleChoices){
        pendingDisplay(p.provider,p.nickname,p.providerUserId,p.roleChoices,p.roleChoices.map{it.targetId}.toSet())
    }
    ModalBottomSheet(onDismissRequest={if(!busy)onCancel()},sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal=24.dp).padding(bottom=32.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            AccountStepTitle(if(p.confirming)"确认绑定" else if(p.existingAccount)"管理游戏与角色" else "选择游戏 / 角色")
            Text(safeLabel(identity.title),style=MaterialTheme.typography.titleMedium,maxLines=2,overflow=TextOverflow.Ellipsis)
            identity.customNote?.let{Text("备注 · ${safeLabel(it)}",color=Muted,maxLines=1,overflow=TextOverflow.Ellipsis)}
            if(p.providerUserId.isBlank())Text("平台未提供社区账号 ID · 按备注与角色核对",color=Muted,maxLines=1,overflow=TextOverflow.Ellipsis)
            else identity.platformLine?.let{Text(it,color=Muted,maxLines=1,overflow=TextOverflow.Ellipsis)}
            if(p.providerUserId.isBlank()) {
                Text("账号 ID 不可用时无法自动判重，请核对本次角色与账号。授权是否有效以下方角色读取结果为准。",style=MaterialTheme.typography.bodySmall,color=Muted)
                Row(Modifier.fillMaxWidth().heightIn(min=48.dp).toggleable(identityChecked,role=Role.Checkbox,onValueChange={identityChecked=it}).testTag("identity-confirm"),verticalAlignment=Alignment.CenterVertically){Checkbox(identityChecked,null);Text("我已核对这是本次要绑定的账号")}
            }
            if(!p.existingAccount)TextButton(onClick=onOther,enabled=!busy,modifier=Modifier.heightIn(min=48.dp)){Text("切换其他账号")}
            if(p.confirming) {
                Text("已选择 ${p.selectedGameIds.size} 个游戏、${p.selectedRoleIds.size} 个角色。",style=MaterialTheme.typography.titleMedium,modifier=Modifier.testTag("selection-summary"))
                (p.roleChoices.filter{it.targetId in p.selectedRoleIds}).forEach{Text(safeLabel(it.name),color=Muted,maxLines=1,overflow=TextOverflow.Ellipsis)}
                Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("每日自动签到");Text("全局计划需在设置中开启",style=MaterialTheme.typography.bodySmall,color=Muted)};Switch(p.autoSignEnabled,onAuto,enabled=!busy,modifier=Modifier.testTag("auto-sign"))}
                Text("完成绑定只保存选择，不会强制执行签到。",color=Muted)
                Button(onClick=onConfirm,enabled=!busy&&p.ready,modifier=Modifier.fillMaxWidth().heightIn(min=50.dp).testTag("complete-binding")){Text(if(busy)"正在保存…" else if(p.existingAccount)"保存修改" else "完成绑定")}
                TextButton(onClick=onReview,enabled=!busy,modifier=Modifier.heightIn(min=48.dp)){Text("返回选择")}
            } else {
                Text(p.message,color=if(p.roles.isEmpty())MaterialTheme.colorScheme.error else Muted)
                games.forEach {game->
                    key(game.id) {
                        GameCard(game,rolesByGame[game.id].orEmpty(),game.id in p.availableGameIds,game.id in p.selectedGameIds,busy,selectedRoleIds,onGame,onRole)
                    }
                }
                Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("每日自动签到");Text("不改变系统全局计划",style=MaterialTheme.typography.bodySmall,color=Muted)};Switch(p.autoSignEnabled,onAuto,enabled=!busy,modifier=Modifier.testTag("auto-sign"))}
                Button(onClick=onReview,enabled=!busy&&p.ready&&p.roleChoices.isNotEmpty()&&(p.providerUserId.isNotBlank()||identityChecked),modifier=Modifier.fillMaxWidth().heightIn(min=50.dp).testTag("review-binding")){Text("下一步 · 确认绑定")}
            }
            TextButton(onClick=onCancel,enabled=!busy,modifier=Modifier.heightIn(min=48.dp)){Text("取消，不更改现有账号")}
        }
    }
}
