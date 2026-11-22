package com.maxime.photodream

import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.min

/**
 * Les vidéos du NAS, lues comme des photos : on les télécharge en entier dans le cache,
 * on en tire une image (affichée pendant le chargement et dans la mosaïque), puis elles
 * se lisent à l'écran jusqu'au bout ou jusqu'à la durée maximale choisie.
 */
object Videos {
    /** Formats que les TV Android lisent en général. */
    private val EXTENSIONS = setOf("mp4", "m4v", "mov", "3gp", "mkv", "webm")

    fun isVideo(path: String): Boolean = path.substringAfterLast('.', "").lowercase() in EXTENSIONS

    /** Une vidéo plus grosse que la moitié du cache n'est pas téléchargée. */
    fun maxBytes(cacheMb: Int): Long = cacheMb * 1024L * 1024L / 2

    /**
     * Écrit dans [out] une image de la vidéo (vers 1 s, à l'endroit, réduite au plein écran)
     * et renvoie la date et le lieu enregistrés par l'appareil. Null si la vidéo est illisible.
     */
    fun thumbnail(video: File, out: File): PhotoMeta? {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(video.path)
            val frame = retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.frameAtTime
                ?: return null
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val upright = upright(frame, rotation, w, h)
            if (!Images.writeJpeg(upright, out)) return null
            val place = parseLocation(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION))
            val date = parseDate(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE))
            return PhotoMeta(place?.first, place?.second, date)
        } catch (e: Exception) {
            return null
        } catch (e: OutOfMemoryError) {
            return null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * L'image remise à l'endroit (selon l'appareil, elle sort déjà tournée ou non) et réduite
     * pour tenir en 1920 × 1080.
     */
    private fun upright(frame: Bitmap, rotation: Int, w: Int, h: Int): Bitmap {
        val matrix = Matrix()
        if (needsRotation(frame.width, frame.height, rotation, w, h)) matrix.postRotate(rotation.toFloat())
        val turned = rotation % 180 != 0 && !matrix.isIdentity
        val fw = if (turned) frame.height else frame.width
        val fh = if (turned) frame.width else frame.height
        val scale = min(1920f / maxOf(fw, fh), 1080f / minOf(fw, fh)).coerceAtMost(1f)
        if (scale < 1f) matrix.postScale(scale, scale)
        if (matrix.isIdentity) return frame
        return Bitmap.createBitmap(frame, 0, 0, frame.width, frame.height, matrix, true).also {
            if (it !== frame) frame.recycle()
        }
    }

    /**
     * Faut-il encore tourner l'image ? Oui si la vidéo est filmée tournée ([rotation] 90 ou 270)
     * et que l'image a encore le sens de la vidéo brute ([w] × [h]).
     */
    fun needsRotation(frameW: Int, frameH: Int, rotation: Int, w: Int, h: Int): Boolean {
        // 180° : la forme de l'image ne dit rien, on fait confiance à Android (qui la tourne en général)
        if (rotation % 180 == 0) return false
        if (w <= 0 || h <= 0 || w == h || frameW == frameH) return false
        return (frameW > frameH) == (w > h)
    }

    /** « 20230615T123456.000Z » (date de tournage) → millisecondes. */
    fun parseDate(text: String?): Long? {
        val t = text?.trim()?.takeIf { it.length >= 15 } ?: return null
        if (t.startsWith("1904") || t.startsWith("1970")) return null // date vide de certains appareils
        return try {
            SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                .parse(t.substring(0, 15))?.time
        } catch (e: Exception) {
            null
        }
    }

    /** Lieu au format ISO 6709 des téléphones, « +48.8566+002.3522/ » ou « +48.85-002.35+035.0/ ». */
    fun parseLocation(text: String?): Pair<Double, Double>? {
        val m = Regex("""^([+-]\d+(?:\.\d+)?)([+-]\d+(?:\.\d+)?)""").find(text?.trim() ?: return null) ?: return null
        val lat = m.groupValues[1].toDoubleOrNull() ?: return null
        val lon = m.groupValues[2].toDoubleOrNull() ?: return null
        if (lat == 0.0 && lon == 0.0) return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return lat to lon
    }
}
