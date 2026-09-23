package com.androidharness.app.forge

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class ForgePluginManifest(
    val schemaVersion: Int = 1,
    val id: String,
    val name: String,
    val version: String = "1.0.0",
    val description: String = "",
    val enabled: Boolean = true,
    val permissions: List<String> = listOf("network"),
    val auth: ForgeAuthSpec? = null,
    val tools: List<ForgeToolSpec> = emptyList(),
    val screens: List<ForgeScreenSpec> = emptyList(),
)

@Serializable
data class ForgeAuthSpec(
    /** none | bearer | api_key */
    val type: String = "none",
    /** Secret slot stored in Android Keystore encrypted preferences. */
    val secret: String = "token",
    val header: String = "Authorization",
    val prefix: String = "Bearer ",
)

@Serializable
data class ForgeParameterSpec(
    val type: String = "string",
    val description: String = "",
    val required: Boolean = false,
)

@Serializable
data class ForgeToolSpec(
    val id: String,
    val description: String,
    val method: String = "GET",
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val parameters: Map<String, ForgeParameterSpec> = emptyMap(),
)

@Serializable
data class ForgeScreenSpec(
    val id: String,
    val title: String,
    /** Declarative UI tree rendered by ForgeScreen. */
    val root: JsonObject,
)
