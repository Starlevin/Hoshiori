package com.glasssign.app.ui.background

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Owns the page background: the stored choice, the private image copy and at most one decoded bitmap.
 *
 * Every disk and decode call happens on [ioDispatcher]; the composable only ever reads the decoded value.
 *
 * Bitmap lifetime: this class never calls `Bitmap.recycle()`. A bitmap handed to Compose may still be drawn
 * on the next frame, so recycling it from a coroutine (or from `onDispose`) races with the render thread and
 * shows as "Canvas: trying to use a recycled bitmap". The reference is simply dropped and the pixels are
 * collected by the GC; because at most one wallpaper bitmap is kept alive at a time, the peak stays bounded.
 */
class BackgroundRepository(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val appContext = context.applicationContext
    private val store = BackgroundFileStore.inFilesDir(appContext.filesDir)
    private val decoder = BackgroundDecoder(store)
    private val stored = BackgroundPreferencesStore(appContext)
    private val mutex = Mutex()

    /**
     * Bumped whenever the stored image bytes change (import or clear).
     *
     * The preference value alone cannot tell "the same photo" from "a different photo": re-picking while
     * `source` is already `custom` produces an equal [BackgroundPreferences], so a composable keyed on it
     * would keep drawing the old bitmap. The page reads this as a decode key instead of comparing
     * timestamps per frame. Only changes on real writes, never during a slider drag.
     */
    private val _revision = MutableStateFlow(0L)

    /** Content revision of the stored image; grows on every successful import or clear. */
    val revision: StateFlow<Long> = _revision.asStateFlow()

    private fun bumpRevision() {
        _revision.value = _revision.value + 1
    }

    @Volatile private var loaded: BitmapImage? = null
    private var loadedKey: String? = null

    fun readPreferences(): BackgroundPreferences = stored.read()

    /**
     * Cold start: clean up an interrupted write, and demote a stored "custom" whose file no longer decodes
     * so the page shows the bundled gradient instead of failing on every launch.
     */
    suspend fun recover(): BackgroundPreferences = withContext(ioDispatcher) {
        val current = stored.read()
        if (current.source != BackgroundSource.custom) return@withContext current
        if (store.recover()) return@withContext current
        stored.save(current.copy(source = BackgroundSource.none))
        current.copy(source = BackgroundSource.none)
    }

    /** Decoded page image for [preferences] at [targetWidth] x [targetHeight], or null for "no background". */
    suspend fun load(preferences: BackgroundPreferences, targetWidth: Int, targetHeight: Int): BitmapImage? =
        mutex.withLock {
            withContext(ioDispatcher) {
                if (preferences.source != BackgroundSource.custom) {
                    dropLoaded()
                    return@withContext null
                }
                val file = store.customFile
                if (!file.isFile) throw BackgroundError("背景图片已不存在")
                val key = "${file.length()}:${file.lastModified()}:${targetWidth}x$targetHeight"
                loaded?.takeIf { loadedKey == key }?.let { return@withContext it }
                val image = decoder.decode(file, targetWidth, targetHeight) as BitmapImage
                dropLoaded()
                loaded = image
                loadedKey = key
                image
            }
        }

    /** Replaces the stored photo with the bytes behind [uri]; returns the preferences to persist. */
    suspend fun importImage(uri: Uri, preferences: BackgroundPreferences): BackgroundPreferences = withContext(ioDispatcher) {
        mutex.withLock {
            // Throws before touching `page.img` when the pick is not usable, so the old wallpaper survives.
            decoder.importPickerImage(appContext, uri)
            dropLoaded()
            // New bytes on disk: tell every reader to re-decode even though `source` is still `custom`.
            bumpRevision()
            val next = preferences.copy(source = BackgroundSource.custom).sanitized()
            stored.save(next)
            next
        }
    }

    /** Returns to "no background" and deletes the private copy of the selected photo. */
    suspend fun clear(preferences: BackgroundPreferences): BackgroundPreferences = withContext(ioDispatcher) {
        mutex.withLock {
            dropLoaded()
            store.delete()
            bumpRevision()
            val next = preferences.copy(source = BackgroundSource.none).sanitized()
            stored.save(next)
            next
        }
    }

    /** Commits mask/blur/source only; no pixels are written, so a slider drag costs one small file write. */
    suspend fun savePreferences(preferences: BackgroundPreferences) = withContext(ioDispatcher) {
        stored.save(preferences.sanitized())
    }

    /** Called from `onDispose`: forgets the cached bitmap. The pixels are left to the GC, never recycled here. */
    fun release() = dropLoaded()

    /** Drops the cached reference. Safe from any thread; the bitmap itself is left for the GC. */
    private fun dropLoaded() {
        loaded = null
        loadedKey = null
    }
}
