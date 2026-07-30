package com.paulbaker.localize.core

import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.inputStream

class TranslationDb {

    data class Entry(val english: String, val tr: MutableMap<String, String> = mutableMapOf())

    // normalized key → Entry
    val byKey = mutableMapOf<String, Entry>()
    // normalized english text → {locale: translation}
    val byEn = mutableMapOf<String, MutableMap<String, String>>()
    // normalized array name → { normalized english → {locale: translation} }
    val byArray = mutableMapOf<String, MutableMap<String, MutableMap<String, String>>>()
    // normalized plurals name → { quantity → {locale: translation} }
    // Fed by rows keyed "plural_name:quantity" (the format ExcelExporter writes).
    val byPlural = mutableMapOf<String, MutableMap<String, MutableMap<String, String>>>()

    val conflicts = mutableListOf<Conflict>()

    data class Conflict(val key: String, val locale: String, val used: String, val ignored: String)

    // ── CSV loading ────────────────────────────────────────────────────────────

    fun loadCsv(path: Path, keyCol: String, langMap: Map<String, String>) {
        if (!path.exists()) return
        val content = path.toFile().readText(Charsets.UTF_8)
        val rows = parseCsvFull(content)
        if (rows.isEmpty()) return

        val headers = rows[0].map { stripBom(it) }
        val keyIdx = headers.indexOf(keyCol.lowercase())
        val enIdx  = headers.indexOf("english")
        val langIdxMap = langMap.mapNotNull { (col, locale) ->
            val idx = headers.indexOf(col.lowercase())
            if (idx >= 0) idx to locale else null
        }.toMap()

        for (i in 1 until rows.size) {
            val cols = rows[i]
            val key     = if (keyIdx >= 0 && keyIdx < cols.size) cols[keyIdx].trim() else ""
            val english = if (enIdx  >= 0 && enIdx  < cols.size) cols[enIdx].trim()  else ""
            val trans   = langIdxMap.mapNotNull { (idx, locale) ->
                val v = cols.getOrNull(idx)?.trim() ?: ""
                if (v.isNotEmpty()) locale to v else null
            }.toMap()

            if (key.isEmpty() && english.isEmpty() && trans.isEmpty()) continue

            ingestRow(key, english, trans)
        }
    }

    // Sparse CSV: android_key only on first row of each group
    fun loadArrayCsv(path: Path, langMap: Map<String, String>) {
        if (!path.exists()) return
        val content = path.toFile().readText(Charsets.UTF_8)
        val rows = parseCsvFull(content)
        if (rows.isEmpty()) return

        val headers = rows[0].map { stripBom(it) }
        val keyIdx = headers.indexOf("android_key")
        val enIdx  = headers.indexOf("english")
        val langIdxMap = langMap.mapNotNull { (col, locale) ->
            val idx = headers.indexOf(col.lowercase())
            if (idx >= 0) idx to locale else null
        }.toMap()

        var currentArray: String? = null
        for (i in 1 until rows.size) {
            val cols = rows[i]
            val arrKey  = if (keyIdx >= 0 && keyIdx < cols.size) cols[keyIdx].trim() else ""
            val english = if (enIdx  >= 0 && enIdx  < cols.size) cols[enIdx].trim()  else ""

            if (arrKey.isNotEmpty()) currentArray = normKey(arrKey)
            val arr = currentArray ?: continue
            if (english.isEmpty()) continue

            val trans = langIdxMap.mapNotNull { (idx, locale) ->
                val v = cols.getOrNull(idx)?.trim() ?: ""
                if (v.isNotEmpty()) locale to v else null
            }.toMap()
            if (trans.isEmpty()) continue

            val ne = normEn(english)
            val arrMap = byArray.getOrPut(arr) { mutableMapOf() }
            val locMap = arrMap.getOrPut(ne) { mutableMapOf() }
            trans.forEach { (locale, v) -> locMap.putIfAbsent(locale, v) }
        }
    }

