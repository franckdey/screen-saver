package com.maxime.photodream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoZoomTest {
    /** Écran 16/9 et photo 4/3 : la photo entière laisse des bandes noires à gauche et à droite. */
    private fun zoom() = PhotoZoom().apply { setSizes(1920f, 1080f, 1440f, 1080f) }

    private fun settle(z: PhotoZoom) = repeat(200) { z.step(16) }

    @Test
    fun `commence centre au premier cran`() {
        val z = zoom()
        assertEquals(2f, z.scale)
        assertEquals(0.5f, z.x)
        assertEquals(0.5f, z.y)
    }

    @Test
    fun `les crans s'arretent au maximum et au minimum`() {
        val z = zoom()
        assertTrue(z.zoomIn())
        assertTrue(z.zoomIn())
        assertFalse(z.zoomIn())
        assertEquals(4f, z.scale)
        assertTrue(z.zoomOut())
        assertTrue(z.zoomOut())
        assertFalse(z.zoomOut())
        assertEquals(2f, z.scale)
    }

    @Test
    fun `le deplacement s'arrete au bord de la photo`() {
        val z = zoom()
        // ×2 : on voit la moitié de la hauteur de la photo, le bord est à 0,25 du centre
        repeat(10) { z.pan(0, -1) }
        assertEquals(0.25f, z.y, 1e-4f)
        assertFalse(z.pan(0, -1))
        repeat(10) { z.pan(0, 1) }
        assertEquals(0.75f, z.y, 1e-4f)
    }

    @Test
    fun `photo etroite reste centree tant qu'elle tient dans l'ecran`() {
        // photo en hauteur sur écran large : à ×2 elle n'est pas encore plus large que l'écran
        val z = PhotoZoom().apply { setSizes(1920f, 1080f, 600f, 1080f) }
        assertFalse(z.pan(1, 0))
        assertEquals(0.5f, z.x)
        assertTrue(z.pan(0, 1))
    }

    @Test
    fun `en dezoomant on reste dans la photo`() {
        val z = zoom()
        z.zoomIn()
        z.zoomIn()
        repeat(10) { z.pan(1, 1) }
        z.zoomOut()
        z.zoomOut()
        settle(z)
        val v = z.visible()
        assertEquals(1f, v[2], 1e-3f)
        assertEquals(1f, v[3], 1e-3f)
        assertEquals(0.5f, v[3] - v[1], 1e-3f)
    }

    @Test
    fun `le zoom affiche rejoint le zoom voulu en douceur`() {
        val z = PhotoZoom(startScale = 1f).apply { setSizes(1920f, 1080f, 1440f, 1080f) }
        z.step(16)
        assertTrue(z.shownScale > 1f && z.shownScale < 2f)
        settle(z)
        assertEquals(2f, z.shownScale, 1e-3f)
    }
}
