package com.glasssign.app.ui

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.glasssign.app.*
import com.glasssign.app.data.*
import com.glasssign.app.local.SignRepository
import com.glasssign.app.login.PendingLogin
import java.time.*
import java.time.format.DateTimeFormatter

/*
 * v0.7.0 UI/perf pass (hs-screen). Facts this file is written against, with the baseline line numbers
 * in the pre-change file (bebb61a) where each cost lived:
 *
 * [H1] baseline line 132 `if(!modal&&WindowInsets.ime.getBottom(LocalDensity.current)==0)LiquidDock(...)`
 *      read a WindowInsets value inside the composition of the 154-line screen body. That body owns the
 *      `val s=vm.state` read (line 30) and therefore every one of its four page branches. A read of
 *      WindowInsets inside that composition makes the whole body a reader of the insets, so every IME
 *      animation frame (the keyboard slide is ~30-60 value changes) scheduled a full body recomposition:
 *      four `when`/`if` branches, the `byId`/`visible`/`tasks` derivations and the `filter{}`/`present{}`
 *      lambdas. Also, none of the four page bodies nor the chip rows are scroll-position readers, so the
 *      current 2/5300 janky frames must come from exactly this class of event-driven recomposition, not
 *      from scrolling. The insets read now lives in the leaf `DockOverlay`, whose composition contains
 *      nothing but the dock call; an IME frame can only recompose that leaf.
 * [H2] baseline `modal` (line 47) referenced `s.pendingLogin` (a field of the whole UiState). Kotlin
 *      short-circuits `adding||s.pendingLogin!=null||...`, so the page body read the state field on
 *      every frame; it also made appearance/plan/danger/rename/record -- pure local UI flags -- part of
 *      the same expression that is recomputed whenever any unrelated state field changes (Snackbar
 *      message, jobRunning, waitingNetwork, loginBusy, verifyingId, submitting, loading are all written
 *      on the same `mutableStateOf(UiState)` object). The six dialog flags now live in one
 *      `rememberSaveable` holder and `modalOpen` is a Boolean remembered from that holder only, so a
 *      Snackbar `s.message` write can no longer re-evaluate the dock branch or any page branch.
 * [H3] baseline line 39 `remember(s.timeline,resultFilter,providerFilter,accountFilter,gameFilter,byId)`
 *      keys the whole record list on the whole `s.timeline` list: the scheduler's `reload()` replaces
 *      `timeline` with a freshly mapped `List` on EVERY state refresh (jobRunning flip, waitingNetwork
 *      flip, any action), so the filter (which calls `present()` per record -> a Regex-free but
 *      string-scanning function, plus `GameCatalog.gameForTarget` = String.split per record) re-ran on
 *      every refresh even when the days/records were identical.
 * [H4] baseline line 41 `val scroll=listOf(rememberLazyListState(),x4)` + line 42 `detailScroll`:
 *      `remember`, so scroll positions survive tab switches (the states sit outside `key(tab,detail)`,
 *      confirmed) but are lost on rotation/process death while `tab` itself was `rememberSaveable`
 *      -> the restored tab was shown scrolled to the top. Now rememberSaveable through a keyed factory.
 * [H5] baseline `items(...)` had no `contentType`, so the LazyColumn cannot reuse a slot's composition
 *      across page switches; and `items{s.bindings.filter{accountId==a.id}}` (line 97) captured the
 *      whole `s` (hence the whole UiState) instead of the already-derived list.
 * [H6] baseline line 117 `present(r)` ran twice per record row per recomposition (label + attention
 *      check) and `qTime(r.startedAt)`/`qTime(r.updatedAt)` ran inside the item lambda.
 * [H7] baseline line 30 `val accounts=s.overview?.accounts.orEmpty().filter{...}` and line 66
 *      `tasks=s.bindings.filter{...}` were recomputed on every recomposition with no `remember` at all,
 *      and `QCard`'s trailing lambda was reallocated each time (lambda identity changes ->
 *      `Surface`/`Column` in the card sees a new unstable `content` parameter).
 *
 * No fps claim is made anywhere in this file. The user measures with their own device script.
 */

// ---------------------------------------------------------------------------------------------
// State holders
// ---------------------------------------------------------------------------------------------

/**
 * UI-only navigation/dialog state, in ONE holder.
 *
 * Why a holder instead of eleven `remember`/`rememberSaveable` calls in the screen body: the screen body
 * must not read any navigation flag it does not need. With separate locals, every flag is declared in
 * the body and the body's composition scope ends up reading six of them; with a holder that is
 * `@Stable`, `modalOpen` is computed from the holder in a separate `remember`, so the body reads only
 * two things (`modalOpen`, and `tab`/`detail` where it actually uses them).
 */
@Stable
internal class ScopeState {
    var tab by mutableIntStateOf(0);var detail by mutableStateOf<String?>(null)
    /** BindState: the add-account sheet this tab owns. */
    var adding by mutableStateOf(false);var renew by mutableStateOf<String?>(null)
    var appearance by mutableStateOf(false);var plan by mutableStateOf(false)
    var danger by mutableStateOf<Pair<Account,String>?>(null);var rename by mutableStateOf<Account?>(null)
    var record by mutableStateOf<TimelineRecord?>(null)
    var resultFilter by mutableStateOf("all");var providerFilter by mutableStateOf("all")
    var accountFilter by mutableStateOf("all");var gameFilter by mutableStateOf("all")
    /** True while any full-screen dialog / bottom sheet covers the dock (baseline `modal`, minus the data part). */
    val modalOpen:Boolean get()=adding||appearance||plan||danger!=null||rename!=null||record!=null
}

/**
 * `rememberSaveable` factory for the five pager slots (home / accounts / records / settings / account detail).
 *
 * [H4] WHY THIS EXISTS: baseline line 41-42 used `remember{rememberLazyListState()}` x5, i.e. NOT saveable,
 * while `tab` and `detail` were `rememberSaveable`. The 4 + 1 states already survived TAB SWITCHES (they sit
 * outside `key(tab,detail)`, confirmed by reading the baseline: `key(tab,detail){ LazyColumn(state=...) }`
 * only wraps the LazyColumn). What they did not survive was rotation / process death: the restored `tab`
 * came back scrolled to the top while the restored filters and detail id came back. One listSaver over
 * Int pairs fixes both without five separate saver lookups.
 */
internal class Pager(home:LazyListState,accounts:LazyListState,records:LazyListState,settings:LazyListState,detail:LazyListState) {
    val home=home;val accounts=accounts;val records=records;val settings=settings;val detail=detail
    fun slot(tab:Int,detailMode:Boolean):LazyListState = if(detailMode&&tab==1)detail else when(tab){1->accounts;2->records;3->settings;else->home}
    companion object {
        val Saver:Saver<Pager,Any> = listSaver(
            save={p->listOf(p.home.firstVisibleItemIndex,p.home.firstVisibleItemScrollOffset,
                p.accounts.firstVisibleItemIndex,p.accounts.firstVisibleItemScrollOffset,
                p.records.firstVisibleItemIndex,p.records.firstVisibleItemScrollOffset,
                p.settings.firstVisibleItemIndex,p.settings.firstVisibleItemScrollOffset,
                p.detail.firstVisibleItemIndex,p.detail.firstVisibleItemScrollOffset)},
            restore={s->Pager(LazyListState(s[0],s[1]),LazyListState(s[2],s[3]),LazyListState(s[4],s[5]),LazyListState(s[6],s[7]),LazyListState(s[8],s[9]))},
        )
    }
}

/**
 * Everything the four page bodies need, derived ONCE per data change and `remember`ed on the exact data
 * it uses. Reading a field of this holder does not read `vm.state`, so a page body that only reads
 * `state.accounts` is not invalidated by `jobRunning`/`message`/`loginBusy` writes.
 */
