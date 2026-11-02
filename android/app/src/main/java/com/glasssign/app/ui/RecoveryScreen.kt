package com.glasssign.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable fun RecoveryScreen(busy:Boolean=false,onRetry:()->Unit) {
    Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background) {
        Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
            Text("升级尚未完成",style=MaterialTheme.typography.headlineMedium)
            Text("原账号、授权与历史仍保留。请勿卸载应用或清除数据。")
            Text("数据库迁移失败会回滚；升级前的检查点保存在应用私有目录。释放存储空间后可以重试。反复失败时请保留原安装与检查点，交由维护者恢复。",color=Muted)
            Button(onClick=onRetry,enabled=!busy,modifier=Modifier.fillMaxWidth().heightIn(min=50.dp).testTag("retry-migration")){Text(if(busy)"正在重试…" else "保留数据并重试")}
        }
    }
}
