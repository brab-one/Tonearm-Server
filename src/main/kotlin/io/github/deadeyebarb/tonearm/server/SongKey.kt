package io.github.deadeyebarb.tonearm.server

import java.text.Normalizer

/** The apps' song key (their Names and SongMatch), letter for letter: what one app disliked, the server and the other app match. */
object SongKey {
    private val decorations = listOf(
        Regex("""\s*[(\[](?:feat|ft|featuring|with)\.?\s[^)\]]*[)\]]""", RegexOption.IGNORE_CASE),
        Regex("""\s*[(\[](?:official\s*(?:music\s*)?(?:video|audio|lyric video|visualizer)|lyrics?|audio|hd|hq|4k|explicit|clean)[^)\]]*[)\]]""", RegexOption.IGNORE_CASE),
        Regex("""\s*[(\[][^)\]]*(?:remaster(?:ed)?|deluxe|anniversary|mono|stereo)[^)\]]*[)\]]""", RegexOption.IGNORE_CASE),
        Regex("""\s+-\s+(?:\d{4}\s+)?remaster(?:ed)?(?:\s+\d{4})?.*$""", RegexOption.IGNORE_CASE),
        Regex("""\s+(?:feat|ft|featuring)\.?\s.*$""", RegexOption.IGNORE_CASE),
    )
    private val credits = Regex("""\s*(?:,|;|&|\bfeat\.?|\bft\.?|\bfeaturing\b|\bx\b|\bwith\b)\s*""", RegexOption.IGNORE_CASE)
    private val marks = Regex("\\p{M}+")
    private val separators = Regex("[^\\p{L}\\p{N}]+")

    fun of(artist: String, title: String) = normalize(primaryArtist(cleanArtist(artist))) + "|" + normalize(cleanTitle(title))

    fun cleanTitle(title: String): String = decorations.fold(title) { t, r -> r.replace(t, "") }.trim()

    fun cleanArtist(artist: String): String =
        artist.removeSuffix(" - Topic").replace(Regex("(?i)VEVO$"), "").replace(Regex("(?i)\\s+official$"), "").trim()

    fun primaryArtist(artist: String): String = artist.split(credits).firstOrNull { it.isNotBlank() }?.trim() ?: artist

    fun normalize(name: String): String {
        val folded = Normalizer.normalize(name, Normalizer.Form.NFKD).replace(marks, "")
        return folded.lowercase().replace('&', ' ').replace(separators, " ").trim().removePrefix("the ").trim()
    }
}