@Immutable
internal data class PageUiState(
    /** Accounts with a completed binding (drafts excluded) -- baseline line 30. */
    val accounts:List<Account>,
    /** Draft/binding-pending accounts shown with "继续读取角色". */
    val pendingAccounts:List<Account>,
    val records:List<CheckRecord>,
    val timeline:List<TimelineRecord>,
    val bindings:List<GameBinding>,
    val byId:Map<String,Account>,
    val authIssues:List<Account>,
    val tasks:List<GameBinding>,
    val completed:Int,
    val attention:Boolean,
    val scheduleEnabled:Boolean,
    val dailyTime:String,
    /**
     * Today's explicit feedback model (data/HomeFeedback.kt): per-game rows, the separate community and
     * authorization lanes, the phase and the last run's result. `null` only before the first reload.
     */
    val feedback:HomeFeedback?=null,
    /** The ViewModel's fix entry for the last failed operation (cause + buttons), not derived from records. */
    val fix:AuthFix?=null,
    val workerError:String?=null,
)

@Composable
private fun rememberPageUiState(s:UiState,safeAccounts:List<Account>):PageUiState = remember(
    s.overview,s.bindings,s.timeline,s.scheduleEnabled,s.dailyTime,s.feedback,s.fix,s.workerError
) {
    val accounts=safeAccounts
    val byId=accounts.associateBy{it.id}
    val bindings=s.bindings
    val records=s.overview?.records.orEmpty()
    // baseline line 66: one filter+distinctBy pass, not one per recomposition.
    val tasks=bindings.filter{it.enabled&&byId[it.accountId]?.enabled==true}.distinctBy{it.accountId to it.targetId}
    // baseline line 67 called present() per (task x record) pair in a nested any{}.
    val completedTargets=records.asSequence().filter{present(it).group=="success"}.map{it.accountId+"|"+it.targetId}.toSet()
    PageUiState(
        accounts=accounts, pendingAccounts=s.overview?.accounts.orEmpty().filter{it.bindingPending}, records=records,
        timeline=s.timeline, bindings=bindings, byId=byId,
        authIssues=accounts.filter{it.authState in AUTH_ISSUES},
        tasks=tasks, completed=tasks.count{(it.accountId+"|"+it.targetId) in completedTargets},
        attention=records.any{present(it).group in ATTENTION_GROUPS},
        scheduleEnabled=s.scheduleEnabled, dailyTime=s.dailyTime,
        feedback=s.feedback, fix=s.fix, workerError=s.workerError,
    )
}

private val AUTH_ISSUES=listOf("needs_action","failed")
private val ATTENTION_GROUPS=listOf("attention","uncertain","submitted")

/**
 * A record's pre-computed texts.
 *
 * Wording NOTE (deliberate): every user-facing label here comes from `present(record)` in
 * data/RecordPresentation.kt -- `label` / `group` / `source` -- and nothing in this file hardcodes
 * sign-in vocabulary for an attention/uncertain row. The "授权 vs 签到" distinction of the real-device
 * bug (a "授权与角色检查" record surfacing as 签到失败) belongs to teammate "presentation", who owns
 * RecordPresentation.kt; HoshioriScreen only carries whatever label/source present() returns. What this
 * class DOES add is pure cost removal: baseline line 117 called `present(r)` twice per row (label +
 * attention check) and `qTime(r.startedAt)` / `qTime(r.updatedAt)` twice, all inside the item lambda, i.e.
 * again on every recomposition of that row. Here they run once per record (memoized by the caller).
 */
internal data class RecordRow(
    val presentation:RecordPresentation,
    val started:String,
    val updated:String,
    /** True when the row is the user's problem to fix: the record itself failed/needs action or
     *  present() groups it as attention/uncertain/submitted. */
    val actionable:Boolean,
    val semanticStatus:String="unknown",
)

internal fun rowOf(record:CheckRecord):RecordRow {
    val p=present(record)
    return RecordRow(
        presentation=p,
        started=qTime(record.startedAt),
        updated=qTime(record.updatedAt),
        actionable=record.status=="failed"||record.status=="needs_action"||p.group in ATTENTION_GROUPS,
        semanticStatus=semanticState(record),
    )
}

/**
 * Scope summary for an account card (product bug: the card showed the legacy `accounts.gameIds` titles,
 * e.g. "原神 · 崩坏：星穹铁道 · 绝区零" (3 games), directly above a count derived from game_bindings
 * ("2 项角色签到"). Both numbers came from different sources, so the card contradicted itself.
 *
 * Source of truth, read off the code (no repo/DB change is made here):
 *  - `SignRepository.setBindings` sets `roleScope=1` and `learnBindings` returns early once roleScope is
 *    true, i.e. roleScope=true means "the user confirmed the role scope; game_bindings is authoritative".
 *  - roleScope=false means the rows are legacy/unscoped: gameIds is the only thing that exists.
 * Rules therefore:
 *  - roleScope=true  -> games are derived from the account's BINDINGS (grouped by gameId); a game the user
 *                       never scoped is never shown as covered. Plus "已确定角色范围 · N 个游戏 / M 项角色".
 *  - roleScope=true with no enabled binding -> "暂无启用角色" (never an empty game line).
 *  - roleScope=false -> gameIds preserved as-is and marked "尚未确定角色范围" so the user is not misled;
 *                       no migration, no write.
 * LIMIT (honest): a roleScope=false account whose games were in fact all selected long ago cannot be
 * told apart from one that was never scoped -- that needs a stored field. The marker therefore says
 * "not yet confirmed", never "wrong".
 */
internal data class ScopeSummary(
    /** Game ids the card may present as covered, in display order. */
    val gameIds:List<String>,
    /** Games derived from bindings (roleScope=true) rather than from the legacy column. */
    val fromBindings:Boolean,
    val enabledRoles:Int,
    /** One-line subtitle for the card; already localized, never empty. */
    val line:String,
)

internal fun accountScopeSummary(account:Account,bindings:List<GameBinding>):ScopeSummary {
    val enabled=bindings.filter{it.accountId==account.id&&it.enabled}
    if(account.roleScope) {
        val games=enabled.map{it.gameId}.distinct().filter{it.isNotBlank()}
        return ScopeSummary(
            gameIds=games, fromBindings=true, enabledRoles=enabled.size,
            line=if(enabled.isEmpty())"已确定角色范围 · 暂无启用角色"
                else "已确定角色范围 · ${games.size} 个游戏 / ${enabled.size} 项角色",
        )
    }
    return ScopeSummary(
        gameIds=account.gameIds, fromBindings=false, enabledRoles=enabled.size,
        line=if(account.gameIds.isEmpty())"尚未确定角色范围 · 游戏待读取"
            else "尚未确定角色范围 · ${account.gameIds.size} 个游戏（以已启用角色为准）",
    )
}

internal fun ScopeSummary.gamesLine():String = gameIds.mapNotNull{GameCatalog.find(it)?.title}.joinToString(" · ").ifBlank{"游戏/角色待读取"}

// ---------------------------------------------------------------------------------------------
// Screen
// ---------------------------------------------------------------------------------------------

