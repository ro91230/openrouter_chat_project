
package com.openrouter.chat

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

data class ModelListResponse(
    @SerializedName("data") val data: List<OpenRouterModel>
)

data class OpenRouterModel(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("pricing") val pricing: OpenRouterPricing?
)

data class OpenRouterPricing(
    @SerializedName("prompt") val prompt: String?,
    @SerializedName("completion") val completion: String?
)

data class ChatCompletionMessage(
    @SerializedName("role") val role: String,
    @SerializedName("content") val content: String
)

data class ChatCompletionRequest(
    @SerializedName("model") val model: String,
    @SerializedName("messages") val messages: List<ChatCompletionMessage>,
    @SerializedName("stream") val stream: Boolean = true
)

data class StreamChunk(
    @SerializedName("choices") val choices: List<StreamChoice>?,
    @SerializedName("usage") val usage: StreamUsage?
)

data class StreamChoice(
    @SerializedName("delta") val delta: StreamDelta?
)

data class StreamDelta(
    @SerializedName("content") val content: String?
)

data class StreamUsage(
    @SerializedName("prompt_tokens") val promptTokens: Int?,
    @SerializedName("completion_tokens") val completionTokens: Int?
)

class OpenRouterService(private val gson: Gson) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun fetchModels(): List<ModelPricing> {
        val request = Request.Builder()
            .url("https://openrouter.ai/api/v1/models")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("Failed to fetch models: ${response.code}")
            val body = response.body?.string() ?: throw Exception("Empty response body")
            val parsed = gson.fromJson(body, ModelListResponse::class.java)
            return parsed.data.map { model ->
                ModelPricing(
                    id = model.id,
                    name = model.name,
                    promptCost = model.pricing?.prompt?.toDoubleOrNull() ?: 0.0,
                    completionCost = model.pricing?.completion?.toDoubleOrNull() ?: 0.0,
                    lastUpdated = System.currentTimeMillis()
                )
            }
        }
    }

    fun streamChatCompletion(
        apiKey: String,
        model: String,
        messages: List<ChatCompletionMessage>
    ): Flow<StreamResponseState> = flow {
        val payload = ChatCompletionRequest(model = model, messages = messages, stream = true)
        val jsonPayload = gson.toJson(payload)

        val request = Request.Builder()
            .url("https://openrouter.ai/api/v1/chat/completions")
            .post(jsonPayload.toRequestBody("application/json".toMediaType()))
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("HTTP-Referer", "https://github.com/openrouter/chat")
            .addHeader("X-Title", "OpenRouter Elite Client")
            .build()

        client.newCall(request).execute().use { response ->
            val code = response.code
            if (!response.isSuccessful) {
                emit(StreamResponseState.Error(code, response.body?.string() ?: "Network Connection Error"))
                return@flow
            }

            val inputStream = response.body?.byteStream() ?: throw Exception("Response body is null")
            val reader = BufferedReader(InputStreamReader(inputStream))
            var line: String?

            while (reader.readLine().also { line = it } != null) {
                val currentLine = line ?: break
                if (currentLine.startsWith("data: ")) {
                    val data = currentLine.substring(6).trim()
                    if (data == "[DONE]") {
                        break
                    }
                    try {
                        val chunk = gson.fromJson(data, StreamChunk::class.java)
                        val content = chunk?.choices?.firstOrNull()?.delta?.content
                        val usage = chunk?.usage

                        if (content != null) {
                            emit(StreamResponseState.Content(content))
                        }
                        if (usage != null) {
                            emit(
                                StreamResponseState.Usage(
                                    promptTokens = usage.promptTokens ?: 0,
                                    completionTokens = usage.completionTokens ?: 0,
                                    statusCode = code
                                )
                            )
                        }
                    } catch (e: Exception) {
                        // Suppress parsing deviations
                    }
                }
            }
            emit(StreamResponseState.Completed)
        }
    }.flowOn(Dispatchers.IO)
}

sealed interface StreamResponseState {
    data class Content(val text: String) : StreamResponseState
    data class Usage(val promptTokens: Int, val completionTokens: Int, val statusCode: Int) : StreamResponseState
    data class Error(val code: Int, val message: String) : StreamResponseState
    data object Completed : StreamResponseState
}
