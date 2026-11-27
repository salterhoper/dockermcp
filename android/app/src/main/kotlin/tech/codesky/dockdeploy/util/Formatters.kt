package tech.codesky.dockdeploy.util

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import tech.codesky.dockdeploy.data.model.Capabilities
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow

object Formatters {

    private val json = Json { ignoreUnknownKeys = true }

    fun parseCapabilities(obj: JsonObject?): Capabilities? {
        if (obj == null || obj.isEmpty()) return null
        return runCatching { json.decodeFromJsonElement<Capabilities>(obj) }.getOrNull()
    }

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val exp = (ln(bytes.toDouble()) / ln(1024.0)).toInt().coerceIn(0, units.lastIndex)
        val value = bytes / 1024.0.pow(exp.toDouble())
        return if (exp == 0) "$bytes B" else String.format(Locale.US, "%.1f %s", value, units[exp])
    }

    fun formatUptime(seconds: Long): String {
        if (seconds <= 0) return "—"
        val days = seconds / 86400
        val hours = (seconds % 86400) / 3600
        val minutes = (seconds % 3600) / 60
        return when {
            days > 0 -> "${days}d ${hours}h"
            hours > 0 -> "${hours}h ${minutes}m"
            else -> "${minutes}m ${seconds % 60}s"
        }
    }

    fun formatRelative(iso: String?): String {
        if (iso.isNullOrBlank()) return "never"
        val instant = runCatching { Instant.parse(iso) }.getOrNull() ?: return iso
        val diff = Duration.between(instant, Instant.now())
        val secs = diff.seconds
        return when {
            secs < 0 -> "just now"
            secs < 60 -> "${secs}s ago"
            secs < 3600 -> "${secs / 60}m ago"
            secs < 86400 -> "${secs / 3600}h ago"
            secs < 86400 * 30 -> "${secs / 86400}d ago"
            else -> DateTimeFormatter.ofPattern("MMM d, yyyy")
                .withZone(ZoneId.systemDefault())
                .format(instant)
        }
    }

    fun formatRelativeEpochSeconds(epochSeconds: Long): String {
        if (epochSeconds <= 0) return "—"
        val diff = Duration.between(Instant.ofEpochSecond(epochSeconds), Instant.now())
        val secs = diff.seconds
        return when {
            secs < 60 -> "${secs}s ago"
            secs < 3600 -> "${secs / 60}m ago"
            secs < 86400 -> "${secs / 3600}h ago"
            else -> "${secs / 86400}d ago"
        }
    }

    fun formatDuration(startedAt: String?, finishedAt: String?): String {
        if (startedAt.isNullOrBlank()) return "—"
        val start = runCatching { Instant.parse(startedAt) }.getOrNull() ?: return "—"
        val end = finishedAt?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: Instant.now()
        val secs = Duration.between(start, end).seconds.coerceAtLeast(0)
        return if (secs < 60) "${secs}s" else "${secs / 60}m ${secs % 60}s"
    }

    private val ansiRegex = Regex("\u001B\\[[0-9;?]*[A-Za-z]|\u001B\\][^\u0007]*\u0007|\u001B[@-Z\\\\-_]")

    fun stripAnsi(raw: String): String = ansiRegex.replace(raw, "")

    fun generateQrBitmap(content: String, sizePx: Int = 512): Bitmap? {
        return runCatching {
            val hints = mapOf(EncodeHintType.MARGIN to 1)
            val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
            val pixels = IntArray(sizePx * sizePx)
            for (y in 0 until sizePx) {
                val offset = y * sizePx
                for (x in 0 until sizePx) {
                    pixels[offset + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
                }
            }
            Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, sizePx, 0, 0, sizePx, sizePx)
            }
        }.getOrNull()
    }
}
