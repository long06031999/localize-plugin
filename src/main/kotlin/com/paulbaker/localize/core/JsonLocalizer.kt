package com.paulbaker.localize.core

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.paulbaker.localize.config.AssetConfig
import com.paulbaker.localize.config.DEFAULT_TRANSLATE_FIELDS
import com.paulbaker.localize.config.GenerateMode
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class JsonLocalizer(private val db: TranslationDb) {

    data class JsonResult(val unmatched: List<UnmatchedField>)
    data class UnmatchedField(val context: String, val value: String)

    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    fun localize(asset: AssetConfig, locale: String, suffix: String, mode: GenerateMode = GenerateMode.MERGE): JsonResult {
        val basePath = asset.dir.resolve(asset.baseFile)
        if (!basePath.exists()) return JsonResult(emptyList())

        val root = JsonParser.parseString(basePath.readText(Charsets.UTF_8))
        val unmatched = mutableListOf<UnmatchedField>()
        val fieldsSet = asset.translateFields.ifEmpty { DEFAULT_TRANSLATE_FIELDS }.toSet()

        // Merge mode: load existing output as fallback for unmatched fields
        val outputPath = asset.dir.resolve("${basePath.toFile().nameWithoutExtension}_$suffix.json")
        val existingRoot = if (mode == GenerateMode.MERGE && outputPath.exists())
            runCatching { JsonParser.parseString(outputPath.readText(Charsets.UTF_8)) }.getOrNull()
        else null

        val result = translateElement(root, fieldsSet, locale, asset.name, unmatched, existingRoot)

        outputPath.writeText(gson.toJson(result), Charsets.UTF_8)
        return JsonResult(unmatched)
    }

    /**
     * Recursively walk the JSON tree and translate every field whose name is in [fields],
     * at any depth. Non-matching fields are preserved as-is.
     */
    private fun translateElement(
        el: JsonElement,
        fields: Set<String>,
        locale: String,
        ctx: String,
        unmatched: MutableList<UnmatchedField>,
        existing: JsonElement? = null   // Merge mode: existing output at same structural position
    ): JsonElement = when {
        el.isJsonObject -> {
            val obj = el.asJsonObject
            val existingObj = existing?.takeIf { it.isJsonObject }?.asJsonObject
            val out = JsonObject()
            obj.keySet().forEach { key ->
                val child = obj.get(key)
                if (key in fields) {
                    when {
                        child.isJsonPrimitive && child.asJsonPrimitive.isString -> {
                            val v = child.asString.takeIf { it.isNotEmpty() }
                            if (v != null) {
                                val tr = db.lookup("", v, locale)
                                    ?: existingObj?.get(key)?.takeIf {  // Merge: use existing if no CSV match
                                        it.isJsonPrimitive && it.asJsonPrimitive.isString && it.asString.isNotEmpty()
                                    }?.asString
                                if (tr != null) out.addProperty(key, tr)
                                else { unmatched += UnmatchedField("$ctx.$key", v); out.add(key, child) }
                            } else out.add(key, child)
                        }
                        child.isJsonArray -> {
                            // Translate each string element in a selected array field
                            val arr = child.asJsonArray
                            val translated = JsonArray()
                            arr.forEachIndexed { i, item ->
                                if (item.isJsonPrimitive && item.asJsonPrimitive.isString && item.asString.isNotEmpty()) {
                                    val tr = db.lookup("", item.asString, locale)
                                    if (tr != null) translated.add(tr)
                                    else { unmatched += UnmatchedField("$ctx.$key[$i]", item.asString); translated.add(item) }
                                } else translated.add(translateElement(item, fields, locale, "$ctx.$key[$i]", unmatched))
                            }
                            out.add(key, translated)
                        }
                        else -> out.add(key, translateElement(child, fields, locale, "$ctx.$key", unmatched, existingObj?.get(key)))
                    }
                } else {
                    out.add(key, translateElement(child, fields, locale, "$ctx.$key", unmatched, existingObj?.get(key)))
                }
            }
            out
        }
        el.isJsonArray -> {
            val existingArr = existing?.takeIf { it.isJsonArray }?.asJsonArray
            JsonArray().also { out ->
                el.asJsonArray.forEachIndexed { i, item ->
                    val existingItem = if (existingArr != null && i < existingArr.size()) existingArr[i] else null
                    out.add(translateElement(item, fields, locale, "$ctx[$i]", unmatched, existingItem))
                }
            }
        }
        else -> el
    }


    // Auto-detect translatable fields by comparing base JSON with reference _ko.json.
    // Matches items by a common key (id, name) to avoid false results when order differs.
    fun detectTranslateFields(asset: AssetConfig): List<String> {
        val basePath = asset.dir.resolve(asset.baseFile)
        val koPath   = asset.dir.resolve("${basePath.toFile().nameWithoutExtension}_ko.json")
        if (!basePath.exists() || !koPath.exists()) return DEFAULT_TRANSLATE_FIELDS

        return runCatching {
            val baseItems = allItems(basePath, asset.dataKey)
            val koItems   = allItems(koPath,   asset.dataKey)
            if (baseItems.isEmpty() || koItems.isEmpty()) return DEFAULT_TRANSLATE_FIELDS

            // Build a lookup of ko items by their "id" or first string key value
            val koById = koItems.associateBy { item ->
                ID_KEYS.firstNotNullOfOrNull { k ->
                    item.get(k)?.takeIf { it.isJsonPrimitive }?.asString
                } ?: ""
            }.filterKeys { it.isNotEmpty() }

            val changedFields = mutableSetOf<String>()
            for (baseItem in baseItems.take(20)) {
                val itemId = ID_KEYS.firstNotNullOfOrNull { k ->
                    baseItem.get(k)?.takeIf { it.isJsonPrimitive }?.asString
                } ?: continue
                val koItem = koById[itemId] ?: continue
                baseItem.keySet().forEach { key ->
                    val bv = baseItem.get(key)?.takeIf { it.isJsonPrimitive }?.asString ?: ""
                    val kv = koItem.get(key)?.takeIf  { it.isJsonPrimitive }?.asString ?: ""
                    if (bv.isNotEmpty() && kv.isNotEmpty() && bv != kv) changedFields += key
                }
            }

            if (changedFields.isNotEmpty()) return changedFields.toList()

            // Fallback: no ID-matched pairs found — compare first items positionally
            val base0 = baseItems.firstOrNull() ?: return emptyList()
            val ko0   = koItems.firstOrNull()   ?: return emptyList()
            base0.keySet().filter { key ->
                val bv = base0.get(key)?.takeIf { it.isJsonPrimitive }?.asString ?: ""
                val kv = ko0.get(key)?.takeIf  { it.isJsonPrimitive }?.asString ?: ""
                bv.isNotEmpty() && kv.isNotEmpty() && bv != kv
            }.ifEmpty { emptyList() }  // return empty — not DEFAULT_TRANSLATE_FIELDS
        }.getOrElse { DEFAULT_TRANSLATE_FIELDS }
    }

    private fun allItems(path: Path, dataKey: String?): List<JsonObject> {
        val root = JsonParser.parseString(path.readText(Charsets.UTF_8))
        return when {
            root.isJsonArray -> root.asJsonArray.filterIsInstance<JsonObject>()
            root.isJsonObject && dataKey != null ->
                root.asJsonObject.getAsJsonArray(dataKey)
                    ?.filterIsInstance<JsonObject>() ?: emptyList()
            else -> emptyList()
        }
    }

    // Auto-detect data_key for nested JSON structures like {"data": [...], "version": N}
    fun detectDataKey(asset: AssetConfig): String? {
        val path = asset.dir.resolve(asset.baseFile)
        if (!path.exists()) return null
        val root = runCatching { JsonParser.parseString(path.readText(Charsets.UTF_8)) }.getOrNull()
            ?: return null
        if (!root.isJsonObject) return null
        return root.asJsonObject.keySet().firstOrNull { key ->
            root.asJsonObject.get(key).isJsonArray
        }
    }

    // All non-empty string fields in first JSON item — used to populate field checkboxes in dialog
    /** Recursively collect all unique string field names at any depth in the JSON. */
    fun detectAllStringFields(asset: AssetConfig): List<String> {
        val path = asset.dir.resolve(asset.baseFile)
        if (!path.exists()) return DEFAULT_TRANSLATE_FIELDS
        return runCatching {
            val root = JsonParser.parseString(path.readText(Charsets.UTF_8))
            val found = linkedSetOf<String>()
            collectStringFields(root, found, depth = 0)
            found.toList().ifEmpty { DEFAULT_TRANSLATE_FIELDS }
        }.getOrElse { DEFAULT_TRANSLATE_FIELDS }
    }

    private fun collectStringFields(el: JsonElement, found: MutableSet<String>, depth: Int) {
        if (depth > 8) return  // guard against pathological nesting
        when {
            el.isJsonObject -> el.asJsonObject.keySet().forEach { key ->
                val v = el.asJsonObject.get(key)
                if (v.isJsonPrimitive && v.asJsonPrimitive.isString && v.asString.isNotEmpty())
                    found += key
                else
                    collectStringFields(v, found, depth + 1)
            }
            el.isJsonArray -> el.asJsonArray.forEach { item ->
                collectStringFields(item, found, depth + 1)
            }
        }
    }

    // ── Localizability detection ───────────────────────────────────────────────

    /**
     * 3-tier check: Lottie → filename pattern → content scoring.
     * Returns true if this JSON file likely contains user-facing translatable text.
     */
    fun isLocalizableJson(asset: AssetConfig): Boolean {
        val path = asset.dir.resolve(asset.baseFile)
        if (!path.exists()) return false

        // Tier 2: filename pattern (fast, no I/O)
        val name = asset.baseFile.lowercase()
        if (ANIMATION_PREFIXES.any { name.startsWith(it) }) return false

        // Tier 1 + 3: parse JSON (read once)
        val root = runCatching {
            JsonParser.parseString(path.toFile().readText(Charsets.UTF_8))
        }.getOrNull() ?: return false

        // Tier 1: Lottie animation structure
        if (root.isJsonObject) {
            val obj = root.asJsonObject
            if (obj.get("v")?.isJsonPrimitive == true &&
                obj.get("fr")?.isJsonPrimitive == true &&
                obj.has("layers") && obj.get("layers").isJsonArray) return false
        }

        // Tier 3: content scoring — need at least 2 localizable strings
        val samples = mutableListOf<String>()
        collectSampleStrings(root, samples, maxCount = 30)
        return samples.count { isLocalizableString(it) } >= 2
    }

    /** Returns "animation file", "config (no text)", or null (= localizable). */
    fun ignoreReason(asset: AssetConfig): String? {
        val path = asset.dir.resolve(asset.baseFile)
        val name = asset.baseFile.lowercase()
        if (ANIMATION_PREFIXES.any { name.startsWith(it) }) return "animation file"
        if (!path.exists()) return null
        val root = runCatching {
            JsonParser.parseString(path.toFile().readText(Charsets.UTF_8))
        }.getOrNull() ?: return null
        if (root.isJsonObject) {
            val obj = root.asJsonObject
            if (obj.get("v")?.isJsonPrimitive == true &&
                obj.get("fr")?.isJsonPrimitive == true &&
                obj.has("layers") && obj.get("layers").isJsonArray) return "animation (Lottie)"
        }
        val samples = mutableListOf<String>()
        collectSampleStrings(root, samples, maxCount = 30)
        return if (samples.count { isLocalizableString(it) } < 2) "config (no user text)" else null
    }

    private fun collectSampleStrings(el: JsonElement, out: MutableList<String>, maxCount: Int) {
        if (out.size >= maxCount) return
        when {
            el.isJsonObject -> el.asJsonObject.keySet().forEach { key ->
                val v = el.asJsonObject.get(key)
                if (v.isJsonPrimitive && v.asJsonPrimitive.isString) out += v.asString
                else collectSampleStrings(v, out, maxCount)
            }
            el.isJsonArray -> el.asJsonArray.forEach { collectSampleStrings(it, out, maxCount) }
        }
    }

    private fun isLocalizableString(s: String): Boolean =
        s.length >= 8 &&
        ' ' in s &&
        !s.startsWith("file://") &&
        !s.startsWith("http") &&
        !s.matches(Regex("[A-Z_0-9]+")) &&
        !s.matches(Regex("[a-z][a-zA-Z0-9_]+"))

    companion object {
        private val ID_KEYS = listOf("id", "name", "key", "type")
        private val ANIMATION_PREFIXES = listOf("anim_", "animation_", "lottie_")
    }

}
