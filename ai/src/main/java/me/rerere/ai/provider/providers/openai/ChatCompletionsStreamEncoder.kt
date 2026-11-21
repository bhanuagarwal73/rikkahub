package me.rerere.ai.provider.providers.openai

import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.PartGroup
import me.rerere.ai.provider.providers.groupPartsByToolBoundary
import me.rerere.ai.ui.OpenRouterReasoningMetadata
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.metadataAs
import me.rerere.ai.util.JsonStreamWriter
import me.rerere.ai.util.encodeBase64
import me.rerere.ai.util.json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.OutputStream

internal fun ChatCompletionsAPI.buildChatCompletionStreamingBody(
    messages: List<UIMessage>,
    params: TextGenerationParams,
    providerSetting: ProviderSetting.OpenAI,
    stream: Boolean,
): RequestBody = object : RequestBody() {
    override fun contentType() = "application/json".toMediaType()

    override fun writeTo(sink: BufferedSink) {
        sink.outputStream().use { out ->
            writeChatCompletionRequestStream(out, messages, params, providerSetting, stream)
        }
    }
}

private fun ChatCompletionsAPI.writeChatCompletionRequestStream(
    out: OutputStream,
    messages: List<UIMessage>,
    params: TextGenerationParams,
    providerSetting: ProviderSetting.OpenAI,
    stream: Boolean,
) {
    val envelope = buildChatCompletionRequestEnvelope(messages, params, providerSetting, stream)
    val reasoningReplayFormat = reasoningReplayFormatFor(providerSetting)
    val jsonOut = JsonStreamWriter(out)
    jsonOut.beginObject()
    envelope.forEach { (key, value) ->
        jsonOut.name(key)
        jsonOut.writeElement(value)
    }
    jsonOut.name("messages")
    writeMessagesStream(
        jsonOut = jsonOut,
        messages = messages,
        includeHistoryReasoning = providerSetting.includeHistoryReasoning,
        reasoningReplayFormat = reasoningReplayFormat,
        supportInputModalities = params.model.inputModalities,
    )
    jsonOut.endObject()
    jsonOut.close()
}

private fun writeMessagesStream(
    jsonOut: JsonStreamWriter,
    messages: List<UIMessage>,
    includeHistoryReasoning: Boolean,
    reasoningReplayFormat: ReasoningReplayFormat,
    supportInputModalities: List<Modality>,
) {
    jsonOut.beginArray()
    val filtered = messages.filter { it.isValidToUpload() }
    filtered.forEach { message ->
        if (message.role == MessageRole.ASSISTANT) {
            writeAssistantMessages(
                jsonOut,
                message,
                includeHistoryReasoning,
                reasoningReplayFormat,
                supportInputModalities,
            )
        } else {
            writeNonAssistantMessage(jsonOut, message)
        }
    }
    jsonOut.endArray()
}

private fun writeAssistantMessages(
    jsonOut: JsonStreamWriter,
    message: UIMessage,
    includeReasoning: Boolean,
    reasoningReplayFormat: ReasoningReplayFormat,
    supportInputModalities: List<Modality>,
) {
    val groups = groupPartsByToolBoundary(message.parts)
    val contentBuffer = mutableListOf<UIMessagePart>()
    var reasoningPart: UIMessagePart.Reasoning? = null

    for (group in groups) {
        when (group) {
            is PartGroup.Content -> {
                if (includeReasoning) {
                    group.parts.filterIsInstance<UIMessagePart.Reasoning>().firstOrNull()?.let {
                        reasoningPart = it
                    }
                }
                group.parts
                    .filter { it is UIMessagePart.Text || it is UIMessagePart.Image }
                    .forEach { contentBuffer.add(it) }
            }

            is PartGroup.Tools -> {
                writeAssistantMessageObject(
                    jsonOut,
                    contentBuffer,
                    group.tools,
                    reasoningPart,
                    reasoningReplayFormat,
                )
                contentBuffer.clear()
                reasoningPart = null
                group.tools.forEach { tool ->
                    writeToolResultMessage(jsonOut, tool, supportInputModalities)
                }
            }
        }
    }

    if (contentBuffer.isNotEmpty() || reasoningPart != null) {
        writeAssistantMessageObject(
            jsonOut,
            contentBuffer,
            emptyList(),
            reasoningPart,
            reasoningReplayFormat,
        )
    }
}

