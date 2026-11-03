package com.glasssign.app.plugins

import android.content.Context
import android.content.SharedPreferences

/**
 * [KeyValueStore] over the page's own SharedPreferences file (`plugins_v1`).
 *
 * Nothing here touches the sign plan (`local_settings_v4`), the appearance settings
 * (`appearance_v1`), the encrypted account stores or the database. There is exactly one key, so the
 * blast radius of this feature is one boolean.
 *
 * `commit()` is used only for the switch, which is a rare, user-initiated write where "the toggle
 * must not lie after a process kill" is worth a few milliseconds on the calling (main) thread.
 * Compose's `animateFloatAsState` does not depend on it, so no frame is dropped in practice.
 */
class SharedPrefsKeyValueStore(context: Context) : KeyValueStore {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PluginSpec.PREFS_NAME, Context.MODE_PRIVATE)

    override fun readBoolean(key: String, fallback: Boolean): Boolean = prefs.getBoolean(key, fallback)
    override fun writeBoolean(key: String, value: Boolean) { prefs.edit().putBoolean(key, value).apply() }
    override fun readString(key: String): String? = prefs.getString(key, null)
    override fun writeString(key: String, value: String?) { prefs.edit().putString(key, value).apply() }
}
