package me.rerere.ai.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.util.Base64
import android.util.Base64OutputStream
import androidx.core.net.toUri
import me.rerere.ai.ui.UIMessagePart
import okio.BufferedSink
import okio.source
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream

private val supportedTypes = setOf(
    "image/jpeg",
    "image/png",
    "image/gif",
    "image/webp",
)

data class EncodedImage(
    val mimeType: String,
    val base64: String? = null,
    internal val dataUriFile: File? = null,
) {
    init {
        require(!base64.isNullOrBlank() || dataUriFile != null) { "EncodedImage needs inline data or a cache file" }
    }

    /** 需要把整个 data URI 放进内存时用；多图请求应优先 [writeDataUriToJson]。 */
    fun dataUriInline(): String =
        base64 ?: dataUriFile?.readText() ?: error("EncodedImage has no data")

    /** 不含 `data:…;base64,` 前缀的 payload；Claude / Gemini 等接口使用。 */
    fun base64DataOnly(): String {
        val raw = dataUriInline()
        return if (raw.startsWith("data:")) raw.substringAfter(";base64,", raw) else raw
    }

    /** OpenAI `image_url.url` 等字段：流式写入 JSON 字符串，不把整段 base64 载入堆。 */
    internal fun writeDataUriQuoted(stream: JsonStreamWriter) {
        if (base64 != null) {
            stream.writeRawQuotedFromInline(base64)
        } else {
            stream.writeRawQuoted(dataUriFile!!)
        }
    }

    /** 非 JSON 场景下把 data URI 流式写入 sink（如导出、文件上传）。 */
    internal fun writeDataUriTo(sink: BufferedSink) {
        if (base64 != null) {
            sink.writeUtf8(base64)
        } else {
            dataUriFile!!.source().use { sink.writeAll(it) }
        }
    }
}

internal enum class ExifTransformType {
    NONE,
    FLIP_HORIZONTAL,
    ROTATE_180,
    FLIP_VERTICAL,
    TRANSPOSE,
    ROTATE_90,
    TRANSVERSE,
    ROTATE_270,
}

internal fun mapExifOrientationToTransform(orientation: Int): ExifTransformType = when (orientation) {
    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> ExifTransformType.FLIP_HORIZONTAL
    ExifInterface.ORIENTATION_ROTATE_180 -> ExifTransformType.ROTATE_180
    ExifInterface.ORIENTATION_FLIP_VERTICAL -> ExifTransformType.FLIP_VERTICAL
    ExifInterface.ORIENTATION_TRANSPOSE -> ExifTransformType.TRANSPOSE
    ExifInterface.ORIENTATION_ROTATE_90 -> ExifTransformType.ROTATE_90
    ExifInterface.ORIENTATION_TRANSVERSE -> ExifTransformType.TRANSVERSE
    ExifInterface.ORIENTATION_ROTATE_270 -> ExifTransformType.ROTATE_270
    ExifInterface.ORIENTATION_NORMAL,
    ExifInterface.ORIENTATION_UNDEFINED
    -> ExifTransformType.NONE

    else -> ExifTransformType.NONE
}

fun UIMessagePart.Image.encodeBase64(withPrefix: Boolean = true): Result<EncodedImage> = runCatching {
    when {
        this.url.startsWith("file://") -> {
            val filePath =
                this.url.toUri().path ?: throw IllegalArgumentException("Invalid file URI: ${this.url}")
            val file = File(filePath)
            if (!file.exists()) {
                throw IllegalArgumentException("File does not exist: ${this.url}")
            }
            val mimeType = file.guessMimeType().getOrThrow()
            file.compressAndEncode(mimeType, withPrefix = withPrefix)
        }

        this.url.startsWith("data:") -> {
            val mimeType = url.substringAfter("data:").substringBefore(";")
            EncodedImage(mimeType = mimeType, base64 = url)
        }
        this.url.startsWith("http") -> {
            EncodedImage(mimeType = "image/png", base64 = url)
        }
        else -> throw IllegalArgumentException("Unsupported URL format: $url")
    }
}

fun UIMessagePart.Video.encodeBase64(withPrefix: Boolean = true): Result<String> = runCatching {
    when {
        this.url.startsWith("file://") -> {
            val filePath =
                this.url.toUri().path ?: throw IllegalArgumentException("Invalid file URI: ${this.url}")
            val file = File(filePath)
            if (!file.exists()) {
                throw IllegalArgumentException("File does not exist: ${this.url}")
            }
            val encoded = file.encodeToBase64Streaming()
            if (withPrefix) "data:video/mp4;base64,$encoded" else encoded
        }

        else -> throw IllegalArgumentException("Unsupported URL format: $url")
    }
}

