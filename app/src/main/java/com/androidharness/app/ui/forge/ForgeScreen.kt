package com.androidharness.app.ui.forge

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.AppContainer
import com.androidharness.app.forge.ForgePluginManifest
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Forge is a runtime extension surface: manifests can add HTTP tools and this
 * renderer can add small native-feeling screens without compiling new Compose.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForgeScreen(
    container: AppContainer,
    pluginId: String?,
    onBack: () -> Unit,
    onOpenPlugin: (String) -> Unit,
) {
    val plugins by container.forge.plugins.collectAsStateWithLifecycle()
    val plugin = pluginId?.let { id -> plugins.firstOrNull { it.id == id } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        plugin?.name ?: "Harness Forge",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (pluginId == null) {
            ForgeHub(
                plugins = plugins,
                container = container,
                onOpenPlugin = onOpenPlugin,
                modifier = Modifier.padding(padding),
            )
        } else if (plugin == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "That Forge plugin is no longer installed.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            ForgePluginPanel(
                plugin = plugin,
                container = container,
                modifier = Modifier.padding(padding),
            )
        }
    }
}

@Composable
private fun ForgeHub(
    plugins: List<ForgePluginManifest>,
    container: AppContainer,
    onOpenPlugin: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (plugins.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(28.dp),
            ) {
                Text("No runtime plugins yet", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Ask the agent to add an integration. Forge can install HTTP tools and dynamic UI without rebuilding the APK.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text(
                "Runtime extensions",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        items(plugins, key = { it.id }) { plugin ->
            Surface(
                onClick = { onOpenPlugin(plugin.id) },
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                    modifier = Modifier.padding(16.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(plugin.name, style = MaterialTheme.typography.titleMedium)
                            if (plugin.description.isNotBlank()) {
                                Text(
                                    plugin.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Switch(
                            checked = plugin.enabled,
                            onCheckedChange = { enabled ->
                                runCatching { container.forge.setEnabled(plugin.id, enabled) }
                            },
                        )
                    }
                    Text(
                        "${plugin.tools.size} tools · ${plugin.screens.size} screens · ${plugin.permissions.joinToString()}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ForgePluginPanel(
    plugin: ForgePluginManifest,
    container: AppContainer,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val state = remember(plugin.id) { mutableStateMapOf<String, String>() }
    var selectedScreenId by remember(plugin.id) {
        mutableStateOf(plugin.screens.firstOrNull()?.id)
    }
    var lastResult by remember(plugin.id) { mutableStateOf<String?>(null) }
    val screen = plugin.screens.firstOrNull { it.id == selectedScreenId }
        ?: plugin.screens.firstOrNull()

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(16.dp),
                ) {
                    Text(plugin.name, style = MaterialTheme.typography.titleLarge)
                    if (plugin.description.isNotBlank()) {
                        Text(
                            plugin.description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        "v${plugin.version} · ${plugin.tools.size} tools · ${plugin.permissions.joinToString()}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        plugin.auth?.takeIf { it.type.lowercase() != "none" }?.let { auth ->
            item {
                CredentialCard(
                    plugin = plugin,
                    secretName = auth.secret,
                    hasSecret = container.forge.hasSecret(plugin.id, auth.secret),
                    onSave = { value ->
                        container.forge.putSecret(plugin.id, auth.secret, value)
                    },
                )
            }
        }

        if (plugin.screens.size > 1) {
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                ) {
                    plugin.screens.forEach { candidate ->
                        TextButton(onClick = { selectedScreenId = candidate.id }) {
                            Text(candidate.title)
                        }
                    }
                }
            }
        }

        if (screen != null) {
            item {
                ForgeNodeRenderer(
                    node = screen.root,
                    state = state,
                    onInvoke = { toolId, args ->
                        scope.launch {
                            lastResult = "Running…"
                            val result = container.forge.invoke(plugin.id, toolId, args)
                            lastResult = result.output.ifBlank {
                                if (result.ok) "Done." else "Forge tool failed."
                            }
                        }
                    },
                )
            }
        } else {
            item {
                Text(
                    "This plugin exposes tools only. The agent can use them even without a dynamic screen.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        lastResult?.let { result ->
            item {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        result,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(14.dp),
                    )
                }
            }
        }

        item {
            HorizontalDivider()
            TextButton(onClick = { container.forge.remove(plugin.id) }) {
                Text("Remove plugin", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun CredentialCard(
    plugin: ForgePluginManifest,
    secretName: String,
    hasSecret: Boolean,
    onSave: (String) -> Unit,
) {
    var value by remember(plugin.id, secretName) { mutableStateOf("") }
    var saved by remember(plugin.id, secretName, hasSecret) { mutableStateOf(hasSecret) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            verticalArrangement = Arrangement.spacedBy(9.dp),
            modifier = Modifier.padding(16.dp),
        ) {
            Text("Credential", style = MaterialTheme.typography.titleSmall)
            Text(
                if (saved) "Encrypted credential is saved. Enter a new value only to replace it."
                else "This plugin needs '$secretName'. It is stored with Android Keystore encryption.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                label = { Text(secretName) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                enabled = value.isNotBlank(),
                onClick = {
                    onSave(value)
                    value = ""
                    saved = true
                },
            ) {
                Text(if (saved) "Replace credential" else "Save credential")
            }
        }
    }
}

@Composable
private fun ForgeNodeRenderer(
    node: JsonObject,
    state: SnapshotStateMap<String, String>,
    onInvoke: (String, JsonObject) -> Unit,
) {
    when (node.string("type")?.lowercase()) {
        "column" -> {
            Column(
                verticalArrangement = Arrangement.spacedBy(node.int("spacing")?.dp ?: 10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                node.children().forEach { child -> ForgeNodeRenderer(child, state, onInvoke) }
            }
        }

        "row" -> {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(node.int("spacing")?.dp ?: 8.dp),
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            ) {
                node.children().forEach { child -> ForgeNodeRenderer(child, state, onInvoke) }
            }
        }

        "card" -> {
            Surface(
                shape = RoundedCornerShape(node.int("radius")?.dp ?: 16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                    modifier = Modifier.padding(node.int("padding")?.dp ?: 14.dp),
                ) {
                    node.children().forEach { child -> ForgeNodeRenderer(child, state, onInvoke) }
                }
            }
        }

        "text" -> {
            val style = when (node.string("style")) {
                "title" -> MaterialTheme.typography.titleLarge
                "subtitle" -> MaterialTheme.typography.titleMedium
                "label" -> MaterialTheme.typography.labelMedium
                else -> MaterialTheme.typography.bodyMedium
            }
            Text(node.string("text").orEmpty(), style = style)
        }

        "input" -> {
            val key = node.string("key") ?: return
            if (key !in state) state[key] = node.string("value").orEmpty()
            val hint = node.string("placeholder")
            OutlinedTextField(
                value = state[key].orEmpty(),
                onValueChange = { state[key] = it },
                label = { Text(node.string("label") ?: key) },
                placeholder = if (hint != null) ({ Text(hint) }) else null,
                singleLine = node.bool("multiline") != true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        "switch" -> {
            val key = node.string("key") ?: return
            if (key !in state) state[key] = (node.bool("value") ?: false).toString()
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(node.string("label") ?: key, modifier = Modifier.weight(1f))
                Switch(
                    checked = state[key].toBoolean(),
                    onCheckedChange = { state[key] = it.toString() },
                )
            }
        }

        "button" -> {
            val tool = node.string("tool")
            Button(
                enabled = !tool.isNullOrBlank(),
                onClick = {
                    if (tool != null) {
                        val rawArgs = node["args"] as? JsonObject ?: JsonObject(emptyMap())
                        onInvoke(tool, resolveState(rawArgs, state) as JsonObject)
                    }
                },
            ) {
                Text(node.string("label") ?: tool ?: "Run")
            }
        }

        "divider" -> HorizontalDivider()
        "spacer" -> Spacer(Modifier.height(node.int("height")?.dp ?: 12.dp))

        else -> Text(
            "Unsupported Forge UI node: ${node.string("type") ?: "missing type"}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.int(key: String): Int? =
    (this[key] as? JsonPrimitive)?.intOrNull

private fun JsonObject.bool(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.booleanOrNull

private fun JsonObject.children(): List<JsonObject> =
    (this["children"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

private val STATE_TEMPLATE = Regex("""\\{\\{state\\.([A-Za-z0-9_]+)}}""")

private fun resolveState(
    element: JsonElement,
    state: Map<String, String>,
): JsonElement = when (element) {
    is JsonObject -> JsonObject(element.mapValues { (_, value) -> resolveState(value, state) })
    is JsonArray -> JsonArray(element.map { resolveState(it, state) })
    is JsonPrimitive -> {
        if (!element.isString) element
        else JsonPrimitive(
            STATE_TEMPLATE.replace(element.content) { match ->
                state[match.groupValues[1]].orEmpty()
            },
        )
    }
    JsonNull -> JsonNull
}
