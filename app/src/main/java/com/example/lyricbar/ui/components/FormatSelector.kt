package com.example.lyricbar.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.lyricbar.data.model.LyricFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormatSelector(
    selectedFormat: LyricFormat,
    onFormatSelected: (LyricFormat) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = "Select Lyrics Format",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 6.dp)
        )
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth()
        ) {
            LyricFormat.entries.forEachIndexed { index, format ->
                SegmentedButton(
                    selected = format == selectedFormat,
                    onClick = { onFormatSelected(format) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = LyricFormat.entries.size)
                ) {
                    Text(
                        text = format.extension,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}
