package com.glasssign.app.plugins

/**
 * The one preference this page owns.
 *
 * WHY AN INTERFACE: the JVM test suite has no Robolectric, so `SharedPreferences` cannot be
 * constructed there. Everything that decides *whether the switch means anything* lives in this
 * file and in [PluginUiState], and both are driven through [KeyValueStore] in the tests.
 *
 * WHY A SEPARATE FILE NAME (`plugins_v1`): the enable switch must be provably incapable of touching
 * the sign plan (`local_settings_v4`), the appearance settings (`appearance_v1`) or any account.
 * Different file, different keys, nothing shared — and no listener registered anywhere.
 */
interface KeyValueStore {
    fun readBoolean(key: String, fallback: Boolean): Boolean
    fun writeBoolean(key: String, value: Boolean)
    fun readString(key: String): String?
    fun writeString(key: String, value: String?)
}

/** In-memory store used by [PluginPrefs] default construction in tests and as a safe fallback. */
class MapKeyValueStore(initial: Map<String, String> = emptyMap()) : KeyValueStore {
    private val map = LinkedHashMap<String, String>(initial)
    override fun readBoolean(key: String, fallback: Boolean) = map[key]?.toBooleanStrictOrNull() ?: fallback
    override fun writeBoolean(key: String, value: Boolean) { map[key] = value.toString() }
    override fun readString(key: String) = map[key]
    override fun writeString(key: String, value: String?) { if (value == null) map.remove(key) else map[key] = value }
    /** Snapshot for assertions; tests read this instead of the private map. */
    fun snapshot(): Map<String, String> = LinkedHashMap(map)
}

/**
 * Reads and writes the enable switch, and refuses the request when the precondition is not met.
 *
 * [enable] is deliberately the only way to turn the switch ON: it re-checks "installed && runtime
 * signature matches" itself instead of trusting the caller's UI state, so a stale screen cannot
 * store an enabled flag for an app that is gone or re-signed.
 */
class PluginPrefs(private val store: KeyValueStore) {

    /** Stored position. `true` here does NOT mean the app is installed or running. */
    fun enabled(): Boolean = store.readBoolean(PluginSpec.KEY_ENABLED, false)

    /**
     * Turns the switch on, but only with a positive verification result for the installed package.
     * Returns the stored value afterwards so the caller renders the truth rather than its intent.
     */
    fun enable(installedCheck: VerifyReport?): Boolean {
        if (installedCheck !is VerifyReport.Passed) return enabled()
        store.writeBoolean(PluginSpec.KEY_ENABLED, true)
        return true
    }

    /** Turning the switch off is always allowed and never touches the other app. */
    fun disable() { store.writeBoolean(PluginSpec.KEY_ENABLED, false) }

    /**
     * Applies a switch request: enable goes through the verification gate, disable is unconditional.
     * Returns the value the switch must display.
     */
    fun setEnabled(requested: Boolean, installedCheck: VerifyReport?): Boolean =
        if (requested) enable(installedCheck) else { disable(); false }

    /**
     * Called on every resume: a switch left ON while the app was uninstalled or re-signed must not
     * keep claiming "已启用". Returns true when the stored value had to be corrected.
     */
    fun reconcile(installedCheck: VerifyReport?): Boolean {
        if (!enabled()) return false
        if (installedCheck is VerifyReport.Passed) return false
        store.writeBoolean(PluginSpec.KEY_ENABLED, false)
        return true
    }
}
