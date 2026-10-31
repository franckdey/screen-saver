package com.maxime.photodream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CaptionTest {

    @get:Rule val tmp = TemporaryFolder()

    private val aug2026 = PhotoMeta.parseExifDate("2026:08:18 13:30:38")!!

    @Test fun lieuEtDate() {
        assertEquals("Key West, Floride · août 2026", Captions.format("Key West, Floride", aug2026, "Plage du matin"))
    }

    @Test fun sansGpsLeNomDeLaPhotoPrendLaPlace() {
        assertEquals("Plage du matin · août 2026", Captions.format(null, aug2026, "Plage du matin"))
        assertEquals("Plage du matin", Captions.format(null, null, "Plage du matin"))
        assertEquals("août 2026", Captions.format(null, aug2026, ""))
        assertEquals("", Captions.format(null, null, ""))
    }

    @Test fun dateExifIllisible() {
        assertNull(PhotoMeta.parseExifDate("pas une date"))
        assertNull(PhotoMeta.parseExifDate("    :  :     :  :  "))
    }

    @Test fun metaSeRelit() {
        val meta = PhotoMeta(24.5551, -81.78, aug2026)
        assertEquals(meta, PhotoMeta.parse(meta.serialize()))
        assertEquals(PhotoMeta.EMPTY, PhotoMeta.parse(PhotoMeta.EMPTY.serialize()))
        assertNull(PhotoMeta.parse("n'importe quoi"))
    }

    @Test fun chaqueLieuNEstDemandeQuUneFois() {
        val file = File(tmp.root, "lieux.txt")
        var calls = 0
        val lookup = { _: Double, _: Double -> calls++; "Key West, Floride" }
        val places = PlaceNames(file, lookup)
        assertEquals("Key West, Floride", places.name(24.55512, -81.78001))
        assertEquals("Key West, Floride", places.name(24.55534, -81.78022)) // à quelques mètres
        assertEquals(1, calls)
        // relu depuis le fichier par une nouvelle instance (nouveau lancement)
        assertEquals("Key West, Floride", PlaceNames(file, lookup).name(24.5551, -81.7800))
        assertEquals(1, calls)
    }

    @Test fun horsLigneOnReessaieplusTard() {
        var online = false
        val places = PlaceNames(File(tmp.root, "lieux.txt")) { _, _ ->
            if (!online) throw java.io.IOException("pas de réseau") else "Paris, Paris"
        }
        assertNull(places.name(48.8566, 2.3522))
        online = true
        assertEquals("Paris, Paris", places.name(48.8566, 2.3522))
    }

    @Test fun endroitSansNomMemorise() {
        var calls = 0
        val places = PlaceNames(File(tmp.root, "lieux.txt")) { _, _ -> calls++; null } // en pleine mer
        assertNull(places.name(30.0, -40.0))
        assertNull(places.name(30.0, -40.0))
        assertEquals(1, calls)
    }
}