    /**
     * Load a CSV that has been pre-processed by CsvPreprocessor.applyMapping().
     * Normalized rows format: [android_key, english, lang1_value, lang2_value, ...]
     * localeOrder: ["es", "ja", "ko", ...] matching the column positions.
     */
    fun loadCsvFromNormalized(rows: List<List<String>>, localeOrder: List<String>) {
        for (row in rows) {
            val key     = row.getOrNull(0)?.trim() ?: ""
            val english = row.getOrNull(1)?.trim() ?: ""
            if (key.isEmpty() && english.isEmpty()) continue

            val trans = localeOrder.mapIndexedNotNull { i, locale ->
                val v = row.getOrNull(i + 2)?.trim() ?: ""
                if (v.isNotEmpty()) locale to v else null
            }.toMap()

            ingestRow(key, english, trans)
        }
    }

    /**
     * Load a mapped arrays CSV using sparse array logic.
     * Input: normalized rows from CsvPreprocessor.applyMapping() —
     *   format: [android_key_or_empty, english, lang1_value, lang2_value, ...]
     * The key column may be: array_name (first row of group) OR empty (subsequent items).
     * localeOrder: locale codes in column order.
     */
    fun loadArrayCsvFromNormalized(rows: List<List<String>>, localeOrder: List<String>) {
        var currentArray: String? = null
        for (row in rows) {
            val key     = row.getOrNull(0)?.trim() ?: ""
            val english = row.getOrNull(1)?.trim() ?: ""
            // Carry forward the array name when key is empty (sparse format)
            if (key.isNotEmpty()) currentArray = normKey(key)
            val arr = currentArray ?: continue
            if (english.isEmpty()) continue
            val trans = localeOrder.mapIndexedNotNull { i, locale ->
                val v = row.getOrNull(i + 2)?.trim() ?: ""
                if (v.isNotEmpty()) locale to v else null
            }.toMap()
            if (trans.isEmpty()) continue
            val ne = normEn(english)
            val arrMap = byArray.getOrPut(arr) { mutableMapOf() }
            val locMap = arrMap.getOrPut(ne) { mutableMapOf() }
            trans.forEach { (locale, v) -> locMap.putIfAbsent(locale, v) }
        }
    }

    /**
     * Register one source row into the lookup maps.
     *
     * A key of the form `name:quantity` is a plurals row, not a `<string>` — Android resource
     * names cannot contain `:`, so the pattern is unambiguous. It goes to [byPlural] and keeps
     * each quantity distinct, which matters for locales where `one` and `other` share the same
     * English source but differ in translation.
     */
    private fun ingestRow(key: String, english: String, trans: Map<String, String>) {
        if (key.isNotEmpty()) {
            val plural = PLURAL_KEY.matchEntire(key.trim())
            if (plural != null) {
                val quantities = byPlural.getOrPut(normKey(plural.groupValues[1])) { mutableMapOf() }
                val locMap     = quantities.getOrPut(plural.groupValues[2].lowercase()) { mutableMapOf() }
                trans.forEach { (locale, v) -> locMap.putIfAbsent(locale, v) }
            } else {
                val nk = normKey(key)
                val entry = byKey.getOrPut(nk) { Entry(english) }
                if (entry.english.isEmpty() && english.isNotEmpty())
                    byKey[nk] = entry.copy(english = english)
                trans.forEach { (locale, v) ->
                    val existing = entry.tr[locale]
                    if (existing != null && existing != v)
                        conflicts += Conflict(key, locale, existing, v)
                    else
                        entry.tr[locale] = v
                }
            }
        }
        if (english.isNotEmpty()) {
            val ne = normEn(english)
            val map = byEn.getOrPut(ne) { mutableMapOf() }
            trans.forEach { (locale, v) -> map.putIfAbsent(locale, v) }
        }
    }

    // ── Lookup ─────────────────────────────────────────────────────────────────

    fun lookup(name: String, enText: String, locale: String): String? {
        if (name.isNotEmpty()) {
            byKey[normKey(name)]?.tr?.get(locale)?.let { return it }
        }
        if (enText.isNotEmpty()) {
            byEn[normEn(enText)]?.get(locale)?.let { return it }
        }
        return null
    }

    fun lookupArray(arrayName: String, enText: String, locale: String): String? {
        val arrMap = byArray[normKey(arrayName)] ?: return null
        return arrMap[normEn(enText)]?.get(locale)
    }

