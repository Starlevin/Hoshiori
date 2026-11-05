package com.glasssign.app.ui

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Which plate the launcher icon is drawn on.
 *
 * Not part of [AppearanceSettings] on purpose: the value lives in its own preference object
 * (`launcher_icon_v1`), so "恢复默认外观" never touches it, the page theme and the launcher icon can be
 * switched independently, and a damaged appearance file cannot take the launcher entry with it.
 */
enum class LauncherIconMode(val key: String, val label: String) {
    FOLLOW("follow", "跟随应用主题"),
    DARK("dark", "始终深色"),
    LIGHT("light", "始终浅色"),
    ;

    companion object {
        /** Unknown or missing text falls back to [FOLLOW]; reading never throws. */
        fun fromKey(raw: String?): LauncherIconMode = entries.firstOrNull { it.key == raw } ?: FOLLOW
    }
}

/**
 * The launcher entry declared as an `activity-alias` in AndroidManifest.xml.
 *
 * [exportedByDefault] is the enabled state the manifest installs with, which is also the state the
 * fallback restores ([LauncherEntryPlan.fallback]).
 */
enum class LauncherEntry(
    val className: String,
    val iconResource: String,
    val exportedByDefault: Boolean,
) {
    SYSTEM("com.glasssign.app.LauncherSystem", "mipmap/ic_launcher", true),
    DARK("com.glasssign.app.LauncherDark", "mipmap/ic_launcher_dark", false),
    LIGHT("com.glasssign.app.LauncherLight", "mipmap/ic_launcher_light", false),
    ;

    companion object {
        val all: List<LauncherEntry> get() = entries.toList()
    }
}

/**
 * Pure mapping from the stored choice to the alias that must be enabled.
 *
 * Context-free so it can be unit-tested and so exactly one place decides what "follow / dark / light"
 * means for each page theme mode.
 *
 * FOLLOW tracks the *page* theme: a forced page theme (light/dark) picks the matching plate, while
 * `system` defers to the OS and therefore keeps the uiMode-driven default alias.
 */
object LauncherIconPlan {
    fun targetOf(mode: LauncherIconMode, pageMode: String): LauncherEntry = when (mode) {
        LauncherIconMode.DARK -> LauncherEntry.DARK
        LauncherIconMode.LIGHT -> LauncherEntry.LIGHT
        LauncherIconMode.FOLLOW -> when (pageMode) {
            "dark" -> LauncherEntry.DARK
            "light" -> LauncherEntry.LIGHT
            else -> LauncherEntry.SYSTEM
        }
    }

    /** The page theme that is actually on screen, i.e. what FOLLOW has to agree with. */
    fun effectiveDark(mode: LauncherIconMode, pageMode: String, systemDark: Boolean): Boolean = when (mode) {
        LauncherIconMode.DARK -> true
        LauncherIconMode.LIGHT -> false
        LauncherIconMode.FOLLOW -> when (pageMode) {
            "dark" -> true
            "light" -> false
            else -> systemDark
        }
    }

    fun of(
        mode: LauncherIconMode,
        pageMode: String,
        systemDark: Boolean,
        current: LauncherEntry?,
    ): LauncherEntryPlan = LauncherEntryPlan(
        mode = mode,
        pageMode = pageMode,
        systemDark = systemDark,
        current = current,
        target = targetOf(mode, pageMode),
    )
}

/**
 * One resolved switch.
 *
 * [enable] puts the target first: the legacy path enables it before disabling the others, so an
 * interruption can leave two entries but never zero.
 */
data class LauncherEntryPlan(
    val mode: LauncherIconMode,
    val pageMode: String,
    val systemDark: Boolean,
    val current: LauncherEntry?,
    val target: LauncherEntry,
) {
    val enable: List<LauncherEntry>
        get() = listOf(target)

    val disable: List<LauncherEntry> get() = LauncherEntry.all.filter { it != target }

    /** Enabled state after a successful switch: only [target]. */
    val finalStates: Map<LauncherEntry, Boolean>
        get() = LauncherEntry.all.associateWith { it == target }

    /** Nothing to write: the chosen alias is the one already on the device. */
    val isSettled: Boolean get() = current == target

    /** Post-upgrade / last-resort state: exactly what the manifest installed with. */
    val fallback: Map<LauncherEntry, Boolean>
        get() = LauncherEntry.all.associateWith { it.exportedByDefault }
}

/**
 * Outcome of one [LauncherIconManager.apply].
 *
 * [applied] = false means the write failed; the aliases were then restored to the manifest default and
 * the caller must not cache the choice.
 */
data class LauncherIconOutcome(
    val applied: Boolean,
    val target: LauncherEntry,
    val alreadySettled: Boolean,
    val failure: String? = null,
)

/** Raw component-enabled reads/writes, injected so the switch logic is testable without a device. */
interface LauncherComponentSwitcher {
    /** Enabled state of [entry], or `null` when the component is not installed at all. */
    fun enabled(entry: LauncherEntry): Boolean?

    /** Applies [states]; with [atomic] the whole set is one API 33+ `setComponentEnabledSettings` call. */
    fun setEnabled(states: Map<LauncherEntry, Boolean>, atomic: Boolean)

    /** Puts every alias back to its manifest default. */
    fun restoreDefault()
}

/** Production implementation over [PackageManager]. */
class PackageManagerSwitcher(private val context: Context) : LauncherComponentSwitcher {
    private val pm: PackageManager get() = context.packageManager
    private val self: String get() = context.packageName
    private val flags: Int = PackageManager.DONT_KILL_APP

