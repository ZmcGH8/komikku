package eu.kanade.domain.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import logcat.LogPriority
import mihon.app.di.globalAppGraph
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import java.util.concurrent.TimeUnit

// KMK -->
/**
 * Houri+ (custom build): asks the AI the user set up for translation (Settings → Translation) a short
 * question, for library helpers like matching titles across sources. Uses the on-device model when that is
 * the chosen provider and it is downloaded, otherwise the cloud provider when it has an API key.
 * Returns null when no AI is set up or the request fails; callers must treat that as "don't know".
 */
object HouriPlusAi {
    private val json = Json { ignoreUnknownKeys = true }

    /** One request at a time: on-device models can't run two, and cloud free tiers are rate limited. */
    private val semaphore = Semaphore(1)

    fun isAvailable(): Boolean = runCatching {
        val prefs = globalAppGraph.translationPreferences
        val local = globalAppGraph.localLlmManager
        if (local.isLocalProvider()) {
            local.isModelReady()
        } else {
            prefs.provider().get().lowercase() in CLOUD_PROVIDERS && prefs.effectiveApiKey().isNotBlank()
        }
    }.getOrDefault(false)

    suspend fun ask(prompt: String): String? = semaphore.withPermit {
        try {
            withIOContext {
                val prefs = globalAppGraph.translationPreferences
                val local = globalAppGraph.localLlmManager
                if (local.isLocalProvider()) {
                    if (!local.isModelReady()) return@withIOContext null
                    if (!local.isRunning()) local.start()
                    return@withIOContext local.generate(prompt)
                }
                val provider = prefs.provider().get().lowercase()
                val apiKey = prefs.effectiveApiKey()
                if (apiKey.isBlank()) return@withIOContext null
                val model = prefs.effectiveModel()
                when (provider) {
                    "gemini" -> gemini(prompt, apiKey, model)
                    "openrouter" -> openAiCompatible(prompt, apiKey, model, "https://openrouter.ai/api/v1")
                    "opencode_zen" -> openAiCompatible(prompt, apiKey, model, "https://opencode.ai/zen/v1")
                    "nvidia_nim" -> openAiCompatible(prompt, apiKey, model, "https://integrate.api.nvidia.com/v1")
                    "custom_openai" -> openAiCompatible(prompt, apiKey, model, prefs.customBaseUrl().get())
                    else -> null
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logcat(LogPriority.WARN, e) { "Houri+ AI request failed" }
            null
        }
    }

    /** Asks a yes/no question; null when the AI isn't available or the answer is unclear. */
    suspend fun askYesNo(prompt: String): Boolean? {
        val answer = ask(prompt)?.trim()?.uppercase() ?: return null
        val word = Regex("""\b(YES|NO)\b""").find(answer)?.value ?: return null
        return word == "YES"
    }

    private val client by lazy {
        globalAppGraph.networkHelper.client.newBuilder().callTimeout(45, TimeUnit.SECONDS).build()
    }

    private fun openAiCompatible(prompt: String, apiKey: String, model: String, baseUrl: String): String? {
        val base = baseUrl.trim().trimEnd('/')
        if (!base.startsWith("https://")) return null
        val body = buildJsonObject {
            put("model", model)
            putJsonArray("messages") {
                add(
                    buildJsonObject {
                        put("role", "user")
                        put("content", prompt)
                    },
                )
            }
            put("max_tokens", 64)
            put("temperature", 0)
        }.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("$base/chat/completions")
            .post(body)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val root = json.parseToJsonElement(response.body.string()).jsonObject
            return root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("message")?.jsonObject
                ?.get("content")?.jsonPrimitive?.contentOrNull
        }
    }

    private fun gemini(prompt: String, apiKey: String, model: String): String? {
        val name = model.ifBlank { "gemini-1.5-flash" }.substringAfterLast("/").substringBefore(":").trim()
        val geminiModel = if (name.isBlank() || !name.contains("gemini", ignoreCase = true)) "gemini-1.5-flash" else name
        val body = buildJsonObject {
            putJsonObject("generationConfig") {
                put("temperature", 0)
                put("maxOutputTokens", 64)
            }
            putJsonArray("contents") {
                add(
                    buildJsonObject {
                        putJsonArray("parts") {
                            add(buildJsonObject { put("text", prompt) })
                        }
                    },
                )
            }
        }.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$geminiModel:generateContent?key=$apiKey")
            .post(body)
            .header("Content-Type", "application/json")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val root = json.parseToJsonElement(response.body.string()).jsonObject
            return root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("content")?.jsonObject
                ?.get("parts")?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("text")?.jsonPrimitive?.contentOrNull
        }
    }

    private val CLOUD_PROVIDERS = setOf("gemini", "openrouter", "opencode_zen", "nvidia_nim", "custom_openai")
}
// KMK <--
