package com.glasssign.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.glasssign.app.data.Account
import com.glasssign.app.data.GameCatalog
import androidx.compose.runtime.saveable.rememberSaveable
import com.glasssign.app.login.LoginBridge
import com.glasssign.app.login.WebLoginActivity
import com.glasssign.app.ui.AccountDialog
import com.glasssign.app.ui.AmbientBackground
import com.glasssign.app.ui.DeleteAccountDialog
import com.glasssign.app.ui.GlassSignTheme
import com.glasssign.app.ui.HistoryDialog
import com.glasssign.app.ui.HomeScreen
import com.glasssign.app.ui.LoginStartDialog
import com.glasssign.app.ui.PendingLoginDialog
import com.glasssign.app.ui.SettingsDialog
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            val viewModel: HomeViewModel = viewModel()
            GlassSignTheme(reduceMotion = viewModel.state.reduceMotion) {
                GlassSignRoot(viewModel)
            }
        }
    }
}

@Composable
private fun GlassSignRoot(viewModel: HomeViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val permission = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { }
    val state = viewModel.state
    var showAccountDialog by remember { mutableStateOf(false) }
    var showLoginStart by remember { mutableStateOf(false) }
    var selectedGameId by rememberSaveable { mutableStateOf("") }
    var replacingAccountId by rememberSaveable { mutableStateOf<String?>(null) }
    var editingAccount by remember { mutableStateOf<Account?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    val loginLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val payload = LoginBridge.consume()
        if (result.resultCode == android.app.Activity.RESULT_OK && payload != null) {
            val parsed = runCatching { JSONObject(payload.credentials) }.getOrNull()
            if (parsed == null) {
                viewModel.reportLoginProblem("官方登录会话格式异常，未保存任何数据")
            } else {
                viewModel.beginLogin(payload.provider, payload.nickname, parsed, replacingAccountId, selectedGameId)
            }
        } else {
            LoginBridge.clear()
            viewModel.reportLoginProblem(result.data?.getStringExtra(WebLoginActivity.EXTRA_ERROR) ?: "已取消登录，未保存任何授权")
        }
    }
    var deleteTarget by remember { mutableStateOf<Account?>(null) }

    val message = state.message
    LaunchedEffect(message?.at) {
        if (message != null) {
            delay(6_000)
            viewModel.dismissMessage(message.at)
        }
    }
    LaunchedEffect(showHistory) {
        if (showHistory) viewModel.loadHistory()
    }

    Box(Modifier.fillMaxSize()) {
        AmbientBackground()
        HomeScreen(
            state = state,
            onRefresh = { viewModel.refresh() },
            onRunAll = { viewModel.runNow() },
            onRunAccount = { accountId -> viewModel.runNow(listOf(accountId)) },
            onVerify = { viewModel.verifyAuthorization(it.id) },
            onManage = { viewModel.manageGames(it.id) },
            onAdd = {
                editingAccount = null
                replacingAccountId = null
                selectedGameId = ""
                showLoginStart = true
            },
            onAddGame = { gameId ->
                editingAccount = null
                replacingAccountId = null
                selectedGameId = gameId
                showLoginStart = true
            },
            onEdit = {
                editingAccount = it
                replacingAccountId = it.id
                selectedGameId = it.gameId
                showLoginStart = true
            },
            onToggle = { account, enabled -> viewModel.setEnabled(account.id, enabled) },
            onDelete = { deleteTarget = it },
            onSettings = { showSettings = true },
            onHistory = { showHistory = true },
            onDismissMessage = viewModel::dismissMessage,
        )
    }

    if (showLoginStart) {
        LoginStartDialog(
            editing = editingAccount,
            busy = state.loginBusy,
            initialGameId = selectedGameId,
            onDismiss = { showLoginStart = false; editingAccount = null },
            onWebLogin = { gameId, nickname ->
                selectedGameId = gameId
                val provider = requireNotNull(GameCatalog.find(gameId)).provider
                showLoginStart = false
                loginLauncher.launch(
                    if(provider=="tagged") com.glasssign.app.login.TaggedLoginActivity.intent(context,nickname)
                    else WebLoginActivity.intent(context, provider, nickname, UUID.randomUUID().toString()),
                )
            },
            onAdvancedImport = { showLoginStart = false; showAccountDialog = true },
        )
    }

    state.pendingLogin?.let { pending ->
        PendingLoginDialog(
            pending = pending,
            busy = state.loginBusy,
            onGameSelection = viewModel::togglePendingGame,
            onConfirm = viewModel::confirmLogin,
            onCancel = viewModel::cancelLogin,
        )
    }

    if (showAccountDialog) {
        val editing = editingAccount
        AccountDialog(
            editing = editing,
            busy = state.jobRunning,
            onDismiss = { showAccountDialog = false },
            onSubmit = { provider, nickname, credentials ->
                if (editing == null) {
                    viewModel.addAccount(provider, nickname, credentials, selectedGameId.takeIf { GameCatalog.find(it)?.provider == provider }.orEmpty())
                } else {
                    viewModel.updateCredentials(editing.id, provider, nickname, credentials)
                }
                showAccountDialog = false
                editingAccount = null
            },
        )
    }

    deleteTarget?.let { account ->
        DeleteAccountDialog(
            account = account,
            onDismiss = { deleteTarget = null },
            onConfirm = {
                viewModel.deleteAccount(account.id, account.nickname)
                deleteTarget = null
            },
        )
    }

    if (showSettings) {
        SettingsDialog(
            scheduleEnabled = state.scheduleEnabled,
            dailyTime = state.dailyTime,
            reduceMotion = state.reduceMotion,
            busy = state.jobRunning,
            onDismiss = { showSettings = false },
            onSave = { enabled, time ->
                viewModel.saveSchedule(enabled, time)
                if (enabled && android.os.Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) permission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                showSettings = false
            },
            onClear = viewModel::clearCredentials,
            onReduceMotion = viewModel::setReduceMotion,
        )
    }

    if (showHistory) {
        HistoryDialog(
            days = state.history,
            selectedDay = state.selectedDay,
            detail = state.dayRecords,
            loading = state.historyLoading,
            onSelect = viewModel::selectDay,
            onDismiss = { showHistory = false },
        )
    }
}
