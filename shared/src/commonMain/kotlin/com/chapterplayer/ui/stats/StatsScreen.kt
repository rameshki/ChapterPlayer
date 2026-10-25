package com.chapterplayer.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.domain.downloads.formatBytes
import com.chapterplayer.domain.stats.EMPTY_STATS_TEXT
import com.chapterplayer.domain.stats.HeatCell
import com.chapterplayer.domain.stats.IMPORTED_ONLY_NOTE
import com.chapterplayer.domain.stats.Ranked
import com.chapterplayer.domain.stats.SeriesProgress
import com.chapterplayer.domain.stats.StatsPeriod
import com.chapterplayer.domain.stats.StatsReport
import com.chapterplayer.domain.stats.TopKind
import com.chapterplayer.domain.stats.WEEKDAY_LETTERS
import com.chapterplayer.domain.stats.barFraction
import com.chapterplayer.domain.stats.booksText
import com.chapterplayer.domain.stats.dayLabel
import com.chapterplayer.domain.stats.formatHoursTotal
import com.chapterplayer.domain.stats.formatListened
import com.chapterplayer.domain.stats.groupThousands
import com.chapterplayer.domain.stats.heatDescription
import com.chapterplayer.domain.stats.heroDetail
import com.chapterplayer.domain.stats.heroLabel
import com.chapterplayer.domain.stats.periodLabel
import com.chapterplayer.domain.stats.seriesToFinishLine
import com.chapterplayer.domain.stats.sourceName
import com.chapterplayer.domain.stats.sourceShares
import com.chapterplayer.domain.stats.sourcesDescription
import com.chapterplayer.domain.stats.spokenDuration
import com.chapterplayer.domain.stats.streakText
import com.chapterplayer.domain.stats.weekdayDescription
import com.chapterplayer.ui.components.EmptyState
import com.chapterplayer.ui.library.LibraryView
import com.chapterplayer.ui.theme.AmberGold
import com.chapterplayer.ui.theme.BackgroundDeep
import com.chapterplayer.ui.theme.LocalPalette
import com.chapterplayer.ui.theme.SurfaceElevated
import com.chapterplayer.ui.theme.SurfaceSecondary
import com.chapterplayer.ui.theme.TextPrimary
import com.chapterplayer.ui.theme.TextSecondary
import org.koin.compose.viewmodel.koinViewModel

/** Tabular figures, so numbers line up and do not shift as they change. */
private val Tabular = TextStyle(fontFeatureSettings = "tnum")

