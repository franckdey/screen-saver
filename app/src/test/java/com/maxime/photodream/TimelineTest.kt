package com.maxime.photodream

import com.maxime.photodream.Timeline.Companion.FADE_MS
import com.maxime.photodream.Timeline.Companion.PAUSE_MAX_MS
import com.maxime.photodream.Timeline.Companion.PAUSE_MS
import com.maxime.photodream.Timeline.Companion.RETRY_MS
import com.maxime.photodream.Timeline.Companion.ZOOM_MS
import com.maxime.photodream.Timeline.Event
import com.maxime.photodream.Timeline.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineTest {
    private val hold = 8_000L
    private val timeline = Timeline(hold)
    private var now = 0L

    /** Avance l'horloge pas à pas (comme les images de l'animation) et récupère les évènements. */
    private fun advance(ms: Long, nextReady: Boolean = true, loading: Boolean = false): List<Event> {
        val events = mutableListOf<Event>()
        val end = now + ms
        while (now < end) {
            now = minOf(end, now + 16)
            events += timeline.update(now, nextReady, loading)
        }
        return events
    }

    private fun startPhoto() {
        assertEquals(listOf(Event.START_PHOTO), advance(PAUSE_MS))
    }

    @Test
    fun `cycle complet d'une photo`() {
        startPhoto()
        assertEquals(Phase.FADE_IN, timeline.phase)
        advance(FADE_MS)
        assertEquals(Phase.ZOOM_IN, timeline.phase)
        advance(ZOOM_MS)
        assertEquals(Phase.HOLD, timeline.phase)
        advance(hold)
        assertEquals(Phase.ZOOM_OUT, timeline.phase)
        advance(ZOOM_MS)
        assertEquals(Phase.FADE_OUT, timeline.phase)
        val events = advance(FADE_MS, nextReady = false)
        assertEquals(listOf(Event.PHOTO_DONE), events)
        assertEquals(Phase.WAIT, timeline.phase)
    }

    @Test
    fun `aucune photo ne part tant que rien n'est charge`() {
        val events = advance(5_000, nextReady = false, loading = true)
        assertTrue(events.isEmpty())
        assertEquals(Phase.WAIT, timeline.phase)
    }

    @Test
    fun `nouvel essai de chargement apres 15 secondes sans photo`() {
        val events = advance(RETRY_MS + 100, nextReady = false, loading = false)
        assertEquals(listOf(Event.RETRY_LOAD), events)
    }

    @Test
    fun `pas de nouvel essai pendant un chargement`() {
        assertTrue(advance(RETRY_MS * 2, nextReady = false, loading = true).isEmpty())
    }

    @Test
    fun `photo fixee reste en plein ecran bien au-dela de la duree normale`() {
        startPhoto()
        advance(FADE_MS + ZOOM_MS + 1_000)
        timeline.togglePause(now)
        advance(hold * 5) // 40 s
        assertEquals(Phase.HOLD, timeline.phase)
        assertTrue(timeline.paused)
    }

    @Test
    fun `relance manuelle de la photo fixee`() {
        startPhoto()
        advance(FADE_MS + ZOOM_MS + 1_000)
        timeline.togglePause(now)
        advance(20_000)
        timeline.togglePause(now)
        assertFalse(timeline.paused)
        // le temps d'affichage restant (la moitié) s'écoule puis la photo repart
        advance(hold / 2 + 100)
        assertEquals(Phase.ZOOM_OUT, timeline.phase)
    }

    @Test
    fun `reprise automatique au bout d'une minute`() {
        startPhoto()
        advance(FADE_MS + ZOOM_MS + 1_000)
        timeline.togglePause(now)
        val before = advance(PAUSE_MAX_MS - 100)
        assertFalse(Event.AUTO_RESUMED in before)
        assertEquals(Phase.HOLD, timeline.phase)
        val after = advance(200)
        assertEquals(listOf(Event.AUTO_RESUMED), after)
        assertFalse(timeline.paused)
        advance(hold / 2 + 100)
        assertEquals(Phase.ZOOM_OUT, timeline.phase)
    }

    @Test
    fun `pause demandee avant le plein ecran s'applique a l'arrivee`() {
        startPhoto()
        timeline.togglePause(now)
        advance(FADE_MS + ZOOM_MS + hold * 2)
        assertEquals(Phase.HOLD, timeline.phase)
    }

    @Test
    fun `photo suivante tout de suite`() {
        startPhoto()
        advance(FADE_MS + ZOOM_MS + 500)
        timeline.togglePause(now)
        timeline.skip()
        assertFalse(timeline.paused)
        advance(32)
        assertEquals(Phase.ZOOM_OUT, timeline.phase)
    }

    @Test
    fun `valeurs dessinees a chaque etape`() {
        assertEquals(0f, timeline.frame(now).alpha, 0f)
        startPhoto()
        advance(FADE_MS / 2)
        val fading = timeline.frame(now)
        assertEquals(0f, fading.zoom, 0f)
        assertTrue(fading.alpha in 0.3f..0.7f)
        advance(FADE_MS / 2)
        var last = -1f
        repeat(10) {
            advance(ZOOM_MS / 10 - 1)
            val z = timeline.frame(now).zoom
            assertTrue("le zoom doit grandir", z >= last)
            last = z
        }
        advance(hold / 2)
        val holding = timeline.frame(now)
        assertEquals(1f, holding.zoom, 0f)
        assertTrue(holding.kenBurns in 0.4f..0.6f)
    }

    @Test
    fun `courbe d'acceleration`() {
        assertEquals(0f, Timeline.ease(0f), 1e-6f)
        assertEquals(0.5f, Timeline.ease(0.5f), 1e-6f)
        assertEquals(1f, Timeline.ease(1f), 1e-6f)
        var previous = 0f
        for (i in 1..100) {
            val v = Timeline.ease(i / 100f)
            assertTrue(v >= previous)
            previous = v
        }
        // symétrique : démarre et finit en douceur
        assertEquals(1f - Timeline.ease(0.2f), Timeline.ease(0.8f), 1e-5f)
    }
}
