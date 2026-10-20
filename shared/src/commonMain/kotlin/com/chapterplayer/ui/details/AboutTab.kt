package com.chapterplayer.ui.details

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.chapterplayer.domain.browse.AboutInfo
import com.chapterplayer.domain.browse.BookLinks
import com.chapterplayer.domain.browse.PersonLink
import com.chapterplayer.ui.library.LibraryView
import com.chapterplayer.ui.theme.AmberGold
import com.chapterplayer.ui.theme.SurfaceElevated
import com.chapterplayer.ui.theme.TextSecondary

private const val COLLAPSED_LINES = 6

internal fun LazyListScope.aboutTab(
    about: AboutInfo?,
    links: BookLinks,
    onOpenGroup: (LibraryView, String) -> Unit
) {
    if (about != null) {
        val description = about.description
        if (!description.isNullOrBlank()) {
            item(key = "about-description") {
                SectionTitle("About this book")
                Description(description)
            }
        }
        if (about.genres.isNotEmpty()) {
            item(key = "about-genres") {
                SectionTitle("Genres")
                Genres(about.genres)
            }
        }
        about.year?.takeIf { it.isNotBlank() }?.let { year ->
            item(key = "about-year") { FactLine("Published $year") }
        }
        about.seriesLine?.let { line -> item(key = "about-series") { FactLine(line) } }
        if (about.needsConnection) {
            item(key = "about-connection") { FactLine("More details need a connection") }
        }
    }
    if (links.authors.isNotEmpty() || links.narrators.isNotEmpty()) {
        item(key = "about-more-title") { SectionTitle("More") }
        if (links.authors.isNotEmpty()) item(key = "more-authors") {
            MoreBySection(links.authors, asNarrator = false) { onOpenGroup(LibraryView.AUTHORS, it) }
        }
        if (links.narrators.isNotEmpty()) item(key = "more-narrators") {
            MoreBySection(links.narrators, asNarrator = true) { onOpenGroup(LibraryView.NARRATORS, it) }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp).semantics { heading() }
    )
}

@Composable
private fun FactLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = TextSecondary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
    )
}

@Composable
private fun Description(text: String) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var overflows by remember { mutableStateOf(false) }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = if (expanded) Int.MAX_VALUE else COLLAPSED_LINES,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow },
        modifier = Modifier.padding(horizontal = 16.dp)
    )
    if (overflows || expanded) {
        TextButton(
            onClick = { expanded = !expanded },
            modifier = Modifier.padding(horizontal = 8.dp).heightIn(min = 48.dp)
        ) { Text(if (expanded) "Show less" else "Read more", color = AmberGold) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Genres(genres: List<String>) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        genres.forEach { genre ->
            Text(
                text = genre,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(SurfaceElevated)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}

/** One role's link rows, capped to the first few with a Show all / Show less toggle. */
@Composable
private fun MoreBySection(people: List<PersonLink>, asNarrator: Boolean, onOpen: (key: String) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column {
        cappedLinks(people, expanded).forEach { person ->
            MoreByRow(moreByText(person, asNarrator)) { onOpen(person.key) }
        }
        linksToggleLabel(people.size, expanded)?.let { label ->
            TextButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.padding(horizontal = 8.dp).heightIn(min = 48.dp)
            ) { Text(label, color = AmberGold) }
        }
    }
}

@Composable
private fun MoreByRow(text: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Open", tint = TextSecondary)
    }
}