/** The statistics page: listening time, streaks, charts, top lists, the library and series progress. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    onBack: () -> Unit,
    onOpenGroup: (LibraryView, String) -> Unit,
    viewModel: StatsViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.onMessageShown()
        }
    }
    Scaffold(
        containerColor = BackgroundDeep,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Statistics", style = MaterialTheme.typography.headlineSmall) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextSecondary) }
                },
                actions = {
                    if (state.refreshing) {
                        CircularProgressIndicator(Modifier.padding(horizontal = 12.dp).size(24.dp), color = AmberGold, strokeWidth = 2.dp)
                    } else {
                        IconButton(onClick = viewModel::onRefresh) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh listening history", tint = TextSecondary)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BackgroundDeep),
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PeriodSwitch(state.period, viewModel::onPeriodSelected)
            val report = state.report
            if (report == null) {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = AmberGold) }
                return@Column
            }
            if (report.nothingYet) {
                EmptyState(Icons.Default.BarChart, EMPTY_STATS_TEXT)
            } else {
                Hero(report)
                Tiles(report)
                if (report.importedOnly) {
                    Text(IMPORTED_ONLY_NOTE, style = MaterialTheme.typography.bodySmall, color = TextSecondary, modifier = Modifier.padding(horizontal = 4.dp))
                }
                StatsCard("Every day") { HeatGrid(report.heat, report.period) }
                StatsCard("When you listen") { WeekdayBars(report.weekdays) }
                StatsCard("Most listened") {
                    TopSwitch(state.topKind, viewModel::onTopKindSelected)
                    TopList(report.top[state.topKind].orEmpty(), state.topKind, onOpenGroup)
                }
                StatsCard("Where you listened") { Sources(report.sources) }
            }
            LibraryCard(report)
            SeriesCards(report.series, onOpenGroup)
        }
    }
}

@Composable
private fun PeriodSwitch(selected: StatsPeriod, onSelected: (StatsPeriod) -> Unit) {
    Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        StatsPeriod.entries.forEach { period ->
            Pill(periodLabel(period), period == selected, Modifier.weight(1f)) { onSelected(period) }
        }
    }
}

@Composable
private fun Pill(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) AmberGold else SurfaceElevated)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .defaultMinSize(minHeight = 40.dp)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) LocalPalette.current.onAmber else TextSecondary,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

@Composable
private fun StatsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(SurfaceElevated).padding(14.dp)) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = TextSecondary,
            modifier = Modifier.padding(bottom = 10.dp).semantics { heading() },
        )
        content()
    }
}

@Composable
private fun Hero(report: StatsReport) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(SurfaceElevated).padding(16.dp)) {
        Text(heroLabel(report.period).uppercase(), style = MaterialTheme.typography.labelMedium, color = TextSecondary)
        Text(
            formatListened(report.listenedSeconds),
            style = MaterialTheme.typography.displaySmall.merge(Tabular),
            color = TextPrimary,
            modifier = Modifier.padding(vertical = 4.dp).semantics { contentDescription = spokenDuration(report.listenedSeconds) },
        )
        Text(heroDetail(report), style = MaterialTheme.typography.bodyMedium.merge(Tabular), color = TextSecondary)
    }
}

@Composable
private fun Tiles(report: StatsReport) {
    val longest = report.longestDay
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile(groupThousands(report.booksFinished.toLong()), if (report.booksFinished == 1) "book finished" else "books finished", Modifier.weight(1f))
            Tile(streakText(report.streakDays), "current streak", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile(
                formatListened(report.timeSavedSeconds), "saved by listening faster than 1×", Modifier.weight(1f),
                spoken = spokenDuration(report.timeSavedSeconds),
            )
            Tile(
                if (longest == null) "–" else formatListened(longest.second),
                if (longest == null) "longest day" else "longest day (${dayLabel(longest.first)})",
                Modifier.weight(1f),
                spoken = longest?.let { spokenDuration(it.second) } ?: "none",
            )
        }
    }
}

@Composable
private fun Tile(value: String, label: String, modifier: Modifier = Modifier, spoken: String = value) {
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceElevated)
            .padding(12.dp)
            .clearAndSetSemantics { contentDescription = "$spoken, $label" },
    ) {
        Text(value, style = MaterialTheme.typography.titleLarge.merge(Tabular), fontWeight = FontWeight.Bold, color = TextPrimary, maxLines = 1)
        Text(label, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
    }
}

/** The colour of a heat level: from the empty square towards amber. */
@Composable
private fun heatColor(cell: HeatCell): Color {
    val empty = SurfaceSecondary
    if (cell.future) return empty.copy(alpha = 0.35f)
    return when (cell.level) {
        0 -> empty
        else -> lerp(empty, AmberGold, cell.level / 4f)
    }
}

@Composable
private fun HeatGrid(weeks: List<List<HeatCell>>, period: StatsPeriod) {
    val description = heatDescription(weeks)
    val gridModifier = Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = description }
    if (period == StatsPeriod.WEEK) {
        // One week: a row of seven days with their letters.
        Row(gridModifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            weeks.single().forEachIndexed { i, cell ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(6.dp)).background(heatColor(cell)))
                    Text(WEEKDAY_LETTERS[i], style = MaterialTheme.typography.labelSmall, color = TextSecondary, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    } else {
        // Weeks as columns, Monday at the top.
        Row(gridModifier, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            weeks.forEach { week ->
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    week.forEach { cell ->
                        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(3.dp)).background(heatColor(cell)))
                    }
                }
            }
        }
    }
}

