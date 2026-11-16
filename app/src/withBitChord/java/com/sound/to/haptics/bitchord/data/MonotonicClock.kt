package com.sound.to.haptics.bitchord.data

// Derived from BitChord (https://github.com/kushagrasinghx/BitChord) at commit
// acf219a7e4a7f52f026d4336d5c7ac5cf7bfafcc — GPL-3.0. Package renamed and, in a
// few files, minimally edited. Copyright remains with the BitChord authors.
// See THIRD_PARTY_LICENSES.txt.

object MonotonicClock {
    private val origin = System.nanoTime()

    @Volatile
    var nowMs: () -> Long = { (System.nanoTime() - origin) / 1_000_000L }
}
