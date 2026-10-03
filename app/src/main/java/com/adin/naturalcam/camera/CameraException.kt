package com.adin.naturalcam.camera

import com.adin.naturalcam.domain.CameraError

/** Backend-level failure carrying a typed domain error (mapped again at the use-case boundary). */
class CameraException(val error: CameraError) : Exception(error.detail ?: error.toString())
