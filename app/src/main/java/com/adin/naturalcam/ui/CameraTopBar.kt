package com.adin.naturalcam.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.adin.naturalcam.R
import com.adin.naturalcam.ui.theme.CameraBlack
import com.adin.naturalcam.ui.theme.CameraControl
import com.adin.naturalcam.ui.theme.CameraWhite

/** Shared dark page shell (Scaffold + back TopAppBar) for Settings and Device info. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraPage(
    title: String,
    onBack: () -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        containerColor = CameraBlack,
        topBar = {
            TopAppBar(
                title = { Text(title) },
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
        content = content,
    )
}

/** Recessed panel shared by the settings sections and the capability cards. */
@Composable
fun CameraPanel(
    verticalSpacing: Dp = 10.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = CameraControl,
        border = BorderStroke(1.dp, CameraWhite.copy(alpha = 0.12f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(verticalSpacing),
            content = content,
        )
    }
}
