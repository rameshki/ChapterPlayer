package com.chapterplayer.ui.library

import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.chapterplayer.ui.theme.AmberGold
import com.chapterplayer.ui.theme.BackgroundDeep
import com.chapterplayer.ui.theme.TextSecondary

/** The five views along the top of the Library screen: Library, Authors, Narrators, Series, Collections. */
@Composable
fun LibraryViewRow(
    selected: LibraryView,
    onSelected: (LibraryView) -> Unit,
    modifier: Modifier = Modifier
) {
    ScrollableTabRow(
        selectedTabIndex = selected.ordinal,
        modifier = modifier,
        containerColor = BackgroundDeep,
        contentColor = AmberGold,
        edgePadding = 16.dp
    ) {
        LibraryView.entries.forEach { view ->
            Tab(
                selected = view == selected,
                onClick = { onSelected(view) },
                selectedContentColor = AmberGold,
                unselectedContentColor = TextSecondary,
                text = { Text(view.title) }
            )
        }
    }
}