private fun writeAssistantMessageObject(
    jsonOut: JsonStreamWriter,
    contentParts: List<UIMessagePart>,
    tools: List<UIMessagePart.Tool>,
    reasoningPart: UIMessagePart.Reasoning?,
    reasoningReplayFormat: ReasoningReplayFormat,
) {
    val hasUsableContent = contentParts.any { part ->
        when (part) {
            is UIMessagePart.Text -> part.text.isNotBlank()
            is UIMessagePart.Image -> part.url.isNotBlank()
            else -> false
        }
    }
    val reasoningDetails = if (reasoningReplayFormat == ReasoningReplayFormat.OPENROUTER_DETAILS) {
        reasoningPart?.metadataAs<OpenRouterReasoningMetadata>()?.reasoningDetails
    } else {
        null
    }
    val hasReasoning = !reasoningPart?.reasoning.isNullOrBlank() || !reasoningDetails.isNullOrEmpty()
    if (!hasUsableContent && !hasReasoning && tools.isEmpty()) return

    jsonOut.beginObject()
    jsonOut.name("role")
    jsonOut.value("assistant")

    val mistralThinking = if (hasReasoning &&
        reasoningReplayFormat == ReasoningReplayFormat.MISTRAL_THINKING_CHUNK
    ) {
        reasoningPart?.reasoning.orEmpty()
    } else {
        null
    }

    if (hasReasoning && mistralThinking == null) {
        if (reasoningDetails.isNullOrEmpty()) {
            jsonOut.name("reasoning_content")
            jsonOut.value(reasoningPart?.reasoning.orEmpty())
        } else {
            jsonOut.name("reasoning_details")
            jsonOut.writeElement(reasoningDetails)
        }
    }

    if (mistralThinking == null && contentParts.isEmpty()) {
        jsonOut.name("content")
        jsonOut.value("")
    } else if (mistralThinking == null && contentParts.size == 1 && contentParts[0] is UIMessagePart.Text) {
        jsonOut.name("content")
        jsonOut.value((contentParts[0] as UIMessagePart.Text).text)
    } else {
        jsonOut.name("content")
        jsonOut.beginArray()
        if (mistralThinking != null) {
            val thinkingBlock = buildJsonObject {
                put("type", "thinking")
                put(
                    "thinking",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("type", "text")
                                put("text", mistralThinking)
                            },
                        )
                    },
                )
            }
            jsonOut.writeElement(thinkingBlock)
        }
        contentParts.forEach { part ->
            when (part) {
                is UIMessagePart.Text -> writeTextContentPart(jsonOut, part.text)
                is UIMessagePart.Image -> writeImageContentPart(jsonOut, part)
                else -> Unit
            }
        }
        jsonOut.endArray()
    }

    if (tools.isNotEmpty()) {
        jsonOut.name("tool_calls")
        jsonOut.beginArray()
        tools.forEach { tool ->
            jsonOut.beginObject()
            jsonOut.name("id")
            jsonOut.value(tool.toolCallId)
            jsonOut.name("type")
            jsonOut.value("function")
            jsonOut.name("function")
            jsonOut.beginObject()
            jsonOut.name("name")
            jsonOut.value(tool.toolName)
            jsonOut.name("arguments")
            jsonOut.value(tool.inputAsJson().toString())
            jsonOut.endObject()
            jsonOut.endObject()
        }
        jsonOut.endArray()
    }
    jsonOut.endObject()
}

private fun writeNonAssistantMessage(jsonOut: JsonStreamWriter, message: UIMessage) {
    jsonOut.beginObject()
    jsonOut.name("role")
    jsonOut.value(message.role.name.lowercase())
    if (message.parts.isOnlyTextPart()) {
        jsonOut.name("content")
        jsonOut.value(message.parts.filterIsInstance<UIMessagePart.Text>().first().text)
    } else {
        jsonOut.name("content")
        jsonOut.beginArray()
        message.parts.forEach { part ->
            when (part) {
                is UIMessagePart.Text -> writeTextContentPart(jsonOut, part.text)
                is UIMessagePart.Image -> writeImageContentPart(jsonOut, part)
                else -> Unit
            }
        }
        jsonOut.endArray()
    }
    jsonOut.endObject()
}

private fun writeToolResultMessage(
    jsonOut: JsonStreamWriter,
    tool: UIMessagePart.Tool,
    supportInputModalities: List<Modality>,
) {
    jsonOut.beginObject()
    jsonOut.name("role")
    jsonOut.value("tool")
    jsonOut.name("tool_call_id")
    jsonOut.value(tool.toolCallId)
    jsonOut.name("content")
    writeToolResultContent(jsonOut, tool, supportInputModalities)
    jsonOut.endObject()
}

private fun writeToolResultContent(
    jsonOut: JsonStreamWriter,
    tool: UIMessagePart.Tool,
    supportInputModalities: List<Modality>,
) {
    val supportsImageInput = Modality.IMAGE in supportInputModalities
    val hasImageToSend = tool.output.any { it is UIMessagePart.Image && supportsImageInput }
    if (!hasImageToSend) {
        val text = tool.output.mapNotNull { part ->
            when (part) {
                is UIMessagePart.Text -> part.text
                is UIMessagePart.Image -> "[Image output omitted: current model does not support image input]"
                else -> null
            }
        }.joinToString("\n")
        jsonOut.value(text)
        return
    }
    jsonOut.beginArray()
    tool.output.forEach { part ->
        when (part) {
            is UIMessagePart.Text -> if (part.text.isNotBlank()) writeTextContentPart(jsonOut, part.text)
            is UIMessagePart.Image -> writeImageContentPart(jsonOut, part)
            else -> Unit
        }
    }
    jsonOut.endArray()
}

private fun writeTextContentPart(jsonOut: JsonStreamWriter, text: String) {
    val element = buildJsonObject {
        put("type", "text")
        put("text", text)
    }
    jsonOut.writeElement(element)
}

private fun writeImageContentPart(jsonOut: JsonStreamWriter, part: UIMessagePart.Image) {
    val encoded = part.encodeBase64().getOrElse {
        it.printStackTrace()
        writeTextContentPart(jsonOut, "")
        return
    }
    jsonOut.beginObject()
    jsonOut.name("type")
    jsonOut.value("image_url")
    jsonOut.name("image_url")
    jsonOut.beginObject()
    jsonOut.name("url")
    encoded.writeDataUriQuoted(jsonOut)
    jsonOut.endObject()
    jsonOut.endObject()
}

private fun List<UIMessagePart>.isOnlyTextPart(): Boolean {
    val gonnaSend = count { it is UIMessagePart.Text || it is UIMessagePart.Image }
    val texts = count { it is UIMessagePart.Text }
    return gonnaSend == texts && texts == 1
}