package com.adin.naturalcam.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.adin.naturalcam.R
import com.adin.naturalcam.domain.CameraCapabilities
import com.adin.naturalcam.domain.CapabilityConfidence
import com.adin.naturalcam.domain.ControlSupport
import com.adin.naturalcam.ui.theme.CameraBlack
import com.adin.naturalcam.ui.theme.CameraControl
import com.adin.naturalcam.ui.theme.CameraOrange
import com.adin.naturalcam.ui.theme.CameraWhite

/**
 * Honest per-camera capability report (PRD 11, AGENTS 9-12): capabilities are
 * listed per physical camera exactly as discovered, with confidence wording.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceInfoScreen(
    capabilitiesPerCamera: List<CameraCapabilities>,
    onBack: () -> Unit,
) {
    Scaffold(
        containerColor = CameraBlack,
        topBar = {
            TopAppBar(
                title = { Text("INFORMASI KAMERA") },
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
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (capabilitiesPerCamera.isEmpty()) {
                Text("DATA KAMERA BELUM TERSEDIA", color = CameraWhite.copy(alpha = 0.62f))
            }
            for (caps in capabilitiesPerCamera) {
                CameraSection(caps)
            }
        }
    }
}

@Composable
private fun CameraSection(caps: CameraCapabilities) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = CameraControl,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "KAMERA ${caps.cameraId.value}",
                style = MaterialTheme.typography.titleMedium,
                color = CameraOrange,
            )
            HorizontalDivider(color = CameraWhite.copy(alpha = 0.14f))
            InfoRow("ARAH LENSA", caps.lensFacing.name)
            InfoRow("LEVEL HARDWARE", caps.hardwareLevel.name)
            InfoRow("RAW", supportLabel(caps.rawSupport))
            InfoRow("FORMAT", caps.supportedFormats.joinToString { it.name })
            caps.resolutions.forEach { (format, sizes) ->
                InfoRow("RESOLUSI ${format.name}", sizes.joinToString { "${it.width}×${it.height}" })
            }
            InfoRow("ISO", caps.isoRange?.let { "${it.first}–${it.last}" } ?: "TIDAK DILAPORKAN")
            InfoRow(
                "EXPOSURE",
                caps.exposureTimeRangeNs?.let { "${it.first}–${it.last} ns" } ?: "TIDAK DILAPORKAN",
            )
            InfoRow(
                "KOMPENSASI EV",
                caps.exposureCompensationEvRange?.let {
                    "${it.start}–${it.endInclusive}, step ${caps.exposureCompensationStepEv}"
                } ?: "TIDAK DILAPORKAN",
            )
            InfoRow("FLASH", if (caps.flashAvailable) "TERSEDIA" else "TIDAK TERSEDIA")
            InfoRow("STABILISASI OPTIK", if (caps.opticalStabilizationSupported) "DIDUKUNG" else "TIDAK TERSEDIA")
            InfoRow("ORIENTASI SENSOR", "${caps.sensorOrientation}°")
            InfoRow(
                "FOKUS MINIMUM",
                caps.minimumFocusDistanceDiopters?.let { "$it diopter" } ?: "TIDAK DILAPORKAN",
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            text = label,
            modifier = Modifier.weight(0.42f),
            style = MaterialTheme.typography.labelSmall,
            color = CameraWhite.copy(alpha = 0.56f),
        )
        Text(
            text = value,
            modifier = Modifier.weight(0.58f),
            style = MaterialTheme.typography.bodySmall,
            color = CameraWhite,
        )
    }
}

/** Confidence wording stays honest (SPEC 12, AGENTS 12). */
private fun supportLabel(support: ControlSupport): String {
    val confidence = when (support.confidence) {
        CapabilityConfidence.CONFIRMED -> "TERKONFIRMASI"
        CapabilityConfidence.SUPPORTED -> "DILAPORKAN PERANGKAT"
        CapabilityConfidence.UNAVAILABLE -> "TIDAK TERSEDIA"
        CapabilityConfidence.UNKNOWN -> "TIDAK DIKETAHUI"
    }
    return support.note?.let { "$confidence — $it" } ?: confidence
}
