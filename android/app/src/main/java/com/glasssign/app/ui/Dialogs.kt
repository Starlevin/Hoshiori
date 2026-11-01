package com.glasssign.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glasssign.app.data.Account
import com.glasssign.app.data.CheckRecord
import com.glasssign.app.data.DayRecords
import com.glasssign.app.data.HistoryDay
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val dialogFieldShape = RoundedCornerShape(14.dp)

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = White,
    unfocusedTextColor = White,
    disabledTextColor = Muted,
    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    focusedBorderColor = Cyan.copy(alpha = 0.8f),
    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
    disabledBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha=.6f),
    cursorColor = Cyan,
    focusedLabelColor = Cyan,
    unfocusedLabelColor = Muted,
    focusedPlaceholderColor = Faint,
    unfocusedPlaceholderColor = Faint,
)

@Composable
fun AccountDialog(
    editing: Account?,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (provider: String, nickname: String, credentials: JSONObject) -> Unit,
) {
    var providerId by remember { mutableStateOf(editing?.provider ?: "miyoushe") }
    var nickname by remember { mutableStateOf(editing?.nickname.orEmpty()) }
    var authJson by remember { mutableStateOf("") }
    var consent by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val meta = providerMeta(providerId)
    val updating = editing != null

    GlassDialog(
        secure = authJson.isNotEmpty(),
        title = if (updating) "更新社区授权" else "绑定社区账号",
        subtitle = if (updating) "只替换授权数据，平台与备注保持不变" else "仅支持本人授权导入；每日计划由你另行开启",
        dismissible = !busy,
        onDismiss = onDismiss,
    ) {
        Text("社区", color = Muted, fontSize = 11.sp)
        Spacer(Modifier.height(7.dp))
        Providers.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { item ->
                    ProviderOption(
                        meta = item,
                        selected = providerId == item.id,
                        enabled = !updating && !busy,
                        modifier = Modifier.weight(1f),
                        onClick = { providerId = item.id; error = null },
                    )
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
        }

        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = nickname,
            onValueChange = { nickname = it.take(40); error = null },
            label = { Text("账号备注") },
            placeholder = { Text("例如：我的主账号") },
            singleLine = true,
            enabled = !updating && !busy,
            shape = dialogFieldShape,
            colors = fieldColors(),
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = authJson,
            onValueChange = { authJson = it; error = null },
            label = { Text("授权 JSON") },
            placeholder = { Text("粘贴你自己设备上取得的授权数据") },
            minLines = 4,
            maxLines = 7,
            enabled = !busy,
            shape = dialogFieldShape,
            colors = fieldColors(),
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        )

        Spacer(Modifier.height(10.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.22f), RoundedCornerShape(14.dp))
                .border(1.dp, meta.color.copy(alpha = 0.18f), RoundedCornerShape(14.dp))
                .padding(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(meta.icon, contentDescription = null, tint = meta.color, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text("${meta.title} · 所需字段：${meta.fields.joinToString(" / ")}", color = White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(6.dp))
            Text(meta.help, color = Muted, fontSize = 10.sp, lineHeight = 15.sp)
            Spacer(Modifier.height(7.dp))
            SelectionContainer {
                Text(
                    meta.sample,
                    color = meta.color.copy(alpha = 0.92f),
                    fontSize = 9.sp,
                    lineHeight = 14.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.Top) {
            Checkbox(
                checked = consent,
                onCheckedChange = { consent = it; error = null },
                colors = CheckboxDefaults.colors(
                    checkedColor = Cyan,
                    checkmarkColor = Oled,
                    uncheckedColor = Muted,
                ),
                modifier = Modifier.padding(top = 2.dp),
            )
            Text(
                "我授权星栞在本机加密保存本人授权，并直接访问官方接口签到；可随时暂停、清除授权或删除账号。",
                color = Muted,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        error?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, color = Rose, fontSize = 11.sp, lineHeight = 16.sp)
        }

        Spacer(Modifier.height(14.dp))
        Button(
            onClick = {
                if (nickname.isBlank()) {
                    error = "请填写账号备注"
                    return@Button
                }
                if (!consent) {
                    error = "请先阅读并勾选本机签到授权同意项"
                    return@Button
                }
                val parsed = try {
                    JSONObject(authJson.trim())
                } catch (_: Exception) {
                    error = "授权 JSON 格式有误，请检查括号与引号"
                    return@Button
                }
                val forbidden = listOf("password", "passwd", "pwd", "smscode", "sms_code", "captcha", "otp")
                val keys = parsed.keys().asSequence().map { it.lowercase() }.toSet()
                if (keys.any { it in forbidden }) {
                    error = "请勿提交账号密码或验证码，这里只需要设备上授权得到的令牌"
                    return@Button
                }
                val missing = meta.fields.filter { parsed.optString(it).isBlank() }
                if (missing.isNotEmpty()) {
                    error = "缺少字段：${missing.joinToString(" / ")}"
                    return@Button
                }
                onSubmit(providerId, nickname.trim(), parsed)
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Cyan,
                contentColor = Oled,
                disabledContainerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha=.6f),
                disabledContentColor = Faint,
            ),
        ) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(16.dp), color = Oled, strokeWidth = 2.dp)
            } else {
                Text(if (updating) "保存并立即检查" else "绑定并立即检查", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "仅支持手动授权导入，不代表已登录。授权通过 Keystore 加密留在本机；不要在聊天中发送凭据。",
            color = Faint,
            fontSize = 9.sp,
            lineHeight = 13.sp,
        )
    }
}