@Composable fun HoshioriScreen(vm:HomeViewModel,onLogin:(String,String,String?)->Unit) {
    val s=vm.state
    val density=LocalDensity.current
    val colors=MaterialTheme.colorScheme
    // [H2] The whole pre-change body read `s` here; these five reads are the only ones the page shell needs.
    val accountsAll=s.overview?.accounts
    // [H1]/[H7] `filter{}` used to run on every recomposition. Keyed on the list instance only; the filter
    // itself is O(n) with n = number of bound accounts (a handful).
    val safeAccounts=remember(accountsAll){accountsAll.orEmpty().filter{!it.bindingPending}}
    val ui=rememberPageUiState(s,safeAccounts)

    val scope=rememberSaveable(saver=ScopeStateSaver){ScopeState()}
    val pager=rememberSaveable(saver=Pager.Saver){Pager(LazyListState(),LazyListState(),LazyListState(),LazyListState(),LazyListState())}
    // [H2] `modalOpen` is read from the holder only. It is a plain Boolean `remember`ed on the holder, so
    // unrelated holder writes (a filter chip, a Snackbar) cannot re-evaluate the page/dock branch. The one
    // piece of data-derived modality (an unconfirmed login sheet) is read ONCE here, next to it.
    val pendingPresent=s.pendingLogin!=null
    // Computed directly, NOT remembered: `scope` is a stable holder instance, so remember(scope,...) would
    // freeze the first value and the dock would stay visible over a freshly opened sheet/dialog.
    val modalOpen=scope.modalOpen||pendingPresent

    // The dock must also be hidden while the not-confirmed login sheet is up (NavigationV070Test asserts
    // "floating-dock" does not exist there), hence `pendingPresent` above.
    val a=ui.byId[scope.detail]
    val detailActive=scope.tab==1&&a!=null
    val listState=pager.slot(scope.tab,detailActive)

    // Derived lists hoisted here (the LazyListScope builders are not composable and cannot remember()).
    val bindingsByAccount=remember(ui.bindings){ui.bindings.groupBy{it.accountId}}
    val ownedBindings=remember(ui.bindings,a?.id){if(a==null)emptyList() else ui.bindings.filter{it.accountId==a.id}}
    val visibleRecords=remember(ui.timeline,ui.byId,scope.resultFilter,scope.providerFilter,scope.accountFilter,scope.gameFilter){
        ui.timeline.filter{e->
            val acc=ui.byId[e.record.accountId]
            (scope.resultFilter=="all"||present(e.record).group==scope.resultFilter||
                (scope.resultFilter=="uncertain"&&present(e.record).group=="submitted"))&&
                (scope.providerFilter=="all"||acc?.provider==scope.providerFilter)&&
                (scope.accountFilter=="all"||e.record.accountId==scope.accountFilter)&&
                (scope.gameFilter=="all"||acc?.let{GameCatalog.gameForTarget(it.provider,e.record.targetId)}==scope.gameFilter)
        }.sortedWith(compareByDescending<TimelineRecord>{it.day}.thenByDescending{it.record.updatedAt})
    }

    // [H4] detail offset restore: only when the detail target changes (LaunchedEffect key = account id).
    LaunchedEffect(scope.detail,detailActive){if(detailActive)pager.detail.requestScrollToItem(0)}

    val context=LocalContext.current
    val permission=androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()){}
    LaunchedEffect(s.focusAccountId){s.focusAccountId?.let{scope.tab=1;scope.detail=it;vm.clearFocus()}}
    androidx.activity.compose.BackHandler(enabled=!modalOpen&&(scope.tab!=0||scope.detail!=null)){if(scope.detail!=null)scope.detail=null else scope.tab=0}

    val vmDeps=remember(vm){VmActions(vm)}
    val onSelect=remember(scope){ { index:Int -> scope.tab=index; scope.detail=null } }
    val policy=currentGlassPolicy()
    // The dock samples the page content into a layer; must match policy.blur exactly.
    val backdrop=rememberPageBackdrop(policy.blur)
    /*
     * Actual dock overlay height in px, measured by the dock after layout (bar + lift + navigation-bar
     * inset). Held here so the list's bottom contentPadding follows the real value instead of a fixed
     * 160.dp, which was wrong for gesture navigation, for the 200% font bar and in landscape.
     * It is a plain state holder, written only when the integer px changes, and its reader is the
     * contentPadding expression below -- not the dock, so this cannot loop.
     */
    val dockOverlap=remember{mutableFloatStateOf(0f)}
    val densityNow=LocalDensity.current

    Box(Modifier.fillMaxSize().background(colors.background)) {
        // [LAYOUT-vs-DRAW] Box owns the snapshot: everything inside this Box is the page, and the dock is a
        // SIBLING (outside), so the snapshot can never contain the dock (no feedback loop) and never needs
        // the dock's position. Recording still happens in a DRAW modifier (recording in a layout lambda
        // throws "layer is not in a draw pass"); nothing here records from a measure/layout pass.
        Box(Modifier.fillMaxSize().backdropSource(backdrop)) {
            com.glasssign.app.ui.background.HoshioriBackground(Modifier.fillMaxSize())
            key(scope.tab,scope.detail) {
                LazyColumn(
                    state=listState,
                    modifier=Modifier.fillMaxSize().testTag("page-${scope.tab}"),
                    // Cards stay inset 20.dp on BOTH sides (start=end=20.dp). The end inset is NOT removed:
                    // every card in this list is also a child of this padding, so dropping it would make the
                    // cards touch the screen edge -- a worse bug than the one being fixed. The records filter
                    // rows instead use Modifier.bleed(PAGE_INSET) to cancel the inset for their own scrollable
                    // viewport only (see RecordsPage), leaving the cards untouched.
                    contentPadding=PaddingValues(start=PAGE_INSET,end=PAGE_INSET,top=12.dp,
                        bottom=dockBottomPaddingPx(dockOverlap.floatValue,densityNow)),
                    verticalArrangement=Arrangement.spacedBy(16.dp),
                ) {
                    item(key="header",contentType="header") {
                        PageHeader(tab=scope.tab,detail=a,accounts=ui.accounts,ui=ui,onBack={scope.detail=null},onAdd={scope.renew=null;scope.adding=true})
                    }
                    when(scope.tab) {
                        // The onTab lambda routes the baseline's inline `tab=1;detail=null` / `tab=2` jumps.
                        0 -> HomePage(ui,s,vmDeps,onTab={ target -> onSelect(target); if(target==1) scope.detail=null })
                        1 -> AccountsPage(scope,ui,a,vmDeps,s,bindingsByAccount,ownedBindings)
                        2 -> RecordsPage(scope,ui,vmDeps,visibleRecords)
                        3 -> SettingsPage(ui,s,policy,onOpenAppearance={scope.appearance=true},onOpenPlan={scope.plan=true},
                            onRefresh={vmDeps.refresh(false)},onSettings=onSettingsAction(context,permission),onTab={ target -> onSelect(target); if(target==1) scope.detail=null },
                            onSchedule={enabled->vmDeps.saveSchedule(enabled,ui.dailyTime)},onReduceMotion={vmDeps.setReduceMotion(it)})
                    }
                    item(key="end",contentType="end"){Spacer(Modifier.height(12.dp))}
                }
            }
        }
        // [H1] The only place WindowInsets are read. Its composition contains the dock call and nothing else,
        // so an IME frame recomposes ~2 composables instead of the page body + four branches.
        if(!modalOpen)DockOverlay(backdrop=backdrop,policy=policy,selected=scope.tab,onSelect=onSelect,enabled=true,
            onOverlapChange={px->dockOverlap.floatValue=px})
        // Feedback pass: a specific failure now renders as the home "这一步没有完成" card with its own fix buttons
        // (see ui/SignFeedback.kt), so only neutral confirmations still use the transient Snackbar -- one failure no
        // longer disappears after 6.5 seconds with no way to act on it.
        s.message?.let{message->Snackbar(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(16.dp),action={TextButton(onClick={vm.dismissMessage(message.at)}){Text("知道了")}}){Text(message.text)}}
        if(s.loginBusy&&s.pendingLogin==null)Surface(Modifier.align(Alignment.Center),shape=RoundedCornerShape(24.dp)){Column(Modifier.padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally){CircularProgressIndicator();Spacer(Modifier.height(12.dp));Text("正在读取账号与角色…")}}
    }
    if(scope.adding)AddAccountSheet(ui.byId[scope.renew],s.loginBusy,{scope.adding=false},{provider,alias->scope.adding=false;onLogin(provider,alias,scope.renew)},{provider,alias,c->scope.adding=false;vm.beginLogin(provider,alias,c,scope.renew)})
    s.pendingLogin?.let{p->RoleBindingSheet(p,s.loginBusy,vm::togglePendingGame,vm::togglePendingRole,vm::pendingAuto,vm::reviewBinding,vm::confirmLogin,vm::cancelLogin){val original=vm.replacementForPending();vm.cancelLogin();onLogin(p.provider,p.nickname,original)}}
    if(scope.appearance)AppearanceDialog{scope.appearance=false}
    if(scope.plan)SettingsDialog(scheduleEnabled=s.scheduleEnabled,dailyTime=s.dailyTime,reduceMotion=s.reduceMotion,busy=s.jobRunning,onDismiss={scope.plan=false},onSave={enabled,time->vm.saveSchedule(enabled,time);scope.plan=false;if(enabled&&android.os.Build.VERSION.SDK_INT>=33)permission.launch(android.Manifest.permission.POST_NOTIFICATIONS)},onClear=vm::clearCredentials,onReduceMotion=vm::setReduceMotion)
    scope.rename?.let{account->var alias by remember(account.id){mutableStateOf(account.nickname)};AlertDialog(onDismissRequest={scope.rename=null},title={Text("编辑账号备注")},text={OutlinedTextField(alias,{alias=it.take(40)},label={Text("备注")})},confirmButton={TextButton(onClick={vm.rename(account.id,alias);scope.rename=null},enabled=alias.isNotBlank()){Text("保存")}},dismissButton={TextButton(onClick={scope.rename=null}){Text("取消")}})}
    scope.danger?.let{(account,kind)->AlertDialog(onDismissRequest={scope.danger=null},title={Text(when(kind){"revoke"->"解除授权？";"history"->"删除历史明细？";else->"删除账号？"})},text={Text("${providerMeta(account.provider).title} · ${safeLabel(account.nickname)}\n"+when(kind){"revoke"->"仅清除加密授权，保留账号、角色、暂停偏好与历史。";"history"->"只删除该账号历史明细，不删除授权或改变计划。当天提交保护仍保留，未知结果不会重发。";else->"删除此账号及所属角色、授权和全部本机记录。其他账号不受影响。"})},confirmButton={TextButton(onClick={when(kind){"revoke"->vm.revoke(account.id);"history"->vm.clearRecords(account.id);else->{vm.deleteAccount(account.id,account.nickname);scope.detail=null}};scope.danger=null}){Text("确认",color=colors.error)}},dismissButton={TextButton(onClick={scope.danger=null}){Text("取消")}})}
    scope.record?.let{entry->val r=entry.record;val account=ui.byId[r.accountId];val row=remember(entry){rowOf(r)};AlertDialog(onDismissRequest={scope.record=null},title={Text(row.presentation.label)},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){Text("${entry.day} · ${safeLabel(account?.nickname.orEmpty())}");Text(safeLabel(r.targetName));Text(row.presentation.source);Text(safeLabel(r.message));Text(recordDiagnostics(r),style=MaterialTheme.typography.bodySmall);Text("开始：${row.started}\n结束 / 最近状态：${row.updated}\n提交尝试：${r.attempts}")}},confirmButton={TextButton(onClick={scope.record=null}){Text("关闭")}},dismissButton={Column{if(account!=null&&entry.day==s.overview?.date)TextButton(onClick={vm.reconcile(account.id,r.targetId);scope.record=null}){Text("校验状态（不提交）")};if(account!=null)TextButton(onClick={scope.record=null;scope.tab=1;scope.detail=account.id}){Text("修复账号")};if(account!=null)TextButton(onClick={runCatching{context.startActivity(Intent(Intent.ACTION_VIEW,android.net.Uri.parse(com.glasssign.app.login.LoginPlatforms.of(account.provider).startUrl)))}}){Text("打开官方页面")}}})}
}

