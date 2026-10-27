package fr.jbrunet.yama.data.arome

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Inflater

/**
 * Raster géoréférencé en lat/lon (EPSG:4326). [values] est rangé ligne par ligne depuis le nord ;
 * ([west], [north]) est le coin nord-ouest du pixel (0, 0).
 */
class Raster(
    val width: Int,
    val height: Int,
    val west: Double,
    val north: Double,
    val resLon: Double,
    val resLat: Double,
    val values: FloatArray,
) {
    operator fun get(x: Int, y: Int) = values[y * width + x]
}

/**
 * Lecteur GeoTIFF minimal pour les réponses WCS de Météo-France : bandes simples en
 * flottants (32/64 bits) ou entiers, non compressées ou Deflate, en bandes ou en tuiles.
 */
object GeoTiff {
    private const val WIDTH = 256
    private const val HEIGHT = 257
    private const val BITS = 258
    private const val COMPRESSION = 259
    private const val STRIP_OFFSETS = 273
    private const val ROWS_PER_STRIP = 278
    private const val STRIP_BYTES = 279
    private const val PREDICTOR = 317
    private const val TILE_WIDTH = 322
    private const val TILE_LENGTH = 323
    private const val TILE_OFFSETS = 324
    private const val TILE_BYTES = 325
    private const val SAMPLE_FORMAT = 339
    private const val PIXEL_SCALE = 33550
    private const val TIEPOINT = 33922
    private const val NODATA = 42113

    /** Côté max accepté (px) : la zone demandée à AROME-PI fait au plus quelques centaines de pixels. */
    private const val MAX_SIDE = 4096

