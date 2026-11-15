package com.maxime.photodream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import kotlin.random.Random

class TransitionTest {

    @Test
    fun `a la fin de chaque effet seule la nouvelle photo est visible, a sa place`() {
        for (tr in Transition.entries) {
            val (old, new) = tr.frame(1f, forward = true)
            assertEquals(tr.name, Layer(), new)
            // l'ancienne a disparu : transparente, hors de l'écran ou recouverte
            val gone = old.alpha == 0f || kotlin.math.abs(old.dx) >= 1f || tr == Transition.WIPE
            assertEquals(tr.name, true, gone)
        }
    }

    @Test
    fun `au debut l'ancienne photo est encore entiere`() {
        for (tr in Transition.entries) {
            val (old, _) = tr.frame(0f, forward = true)
            assertEquals(tr.name, Layer(), old)
        }
    }

    @Test
    fun `le glissement change de sens en arriere`() {
        val (_, fwd) = Transition.SLIDE.frame(0.5f, forward = true)
        val (_, back) = Transition.SLIDE.frame(0.5f, forward = false)
        assertEquals(fwd.dx, -back.dx, 1e-6f)
        assertEquals(true, fwd.dx > 0f)
    }

    @Test
    fun `au hasard un vrai effet, different du precedent`() {
        repeat(30) { seed ->
            val t = Transition.RANDOM.resolve(Transition.SLIDE, Random(seed))
            assertNotEquals(Transition.RANDOM, t)
            assertNotEquals(Transition.SLIDE, t)
        }
        assertEquals(Transition.ZOOM, Transition.ZOOM.resolve(Transition.ZOOM))
    }

    @Test
    fun `un reglage inconnu donne le fondu`() {
        assertEquals(Transition.FADE, Transition.of("??"))
        assertEquals(Transition.WIPE, Transition.of("wipe"))
    }
}
