package com.maxime.photodream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Lecture des réponses d'OpenStreetMap (org.json vient d'Android : Robolectric). */
@RunWith(RobolectricTestRunner::class)
class NominatimTest {
    @Test fun villeAmericaineAvecSonEtat() {
        val json = """{"address":{"city":"Key West","county":"Monroe County","state":"Floride","country":"États-Unis","country_code":"us"}}"""
        assertEquals("Key West, Floride", Captions.placeFromNominatim(json))
    }

    @Test fun villageFrancaisAvecSonDepartement() {
        val json = """{"address":{"village":"Bonifacio","county":"Corse-du-Sud","state":"Corse","country":"France","country_code":"fr"}}"""
        assertEquals("Bonifacio, Corse-du-Sud", Captions.placeFromNominatim(json))
    }

    @Test fun ailleursLePays() {
        val json = """{"address":{"town":"Sintra","state":"Lisboa","country":"Portugal","country_code":"pt"}}"""
        assertEquals("Sintra, Portugal", Captions.placeFromNominatim(json))
    }

    @Test fun pasDAdresse() {
        assertNull(Captions.placeFromNominatim("""{"error":"Unable to geocode"}"""))
        assertNull(Captions.placeFromNominatim("pas du json"))
    }
}
