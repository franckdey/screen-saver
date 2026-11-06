package com.maxime.photodream

/** Coupe une légende trop longue sur plusieurs lignes, sans dessin : testable sans Android. */
object TextWrap {

    /**
     * Découpe [text] en lignes de largeur au plus [maxWidth] (mesurée par [measure]), en coupant
     * entre les mots. Au-delà de [maxLines] lignes, la dernière se termine par « … ».
     * Un « · » ne reste jamais seul en fin ou en début de ligne.
     */
    fun wrap(text: String, maxWidth: Float, measure: (String) -> Float, maxLines: Int = 2): List<String> {
        if (text.isEmpty() || measure(text) <= maxWidth) return listOf(text)
        val words = text.split(' ').filter { it.isNotEmpty() }
        val lines = ArrayList<String>()
        var line = ""
        var i = 0
        while (i < words.size && lines.size < maxLines - 1) {
            val candidate = if (line.isEmpty()) words[i] else "$line ${words[i]}"
            if (line.isNotEmpty() && measure(clean(candidate)) > maxWidth) {
                lines += clean(line)
                line = ""
            } else {
                line = candidate
                i++
            }
        }
        // la dernière ligne prend tout le reste, raccourcie si besoin
        val rest = clean((listOf(line) + words.drop(i)).filter { it.isNotEmpty() }.joinToString(" "))
        lines += ellipsize(rest, maxWidth, measure)
        return lines.filter { it.isNotEmpty() }
    }

    private fun clean(s: String) = s.trim().removePrefix("·").removeSuffix("·").trim()

    private fun ellipsize(s: String, maxWidth: Float, measure: (String) -> Float): String {
        if (measure(s) <= maxWidth) return s
        var end = s.length
        while (end > 0 && measure(s.substring(0, end).trimEnd() + "…") > maxWidth) end--
        return s.substring(0, end).trimEnd().removeSuffix("·").trimEnd() + "…"
    }
}
