package com.paulbaker.localize.config

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.project.Project
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * File-based config persistence.
 * Saves to {projectDir}/.idea/localize-plugin.json — always reliable, human-readable.
 */
class ConfigPersistence(project: Project) {

    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val configPath: Path = Paths.get(project.basePath ?: ".")
        .resolve(".idea/localize-plugin.json")

    // ── Cached object — read once, write on save ──────────────────────────────

    private val data: JsonObject by lazy {
        if (configPath.exists()) {
            runCatching { JsonParser.parseString(configPath.readText()).asJsonObject }
                .getOrElse { JsonObject() }
        } else {
            JsonObject()
        }
    }

    // ── String properties ─────────────────────────────────────────────────────

    var csvAndroidOnly: String
        get() = data.getString(KEY_CSV_ANDROID)
        set(v) { data.addProperty(KEY_CSV_ANDROID, v); flush() }

    var csvOverlap: String
        get() = data.getString(KEY_CSV_OVERLAP)
        set(v) { data.addProperty(KEY_CSV_OVERLAP, v); flush() }

    var csvArrays: String
        get() = data.getString(KEY_CSV_ARRAYS)
        set(v) { data.addProperty(KEY_CSV_ARRAYS, v); flush() }

    // ── List properties ───────────────────────────────────────────────────────

    var checkedLanguages: List<String>
        get() = data.getStringList(KEY_LANGUAGES)
        set(v) { data.putStringList(KEY_LANGUAGES, v); flush() }

    var checkedXmlFiles: List<String>
        get() = data.getStringList(KEY_XML_FILES)
        set(v) { data.putStringList(KEY_XML_FILES, v); flush() }

    var checkedAssets: List<String>
        get() = data.getStringList(KEY_ASSETS)
        set(v) { data.putStringList(KEY_ASSETS, v); flush() }

    // ── Map<assetName, List<field>> ───────────────────────────────────────────

    var assetFields: Map<String, List<String>>
        get() {
            val obj = data.getAsJsonObject(KEY_ASSET_FIELDS) ?: return emptyMap()
            return obj.keySet().associateWith { key ->
                obj.getAsJsonArray(key)?.map { it.asString } ?: emptyList()
            }
        }
        set(v) {
            val obj = JsonObject()
            v.forEach { (name, fields) ->
                val arr = com.google.gson.JsonArray()
                fields.forEach { arr.add(it) }
                obj.add(name, arr)
            }
            data.add(KEY_ASSET_FIELDS, obj)
            flush()
        }

    // ── Batch save — write all fields at once ─────────────────────────────────

