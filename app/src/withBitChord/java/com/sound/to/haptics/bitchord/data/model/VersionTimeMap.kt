package com.sound.to.haptics.bitchord.data.model

// Derived from BitChord (https://github.com/kushagrasinghx/BitChord) at commit
// acf219a7e4a7f52f026d4336d5c7ac5cf7bfafcc — GPL-3.0. Package renamed and, in a
// few files, minimally edited. Copyright remains with the BitChord authors.
// See THIRD_PARTY_LICENSES.txt.

class VersionTimeMap(segments: List<Segment>) {

    data class Segment(val fromStartMs: Long, val toStartMs: Long, val durationMs: Long)

    val segments: List<Segment> = segments.sortedBy { it.fromStartMs }

    init {
        require(this.segments.isNotEmpty()) { "a time map needs at least one segment" }
    }

    fun map(positionMs: Long): Long {
        for (segment in segments) {
            if (positionMs < segment.fromStartMs) return segment.toStartMs
            val into = positionMs - segment.fromStartMs
            if (into < segment.durationMs) return segment.toStartMs + into
        }
        val last = segments.last()
        return last.toStartMs + last.durationMs
    }

    fun inverted(): VersionTimeMap =
        VersionTimeMap(segments.map { Segment(it.toStartMs, it.fromStartMs, it.durationMs) })
}

data class VersionPair(val song: Song, val timeMap: VersionTimeMap?)
