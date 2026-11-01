package com.maxime.photodream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoPlayerTest {

    private fun photo(path: String) = RemotePhoto(path, 100L, 1L)

    private val all = listOf(
        photo("Vacances\\Corse\\IMG_3.jpg"),
        photo("Vacances\\Corse\\img_1.jpg"),
        photo("Vacances\\Corse\\Plage\\IMG_2.jpg"),
        photo("Vacances\\Bretagne\\IMG_9.jpg"),
        photo("Vacances\\Corse\\IMG_2.jpg"),
    )

    @Test
    fun `ordre dossier par dossier puis par nom`() {
        val list = PhotoPlayer.ordered(all)
        assertEquals(
            listOf(
                "Vacances\\Bretagne\\IMG_9.jpg",
                "Vacances\\Corse\\img_1.jpg",
                "Vacances\\Corse\\IMG_2.jpg",
                "Vacances\\Corse\\IMG_3.jpg",
                "Vacances\\Corse\\Plage\\IMG_2.jpg",
            ),
            list.map { it.path },
        )
    }

    @Test
    fun `photos a la racine avant les sous-dossiers`() {
        val list = PhotoPlayer.ordered(listOf(photo("X\\c.jpg"), photo("b.jpg"), photo("a.jpg")))
        assertEquals(listOf("a.jpg", "b.jpg", "X\\c.jpg"), list.map { it.path })
    }

    @Test
    fun `avance toute seule apres l'intervalle compte depuis l'affichage`() {
        val p = PhotoPlayer(listOf(photo("1"), photo("2"), photo("3")), 1, 5_000)
        assertFalse(p.update(10_000)) // pas encore affichée : on attend le chargement
        p.shown(10_000)
        assertFalse(p.update(14_999))
        assertTrue(p.update(15_000))
        assertEquals(2, p.index)
        assertFalse(p.update(30_000)) // la suivante n'est pas encore affichée
    }

    @Test
    fun `gauche et droite font le tour du dossier`() {
        val p = PhotoPlayer(listOf(photo("1"), photo("2"), photo("3")), 0, 5_000)
        p.previous()
        assertEquals(2, p.index)
        p.next()
        assertEquals(0, p.index)
        assertEquals("2", p.peekNext().path)
        assertEquals("3", p.peekPrevious().path)
    }

    @Test
    fun `la pause bloque le defilement et la reprise redonne un intervalle entier`() {
        val p = PhotoPlayer(listOf(photo("1"), photo("2")), 0, 5_000)
        p.shown(0)
        p.togglePause(1_000)
        assertFalse(p.update(60_000))
        p.togglePause(60_000)
        assertFalse(p.update(64_000))
        assertTrue(p.update(65_000))
        assertEquals(1, p.index)
    }

    @Test
    fun `navigation manuelle relance le compte a rebours`() {
        val p = PhotoPlayer(listOf(photo("1"), photo("2"), photo("3")), 0, 5_000)
        p.shown(0)
        p.next()
        assertFalse(p.update(6_000)) // la nouvelle photo n'est pas encore affichée
        p.shown(6_000)
        assertFalse(p.update(10_000))
        assertTrue(p.update(11_000))
    }
}