    fun saveAll(
        androidOnly: String,
        overlap: String,
        arrays: String,
        languages: List<String>,
        xmlFiles: List<String>,
        assets: List<String>,
        fields: Map<String, List<String>>
    ) {
        data.addProperty(KEY_CSV_ANDROID, androidOnly)
        data.addProperty(KEY_CSV_OVERLAP, overlap)
        data.addProperty(KEY_CSV_ARRAYS, arrays)
        data.putStringList(KEY_LANGUAGES, languages)
        data.putStringList(KEY_XML_FILES, xmlFiles)
        data.putStringList(KEY_ASSETS, assets)
        val fieldsObj = JsonObject()
        fields.forEach { (name, list) ->
            val arr = com.google.gson.JsonArray()
            list.forEach { arr.add(it) }
            fieldsObj.add(name, arr)
        }
        data.add(KEY_ASSET_FIELDS, fieldsObj)
        flush()
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun flush() {
        runCatching {
            configPath.parent.toFile().mkdirs()
            configPath.writeText(gson.toJson(data), Charsets.UTF_8)
        }
    }

    private fun JsonObject.getString(key: String): String =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString ?: ""

    private fun JsonObject.getStringList(key: String): List<String> =
        getAsJsonArray(key)?.map { it.asString } ?: emptyList()

    private fun JsonObject.putStringList(key: String, list: List<String>) {
        val arr = com.google.gson.JsonArray()
        list.forEach { arr.add(it) }
        add(key, arr)
    }

    // ── Generate mode ─────────────────────────────────────────────────────────

    var generateMode: GenerateMode
        get() = when (data.getString(KEY_GENERATE_MODE)) {
            "FULL_REPLACE" -> GenerateMode.FULL_REPLACE
            else           -> GenerateMode.MERGE   // default: safe merge
        }
        set(v) { data.addProperty(KEY_GENERATE_MODE, v.name); flush() }

    // ── Directory overrides ───────────────────────────────────────────────────

    /** Custom values/ directory. Empty = use default (app/src/main/res/values). */
    var valuesDir: String
        get() = data.getString(KEY_VALUES_DIR)
        set(v) { data.addProperty(KEY_VALUES_DIR, v); flush() }

    /** Custom assets/ directory. Empty = use default (app/src/main/assets). */
    var assetsDir: String
        get() = data.getString(KEY_ASSETS_DIR)
        set(v) { data.addProperty(KEY_ASSETS_DIR, v); flush() }

    /** Custom report output directory. Empty = use project root. */
    var reportDir: String
        get() = data.getString(KEY_REPORT_DIR)
        set(v) { data.addProperty(KEY_REPORT_DIR, v); flush() }

    // ── CSV column mappings ────────────────────────────────────────────────────

    fun getCsvMapping(filePath: String): CsvMapping? {
        val mappings = data.getAsJsonObject(KEY_CSV_MAPPINGS) ?: return null
        val entry = mappings.getAsJsonObject(filePath) ?: return null
        return runCatching {
            val keyCol = entry.getString("keyColumn")
            val enCol  = entry.getString("englishColumn")
            val langArr = entry.getAsJsonObject("languageColumns")
            val langMap = langArr?.keySet()?.associateWith { langArr.getString(it) } ?: emptyMap()
            CsvMapping(
                keyColumn = keyCol,
                englishColumn = enCol,
                languageColumns = langMap,
                skipEmptyRows   = entry.get("skipEmptyRows")?.asBoolean ?: true,
                skipSectionRows = entry.get("skipSectionRows")?.asBoolean ?: true,
            )
        }.getOrNull()
    }

    fun saveCsvMapping(filePath: String, mapping: CsvMapping) {
        val mappings = data.getAsJsonObject(KEY_CSV_MAPPINGS) ?: JsonObject().also { data.add(KEY_CSV_MAPPINGS, it) }
        val entry = JsonObject().apply {
            addProperty("keyColumn", mapping.keyColumn)
            addProperty("englishColumn", mapping.englishColumn)
            val langObj = JsonObject()
            mapping.languageColumns.forEach { (col, locale) -> langObj.addProperty(col, locale) }
            add("languageColumns", langObj)
            addProperty("skipEmptyRows", mapping.skipEmptyRows)
            addProperty("skipSectionRows", mapping.skipSectionRows)
        }
        mappings.add(filePath, entry)
        flush()
    }

    fun removeCsvMapping(filePath: String) {
        data.getAsJsonObject(KEY_CSV_MAPPINGS)?.remove(filePath)
        flush()
    }

    // ── Custom locale mapping (user-defined for unrecognized headers) ────────

    /** col_name.lowercase() → android_locale. Persisted across sessions. */
    var customLocaleMap: Map<String, String>
        get() {
            val obj = data.getAsJsonObject(KEY_CUSTOM_LOCALES) ?: return emptyMap()
            return obj.keySet().associateWith { obj.get(it).asString }
        }
        set(v) {
            val obj = JsonObject()
            v.forEach { (col, locale) -> obj.addProperty(col, locale) }
            data.add(KEY_CUSTOM_LOCALES, obj)
            flush()
        }

    companion object {
        private const val KEY_CSV_ANDROID    = "csvAndroidOnly"
        private const val KEY_CSV_OVERLAP    = "csvOverlap"
        private const val KEY_CSV_ARRAYS     = "csvArrays"
        private const val KEY_LANGUAGES      = "checkedLanguages"
        private const val KEY_XML_FILES      = "checkedXmlFiles"
        private const val KEY_ASSETS         = "checkedAssets"
        private const val KEY_ASSET_FIELDS   = "assetFields"
        private const val KEY_CUSTOM_LOCALES  = "customLocaleMap"
        private const val KEY_GENERATE_MODE   = "generateMode"
        private const val KEY_VALUES_DIR      = "valuesDir"
        private const val KEY_ASSETS_DIR      = "assetsDir"
        private const val KEY_REPORT_DIR      = "reportDir"
        private const val KEY_CSV_MAPPINGS    = "csvMappings"
    }
}