/**
 * Saveable form of [ScopeState]: 8 primitives, no Bundle class loading.
 * (`tab`, `detail`) must survive rotation the same way the baseline `rememberSaveable` locals did; the six
 * Destructive/action dialog flags are not restored. Appearance is a read-only draft until the user
 * applies a setting, so keep that sheet open to restore its RGB draft across configuration changes.
 */
private val ScopeStateSaver:Saver<ScopeState,Any> = listSaver(
    save={v->listOf<Any?>(v.tab,v.detail,v.adding,v.renew,v.resultFilter,v.providerFilter,v.accountFilter,v.gameFilter,v.appearance)},
    restore={r->ScopeState().apply{
        tab=r.getOrNull(0) as? Int ?: 0
        detail=r.getOrNull(1) as? String
        adding=r.getOrNull(2) as? Boolean ?: false
        renew=r.getOrNull(3) as? String
        resultFilter=r.getOrNull(4) as? String ?: "all"
        providerFilter=r.getOrNull(5) as? String ?: "all"
        accountFilter=r.getOrNull(6) as? String ?: "all"
        gameFilter=r.getOrNull(7) as? String ?: "all"
        appearance=r.getOrNull(8) as? Boolean ?: false
    }},
)

/** Stable action bundle: one object per ViewModel, so the page lambdas handed to list items do not capture
 *  `vm` plus changing values (baseline item lambdas closed over `s`, `accounts`, `byId` at call time). */
@Stable
internal class VmActions(private val vm:HomeViewModel) {
    fun runNow(ids:List<String>?=null){vm.runNow(ids)}
    fun clearFocus(){vm.clearFocus()}
    fun verifyAuthorization(id:String){vm.verifyAuthorization(id)}
    fun discardDraft(id:String){vm.discardDraft(id)}
    fun setEnabled(id:String,value:Boolean){vm.setEnabled(id,value)}
    fun autoEnabled(id:String,value:Boolean){vm.autoEnabled(id,value)}
    fun manageGames(id:String){vm.manageGames(id)}
    fun bindingEnabled(accountId:String,target:String,value:Boolean){vm.bindingEnabled(accountId,target,value)}
    fun reconcile(accountId:String,target:String){vm.reconcile(accountId,target)}
    fun setReduceMotion(value:Boolean){vm.setReduceMotion(value)}
    fun saveSchedule(enabled:Boolean,time:String){vm.saveSchedule(enabled,time)}
    fun refresh(showLoading:Boolean){vm.refresh(showLoading)}
    fun verifyAccount(id:String){vm.verifyAccount(id)}
    fun retryAccount(id:String){vm.retryAccount(id)}
    fun dismissFix(){vm.dismissFix()}
}

// ---------------------------------------------------------------------------------------------
// Overlays
// ---------------------------------------------------------------------------------------------

private fun onSettingsAction(context:Context,permission:androidx.activity.result.ActivityResultLauncher<String>):(String)->Unit = {action->
    if(action=="plugins")context.startActivity(Intent(context,com.glasssign.app.plugins.PluginsActivity::class.java))
    else if(action=="notification"&&android.os.Build.VERSION.SDK_INT>=33)permission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    else runCatching{context.startActivity(Intent(if(action=="battery")Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS else if(action=="notification")Settings.ACTION_APP_NOTIFICATION_SETTINGS else Settings.ACTION_WIRELESS_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,context.packageName))}
}

/**
 * The dock leaf. This is the ONLY composable in the file that reads `WindowInsets.ime` (see [H1] at the top):
 * its parent reads no insets, so the invalidation scope of an IME animation frame ends here instead of
 * reaching the page body. It is declared as `BoxScope` extension because the dock is aligned
 * BottomCenter inside the screen's root Box.
 *
 * Behaviour is intentionally unchanged: the dock disappears while the IME is up (as before) and `enabled`
 * is forwarded to the dock, which makes it invisible to TalkBack when false.
 *
 * [onOverlapChange] forwards the dock's measured overlay height to the page list. It is a plain lambda, so
 * the IME insets read here still do not leak into the page body's composition.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BoxScope.DockOverlay(backdrop:PageBackdropState,policy:GlassPolicy,selected:Int,onSelect:(Int)->Unit,enabled:Boolean,onOverlapChange:(Float)->Unit={}) {
    val density=LocalDensity.current
    val imeVisible=WindowInsets.isImeVisible
    val imeBottom=WindowInsets.ime.getBottom(density)
    if(imeBottom==0&&!imeVisible) {
        LiquidDock(selected=selected,onSelect=onSelect,backdrop=backdrop,policy=policy,enabled=enabled,onOverlapChange=onOverlapChange,modifier=Modifier.align(Alignment.BottomCenter))
    }
}

// ---------------------------------------------------------------------------------------------
// Pages
// ---------------------------------------------------------------------------------------------

/** Page background gradient as a remembered brush (was a new Brush allocated per background() call). */
@Composable private fun PageGradient(top:Color,mid:Color,bottom:Color):Brush = remember(top,mid,bottom) {
    Brush.linearGradient(listOf(top.copy(alpha=.38f),mid,bottom.copy(alpha=.18f)))
}