@Composable
private fun WeekdayBars(weekdays: List<Long>) {
    val max = weekdays.maxOrNull() ?: 0L
    val description = weekdayDescription(weekdays)
    Row(
        Modifier.fillMaxWidth().height(96.dp).clearAndSetSemantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        val empty = SurfaceSecondary
        val bar = AmberGold
        weekdays.forEachIndexed { i, seconds ->
            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                    val fraction = barFraction(seconds, max)
                    Box(
                        Modifier.fillMaxWidth()
                            .fillMaxHeight(if (fraction > 0f) fraction.coerceAtLeast(0.03f) else 0.03f)
                            .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                            .background(if (fraction > 0f) bar else empty)
                    )
                }
                Text(WEEKDAY_LETTERS[i], style = MaterialTheme.typography.labelSmall, color = TextSecondary, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

private fun topLabel(kind: TopKind) = when (kind) {
    TopKind.AUTHORS -> "Authors"
    TopKind.NARRATORS -> "Narrators"
    TopKind.SERIES -> "Series"
}

@Composable
private fun TopSwitch(selected: TopKind, onSelected: (TopKind) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TopKind.entries.forEach { kind -> Pill(topLabel(kind), kind == selected, Modifier.weight(1f)) { onSelected(kind) } }
    }
}

@Composable
private fun TopList(rows: List<Ranked>, kind: TopKind, onOpenGroup: (LibraryView, String) -> Unit) {
    if (rows.isEmpty()) {
        Text("Nothing in this period yet", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        return
    }
    val view = when (kind) {
        TopKind.AUTHORS -> LibraryView.AUTHORS
        TopKind.NARRATORS -> LibraryView.NARRATORS
        TopKind.SERIES -> LibraryView.SERIES
    }
    rows.forEachIndexed { i, row ->
        if (i > 0) HorizontalDivider(color = SurfaceSecondary)
        StatRow(row.name, formatListened(row.seconds), spokenValue = spokenDuration(row.seconds)) { onOpenGroup(view, row.key) }
    }
}

/** A name with a figure on the right; tappable when [onClick] is given. */
@Composable
private fun StatRow(name: String, value: String, spokenValue: String = value, onClick: (() -> Unit)? = null) {
    val base = Modifier.fillMaxWidth().defaultMinSize(minHeight = 44.dp)
    Row(
        (if (onClick != null) base.clickable(role = Role.Button, onClick = onClick) else base)
            .clearAndSetSemantics { contentDescription = "$name, $spokenValue" }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, style = MaterialTheme.typography.bodyLarge, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(10.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium.merge(Tabular), color = TextSecondary, maxLines = 1)
    }
}

@Composable
private fun sourceColor(source: ServerSource): Color = when (source) {
    ServerSource.ABS -> AmberGold
    ServerSource.PLEX -> TextSecondary
}

@Composable
private fun Sources(sources: Map<ServerSource, Long>) {
    val shares = sourceShares(sources)
    if (shares.isEmpty()) {
        Text("Nothing in this period yet", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        return
    }
    Column(Modifier.clearAndSetSemantics { contentDescription = sourcesDescription(shares) }) {
        Row(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp))) {
            shares.forEach { (source, percent) ->
                if (percent > 0) Box(Modifier.weight(percent.toFloat()).fillMaxHeight().background(sourceColor(source)))
            }
        }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            shares.forEach { (source, percent) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(sourceColor(source)))
                    Text(
                        "${sourceName(source)} $percent%",
                        style = MaterialTheme.typography.bodySmall.merge(Tabular),
                        color = TextSecondary,
                        modifier = Modifier.padding(start = 5.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun LibraryCard(report: StatsReport) {
    val lib = report.library
    StatsCard("Your library") {
        StatRow("Books", groupThousands(lib.books.toLong()))
        HorizontalDivider(color = SurfaceSecondary)
        StatRow("Total length", formatHoursTotal(lib.totalMs))
        HorizontalDivider(color = SurfaceSecondary)
        StatRow("Downloaded", "${booksText(lib.downloadedBooks)} · ${formatBytes(lib.downloadedBytes)}")
        HorizontalDivider(color = SurfaceSecondary)
        StatRow("Not started", booksText(lib.notStarted))
    }
}

@Composable
private fun SeriesCards(series: SeriesProgress, onOpenGroup: (LibraryView, String) -> Unit) {
    StatsCard("Series finished") {
        if (series.finished.isEmpty()) {
            Text("Finish every book of a series and it shows here", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        } else {
            Text(
                if (series.finished.size == 1) "1 series finished" else "${series.finished.size} series finished",
                style = MaterialTheme.typography.bodyMedium.merge(Tabular),
                color = TextSecondary,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            series.finished.forEachIndexed { i, s ->
                if (i > 0) HorizontalDivider(color = SurfaceSecondary)
                StatRow(s.title, booksText(s.books)) { onOpenGroup(LibraryView.SERIES, s.key) }
            }
        }
    }
    StatsCard("Series to finish") {
        if (series.toFinish.isEmpty()) {
            Text("Series you have started show here", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        } else {
            series.toFinish.forEachIndexed { i, s ->
                if (i > 0) HorizontalDivider(color = SurfaceSecondary)
                StatRow(s.title, seriesToFinishLine(s)) { onOpenGroup(LibraryView.SERIES, s.key) }
            }
        }
    }
}
