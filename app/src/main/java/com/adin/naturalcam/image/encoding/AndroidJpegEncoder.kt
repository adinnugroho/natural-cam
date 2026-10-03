package com.adin.naturalcam.image.encoding

import android.graphics.Bitmap
import com.adin.naturalcam.image.core.EncodedImage
import com.adin.naturalcam.image.core.JpegEncoder
import java.io.ByteArrayOutputStream

/** Platform JPEG encoder (PRD 24: encoding stays separate from processing). */
class AndroidJpegEncoder : JpegEncoder {

    override fun encode(argb: IntArray, width: Int, height: Int, quality: Int): EncodedImage {
        require(width > 0 && height > 0) { "Invalid dimensions: ${width}x$height" }
        require(argb.size >= width * height) { "Pixel buffer too small: ${argb.size} < ${width * height}" }
        val bitmap = Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888)
        try {
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            return EncodedImage(out.toByteArray(), width, height)
        } finally {
            bitmap.recycle()
        }
    }
}
