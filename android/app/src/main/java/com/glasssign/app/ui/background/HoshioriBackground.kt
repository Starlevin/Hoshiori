package com.glasssign.app.ui.background

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.glasssign.app.R
import com.glasssign.app.ui.LocalBackgroundPreferences
import com.glasssign.app.ui.LocalBackgroundRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

/**
 * Bundled wallpaper hook: the app owner supplies the original artwork as a drawable and sets the id once
 * (default 0 = not shipped yet), so this module never hard-codes a resource that does not exist.
 */
object BundledBackgrounds {
    /** Original artwork shipped in `res/drawable-nodpi` by the app owner. */
    var starfieldRes: Int = R.drawable.hoshiori_starfield_v076

    /**
     * Reads the bundled copy at a bounded size, always off the main thread.
     *
     * Bounds come from a decode-free `inJustDecodeBounds` pass, then the planner picks the sample size, so a
     * later high-resolution artwork is sampled down instead of being fully inflated first. The resized copy
     * is returned; the intermediate is released immediately (GDX has no reference to it).
     */
    internal suspend fun load(context: Context, width: Int, height: Int): Bitmap? = withContext(Dispatchers.IO) {
        if (starfieldRes == 0) return@withContext null
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeResource(context.resources, starfieldRes, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
            val plan = planDecode(ImageInfo(bounds.outWidth, bounds.outHeight), 0, width, height)
                .getOrNull() ?: return@runCatching null
            val options = BitmapFactory.Options().apply {
                inSampleSize = plan.inSampleSize
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val sampled = BitmapFactory.decodeResource(context.resources, starfieldRes, options)
                ?: return@runCatching null
            if (sampled.width == plan.scaledWidth && sampled.height == plan.scaledHeight) {
                sampled
            } else {
                Bitmap.createScaledBitmap(sampled, plan.scaledWidth, plan.scaledHeight, true)
            }
        }.getOrNull()
    }
}

/**
 * Page background for HoshioriScreen: replaces the `PageGradient` brush line, nothing else changes.
 *
 * [modifier] is applied to the outermost node (pass `Modifier.fillMaxSize()`, matching the old line), and
 * everything inside is a plain [Box], so the dock's backdrop capture samples this wallpaper — it is painted
 * content in the same layer tree, not a window background. Only this layer is ever blurred or masked; text
 * and cards keep the theme's contrast.
 *
 * Decode is keyed on the source, the stored file stamp and the measured pixel size. Mask/blur never re-enter
 * the effect, so dragging a slider recomposes the draw lambda without touching disk or the decoder, and a
 * recompose without those keys reuses the same bitmap instead of decoding again. Custom (picked) and bundled
 * (starfield) images go through the same bounded planner.
 */
@Composable
fun HoshioriBackground(modifier: Modifier = Modifier) {
    val repository = LocalBackgroundRepository.current
    val preferences = com.glasssign.app.ui.LocalBackgroundPreview.current ?: LocalBackgroundPreferences.current
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val density = context.resources.displayMetrics.density
    var measured by remember { mutableStateOf(0 to 0) }
    var loaded by remember { mutableStateOf<Bitmap?>(null) }

    val customFile = remember(preferences.source) {
        if (preferences.source == BackgroundSource.custom) BackgroundFileStore.customPath(context.filesDir) else null
    }
    // Content revision: bumped by the repository on every import/clear. It is the only key that changes when
    // the user re-picks while `source` is already `custom` (the preferences value stays equal), so without it
    // the page would keep the previous bitmap. Read as state, never recomputed per frame.
    val fallbackRevision = remember { MutableStateFlow(0L) }
    val revisionState = (repository?.revision ?: fallbackRevision).collectAsState()
    val revision = revisionState.value
    // Cheap fingerprint of the stored copy; recomputed when the file or the revision changes, never per frame.
    val stamp = remember(customFile, revision) { customFile?.let { "${it.length()}:${it.lastModified()}" } }
    val targetWidth = if (measured.first > 0) measured.first else ((configuration.screenWidthDp.takeIf { it > 0 } ?: 400) * density).toInt()
    val targetHeight = if (measured.second > 0) measured.second else ((configuration.screenHeightDp.takeIf { it > 0 } ?: 800) * density).toInt()

    LaunchedEffect(repository, preferences.source, revision, stamp, targetWidth, targetHeight) {
        loaded = null
        when (preferences.source) {
            BackgroundSource.none -> Unit
            BackgroundSource.custom -> {
                if (repository == null || customFile == null) return@LaunchedEffect
                runCatching { repository.load(preferences, targetWidth, targetHeight) }.onSuccess { loaded = it?.bitmap }
            }
            else -> loaded = BundledBackgrounds.load(context, targetWidth, targetHeight)
        }
    }

    val image = loaded
    val mask = preferences.maskStrength.coerceIn(BackgroundPreferences.MaskRange)
    val blurAllowed = com.glasssign.app.ui.currentGlassPolicy().blur
    val blurDp = preferences.blurDp.coerceIn(BackgroundPreferences.BlurRange).takeIf { it > 0f && blurAllowed }
    val page = MaterialTheme.colorScheme.background

    Box(modifier.onSizeChanged { if (measured.first != it.width || measured.second != it.height) measured = it.width to it.height }) {
        if (image != null) {
            // One bitmap, scaled to the page; `blur` draws it into its own layer so the effect stops there.
            Image(
                bitmap = remember(image) { image.asImageBitmap() },
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().then(if (blurDp != null) Modifier.blur(blurDp.dp) else Modifier),
            )
            // Full-window mask, exactly like the old brush, so the wallpaper never runs unshaded under the
            // status/navigation bars.
            if (mask > 0f) Box(Modifier.fillMaxSize().background(page.copy(alpha = mask)))
        } else {
            // No wallpaper, still decoding, or a bundled id that is not shipped: identical to 0.7.5.
            Box(Modifier.fillMaxSize().background(pageGradientBrush()))
        }
    }
}

/**
 * True when any wallpaper is painted behind the page. Read by QCard (and anything else that wants to be more
 * translucent over a photo) so a card's `surface.copy(alpha = .94)` can drop to ~.74 and still clear AA over
 * the mask. Derived from [LocalBackgroundPreferences], which only changes when the choice is committed.
 */
val LocalBackgroundEnabled = staticCompositionLocalOf { false }

/** Same shape as the 0.7.5 `PageGradient`, so "no background" is pixel-compatible with the old page. */
@Composable
internal fun pageGradientBrush(): Brush {
    val colors = MaterialTheme.colorScheme
    val flat = com.glasssign.app.ui.LocalAppearance.current.colorStyle == "neutral" || colors.background == Color.Black
    return remember(colors.primaryContainer, colors.background, colors.secondaryContainer,flat) {
        // True OLED: a pure-black background means a flat black page, not a tinted
        // gradient. The scheme already makes the containers black in that case, so
        // this is a belt-and-braces flatten, not a second source of truth.
        if (flat) Brush.linearGradient(listOf(colors.background, colors.background))
        else Brush.linearGradient(
            listOf(colors.primaryContainer.copy(alpha = .38f), colors.background, colors.secondaryContainer.copy(alpha = .18f))
        )
    }
}

/**
 * Small preview of the stored pick for the settings dialog. Reads a bounded copy on the IO dispatcher; the
 * bitmap is never modified afterwards, so the caller just drops it and lets the GC reclaim it.
 */
internal suspend fun decodePreview(context: Context, maxSide: Int): Bitmap? = withContext(Dispatchers.IO) {
    val store = BackgroundFileStore.inFilesDir(context.filesDir)
    if (!store.isValid()) return@withContext null
    runCatching { BackgroundDecoder(store).decode(store.customFile, maxSide, maxSide).pixels as Bitmap }.getOrNull()
}

/** Copies a picker URI into the private store; returns the preferences to persist. Throws [BackgroundError]. */
internal suspend fun persistPickedImage(repository: BackgroundRepository, uri: Uri, current: BackgroundPreferences) =
    repository.importImage(uri, current)


