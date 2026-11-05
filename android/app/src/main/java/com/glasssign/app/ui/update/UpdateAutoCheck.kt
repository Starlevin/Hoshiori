package com.glasssign.app.ui.update

/**
 * 自动检查的持久化状态。
 *
 * [lastCheckMs] 是限频基准，[lastAttemptFailed] 决定冷却窗口：成功 24 小时，失败 10 分钟。
 * 失败标志必须落盘，否则应用重启后又会按 24 小时算，网络一断就整天不再检查。
 *
 * [etag] 与 [cachedBody] 一起构成条件请求的缓存：只有正文与 etag 都在，304 才能复现同一个结论。
 */
data class UpdateCheckMemory(
    val lastCheckMs: Long = 0L,
    val lastAttemptFailed: Boolean = false,
    val etag: String? = null,
    val cachedBody: String? = null,
    val cachedEndpoint: String? = null,
    val cachedVersion: String? = null,
    val noticedVersion: String? = null,
)

/** 存储抽象：正式实现落 SharedPreferences，测试用内存实现并可在两个实例间传递。 */
interface UpdateCheckStore {
    fun read(): UpdateCheckMemory
    fun write(memory: UpdateCheckMemory)
}

/** 单进程内存实现，主要给测试用。 */
class MapUpdateCheckStore(initial: UpdateCheckMemory = UpdateCheckMemory()) : UpdateCheckStore {
    private var memory = initial
    override fun read(): UpdateCheckMemory = memory
    override fun write(memory: UpdateCheckMemory) {
        this.memory = memory
    }
}

/** 这次自动检查该怎么办。 */
sealed class UpdateAutoCheckDecision {
    /** 该查，带上可用于条件请求的 etag（没有缓存时为 null）。 */
    data class Check(val etag: String?) : UpdateAutoCheckDecision()

    /** 还没到时间，[nextAllowedInMs] 之后才可以。 */
    data class Throttled(val nextAllowedInMs: Long) : UpdateAutoCheckDecision()

    /** 不自动检查（非发布构建）。 */
    data object Inactive : UpdateAutoCheckDecision()
}

/** 自动检查的限频规则。纯函数，无状态。 */
object UpdateAutoCheck {

    /** 成功后的间隔：24 小时。 */
    const val INTERVAL_MS = 24L * 60L * 60L * 1000L

    /** 失败后的冷却：10 分钟。 */
    const val FAILURE_COOLDOWN_MS = 10L * 60L * 1000L

    fun decide(
        channel: UpdateReleaseChannel?,
        memory: UpdateCheckMemory,
        nowMs: Long,
        intervalMs: Long = INTERVAL_MS,
    ): UpdateAutoCheckDecision {
        if (channel == null) return UpdateAutoCheckDecision.Inactive
        if (memory.lastCheckMs <= 0L) return UpdateAutoCheckDecision.Check(etag = null)
        val window = if (memory.lastAttemptFailed) minOf(FAILURE_COOLDOWN_MS, intervalMs) else intervalMs
        val elapsed = nowMs - memory.lastCheckMs
        if (elapsed < 0L) {
            // 系统时间被往回调过：不把"未来"当成"刚检查过"，也不立刻再查一次。
            return UpdateAutoCheckDecision.Throttled(nextAllowedInMs = window)
        }
        if (elapsed < window) {
            return UpdateAutoCheckDecision.Throttled(nextAllowedInMs = window - elapsed)
        }
        return UpdateAutoCheckDecision.Check(etag = memory.etag)
    }

    /** 这次要不要提示用户？只有"真的更新、并且之前没提示过这个版本"才提示。 */
    fun shouldNotify(check: UpdateCheck?, noticedVersion: String?): Boolean {
        val candidate = (check as? UpdateCheck.Available)?.candidate ?: return false
        return candidate.version.toString() != noticedVersion
    }

    /**
     * 记录一次尝试。成功时保存 etag 与正文（供下次 304 复用），失败时只前移时间戳并置失败标志，
     * 保留上一次可用的缓存，避免一次断网就把缓存清掉。
     */
    fun afterAttempt(
        previous: UpdateCheckMemory,
        nowMs: Long,
        succeeded: Boolean,
        etag: String? = null,
        body: String? = null,
        endpoint: String? = null,
        comparedWith: String? = null,
    ): UpdateCheckMemory = if (succeeded) {
        previous.copy(
            lastCheckMs = nowMs,
            lastAttemptFailed = false,
            etag = etag,
            cachedBody = body,
            cachedEndpoint = endpoint,
            cachedVersion = comparedWith,
        )
    } else {
        previous.copy(lastCheckMs = nowMs, lastAttemptFailed = true)
    }

    /** 用户已经看过这个版本了：以后同一个版本不再提示。 */
    fun markNoticed(previous: UpdateCheckMemory, version: String): UpdateCheckMemory =
        previous.copy(noticedVersion = version)
}
