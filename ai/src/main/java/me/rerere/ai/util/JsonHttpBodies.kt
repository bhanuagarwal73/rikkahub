package me.rerere.ai.util

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToStream
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.OutputStream

/**
 * 把 [JsonObject] 流式写入 HTTP 请求体，避免 [kotlinx.serialization.json.Json.encodeToString]
 * 再分配一整块与 JSON 等大的字符串（多图对话时可能触发 OOM）。
 */
fun JsonObject.toStreamingRequestBody(json: kotlinx.serialization.json.Json = me.rerere.ai.util.json): RequestBody =
    object : RequestBody() {
        override fun contentType() = "application/json".toMediaType()

        override fun writeTo(sink: BufferedSink) {
            sink.outputStream().use { out ->
                json.encodeToStream(JsonObject.serializer(), this@toStreamingRequestBody, out)
            }
        }
    }

/** 把小段 [JsonElement] 写入已打开的 JSON 对象（值本身，不含外层花括号）。 */
internal fun kotlinx.serialization.json.Json.writeJsonValue(element: JsonElement, sink: OutputStream) {
    encodeToStream(JsonElement.serializer(), element, sink)
}