fun UIMessagePart.Audio.encodeBase64(withPrefix: Boolean = true): Result<String> = runCatching {
    when {
        this.url.startsWith("file://") -> {
            val filePath =
                this.url.toUri().path ?: throw IllegalArgumentException("Invalid file URI: ${this.url}")
            val file = File(filePath)
            if (!file.exists()) {
                throw IllegalArgumentException("File does not exist: ${this.url}")
            }
            val encoded = file.encodeToBase64Streaming()
            if (withPrefix) "data:audio/mp3;base64,$encoded" else encoded
        }

        else -> throw IllegalArgumentException("Unsupported URL format: $url")
    }
}

private fun File.compressAndEncode(
    mimeType: String,
    withPrefix: Boolean,
    maxDimension: Int = 10_000,
    maxPixels: Long = 16_000_000L,
    quality: Int = 85
): EncodedImage {
    val cacheKey = "$absolutePath|${lastModified()}|${length()}|$maxDimension|$maxPixels|$quality|pfx=$withPrefix"

    val outputMimeType = if (mimeType == "image/gif") mimeType else "image/jpeg"
    ImageEncodeCache.getDataUri(cacheKey)?.let { cached ->
        return EncodedImage(mimeType = outputMimeType, dataUriFile = cached)
    }

    if (mimeType == "image/gif") {
        val dataUriFile = writeDataUriCache(cacheKey, mimeType, withPrefix) { out, prefix ->
            out.write(prefix.toByteArray(Charsets.UTF_8))
            inputStream().use { input ->
                Base64OutputStream(out, Base64.NO_WRAP).use { b64 -> input.copyTo(b64) }
            }
        }
        return EncodedImage(mimeType = mimeType, dataUriFile = dataUriFile)
    }

    ImageEncodeCache.getJpeg(cacheKey)?.let { cachedJpeg ->
        val dataUriFile = writeDataUriCache(cacheKey, "image/jpeg", withPrefix) { out, prefix ->
            out.write(prefix.toByteArray(Charsets.UTF_8))
            cachedJpeg.inputStream().use { input ->
                Base64OutputStream(out, Base64.NO_WRAP).use { b64 -> input.copyTo(b64) }
            }
        }
        return EncodedImage(mimeType = "image/jpeg", dataUriFile = dataUriFile)
    }

    val jpegBytes = compressToJpeg(maxDimension = maxDimension, maxPixels = maxPixels, quality = quality)
    ImageEncodeCache.putJpeg(cacheKey, jpegBytes)
    val dataUriFile = writeDataUriCache(cacheKey, "image/jpeg", withPrefix) { out, prefix ->
        out.write(prefix.toByteArray(Charsets.UTF_8))
        Base64OutputStream(out, Base64.NO_WRAP).use { b64 ->
            b64.write(jpegBytes)
        }
    }
    return EncodedImage(mimeType = "image/jpeg", dataUriFile = dataUriFile)
}

private fun File.writeDataUriCache(
    cacheKey: String,
    mimeType: String,
    withPrefix: Boolean,
    writer: (OutputStream, String) -> Unit,
): File? {
    val prefix = if (withPrefix) "data:$mimeType;base64," else ""
    ImageEncodeCache.putDataUri(cacheKey) { out -> writer(out, prefix) }
    return ImageEncodeCache.getDataUri(cacheKey)
}

private fun File.compressToJpeg(maxDimension: Int, maxPixels: Long, quality: Int): ByteArray {
    // 读取图片尺寸
    val options = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
    }
    BitmapFactory.decodeFile(absolutePath, options)

    options.inSampleSize = calculateImageInSampleSize(
        width = options.outWidth,
        height = options.outHeight,
        maxDimension = maxDimension,
        maxPixels = maxPixels
    )
    options.inJustDecodeBounds = false

    val bitmap = BitmapFactory.decodeFile(absolutePath, options)
        ?: throw IllegalArgumentException("Failed to decode image: $absolutePath")
    val normalizedBitmap = normalizeByExif(bitmap)

    return try {
        val outputStream = ByteArrayOutputStream()
        // 强制使用 JPEG 格式，因为很多提供商不支持 webp
        check(normalizedBitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)) {
            "Failed to compress image: $absolutePath"
        }
        outputStream.toByteArray()
    } finally {
        if (normalizedBitmap !== bitmap) {
            normalizedBitmap.recycle()
        }
        bitmap.recycle()
    }
}