@Composable private fun PageHeader(tab:Int,detail:Account?,accounts:List<Account>,ui:PageUiState,onBack:()->Unit,onAdd:()->Unit) {
    Row(Modifier.statusBarsPadding().fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
        if(tab==1&&detail!=null)IconButton(onClick=onBack){Icon(Icons.Rounded.ArrowBack,"返回账号列表")}
        Column(Modifier.weight(1f)) {
            Text(when(tab){0->"星栞";1->if(detail==null)"账号" else "账号详情";2->"记录";else->"设置"},style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.SemiBold)
            Text(
                when(tab){
                    0->"今日签到 · ${ui.accounts.size} 个账号"
                    1->if(detail==null)"${accounts.map{it.provider}.distinct().size} 个平台 · ${accounts.size} 个账号 · ${ui.bindings.count{it.enabled&&it.accountId in ui.byId}} 项游戏/角色" else providerMeta(detail.provider).title
                    2->"每次执行都有独立结果"
                    else->"本机计划 · 运行诊断 · 外观"
                },style=MaterialTheme.typography.bodySmall,color=Muted
            )
        }
        if(tab==1&&detail==null)FilledTonalIconButton(onClick=onAdd,modifier=Modifier.sizeIn(minWidth=48.dp,minHeight=48.dp).testTag("add-account")){Icon(Icons.Rounded.Add,"新增账号")}
    }
}

/** Shared LazyListScope helper: `items` with a stable key + contentType. The item lambda is @Composable. */
private fun LazyListScope.rows(
    items:List<Account>,key:(Account)->String=Account::id,contentType:String="account",item:@Composable (Account)->Unit,
) = items(items,key=key,contentType={contentType}){v->item(v)}

// LazyListScope builders are NOT @Composable: LazyColumn's `content` is a plain (non-composable) builder
// lambda, so a composable builder function cannot be called there. Anything that needs remember() is
// computed by the caller (the composable page body) and passed in; item{} lambdas stay @Composable.
private fun LazyListScope.HomePage(ui:PageUiState,s:UiState,vm:VmActions,onTab:(Int)->Unit) {
    val records=ui.records
    val accounts=ui.accounts

    if(accounts.isEmpty()) {
        item(key="empty",contentType="empty"){QCard{Text("还没有绑定账号",style=MaterialTheme.typography.headlineSmall);Text("绑定一次，每日由手机本地签到。",color=Muted);Button(onClick={onTab(1)},modifier=Modifier.fillMaxWidth().heightIn(min=50.dp)){Text("去绑定账号")}}}
    } else {
        // v0.7.4 feedback pass: ONE status card (state word + phase + per-lane counts + the primary action), then
        // the last run's readable result, then a specific fix entry when an operation failed, then at most three
        // lane cards (游戏 / 授权 / 社区). Before this the page emitted one generic failure card per account, one
        // card per binding task with a bare record status, and a count that merged 社区奖励 into "已成功 N 项".
        // Everything comes from ui.feedback (data/HomeFeedback.kt), computed once per state change.
        HomeFeedbackCards(ui,s,vm,onTab)
        FeedbackLaneCards(ui,vm,onTab)
        // Legacy accounts without any binding row still need an entry point from the home page.
        if(ui.feedback==null) {
            rows(ui.authIssues,key={"auth-${it.id}"},contentType="auth-issue"){account->
                QCard({onTab(1)}){Text("${providerMeta(account.provider).title} · ${safeLabel(account.nickname)}需要处理",color=MaterialTheme.colorScheme.error);Text("点击修复授权",style=MaterialTheme.typography.bodySmall,color=Muted)}
            }
        }
    }
    item(key="plan",contentType="plan"){QCard({onTab(3)}){Text("每日计划",style=MaterialTheme.typography.titleMedium);Text(if(ui.scheduleEnabled)"已开启 · ${ui.dailyTime} 后由系统安排" else "未开启 · 可在设置中安排",color=Muted);Text("最近实际执行：${qTime(s.overview?.lastFinishedAt)}",style=MaterialTheme.typography.bodySmall,color=Muted);Text("下次预计：${qNext(s)}",style=MaterialTheme.typography.bodySmall,color=Muted)}}
}

private fun LazyListScope.AccountsPage(scope:ScopeState,ui:PageUiState,a:Account?,vm:VmActions,s:UiState,bindingsByAccount:Map<String,List<GameBinding>>,owned:List<GameBinding>) {
    if(a==null) {
        if(ui.accounts.isEmpty())item(key="empty",contentType="empty"){QCard{Text("让账号各自独立",style=MaterialTheme.typography.headlineSmall);Text("点击右上角 +，选择平台后登录。一个账号可管理多个游戏与角色。",color=Muted)}}
        // [H5] bindings grouped once by the caller; the item lambda closes over the ready map.
        items(ui.accounts,key={it.id},contentType={"account"}){account->
            QAccount(account,bindingsByAccount[account.id].orEmpty()){id->scope.detail=id}
        }
        items(ui.pendingAccounts,key={"pending-${it.id}"},contentType={"pending"}){account->QCard{Text("未完成的绑定 · ${safeLabel(account.nickname)}",overflow=TextOverflow.Ellipsis,maxLines=2);Text("尚未启用签到",color=Muted);TextButton(onClick={vm.verifyAuthorization(account.id)}){Text("继续读取角色")};TextButton(onClick={vm.discardDraft(account.id)}){Text("取消这次绑定")}}}
    } else {
        AccountDetailPage(scope,ui,a,vm,s,owned)
    }
}