@Composable
private fun ProviderOption(
    meta: ProviderMeta,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val alpha = if (selected) 1f else if (enabled) 0.65f else 0.35f
    Row(
        modifier
            .background(
                if (selected) meta.color.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceContainerLow,
                RoundedCornerShape(15.dp),
            )
            .border(
                1.dp,
                if (selected) meta.color.copy(alpha = 0.65f) else Color.White.copy(alpha = 0.12f),
                RoundedCornerShape(15.dp),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(meta.icon, contentDescription = null, tint = meta.color.copy(alpha = alpha), modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(7.dp))
        Column {
            Text(meta.title, color = White.copy(alpha = alpha), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(
                meta.games,
                color = Muted.copy(alpha = alpha),
                fontSize = 8.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selected) {
            Spacer(Modifier.weight(1f))
            Icon(Icons.Rounded.Check, contentDescription = null, tint = meta.color, modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
fun CloudSettingsDialog(
    initialServer: String,
    hasToken: Boolean,
    reduceMotion: Boolean,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
    onClear: () -> Unit,
    onReduceMotion: (Boolean) -> Unit,
) {
    var url by remember { mutableStateOf(initialServer) }
    var key by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    GlassDialog(
        title = "私人云端",
        subtitle = "只填写你自己部署的 HTTPS 服务",
        dismissible = !busy,
        onDismiss = onDismiss,
    ) {
        OutlinedTextField(
            value = url,
            onValueChange = { url = it.trim(); error = null },
            label = { Text("HTTPS API 地址") },
            placeholder = { Text("https://sign.example.com") },
            singleLine = true,
            shape = dialogFieldShape,
            colors = fieldColors(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = key,
            onValueChange = { key = it; error = null },
            label = { Text("API 密钥") },
            placeholder = { Text(if (hasToken) "已保存，留空保持不变" else "至少 32 个字符") },
            singleLine = true,
            shape = dialogFieldShape,
            colors = fieldColors(),
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Lock, contentDescription = null, tint = Mint, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                if (hasToken) "API 密钥已由 Android Keystore 加密保存" else "尚未保存 API 密钥",
                color = Muted,
                fontSize = 10.sp,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("减少动画", color = White, fontSize = 12.sp)
                Text("降低光斑与扫光动画，省电并提升可读性", color = Faint, fontSize = 9.sp)
            }
            Switch(
                checked = reduceMotion,
                onCheckedChange = onReduceMotion,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Oled,
                    checkedTrackColor = Cyan,
                    uncheckedThumbColor = Muted,
                    uncheckedTrackColor = Color.White.copy(alpha = 0.10f),
                    uncheckedBorderColor = Color.White.copy(alpha = 0.18f),
                ),
            )
        }
        error?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, color = Rose, fontSize = 11.sp)
        }
        Spacer(Modifier.height(14.dp))
        Button(
            onClick = {
                try {
                    onSave(url, key)
                    onDismiss()
                } catch (exception: Exception) {
                    error = exception.message ?: "配置有误"
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Oled),
        ) {
            Text("保存并连接", fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
        Spacer(Modifier.height(8.dp))
        if (confirmClear) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("会删除本机地址与密钥（不影响云端数据）", color = Rose, fontSize = 10.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { confirmClear = false }) { Text("取消", color = Muted, fontSize = 11.sp) }
                TextButton(onClick = { onClear(); onDismiss() }) { Text("确认清除", color = Rose, fontSize = 11.sp) }
            }
        } else {
            TextButton(onClick = { confirmClear = true }, contentPadding = PaddingValues(0.dp)) {
                Text("清除本机配置", color = Rose.copy(alpha = 0.85f), fontSize = 11.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
        NoteRow(Icons.Rounded.Info, "地址必须是 HTTPS 且证书受系统信任；不支持明文 HTTP、重定向或自签名证书。", Faint)
    }
}

@Composable
fun DeleteAccountDialog(
    account: Account,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val meta = providerMeta(account.provider)
    GlassDialog(
        title = "删除账号",
        subtitle = "该操作不可撤销",
        onDismiss = onDismiss,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProviderBadge(meta, 44.dp)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(meta.title, color = White, fontWeight = FontWeight.SemiBold)
                Text(account.nickname, color = Muted, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(
            if (account.bindingPending || account.authState=="pending") "将清除本次暂存授权并取消绑定；原有账号与签到记录不受影响。" else "将从本机删除该账号的加密授权与全部签到记录，并停止该账号自动签到。此操作不会影响你在官方社区的账号。",
            color = Muted,
            fontSize = 12.sp,
            lineHeight = 18.sp,
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                Text("取消", color = Muted)
            }
            Button(
                onClick = onConfirm,
                modifier = Modifier.weight(1f).height(46.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Rose, contentColor = Oled),
            ) {
                Icon(Icons.Rounded.Delete, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
                Text("确认删除", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }
    }
}

@Composable
fun HistoryDialog(
    days: List<HistoryDay>,
    selectedDay: String?,
    detail: DayRecords?,
    loading: Boolean,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    GlassDialog(
        title = "签到记录",
        subtitle = "本机执行记录；最多展示最近 90 个有记录的日期",
        onDismiss = onDismiss,
    ) {
        if (days.isEmpty() && !loading) {
            Text("还没有历史记录。绑定账号并执行一次后会出现在这里。", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
            return@GlassDialog
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            days.forEach { day ->
                val selected = day.day == selectedDay
                Column(
                    Modifier
                        .background(
                            if (selected) Cyan.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceContainerLow,
                            RoundedCornerShape(13.dp),
                        )
                        .border(
                            1.dp,
                            if (selected) Cyan.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.10f),
                            RoundedCornerShape(13.dp),
                        )
                        .clickable { onSelect(day.day) }
                        .padding(horizontal = 11.dp, vertical = 8.dp),
                ) {
                    Text(formatDay(day.day), color = if (selected) Cyan else White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Text("成功 ${day.ok} · 关注 ${day.attention}", color = Muted, fontSize = 8.sp)
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        if (loading) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), color = Cyan, strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("正在读取…", color = Muted, fontSize = 11.sp)
            }
        }
        val records = detail?.records.orEmpty()
        if (records.isEmpty() && !loading) {
            Text("该日期没有记录。", color = Faint, fontSize = 11.sp)
        } else {
            val ok = records.count { it.status == "success" || it.status == "already" }
            val attention = records.count { it.status == "failed" || it.status == "needs_action" }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassChip("成功 $ok", Mint)
                GlassChip("需关注 $attention", if (attention > 0) Amber else Slate)
                GlassChip("共 ${records.size} 项", Cyan)
            }
            Spacer(Modifier.height(10.dp))
            records.forEach { record ->
                HistoryRecordRow(record)
                Spacer(Modifier.height(8.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        NoteRow(Icons.Rounded.Shield, "失败或需处理的记录不会显示为成功；请以官方社区记录为准。", Faint)
    }
}

@Composable
private fun HistoryRecordRow(record: CheckRecord) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.025f), RoundedCornerShape(12.dp))
            .padding(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .padding(top = 5.dp)
                .size(6.dp)
                .background(statusColor(record.status), CircleShape)
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(record.targetName, color = White.copy(alpha = 0.94f), fontSize = 11.sp, fontWeight = FontWeight.Medium)
            Text(
                record.message.ifBlank { "无附加信息" },
                color = Faint,
                fontSize = 9.sp,
                lineHeight = 13.sp,
            )
        }
        StatusChip(record.status)
    }
}

private val dayFormatter = DateTimeFormatter.ofPattern("MM-dd")

private fun formatDay(value: String): String = runCatching {
    LocalDate.parse(value).format(dayFormatter)
}.getOrDefault(value)

@Composable
fun NoticeDialog(
    title: String,
    message: String,
    confirmLabel: String = "知道了",
    onDismiss: () -> Unit,
) {
    GlassDialog(title = title, onDismiss = onDismiss) {
        Text(message, color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
        Spacer(Modifier.height(14.dp))
        Row {
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDismiss) {
                Icon(Icons.Rounded.CloudDone, contentDescription = null, tint = Cyan, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(5.dp))
                Text(confirmLabel, color = Cyan, fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun SettingsDialog(
    scheduleEnabled: Boolean, dailyTime: String, reduceMotion: Boolean, busy: Boolean,
    onDismiss: () -> Unit, onSave: (Boolean, String) -> Unit, onClear: () -> Unit,
    onReduceMotion: (Boolean) -> Unit,
) {
    var appearance by remember { mutableStateOf(false) }
    var enabled by remember { mutableStateOf(scheduleEnabled) }
    var time by remember { mutableStateOf(dailyTime) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    GlassDialog(title = "本地计划与隐私", subtitle = "数据只在本机 · 无需配置服务器", onDismiss = onDismiss) {
        TextButton(onClick = { appearance = true }, modifier = Modifier.fillMaxWidth()) { Text("外观与主题", color = Cyan) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("每日后台签到", color = White, modifier = Modifier.weight(1f))
            Switch(checked = enabled, onCheckedChange = { enabled = it }, enabled = !busy)
        }
        OutlinedTextField(value = time, onValueChange = { time = it; error = null }, label = { Text("北京时间 HH:mm") }, singleLine = true, colors = fieldColors(), modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        Text("Android 会在所选时间后择机联网执行，可能延迟；关机时无法运行，重启联网后补查当天。请在系统设置允许自启动及后台运行。", color = Muted, fontSize = 12.sp)
        Text("通知权限被拒时仍会保存本地记录。网络恢复由系统调度；不会要求常亮屏幕。", color = Muted, fontSize = 12.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("减少动画", color = White, modifier = Modifier.weight(1f))
            Switch(checked = reduceMotion, onCheckedChange = onReduceMotion)
        }
        error?.let { Text(it, color = Rose) }
        Button(onClick = {
            if (!Regex("[0-2][0-9]:[0-5][0-9]").matches(time) || runCatching { java.time.LocalTime.parse(time) }.isFailure) error = "请输入有效时间，例如 08:10"
            else onSave(enabled, time)
        }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("保存本地计划") }
        TextButton(onClick = { if (confirmClear) { onClear(); onDismiss() } else confirmClear = true }, enabled = !busy) {
            Text(if (confirmClear) "确认清除全部授权并暂停计划" else "清除全部本机授权", color = Rose)
        }
        Text("普通登录已支持在官方页面完成；四个平台的实号验证仍待本人逐一确认。", color = Faint, fontSize = 10.sp)
    }
    if (appearance) AppearanceDialog { appearance = false }
}

/** The normal login entry lives in ui/LoginFlow.kt; this file keeps the developer import path. */
