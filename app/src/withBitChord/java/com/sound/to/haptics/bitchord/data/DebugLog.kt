package com.sound.to.haptics.bitchord.data

// Derived from BitChord (https://github.com/kushagrasinghx/BitChord) at commit
// acf219a7e4a7f52f026d4336d5c7ac5cf7bfafcc — GPL-3.0. Package renamed and, in a
// few files, minimally edited. Copyright remains with the BitChord authors.
// See THIRD_PARTY_LICENSES.txt.

object DebugLog {
    fun interface Sink {
        fun log(level: Char, tag: String, message: String, error: Throwable?)
    }

    @Volatile
    var sink: Sink? = null

    fun d(tag: String, message: String) = sink?.log('D', tag, message, null) ?: Unit

    fun i(tag: String, message: String) = sink?.log('I', tag, message, null) ?: Unit

    fun w(tag: String, message: String) = sink?.log('W', tag, message, null) ?: Unit

    fun w(tag: String, message: String, error: Throwable) = sink?.log('W', tag, message, error) ?: Unit

    fun e(tag: String, message: String) = sink?.log('E', tag, message, null) ?: Unit

    fun e(tag: String, message: String, error: Throwable) = sink?.log('E', tag, message, error) ?: Unit
}
