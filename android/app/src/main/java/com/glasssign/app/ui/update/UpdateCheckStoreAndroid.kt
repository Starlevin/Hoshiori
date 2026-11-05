package com.glasssign.app.ui.update

import android.content.Context

/** 落盘的自动检查状态。独立偏好文件，不动任何既有用户设置。 */
class SharedPrefsUpdateCheckStore(context: Context) : UpdateCheckStore {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun read(): UpdateCheckMemory = UpdateCheckMemory(
        lastCheckMs = prefs.getLong(KEY_LAST_CHECK_MS, 0L),
        lastAttemptFailed = prefs.getBoolean(KEY_LAST_ATTEMPT_FAILED, false),
        etag = prefs.getString(KEY_ETAG, null),
        cachedBody = prefs.getString(KEY_CACHED_BODY, null),
        cachedEndpoint = prefs.getString(KEY_CACHED_ENDPOINT, null),
        cachedVersion = prefs.getString(KEY_CACHED_VERSION, null),
        noticedVersion = prefs.getString(KEY_NOTICED_VERSION, null),
    )

    override fun write(memory: UpdateCheckMemory) {
        prefs.edit()
            .putLong(KEY_LAST_CHECK_MS, memory.lastCheckMs)
            .putBoolean(KEY_LAST_ATTEMPT_FAILED, memory.lastAttemptFailed)
            .putString(KEY_ETAG, memory.etag)
            .putString(KEY_CACHED_BODY, memory.cachedBody)
            .putString(KEY_CACHED_ENDPOINT, memory.cachedEndpoint)
            .putString(KEY_CACHED_VERSION, memory.cachedVersion)
            .putString(KEY_NOTICED_VERSION, memory.noticedVersion)
            .apply()
    }

    private companion object {
        const val PREFS = "updates_v1"
        const val KEY_LAST_CHECK_MS = "last_check_ms"
        const val KEY_LAST_ATTEMPT_FAILED = "last_attempt_failed"
        const val KEY_ETAG = "last_etag"
        const val KEY_CACHED_BODY = "cached_body"
        const val KEY_CACHED_ENDPOINT = "cached_endpoint"
        const val KEY_CACHED_VERSION = "cached_version"
        const val KEY_NOTICED_VERSION = "noticed_version"
    }
}
