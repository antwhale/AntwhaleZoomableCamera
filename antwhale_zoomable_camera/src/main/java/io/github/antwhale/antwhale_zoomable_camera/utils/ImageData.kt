package io.github.antwhale.antwhale_zoomable_camera.utils

import android.content.Context
import android.hardware.camera2.CaptureResult
import android.media.Image
import java.io.Closeable
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

data class CombinedCaptureResult(
    val image: Image,
    val metadata: CaptureResult,
    val orientation: Int,
    val format: Int
) : Closeable {
    override fun close() = image.close()
}

