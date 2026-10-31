package com.maxime.photodream

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Charge une seule photo en détaillant chaque étape (liste, lecture, décodage…) avec sa durée,
 * pour comprendre où le diaporama bloque. [onStep] reçoit le compte rendu au fur et à mesure.
 */
class PhotoDiagnostic(
    private val prefs: Prefs,
    private val cacheDir: File,
    private val sourceFactory: (Prefs) -> PhotoSource? = PhotoRepository::smbSource,
) {
    class Result(val report: String, val bitmap: Bitmap?)

    suspend fun run(onStep: (String) -> Unit = {}): Result = withContext(Dispatchers.IO) {
        val lines = ArrayList<String>()
        var started = SystemClock.elapsedRealtime()
        fun elapsed(): String {
            val now = SystemClock.elapsedRealtime()
            val s = "%.1f s".format((now - started) / 1000f)
            started = now
            return s
        }
        fun step(text: String) {
            lines += text
            onStep(lines.joinToString("\n"))
        }

        val src = sourceFactory(prefs) ?: return@withContext Result("Aucun dossier réseau configuré.", null)
        var current = "connexion"
        try {
            step("1. Lecture du dossier…")
            current = "lecture du dossier"
            val list = src.listPhotos(prefs.recursive)
            lines[lines.lastIndex] = "1. Dossier lu : ${list.size} photos (${elapsed()})"
            if (list.isEmpty()) {
                step("Aucune photo trouvée.")
                return@withContext Result(lines.joinToString("\n"), null)
            }
            val photo = list.random()
            step("2. Photo choisie : ${photo.path} (${photo.size / 1024} Ko)")

            step("3. Téléchargement…")
            current = "téléchargement"
            val bytes = src.read(photo.path)
            lines[lines.lastIndex] = "3. Téléchargée : ${bytes.size / 1024} Ko (${elapsed()})"
            onStep(lines.joinToString("\n"))

            current = "lecture des dimensions"
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth > 0) {
                step("4. Image ${bounds.outWidth} × ${bounds.outHeight}, type ${bounds.outMimeType}")
            } else {
                val head = bytes.take(12).joinToString(" ") { "%02X".format(it) }
                step("4. Image non reconnue par Android (début du fichier : $head)")
            }

            current = "réduction"
            val out = File(cacheDir, "diagnostic.jpg").also { it.parentFile?.mkdirs() }
            val ok = Images.downscaleToJpeg(bytes, out)
            step(if (ok) "5. Réduite au plein écran : ${out.length() / 1024} Ko (${elapsed()})" else "5. Échec de la réduction (${elapsed()})")
            if (!ok) return@withContext Result(lines.joinToString("\n"), null)

            current = "affichage"
            val bmp = Images.decode(out, 1920)
            step(if (bmp != null) "6. Prête à afficher : ${bmp.width} × ${bmp.height} ✔" else "6. Impossible de relire la photo réduite")
            Result(lines.joinToString("\n"), bmp)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // Throwable : on veut voir aussi les erreurs graves (mémoire, classe manquante…)
            step("Erreur pendant : $current (${elapsed()})")
            step("${t.javaClass.simpleName} : ${t.message}")
            var cause = t.cause
            var depth = 0
            while (cause != null && depth < 3) {
                step("cause : ${cause.javaClass.simpleName} : ${cause.message}")
                cause = cause.cause
                depth++
            }
            Result(lines.joinToString("\n"), null)
        } finally {
            runCatching { src.close() }
        }
    }
}