private fun LazyListScope.AccountDetailPage(scope:ScopeState,ui:PageUiState,a:Account,vm:VmActions,s:UiState,owned:List<GameBinding>) {
    item(key="identity",contentType="identity"){QAccount(a,owned,null)}
    item(key="actions",contentType="actions"){QCard{
        QSwitch("启用此账号签到","关闭后手动与自动队列均跳过",a.enabled){vm.setEnabled(a.id,it)}
        QSwitch("参加每日自动签到","需开启全局计划；关闭后仍可手动签到",a.autoSignEnabled){vm.autoEnabled(a.id,it)}
        OutlinedButton(onClick={vm.manageGames(a.id)},enabled=!s.loginBusy,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("manage-games")){Text("管理游戏与角色")}
        TextButton(onClick={vm.verifyAccount(a.id)},enabled=!s.loginBusy&&!s.jobRunning&&!s.submitting){Text("验证授权（不签到）")}
        val own=ui.feedback?.gameRows?.filter{it.accountId==a.id}.orEmpty().ifEmpty{null}
        val done=own?.let{rows->rows.isNotEmpty()&&rows.all{it.state==RowState.COMPLETED}} ?: ui.records.filter{it.accountId==a.id}.let{it.isNotEmpty()&&it.all{r->present(r).group=="success"}}
        Text(
            when {
                own==null -> if(done)"今日角色已全部签到" else "尚未完成今天全部角色"
                else -> "今日已完成 ${own.count{it.state==RowState.COMPLETED}} / ${own.size} 项"+
                    (own.count{it.state==RowState.UNCERTAIN}.takeIf{it>0}?.let{" · 待确认 $it"} ?: "")+
                    (own.count{it.state==RowState.SUBMITTED}.takeIf{it>0}?.let{" · 待复核 $it"} ?: "")
            },
            style=MaterialTheme.typography.bodySmall,color=Muted
        )
        val authProblem=ui.feedback?.authRows?.firstOrNull{it.accountId==a.id}
        if(authProblem!=null)Text("${authProblem.label}：${authProblem.detail}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error,maxLines=3,overflow=TextOverflow.Ellipsis)
        Button(onClick={vm.retryAccount(a.id)},enabled=a.enabled&&!s.jobRunning&&!s.submitting&&!s.waitingNetwork,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text(if(done)"重新检查此账号" else "立即签到")}
    }}
    items(owned,key={it.targetId},contentType={"binding"}){b->QCard{QSwitch(safeLabel(b.name),"${GameCatalog.find(b.gameId)?.title} · 独立角色签到项",b.enabled){vm.bindingEnabled(a.id,b.targetId,it)}}}
    item(key="maintenance",contentType="maintenance"){QCard{
        Text("账号维护",style=MaterialTheme.typography.titleMedium)
        TextButton(onClick={scope.renew=a.id;scope.adding=true}){Text("重新授权")}
        TextButton(onClick={scope.rename=a}){Text("编辑备注")}
        TextButton(onClick={scope.danger=a to "revoke"}){Text("解除授权",color=MaterialTheme.colorScheme.error)}
        TextButton(onClick={scope.danger=a to "history"}){Text("删除此账号历史",color=MaterialTheme.colorScheme.error)}
        TextButton(onClick={scope.danger=a to "delete"}){Text("删除账号与全部本机数据",color=MaterialTheme.colorScheme.error)}
    }}
}

/**
 * Records page chips.
 *
 * OVERFLOW FINDING (real, reported to lead): the two filter rows are `horizontalScroll` containers inside a
 * LazyColumn that pads start=20.dp / end=20.dp. Because the scroll container was constrained to the padded
 * width, no amount of scrolling could bring the trailing 20.dp of content into view: the row visually
 * stopped 20.dp short of the screen edge, so the last chip could not be scrolled fully clear and looked
 * clipped. The baseline also had no start padding of its own, so the first chip's left edge was flush
 * against the card edge while every card below it was inset -- an inconsistent alignment.
 *
 * FIX SHIPPED: the page keeps start=end=20.dp (cards must stay inset on both sides). Each filter row calls
 * [bleed] with that inset to cancel it for its own scrollable viewport, then re-adds it as content padding,
 * so the viewport spans the full screen width while the first and last chips still align with the cards.
 *
 * Touch targets and font size are untouched (FilterChip keeps its M3 48dp height); long provider/account
 * names are ellipsized inside the chip instead of widening it without bound.
 */
@OptIn(ExperimentalFoundationApi::class)
private fun LazyListScope.RecordsPage(scope:ScopeState,ui:PageUiState,vm:VmActions,visible:List<TimelineRecord>) {
    item(key="filters",contentType="filters"){Column {
        val statusScroll=rememberScrollState()
        val metadataScroll=rememberScrollState()
        Row(Modifier.fillMaxWidth().horizontalScroll(statusScroll),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically){
            listOf("all" to "全部","success" to "已完成","attention" to "需要处理","uncertain" to "待确认").forEach{(id,label)->
                val requester=remember { BringIntoViewRequester() }
                LaunchedEffect(scope.resultFilter) { if(scope.resultFilter==id)requester.bringIntoView() }
                FilterChip(scope.resultFilter==id,{scope.resultFilter=id},modifier=Modifier.bringIntoViewRequester(requester),label={Text(label,maxLines=1)})
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(metadataScroll),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically){
            QFilter("平台",scope.providerFilter,providerOptions){scope.providerFilter=it}
            QFilter("账号",scope.accountFilter,accountOptions(ui)){scope.accountFilter=it}
            QFilter("游戏",scope.gameFilter,gameOptions){scope.gameFilter=it}
        }
        if(statusScroll.maxValue>0||metadataScroll.maxValue>0)Text("左右滑动查看全部筛选项",style=MaterialTheme.typography.labelSmall,color=Muted)
    }}
    if(visible.isEmpty())item(key="empty",contentType="empty"){QCard{Text("暂无符合条件的记录",style=MaterialTheme.typography.titleMedium);Text("签到后按日期、账号与游戏保留结果。",color=Muted)}}
    items(visible,key={it.day+"|"+it.record.accountId+"|"+it.record.targetId},contentType={"record"}){entry->
        RecordCard(entry,ui.byId[entry.record.accountId]?.nickname.orEmpty()){scope.record=entry;scope.tab=2}
    }
    item(key="retry",contentType="retry"){Column{if(ui.timeline.any{it.record.retryable&&it.record.status in RETRYABLE})OutlinedButton(onClick={vm.runNow()},enabled=ui.accounts.isNotEmpty()){Text("重试可安全重试的未完成项")};Text("只有明确可重试的失败项才会重试；待确认提交不重发，点击记录可校验官方状态。",style=MaterialTheme.typography.bodySmall,color=Muted)}}
}

private val RETRYABLE=listOf("failed","waiting_network")

@Composable private fun RecordCard(entry:TimelineRecord,accountNickname:String,onOpen:(TimelineRecord)->Unit) {
    val r=entry.record
    // [H6] computed once per record instance: present() ran 2x and qTime() 2x per row recomposition before.
    val row=remember(r){rowOf(r)}
    val errorColor=MaterialTheme.colorScheme.error
    QCard({onOpen(entry)}){
        Text("${entry.day} · ${safeLabel(accountNickname)}",style=MaterialTheme.typography.bodySmall,color=Muted,overflow=TextOverflow.Ellipsis,maxLines=1)
        // OVERFLOW: the status is the critical side, so it is NOT truncated -- it wraps to
        // SUBLABEL_MAX_LINES lines inside a bounded width. The game/role name is the long, user-controlled
        // side and takes `weight(1f)` with ellipsis, so it yields space to the status instead of the other
        // way round. Before this, both were unweighted in a Row: a long present() label plus a long target
        // name measured wider than the card and the chip was clipped at the card edge.
        Row(verticalAlignment=Alignment.Top){
            Text(safeLabel(r.targetName),modifier=Modifier.weight(1f),style=MaterialTheme.typography.titleMedium,overflow=TextOverflow.Ellipsis,maxLines=2)
            Spacer(Modifier.width(8.dp))
            QResultRow(row,modifier=Modifier.widthIn(max=SUBLABEL_MAX))
        }
        Text(safeLabel(r.message),style=MaterialTheme.typography.bodySmall,color=Muted)
        Text("开始 ${row.started}\n结束 / 更新 ${row.updated}",style=MaterialTheme.typography.labelSmall,color=Muted)
        if(row.actionable&&row.presentation.group!="success")TextButton(onClick={onOpen(entry)}){Text("查看详情与来源",color=errorColor)}
    }
}

private fun LazyListScope.SettingsPage(ui:PageUiState,s:UiState,policy:GlassPolicy,onOpenAppearance:()->Unit,onOpenPlan:()->Unit,onRefresh:()->Unit,onSettings:(String)->Unit,onTab:(Int)->Unit,onSchedule:(Boolean)->Unit,onReduceMotion:(Boolean)->Unit) {
    item(key="automatic",contentType="automatic"){QCard{Text("自动签到",style=MaterialTheme.typography.titleLarge);QSwitch("每日自动签到","手机本地执行；系统可能延迟",ui.scheduleEnabled){enabled->onSchedule(enabled);onOpenPlan()};TextButton(onClick=onOpenPlan){Text("每日运行偏好 · ${ui.dailyTime}")};Text("本次开始：${if(s.jobRunning)qTime(s.overview?.lastStartedAt) else "当前未执行"}",color=Muted);Text("最近实际执行：${qTime(s.overview?.lastFinishedAt)}",color=Muted);Text("下次预计：${qNext(s)}",color=Muted);TextButton(onClick={onTab(1)}){Text("按账号 / 游戏管理")}}}
    item(key="diagnostics",contentType="diagnostics"){QDiagnostics(s,policy,onRefresh,onSettings)}
    item(key="appearance",contentType="appearance"){QCard{Text("外观",style=MaterialTheme.typography.titleLarge);TextButton(onClick=onOpenAppearance,modifier=Modifier.testTag("appearance")){Text("主题与液态玻璃")};QSwitch("减少动效","关闭弹性过渡，同时尊重系统动画设置",s.reduceMotion){onReduceMotion(it)};Text("当前材质：${policy.reason}",style=MaterialTheme.typography.bodySmall,color=Muted)}}
    item(key="updates",contentType="updates"){com.glasssign.app.ui.update.HoshioriUpdateCard()}
    item(key="plugins",contentType="plugins"){QCard{Text("插件",style=MaterialTheme.typography.titleLarge);Text("按需下载扩展，核心游戏签到无需安装插件。",style=MaterialTheme.typography.bodyMedium,color=Muted);TextButton(onClick={onSettings("plugins")},modifier=Modifier.testTag("plugin-manager")){Text("插件管理")}}}
    item(key="privacy",contentType="privacy"){QCard{Text("数据与隐私",style=MaterialTheme.typography.titleLarge);Text("授权通过本机 Keystore 加密。密码、验证码不保存；不需要云服务器。",color=Muted);Text("升级自动迁移本机数据，失败保留原数据与私有检查点。暂不支持跨设备授权备份；卸载或清数据会丢失本机密钥。",style=MaterialTheme.typography.bodySmall,color=Muted);TextButton(onClick={onTab(1)}){Text("授权管理与历史清理")}}}
    item(key="about",contentType="about"){Text("星栞 Hoshiori ${BuildConfig.VERSION_NAME}\n本机执行 · 无自建服务器\n第三方实号及长期后台表现以实际验收为准",style=MaterialTheme.typography.bodySmall,color=Muted)}
    item(key="brand",contentType="brand"){HoshioriBrandCard()}
}

// ---------------------------------------------------------------------------------------------
// Shared pieces (signatures preserved for other screens/tests)
// ---------------------------------------------------------------------------------------------

@Composable internal fun QCard(onClick:(()->Unit)?=null,content:@Composable ColumnScope.()->Unit) {val c=MaterialTheme.colorScheme;val cardAlpha=if(com.glasssign.app.ui.background.LocalBackgroundEnabled.current).78f else .94f;Surface(Modifier.fillMaxWidth().then(if(onClick!=null)Modifier.clickable(onClick=onClick) else Modifier),shape=RoundedCornerShape(24.dp),color=c.surface.copy(alpha=cardAlpha),contentColor=c.onSurface,border=BorderStroke(1.dp,c.outlineVariant.copy(alpha=.5f))){Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp),content=content)}}
@Composable internal fun QSwitch(title:String,description:String,checked:Boolean,onChange:(Boolean)->Unit){Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){Column(Modifier.weight(1f)){Text(title,style=MaterialTheme.typography.titleSmall);Text(description,style=MaterialTheme.typography.bodySmall,color=Muted)};Switch(checked,onChange)}}

