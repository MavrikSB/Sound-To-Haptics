package com.sound.to.haptics.bitchord.data.sources

// Derived from BitChord (https://github.com/kushagrasinghx/BitChord) at commit
// acf219a7e4a7f52f026d4336d5c7ac5cf7bfafcc — GPL-3.0. Package renamed and, in a
// few files, minimally edited. Copyright remains with the BitChord authors.
// See THIRD_PARTY_LICENSES.txt.

/**
 * The shape of a source-backed track's id — `src:<configId>::<trackId>` — as the
 * queue carries it. Parsed here, where the stream stats can read it too; the
 * phone's SourceRegistry mints them.
 */
object SourceTrackKeys {
    const val PREFIX = "src:"
    const val SEPARATOR = "::"

    /** The `(configId, trackId)` inside a key, or null if this is an ordinary YouTube id. */
    fun parse(key: String): Pair<String, String>? {
        if (!key.startsWith(PREFIX)) return null
        val body = key.removePrefix(PREFIX)
        val cut = body.indexOf(SEPARATOR)
        if (cut <= 0) return null
        return body.substring(0, cut) to body.substring(cut + SEPARATOR.length)
    }
}