    override fun enabled(entry: LauncherEntry): Boolean? {
        if (entry.className.substringBeforeLast('.') != self) return null
        val state = runCatching {
            pm.getComponentEnabledSetting(ComponentName(self, entry.className))
        }.getOrNull() ?: return null
        return when (state) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> false
            // COMPONENT_ENABLED_STATE_DEFAULT means "as declared", which is the only truthful answer.
            else -> entry.exportedByDefault
        }
    }

    override fun setEnabled(states: Map<LauncherEntry, Boolean>, atomic: Boolean) {
        if (atomic&&Build.VERSION.SDK_INT>=33) {
            pm.setComponentEnabledSettings(
                states.map { (entry, on) ->
                    PackageManager.ComponentEnabledSetting(
                        ComponentName(self, entry.className),
                        if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                        else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        flags,
                    )
                },
            )
            return
        }
        // API 26..32: chosen alias first, then the others; every call carries DONT_KILL_APP.
        states.entries.sortedByDescending { it.value }.forEach { (entry, on) ->
            pm.setComponentEnabledSetting(
                ComponentName(self, entry.className),
                if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                flags,
            )
        }
    }

    override fun restoreDefault() {
        runCatching { setEnabled(LauncherEntry.all.associateWith { it.exportedByDefault }, atomic = false) }
    }
}

/**
 * Keeps the single enabled launcher alias in sync with [LauncherIconStore].
 *
 * [apply] caches the last committed selection, so a recomposition or a theme repaint that did not move
 * the choice never reaches the binder. No failure escapes: a launcher preference must not crash the app,
 * and on failure the aliases are restored to the manifest default instead of being left half-switched.
 */
class LauncherIconManager internal constructor(
    private val switcher: LauncherComponentSwitcher,
    private val atomicSwitch: Boolean,
) {
    private data class Committed(val mode: LauncherIconMode, val pageMode: String, val systemDark: Boolean)

    private var committed: Committed? = null

    /** The alias that is enabled on the device right now, or `null` if none could be read. */
    fun installedEntry(): LauncherEntry? = LauncherEntry.all.firstOrNull { switcher.enabled(it) == true }

    /** Applies [mode]; returns `null` when the identical state was already applied by this process. */
    fun apply(mode: LauncherIconMode, pageMode: String, systemDark: Boolean): LauncherIconOutcome? {
        val key = Committed(mode, pageMode, systemDark)
        if (committed == key) return null
        val plan = LauncherIconPlan.of(mode, pageMode, systemDark, installedEntry())
        if (plan.isSettled&&LauncherEntry.all.count{switcher.enabled(it)==true}==1) {
            committed = key
            return LauncherIconOutcome(applied = true, target = plan.target, alreadySettled = true)
        }
        val result = runCatching { switcher.setEnabled(plan.finalStates, atomic = atomicSwitch) }
        if (result.isFailure) {
            runCatching { switcher.restoreDefault() }
            return LauncherIconOutcome(
                applied = false,
                target = plan.target,
                alreadySettled = false,
                failure = result.exceptionOrNull()?.message ?: result.exceptionOrNull()?.javaClass?.simpleName,
            )
        }
        committed = key
        return LauncherIconOutcome(applied = true, target = plan.target, alreadySettled = false)
    }

    /** Puts the aliases back to the manifest default (SYSTEM on, forced plates off). Never throws. */
    fun restoreDefault() {
        committed = null
        runCatching { switcher.restoreDefault() }
    }

    /** Drops the cache so the next [apply] re-reads the device, e.g. after the preference changed. */
    fun invalidate() {
        committed = null
    }
}

/**
 * Hardware-version gate for the one-call switch.
 *
 * API 33+ has `setComponentEnabledSettings` (atomic); API 26..32 must enable the target first and then
 * disable the others with `DONT_KILL_APP`.
 */
internal fun atomicAliasSwitch(sdkInt: Int): Boolean = sdkInt >= 33

/** Launcher choice store; a file of its own, independent of `appearance_v1`. */
class LauncherIconStore(val preferences: SharedPreferences) {
    fun read(): LauncherIconMode = LauncherIconMode.fromKey(runCatching{preferences.getString(KEY, null)}.getOrNull())

    fun save(mode: LauncherIconMode):Boolean = preferences.edit().putString(KEY,mode.key).commit()

    fun register(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        preferences.registerOnSharedPreferenceChangeListener(listener)

    fun unregister(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        preferences.unregisterOnSharedPreferenceChangeListener(listener)

    companion object {
        const val FILE = "launcher_icon_v1"
        const val KEY = "launcher_icon_mode"

        fun open(context: Context): LauncherIconStore =
            LauncherIconStore(context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE))
    }
}

/**
 * Composition wiring for the icon page.
 *
 * [LocalLauncherIconMode] carries the committed choice so the settings page and [HoshioriBrandMark]
 * follow it; [LocalLauncherIconController] is null outside [GlassSignTheme] (previews, tests) and every
 * consumer treats null as "not persisted here".
 */
val LocalLauncherIconMode = staticCompositionLocalOf { LauncherIconMode.FOLLOW }
internal val LocalLauncherIconController = staticCompositionLocalOf<LauncherIconController?> { null }

class LauncherIconController internal constructor(
    private val store: LauncherIconStore,
    private val manager: LauncherIconManager?,
) {
    fun commit(mode: LauncherIconMode) {
        if(store.save(mode))manager?.invalidate()
    }
}

/** Builds the [ComponentName] for [entry]; kept for call sites that need one explicitly. */
internal fun launcherComponent(applicationId: String, entry: LauncherEntry) =
    ComponentName(applicationId, entry.className)
