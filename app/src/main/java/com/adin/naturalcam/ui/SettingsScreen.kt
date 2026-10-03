package com.adin.naturalcam.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.adin.naturalcam.R
import com.adin.naturalcam.domain.AppSettings
import com.adin.naturalcam.domain.AspectRatio
import com.adin.naturalcam.domain.ProcessingProfile
import com.adin.naturalcam.domain.RawMode
import com.adin.naturalcam.ui.theme.CameraBlack
import com.adin.naturalcam.ui.theme.CameraControl
import com.adin.naturalcam.ui.theme.CameraOrange
import com.adin.naturalcam.ui.theme.CameraWhite
import com.adin.naturalcam.ui.theme.cameraChoiceColors

/** Settings events; implemented outside the UI, composables stay declarative (AGENTS 38). */
interface SettingsActions {
    fun onSetProfile(profile: ProcessingProfile)
    fun onSetRawMode(rawMode: RawMode)
    fun onSetTimerSeconds(seconds: Int)
    fun onSetAspectRatio(aspectRatio: AspectRatio)
    fun onSetGeotagging(enabled: Boolean)
    fun onSetGrid(enabled: Boolean)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    actions: SettingsActions,
    onBack: () -> Unit,
    onOpenDeviceInfo: () -> Unit,
) {
    Scaffold(
        containerColor = CameraBlack,
        topBar = {
            TopAppBar(
                title = { Text("PENGATURAN") },
                navigationIcon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_back),
                        contentDescription = "Kembali",
                        modifier = Modifier
                            .padding(16.dp)
                            .clickable(onClick = onBack),
                        tint = CameraWhite,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = CameraBlack,
                    titleContentColor = CameraWhite,
                ),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            SettingsSection("HASIL FOTO") {
                SettingLabel("STYLE")
                ProfileSelector(settings.profile, actions::onSetProfile)
                SettingLabel("RAW")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RawMode.entries.forEach { mode ->
                        FilterChip(
                            selected = settings.rawMode == mode,
                            onClick = { actions.onSetRawMode(mode) },
                            colors = cameraChoiceColors(),
                            label = {
                                Text(
                                    when (mode) {
                                        RawMode.FINAL_ONLY -> "OFF"
                                        RawMode.RAW_AND_FINAL -> "RAW+JPEG"
                                        RawMode.RAW_ONLY -> "RAW"
                                    },
                                )
                            },
                        )
                    }
                }
            }
            SettingsSection("PEMBIDIK") {
                SettingLabel("RASIO")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AspectRatio.entries.forEach { ratio ->
                        FilterChip(
                            selected = settings.aspectRatio == ratio,
                            onClick = { actions.onSetAspectRatio(ratio) },
                            colors = cameraChoiceColors(),
                            label = { Text(ratio.label) },
                        )
                    }
                }
                SettingLabel("TIMER")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0, 3, 10).forEach { seconds ->
                        FilterChip(
                            selected = settings.timerSeconds == seconds,
                            onClick = { actions.onSetTimerSeconds(seconds) },
                            colors = cameraChoiceColors(),
                            label = { Text(if (seconds == 0) "OFF" else "${seconds}S") },
                        )
                    }
                }
                SettingToggle("GRID", settings.gridEnabled, actions::onSetGrid)
            }
            SettingsSection("PRIVASI") {
                SettingToggle("SIMPAN LOKASI", settings.geotagging, actions::onSetGeotagging)
            }
            SettingsSection("PERANGKAT") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Informasi perangkat" }
                        .clickable(onClick = onOpenDeviceInfo)
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_info),
                        contentDescription = null,
                        tint = CameraOrange,
                    )
                    Text(
                        text = "INFORMASI KAMERA",
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 12.dp),
                        color = CameraWhite,
                    )
                    Text("›", color = CameraOrange, style = MaterialTheme.typography.titleLarge)
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = CameraOrange, style = MaterialTheme.typography.labelLarge)
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = CameraControl,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun SettingLabel(text: String) {
    Text(text, color = CameraWhite.copy(alpha = 0.66f), style = MaterialTheme.typography.labelSmall)
}

@Composable
private fun SettingToggle(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, modifier = Modifier.weight(1f), color = CameraWhite)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = CameraBlack,
                checkedTrackColor = CameraOrange,
                uncheckedThumbColor = CameraWhite,
                uncheckedTrackColor = Color(0xFF3A3A3A),
            ),
        )
    }
}