    fun lookupPlural(pluralName: String, quantity: String, locale: String): String? =
        byPlural[normKey(pluralName)]?.get(quantity.trim().lowercase())?.get(locale)

    // ── Utilities ──────────────────────────────────────────────────────────────

    fun normKey(k: String): String = k.trim().lowercase().replace('-', '_')

    /**
     * Canonical form of an English source string, used as the lookup key for [byEn] / [byArray].
     *
     * Invisible characters are stripped first: spreadsheets routinely carry a stray
     * VARIATION SELECTOR-16 or zero-width character that the XML template doesn't have
     * (e.g. `U+FE0F + "Work Tasks"` in the CSV vs plain `"Work Tasks"` in `strings.xml`),
     * which would otherwise make the item unmatchable and silently skip the whole
     * string-array. Emoji themselves are kept, so `"Business"` with a leading briefcase
     * emoji still never matches bare `"Business"`.
     */
    fun normEn(s: String): String = s
        .replace(INVISIBLE_CHARS, "")
        .replace('\u00A0', ' ')
        .trim().lowercase()
        .replace(Regex("\\s+"), " ")

    fun readHeaders(path: Path): List<String> {
        if (!path.exists()) return emptyList()
        val line = BufferedReader(InputStreamReader(path.inputStream(), Charsets.UTF_8))
            .readLine() ?: return emptyList()
        return parseCsvLine(line).map { stripBom(it) }
    }

    companion object {
        /** `plural_name:quantity` — the key format ExcelExporter writes for `<plurals>` items. */
        private val PLURAL_KEY = Regex("""^([A-Za-z_][A-Za-z0-9_]*):(zero|one|two|few|many|other)$""", RegexOption.IGNORE_CASE)

        /** Variation selectors + zero-width characters + BOM — invisible, never meaningful for matching. */
        private val INVISIBLE_CHARS = Regex("[\uFE00-\uFE0F\u200B-\u200D\u2060\uFEFF]")

        /** Strip UTF-8 BOM (﻿), trim whitespace, lowercase. */
        fun stripBom(s: String): String = s.trimStart('\uFEFF').trim().lowercase()

        /**
         * Full CSV parser — correctly handles quoted fields containing newlines.
         * Returns rows as list of columns.
         */
        fun parseCsvFull(content: String): List<List<String>> {
            val rows  = mutableListOf<List<String>>()
            val row   = mutableListOf<String>()
            val field = StringBuilder()
            var inQuotes = false
            var i = 0
            while (i < content.length) {
                val c = content[i]
                when {
                    c == '"' -> {
                        if (inQuotes && i + 1 < content.length && content[i + 1] == '"') {
                            field.append('"'); i++
                        } else inQuotes = !inQuotes
                    }
                    c == ',' && !inQuotes -> { row.add(field.toString()); field.clear() }
                    c == '\r' && !inQuotes -> {
                        if (i + 1 < content.length && content[i + 1] == '\n') i++
                        row.add(field.toString()); field.clear()
                        if (row.any { it.isNotEmpty() }) rows.add(row.toList())
                        row.clear()
                    }
                    c == '\n' && !inQuotes -> {
                        row.add(field.toString()); field.clear()
                        if (row.any { it.isNotEmpty() }) rows.add(row.toList())
                        row.clear()
                    }
                    c == '\r' && inQuotes -> { /* skip \r inside quotes */ }
                    else -> field.append(c)
                }
                i++
            }
            if (field.isNotEmpty() || row.isNotEmpty()) {
                row.add(field.toString())
                if (row.any { it.isNotEmpty() }) rows.add(row.toList())
            }
            return rows
        }

                fun parseCsvLine(line: String): List<String> {
            val result = mutableListOf<String>()
            val sb = StringBuilder()
            var inQuotes = false
            var i = 0
            while (i < line.length) {
                when (val c = line[i]) {
                    '"' -> if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                        sb.append('"'); i++
                    } else {
                        inQuotes = !inQuotes
                    }
                    ',' -> if (inQuotes) sb.append(c) else { result += sb.toString(); sb.clear() }
                    else -> sb.append(c)
                }
                i++
            }
            result += sb.toString()
            return result
        }
    }
}
