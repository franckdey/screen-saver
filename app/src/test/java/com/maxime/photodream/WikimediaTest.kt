package com.maxime.photodream

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** Photos de paysages de Wikimedia Commons (org.json vient d'Android : Robolectric). */
@RunWith(RobolectricTestRunner::class)
class WikimediaTest {
    @get:Rule val tmp = TemporaryFolder()

    private val page1 = """{"continue":{"cmcontinue":"file|suite","continue":"-||"},"query":{"categorymembers":[
        {"pageid":1,"ns":6,"title":"File:Lac_d'Annecy.jpg"},
        {"pageid":2,"ns":6,"title":"File:Carte.svg"},
        {"pageid":3,"ns":6,"title":"File:Glacier.JPEG"}]}}"""
    private val page2 = """{"query":{"categorymembers":[{"pageid":4,"ns":6,"title":"File:Dune.jpg"}]}}"""
    private val info = """{"query":{"pages":[{"pageid":1,"title":"File:Lac_d'Annecy.jpg","imageinfo":[{
        "url":"https://upload.wikimedia.org/original.jpg","thumburl":"https://upload.wikimedia.org/1920px.jpg",
        "extmetadata":{"Artist":{"value":"<a href=\"//commons.wikimedia.org/wiki/User:Marie\">Marie &amp; Paul</a>"},
        "LicenseShortName":{"value":"CC BY-SA 4.0"},"ObjectName":{"value":"Lac d'Annecy au matin"}}}]}]}}"""

    @Test fun listeEtSuite() {
        val m = Wikimedia.parseMembers(page1)
        assertEquals(listOf("File:Lac_d'Annecy.jpg", "File:Carte.svg", "File:Glacier.JPEG"), m.titles)
        assertEquals("file|suite", m.next)
        assertNull(Wikimedia.parseMembers(page2).next)
    }

    @Test fun seulementDesPhotos() {
        assertTrue(Wikimedia.isPhoto("File:Glacier.JPEG"))
        assertFalse(Wikimedia.isPhoto("File:Carte.svg"))
        assertFalse(Wikimedia.isPhoto("File:Film.webm"))
    }

    @Test fun titreDuFichier() {
        assertEquals("Lac d'Annecy", Wikimedia.titleFromFile("File:Lac_d'Annecy.jpg"))
    }

    @Test fun auteurEtLicenceSansHtml() {
        val i = Wikimedia.parseInfo(info)!!
        assertEquals("https://upload.wikimedia.org/1920px.jpg", i.url)
        assertEquals("Lac d'Annecy au matin", i.title)
        assertEquals("© Marie & Paul, CC BY-SA 4.0", i.credit)
    }

    @Test fun reponseSansImage() {
        assertNull(Wikimedia.parseInfo("""{"query":{"pages":[{"title":"File:X.jpg","missing":true}]}}"""))
    }

    @Test fun lectureAvecCreditGarde() = runBlocking {
        val credits = File(tmp.root, "credits.txt")
        val asked = mutableListOf<String>()
        val src = WikimediaSource(credits) { url ->
            asked += url
            when {
                "cmcontinue=" in url -> page2.toByteArray()
                "categorymembers" in url -> page1.toByteArray()
                "imageinfo" in url -> info.toByteArray()
                else -> byteArrayOf(1, 2, 3)
            }
        }
        val photos = src.listPhotos(recursive = false)
        // les deux catégories renvoient ici la même liste : pas de doublon, pas de dessin
        assertEquals(listOf("File:Lac_d'Annecy.jpg", "File:Glacier.JPEG", "File:Dune.jpg"), photos.map { it.path })
        val photo = photos[0]
        assertEquals("Lac d'Annecy", src.displayName(photo))
        assertNull(src.credit(photo))
        assertEquals(3, src.read(photo.path).size)
        assertEquals("Lac d'Annecy au matin", src.displayName(photo))
        assertEquals("© Marie & Paul, CC BY-SA 4.0", src.credit(photo))
        // relu depuis le fichier par une nouvelle source (après un redémarrage)
        assertEquals("© Marie & Paul, CC BY-SA 4.0", WikimediaSource(credits) { error("pas de réseau") }.credit(photo))
    }
}
