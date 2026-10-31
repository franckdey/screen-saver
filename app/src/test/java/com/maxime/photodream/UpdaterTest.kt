package com.maxime.photodream

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.UnknownHostException

/** Tests de la mise à jour intégrée, contre un petit serveur HTTP local qui imite GitHub. */
class UpdaterTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var server: HttpServer
    private lateinit var base: String
    private var versionJson = """{"versionCode": 7, "versionName": "1.7"}"""
    private val apkBytes = ByteArray(300_000) { (it % 251).toByte() }

    @Before fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        // Comme GitHub : releases/latest/download/... redirige vers le vrai fichier.
        server.createContext("/latest/") { ex ->
            ex.responseHeaders.add("Location", "/files/" + ex.requestURI.path.substringAfterLast('/'))
            ex.sendResponseHeaders(302, -1)
            ex.close()
        }
        server.createContext("/files/version.json") { ex ->
            val body = versionJson.toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.createContext("/files/PhotoDream.apk") { ex ->
            ex.sendResponseHeaders(200, apkBytes.size.toLong())
            ex.responseBody.use { it.write(apkBytes) }
        }
        server.start()
        base = "http://127.0.0.1:${server.address.port}/latest/"
    }

    @After fun stop() = server.stop(0)

    @Test fun parsesVersionFile() {
        assertEquals(UpdateInfo(12, "1.12"), Updater.parse("""{"versionCode": 12, "versionName": "1.12"}"""))
        assertEquals(UpdateInfo(5, "1.5"), Updater.parse("{\n  \"versionName\":\"1.5\",\n  \"versionCode\":5\n}\n"))
        assertEquals(UpdateInfo(3, "build 3"), Updater.parse("""{"versionCode":3}"""))
    }

    @Test(expected = IOException::class) fun rejectsGarbage() {
        Updater.parse("<html>Not Found</html>")
    }

    @Test fun reportsNewerVersionThroughRedirect() {
        assertEquals(UpdateInfo(7, "1.7"), Updater(base, currentVersion = 6).check())
    }

    @Test fun ignoresSameOrOlderVersion() {
        assertNull(Updater(base, currentVersion = 7).check())
        assertNull(Updater(base, currentVersion = 9).check())
    }

    @Test fun downloadsApkWithProgress() {
        val progress = mutableListOf<Int>()
        val dest = File(tmp.root, "updates/PhotoDream.apk")
        Updater(base, 1).download(dest) { progress += it }
        assertArrayEquals(apkBytes, dest.readBytes())
        assertEquals(100, progress.last())
        assertEquals(progress.sorted(), progress)
        assertFalse(File(dest.path + ".part").exists())
    }

    @Test fun replacesPreviousDownload() {
        val dest = tmp.newFile("PhotoDream.apk").apply { writeText("ancienne version") }
        Updater(base, 1).download(dest)
        assertArrayEquals(apkBytes, dest.readBytes())
    }

    @Test fun httpErrorIsReported() {
        val error = runCatching { Updater("http://127.0.0.1:${server.address.port}/nope/", 1).check() }.exceptionOrNull()
        assertTrue(error is IOException)
        assertTrue(error!!.message!!.contains("404"))
    }

    @Test fun describesNetworkErrors() {
        assertEquals("pas d'accès à Internet (GitHub injoignable)", Updater.describe(UnknownHostException("github.com")))
        assertEquals("boom", Updater.describe(IOException("boom")))
    }
}
