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

            if (key.isNotEmpty()) {
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
            if (english.isNotEmpty()) {
                val ne = normEn(english)
                val map = byEn.getOrPut(ne) { mutableMapOf() }
                trans.forEach { (locale, v) -> map.putIfAbsent(locale, v) }
            }
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

    // ── Utilities ──────────────────────────────────────────────────────────────

    fun normKey(k: String): String = k.trim().lowercase().replace('-', '_')
    fun normEn(s: String): String  = s.trim().lowercase().replace(Regex("\\s+"), " ")

    fun readHeaders(path: Path): List<String> {
        if (!path.exists()) return emptyList()
        val line = BufferedReader(InputStreamReader(path.inputStream(), Charsets.UTF_8))
            .readLine() ?: return emptyList()
        return parseCsvLine(line).map { stripBom(it) }
    }

    companion object {
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
