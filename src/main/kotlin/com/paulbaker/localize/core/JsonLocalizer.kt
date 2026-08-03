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

    data class JsonResult(
        val unmatched: List<UnmatchedField>,
        /** Fields the CSV didn't cover that kept the value already in the locale file. */
        val preserved: List<PreservedField> = emptyList()
    )
    data class UnmatchedField(val context: String, val value: String)
    data class PreservedField(val context: String, val value: String)

    /** Collects what happened during one walk, so translateElement stays at six parameters. */
    private class Acc {
        val unmatched = mutableListOf<UnmatchedField>()
        val preserved = mutableListOf<PreservedField>()
    }

    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    /**
     * @param keepExisting extends the existing-file fallback to Full Replace. Merge always uses
     *   it; this flag only decides whether Full Replace does too.
     */
    fun localize(
        asset: AssetConfig,
        locale: String,
        suffix: String,
        mode: GenerateMode = GenerateMode.MERGE,
        keepExisting: Boolean = false
    ): JsonResult {
        val basePath = asset.dir.resolve(asset.baseFile)
        if (!basePath.exists()) return JsonResult(emptyList())

        val root = JsonParser.parseString(basePath.readText(Charsets.UTF_8))
        val acc  = Acc()
        val fieldsSet = asset.translateFields.ifEmpty { DEFAULT_TRANSLATE_FIELDS }.toSet()

        // Load the existing output as a fallback for fields the CSV doesn't cover.
        val outputPath = asset.dir.resolve("${basePath.toFile().nameWithoutExtension}_$suffix.json")
        val useExisting  = mode == GenerateMode.MERGE || keepExisting
        val existingRoot = if (useExisting && outputPath.exists())
            runCatching { JsonParser.parseString(outputPath.readText(Charsets.UTF_8)) }.getOrNull()
        else null

        val result = translateElement(root, fieldsSet, locale, asset.name, acc, existingRoot)

        outputPath.writeText(gson.toJson(result), Charsets.UTF_8)
        return JsonResult(acc.unmatched, acc.preserved)
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
        acc: Acc,
        existing: JsonElement? = null   // existing output at the same structural position
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
                                val fromCsv = db.lookup("", v, locale)
                                val fromExisting = if (fromCsv != null) null else
                                    existingObj?.get(key)?.takeIf {
                                        it.isJsonPrimitive && it.asJsonPrimitive.isString && it.asString.isNotEmpty()
                                    }?.asString
                                when {
                                    fromCsv != null -> out.addProperty(key, fromCsv)
                                    // Kept from the previous run — recorded, because a value that
                                    // came from neither the CSV nor English is the one worth auditing.
                                    fromExisting != null -> {
                                        out.addProperty(key, fromExisting)
                                        acc.preserved += PreservedField("$ctx.$key", v)
                                    }
                                    else -> { acc.unmatched += UnmatchedField("$ctx.$key", v); out.add(key, child) }
                                }
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
                                    else { acc.unmatched += UnmatchedField("$ctx.$key[$i]", item.asString); translated.add(item) }
                                } else translated.add(translateElement(item, fields, locale, "$ctx.$key[$i]", acc))
                            }
                            out.add(key, translated)
                        }
                        else -> out.add(key, translateElement(child, fields, locale, "$ctx.$key", acc, existingObj?.get(key)))
                    }
                } else {
                    out.add(key, translateElement(child, fields, locale, "$ctx.$key", acc, existingObj?.get(key)))
                }
            }
            out
        }
        el.isJsonArray -> {
            val existingArr = existing?.takeIf { it.isJsonArray }?.asJsonArray
            // Pair each item with the SAME item in the existing output, matched by its own
            // identity (id / name / key / type) — never by position.
            //
            // Positional pairing hands an item whatever used to sit at that index, so as soon
            // as the list is reordered, or an entry is inserted or removed, every item below
            // inherits a neighbour's text. The item then silently carries a translation that
            // belongs to a different feature: far worse than staying in English, because
            // nothing about the output looks wrong.
            //
            // An item whose identity is absent from the existing file gets NO fallback: it is
            // new to this locale, so English is the honest answer and the report says so.
            val existingById = HashMap<String, JsonElement>()
            existingArr?.forEach { e -> itemIdentity(e)?.let { existingById.putIfAbsent(it, e) } }

            JsonArray().also { out ->
                el.asJsonArray.forEachIndexed { i, item ->
                    val identity = itemIdentity(item)
                    val existingItem = when {
                        identity != null -> existingById[identity]
                        // No identity to match on (primitives, or objects without any id field)
                        // — position is all there is.
                        existingArr != null && i < existingArr.size() -> existingArr[i]
                        else -> null
                    }
                    out.add(translateElement(item, fields, locale, "$ctx[$i]", acc, existingItem))
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

        /**
         * Stable identity of one array item, used to line it up with the same item in an
         * existing localized file or in a locale file being exported.
         *
         * Returns null when the element carries no usable id field — callers must then decide
         * for themselves whether positional matching is acceptable.
         */
        fun itemIdentity(el: JsonElement): String? {
            if (!el.isJsonObject) return null
            val obj = el.asJsonObject
            ID_KEYS.forEach { key ->
                val v = obj.get(key)
                if (v != null && v.isJsonPrimitive) {
                    val s = v.asString
                    if (s.isNotEmpty()) return "$key=$s"
                }
            }
            return null
        }
    }

}