    fun decode(bytes: ByteArray): Raster {
        val buf = ByteBuffer.wrap(bytes)
        buf.order(if (bytes[0] == 'I'.code.toByte()) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
        require(buf.getShort(2).toInt() == 42) { "Not a classic TIFF" }
        val tags = readIfd(buf, buf.getInt(4))

        val width = tags.int(WIDTH)
        val height = tags.int(HEIGHT)
        require(width in 1..MAX_SIDE && height in 1..MAX_SIDE) { "Unexpected TIFF size ($width × $height)" }
        val bits = tags.int(BITS)
        val compression = tags.intOr(COMPRESSION, 1)
        val predictor = tags.intOr(PREDICTOR, 1)
        val format = tags.intOr(SAMPLE_FORMAT, 1) // 1 entier non signé, 2 signé, 3 flottant
        require(compression == 1 || compression == 8 || compression == 32946) { "Unsupported TIFF compression $compression" }
        require(predictor == 1) { "Unsupported TIFF predictor $predictor" }

        val scale = tags.doubles(PIXEL_SCALE)
        val tie = tags.doubles(TIEPOINT)
        val noData = tags[NODATA]?.text?.trim()?.toDoubleOrNull()
        val bytesPerSample = bits / 8
        val values = FloatArray(width * height)

        fun sample(block: ByteBuffer, index: Int): Float {
            val pos = index * bytesPerSample
            val v = when {
                format == 3 && bits == 32 -> block.getFloat(pos).toDouble()
                format == 3 && bits == 64 -> block.getDouble(pos)
                bits == 16 -> if (format == 2) block.getShort(pos).toDouble() else (block.getShort(pos).toInt() and 0xFFFF).toDouble()
                bits == 8 -> if (format == 2) block.get(pos).toDouble() else (block.get(pos).toInt() and 0xFF).toDouble()
                bits == 32 -> block.getInt(pos).toDouble()
                else -> error("Unsupported sample format ($bits bits, type $format)")
            }
            return if (v.isNaN() || (noData != null && v == noData) || v > 1e10) 0f else v.toFloat()
        }

        fun block(offset: Long, length: Long, expected: Int): ByteBuffer {
            require(offset >= 0 && length >= 0 && offset + length <= bytes.size) { "TIFF strip outside the file" }
            val raw = bytes.copyOfRange(offset.toInt(), (offset + length).toInt())
            val data = if (compression == 1) raw else inflate(raw, expected)
            require(data.size >= expected) { "Truncated TIFF strip" }
            return ByteBuffer.wrap(data).order(buf.order())
        }

        if (tags[TILE_OFFSETS] != null) {
            val tw = tags.int(TILE_WIDTH)
            val th = tags.int(TILE_LENGTH)
            val offsets = tags.longs(TILE_OFFSETS)
            val lengths = tags.longs(TILE_BYTES)
            val across = (width + tw - 1) / tw
            offsets.indices.forEach { t ->
                val b = block(offsets[t], lengths[t], tw * th * bytesPerSample)
                val tx = (t % across) * tw
                val ty = (t / across) * th
                for (y in 0 until th) for (x in 0 until tw) {
                    val gx = tx + x
                    val gy = ty + y
                    if (gx < width && gy < height) values[gy * width + gx] = sample(b, y * tw + x)
                }
            }
        } else {
            val rowsPerStrip = tags.intOr(ROWS_PER_STRIP, height)
            val offsets = tags.longs(STRIP_OFFSETS)
            val lengths = tags.longs(STRIP_BYTES)
            offsets.indices.forEach { s ->
                val b = block(offsets[s], lengths[s], minOf(rowsPerStrip, height - s * rowsPerStrip) * width * bytesPerSample)
                val first = s * rowsPerStrip
                for (y in 0 until rowsPerStrip) {
                    val gy = first + y
                    if (gy >= height) break
                    for (x in 0 until width) values[gy * width + x] = sample(b, y * width + x)
                }
            }
        }

        // Tiepoint : pixel (i, j) ↔ (X, Y) ; avec l'échelle on obtient le coin nord-ouest.
        val west = tie[3] - tie[0] * scale[0]
        val north = tie[4] + tie[1] * scale[1]
        return Raster(width, height, west, north, scale[0], scale[1], values)
    }

    /** Décompresse au plus [max] octets : un fichier corrompu ne peut pas saturer la mémoire. */
    private fun inflate(data: ByteArray, max: Int): ByteArray {
        val inflater = Inflater()
        try {
            inflater.setInput(data)
            val out = ByteArray(max)
            var size = 0
            while (size < max && !inflater.finished()) {
                val n = inflater.inflate(out, size, max - size)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                size += n
            }
            return if (size == max) out else out.copyOf(size)
        } finally {
            inflater.end()
        }
    }

    private class Tag(val numbers: List<Double>, val text: String?)

    private fun Map<Int, Tag>.int(id: Int) = requireNotNull(this[id]) { "Missing TIFF tag $id" }.numbers[0].toInt()
    private fun Map<Int, Tag>.intOr(id: Int, default: Int) = this[id]?.numbers?.firstOrNull()?.toInt() ?: default
    private fun Map<Int, Tag>.longs(id: Int) = requireNotNull(this[id]) { "Missing TIFF tag $id" }.numbers.map { it.toLong() }
    private fun Map<Int, Tag>.doubles(id: Int) = requireNotNull(this[id]) { "Missing georeferencing (tag $id)" }.numbers

    private fun readIfd(buf: ByteBuffer, offset: Int): Map<Int, Tag> {
        val count = buf.getShort(offset).toInt() and 0xFFFF
        return (0 until count).associate { i ->
            val entry = offset + 2 + i * 12
            val id = buf.getShort(entry).toInt() and 0xFFFF
            val type = buf.getShort(entry + 2).toInt()
            val n = buf.getInt(entry + 4)
            val size = when (type) { 1, 2, 6, 7 -> 1; 3, 8 -> 2; 4, 9, 11 -> 4; 5, 10, 12 -> 8; 16 -> 8; else -> 1 }
            val dataPos = if (n * size <= 4) entry + 8 else buf.getInt(entry + 8)
            val numbers = if (type == 2) emptyList() else (0 until n).map { k ->
                val p = dataPos + k * size
                when (type) {
                    1, 7 -> (buf.get(p).toInt() and 0xFF).toDouble()
                    3 -> (buf.getShort(p).toInt() and 0xFFFF).toDouble()
                    4 -> (buf.getInt(p).toLong() and 0xFFFFFFFFL).toDouble()
                    16 -> buf.getLong(p).toDouble()
                    11 -> buf.getFloat(p).toDouble()
                    12 -> buf.getDouble(p)
                    5 -> (buf.getInt(p).toLong() and 0xFFFFFFFFL).toDouble() / (buf.getInt(p + 4).toLong() and 0xFFFFFFFFL)
                    else -> 0.0
                }
            }
            val text = if (type == 2) String(ByteArray(n) { buf.get(dataPos + it) }).trimEnd('\u0000') else null
            id to Tag(numbers, text)
        }
    }
}
