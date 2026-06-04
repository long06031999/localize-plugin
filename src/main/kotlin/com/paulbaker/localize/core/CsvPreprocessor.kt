package com.paulbaker.localize.core

import com.paulbaker.localize.config.CsvMapping
import com.paulbaker.localize.config.LANGUAGE_LOCALE_MAP
import com.paulbaker.localize.config.NON_LANGUAGE_COLS
import java.nio.file.Path

object CsvPreprocessor {

    // ── Auto-detection ─────────────────────────────────────────────────────────

    /** Suffixes commonly added to language column names by translators. */
    private val LANG_SUFFIXES = listOf(
        "(dịch cơm)", "(Dịch cơm)", "(machine translate)", "(MT)",
        "(mt)", "(auto)", "(Auto)", "(翻訳)", "(번역)", "(译)"
    )

    /** Column name patterns that indicate a QC/review column to skip. */
    private val QC_KEYWORDS = listOf("qc", "check", "review", "status", "note", "comment", "verified")

    /** Column names that map to android_key. */
    private val KEY_NAMES = setOf("key", "android_key", "id", "string_key", "string_id", "resource_key")

    /** Column names that map to english. */
    private val ENGLISH_NAMES = setOf(
        "text", "english", "en", "source", "value", "string", "original", "default"
    )

    /**
     * Suggest an initial mapping based on header names and sample data.
     * All suggestions can be overridden by the user in CsvMappingDialog.
     */
    fun autoDetect(headers: List<String>, sampleRows: List<List<String>>): CsvMapping {
        var keyColumn     = ""
        var englishColumn = ""
        val langColumns   = mutableMapOf<String, String>()

        headers.forEachIndexed { idx, rawHeader ->
            val h = rawHeader.trim()
            if (h.isEmpty()) return@forEachIndexed   // unnamed → skip

            val hLower = h.lowercase()

            // QC/review column?
            if (QC_KEYWORDS.any { hLower.contains(it) }) return@forEachIndexed

            // android_key?
            if (keyColumn.isEmpty() && (hLower in KEY_NAMES || hLower.contains("android_key"))) {
                keyColumn = h; return@forEachIndexed
            }

            // english?
            if (englishColumn.isEmpty() && hLower in ENGLISH_NAMES) {
                englishColumn = h; return@forEachIndexed
            }

            // Language column? Strip suffixes and try resolving locale.
            val stripped = stripLangSuffix(h)
            val locale   = resolveLocale(stripped)
            if (locale != null) {
                langColumns[h] = locale
                return@forEachIndexed
            }

            // Heuristic: column whose sample values are all very short (QC checkmarks)
            val samples = sampleRows.map { it.getOrNull(idx)?.trim() ?: "" }.filter { it.isNotEmpty() }
            if (samples.isNotEmpty() && samples.all { it.length <= 3 }) return@forEachIndexed

            // Default: unrecognized → skip (user can change in dialog)
        }

        return CsvMapping(
            keyColumn       = keyColumn,
            englishColumn   = englishColumn,
            languageColumns = langColumns,
        )
    }

    fun stripLangSuffix(name: String): String {
        var result = name
        LANG_SUFFIXES.forEach { suffix ->
            result = result.replace(suffix, "", ignoreCase = true)
        }
        return result.trim()
    }

    private fun resolveLocale(name: String): String? {
        val h = name.trim().lowercase()
        LANGUAGE_LOCALE_MAP[h]?.let { return it }
        for (locale in java.util.Locale.getAvailableLocales()) {
            if (locale.language.isEmpty()) continue
            if (locale.getDisplayLanguage(java.util.Locale.ENGLISH).lowercase() == h) return locale.language
            if (locale.displayLanguage.lowercase() == h) return locale.language
        }
        return null
    }

    // ── Apply mapping ──────────────────────────────────────────────────────────

    /**
     * Apply a CsvMapping to a raw CSV, returning normalized rows:
     *   [ [android_key, english, lang1_value, lang2_value, ...], ... ]
     *
     * Also returns the ordered locale list so TranslationDb knows which column = which locale.
     */
    data class NormalizedCsv(
        val rows: List<List<String>>,
        val localeOrder: List<String>   // locale codes in column order
    )

    fun applyMapping(csvPath: Path, mapping: CsvMapping): NormalizedCsv {
        val raw  = csvPath.toFile().readText(Charsets.UTF_8)
        val allRows = TranslationDb.parseCsvFull(raw)
        if (allRows.isEmpty()) return NormalizedCsv(emptyList(), emptyList())

        // Parse header row
        val headerRow = allRows[0].map { TranslationDb.stripBom(it) }
        val keyIdx    = headerRow.indexOfFirst { it.trim() == mapping.keyColumn.trim() }
        val enIdx     = headerRow.indexOfFirst { it.trim() == mapping.englishColumn.trim() }

        // Build ordered list of (colIdx, locale)
        val langCols = mapping.languageColumns.entries.mapNotNull { (colName, locale) ->
            val idx = headerRow.indexOfFirst { it.trim() == colName.trim() }
            if (idx >= 0) idx to locale else null
        }.sortedBy { it.first }

        val localeOrder = langCols.map { it.second }

        val result = mutableListOf<List<String>>()
        for (i in 1 until allRows.size) {
            val row = allRows[i]

            // Skip empty rows
            if (mapping.skipEmptyRows && row.all { it.isBlank() }) continue

            // Skip section header rows (≤1 non-empty cell)
            if (mapping.skipSectionRows) {
                val nonEmpty = row.count { it.isNotBlank() }
                if (nonEmpty <= 1) continue
            }

            val key     = if (keyIdx >= 0 && keyIdx < row.size) row[keyIdx].trim() else ""
            val english = if (enIdx  >= 0 && enIdx  < row.size) row[enIdx].trim()  else ""

            // Skip rows where BOTH key AND english are empty — nothing to use
            // Rows with empty key but non-empty english are kept: they contribute to byEn lookup
            if (key.isEmpty() && english.isEmpty()) continue

            val normalizedRow = mutableListOf(key, english)
            langCols.forEach { (colIdx, _) ->
                normalizedRow += if (colIdx < row.size) row[colIdx].trim() else ""
            }
            result += normalizedRow
        }

        return NormalizedCsv(result, localeOrder)
    }
}
