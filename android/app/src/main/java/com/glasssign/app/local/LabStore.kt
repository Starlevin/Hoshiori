package com.glasssign.app.local

import android.content.Context
import android.content.SharedPreferences
import com.glasssign.app.BuildConfig

/*
 * Persisted lab flags.
 *
 * Only the experiments registered in [LabStore.all] exist. The user's CHOICE and the TEST EVIDENCE are stored
 * separately and are never derived from one another: enabling an experiment cannot produce "已验证".
 * Everything lives in `lab_flags_v1`; no account, role, record or guard is ever written here.
 */

enum class ValidationState(val label: String) {
    UNVERIFIED("未验证"),
    EMULATOR_ONLY("仅模拟器"),
    DEVICE_VERIFIED("真机验证"),
    FAILED("异常回退"),
    ;

    companion object {
        fun byName(name: String?): ValidationState = entries.firstOrNull { it.name == name } ?: UNVERIFIED
    }
}

internal enum class GlassQuality { FULL, LITE, OFF }

/**
 * Test evidence recorded alongside a validation state. Bound to a build so a later release cannot inherit it.
 * All three fields are required for a state to be shown as verified.
 */
data class ValidationRecord(
    val buildVersion: String = "",
    val device: String = "",
    val evidenceRef: String = "",
    val at: String = "",
) {
    /** True only when the record names a build, a device and an evidence reference. */
    val isComplete: Boolean get() = buildVersion.isNotBlank() && device.isNotBlank() && evidenceRef.isNotBlank()

    /** True when nothing at all is recorded (a freshly toggled flag). */
    fun isEmpty(): Boolean = buildVersion.isBlank() && device.isBlank() && evidenceRef.isBlank() && at.isBlank()
}

data class ExperimentalFlag(
    val id: String,
    val title: String,
    val description: String,
    val risk: String,
    val defaultEnabled: Boolean = false,
    val rollbackHint: String = "",
)

data class LabFlagState(
    val flag: ExperimentalFlag,
    val enabled: Boolean,
    /** Raw stored state. Use [effectiveValidation] for what the UI may show. */
    val validation: ValidationState,
    val record: ValidationRecord,
    val lastErrorAt: String,
    val lastErrorKind: String,
) {
    /**
     * What the UI may display. A verification recorded for ANOTHER build, or one without complete evidence, is NOT
     * carried over: it degrades to "未验证" so an upgrade cannot inherit a claim it never earned.
     */
    fun effectiveValidation(currentBuild: String): ValidationState = when {
        validation == ValidationState.UNVERIFIED || validation == ValidationState.FAILED -> validation
        record.buildVersion != currentBuild -> ValidationState.UNVERIFIED
        !record.isComplete -> ValidationState.UNVERIFIED
        else -> validation
    }
}

