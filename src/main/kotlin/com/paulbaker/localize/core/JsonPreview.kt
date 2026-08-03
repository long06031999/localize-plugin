package com.paulbaker.localize.core

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * Computes what [JsonLocalizer] would write, without writing it.
 *
 * This lives beside the localizer rather than inside the dialog on purpose: the value of a
 * preview is that it cannot disagree with the real thing, and the only way to keep that true is
 * to keep the two resolution orders side by side where a change to one is obviously a change to
 * the other. Both resolve a selected field as: spreadsheet → existing locale file → English.
 */
object JsonPreview {

    /** Where each selected field's value came from — surfaced in the dialog's status line. */
    class Stats {
        var fromCsv = 0
        var fromFile = 0
        var english = 0

        val total: Int get() = fromCsv + fromFile + english
    }

    /**
     * @param ref the existing `*_{locale}.json` tree, or null when there is none
     * @param translate (english, locale) → spreadsheet translation; null when no spreadsheet
     */
    fun build(
        base: JsonElement,
        ref: JsonElement?,
        fields: Set<String>,
        locale: String,
        translate: ((String, String) -> String?)?,
        stats: Stats = Stats()
    ): JsonElement = when {
        base.isJsonObject -> {
            val obj    = base.asJsonObject
            val refObj = ref?.takeIf { it.isJsonObject }?.asJsonObject
            val out    = JsonObject()
            obj.keySet().forEach { key ->
                val child = obj.get(key)
                val isSelectedString = key in fields &&
                    child.isJsonPrimitive && child.asJsonPrimitive.isString && child.asString.isNotEmpty()
                when {
                    isSelectedString -> {
                        val en      = child.asString
                        val fromCsv = translate?.invoke(en, locale)?.takeIf { it.isNotEmpty() }
                        val fromRef = if (fromCsv != null) null else refObj?.get(key)
                            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString && it.asString.isNotEmpty() }
                            ?.asString
                        when {
                            fromCsv != null -> { out.addProperty(key, fromCsv); stats.fromCsv++ }
                            fromRef != null -> { out.addProperty(key, fromRef); stats.fromFile++ }
                            else            -> { out.add(key, child);           stats.english++ }
                        }
                    }
                    // A selected field holding an array of strings: JsonLocalizer translates
                    // these from the spreadsheet only, with no locale-file fallback.
                    key in fields && child.isJsonArray -> {
                        val outArr = JsonArray()
                        child.asJsonArray.forEach { item ->
                            if (item.isJsonPrimitive && item.asJsonPrimitive.isString && item.asString.isNotEmpty()) {
                                val tr = translate?.invoke(item.asString, locale)?.takeIf { it.isNotEmpty() }
                                if (tr != null) { outArr.add(tr);  stats.fromCsv++ }
                                else            { outArr.add(item); stats.english++ }
                            } else outArr.add(build(item, null, fields, locale, translate, stats))
                        }
                        out.add(key, outArr)
                    }
                    child.isJsonObject || child.isJsonArray ->
                        out.add(key, build(child, refObj?.get(key), fields, locale, translate, stats))
                    else -> out.add(key, child)
                }
            }
            out
        }
        base.isJsonArray -> {
            val refArr = ref?.takeIf { it.isJsonArray }?.asJsonArray
            // Pair by item identity, never by index — see JsonLocalizer for why.
            val refById = HashMap<String, JsonElement>()
            refArr?.forEach { e -> JsonLocalizer.itemIdentity(e)?.let { refById.putIfAbsent(it, e) } }
            JsonArray().also { out ->
                base.asJsonArray.forEachIndexed { i, item ->
                    val identity = JsonLocalizer.itemIdentity(item)
                    val refItem = when {
                        identity != null -> refById[identity]
                        refArr != null && i < refArr.size() -> refArr[i]
                        else -> null
                    }
                    out.add(build(item, refItem, fields, locale, translate, stats))
                }
            }
        }
        else -> base
    }
}
