package com.maxime.photodream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideosTest {
    @Test
    fun `formats video reconnus, photos non`() {
        assertTrue(Videos.isVideo("Vacances\\VID_001.MP4"))
        assertTrue(Videos.isVideo("a.mov"))
        assertTrue(Videos.isVideo("a.mkv"))
        assertFalse(Videos.isVideo("a.jpg"))
        assertFalse(Videos.isVideo("mp4"))
    }

    @Test
    fun `date de tournage des telephones`() {
        // 15 juin 2023 12:34:56 UTC
        assertEquals(1686832496000L, Videos.parseDate("20230615T123456.000Z"))
        assertEquals(1686832496000L, Videos.parseDate("20230615T123456"))
        assertNull(Videos.parseDate("19040101T000000.000Z"))
        assertNull(Videos.parseDate(""))
        assertNull(Videos.parseDate(null))
    }

    @Test
    fun `lieu au format ISO 6709`() {
        assertEquals(48.8566 to 2.3522, Videos.parseLocation("+48.8566+002.3522/"))
        assertEquals(-33.86 to 151.21, Videos.parseLocation("-33.86+151.21+012.000/"))
        assertEquals(24.55 to -81.78, Videos.parseLocation("+24.55-081.78/"))
        assertNull(Videos.parseLocation("+00.0000+000.0000/"))
        assertNull(Videos.parseLocation("n'importe quoi"))
        assertNull(Videos.parseLocation(null))
    }

    @Test
    fun `image remise a l'endroit seulement si Android ne l'a pas deja fait`() {
        // vidéo de téléphone tenu droit : 1920 × 1080 tournée de 90°
        assertTrue(Videos.needsRotation(1920, 1080, 90, 1920, 1080))
        assertFalse(Videos.needsRotation(1080, 1920, 90, 1920, 1080))
        assertFalse(Videos.needsRotation(1920, 1080, 0, 1920, 1080))
        assertFalse(Videos.needsRotation(1920, 1080, 180, 1920, 1080))
    }

    @Test
    fun `une video au plus la moitie du cache`() {
        assertEquals(250L * 1024 * 1024, Videos.maxBytes(500))
    }
}
