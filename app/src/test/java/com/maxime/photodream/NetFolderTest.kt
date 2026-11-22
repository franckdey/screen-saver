package com.maxime.photodream

import org.junit.Assert.assertEquals
import org.junit.Test

class NetFolderTest {

    @Test
    fun `les coches sont gardees d'un lancement a l'autre`() {
        val folders = listOf(NetFolder("smb://nas/a"), NetFolder("smb://nas/b", enabled = false), NetFolder("  "))
        val text = NetFolder.serialize(folders)
        assertEquals("smb://nas/a\n#smb://nas/b", text)
        assertEquals(folders.take(2), NetFolder.parse(text))
    }

    @Test
    fun `seuls les dossiers coches sont lus, le principal en premier`() {
        val extras = listOf(NetFolder("smb://nas/b"), NetFolder("smb://nas/c", enabled = false), NetFolder("smb://nas/a"))
        assertEquals(
            listOf("smb://nas/a" to true, "smb://nas/b" to false),
            NetFolder.enabled("smb://nas/a", true, extras),
        )
        // principal décoché : seuls les autres dossiers cochés restent
        assertEquals(
            listOf("smb://nas/b" to false, "smb://nas/a" to false),
            NetFolder.enabled("smb://nas/a", false, extras),
        )
        assertEquals(emptyList<Pair<String, Boolean>>(), NetFolder.enabled("", true, emptyList()))
    }

    @Test
    fun `un autre NAS garde ses propres identifiants`() {
        val folders = listOf(
            NetFolder("smb://nas2/photos", user = "papa", password = "mot de\tpasse%é"),
            NetFolder("smb://nas2/vieux", enabled = false, user = "papa", password = ""),
            NetFolder("smb://nas1/b"),
        )
        val text = NetFolder.serialize(folders)
        assertEquals(3, text.lines().size)
        assertEquals("smb://nas1/b", text.lines()[2])
        assertEquals(folders, NetFolder.parse(text))
    }

    @Test
    fun `sans identifiants propres, ceux du dossier principal servent`() {
        val main = Login("maxime", "secret", "WORKGROUP")
        val extras = listOf(NetFolder("smb://nas1/b"), NetFolder("smb://nas2/photos", user = "papa", password = "1234"))
        assertEquals(main, NetFolder.login("smb://nas1/a", "smb://nas1/a", main, extras))
        assertEquals(main, NetFolder.login("smb://nas1/b", "smb://nas1/a", main, extras))
        assertEquals(Login("papa", "1234", ""), NetFolder.login(" smb://nas2/photos ", "smb://nas1/a", main, extras))
        // même adresse que le principal : le principal l'emporte, comme pour la liste des dossiers
        assertEquals(main, NetFolder.login("smb://nas2/photos", "smb://nas2/photos", main, extras))
    }
}