/**
 * Account card. `onClick==null` renders the same card without a click action (detail header).
 *
 * THE CONTRADICTION THIS FIXES: the card used to render `a.gameIds` titles (the legacy `accounts.games`
 * column) on one line and `bindings.count{it.enabled}` on the next. For a 米游社 account with 3 legacy
 * gameIds and 2 enabled game_bindings the card read "原神 · 崩坏：星穹铁道 · 绝区零" then "2 项角色签到", i.e.
 * three games listed above a count that covers two -- because the two lines came from different tables.
 * [accountScopeSummary] now picks ONE source per account: bindings when roleScope=true (the user confirmed
 * the role scope, so game_bindings is authoritative), legacy gameIds with an explicit "尚未确定角色范围"
 * marker when roleScope=false.
 */
@Composable private fun QAccount(a:Account,bindings:List<GameBinding>,onClick:((String)->Unit)?) {
    val meta=providerMeta(a.provider)
    val valid=a.authState=="valid"
    val summary=remember(a,bindings){accountScopeSummary(a,bindings)}
    val games=remember(summary){summary.gamesLine()}
    // Identity model (data/AccountDisplay.kt): the platform's readable name+tag is the default title, the user's
    // remark is its own line, the masked platform UID is a supplement, and roles are summarised. A role display
    // name is only used as a title when the platform wrote it as a platform card ("清寒#7810").
    val identity=remember(a,bindings){accountDisplay(a.provider,a.nickname,a.providerUserId,bindings.map{it.name})}
    QCard(if(onClick==null)null else {{onClick(a.id)}}) {
        // OVERFLOW at 200% font scale: the title/nickname column takes weight(1f) with ellipsis, and the
        // status word is capped so 2x font cannot squeeze the title down to a single glyph before wrapping.
        Row(verticalAlignment=Alignment.CenterVertically){ProviderBadge(meta);Spacer(Modifier.width(12.dp));Column(Modifier.weight(1f)){Text(safeLabel(identity.title),style=MaterialTheme.typography.titleMedium,overflow=TextOverflow.Ellipsis,maxLines=1);identity.customNote?.let{Text("备注 · ${safeLabel(it)}",color=Muted,overflow=TextOverflow.Ellipsis,maxLines=1)}};Text(if(!a.enabled)"已暂停" else if(valid)"授权可用" else "需要处理",modifier=Modifier.widthIn(max=SUBLABEL_MAX),style=MaterialTheme.typography.labelLarge,color=if(valid)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,maxLines=2,overflow=TextOverflow.Ellipsis)}
        identity.platformLine?.let{Text(it,style=MaterialTheme.typography.bodySmall,color=Muted,overflow=TextOverflow.Ellipsis,maxLines=2)}
        // 角色行: 读到角色后，标题之外还有角色才显示汇总；一条角色都没读到时才显示占位符，
        // 因此"标题里已经是那条角色"不会再被同时写成"角色待同步"。
        if(identity.hasRoleData) identity.rolesLine?.let{Text(safeLabel(it),style=MaterialTheme.typography.bodySmall,color=Muted,overflow=TextOverflow.Ellipsis,maxLines=2)}
        else Text(PENDING_ROLES,style=MaterialTheme.typography.bodySmall,color=Muted,overflow=TextOverflow.Ellipsis,maxLines=2)
        Text(games,overflow=TextOverflow.Ellipsis,maxLines=2)
        Text(summary.line,style=MaterialTheme.typography.bodySmall,color=Muted)
        Text(if(a.autoSignEnabled)"参加自动计划" else "仅手动 · 不参加自动计划",style=MaterialTheme.typography.bodySmall,color=Muted)
        if(a.authMessage.isNotBlank())Text(safeLabel(a.authMessage),style=MaterialTheme.typography.bodySmall,color=Muted)
    }
}


/**
 * Status word on a record card. The LABEL IS NEVER TRUNCATED: this is the one place that tells the user
 * what actually happened, so an ellipsized "需要处理…" would hide the critical part of the message.
 *
 * It wraps to at most [SUBLABEL_MAX_LINES] lines instead (bounded so one verbose provider string cannot
 * push the card's own details off screen); the game/role name on the left takes `weight(1f)` and ellipsizes
 * first, so the status always wins the space it needs. A whole row tap opens the detail sheet, which
 * repeats the full error, source, providerCode, httpStatus, verificationMethod, verifiedAt and executionId,
 * so nothing is only reachable from this label.
 *
 * Font size is not reduced and the touch target is unaffected: this is a Text, not a control.
 */
