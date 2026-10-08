package com.adin.naturalcam.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.adin.naturalcam.domain.CameraCapabilities
import com.adin.naturalcam.domain.CapabilityConfidence
import com.adin.naturalcam.domain.ControlSupport
import com.adin.naturalcam.ui.theme.CameraOrange
import com.adin.naturalcam.ui.theme.CameraWhite

/**
 * Honest per-camera capability report (PRD 11, AGENTS 9-12): capabilities are
 * listed per physical camera exactly as discovered, with confidence wording.
 */
@Composable
fun DeviceInfoScreen(
    capabilitiesPerCamera: List<CameraCapabilities>,
    onBack: () -> Unit,
) {
    CameraPage(title = "INFORMASI KAMERA", onBack = onBack) { padding ->
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
    CameraPanel(verticalSpacing = 8.dp) {
        Text(
            text = "KAMERA ${caps.cameraId.value}",
            style = MaterialTheme.typography.titleMedium,
            color = CameraOrange,
        )
        HorizontalDivider(color = CameraWhite.copy(alpha = 0.14f))
        InfoRow("ARAH LENSA", caps.lensFacing.name)
        InfoRow("LEVEL HARDWARE", caps.hardwareLevel.name)
        InfoRow("MULTI-KAMERA LOGIS", if (caps.logicalMultiCamera) "YA" else "TIDAK")
        InfoRow(
            "ID KAMERA FISIK",
            caps.physicalCameraIds.joinToString { it.value }.ifEmpty { "TIDAK ADA" },
        )
        InfoRow("SENSOR MANUAL", supportLabel(caps.manualSensorSupport))
        InfoRow("PEMROSESAN MANUAL", supportLabel(caps.manualPostProcessingSupport))
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
        InfoRow(
            "RENTANG ZOOM",
            caps.zoomRatioRange?.let { "${it.start}–${it.endInclusive}" } ?: "TIDAK DILAPORKAN",
        )
        InfoRow("STABILISASI OPTIK", if (caps.opticalStabilizationSupported) "DIDUKUNG" else "TIDAK TERSEDIA")
        InfoRow("ORIENTASI SENSOR", "${caps.sensorOrientation}°")
        InfoRow(
            "FOKUS MINIMUM",
            caps.minimumFocusDistanceDiopters?.let { "$it diopter" } ?: "TIDAK DILAPORKAN",
        )
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
