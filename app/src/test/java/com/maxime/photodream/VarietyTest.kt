package com.maxime.photodream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VarietyTest {

    @Test
    fun `groupe d'une photo du NAS, son dossier`() {
        assertEquals(Variety.group("Vacances\\IMG_1.jpg"), Variety.group("vacances\\IMG_2.jpg"))
        assertNotEquals(Variety.group("Vacances\\IMG_1.jpg"), Variety.group("Noel\\IMG_1.jpg"))
        // même nom de dossier sur deux NAS différents : deux groupes
        assertNotEquals(Variety.group("@ab12@Vacances\\IMG_1.jpg"), Variety.group("Vacances\\IMG_1.jpg"))
    }

    @Test
    fun `groupe d'une photo du web, les premiers mots de son titre`() {
        assertEquals(Variety.group("@web@File:Lake_Bled_2.jpg"), Variety.group("@web@File:Lake Bled from the castle.jpg"))
        assertNotEquals(Variety.group("@web@File:Lake_Bled_2.jpg"), Variety.group("@web@File:Lake_Geneva_sunset.jpg"))
        // « View of… » ne suffit pas à faire un groupe
        assertNotEquals(Variety.group("@web@File:View of Mont Blanc.jpg"), Variety.group("@web@File:View of Lake Como.jpg"))
    }

    @Test
    fun `deux photos du meme groupe ne se suivent pas`() {
        val list = listOf("a1", "a2", "a3", "b1", "b2", "c1", "c2", "d1")
        val out = Variety.spread(list) { it.take(1) }
        assertEquals(list.toSet(), out.toSet())
        assertEquals(list.size, out.size)
        for (i in 1 until out.size) assertNotEquals("$out", out[i - 1][0], out[i][0])
    }

    @Test
    fun `tout du meme groupe, l'ordre ne change pas`() {
        val list = listOf("a1", "a2", "a3", "a4")
        assertEquals(list, Variety.spread(list) { "a" })
    }

    @Test
    fun `empreinte de couleurs, moyenne par case`() {
        // 8 × 6 pixels : moitié haute bleue, moitié basse verte
        val pixels = IntArray(48) { i -> if (i / 8 < 3) 0xFF0000FF.toInt() else 0xFF00FF00.toInt() }
        val p = Variety.print(pixels, 8, 6)
        assertEquals(36, p.size)
        // première case (en haut à gauche) : moitié bleue (lignes 0 et 1 sur 0-1) → bleu pur
        assertEquals(1f, p[2], 0.001f)
        assertEquals(0f, p[1], 0.001f)
        // dernière case (en bas à droite) : vert pur
        assertEquals(1f, p[34], 0.001f)
    }

    @Test
    fun `couleurs presque pareilles ou differentes`() {
        val sea = IntArray(48) { 0xFF2050A0.toInt() }
        val sea2 = IntArray(48) { 0xFF2252A4.toInt() }
        val forest = IntArray(48) { 0xFF206020.toInt() }
        val a = Variety.print(sea, 8, 6)
        assertTrue(Variety.tooClose(a, Variety.print(sea2, 8, 6)))
        assertFalse(Variety.tooClose(a, Variety.print(forest, 8, 6)))
        assertFalse(Variety.tooClose(null, a))
    }

    @Test
    fun `diaporama simple, la suivante trop ressemblante passe plus loin`() {
        val photos = (1..12).map { RemotePhoto("IMG_$it.jpg", 1L, 1L) }
        val player = PhotoPlayer(photos, 0, 1000L)
        assertTrue(player.deferNext(by = 5))
        assertEquals("IMG_3.jpg", player.peekNext().path)
        assertEquals(6, player.photos.indexOfFirst { it.path == "IMG_2.jpg" })
        assertEquals(12, player.photos.size)
        // près de la fin du tour : rien où la mettre
        val end = PhotoPlayer(photos, 10, 1000L)
        assertFalse(end.deferNext())
    }
}
