package com.maxime.photodream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NeighborsTest {

    // une petite grille 3 × 3, cases de 100 px
    private val grid = (0..2).flatMap { row -> (0..2).map { col -> col * 100f to row * 100f } }

    @Test
    fun `chaque fleche va a la case voisine`() {
        val (x, y) = grid[4] // le centre
        assertEquals(5, Neighbors.pick(x, y, grid, 1, 0))
        assertEquals(3, Neighbors.pick(x, y, grid, -1, 0))
        assertEquals(7, Neighbors.pick(x, y, grid, 0, 1))
        assertEquals(1, Neighbors.pick(x, y, grid, 0, -1))
    }

    @Test
    fun `rien au bord`() {
        val (x, y) = grid[2] // en haut à droite
        assertNull(Neighbors.pick(x, y, grid, 1, 0))
        assertNull(Neighbors.pick(x, y, grid, 0, -1))
    }

    @Test
    fun `prefere une case alignee a une case plus proche mais de biais`() {
        val points = listOf(60f to 50f, 150f to 5f)
        assertEquals(1, Neighbors.pick(0f, 0f, points, 1, 0))
    }
}
