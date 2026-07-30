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

    /**
     * Shared per config file, NOT per instance.
     *
     * Every panel builds its own [ConfigPersistence]; with a per-instance snapshot each one
     * would `flush()` its own stale copy of the whole document, silently reverting whatever
     * another panel had saved in the meantime (e.g. changing Generate Mode in Localize, then
     * exporting, used to reset the mode back).
     */
    private val data: JsonObject get() = sharedData(configPath)

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
        val snapshot = data
        synchronized(snapshot) {
            runCatching {
                configPath.parent.toFile().mkdirs()
                configPath.writeText(gson.toJson(snapshot), Charsets.UTF_8)
            }
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

    /**
     * Keep every key at the position it already has in the `values-{locale}` XML files.
     * Default on: re-ordering a locale file to match the template turns a one-line
     * translation change into a delete-here / add-there diff that nobody can review.
     */
    var preserveKeyOrder: Boolean
        get() = data.get(KEY_PRESERVE_ORDER)?.takeIf { it.isJsonPrimitive }?.asBoolean ?: true
        set(v) { data.addProperty(KEY_PRESERVE_ORDER, v); flush() }

    /**
     * Off (default): `translatable="false"` is always honoured.
     * On: such a string is translated anyway when the source spreadsheet supplies a value.
     */
    var overrideNonTranslatable: Boolean
        get() = data.get(KEY_OVERRIDE_NON_TRANSLATABLE)?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
        set(v) { data.addProperty(KEY_OVERRIDE_NON_TRANSLATABLE, v); flush() }

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

    var exportLocales: List<String>
        get() = data.getStringList(KEY_EXPORT_LOCALES)
        set(v) { data.putStringList(KEY_EXPORT_LOCALES, v); flush() }

    /** Assets ticked in the Export tool — includes ones promoted out of the Ignored group. */
    var exportAssets: List<String>
        get() = data.getStringList(KEY_EXPORT_ASSETS)
        set(v) { data.putStringList(KEY_EXPORT_ASSETS, v); flush() }

    var exportXmlFiles: List<String>
        get() = data.getStringList(KEY_EXPORT_XML_FILES)
        set(v) { data.putStringList(KEY_EXPORT_XML_FILES, v); flush() }

    var exportOutputPath: String
        get() = data.getString(KEY_EXPORT_OUTPUT_PATH)
        set(v) { data.addProperty(KEY_EXPORT_OUTPUT_PATH, v); flush() }

    // ── Export tool: per-asset field config (separate from Localize tool) ────

    var exportAssetFields: Map<String, List<String>>
        get() {
            val obj = data.getAsJsonObject(KEY_EXPORT_ASSET_FIELDS) ?: return emptyMap()
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
            data.add(KEY_EXPORT_ASSET_FIELDS, obj)
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
        /** configPath → parsed document, shared by every instance pointing at the same file. */
        private val documents = java.util.concurrent.ConcurrentHashMap<String, JsonObject>()

        private fun sharedData(configPath: Path): JsonObject =
            documents.computeIfAbsent(configPath.toString()) {
                if (configPath.exists())
                    runCatching { JsonParser.parseString(configPath.readText()).asJsonObject }
                        .getOrElse { JsonObject() }
                else JsonObject()
            }

        private const val KEY_CSV_ANDROID    = "csvAndroidOnly"
        private const val KEY_CSV_OVERLAP    = "csvOverlap"
        private const val KEY_CSV_ARRAYS     = "csvArrays"
        private const val KEY_LANGUAGES      = "checkedLanguages"
        private const val KEY_XML_FILES      = "checkedXmlFiles"
        private const val KEY_ASSETS         = "checkedAssets"
        private const val KEY_ASSET_FIELDS   = "assetFields"
        private const val KEY_EXPORT_ASSET_FIELDS  = "exportAssetFields"
        private const val KEY_EXPORT_OUTPUT_PATH   = "exportOutputPath"
        private const val KEY_EXPORT_XML_FILES     = "exportXmlFiles"
        private const val KEY_EXPORT_LOCALES       = "exportLocales"
        private const val KEY_EXPORT_ASSETS        = "exportAssets"
        private const val KEY_CUSTOM_LOCALES  = "customLocaleMap"
        private const val KEY_GENERATE_MODE   = "generateMode"
        private const val KEY_PRESERVE_ORDER  = "preserveKeyOrder"
        private const val KEY_OVERRIDE_NON_TRANSLATABLE = "overrideNonTranslatable"
        private const val KEY_VALUES_DIR      = "valuesDir"
        private const val KEY_ASSETS_DIR      = "assetsDir"
        private const val KEY_REPORT_DIR      = "reportDir"
        private const val KEY_CSV_MAPPINGS    = "csvMappings"
    }
}
