package com.maxime.photodream

import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMB2MessageCommandCode
import com.hierynomus.mssmb2.SMBApiException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class SmbHelpersTest {

    // --- Formats reconnus ---

    @Test
    fun `formats photo courants reconnus quelle que soit la casse`() {
        for (name in listOf("a.jpg", "b.JPEG", "c.png", "d.webp", "e.Bmp")) {
            assertTrue(name, SmbPhotoSource.isImage(name, sdkInt = 26))
        }
    }

    @Test
    fun `HEIC seulement a partir d'Android 9`() {
        assertFalse(SmbPhotoSource.isImage("iphone.HEIC", sdkInt = 27))
        assertTrue(SmbPhotoSource.isImage("iphone.HEIC", sdkInt = 28))
        assertTrue(SmbPhotoSource.isImage("x.heif", sdkInt = 34))
    }

    @Test
    fun `videos, textes et fichiers sans extension ignores`() {
        for (name in listOf("film.mp4", "notes.txt", "Thumbs.db", "jpg", "photo.jpg.part")) {
            assertFalse(name, SmbPhotoSource.isImage(name, sdkInt = 34))
        }
    }

    // --- Nom de dossier affiché ---

    @Test
    fun `nom du dossier relatif au dossier choisi`() {
        assertEquals("2019 Corse › Plage", SmbPhotoSource.folderLabel("Vacances", "Vacances\\2019 Corse\\Plage\\IMG_1.jpg"))
    }

    @Test
    fun `photo directement dans le dossier choisi sans libelle`() {
        assertEquals("", SmbPhotoSource.folderLabel("Vacances", "Vacances\\IMG_1.jpg"))
    }

    @Test
    fun `dossier choisi a la racine du partage`() {
        assertEquals("Noel", SmbPhotoSource.folderLabel("", "Noel\\IMG_1.jpg"))
        assertEquals("", SmbPhotoSource.folderLabel("", "IMG_1.jpg"))
    }

    // --- Messages d'erreur ---

    private fun smb(status: NtStatus) = SMBApiException(status.value, SMB2MessageCommandCode.SMB2_CREATE, null)

    @Test
    fun `mot de passe refuse`() {
        assertEquals("Identifiant ou mot de passe refusé par le NAS", SmbPhotoSource.describe(smb(NtStatus.STATUS_LOGON_FAILURE)))
    }

    @Test
    fun `partage et dossier introuvables`() {
        assertTrue(SmbPhotoSource.describe(smb(NtStatus.STATUS_BAD_NETWORK_NAME)).startsWith("Partage introuvable"))
        assertEquals("Dossier introuvable sur le partage", SmbPhotoSource.describe(smb(NtStatus.STATUS_OBJECT_PATH_NOT_FOUND)))
        assertEquals("Dossier introuvable sur le partage", SmbPhotoSource.describe(smb(NtStatus.STATUS_OBJECT_NAME_NOT_FOUND)))
    }

    @Test
    fun `acces refuse`() {
        assertTrue(SmbPhotoSource.describe(smb(NtStatus.STATUS_ACCESS_DENIED)).startsWith("Accès refusé"))
    }

    @Test
    fun `erreur reseau enveloppee reconnue dans la chaine des causes`() {
        val wrapped = IOException("transport", ConnectException("Connection refused"))
        assertTrue(SmbPhotoSource.describe(wrapped).startsWith("NAS injoignable"))
        assertTrue(SmbPhotoSource.describe(SocketTimeoutException()).startsWith("NAS injoignable"))
        assertTrue(SmbPhotoSource.describe(IOException(UnknownHostException("nas"))).startsWith("Adresse du NAS inconnue"))
    }

    @Test
    fun `erreur inconnue garde son message`() {
        assertEquals("boum", SmbPhotoSource.describe(IllegalStateException("boum")))
    }
}
