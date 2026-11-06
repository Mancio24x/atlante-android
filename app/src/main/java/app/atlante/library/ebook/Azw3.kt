package app.atlante.library.ebook

import app.atlante.library.model.Book
import app.atlante.library.model.BookMatcher
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.charset.Charset

/**
 * AZW3 (Kindle KF8) and MOBI files without DRM: PalmDB container, MOBI header and EXTH metadata.
 *
 * Supported: title, authors, publisher, language, ISBN, date, description, subjects, embedded cover, and the
 * text when it uses no compression or PalmDOC compression (what Calibre and most DRM-free files use).
 * Not supported, and said so: DRM-protected files (refused), HUFF/CDIC compression (the book is catalogued
 * but cannot be read in Atlante), the Kindle table of contents.
 */
object Azw3 {
    class Mobi(
        val records: List<ByteArray>, val compression: Int, val textLength: Int, val textRecords: Int, val encrypted: Boolean,
        val version: Int, val charset: Charset, val firstImage: Int, val extraFlags: Int, val exth: Map<Int, List<ByteArray>>, val fullName: String,
    ) {
        fun string(type: Int) = exth[type]?.firstOrNull()?.let { String(it, charset).trim() }?.takeIf { it.isNotEmpty() }
        fun strings(type: Int) = exth[type]?.map { String(it, charset).trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        fun int(type: Int) = exth[type]?.firstOrNull()?.takeIf { it.size == 4 }?.let { u32(it, 0) }
        val readable get() = compression == 1 || compression == 2
    }

    const val HUFF_CDIC = 17480
    private const val MAX_SIZE = 256L * 1024 * 1024

    @JvmStatic
    fun open(file: File): Mobi {
        if (file.length() > MAX_SIZE) throw IOException("File oltre 256 MB")
        val data = RandomAccessFile(file, "r").use { f -> ByteArray(f.length().toInt()).also { f.readFully(it) } }
        if (data.size < 86 || String(data, 60, 8, Charsets.ISO_8859_1) != "BOOKMOBI")
            throw IOException("Non è un file Kindle (AZW3/MOBI) valido")
        val count = u16(data, 76)
        if (count < 2 || 78 + count * 8 > data.size) throw IOException("File Kindle danneggiato")
        val offsets = (0 until count).map { u32(data, 78 + it * 8) } + data.size
        val records = (0 until count).map { i ->
            val from = offsets[i]; val to = offsets[i + 1]
            if (from < 78 + count * 8 || to > data.size || to < from) throw IOException("File Kindle danneggiato")
            data.copyOfRange(from, to)
        }
        val r0 = records[0]
        if (r0.size < 132 || String(r0, 16, 4, Charsets.ISO_8859_1) != "MOBI") throw IOException("Intestazione MOBI mancante")
        val headerLength = u32(r0, 20)
        if(headerLength<116 || headerLength>r0.size-16) throw IOException("Intestazione Kindle danneggiata")
        val charset = if (u32(r0, 28) == 65001) Charsets.UTF_8 else Charset.forName("windows-1252")
        val exth = HashMap<Int, MutableList<ByteArray>>()
        if (u32(r0, 128) and 0x40 != 0) {
            var p = 16 + headerLength
            if (p + 12 <= r0.size && String(r0, p, 4, Charsets.ISO_8859_1) == "EXTH") {
                val n = u32(r0, p + 8); p += 12
                if(n<0 || n>4096)throw IOException("Metadati Kindle danneggiati")
                repeat(n) {
                    if (p + 8 > r0.size) throw IOException("Metadati Kindle troncati")
                    val type = u32(r0, p); val len = u32(r0, p + 4)
                    if (len < 8 || len > r0.size-p) throw IOException("Metadati Kindle danneggiati")
                    exth.getOrPut(type) { ArrayList() } += r0.copyOfRange(p + 8, p + len); p += len
                }
            }
        }
        val nameOffset = u32(r0, 84); val nameLength = u32(r0, 88)
        val fullName = if (nameOffset in 0 until r0.size && nameLength in 1..1000 && nameOffset + nameLength <= r0.size) String(r0, nameOffset, nameLength, charset) else ""
        return Mobi(records, u16(r0, 0), u32(r0, 4), u16(r0, 8), u16(r0, 12) != 0, u32(r0, 36), charset,
            u32(r0, 108), if (headerLength >= 0xE4 && r0.size >= 244) u16(r0, 242) else 0, exth, fullName)
    }

    /** Fills the book with what the file really contains; nothing is guessed. */
    @JvmStatic
    fun metadata(file: File, cover: File?, book: Book) {
        val m = open(file)
        if (m.encrypted) throw IOException("Questo file Kindle è protetto da DRM: Atlante non può aprirlo né importarlo.")
        (m.string(503) ?: m.fullName.takeIf { it.isNotBlank() })?.let { book.title = it }
        m.strings(100).takeIf { it.isNotEmpty() }?.let { book.author = it.joinToString(" & ") }
        m.string(101)?.let { book.publisher = it }
        m.string(524)?.let { book.language = it }
        m.string(106)?.let { book.year = it.take(10) }
        m.string(103)?.let { book.description = EbookParser.plain(it) }
        m.strings(105).takeIf { it.isNotEmpty() }?.let { book.genre = it.joinToString(", ") }
        m.string(104)?.let { raw -> BookMatcher.isbns(raw).firstOrNull()?.let { book.isbn = it } }
        m.string(113)?.let { asin -> runCatching { book.meta.put("ids", (book.meta.optJSONObject("ids") ?: org.json.JSONObject()).put("asin", asin)) } }
        if (!m.readable) runCatching { book.meta.put("readable", false) }
        if (cover != null) coverImage(m)?.let { cover.writeBytes(it) }
    }

    /** Embedded cover: EXTH 201 (cover offset) or 202 (thumbnail), counted from the first image record. */
    fun coverImage(m: Mobi): ByteArray? {
        val offset = m.int(201) ?: m.int(202) ?: return null
        val index = m.firstImage + offset
        val image = m.records.getOrNull(index) ?: return null
        val jpeg = image.size > 3 && image[0] == 0xFF.toByte() && image[1] == 0xD8.toByte()
        val png = image.size > 8 && image[0] == 0x89.toByte() && image[1] == 'P'.code.toByte()
        val gif = image.size > 6 && String(image, 0, 3, Charsets.ISO_8859_1) == "GIF"
        return if (jpeg || png || gif) image else null
    }

    /** The book's text (markup removed by the caller); refuses what cannot be decoded. */
    @JvmStatic
    fun text(file: File): String {
        val m = open(file)
        if (m.encrypted) throw IOException("Questo file Kindle è protetto da DRM: Atlante non può aprirlo.")
        if (m.compression == HUFF_CDIC) throw IOException("Questo file Kindle usa una compressione (HUFF/CDIC) che Atlante non sa leggere: aprilo con un’altra app o convertilo in EPUB.")
        if (!m.readable) throw IOException("Compressione Kindle sconosciuta (${m.compression})")
        val out = java.io.ByteArrayOutputStream()
        for (i in 1..m.textRecords) {
            val record = m.records.getOrNull(i) ?: break
            val body = record.copyOf(record.size - trailing(record, m.extraFlags))
            out.write(if (m.compression == 2) palmDoc(body) else body)
            if(out.size()>32*1024*1024)throw IOException("Testo Kindle troppo grande")
            if (out.size() >= m.textLength) break
        }
        val bytes = out.toByteArray().let { if (it.size > m.textLength && m.textLength > 0) it.copyOf(m.textLength) else it }
        return String(bytes, m.charset)
    }

    /** Size of the extra data appended to a text record (MOBI "extra data flags"). */
    fun trailing(record: ByteArray, flags: Int): Int {
        var size = record.size; var total = 0
        var f = flags shr 1
        while (f != 0) {
            if (f and 1 != 0) {
                var value = 0; var shift = 0; var p = size - total
                while (p > 0) {
                    val b = record[p - 1].toInt() and 0xFF
                    value = value or ((b and 0x7F) shl shift); shift += 7; p--
                    if (b and 0x80 != 0 || shift >= 28) break
                }
                total += value
            }
            f = f shr 1
        }
        if (flags and 1 != 0 && size - total - 1 >= 0) total += (record[size - total - 1].toInt() and 0x3) + 1
        return total.coerceIn(0, size)
    }

    /** PalmDOC LZ77 decompression. */
    fun palmDoc(input: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(input.size * 2)
        var buffer = ByteArray(8192); var n = 0
        fun put(b: Int) { if(n>=32*1024*1024)throw IOException("Testo Kindle troppo grande"); if (n == buffer.size) buffer = buffer.copyOf(n * 2); buffer[n++] = b.toByte() }
        var i = 0
        while (i < input.size) {
            val c = input[i++].toInt() and 0xFF
            when {
                c == 0 || c in 0x09..0x7F -> put(c)
                c in 0x01..0x08 -> repeat(c) { if (i < input.size) put(input[i++].toInt() and 0xFF) }
                c in 0xC0..0xFF -> { put(' '.code); put(c xor 0x80) }
                else -> {   // 0x80..0xBF: back reference
                    if (i >= input.size) break
                    val pair = (c shl 8) or (input[i++].toInt() and 0xFF)
                    val distance = (pair shr 3) and 0x7FF; val length = (pair and 7) + 3
                    if (distance == 0 || distance > n) continue
                    repeat(length) { put(buffer[n - distance].toInt() and 0xFF) }
                }
            }
        }
        out.write(buffer, 0, n)
        return out.toByteArray()
    }

    private fun u16(b: ByteArray, p: Int) = ((b[p].toInt() and 0xFF) shl 8) or (b[p + 1].toInt() and 0xFF)
    private fun u32(b: ByteArray, p: Int) = ((b[p].toInt() and 0xFF) shl 24) or ((b[p + 1].toInt() and 0xFF) shl 16) or ((b[p + 2].toInt() and 0xFF) shl 8) or (b[p + 3].toInt() and 0xFF)
}
