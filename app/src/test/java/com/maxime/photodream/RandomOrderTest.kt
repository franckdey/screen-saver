package com.maxime.photodream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import kotlin.random.Random

class RandomOrderTest {

    private fun photos(n: Int) = (1..n).map { RemotePhoto("IMG_$it.jpg", 10L, 1L) }

    @Test
    fun `premier lancement, toutes les photos, une seule fois chacune`() {
        val all = photos(50)
        val order = RandomOrder.reconcile(emptyList(), all, Random(1))
        assertEquals(all.toSet(), order.toSet())
        assertEquals(50, order.size)
        assertNotEquals(all, order)
    }

    @Test
    fun `l'ordre memorise est repris, les nouvelles photos a la fin, les disparues retirees`() {
        val all = photos(5)
        val saved = listOf("IMG_3.jpg", "IMG_1.jpg", "IMG_9.jpg", "IMG_2.jpg")
        val order = RandomOrder.reconcile(saved, all, Random(2))
        assertEquals(listOf("IMG_3.jpg", "IMG_1.jpg", "IMG_2.jpg"), order.take(3).map { it.path })
        assertEquals(setOf("IMG_4.jpg", "IMG_5.jpg"), order.drop(3).map { it.path }.toSet())
    }

    @Test
    fun `un nouveau tour ne recommence pas par la derniere photo vue`() {
        val all = photos(3)
        repeat(50) { seed ->
            val next = RandomOrder.reshuffle(all, Random(seed))
            assertEquals(all.toSet(), next.toSet())
            assertNotEquals(all.last(), next.first())
        }
    }

    @Test
    fun `le diaporama simple tire un nouvel ordre apres la derniere photo`() {
        val all = photos(3)
        var tours = 0
        val player = PhotoPlayer(all, 2, 1000L) { old -> tours++; RandomOrder.reshuffle(old, Random(3)) }
        player.next()
        assertEquals(1, tours)
        assertEquals(0, player.index)
        assertNotEquals(all.last(), player.current)
        // en arrière depuis la première, on va à la dernière du nouvel ordre, sans nouveau tirage
        player.previous()
        assertEquals(2, player.index)
        assertEquals(1, tours)
    }
}
