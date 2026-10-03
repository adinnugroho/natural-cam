package com.adin.naturalcam.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.adin.naturalcam.ui.theme.cameraChoiceColors
import com.adin.naturalcam.domain.ProcessingProfile

/**
 * Profile selection communicates processing philosophy, not technical
 * implementation (SPEC 94, AGENTS 8). Public profiles are strictly
 * PURE / NATURAL / SYSTEM (PRD 7).
 */
@Composable
fun ProfileSelector(
    selected: ProcessingProfile,
    onSelect: (ProcessingProfile) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (profile in ProcessingProfile.entries) {
            FilterChip(
                selected = profile == selected,
                onClick = { onSelect(profile) },
                colors = cameraChoiceColors(),
                label = { Text(profile.name) },
            )
        }
    }
}