class LabStore(
    private val prefs: SharedPreferences?,
    private val buildVersionProvider: () -> String = { BuildConfig.VERSION_NAME },
) {
    constructor(context: Context) :
        this(runCatching { context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }.getOrNull())

    private fun key(id: String, suffix: String) = "lab_${id}_$suffix"

    val flags: List<ExperimentalFlag> get() = all

    fun flag(id: String): ExperimentalFlag? = all.firstOrNull { it.id == id }

    val currentBuild: String get() = runCatching { buildVersionProvider() }.getOrDefault("")

    // ---- choice ---------------------------------------------------------------------------------------------

    /** Unknown ids are always false; a read failure degrades to the flag default (off). */
    @Synchronized
    fun isEnabled(id: String): Boolean {
        val f = flag(id) ?: return false
        if (id == FLAG_WATER_DROP_GLASS) return glassQuality() == GlassQuality.FULL
        val store = prefs ?: return f.defaultEnabled
        return try {
            store.getBoolean(key(id, KEY_ENABLED), f.defaultEnabled)
        } catch (_: Exception) {
            f.defaultEnabled
        }
    }

    /** Writes the choice ONLY. Never writes validation evidence. */
    @Synchronized
    fun setEnabled(id: String, enabled: Boolean): Boolean {
        if (flag(id) == null) return false
        if (id == FLAG_WATER_DROP_GLASS) return setGlassQuality(if (enabled) GlassQuality.FULL else GlassQuality.LITE)
        val store = prefs ?: return false
        return runCatching { store.edit().putBoolean(key(id, KEY_ENABLED), enabled).commit() }.getOrDefault(false)
    }

    /**
     * P2 (2026-11-07): "unset" vs "explicitly turned off" are different facts.
     *
     * The old `glassQuality()` treats an absent quality key as LITE. That is correct for the tiny lab
     * store in isolation (and the JVM tests assert it), but the PRODUCT decision is that Liquid Glass
     * 2.0 is the default enhanced visual on a fresh install: a user who never opened the lab must get
     * FULL (subject to the device/a11y downgrade in DockGlassPolicy), while a user who explicitly chose
     * OFF/LITE must keep that choice forever.
     *
     * This function is the single production decision point. It NEVER writes a preference: an absent
     * key means "use the enhanced default", and any saved value (including OFF) is returned verbatim.
     * The legacy boolean key is still honoured for users who toggled the experiment before the quality
     * key existed. `rollbackAdvancedGlass` writes an explicit LITE, so a recorded crash stays downgraded.
     */
    @Synchronized
    internal fun requestedGlassQuality(): GlassQuality = runCatching {
        val saved = prefs?.getString(key(FLAG_WATER_DROP_GLASS, KEY_GLASS_QUALITY), null)
        if (saved != null) return GlassQuality.entries.firstOrNull { it.name == saved } ?: GlassQuality.FULL
        val legacy = prefs?.getBoolean(key(FLAG_WATER_DROP_GLASS, KEY_ENABLED), false) ?: false
        // Absent quality + absent legacy choice = fresh install / never touched the lab => enhanced default.
        // A legacy user who explicitly disabled the experiment (key present, false) keeps LITE.
        val legacyPresent = prefs?.contains(key(FLAG_WATER_DROP_GLASS, KEY_ENABLED)) == true
        if (!legacyPresent) GlassQuality.FULL else if (legacy) GlassQuality.FULL else GlassQuality.LITE
    }.getOrDefault(GlassQuality.FULL)

    @Synchronized
    internal fun glassQuality(): GlassQuality = runCatching {
        val saved = prefs?.getString(key(FLAG_WATER_DROP_GLASS, KEY_GLASS_QUALITY), null)
        if (saved == null) {
            val enabled = prefs?.getBoolean(key(FLAG_WATER_DROP_GLASS, KEY_ENABLED), false) ?: false
            if (enabled) GlassQuality.FULL else GlassQuality.LITE
        } else {
            GlassQuality.entries.firstOrNull { it.name == saved } ?: GlassQuality.LITE
        }
    }.getOrDefault(GlassQuality.LITE)

    @Synchronized
    internal fun setGlassQuality(quality: GlassQuality): Boolean {
        val store = prefs ?: return false
        return runCatching {
            store.edit()
                .putString(key(FLAG_WATER_DROP_GLASS, KEY_GLASS_QUALITY), quality.name)
                .commit()
        }.getOrDefault(false)
    }

    // ---- evidence -------------------------------------------------------------------------------------------

    @Synchronized
    fun validation(id: String): ValidationState =
        ValidationState.byName(runCatching { prefs?.getString(key(id, KEY_VALIDATION), null) }.getOrNull())

    @Synchronized
    fun record(id: String): ValidationRecord = runCatching {
        ValidationRecord(
            buildVersion = prefs?.getString(key(id, KEY_VER_BUILD), "").orEmpty(),
            device = prefs?.getString(key(id, KEY_VER_DEVICE), "").orEmpty(),
            evidenceRef = prefs?.getString(key(id, KEY_VER_EVIDENCE), "").orEmpty(),
            at = prefs?.getString(key(id, KEY_VERIFIED_AT), "").orEmpty(),
        )
    }.getOrDefault(ValidationRecord())

    /**
     * Records evidence. This is the ONLY path that can produce a verified state, and it requires the build, the
     * device and an evidence reference, so "已验证" is never a bare toggle.
     */
    @Synchronized
    fun recordValidation(id: String, state: ValidationState, record: ValidationRecord): Boolean {
        if (flag(id) == null) return false
        val store = prefs ?: return false
        return runCatching {
            store.edit()
                .putString(key(id, KEY_VALIDATION), state.name)
                .putString(key(id, KEY_VER_BUILD), record.buildVersion)
                .putString(key(id, KEY_VER_DEVICE), record.device)
                .putString(key(id, KEY_VER_EVIDENCE), record.evidenceRef)
                .putString(key(id, KEY_VERIFIED_AT), record.at)
                .commit()
        }.getOrDefault(false)
    }

    /** Records a bounded, credential-free failure kind. Arbitrary text collapses to "unspecified". */
    @Synchronized
    fun recordError(id: String, kind: String, at: String): Boolean = writeFailure(id, kind, at, disable = false)

    /**
     * Rolls the advanced glass experiment back to Lite and marks it failed, in one write. It touches no other
     * preference, so it is safe to call automatically after a recorded failure.
     */
    @Synchronized
    fun rollbackAdvancedGlass(at: String): Boolean = writeFailure(FLAG_WATER_DROP_GLASS, "rollback", at, disable = true)

    private fun writeFailure(id: String, kind: String, at: String, disable: Boolean): Boolean {
        if (flag(id) == null) return false
        val store = prefs ?: return false
        val safe = kind.takeIf { it.matches(Regex("[a-z_]{1,32}")) } ?: "unspecified"
        return runCatching {
            store.edit().apply {
                if (disable) {
                    putString(key(id, KEY_GLASS_QUALITY), GlassQuality.LITE.name)
                }
                putString(key(id, KEY_VALIDATION), ValidationState.FAILED.name)
                putString(key(id, KEY_ERROR_AT), at)
                putString(key(id, KEY_ERROR_KIND), safe)
            }.commit()
        }.getOrDefault(false)
    }

    /**
     * Recovery after a UI crash that the host actually RECORDED.
     *
     * [hasRecordedCrash] must come from a reliable signal (an uncaught Java exception captured before the process
     * died). A normal exit, a user swipe-away or a plain force-stop is not a crash and must not be treated as one:
     * pass false and nothing is written, so the experiment keeps its state.
     */
    @Synchronized
    fun recoverRecordedUiCrash(hasRecordedCrash: Boolean, at: String): Boolean {
        if (!hasRecordedCrash) return false
        return writeFailure(FLAG_WATER_DROP_GLASS, "crash", at, disable = true)
    }

    @Synchronized
    fun lastErrorAt(id: String): String = runCatching { prefs?.getString(key(id, KEY_ERROR_AT), "").orEmpty() }.getOrDefault("")
    @Synchronized
    fun lastErrorKind(id: String): String = runCatching { prefs?.getString(key(id, KEY_ERROR_KIND), "").orEmpty() }.getOrDefault("")

    @Synchronized
    fun state(id: String): LabFlagState? {
        val f = flag(id) ?: return null
        return LabFlagState(f, isEnabled(id), validation(id), record(id), lastErrorAt(id), lastErrorKind(id))
    }

    @Synchronized
    fun states(): List<LabFlagState> = all.mapNotNull { state(it.id) }

    /** Change notification for Compose/VM. Returns the listener for [stopListening]. */
    fun listen(onChange: (String) -> Unit): SharedPreferences.OnSharedPreferenceChangeListener? {
        val store = prefs ?: return null
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            val id = all.firstOrNull { key?.startsWith("lab_${it.id}_") == true }?.id
            if (id != null) onChange(id)
        }
        runCatching { store.registerOnSharedPreferenceChangeListener(listener) }
        return listener
    }

    fun stopListening(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
        if (listener == null) return
        val store = prefs ?: return
        runCatching { store.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    companion object {
        const val PREFS_NAME = "lab_flags_v1"

        /** The only experiment registered: the Haze-based dock. */
        const val FLAG_WATER_DROP_GLASS = "water_drop_glass"

        private const val KEY_ENABLED = "enabled"
        private const val KEY_GLASS_QUALITY = "quality_v2"
        private const val KEY_VALIDATION = "validation"
        private const val KEY_ERROR_AT = "error_at"
        private const val KEY_ERROR_KIND = "error_kind"
        private const val KEY_VERIFIED_AT = "verified_at"
        private const val KEY_VER_BUILD = "ver_build"
        private const val KEY_VER_DEVICE = "ver_device"
        private const val KEY_VER_EVIDENCE = "ver_evidence"

        val all: List<ExperimentalFlag> = listOf(
            ExperimentalFlag(
                id = FLAG_WATER_DROP_GLASS,
                title = "Liquid Glass 2.0",
                description = "Haze 完整模式支持动态折射和单颗水珠变形。",
                risk = "较新 GPU 表现更好；低端机可能掉帧。签到功能不受影响。",
                defaultEnabled = false,
                rollbackHint = "关闭后立即回到当前的 Dock 材质；不删除账号、授权、角色或签到记录",
            ),
        )
    }
}
