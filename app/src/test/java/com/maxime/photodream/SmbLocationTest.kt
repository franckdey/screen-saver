package com.maxime.photodream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmbLocationTest {
    @Test
    fun `adresse smb complete`() {
        assertEquals(
            SmbLocation("192.168.1.20", "photo", "Vacances\\2019"),
            SmbLocation.parse("smb://192.168.1.20/photo/Vacances/2019"),
        )
    }

    @Test
    fun `chemin Windows avec antislashs`() {
        assertEquals(SmbLocation("NAS", "photo", "Vacances"), SmbLocation.parse("\\\\NAS\\photo\\Vacances"))
    }

    @Test
    fun `partage seul sans dossier`() {
        assertEquals(SmbLocation("nas", "photo", ""), SmbLocation.parse("smb://nas/photo"))
    }

    @Test
    fun `espaces et barres en trop sont ignores`() {
        assertEquals(SmbLocation("nas", "photo", "A\\B"), SmbLocation.parse("  SMB://nas//photo/A/B/  "))
    }

    @Test
    fun `sans partage l'adresse est refusee`() {
        assertNull(SmbLocation.parse("smb://192.168.1.20"))
        assertNull(SmbLocation.parse(""))
        assertNull(SmbLocation.parse("   "))
    }
}
