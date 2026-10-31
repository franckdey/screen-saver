package com.maxime.photodream

import android.content.Context
import org.robolectric.RuntimeEnvironment
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.net.ConnectException

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoRepositoryTest {

    /** Un faux NAS en mémoire, qui compte les téléchargements. */
    private class FakeSource(
        val files: Map<String, ByteArray>,
        var failListing: Exception? = null,
    ) : PhotoSource {
        val reads = mutableMapOf<String, Int>()

        override suspend fun listPhotos(recursive: Boolean, limit: Int): List<RemotePhoto> {
            failListing?.let { throw it }
            return files.keys.map { RemotePhoto(it, files.getValue(it).size.toLong(), 1L) }
        }

        override suspend fun read(path: String): ByteArray {
            reads[path] = (reads[path] ?: 0) + 1
            return files.getValue(path)
        }

        override fun folderLabel(photo: RemotePhoto) = photo.path.substringBeforeLast('\\', "")
        override fun close() {}
    }

    private val context: Context = RuntimeEnvironment.getApplication()
    private lateinit var prefs: Prefs
    private val cacheDir get() = File(context.cacheDir, "photos")

    @Before
    fun setUp() {
        prefs = Prefs(context)
        cacheDir.deleteRecursively()
    }

    @After
    fun tearDown() {
        cacheDir.deleteRecursively()
    }

    private fun photos(count: Int) = (1..count).associate { "Vacances\\Corse\\IMG_$it.jpg" to TestImages.jpeg(640, 480) }

    private fun repo(source: PhotoSource?) = PhotoRepository(context, prefs) { source }

    @Test
    fun `aucun dossier configure`() = runTest {
        val r = repo(null)
        assertEquals(0, r.load())
        assertTrue(r.lastError!!.startsWith("Aucun dossier réseau configuré"))
        assertNull(r.nextPhoto(1))
    }

    @Test
    fun `dossier vide`() = runTest {
        val r = repo(FakeSource(emptyMap()))
        assertEquals(0, r.load())
        assertEquals("Aucune photo trouvée dans ce dossier", r.lastError)
    }

    @Test
    fun `photos chargees avec leur dossier et leur vignette`() = runTest {
        val r = repo(FakeSource(photos(3)))
        assertEquals(3, r.load())
        val p = r.nextPhoto(1)!!
        assertEquals("Vacances\\Corse", p.label)
        assertEquals(640, p.full.width)
        assertEquals(320 to 240, p.tile.width to p.tile.height)
    }

    @Test
    fun `toutes les photos passent avant qu'une revienne`() = runTest {
        val source = FakeSource(photos(6))
        val r = repo(source)
        r.load()
        repeat(6) { assertNotNull(r.nextPhoto(0)) }
        assertEquals(6, source.reads.size)
        assertTrue(source.reads.values.all { it == 1 })
    }

    @Test
    fun `photo deja en cache pas retelechargee`() = runTest {
        val source = FakeSource(photos(2))
        val r = repo(source)
        r.load()
        repeat(6) { r.nextPhoto(0) }
        assertTrue(source.reads.values.all { it == 1 })
    }

    @Test
    fun `fichier abime saute sans bloquer le diaporama`() = runTest {
        val files = photos(2) + ("Vacances\\abime.jpg" to "pas une photo".toByteArray())
        val r = repo(FakeSource(files))
        r.load()
        repeat(4) { assertNotNull(r.nextPhoto(0)) }
    }

    @Test
    fun `format illisible explique avec le nom du fichier`() = runTest {
        val r = repo(FakeSource(mapOf("Vacances\\IMG_0001.xyz.jpg" to "pas une photo".toByteArray())))
        r.load()
        assertNull(r.nextPhoto(0))
        assertEquals("Format de photo non reconnu par l'appareil : IMG_0001.xyz.jpg", r.lastError)
    }

    @Test
    fun `fichier vide explique`() = runTest {
        val r = repo(FakeSource(mapOf("Vacances\\vide.jpg" to ByteArray(0))))
        r.load()
        assertNull(r.nextPhoto(0))
        assertEquals("Fichier vide : vide.jpg", r.lastError)
    }

    @Test
    fun `NAS injoignable, les photos deja vues restent disponibles`() = runTest {
        repo(FakeSource(photos(3))).apply {
            load()
            repeat(3) { nextPhoto(0) }
        }
        val offline = repo(FakeSource(photos(3), failListing = ConnectException("refused")))
        assertEquals(3, offline.load())
        assertTrue(offline.offline)
        assertTrue(offline.lastError!!.startsWith("NAS injoignable"))
        val p = offline.nextPhoto(0)
        assertNotNull(p)
        assertEquals("", p!!.label)
    }

    @Test
    fun `NAS injoignable et cache vide`() = runTest {
        val r = repo(FakeSource(photos(3), failListing = ConnectException("refused")))
        assertEquals(0, r.load())
        assertFalse(r.offline)
    }

    @Test
    fun `cache plein, les plus anciennes partent mais pas celle affichee`() = runTest {
        prefs.cacheMb = 0 // on force le nettoyage à chaque passage
        val r = repo(FakeSource(photos(25)))
        r.load()
        repeat(25) { assertNotNull("photo ${it + 1}", r.nextPhoto(0)) }
        assertTrue(cacheDir.listFiles()!!.size < 25)
    }

    @Test
    fun `vider le cache`() = runTest {
        val r = repo(FakeSource(photos(2)))
        r.load()
        repeat(2) { r.nextPhoto(0) }
        assertTrue(cacheDir.listFiles()!!.isNotEmpty())
        r.clearCache()
        assertTrue(cacheDir.listFiles()!!.isEmpty())
    }

    @Test
    fun `vignettes de la mosaique`() = runTest {
        val r = repo(FakeSource(photos(2)))
        r.load()
        val tile = r.nextTile(1)!!
        assertEquals(320 to 240, tile.width to tile.height)
    }
}
