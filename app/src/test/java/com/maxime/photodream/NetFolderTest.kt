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
}
