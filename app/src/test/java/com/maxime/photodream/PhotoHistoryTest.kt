package com.maxime.photodream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhotoHistoryTest {

    private fun photo(n: Int) = RemotePhoto("IMG_$n.jpg", 100L, 1L)

    @Test
    fun `les nouvelles photos s'ajoutent a la fin`() {
        val h = PhotoHistory()
        assertEquals(-1, h.shown)
        h.show(photo(1), -1)
        h.show(photo(2), -1)
        assertEquals(2, h.size)
        assertEquals(1, h.shown)
        assertNull(h.replayIndex())
    }

    @Test
    fun `apres un retour en arriere on rejoue la suite puis de nouvelles photos`() {
        val h = PhotoHistory()
        (1..3).forEach { h.show(photo(it), -1) }
        h.show(photo(1), 0) // flèche gauche deux fois
        assertEquals(0, h.shown)
        assertEquals(1, h.replayIndex())
        h.show(photo(2), 1)
        assertEquals(2, h.replayIndex())
        h.show(photo(3), 2)
        assertNull(h.replayIndex())
        assertEquals(3, h.size)
    }

    @Test
    fun `l'historique est limite`() {
        val h = PhotoHistory(max = 3)
        (1..5).forEach { h.show(photo(it), -1) }
        assertEquals(3, h.size)
        assertEquals(photo(3), h[0])
        assertEquals(2, h.shown)
    }

    @Test
    fun `une position perimee ajoute la photo`() {
        val h = PhotoHistory()
        h.show(photo(1), -1)
        h.show(photo(2), 0) // la position 0 n'est pas cette photo
        assertEquals(2, h.size)
        assertEquals(1, h.shown)
    }
}
