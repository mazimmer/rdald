package com.rdald.app

/**
 * Shared paragraph/sentence segmentation used by BOTH the reader view and the player, so
 * sentence indices stay in sync. The display text is never altered; periods that belong to
 * abbreviations, initials or dates are only protected from being treated as sentence ends.
 */
object TextSegmentation {

    private const val PROTECT = '\u0001'

    private val abbreviations = setOf(
        "mr", "mrs", "ms", "dr", "prof", "jr", "sr", "vs", "etc", "e.g", "i.e",
        "u.s", "u.k", "u.n", "a.m", "p.m", "bzw", "usw", "z.b", "d.h", "nr", "ca",
        "evtl", "ggf", "inkl", "abs", "art", "stk", "sig", "dott", "ecc", "sra",
        "m", "mme", "др", "т.д", "т.п"
    )

    private val months = setOf(
        "january", "february", "march", "april", "may", "june", "july", "august",
        "september", "october", "november", "december",
        "januar", "februar", "märz", "mai", "juni", "juli", "oktober", "dezember",
        "janvier", "février", "mars", "avril", "juin", "juillet", "août", "septembre",
        "octobre", "novembre", "décembre", "gennaio", "febbraio", "marzo", "aprile",
        "maggio", "giugno", "luglio", "agosto", "ottobre", "dicembre", "enero",
        "febrero", "abril", "mayo", "junio", "julio", "septiembre", "octubre",
        "noviembre", "января", "февраля", "марта", "апреля", "мая", "июня", "июля",
        "августа", "сентября", "октября", "ноября", "декабря"
    )

    fun paragraphs(raw: String): List<String> =
        raw.split(Regex("\\n\\s*\\n|\\n")).map { it.trim() }.filter { it.isNotBlank() }

    fun sentences(paragraph: String): List<String> {
        val protectedText = protect(paragraph)
        return protectedText.split(Regex("(?<=[.!?])\\s+"))
            .map { it.trim().replace(PROTECT, '.') }
            .filter { it.isNotBlank() }
    }

    private fun protect(text: String): String {
        var t = text
        // Protect abbreviations/initials: a word token ending in "." whose spelling is a known
        // abbreviation (or a single letter) should not end a sentence.
        t = Regex("(?<![\\p{L}])([\\p{L}][\\p{L}.]{0,8})\\.(?=\\s|$)").replace(t) { m ->
            val token = m.groupValues[1]
            val lower = token.lowercase()
            if (token.length == 1 || abbreviations.contains(lower)) {
                token + PROTECT
            } else {
                m.value
            }
        }
        // Protect "10. August" style dates (day followed by a month name).
        val monthAlt = months.joinToString("|") { Regex.escape(it) }
        t = Regex("\\b(\\d{1,2})\\.(?=\\s*(?:$monthAlt)\\b)", RegexOption.IGNORE_CASE)
            .replace(t) { m -> m.groupValues[1] + PROTECT }
        return t
    }
}
