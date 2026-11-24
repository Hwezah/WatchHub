package com.torrentviewer.core

/*
 * Saving a source's subtitles: which are ticked to start with, and what each file is called. Pure, tested on the JVM.
 */

/** Languages as sources write them, to one name: "en", "eng", "English (SDH)", "english" → "English". */
private val SUB_LANGS = mapOf(
    "en" to "English", "eng" to "English", "english" to "English",
    "fr" to "French", "fre" to "French", "fra" to "French", "french" to "French",
    "es" to "Spanish", "spa" to "Spanish", "spanish" to "Spanish", "esp" to "Spanish",
    "de" to "German", "ger" to "German", "deu" to "German", "german" to "German",
    "it" to "Italian", "ita" to "Italian", "italian" to "Italian",
    "pt" to "Portuguese", "por" to "Portuguese", "portuguese" to "Portuguese", "ptbr" to "Portuguese", "pt-br" to "Portuguese",
    "ar" to "Arabic", "ara" to "Arabic", "arabic" to "Arabic",
    "hi" to "Hindi", "hin" to "Hindi", "hindi" to "Hindi",
    "sw" to "Swahili", "swa" to "Swahili", "swahili" to "Swahili",
)

/** [language] as one name ([SUB_LANGS]), anything in brackets after it ("(SDH)", "[Forced]") dropped; unknown ones title-cased. */
fun subtitleLanguage(language: String): String {
    val bare = language.substringBefore('(').substringBefore('[').trim().lowercase()
    return SUB_LANGS[bare] ?: SUB_LANGS[bare.substringBefore(' ')] ?: bare.split(' ', '-', '_').filter { it.isNotEmpty() }
        .joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }.ifEmpty { "Subtitles" }
}

/**
 * Which of [languages] are ticked when the list opens: the first English one only (the owner's choice — a film's dozens
 * of subtitle files flooded its folder); none when there is no English.
 */
fun defaultSubtitlePicks(languages: List<String>): Set<Int> =
    languages.indexOfFirst { subtitleLanguage(it) == "English" }.takeIf { it >= 0 }?.let { setOf(it) } ?: emptySet()

/**
 * A saved subtitle's name, beside its video: "[base].[Language].[ext]" — a player finds it by the video's name — with a
 * number after a second of the same language ("Film.English.2.srt"). [taken] is what's already been named.
 */
fun subtitleFileName(base: String, language: String, ext: String, taken: Collection<String>): String {
    val lang = subtitleLanguage(language).replace(' ', '_')
    val first = "$base.$lang.$ext"
    if (taken.none { it.equals(first, ignoreCase = true) }) return first
    var n = 2
    while (taken.any { it.equals("$base.$lang.$n.$ext", ignoreCase = true) }) n++
    return "$base.$lang.$n.$ext"
}
