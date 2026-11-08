package com.glasssign.app.local

import android.content.Context
import com.glasssign.app.data.RoleChoice
import org.json.JSONObject

/**
 * 领签作为「星栞可绑定平台」的离线模拟适配器（2026-11-08 产品决策）。
 *
 * 边界（必须一直成立）：
 *  - 星栞不读取领签的账号、Token、数据库，也不跨进程调用它的内部逻辑；
 *  - 本适配器不发起任何网络请求，不调用任何官方接口；
 *  - 结果只写一条本机记录，状态为 success 但来源固定为 [SOURCE]，消息明确写「本地模拟」，
 *    绝不冒充官方已签到；
 *  - 领签未安装或未通过验签时，签到返回 needs_action 并提示去「设置 → 插件」安装。
 */
object LingqianPlugin {
    const val ID = "lingqian"
    const val GAME_ID = "lingqian_tasks"
    const val PACKAGE_NAME = "com.qinghan.aicheckin"

    /** 记录来源标记：与官方/已接受/本地缓存区分开，读起来就知道这是模拟。 */
    const val SOURCE = "local_simulation"

    val manifest = ProviderManifest(
        id = ID,
        title = "领签 · 本地模拟",
        version = "1",
        minHostVersion = "0.8.0",
        capabilities = setOf(ProviderCapability.ROLE_DISCOVERY, ProviderCapability.SIGN),
        permissionSummary = "不读取任何第三方账号或令牌；不发起网络请求；只在本机写入一条明确标注「本地模拟」的签到记录",
        description = "插件平台：需先在「设置 → 插件」安装并验签领签。签到现在星栞本机执行模拟，不代表官方已签到。",
        games = listOf(GAME_ID),
    )

    /** 领签是否已安装（只查包名，不读它的任何数据）。 */
    fun installed(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(PACKAGE_NAME, 0)
        true
    }.getOrDefault(false)
}

class LingqianAdapter(
    /** 领签是否已安装并可用。未就绪时拒绝模拟签到，提示先去插件页安装。 */
    private val ready: () -> Boolean = { true },
    private val host: () -> String = { SignRepository.now() },
) : ProviderAdapter {

    override fun targets(c: JSONObject, persist: () -> Unit): List<SignTarget> =
        listOf(SignTarget("task|daily", "领签 · 每日任务"))

    override fun checkin(c: JSONObject, t: SignTarget, persist: () -> Unit): SignResult {
        if (!runCatching { ready() }.getOrDefault(false)) {
            return SignResult(
                status = "needs_action",
                message = "领签未安装或未通过验签：请先在「设置 → 插件」下载并安装领签，再回来签到",
                resultSource = LingqianPlugin.SOURCE,
                verificationMethod = VerifyMethod.NOT_SUPPORTED,
                // 未安装时没有发生任何提交，装好插件后再点一次是安全的，因此允许重试。
                retryable = true,
            )
        }
        return SignResult(
            status = "success",
            message = "本地模拟签到完成（离线执行，未调用官方接口，不代表官方已签到）",
            resultSource = LingqianPlugin.SOURCE,
            verificationMethod = VerifyMethod.NOT_SUPPORTED,
            verifiedAt = "",
        )
    }

    /** 只读状态查询同样只是本地模拟，不声称官方结论。 */
    override fun queryStatus(c: JSONObject, t: SignTarget, persist: () -> Unit): SignResult =
        SignResult(
            status = "uncertain",
            message = "本机只能确认本地模拟记录；请以领签自身的记录为准",
            resultSource = LingqianPlugin.SOURCE,
            verificationMethod = VerifyMethod.NOT_SUPPORTED,
        )
}

/** 供绑定流程使用：领签只有一项任务，无需角色选择。 */
fun lingqianRoles(): List<RoleChoice> =
    listOf(RoleChoice("task|daily", LingqianPlugin.GAME_ID, "领签 · 每日任务"))
