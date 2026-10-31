package com.maxime.photodream

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.media.ExifInterface
import android.os.Build
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object Images {
    /** Taille maximale des photos gardées en cache (plein écran Full HD). */
    private const val MAX_W = 1920
    private const val MAX_H = 1080

    /** Proportions des vignettes de la mosaïque. */
    const val TILE_ASPECT = 4f / 3f
    private const val TILE_BMP_W = 320

    /**
     * Réduit une photo originale (souvent 12 Mpx et plus) à la taille de l'écran,
     * la remet à l'endroit d'après l'EXIF et l'écrit en JPEG dans [out].
     */
    fun downscaleToJpeg(bytes: ByteArray, out: File): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            // Format que BitmapFactory ne lit pas (certains HEIC…) : ImageDecoder, Android 9+.
            val bmp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) decodeModern(bytes) else null
            return bmp != null && writeJpeg(bmp, out)
        }

        val rotation = exifRotation(bytes)
        val rotatedW = if (rotation % 180 == 0) bounds.outWidth else bounds.outHeight
        val rotatedH = if (rotation % 180 == 0) bounds.outHeight else bounds.outWidth
        val scale = min(MAX_W.toFloat() / rotatedW, MAX_H.toFloat() / rotatedH).coerceAtMost(1f)

        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSizeFor(scale) }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return false

        val dw = if (rotation % 180 == 0) decoded.width else decoded.height
        val dh = if (rotation % 180 == 0) decoded.height else decoded.width
        val finalScale = min(MAX_W.toFloat() / dw, MAX_H.toFloat() / dh).coerceAtMost(1f)
        val matrix = Matrix().apply {
            if (rotation != 0) postRotate(rotation.toFloat())
            if (finalScale < 1f) postScale(finalScale, finalScale)
        }
        val result = if (matrix.isIdentity) decoded
        else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)

        if (result !== decoded) decoded.recycle()
        return writeJpeg(result, out)
    }

    private fun writeJpeg(bmp: Bitmap, out: File): Boolean {
        val tmp = File(out.parentFile, out.name + ".tmp")
        val ok = tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bmp.recycle()
        return ok && tmp.renameTo(out)
    }

    /** Décodage par ImageDecoder (remet aussi la photo à l'endroit), réduit au plein écran. */
    @android.annotation.TargetApi(Build.VERSION_CODES.P)
    private fun decodeModern(bytes: ByteArray): Bitmap? = try {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
            val w = info.size.width
            val h = info.size.height
            val scale = min(MAX_W.toFloat() / max(w, h), MAX_H.toFloat() / min(w, h)).coerceAtMost(1f)
            decoder.setTargetSize(max(1, (w * scale).roundToInt()), max(1, (h * scale).roundToInt()))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } catch (e: Exception) {
        null
    }

    /** Décode un fichier du cache en limitant sa largeur à environ [maxWidth]. */
    fun decode(file: File, maxWidth: Int, config: Bitmap.Config = Bitmap.Config.ARGB_8888): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(maxWidth.toFloat() / bounds.outWidth)
            inPreferredConfig = config
        }
        return BitmapFactory.decodeFile(file.path, opts)
    }

    /** Fabrique une vignette recadrée au format de la mosaïque, éventuellement floutée. */
    fun makeTile(src: Bitmap, blurLevel: Int): Bitmap {
        val w = TILE_BMP_W
        val h = (TILE_BMP_W / TILE_ASPECT).roundToInt()
        val crop = centerCrop(src.width, src.height, TILE_ASPECT)
        val tile = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(tile).drawBitmap(src, crop, Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
        val radius = when (blurLevel) {
            0 -> 0
            1 -> 3
            else -> 7
        }
        if (radius > 0) boxBlur(tile, radius, passes = 3)
        return tile
    }

    fun centerCrop(w: Int, h: Int, aspect: Float): Rect {
        return if (w.toFloat() / h > aspect) {
            val cw = (h * aspect).roundToInt()
            Rect((w - cw) / 2, 0, (w - cw) / 2 + cw, h)
        } else {
            val ch = (w / aspect).roundToInt()
            Rect(0, (h - ch) / 2, w, (h - ch) / 2 + ch)
        }
    }

    /** Plus grande puissance de 2 qui garde une image au moins aussi grande que demandé. */
    internal fun sampleSizeFor(scale: Float): Int {
        var sample = 1
        if (scale <= 0f) return sample
        while (sample * 2 <= 1f / scale) sample *= 2
        return sample
    }

    private fun exifRotation(bytes: ByteArray): Int = try {
        when (ExifInterface(ByteArrayInputStream(bytes))
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_TRANSPOSE -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270, ExifInterface.ORIENTATION_TRANSVERSE -> 270
            else -> 0
        }
    } catch (e: Exception) {
        0
    }

    /** Flou rapide (plusieurs passes de flou « boîte » ≈ flou gaussien) sur une petite image. */
    private fun boxBlur(bmp: Bitmap, radius: Int, passes: Int) {
        val w = bmp.width
        val h = bmp.height
        val src = IntArray(w * h)
        val tmp = IntArray(w * h)
        bmp.getPixels(src, 0, w, 0, 0, w, h)
        repeat(passes) {
            blurLine(src, tmp, w, h, radius, horizontal = true)
            blurLine(tmp, src, w, h, radius, horizontal = false)
        }
        bmp.setPixels(src, 0, w, 0, 0, w, h)
    }

    private fun blurLine(input: IntArray, output: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val len = if (horizontal) w else h
        val div = 2 * r + 1
        for (line in 0 until lines) {
            fun idx(i: Int): Int {
                val c = i.coerceIn(0, len - 1)
                return if (horizontal) line * w + c else c * w + line
            }
            var sr = 0
            var sg = 0
            var sb = 0
            for (i in -r..r) {
                val p = input[idx(i)]
                sr += (p shr 16) and 0xFF
                sg += (p shr 8) and 0xFF
                sb += p and 0xFF
            }
            for (i in 0 until len) {
                output[idx(i)] = (0xFF shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val add = input[idx(i + r + 1)]
                val rem = input[idx(i - r)]
                sr += ((add shr 16) and 0xFF) - ((rem shr 16) and 0xFF)
                sg += ((add shr 8) and 0xFF) - ((rem shr 8) and 0xFF)
                sb += (add and 0xFF) - (rem and 0xFF)
            }
        }
    }
}
