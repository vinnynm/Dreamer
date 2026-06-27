package com.enigma.dreamer.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.core.graphics.toColorInt
import androidx.palette.graphics.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 9.2 — Standalone color extractor.
 *
 * Extracted from [MusicViewModel] (was: [dominantColor], [contrastColor],
 * [scheduleColorExtraction]). Now a plain class the ViewModel holds a
 * reference to, making it independently testable without a real [android.app.Application].
 *
 * Usage:
 *   val extractor = ColorExtractor(application)
 *   val (bg, fg) = extractor.extract(artUri)
 *
 * Both colors are returned as packed ARGB ints so callers don't need to
 * depend on Compose [android.graphics.Color] in the data layer.
 *
 * Thread-safety: [extract] is a suspend function that switches to
 * [kotlinx.coroutines.Dispatchers.IO] internally; it is safe to call from any coroutine.
 */
class ColorExtractor(private val app: Application) {

    data class Result(val dominantColor: Int, val accentTextColor: Int)

    /**
     * Derives a background color and a legible foreground color from [artUri].
     *
     * Returns [DEFAULT] if the URI is null, the bitmap cannot be decoded, or
     * the palette contains no usable swatch.
     */
    suspend fun extract(artUri: Uri?): Result = withContext(Dispatchers.IO) {
        val bitmap = withTimeoutOrNull(2_000L) {
            artUri?.let { uri ->
                runCatching {
                    app.contentResolver.openInputStream(uri)?.use { stream ->
                        // Two-pass decode: read bounds first, then decode at 1/4 scale
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeStream(stream, null, bounds)

                        // Re-open; can't reuse an exhausted InputStream
                        app.contentResolver.openInputStream(uri)?.use { stream2 ->
                            val opts = BitmapFactory.Options().apply {
                                inSampleSize = calculateInSampleSize(bounds, 128, 128)
                            }
                            BitmapFactory.decodeStream(stream2, null, opts)
                        }
                    }
                }.getOrNull()
            }
        }

        val dominant = if (bitmap != null) dominantColor(bitmap) else DEFAULT_BG
        Result(dominantColor = dominant, accentTextColor = contrastColor(dominant))
    }

    private fun calculateInSampleSize(
        options: BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        var inSampleSize = 1
        val (height, width) = options.outHeight to options.outWidth
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth  = width / 2
            while (halfHeight / inSampleSize >= reqHeight &&
                halfWidth  / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    // ── Palette-based dominant color (FIX B-9) ────────────────────────────────

    private fun dominantColor(bmp: Bitmap): Int {
        val palette = Palette.from(bmp).generate()
        val swatch  = palette.darkVibrantSwatch
            ?: palette.darkMutedSwatch
            ?: palette.vibrantSwatch
            ?: palette.mutedSwatch
            ?: return DEFAULT_BG

        // Darken slightly so the extracted color works as a translucent overlay
        // without washing out white text. Factor 0.70 keeps it visibly coloured.
        val r = (Color.red(swatch.rgb)   * 0.70f).toInt().coerceIn(0, 255)
        val g = (Color.green(swatch.rgb) * 0.70f).toInt().coerceIn(0, 255)
        val b = (Color.blue(swatch.rgb)  * 0.70f).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }

    // ── WCAG-relative-luminance contrast picker ───────────────────────────────

    private fun contrastColor(bgColor: Int): Int {
        fun lin(c: Double) =
            if (c <= 0.04045) c / 12.92
            else Math.pow((c + 0.055) / 1.055, 2.4)

        val lum = 0.2126 * lin(Color.red(bgColor)   / 255.0) +
                0.7152 * lin(Color.green(bgColor) / 255.0) +
                0.0722 * lin(Color.blue(bgColor)  / 255.0)

        return if (lum > 0.179) "#1A1A1A".toColorInt() else Color.WHITE
    }

    companion object {
        val DEFAULT_BG = "#0D0D0D".toColorInt()
        val DEFAULT: Result = Result(DEFAULT_BG, Color.WHITE)
    }
}