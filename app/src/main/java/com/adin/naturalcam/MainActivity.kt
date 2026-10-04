package com.adin.naturalcam

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.adin.naturalcam.camera.camerax.CameraXController
import com.adin.naturalcam.capture.CaptureCoordinator
import com.adin.naturalcam.image.encoding.AndroidJpegEncoder
import com.adin.naturalcam.image.core.DefaultImagePipeline
import com.adin.naturalcam.location.AndroidLocationProvider
import com.adin.naturalcam.settings.SettingsRepository
import com.adin.naturalcam.storage.LatestPhotoReader
import com.adin.naturalcam.storage.MediaStoreWriterImpl
import com.adin.naturalcam.storage.TempFileCleanerImpl
import com.adin.naturalcam.ui.CameraActions
import com.adin.naturalcam.ui.CameraScreen
import com.adin.naturalcam.ui.CameraViewModel
import com.adin.naturalcam.ui.DeviceInfoScreen
import com.adin.naturalcam.ui.SettingsScreen
import com.adin.naturalcam.ui.VmEvent
import com.adin.naturalcam.ui.theme.NaturalCameraTheme

/**
 * Single activity; composition root wiring the domain to UI (SPEC 6).
 * Camera permission gates startup; location is requested only when the user
 * enables geotagging (AGENTS 53).
 */
class MainActivity : ComponentActivity() {

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) viewModel.onCameraPermissionGranted() else viewModel.onCameraPermissionDenied()
        }

    private val locationPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val granted = results.values.any { it }
            viewModel.onLocationPermissionResult(granted)
        }

    private val viewModel: CameraViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val controller = CameraXController(applicationContext, this@MainActivity)
                val coordinator = CaptureCoordinator(
                    controller = controller,
                    pipeline = DefaultImagePipeline(AndroidJpegEncoder()),
                    mediaStore = MediaStoreWriterImpl(applicationContext),
                    locationProvider = AndroidLocationProvider(applicationContext),
                )
                return CameraViewModel(
                    controller = controller,
                    coordinator = coordinator,
                    settingsRepository = SettingsRepository(applicationContext),
                    appContext = applicationContext,
                    latestPhotoReader = LatestPhotoReader(applicationContext),
                ) as T
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            viewModel.onCameraPermissionGranted()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }

        lifecycleScope.launch {
            TempFileCleanerImpl(applicationContext).cleanStale()
        }

        lifecycleScope.launch {
            viewModel.events.collect { event ->
                when (event) {
                    VmEvent.OpenSettings -> Unit
                    VmEvent.OpenDeviceInfo -> Unit
                    is VmEvent.OpenGallery -> startActivity(viewModel.launchGalleryIntent(event.uri))
                    VmEvent.RequestLocationPermission -> locationPermission.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                            Manifest.permission.ACCESS_FINE_LOCATION,
                        ),
                    )
                }
            }
        }

        setContent {
            NaturalCameraTheme {
                CameraApp(viewModel)
            }
        }
    }
}

private const val ROUTE_CAMERA = "camera"
private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_DEVICE_INFO = "deviceInfo"

@Composable
private fun CameraApp(viewModel: CameraViewModel) {
    val navController = rememberNavController()
    val state by viewModel.uiState.collectAsState()
    val settings by viewModel.currentSettings().collectAsState(
        initial = com.adin.naturalcam.domain.AppSettings(),
    )

    NavHost(navController = navController, startDestination = ROUTE_CAMERA) {
        composable(ROUTE_CAMERA) {
            CameraScreen(
                state = state,
                actions = object : CameraActions by viewModel {
                    override fun onOpenSettings() { navController.navigate(ROUTE_SETTINGS) }
                    override fun onOpenDeviceInfo() { navController.navigate(ROUTE_DEVICE_INFO) }
                    override fun onLockFocus(xFraction: Float, yFraction: Float) =
                        viewModel.onLockFocus(xFraction, yFraction)
                    override fun onSelectStylePreset(style: com.adin.naturalcam.domain.StyleState) =
                        viewModel.onSelectStylePreset(style)
                },
                onPreviewViewCreated = viewModel::onPreviewView,
            )
        }
        composable(ROUTE_SETTINGS) {
            SettingsScreen(
                settings = settings,
                actions = object : com.adin.naturalcam.ui.SettingsActions {
                    override fun onSetProfile(profile: com.adin.naturalcam.domain.ProcessingProfile) =
                        viewModel.onSelectProfile(profile)

                    override fun onSetRawMode(rawMode: com.adin.naturalcam.domain.RawMode) =
                        viewModel.onSetRawMode(rawMode)

                    override fun onSetTimerSeconds(seconds: Int) = viewModel.onSetTimerSeconds(seconds)

                    override fun onSetAspectRatio(aspectRatio: com.adin.naturalcam.domain.AspectRatio) =
                        viewModel.onSetAspectRatio(aspectRatio)

                    override fun onSetGeotagging(enabled: Boolean) = viewModel.onSetGeotagging(enabled)

                    override fun onSetGrid(enabled: Boolean) = viewModel.onSetGrid(enabled)
                },
                onBack = { navController.popBackStack() },
                onOpenDeviceInfo = { navController.navigate(ROUTE_DEVICE_INFO) },
            )
        }
        composable(ROUTE_DEVICE_INFO) {
            DeviceInfoScreen(
                capabilitiesPerCamera = viewModel.allCapabilities(),
                onBack = { navController.popBackStack() },
            )
        }
    }
}
