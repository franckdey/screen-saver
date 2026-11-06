package com.maxime.photodream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextWrapTest {

    // chaque caractère fait 1 de large
    private val measure: (String) -> Float = { it.length.toFloat() }

    @Test
    fun `une legende courte reste sur une ligne`() {
        assertEquals(listOf("Key West · août 2026"), TextWrap.wrap("Key West · août 2026", 30f, measure))
    }

    @Test
    fun `une legende trop longue passe sur deux lignes sans point median isole`() {
        val lines = TextWrap.wrap("2019 Corse › Plage du matin · IMG_1234 · août 2026", 30f, measure)
        assertEquals(listOf("2019 Corse › Plage du matin", "IMG_1234 · août 2026"), lines)
        assertTrue(lines.all { it.length <= 30 })
    }

    @Test
    fun `au-dela de deux lignes la fin est raccourcie`() {
        val lines = TextWrap.wrap("un deux trois quatre cinq six sept huit neuf dix onze douze", 15f, measure)
        assertEquals(2, lines.size)
        assertEquals("un deux trois", lines[0])
        assertTrue(lines[1].endsWith("…") && lines[1].length <= 15)
    }

    @Test
    fun `un mot trop long est raccourci`() {
        val lines = TextWrap.wrap("IMG_20190815_123456789_tres_long", 10f, measure)
        assertEquals(1, lines.size)
        assertTrue(lines[0].endsWith("…") && lines[0].length <= 10)
    }
}
