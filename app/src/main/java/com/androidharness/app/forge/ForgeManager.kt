package com.androidharness.app.forge

import android.content.Context
import com.androidharness.app.data.KeyStoreManager
import com.androidharness.app.tools.Tool
import com.androidharness.app.tools.ToolContext
import com.androidharness.app.tools.ToolFailure
import com.androidharness.app.tools.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Runtime extension host. Forge plugins are declarative JSON manifests, so new
 * HTTP tools and Compose-rendered screens can be installed without rebuilding
 * the APK. The host deliberately exposes a small capability surface.
 */
class ForgeManager(
    context: Context,
    private val keys: KeyStoreManager,
    private val http: OkHttpClient,
) {
    private val root = File(context.filesDir, "forge").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private val _plugins = MutableStateFlow(loadPlugins())
    val plugins: StateFlow<List<ForgePluginManifest>> = _plugins

    /** Agent/UI bridge: forge_open_ui asks AppNav to show this plugin. */
    val openRequests = MutableSharedFlow<String>(extraBufferCapacity = 4)

    fun managementTools(): List<Tool> = listOf(
        ForgeListTool(this),
        ForgeInstallTool(this),
        ForgeRemoveTool(this),
        ForgeInvokeTool(this),
        ForgeOpenUiTool(this),
    )

    /** Individual plugin tools become first-class model tools on subsequent runs. */
    fun activeTools(): List<Tool> = plugins.value
        .filter { it.enabled }
        .flatMap { plugin -> plugin.tools.map { ForgeHttpTool(this, plugin.id, it) } }

    fun find(id: String): ForgePluginManifest? = plugins.value.firstOrNull { it.id == id }

    fun listSummary(): String = plugins.value.joinToString("\n") { p ->
        val state = if (p.enabled) "enabled" else "disabled"
        "- ${p.id}: ${p.name} ${p.version} ($state), ${p.tools.size} tools, ${p.screens.size} screens"
    }.ifBlank { "No Forge plugins installed." }

    fun install(rawManifest: String): ForgePluginManifest {
        if (rawManifest.toByteArray().size > MAX_MANIFEST_BYTES) {
            throw ToolFailure("Forge manifest is too large (max ${MAX_MANIFEST_BYTES / 1024} KB).")
        }
        val parsed = runCatching { json.decodeFromString<ForgePluginManifest>(rawManifest) }
            .getOrElse { throw ToolFailure("Invalid Forge manifest: ${it.message}") }
        validate(parsed)
        persist(parsed)
        refresh()
        return parsed
    }

    fun remove(id: String): Boolean {
        validateId(id, "plugin")
        val removed = fileFor(id).delete()
        if (removed) {
            keys.removeForgePluginSecrets(id)
            refresh()
        }
        return removed
    }

    fun setEnabled(id: String, enabled: Boolean) {
        val plugin = find(id) ?: throw ToolFailure("Forge plugin '$id' is not installed.")
        persist(plugin.copy(enabled = enabled))
        refresh()
    }

    fun putSecret(pluginId: String, name: String, value: String) {
        if (find(pluginId) == null) throw ToolFailure("Forge plugin '$pluginId' is not installed.")
        validateId(name, "secret")
        keys.putForgeSecret(pluginId, name, value)
    }

    fun hasSecret(pluginId: String, name: String): Boolean =
        !keys.getForgeSecret(pluginId, name).isNullOrBlank()

    fun requestOpen(pluginId: String) {
        if (find(pluginId) == null) throw ToolFailure("Forge plugin '$pluginId' is not installed.")
        openRequests.tryEmit(pluginId)
    }

    suspend fun invoke(pluginId: String, toolId: String, args: JsonObject): ToolResult {
        val plugin = find(pluginId) ?: return ToolResult(false, "Forge plugin '$pluginId' is not installed.")
        if (!plugin.enabled) return ToolResult(false, "Forge plugin '$pluginId' is disabled.")
        val spec = plugin.tools.firstOrNull { it.id == toolId }
            ?: return ToolResult(false, "Tool '$toolId' does not exist in Forge plugin '$pluginId'.")
        return executeHttp(plugin, spec, args)
    }

    internal suspend fun executeHttp(
        plugin: ForgePluginManifest,
        spec: ForgeToolSpec,
        args: JsonObject,
    ): ToolResult = withContext(Dispatchers.IO) {
        if ("network" !in plugin.permissions) {
            return@withContext ToolResult(false, "Plugin '${plugin.id}' was not granted network capability.")
        }

        val url = expand(spec.url, args, urlEncode = true)
        if (!isAllowedUrl(url)) {
            return@withContext ToolResult(false, "Forge blocks non-HTTPS network targets (except localhost): $url")
        }

        val builder = Request.Builder().url(url)
        spec.headers.forEach { (name, value) ->
            builder.header(name, expand(value, args, urlEncode = false))
        }

        val auth = plugin.auth
        if (auth != null && auth.type.lowercase() != "none") {
            if ("secrets" !in plugin.permissions) {
                return@withContext ToolResult(false, "Plugin '${plugin.id}' auth requires the secrets capability.")
            }
            val secret = keys.getForgeSecret(plugin.id, auth.secret)
                ?: return@withContext ToolResult(
                    false,
                    "Plugin '${plugin.id}' needs credential '${auth.secret}'. Open Forge and save it first.",
                )
            builder.header(auth.header, auth.prefix + secret)
        }

        val method = spec.method.uppercase()
        val contentType = spec.headers.entries
            .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
            ?.value
            ?.let { expand(it, args, false) }
            ?: "application/json"
        val bodyText = spec.body?.let { expand(it, args, urlEncode = false) }
        val body = (bodyText ?: "").toRequestBody(contentType.toMediaTypeOrNull())

        when (method) {
            "GET" -> builder.get()
            "HEAD" -> builder.head()
            "DELETE" -> if (bodyText == null) builder.delete() else builder.delete(body)
            "POST" -> builder.post(body)
            "PUT" -> builder.put(body)
            "PATCH" -> builder.patch(body)
            else -> builder.method(method, if (method in BODYLESS_METHODS) null else body)
        }

        try {
            http.newCall(builder.build()).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                val clipped = if (responseBody.length > MAX_RESPONSE_CHARS) {
                    responseBody.take(MAX_RESPONSE_CHARS) + "\n[truncated by Forge]"
                } else responseBody
                ToolResult(
                    ok = response.isSuccessful,
                    output = buildString {
                        append("HTTP ").append(response.code)
                        response.message.takeIf { it.isNotBlank() }?.let { append(' ').append(it) }
                        if (clipped.isNotBlank()) append("\n").append(clipped)
                    },
                )
            }
        } catch (e: Exception) {
            ToolResult(false, "Forge request failed: ${e.message}")
        }
    }

    private fun refresh() {
        _plugins.value = loadPlugins()
    }

    private fun loadPlugins(): List<ForgePluginManifest> = root.listFiles().orEmpty()
        .filter { it.isFile && it.extension == "json" }
        .mapNotNull { file ->
            runCatching { json.decodeFromString<ForgePluginManifest>(file.readText()) }.getOrNull()
        }
        .sortedBy { it.name.lowercase() }

    private fun persist(plugin: ForgePluginManifest) {
        val target = fileFor(plugin.id)
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(json.encodeToString(plugin))
        if (!tmp.renameTo(target)) {
            target.writeText(tmp.readText())
            tmp.delete()
        }
    }

    private fun fileFor(id: String) = File(root, "$id.json")

    private fun validate(plugin: ForgePluginManifest) {
        if (plugin.schemaVersion != 1) throw ToolFailure("Unsupported Forge schemaVersion ${plugin.schemaVersion}.")
        validateId(plugin.id, "plugin")
        if (plugin.name.isBlank() || plugin.name.length > 80) throw ToolFailure("Plugin name must be 1-80 characters.")
        if (plugin.tools.size > 32) throw ToolFailure("Forge plugins may expose at most 32 tools.")
        if (plugin.screens.size > 8) throw ToolFailure("Forge plugins may expose at most 8 screens.")

        val unknownPermissions = plugin.permissions.toSet() - ALLOWED_PERMISSIONS
        if (unknownPermissions.isNotEmpty()) {
            throw ToolFailure("Unsupported Forge permissions: ${unknownPermissions.joinToString()}.")
        }
        if (plugin.tools.isNotEmpty() && "network" !in plugin.permissions) {
            throw ToolFailure("Plugins with HTTP tools must request the 'network' permission.")
        }
        if (plugin.screens.isNotEmpty() && "ui" !in plugin.permissions) {
            throw ToolFailure("Plugins with dynamic screens must request the 'ui' permission.")
        }
        if (plugin.auth != null && plugin.auth.type.lowercase() != "none" && "secrets" !in plugin.permissions) {
            throw ToolFailure("Authenticated plugins must request the 'secrets' permission.")
        }

        val toolIds = HashSet<String>()
        plugin.tools.forEach { tool ->
            validateId(tool.id, "tool")
            if (!toolIds.add(tool.id)) throw ToolFailure("Duplicate Forge tool id '${tool.id}'.")
            val expandedProbe = tool.url.replace(TEMPLATE_REGEX, "x")
            if (!isAllowedUrl(expandedProbe)) throw ToolFailure("Forge tool '${tool.id}' must use HTTPS (localhost HTTP is allowed).")
            if (tool.description.isBlank()) throw ToolFailure("Forge tool '${tool.id}' needs a description.")
            tool.parameters.keys.forEach { validateId(it, "parameter") }
        }

        val screenIds = HashSet<String>()
        plugin.screens.forEach { screen ->
            validateId(screen.id, "screen")
            if (!screenIds.add(screen.id)) throw ToolFailure("Duplicate Forge screen id '${screen.id}'.")
            if (screen.title.isBlank()) throw ToolFailure("Forge screen '${screen.id}' needs a title.")
        }
    }

    private fun validateId(value: String, label: String) {
        if (!ID_REGEX.matches(value)) {
            throw ToolFailure("Invalid $label id '$value'. Use lowercase letters, numbers, _ or -, starting with a letter.")
        }
    }

    private fun isAllowedUrl(url: String): Boolean = runCatching {
        val uri = URI(url)
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase()
        scheme == "https" || (scheme == "http" && host in LOCAL_HOSTS)
    }.getOrDefault(false)

    private fun expand(template: String, args: JsonObject, urlEncode: Boolean): String =
        TEMPLATE_REGEX.replace(template) { match ->
            val key = match.groupValues[1]
            val element = args[key] ?: throw ToolFailure("Missing Forge argument '$key'.")
            val raw = if (element is JsonPrimitive) element.contentOrNull ?: element.toString() else element.toString()
            if (urlEncode) URLEncoder.encode(raw, StandardCharsets.UTF_8.toString()) else raw
        }

    companion object {
        private val ID_REGEX = Regex("[a-z][a-z0-9_-]{0,48}")
        private val TEMPLATE_REGEX = Regex("""\\{\\{([A-Za-z0-9_]+)}}""")
        private val ALLOWED_PERMISSIONS = setOf("network", "secrets", "ui")
        private val LOCAL_HOSTS = setOf("localhost", "127.0.0.1", "::1")
        private val BODYLESS_METHODS = setOf("GET", "HEAD")
        private const val MAX_MANIFEST_BYTES = 256 * 1024
        private const val MAX_RESPONSE_CHARS = 200_000
    }
}

internal class ForgeHttpTool(
    private val manager: ForgeManager,
    private val pluginId: String,
    private val spec: ForgeToolSpec,
) : Tool {
    override val name: String = "forge_${pluginId}_${spec.id}"
    override val description: String = spec.description
    override val isReadOnly: Boolean = spec.method.uppercase() in setOf("GET", "HEAD")

    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            spec.parameters.forEach { (name, parameter) ->
                put(name, buildJsonObject {
                    put("type", parameter.type)
                    if (parameter.description.isNotBlank()) put("description", parameter.description)
                })
            }
        }
        val required = spec.parameters.filterValues { it.required }.keys
        if (required.isNotEmpty()) {
            putJsonArray("required") { required.forEach { add(it) } }
        }
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
        manager.invoke(pluginId, spec.id, args)
}
