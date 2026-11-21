package me.rerere.ai.util

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToStream
import java.io.OutputStream
import java.io.OutputStreamWriter

/**
 * 把 JSON 结构增量写入 [OutputStream]；字符串字段用 [quoteString] 做转义，
 * 大段 data URI 用 [writeRawQuoted] 从文件流式拷贝。
 */
@OptIn(ExperimentalSerializationApi::class)
internal class JsonStreamWriter(private val out: OutputStream) {
    private val writer = OutputStreamWriter(out, Charsets.UTF_8)
    private var needComma = false

    fun beginObject() {
        writeSeparator()
        writer.write("{")
        needComma = false
        flush()
    }

    fun endObject() {
        writer.write("}")
        needComma = true
        flush()
    }

    fun beginArray() {
        writeSeparator()
        writer.write("[")
        needComma = false
        flush()
    }

    fun endArray() {
        writer.write("]")
        needComma = true
        flush()
    }

    fun name(key: String) {
        writeSeparator()
        quoteString(key)
        writer.write(":")
        needComma = false
        flush()
    }

    fun value(text: String) {
        writeSeparator()
        quoteString(text)
        needComma = true
        flush()
    }

    fun value(number: Number) {
        writeSeparator()
        writer.write(number.toString())
        needComma = true
        flush()
    }

    fun value(boolean: Boolean) {
        writeSeparator()
        writer.write(if (boolean) "true" else "false")
        needComma = true
        flush()
    }

    fun writeElement(element: JsonElement) {
        writeSeparator()
        json.encodeToStream(JsonElement.serializer(), element, out)
        needComma = true
        flush()
    }

    fun writeRawQuoted(content: java.io.File) {
        writeSeparator()
        writer.write("\"")
        flush()
        content.inputStream().use { it.copyTo(out, bufferSize = 8 * 1024) }
        writer.write("\"")
        needComma = true
        flush()
    }

    fun writeRawQuotedFromInline(text: String) {
        writeSeparator()
        quoteString(text)
        needComma = true
        flush()
    }

    fun close() {
        writer.flush()
    }

    private fun writeSeparator() {
        if (needComma) writer.write(",")
    }

    private fun quoteString(text: String) {
        writer.write("\"")
        text.forEach { ch ->
            when (ch) {
                '\\' -> writer.write("\\\\")
                '"' -> writer.write("\\\"")
                '\n' -> writer.write("\\n")
                '\r' -> writer.write("\\r")
                '\t' -> writer.write("\\t")
                else -> writer.write(ch.toString())
            }
        }
        writer.write("\"")
    }

    private fun flush() {
        writer.flush()
        out.flush()
    }
}