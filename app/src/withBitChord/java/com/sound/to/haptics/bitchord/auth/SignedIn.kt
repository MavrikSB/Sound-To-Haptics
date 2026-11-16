package com.sound.to.haptics.bitchord.auth

// Derived from BitChord (https://github.com/kushagrasinghx/BitChord) at commit
// acf219a7e4a7f52f026d4336d5c7ac5cf7bfafcc — GPL-3.0. Package renamed and, in a
// few files, minimally edited. Copyright remains with the BitChord authors.
// See THIRD_PARTY_LICENSES.txt.

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow

object SignedIn {

    @Volatile private var state: MutableStateFlow<Boolean>? = null

    fun state(context: Context): MutableStateFlow<Boolean> =
        state ?: synchronized(this) {
            state ?: MutableStateFlow(AuthStore(context.applicationContext).isSignedIn).also { state = it }
        }
}
