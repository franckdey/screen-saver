package com.maxime.photodream

import kotlin.math.sqrt

/**
 * Pour que deux photos qui se suivent soient vraiment différentes, en ordre aléatoire :
 * - pas deux photos du même groupe à la suite (même dossier du NAS, même sujet pour le web,
 *   par exemple « Lake Bled 1 » et « Lake Bled from the castle ») ;
 * - pas deux photos aux couleurs presque identiques (deux couchers de soleil sur la mer…),
 *   d'après une empreinte de couleurs : la couleur moyenne de 4 × 3 cases de la photo.
 * Sans dessin, pour pouvoir être testé.
 */
object Variety {
    /** Combien de photos précédentes ne doivent pas être du même groupe. */
    const val WINDOW = 3
    /** Jusqu'où on cherche plus loin une photo d'un autre groupe. */
    private const val LOOKAHEAD = 60
    /** En dessous de cet écart moyen de couleur (0 à 1), deux photos se ressemblent trop. */
    const val SIMILAR = 0.10f

    const val PRINT_W = 4
    const val PRINT_H = 3

    private val STOP = setOf(
        "the", "and", "from", "with", "view", "near", "into", "over", "des", "les", "une", "sur", "vue",
        "del", "della", "von", "der", "die", "das", "und", "pic", "photo", "image",
    )

    /** Le groupe d'une photo : son dossier, ou pour une photo du web les premiers mots de son titre. */
    fun group(path: String): String {
        var tag = ""
        var inner = path
        if (path.startsWith("@")) {
            val end = path.indexOf('@', 1)
            if (end > 0) {
                tag = path.substring(1, end)
                inner = path.substring(end + 1)
            }
        }
        if (inner.startsWith("File:")) {
            val words = Wikimedia.titleFromFile(inner).lowercase()
                .split(Regex("[^\\p{L}]+"))
                .filter { it.length >= 3 && it !in STOP }
            return "web|" + words.take(2).joinToString(" ")
        }
        return "nas|$tag|" + inner.substringBeforeLast('\\', "").lowercase()
    }

    /**
     * Réordonne [list] pour éviter deux photos du même groupe à moins de [WINDOW] photos d'écart.
     * Les photos ne font que glisser de quelques places ; si tout est du même groupe, rien ne change.
     */
    fun <T> spread(list: List<T>, key: (T) -> String): List<T> {
        if (list.size < 3) return list
        val rest = ArrayList(list)
        val out = ArrayList<T>(list.size)
        val recent = ArrayDeque<String>()
        while (rest.isNotEmpty()) {
            val limit = minOf(rest.size, LOOKAHEAD)
            var pick = 0
            for (i in 0 until limit) {
                if (key(rest[i]) !in recent) {
                    pick = i
                    break
                }
            }
            val item = rest.removeAt(pick)
            out += item
            recent.addLast(key(item))
            if (recent.size > WINDOW) recent.removeFirst()
        }
        return out
    }

    fun spreadPhotos(list: List<RemotePhoto>) = spread(list) { group(it.path) }

    /**
     * Empreinte de couleurs : la couleur moyenne de chacune des [PRINT_W] × [PRINT_H] cases,
     * à partir des pixels (ARGB) de la photo réduite à [w] × [h].
     */
    fun print(pixels: IntArray, w: Int, h: Int): FloatArray {
        val out = FloatArray(PRINT_W * PRINT_H * 3)
        val count = IntArray(PRINT_W * PRINT_H)
        for (y in 0 until h) for (x in 0 until w) {
            val c = pixels[y * w + x]
            val cell = (y * PRINT_H / h) * PRINT_W + (x * PRINT_W / w)
            out[cell * 3] += ((c shr 16) and 0xFF) / 255f
            out[cell * 3 + 1] += ((c shr 8) and 0xFF) / 255f
            out[cell * 3 + 2] += (c and 0xFF) / 255f
            count[cell]++
        }
        for (cell in count.indices) if (count[cell] > 0) for (k in 0..2) out[cell * 3 + k] /= count[cell]
        return out
    }

    /** Écart moyen entre deux empreintes, case par case : 0 = identiques, 1 = noir contre blanc. */
    fun distance(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 1f
        var sum = 0f
        for (cell in 0 until a.size / 3) {
            val dr = a[cell * 3] - b[cell * 3]
            val dg = a[cell * 3 + 1] - b[cell * 3 + 1]
            val db = a[cell * 3 + 2] - b[cell * 3 + 2]
            sum += sqrt((dr * dr + dg * dg + db * db) / 3f)
        }
        return sum / (a.size / 3)
    }

    fun tooClose(a: FloatArray?, b: FloatArray?) = a != null && b != null && distance(a, b) < SIMILAR
}
