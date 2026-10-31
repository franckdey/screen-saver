package com.maxime.photodream

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File

/** Fabrique de vraies photos JPEG pour les tests. */
object TestImages {
    /** Une photo coupée en deux : moitié gauche rouge, moitié droite bleue. */
    fun jpeg(width: Int, height: Int): ByteArray {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.BLUE)
        canvas.drawRect(0f, 0f, width / 2f, height.toFloat(), Paint().apply { color = Color.RED })
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 92, out)
        return out.toByteArray()
    }

    /** La même photo avec une orientation EXIF (6 = prise téléphone tourné de 90°). */
    fun jpegWithOrientation(width: Int, height: Int, orientation: Int, dir: File): ByteArray {
        val file = File(dir, "exif-${System.nanoTime()}.jpg")
        file.writeBytes(jpeg(width, height))
        ExifInterface(file.path).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            saveAttributes()
        }
        return file.readBytes()
    }
}