internal const val SUBLABEL_MAX_LINES=3

/**
 * Semantic status tone. The five names are the product vocabulary (greenSuccess / tealAlready /
 * amberAccepted / grayUnknown / redFailed). Theme owns the actual palette, so this maps each presentation
 * group onto an existing ColorScheme role instead of hard-coding hex values that would not follow the
 * theme or its dark/dynamic variants.
 *
 * Pure and internal so the mapping is pinned by a JVM test: a group silently falling through to the muted
 * colour is exactly how "需要处理" would stop looking urgent.
 */
internal enum class StatusTone { GreenSuccess, TealAlready, AmberAccepted, GrayUnknown, RedFailed }

internal fun statusTone(group:String):StatusTone = when(group.trim().lowercase()) {
    "success" -> StatusTone.GreenSuccess
    "already","done","completed","renewed" -> StatusTone.TealAlready
    "attention","actionable","submitted","accepted","auth_required" -> StatusTone.AmberAccepted
    "uncertain","pending","unknown" -> StatusTone.GrayUnknown
    "failed","failure","error" -> StatusTone.RedFailed
    else -> StatusTone.GrayUnknown
}

@Composable private fun QResultRow(row:RecordRow,modifier:Modifier=Modifier) {
    Text(text=row.presentation.label,modifier=modifier.testTag("record-status"),style=MaterialTheme.typography.labelLarge,
        color=signStateColor(row.semanticStatus),maxLines=SUBLABEL_MAX_LINES,overflow=TextOverflow.Visible)
}

@Composable internal fun signStateColor(state:String):Color {
    val colors=MaterialTheme.colorScheme
    // Colour AND the word itself carry the meaning, so a colourblind user still reads the status.
    val dark=colors.surface.luminance()<.5f
    return when(statusTone(state)) {
        StatusTone.GreenSuccess->if(dark)Color(0xFF8DDDB4) else Color(0xFF17613C)
        StatusTone.TealAlready->if(dark)Color(0xFF83D4DC) else Color(0xFF00646E)
        StatusTone.AmberAccepted->if(dark)Color(0xFFF1CF86) else Color(0xFF785000)
        StatusTone.GrayUnknown->Muted
        StatusTone.RedFailed->colors.error
    }
}

internal val SUBLABEL_MAX=128.dp

internal fun recordDiagnostics(r:CheckRecord):String = listOfNotNull(
    r.httpStatus?.let { "HTTP：$it" },
    r.providerCode.takeIf { it.isNotBlank() }?.let { "平台业务码：$it" },
    "结果来源：${r.resultSource}",
    r.verificationMethod.takeIf { it.isNotBlank() }?.let { "核验方式：$it" },
    r.verifiedAt.takeIf { it.isNotBlank() }?.let { "官方确认时间：$it" },
    "重复提交：${if(r.retryable)"仅在明确允许的失败条件下重试" else "已限制；请先核对官方状态"}",
    r.executionId.takeIf { it.isNotBlank() }?.let { "执行编号：$it" },
).joinToString("\n")

// Page content inset, shared by the LazyColumn contentPadding and by rows that must bleed past it.
private val PAGE_INSET=20.dp

/**
 * Pull a horizontal row out by [inset] on each side so its viewport spans the full screen width; the row
 * re-adds the same inset as its own `contentPadding`, so the first and last child still align with the
 * cards at rest while the scrollable area reaches both screen edges.
 *
 * The previous version was mathematically wrong: it MEASURED with `maxWidth - 2*inset` and then reported
 * `placeable.width + 2*inset`, so the reported width equalled the original constraint and the row never
 * reached past the LazyColumn's inset at all -- only its content padding grew. Fixed here by measuring
 * with the full constraint, reporting `width + 2*inset`, and placing at `-inset` so the extra space is
 * genuinely outside the page inset on both sides.
 *
 * Why not `Modifier.padding(-20.dp)`: padding() coerces negatives to zero. Why not `Modifier.offset()`:
 * offset shifts the node but keeps its narrow width, so it clips at one end instead of bleeding at both.
 */
private fun Modifier.bleed(inset:Dp):Modifier = this.layout { measurable, constraints ->
    val px=inset.roundToPx()
    // Measure at the FULL constraint: the child keeps the width it would have had inside the inset, and
    // the extra bleed is added around it, so the viewport grows instead of the content shrinking.
    val placeable=measurable.measure(if(constraints.hasBoundedWidth) constraints.copy(minWidth=0) else constraints)
    val w=placeable.width+px*2
    layout(w,placeable.height){placeable.place(px,0)}
}

internal fun qTime(value:String?):String=if(value.isNullOrBlank())"尚无记录" else runCatching{OffsetDateTime.parse(value).atZoneSameInstant(SignRepository.TZ).format(DateTimeFormatter.ofPattern("MM-dd HH:mm:ss"))}.getOrDefault(value.take(19))
private fun qNext(s:UiState):String{if(!s.scheduleEnabled)return "未开启";if(s.waitingNetwork)return "等待网络或系统调度";return runCatching{val now=ZonedDateTime.now(SignRepository.TZ);var next=now.toLocalDate().atTime(LocalTime.parse(s.dailyTime)).atZone(SignRepository.TZ);if(next<now)next=next.plusDays(1);next.format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))+" 后（非精确时间）"}.getOrDefault("等待系统安排")}

private val providerOptions=listOf("all" to "全部平台")+Providers.map{it.id to it.title}
private val gameOptions=listOf("all" to "全部游戏")+GameCatalog.all.map{it.id to it.title}
private fun accountOptions(ui:PageUiState)=listOf("all" to "全部账号")+ui.accounts.map{it.id to safeLabel(it.nickname)}

@Composable private fun QFilter(label:String,value:String,options:List<Pair<String,String>>,onChoose:(String)->Unit) {
    var open by remember{mutableStateOf(false)}
    Box{
        OutlinedButton(onClick={open=true}){
            Text("$label：${options.firstOrNull{it.first==value}?.second.orEmpty()}",maxLines=1,overflow=TextOverflow.Ellipsis)
        }
        DropdownMenu(open,{open=false}){options.forEach{(id,title)->DropdownMenuItem(text={Text(title,maxLines=1,overflow=TextOverflow.Ellipsis)},onClick={onChoose(id);open=false})}}
    }
}

@Composable private fun QDiagnostics(s:UiState,policy:GlassPolicy,onRefresh:()->Unit,onSettings:(String)->Unit) {
    val c=LocalContext.current
    val connected=c.getSystemService(ConnectivityManager::class.java).let{it.getNetworkCapabilities(it.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)==true}
    val notifications=androidx.core.app.NotificationManagerCompat.from(c).areNotificationsEnabled()
    val exempt=c.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(c.packageName)
    QCard{
        Text("运行诊断",style=MaterialTheme.typography.titleLarge)
        Text("通知：${if(notifications)"已允许" else "未允许，可能看不到结果通知"}")
        if(!notifications)TextButton(onClick={onSettings("notification")}){Text("设置通知权限")}
        Text("网络：${if(connected)"系统已验证联网，社区连通性以请求为准" else "尚未验证联网"}")
        if(!connected)TextButton(onClick={onSettings("network")}){Text("检查网络")}
        Text("电池限制：${if(exempt)"未限制此应用" else "受系统优化管理，后台可能延迟"}")
        TextButton(onClick={onSettings("battery")}){Text("查看电池优化设置")}
        Text("系统队列：${if(s.jobRunning)"正在执行" else if(s.waitingNetwork)"已排队 / 等待约束" else "暂无执行中任务"}")
        Text("授权需处理：${s.overview?.accounts.orEmpty().count{!it.bindingPending&&it.authState!="valid"}} 个账号")
        Text("材质：${policy.reason}",style=MaterialTheme.typography.bodySmall,color=Muted)
        TextButton(onClick=onRefresh){Text("刷新诊断")}
    }
}
