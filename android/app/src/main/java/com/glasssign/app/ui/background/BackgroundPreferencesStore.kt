package com.glasssign.app.ui.background

import android.content.Context
import android.content.SharedPreferences

/**
 * Persistence for [BackgroundPreferences] inside the existing appearance file.
 *
 * The three keys are new and every one of them is read with a default, so a preference file written by
 * 0.7.5 (no background keys at all) reads back as "no background" and nothing else in 外观与主题 changes.
 * The image bytes are never stored here, only the choice; see [BackgroundFileStore].
 */
class BackgroundPreferencesStore(context: Context) {
    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences("appearance_v1", Context.MODE_PRIVATE)

    fun read(): BackgroundPreferences = BackgroundPreferences.decode(
        source = preferences.getString(keySource, null),
        mask = preferences.getFloat(keyMask, -1f).takeIf { it >= 0f && !it.isNaN() },
        blur = preferences.getFloat(keyBlur, -1f).takeIf { it >= 0f && !it.isNaN() },
    )

    fun save(value: BackgroundPreferences) {
        val clean = value.sanitized()
        preferences.edit()
            .putString(keySource, clean.source.name)
            .putFloat(keyMask, clean.maskStrength)
            .putFloat(keyBlur, clean.blurDp)
            .apply()
    }

    companion object {
        const val keySource = "background_source"
        const val keyMask = "background_mask"
        const val keyBlur = "background_blur"
    }
}

/** The user stored "custom photo" but the private copy is missing/corrupt: fall back, keep the dialog honest. */
fun BackgroundPreferences.withoutMissingCustomImage(store: BackgroundFileStore): BackgroundPreferences =
    if (source == BackgroundSource.custom && !store.isValid()) copy(source = BackgroundSource.none) else this
