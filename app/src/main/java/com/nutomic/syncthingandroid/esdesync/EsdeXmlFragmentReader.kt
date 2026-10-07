package com.nutomic.syncthingandroid.esdesync

import java.io.File
import java.io.FilterInputStream
import java.io.InputStreamReader
import java.io.Reader
import java.io.StringReader
import java.nio.charset.CodingErrorAction

/** Bounded UTF-8 fragment reader. No full-file byte array or string copies. */
internal class EsdeXmlFragmentReader(file: File, root: String, maxBytes: Long) : Reader() {
    private val source: Reader
    private val parts: List<Reader>
    private var part = 0
    private var doctypeMatch = 0

    init {
        require(file.isFile && file.length() in 1..maxBytes) { "gamelist.xml has invalid size" }
        val stream = object : FilterInputStream(file.inputStream()) {
            var count = 0L
            override fun read(): Int = super.read().also { if (it >= 0) check(++count <= maxBytes) { "XML size limit exceeded" } }
            override fun read(b: ByteArray, off: Int, len: Int): Int = `in`.read(b, off, len).also {
                if (it > 0) { count += it; check(count <= maxBytes) { "XML size limit exceeded" } }
            }
        }
        source = InputStreamReader(stream, Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)).buffered()
        try {
            val prefix = CharArray(4096)
            var size = 0
            while (size < prefix.size) {
                val n = source.read(prefix, size, prefix.size - size)
                if (n < 0) break
                size += n
            }
            val text = String(prefix, 0, size).removePrefix("\uFEFF")
            val fragment = Regex("^\\s*<\\?xml\\s+[^?]*\\?>", RegexOption.IGNORE_CASE).replaceFirst(text, "")
            parts = listOf(StringReader("<$root>"), StringReader(fragment), source, StringReader("</$root>"))
        } catch (error: Throwable) { source.close(); throw error }
    }

    override fun read(buffer: CharArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        while (part < parts.size) {
            val n = parts[part].read(buffer, offset, length)
            if (n < 0) { part++; continue }
            for (index in offset until offset + n) {
                val char = buffer[index].uppercaseChar()
                doctypeMatch = if (char == DOCTYPE[doctypeMatch]) doctypeMatch + 1 else if (char == '<') 1 else 0
                require(doctypeMatch < DOCTYPE.length) { "DOCTYPE is forbidden in gamelist.xml" }
            }
            return n
        }
        return -1
    }
    override fun close() = source.close()
    companion object { private const val DOCTYPE = "<!DOCTYPE" }
}
