package com.maxime.photodream

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.util.EnumSet

/**
 * Tests contre un vrai serveur Samba. Ils ne tournent que si SMB_TEST_HOST est défini :
 * dans GitHub Actions, le workflow « Tests » démarre un serveur Samba dans Docker.
 */
class SmbIntegrationTest {
    private val host = System.getenv("SMB_TEST_HOST").orEmpty()
    private val user = System.getenv("SMB_TEST_USER") ?: "test"
    private val password = System.getenv("SMB_TEST_PASSWORD") ?: "secret"
    private val share = System.getenv("SMB_TEST_SHARE") ?: "photos"

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 4, 5, 0xFF.toByte(), 0xD9.toByte())

    @Before
    fun prepareShare() {
        assumeTrue("SMB_TEST_HOST non défini : tests Samba ignorés", host.isNotEmpty())
        SMBClient().use { client ->
            client.connect(host).use { conn ->
                val session = conn.authenticate(AuthenticationContext(user, password.toCharArray(), null))
                (session.connectShare(share) as DiskShare).use { disk ->
                    for (dir in listOf("Vacances", "Vacances\\2019 Corse", "Vacances\\@eaDir", "Vacances\\.cache")) {
                        if (!disk.folderExists(dir)) disk.mkdir(dir)
                    }
                    for (path in listOf(
                        "Vacances\\plage.jpg", "Vacances\\2019 Corse\\IMG_1.JPG", "Vacances\\2019 Corse\\film.mp4",
                        "Vacances\\@eaDir\\miniature.jpg", "Vacances\\.cache\\cache.jpg", "Vacances\\notes.txt",
                    )) write(disk, path, jpeg)
                }
            }
        }
    }

    private fun write(disk: DiskShare, path: String, bytes: ByteArray) {
        disk.openFile(
            path, EnumSet.of(AccessMask.GENERIC_WRITE), null, SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OVERWRITE_IF, null,
        ).use { f -> f.outputStream.use { it.write(bytes) } }
    }

    private fun source(folder: String = "Vacances", pass: String = password, shareName: String = share) =
        SmbPhotoSource(SmbLocation(host, shareName, folder), user, pass, "", sdkInt = 34)

    @Test
    fun `liste les photos des sous-dossiers en ignorant le reste`() = runTest {
        source().use { src ->
            val paths = src.listPhotos(recursive = true).map { it.path }.sorted()
            assertEquals(listOf("Vacances\\2019 Corse\\IMG_1.JPG", "Vacances\\plage.jpg"), paths)
        }
    }

    @Test
    fun `sans sous-dossiers seulement le dossier choisi`() = runTest {
        source().use { src ->
            assertEquals(listOf("Vacances\\plage.jpg"), src.listPhotos(recursive = false).map { it.path })
        }
    }

    @Test
    fun `lit le contenu exact d'une photo`() = runTest {
        source().use { src ->
            assertArrayEquals(jpeg, src.read("Vacances\\2019 Corse\\IMG_1.JPG"))
            val photo = src.listPhotos(true).first { it.path.endsWith("IMG_1.JPG") }
            assertEquals(jpeg.size.toLong(), photo.size)
            assertEquals("2019 Corse", src.folderLabel(photo))
        }
    }

    @Test
    fun `lit une grosse photo par blocs sans rien perdre`() = runTest {
        // 6 Mo, comme une photo d'appareil : bien plus que les blocs de lecture de 64 Ko
        val big = ByteArray(6 * 1024 * 1024 + 123) { (it * 31 % 251).toByte() }
        SMBClient().use { client ->
            client.connect(host).use { conn ->
                val session = conn.authenticate(AuthenticationContext(user, password.toCharArray(), null))
                (session.connectShare(share) as DiskShare).use { write(it, "grande.jpg", big) }
            }
        }
        // à la racine du partage, hors du dossier « Vacances » listé par les autres tests
        source().use { src -> assertArrayEquals(big, src.read("grande.jpg")) }
        // le mode compatible (SMB 2) lit aussi correctement
        SmbPhotoSource(SmbLocation(host, share, "Vacances"), user, password, "", sdkInt = 34, compat = true).use { src ->
            assertArrayEquals(big, src.read("grande.jpg"))
        }
    }

    @Test
    fun `mauvais mot de passe explique clairement`() = runTest {
        source(pass = "faux").use { src ->
            val error = runCatching { src.listPhotos(true) }.exceptionOrNull()!!
            assertEquals("Identifiant ou mot de passe refusé par le NAS", SmbPhotoSource.describe(error))
        }
    }

    @Test
    fun `partage inexistant explique clairement`() = runTest {
        source(shareName = "inexistant").use { src ->
            val error = runCatching { src.listPhotos(true) }.exceptionOrNull()!!
            assertTrue(SmbPhotoSource.describe(error).startsWith("Partage introuvable"))
        }
    }

    @Test
    fun `dossier inexistant explique clairement`() = runTest {
        source(folder = "Inexistant").use { src ->
            val error = runCatching { src.listPhotos(true) }.exceptionOrNull()!!
            assertEquals("Dossier introuvable sur le partage", SmbPhotoSource.describe(error))
        }
    }
}
