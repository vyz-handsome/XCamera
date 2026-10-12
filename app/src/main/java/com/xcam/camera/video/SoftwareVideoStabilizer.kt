package com.xcam.camera.video

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File

/**
 * Offline software EIS for captured MP4 files.
 * The FFmpeg deshake filter estimates inter-frame motion and applies corrective transforms.
 * This is post-processing, so it adds processing time and crops/mirrors edges rather than
 * stabilizing the live camera sensor preview.
 */
object SoftwareVideoStabilizer {
    private const val FILTER = "deshake=rx=24:ry=24:blocksize=8:contrast=125:search=less:edge=mirror,crop=iw-48:ih-48,scale=iw+48:ih+48"

    /** Must be called on a worker thread; never invoke this from the Android main thread. */
    fun stabilize(input: File, output: File): Boolean {
        if (!input.isFile || input.length() < 1024L) return false
        output.parentFile?.mkdirs()

        // Prefer Android's H.264 MediaCodec encoder to keep processing practical on phones.
        // Fall back to software encoders if a device's MediaCodec encoder rejects this stream.
        val encoders = listOf("h264_mediacodec", "libopenh264", "mpeg4")
        for (encoder in encoders) {
            output.delete()
            val command = buildString {
                append("-y -hide_banner -i ").append(quote(input.absolutePath))
                append(" -map 0:v:0 -map 0:a? -vf ").append(quote(FILTER))
                append(" -c:v ").append(encoder)
                append(" -b:v 16M -pix_fmt yuv420p -c:a copy -map_metadata 0 -movflags +faststart ")
                append(quote(output.absolutePath))
            }
            val session = try {
                FFmpegKit.execute(command)
            } catch (_: Exception) {
                null
            }
            if (session != null && ReturnCode.isSuccess(session.returnCode) &&
                output.isFile && output.length() > 1024L) {
                return true
            }
        }
        output.delete()
        return false
    }

    private fun quote(pathOrFilter: String): String =
        "'" + pathOrFilter.replace("'", "'\\''") + "'"
}