private fun File.normalizeByExif(bitmap: Bitmap): Bitmap {
    val orientation = runCatching {
        ExifInterface(absolutePath).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    val transform = mapExifOrientationToTransform(orientation)
    return applyExifTransform(bitmap, transform)
}

private fun applyExifTransform(bitmap: Bitmap, transform: ExifTransformType): Bitmap {
    if (transform == ExifTransformType.NONE) return bitmap

    val matrix = Matrix()
    when (transform) {
        ExifTransformType.NONE -> return bitmap
        ExifTransformType.FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
        ExifTransformType.ROTATE_180 -> matrix.setRotate(180f)
        ExifTransformType.FLIP_VERTICAL -> matrix.setScale(1f, -1f)
        ExifTransformType.TRANSPOSE -> {
            matrix.setRotate(90f)
            matrix.postScale(-1f, 1f)
        }
        ExifTransformType.ROTATE_90 -> matrix.setRotate(90f)
        ExifTransformType.TRANSVERSE -> {
            matrix.setRotate(270f)
            matrix.postScale(-1f, 1f)
        }
        ExifTransformType.ROTATE_270 -> matrix.setRotate(270f)
    }

    return runCatching {
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }.getOrElse { bitmap }
}

private fun File.encodeToBase64Streaming(): String {
    val byteArrayOutputStream = ByteArrayOutputStream()
    Base64OutputStream(byteArrayOutputStream, Base64.NO_WRAP).use { base64Stream ->
        inputStream().use { input ->
            input.copyTo(base64Stream, bufferSize = 8 * 1024)
        }
    }
    return byteArrayOutputStream.toString(Charsets.ISO_8859_1.name())
}

internal fun calculateImageInSampleSize(
    width: Int,
    height: Int,
    maxDimension: Int,
    maxPixels: Long
): Int {
    if (width <= 0 || height <= 0) return 1

    var inSampleSize = 1
    while (
        (height / inSampleSize) > maxDimension ||
        (width / inSampleSize) > maxDimension ||
        (width.toLong() / inSampleSize) * (height.toLong() / inSampleSize) > maxPixels
    ) {
        inSampleSize *= 2
    }
    return inSampleSize
}

private fun File.guessMimeType(): Result<String> = runCatching {
    inputStream().use { input ->
        val bytes = ByteArray(16)
        val read = input.read(bytes)
        if (read < 12) error("File too short to determine MIME type")

        // 判断 HEIF/HEIC/AVIF 格式：ISO-BMFF 容器，"ftyp" box 位于字节 4..8，主品牌码位于 8..12
        // 新手机的 HDR HEIF 照片常用 heix/hevc/mif1/msf1 等品牌码，而非仅 heic，需全部识别
        if (bytes.copyOfRange(4, 8).toString(Charsets.US_ASCII) == "ftyp") {
            when (bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII)) {
                "heic", "heix", "heim", "heis",
                "hevc", "hevx", "hevm", "hevs",
                "mif1", "msf1", "heif",
                -> return@runCatching "image/heic"

                "avif", "avis" -> return@runCatching "image/avif"
            }
        }

        // 判断 JPEG 格式：开头为 0xFF 0xD8
        if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) {
            return@runCatching "image/jpeg"
        }

        // 判断 PNG 格式：开头为 89 50 4E 47 0D 0A 1A 0A
        if (bytes.copyOfRange(0, 8).contentEquals(
                byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
            )
        ) {
            return@runCatching "image/png"
        }

        // 判断WebP格式：开头为 "RIFF" + 4字节长度 + "WEBP"
        if (bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" && bytes.copyOfRange(8, 12)
                .toString(Charsets.US_ASCII) == "WEBP"
        ) {
            return@runCatching "image/webp"
        }

        // 判断 GIF 格式：开头为 "GIF89a" 或 "GIF87a"
        val header = bytes.copyOfRange(0, 6).toString(Charsets.US_ASCII)
        if (header == "GIF89a" || header == "GIF87a") {
            return@runCatching "image/gif"
        }

        error(
            "Failed to guess MIME type: $header, ${
                bytes.joinToString(",") {
                    it.toUByte().toString()
                }
            }"
        )
    }
}
