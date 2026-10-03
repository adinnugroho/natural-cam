package com.adin.naturalcam.compat

import android.os.Build

/**
 * Vendor quirks live here only (AGENTS 58) — never `if (MANUFACTURER == ...)`
 * scattered across the app. A quirk requires a reproducible issue, affected
 * build, technical explanation and documentation (see docs/COMPAT.md).
 */
data class DeviceQuirk(
    val id: String,
    val description: String,
    val appliesTo: (BuildInfo) -> Boolean,
)

data class BuildInfo(
    val manufacturer: String,
    val model: String,
    val device: String,
    val sdkInt: Int,
)

object CompatRegistry {

    /**
     * No quirks yet: every behavior is capability-driven until a real device
     * demonstrates otherwise. Adding entries requires the evidence listed in
     * docs/COMPAT.md.
     */
    private val quirks: List<DeviceQuirk> = emptyList()

    fun currentBuild(): BuildInfo = BuildInfo(
        manufacturer = Build.MANUFACTURER,
        model = Build.MODEL,
        device = Build.DEVICE,
        sdkInt = Build.VERSION.SDK_INT,
    )

    fun activeQuirks(build: BuildInfo = currentBuild()): List<DeviceQuirk> =
        quirks.filter { it.appliesTo(build) }
}
