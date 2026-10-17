package com.jerreader.android.translation

import com.jerreader.shared.translation.DirectAIProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.time.Instant

data class AIModelOption(val id: String, val displayName: String, val created: Long = 0)
data class AIModelCatalog(val models: List<AIModelOption>, val source: String, val refreshedAt: Long, val truncated: Boolean)
data class AIModelCatalogConfiguration(val provider: DirectAIProvider, val endpoint: String, val key: String) {
    fun request(cursor: String? = null): Pair<String, Map<String, String>> {
        val uri = runCatching { URI(endpoint.trim()) }.getOrNull()
        if (uri == null || uri.scheme != "https" || uri.host.isNullOrEmpty() || uri.userInfo != null ||
            uri.rawQuery != null || uri.rawFragment != null || key.isBlank()) throw AIModelCatalogException.configuration()
        var path = uri.path.trimEnd('/')
        if (provider != DirectAIProvider.GEMINI) {
            val suffix = listOf("/chat/completions", "/responses", "/messages").firstOrNull { path.endsWith(it) }
                ?: throw AIModelCatalogException.configuration()
            path = path.removeSuffix(suffix)
        }
        val query = when (provider) {
            DirectAIProvider.ANTHROPIC -> "limit=1000" + (cursor?.let { "&after_id=" + URLEncoder.encode(it, "UTF-8") } ?: "")
            DirectAIProvider.GEMINI -> "pageSize=1000" + (cursor?.let { "&pageToken=" + URLEncoder.encode(it, "UTF-8") } ?: "")
            else -> null
        }
        val base = URI("https", null, uri.host, uri.port, "$path/models", null, null).toASCIIString()
        val headers = when (provider) {
            DirectAIProvider.ANTHROPIC -> mapOf("x-api-key" to key.trim(), "anthropic-version" to "2023-06-01")
            DirectAIProvider.GEMINI -> mapOf("x-goog-api-key" to key.trim())
            else -> mapOf("Authorization" to "Bearer ${key.trim()}")
        }
        return (base + (query?.let { "?$it" } ?: "")) to headers
    }
}
class AIModelCatalogException(message: String) : Exception(message) {
    companion object {
        fun configuration() = AIModelCatalogException("请先填写 API Key 和有效的 HTTPS 服务地址。")
        fun invalid() = AIModelCatalogException("模型列表格式无法识别；可手动填写模型 ID。")
    }
}
fun interface AIModelCatalogTransport {
    suspend fun get(url: String, headers: Map<String, String>): TranslationHttpResponse
}
class UrlConnectionAIModelCatalogTransport : AIModelCatalogTransport {
    override suspend fun get(url: String, headers: Map<String, String>): TranslationHttpResponse = withContext(Dispatchers.IO) {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            headers.forEach(connection::setRequestProperty)
            val status = connection.responseCode
            if (status !in 200..299) return@withContext TranslationHttpResponse(status, "")
            val output = java.io.ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > 2_097_152) throw AIModelCatalogException.invalid()
                    output.write(buffer, 0, count)
                }
            }
            TranslationHttpResponse(status, output.toString("UTF-8"))
        } finally { connection.disconnect() }
    }
}
fun interface AIModelCatalogService {
    suspend fun fetch(configuration: AIModelCatalogConfiguration): AIModelCatalog
}
class RemoteAIModelCatalogService(private val transport: AIModelCatalogTransport = UrlConnectionAIModelCatalogTransport()) : AIModelCatalogService {
    // Parsing and sorting a large provider catalog must not occupy the Compose UI thread.
    override suspend fun fetch(configuration: AIModelCatalogConfiguration): AIModelCatalog = withContext(Dispatchers.Default) {
        val options = linkedMapOf<String, AIModelOption>()
        val seen = mutableSetOf<String>()
        var cursor: String? = null
        var truncated = false
        val source = URI(configuration.request().first).host
        for (page in 0 until 10) {
            currentCoroutineContext().ensureActive()
            val (url, headers) = configuration.request(cursor)
            val response = transport.get(url, headers)
            currentCoroutineContext().ensureActive()
            if (response.statusCode == 401 || response.statusCode == 403)
                throw AIModelCatalogException("模型列表验证失败，请检查此服务商的 API Key 和权限。")
            if (response.statusCode !in 200..299)
                throw AIModelCatalogException("无法获取模型列表；可稍后刷新或手动填写模型 ID。")
            val parsed = parse(response.body, configuration.provider)
            parsed.first.forEach {
                if (it.id !in options) {
                    if (options.size < 5000) options[it.id] = it else truncated = true
                }
            }
            cursor = parsed.second
            if (cursor == null) break
            if (!seen.add(cursor)) throw AIModelCatalogException.invalid()
            if (page == 9 || options.size >= 5000) { truncated = true; break }
        }
        AIModelCatalog(options.values.sortedWith(compareByDescending<AIModelOption> { it.created }.thenBy { it.id }),
            source, System.currentTimeMillis(), truncated)
    }
    companion object {
        internal fun parse(body: String, provider: DirectAIProvider): Pair<List<AIModelOption>, String?> {
            if (body.toByteArray(Charsets.UTF_8).size > 2_097_152) throw AIModelCatalogException.invalid()
            val json = runCatching { JSONObject(body) }.getOrElse { throw AIModelCatalogException.invalid() }
            val gemini = provider == DirectAIProvider.GEMINI
            val rows = json.optJSONArray(if (gemini) "models" else "data") ?: throw AIModelCatalogException.invalid()
            val options = (0 until rows.length()).mapNotNull { index ->
                val row = rows.optJSONObject(index) ?: throw AIModelCatalogException.invalid()
                if (gemini) {
                    val methods = row.optJSONArray("supportedGenerationMethods")
                    if (methods == null || (0 until methods.length()).none { methods.optString(it) == "generateContent" }) return@mapNotNull null
                }
                val id = (row.opt(if (gemini) "name" else "id") as? String)?.let {
                    if (gemini) it.removePrefix("models/") else it
                } ?: return@mapNotNull null
                if (id.isEmpty() || id.length > 256 || id.any { it.isWhitespace() }) return@mapNotNull null
                val name = (row.opt(if (gemini) "displayName" else "display_name") as? String)?.takeIf { it.isNotEmpty() }?.take(256) ?: id
                val created = (row.opt("created") as? Number)?.toLong()
                    ?: runCatching { Instant.parse(row.optString("created_at")).epochSecond }.getOrDefault(0)
                AIModelOption(id, name, created)
            }
            var cursor = if (gemini) json.optString("nextPageToken").takeIf { it.isNotEmpty() } else null
            if (provider == DirectAIProvider.ANTHROPIC && json.optBoolean("has_more")) {
                cursor = (json.opt("last_id") as? String)?.takeIf { it.isNotEmpty() } ?: throw AIModelCatalogException.invalid()
            }
            return options to cursor
        }
    }
}
class MockAIModelCatalogService : AIModelCatalogService {
    override suspend fun fetch(configuration: AIModelCatalogConfiguration) = AIModelCatalog(
        listOf(AIModelOption("mock-text-latest", "示例文本模型")), "离线示例", System.currentTimeMillis(), false)
}
