package com.maxime.photodream

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
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.net.UnknownHostException

/** Tests de la mise à jour intégrée, contre un petit serveur HTTP local qui imite GitHub. */
class UpdaterTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var server: ServerSocket
    private lateinit var base: String
    private var versionJson = """{"versionCode": 7, "versionName": "1.7"}"""
    private val apkBytes = ByteArray(300_000) { (it % 251).toByte() }

    /**
     * Mini serveur HTTP (java.net seulement : les tests Android ne voient pas com.sun.net.httpserver).
     * Comme GitHub, /latest/<fichier> redirige vers /files/<fichier>.
     */
    @Before fun start() {
        server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        base = "http://127.0.0.1:${server.localPort}/latest/"
        Thread {
            while (true) {
                val socket = try { server.accept() } catch (e: SocketException) { break }
                socket.use { s ->
                    val reader = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                    val path = reader.readLine()?.split(" ")?.getOrNull(1) ?: return@use
                    while (!reader.readLine().isNullOrEmpty()) { /* en-têtes ignorés */ }
                    val out = s.getOutputStream()
                    fun reply(status: String, body: ByteArray = ByteArray(0), extra: String = "") {
                        out.write("HTTP/1.1 $status\r\nContent-Length: ${body.size}\r\n${extra}Connection: close\r\n\r\n".toByteArray())
                        out.write(body)
                        out.flush()
                    }
                    when {
                        path.startsWith("/latest/") -> reply("302 Found", extra = "Location: /files/${path.substringAfterLast('/')}\r\n")
                        path == "/files/version.json" -> reply("200 OK", versionJson.toByteArray())
                        path == "/files/PhotoDream.apk" -> reply("200 OK", apkBytes)
                        else -> reply("404 Not Found")
                    }
                }
            }
        }.apply { isDaemon = true }.start()
    }

    @After fun stop() = server.close()

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
        val error = runCatching { Updater("http://127.0.0.1:${server.localPort}/nope/", 1).check() }.exceptionOrNull()
        assertTrue(error is IOException)
        assertTrue(error!!.message!!.contains("404"))
    }

    @Test fun describesNetworkErrors() {
        assertEquals("pas d'accès à Internet (GitHub injoignable)", Updater.describe(UnknownHostException("github.com")))
        assertEquals("boom", Updater.describe(IOException("boom")))
    }
}
