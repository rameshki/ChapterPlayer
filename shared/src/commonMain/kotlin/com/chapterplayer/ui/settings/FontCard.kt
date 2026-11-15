package com.chapterplayer.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.chapterplayer.data.settings.AppFont
import com.chapterplayer.domain.settings.FONT_CHOICES
import com.chapterplayer.domain.settings.fontDescription
import com.chapterplayer.domain.settings.fontName
import com.chapterplayer.ui.theme.AmberGold
import com.chapterplayer.ui.theme.SurfaceElevated
import com.chapterplayer.ui.theme.TextSecondary
import com.chapterplayer.ui.theme.appFontFamilies

/** Settings, Appearance: the font choices, each name drawn in its own title font and its line in its text font. */
@Composable
internal fun FontCard(selected: AppFont, onSelect: (AppFont) -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceElevated),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.selectableGroup()) {
            FONT_CHOICES.forEach { font ->
                val isSelected = selected == font
                val families = appFontFamilies(font)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(font) })
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = isSelected,
                        onClick = null,
                        colors = RadioButtonDefaults.colors(selectedColor = AmberGold)
                    )
                    Column(modifier = Modifier.padding(start = 12.dp)) {
                        Text(
                            text = fontName(font),
                            style = MaterialTheme.typography.titleLarge.copy(fontFamily = families.display),
                            color = if (isSelected) MaterialTheme.colorScheme.onSurface else TextSecondary
                        )
                        Text(
                            text = fontDescription(font),
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = families.body),
                            color = TextSecondary
                        )
                    }
                }
            }
        }
    }
}
