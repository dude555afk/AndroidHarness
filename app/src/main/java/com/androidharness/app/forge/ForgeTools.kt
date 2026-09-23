package com.androidharness.app.forge

import com.androidharness.app.tools.Schema
import com.androidharness.app.tools.Tool
import com.androidharness.app.tools.ToolContext
import com.androidharness.app.tools.ToolResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

private const val MANIFEST_GUIDE = """
Forge manifest JSON schema:
{
  "schemaVersion":1,
  "id":"lowercase-id",
  "name":"Display name",
  "version":"1.0.0",
  "description":"...",
  "permissions":["network","ui","secrets"],
  "auth":{"type":"bearer|api_key|none","secret":"token","header":"Authorization","prefix":"Bearer "},
  "tools":[{
    "id":"search",
    "description":"Search the service",
    "method":"GET|POST|PUT|PATCH|DELETE",
    "url":"https://api.example.com/search?q={{query}}",
    "headers":{"Content-Type":"application/json"},
    "body":"{\"q\":\"{{query}}\"}",
    "parameters":{"query":{"type":"string","description":"Search text","required":true}}
  }],
  "screens":[{
    "id":"home",
    "title":"Service",
    "root":{"type":"column","children":[
      {"type":"text","text":"Service","style":"title"},
      {"type":"input","key":"query","label":"Search"},
      {"type":"button","label":"Search","tool":"search","args":{"query":"{{state.query}}"}}
    ]}
  }]
}
UI node types: column, row, card, text, input, switch, button, divider, spacer.
Buttons invoke a plugin tool by id. Never put secrets directly in a manifest; authenticated
plugins declare auth and the user stores the credential from the Forge screen.
"""

internal class ForgeListTool(private val forge: ForgeManager) : Tool {
    override val name = "forge_list"
    override val description = "List runtime Forge plugins and their tools/screens."
    override val isReadOnly = true
    override val parametersSchema = Schema.obj(emptyMap())
    override suspend fun execute(args: JsonObject, ctx: ToolContext) =
        ToolResult(true, forge.listSummary())
}

internal class ForgeInstallTool(private val forge: ForgeManager) : Tool {
    override val name = "forge_install"
    override val description =
        "Install or update a runtime Forge plugin without rebuilding the APK. " +
        "Use this when a requested integration is missing. The user must approve installation.\n" + MANIFEST_GUIDE
    override val isReadOnly = false
    override val parametersSchema = Schema.obj(
        mapOf("manifest_json" to Schema.string("Complete Forge plugin manifest JSON.")),
        required = listOf("manifest_json"),
    )
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val raw = args["manifest_json"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult(false, "manifest_json is required.")
        val plugin = forge.install(raw)
        return ToolResult(
            true,
            "Installed Forge plugin '${plugin.name}' (${plugin.id}) with ${plugin.tools.size} tools and ${plugin.screens.size} screens. " +
                "You can use it immediately through forge_invoke; its named tools appear automatically on the next agent run.",
        )
    }
}

internal class ForgeRemoveTool(private val forge: ForgeManager) : Tool {
    override val name = "forge_remove"
    override val description = "Remove an installed Forge plugin and its encrypted credentials."
    override val isReadOnly = false
    override val parametersSchema = Schema.obj(
        mapOf("plugin_id" to Schema.string("Forge plugin id.")),
        required = listOf("plugin_id"),
    )
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val id = args["plugin_id"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult(false, "plugin_id is required.")
        return if (forge.remove(id)) ToolResult(true, "Removed Forge plugin '$id'.")
        else ToolResult(false, "Forge plugin '$id' was not installed.")
    }
}

internal class ForgeInvokeTool(private val forge: ForgeManager) : Tool {
    override val name = "forge_invoke"
    override val description =
        "Invoke a tool from an installed Forge plugin immediately, including a plugin created earlier in this same agent run."
    override val isReadOnly = false
    override val parametersSchema = Schema.obj(
        mapOf(
            "plugin_id" to Schema.string("Forge plugin id."),
            "tool_id" to Schema.string("Tool id inside that plugin."),
            "arguments_json" to Schema.string("JSON object containing arguments for the plugin tool. Use {} when none are needed."),
        ),
        required = listOf("plugin_id", "tool_id", "arguments_json"),
    )

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val plugin = args["plugin_id"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult(false, "plugin_id is required.")
        val tool = args["tool_id"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult(false, "tool_id is required.")
        val raw = args["arguments_json"]?.jsonPrimitive?.contentOrNull ?: "{}"
        val parsed = runCatching { json.parseToJsonElement(raw) as JsonObject }
            .getOrElse { return ToolResult(false, "arguments_json must be a JSON object: ${it.message}") }
        return forge.invoke(plugin, tool, parsed)
    }
}

internal class ForgeOpenUiTool(private val forge: ForgeManager) : Tool {
    override val name = "forge_open_ui"
    override val description = "Open an installed Forge plugin's dynamic UI in AndroidHarness."
    override val isReadOnly = false
    override val parametersSchema = Schema.obj(
        mapOf("plugin_id" to Schema.string("Forge plugin id.")),
        required = listOf("plugin_id"),
    )
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val id = args["plugin_id"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult(false, "plugin_id is required.")
        return runCatching {
            forge.requestOpen(id)
            ToolResult(true, "Opened Forge UI for '$id'.")
        }.getOrElse { ToolResult(false, it.message ?: "Could not open Forge plugin.") }
    }
}
