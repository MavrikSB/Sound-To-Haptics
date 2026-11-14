package com.sound.to.haptics.ui.icons

import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import com.sound.to.haptics.R
import androidx.compose.material3.Icon

/**
 * The BitChord mark for navigation and the music tab.
 *
 * This is BitChord's own vector asset (`res/drawable/bitchord_logo.xml`,
 * taken from the BitChord project, GPL-3.0), recolored through [tint] so it
 * follows the selected/unselected navigation colors. The `waveTint` parameter
 * is kept for drop-in compatibility with the call sites it replaces; the mark
 * has no second layer, so it is ignored.
 */
@Composable
fun BitChordLogo(
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    @Suppress("UNUSED_PARAMETER") waveTint: Color = LocalContentColor.current,
) {
    Icon(
        painter = painterResource(R.drawable.bitchord_logo),
        contentDescription = null,
        tint = tint,
        modifier = modifier,
    )
}
