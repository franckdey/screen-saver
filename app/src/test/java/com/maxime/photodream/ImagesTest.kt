package com.maxime.photodream

import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.media.ExifInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImagesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun size(bytes: ByteArray): Pair<Int, Int> {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        return o.outWidth to o.outHeight
    }

    @Test
    fun `facteur de reduction au decodage`() {
        assertEquals(1, Images.sampleSizeFor(1f))
        assertEquals(1, Images.sampleSizeFor(0.6f))
        assertEquals(2, Images.sampleSizeFor(0.5f))
        assertEquals(2, Images.sampleSizeFor(0.3f))
        assertEquals(4, Images.sampleSizeFor(0.25f))
        assertEquals(1, Images.sampleSizeFor(0f))
    }

    @Test
    fun `recadrage au centre au format des vignettes`() {
        assertEquals(Rect(100, 0, 500, 300), Images.centerCrop(600, 300, 4f / 3f))
        assertEquals(Rect(0, 150, 300, 375), Images.centerCrop(300, 525, 4f / 3f))
        assertEquals(Rect(0, 0, 400, 300), Images.centerCrop(400, 300, 4f / 3f))
    }

    @Test
    fun `grande photo paysage reduite en Full HD sans deformation`() {
        val out = tmp.newFile("a.jpg")
        assertTrue(Images.downscaleToJpeg(TestImages.jpeg(4000, 3000), out))
        val (w, h) = size(out.readBytes())
        assertEquals(1440, w)
        assertEquals(1080, h)
    }

    @Test
    fun `photo portrait limitee par la hauteur de l'ecran`() {
        val out = tmp.newFile("b.jpg")
        assertTrue(Images.downscaleToJpeg(TestImages.jpeg(1500, 3000), out))
        val (w, h) = size(out.readBytes())
        assertEquals(540, w)
        assertEquals(1080, h)
    }

    @Test
    fun `petite photo jamais agrandie`() {
        val out = tmp.newFile("c.jpg")
        assertTrue(Images.downscaleToJpeg(TestImages.jpeg(800, 600), out))
        assertEquals(800 to 600, size(out.readBytes()))
    }

    @Test
    fun `photo prise telephone tourne remise a l'endroit`() {
        val bytes = TestImages.jpegWithOrientation(800, 400, ExifInterface.ORIENTATION_ROTATE_90, tmp.root)
        val out = tmp.newFile("d.jpg")
        assertTrue(Images.downscaleToJpeg(bytes, out))
        assertEquals(400 to 800, size(out.readBytes()))
        // la moitié rouge (gauche de l'original) se retrouve en haut après rotation de 90°
        val bmp = BitmapFactory.decodeFile(out.path)
        val top = bmp.getPixel(200, 100)
        val bottom = bmp.getPixel(200, 700)
        assertTrue(Color.red(top) > 200 && Color.blue(top) < 80)
        assertTrue(Color.blue(bottom) > 200 && Color.red(bottom) < 80)
    }

    @Test
    fun `fichier qui n'est pas une image refuse sans plantage`() {
        val out = tmp.newFile("e.jpg").apply { delete() }
        assertFalse(Images.downscaleToJpeg("pas une photo".toByteArray(), out))
        assertFalse(out.exists())
    }

    @Test
    fun `decodage du cache limite en largeur`() {
        val f = tmp.newFile("f.jpg").apply { writeBytes(TestImages.jpeg(1920, 1080)) }
        val bmp = Images.decode(f, 480)!!
        assertTrue(bmp.width in 480..960)
    }

    @Test
    fun `vignette au bon format, nette ou floutee`() {
        val src = BitmapFactory.decodeByteArray(TestImages.jpeg(1200, 900), 0, TestImages.jpeg(1200, 900).size)
        val sharp = Images.makeTile(src, blurLevel = 0)
        val blurred = Images.makeTile(src, blurLevel = 2)
        assertEquals(320, sharp.width)
        assertEquals(240, sharp.height)
        assertEquals(320 to 240, blurred.width to blurred.height)
        // à la frontière rouge / bleu, la vignette floutée mélange les deux couleurs
        val edgeSharp = sharp.getPixel(162, 120)
        val edgeBlur = blurred.getPixel(162, 120)
        assertTrue(abs(Color.red(edgeBlur) - Color.blue(edgeBlur)) < abs(Color.red(edgeSharp) - Color.blue(edgeSharp)))
        // loin de la frontière la couleur reste franche
        assertTrue(Color.red(blurred.getPixel(20, 120)) > 200)
    }
}
