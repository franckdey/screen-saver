package com.maxime.photodream

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class MultiPhotoSourceTest {

    /** Un faux dossier : des noms de fichiers dont le contenu est le nom du dossier + le chemin. */
    private class Fake(val name: String, val paths: List<String>, val fail: Boolean = false) : PhotoSource {
        var closed = false
        override suspend fun listPhotos(recursive: Boolean, limit: Int): List<RemotePhoto> {
            if (fail) throw IOException("$name injoignable")
            return paths.map { RemotePhoto(it, 10L, 1L) }
        }
        override suspend fun read(path: String) = "$name:$path".toByteArray()
        override fun folderLabel(photo: RemotePhoto) = "$name/" + photo.path.substringBeforeLast('\\', "")
        override fun close() { closed = true }
    }

    @Test
    fun `les photos des deux dossiers sont reunies et relues au bon endroit`() = runBlocking {
        val a = Fake("A", listOf("Vacances\\IMG_1.jpg"))
        val b = Fake("B", listOf("Noel\\IMG_1.jpg"))
        val multi = MultiPhotoSource.of(listOf("smb://nas/a" to a, "smb://nas/b" to b))
        val list = multi.listPhotos(true)
        assertEquals(2, list.size)
        // le premier dossier garde ses chemins tels quels
        assertTrue(list.any { it.path == "Vacances\\IMG_1.jpg" })
        val fromB = list.first { it.path != "Vacances\\IMG_1.jpg" }
        assertTrue(fromB.path.startsWith("@") && fromB.path.endsWith("@Noel\\IMG_1.jpg"))
        assertEquals("A:Vacances\\IMG_1.jpg", String(multi.read("Vacances\\IMG_1.jpg")))
        assertEquals("B:Noel\\IMG_1.jpg", String(multi.read(fromB.path)))
        assertEquals("B/Noel", multi.folderLabel(fromB))
        assertEquals("smb://nas/b" to "Noel\\IMG_1.jpg", multi.locate(fromB.path))
        multi.close()
        assertTrue(a.closed && b.closed)
    }

    @Test
    fun `un dossier injoignable n'empeche pas de lire les autres`() = runBlocking {
        val multi = MultiPhotoSource.of(
            listOf("smb://nas/a" to Fake("A", emptyList(), fail = true), "smb://nas/b" to Fake("B", listOf("x.jpg"))),
        )
        assertEquals(1, multi.listPhotos(true).size)
    }

    @Test
    fun `si aucun dossier ne repond l'erreur remonte`() = runBlocking {
        val multi = MultiPhotoSource.of(
            listOf("smb://nas/a" to Fake("A", emptyList(), fail = true), "smb://nas/b" to Fake("B", emptyList(), fail = true)),
        )
        try {
            multi.listPhotos(true)
            fail("une erreur était attendue")
        } catch (e: IOException) {
            assertEquals("A injoignable", e.message)
        }
    }

    @Test
    fun `l'empreinte d'une adresse est stable`() {
        assertEquals(MultiPhotoSource.tagOf("smb://NAS/b "), MultiPhotoSource.tagOf("smb://nas/b"))
        assertEquals(8, MultiPhotoSource.tagOf("smb://nas/b").length)
    }
}
