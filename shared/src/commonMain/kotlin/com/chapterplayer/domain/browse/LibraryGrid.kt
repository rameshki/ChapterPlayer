package com.chapterplayer.domain.browse

import com.chapterplayer.data.settings.LibraryLayout
import kotlin.math.floor

private const val LARGE_TILE_DP = 168f
private const val SMALL_TILE_DP = 108f
private const val GRID_GAP_DP = 12f
private const val GRID_SIDE_DP = 40f

/**
 * How many tiles the Library grid puts across [widthDp]: the layout's own count on a phone (2 large, 3 small), more on a wide
 * screen (landscape, a tablet) so the tiles keep about the same size instead of growing to half the screen.
 */
fun libraryGridColumns(layout: LibraryLayout, widthDp: Float): Int {
    val (base, tile) = when (layout) {
        LibraryLayout.LARGE_GRID -> 2 to LARGE_TILE_DP
        LibraryLayout.SMALL_GRID -> 3 to SMALL_TILE_DP
        LibraryLayout.LIST -> return 1
    }
    val fit = floor((widthDp - GRID_SIDE_DP + GRID_GAP_DP) / (tile + GRID_GAP_DP)).toInt()
    return maxOf(base, fit)
}
