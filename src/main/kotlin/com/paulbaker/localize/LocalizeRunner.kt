package com.paulbaker.localize

import com.paulbaker.localize.config.GenerateMode
import com.paulbaker.localize.config.LocalizeConfig
import com.paulbaker.localize.core.CsvPreprocessor
import com.paulbaker.localize.core.JsonLocalizer
import com.paulbaker.localize.core.TranslationDb
import com.paulbaker.localize.core.XmlGenerator
import com.paulbaker.localize.ui.OutputLevel
import java.nio.file.Path
import kotlin.io.path.writeText

class LocalizeRunner {

    fun run(config: LocalizeConfig, logger: (String, OutputLevel) -> Unit) =
        generate(loadCsvs(config, logger), config, logger)

    private fun loadCsvs(config: LocalizeConfig, logger: (String, OutputLevel) -> Unit): TranslationDb {
        val langMap = config.selectedLanguages  // csv_col → locale

        // ── Phase 1: Load CSVs ─────────────────────────────────────────────────
        logger("Phase 1: Loading CSVs...", OutputLevel.INFO)
        val db = TranslationDb()
        config.csvAndroidOnly?.let { path ->
            val mapping = config.csvMappings[path.toString()]
            if (mapping != null) {
                val norm = CsvPreprocessor.applyMapping(path, mapping)
                db.loadCsvFromNormalized(norm.rows, norm.localeOrder)
                logger("  ✓ android_only: loaded (custom mapping, ${norm.rows.size} rows)", OutputLevel.INFO)
            } else {
                db.loadCsv(path, "android_key", langMap)
                logger("  ✓ android_only: loaded", OutputLevel.INFO)
            }
        }
        config.csvOverlap?.let { path ->
            val mapping = config.csvMappings[path.toString()]
            if (mapping != null) {
                val norm = CsvPreprocessor.applyMapping(path, mapping)
                db.loadCsvFromNormalized(norm.rows, norm.localeOrder)
                logger("  ✓ overlap: loaded (custom mapping, ${norm.rows.size} rows)", OutputLevel.INFO)
            } else {
                db.loadCsv(path, "unified_id", langMap)
                logger("  ✓ overlap: loaded", OutputLevel.INFO)
            }
        }
        config.csvArrays?.let { path ->
            val mapping = config.csvMappings[path.toString()]
            if (mapping != null) {
                // Custom mapping: normalize columns first, then apply sparse array logic
                val norm = CsvPreprocessor.applyMapping(path, mapping)
                db.loadArrayCsvFromNormalized(norm.rows, norm.localeOrder)
                logger("  ✓ arrays: loaded (custom mapping, ${db.byArray.size} arrays)", OutputLevel.INFO)
            } else {
                db.loadArrayCsv(path, langMap)
                logger("  ✓ arrays: loaded (${db.byArray.size} arrays)", OutputLevel.INFO)
            }
        }
        return db
    }

    /** Phases 2–4, from an already-populated [db]. */
    private fun generate(db: TranslationDb, config: LocalizeConfig, logger: (String, OutputLevel) -> Unit) {
        val langMap = config.selectedLanguages

        logger("  DB: ${db.byKey.size} unique keys, ${db.byEn.size} English phrases" +
               (if (db.byPlural.isNotEmpty()) ", ${db.byPlural.size} plurals" else ""), OutputLevel.INFO)
        if (db.conflicts.isNotEmpty())
            logger("  ⚠ ${db.conflicts.size} source conflicts (android_only takes priority)", OutputLevel.WARN)

        val valuesDir = config.resolvedValuesDir()
        val xmlGen = XmlGenerator(db)
        xmlGen.scanCdataKeys(valuesDir)
        // Pass selected assets so XmlGenerator can dynamically detect JSON path strings
        xmlGen.setSelectedAssets(config.selectedAssets)
        xmlGen.setGenerateMode(config.generateMode)
        xmlGen.setPreserveKeyOrder(config.preserveKeyOrder)
        xmlGen.setOverrideNonTranslatable(config.overrideNonTranslatable)
        val jsonLoc = JsonLocalizer(db)
        logger("  Mode: ${config.generateMode.name.lowercase().replace('_', ' ')}", OutputLevel.INFO)
        logger("  Key order: ${if (config.preserveKeyOrder) "preserve existing positions" else "follow template"}", OutputLevel.INFO)
        if (config.overrideNonTranslatable)
            logger("  translatable=\"false\": overridden when the source has a value", OutputLevel.WARN)
        if (config.keepExistingJsonFields && config.generateMode == GenerateMode.FULL_REPLACE)
            logger("  JSON: keeping existing translations the CSV doesn't cover", OutputLevel.WARN)

        val allReports = mutableMapOf<String, LocaleReport>()

        // ── Phase 2 & 3: Per-locale generation ────────────────────────────────
        langMap.forEach { (csvCol, locale) ->
            logger("\n─── Locale: $locale ───────────────────────────────────", OutputLevel.INFO)
            val report = LocaleReport(locale)

            // XML files
            if (config.selectedXmlFiles.isNotEmpty()) {
                logger("Phase 2: Generating XML for $locale...", OutputLevel.INFO)
                config.selectedXmlFiles.forEach { xmlFile ->
                    val tmpl = valuesDir.resolve(xmlFile)
                    val out  = valuesDir.parent.resolve("values-$locale").resolve(xmlFile)
                    val result = xmlGen.generate(tmpl, out, locale, locale)
                    val preservedNote = if (result.preserved > 0) ", ${result.preserved} preserved" else ""
                    val newNote       = if (result.addedKeys.isNotEmpty()) " (+${result.addedKeys.size} new)" else ""
                    logger("  [$locale] $xmlFile → ${result.written} written, ${result.skipped} skipped$preservedNote$newNote", OutputLevel.INFO)
                    report.xmlWritten    += result.written
                    report.xmlSkipped    += result.skipped
                    report.xmlPreserved  += result.preserved
                    report.addedKeys     += result.addedKeys
                    report.changedKeys   += result.changedKeys
                    report.skippedArrays += result.skippedArrays
                    report.notFoundKeys  += result.notFoundKeys
                    report.ntOverridden  += result.ntOverridden
                    report.ntSkipped     += result.ntSkipped
                }
            }

            // JSON assets
            if (config.selectedAssets.isNotEmpty()) {
                logger("Phase 3: Generating JSON for $locale...", OutputLevel.INFO)
                config.selectedAssets.forEach { asset ->
                    val result = jsonLoc.localize(
                        asset, locale, locale, config.generateMode, config.keepExistingJsonFields
                    )
                    val keptNote = if (result.preserved.isNotEmpty()) " (${result.preserved.size} kept)" else ""
                    logger("  [$locale] ${asset.baseFile.substringBeforeLast(".")}_$locale.json$keptNote", OutputLevel.INFO)
                    report.jsonWritten++
                    report.jsonUnmatched += result.unmatched
                    report.jsonPreserved += result.preserved
                }
            }

            allReports[locale] = report
        }

        // ── Phase 4: Write reports ─────────────────────────────────────────────
        logger("\nPhase 4: Writing reports...", OutputLevel.INFO)
        allReports.forEach { (locale, report) ->
            val reportPath = config.resolvedReportDir().resolve("localize_report_$locale.md")
            reportPath.writeText(report.toMarkdown(db.conflicts), Charsets.UTF_8)
            logger("  [$locale] Report → localize_report_$locale.md", OutputLevel.INFO)
        }

        logger("\n✅ Done.", OutputLevel.SUCCESS)
    }
}


class LocaleReport(val locale: String) {
    var xmlWritten   = 0
    var xmlSkipped   = 0
    var xmlPreserved = 0
    val addedKeys    = mutableListOf<String>()
    val changedKeys  = mutableListOf<String>()
    val skippedArrays = mutableListOf<XmlGenerator.SkippedArray>()
    val notFoundKeys  = mutableListOf<XmlGenerator.NotFound>()
    val ntOverridden  = mutableListOf<XmlGenerator.NonTranslatable>()
    val ntSkipped     = mutableListOf<XmlGenerator.NonTranslatable>()
    var jsonWritten  = 0
    val jsonUnmatched = mutableListOf<JsonLocalizer.UnmatchedField>()
    val jsonPreserved = mutableListOf<JsonLocalizer.PreservedField>()

    fun toMarkdown(allConflicts: List<TranslationDb.Conflict>): String {
        val conflicts = allConflicts.filter { it.locale == locale }
        val sb = StringBuilder()
        sb.appendLine("# Localization Report: $locale\n")
        sb.appendLine("## Summary\n")
        sb.appendLine("| Metric | Count |")
        sb.appendLine("|---|---|")
        sb.appendLine("| XML strings written (from CSV) | $xmlWritten |")
        sb.appendLine("| XML strings preserved (Merge) | $xmlPreserved |")
        sb.appendLine("| XML strings skipped (no translation) | $xmlSkipped |")
        sb.appendLine("| XML new keys added | ${addedKeys.size} |")
        sb.appendLine("| XML values changed | ${changedKeys.size} |")
        sb.appendLine("| JSON files written | $jsonWritten |")
        sb.appendLine("| JSON fields unmatched | ${jsonUnmatched.size} |")
        sb.appendLine("| JSON fields kept from previous file | ${jsonPreserved.size} |")
        sb.appendLine("| CSV conflicts | ${conflicts.size} |")
        sb.appendLine("| Skipped string-arrays | ${skippedArrays.size} |")
        sb.appendLine("| Non-translatable overridden | ${ntOverridden.size} |")
        sb.appendLine("| Non-translatable skipped | ${ntSkipped.size} |")
        sb.appendLine()

        if (changedKeys.isNotEmpty()) {
            sb.appendLine("## Changed Values\n")
            changedKeys.forEach { sb.appendLine("- `$it`") }
            sb.appendLine()
        }

        if (conflicts.isNotEmpty()) {
            sb.appendLine("## CSV Conflicts (android_only used)\n")
            sb.appendLine("| Key | Used | Ignored |")
            sb.appendLine("|---|---|---|")
            conflicts.forEach { c ->
                sb.appendLine("| `${c.key}` | ${c.used} | ${c.ignored} |")
            }
            sb.appendLine()
        }

        if (notFoundKeys.isNotEmpty()) {
            sb.appendLine("## XML Keys Not Found in CSV\n")
            sb.appendLine("| Key | English |")
            sb.appendLine("|---|---|")
            notFoundKeys.forEach { sb.appendLine("| `${it.key}` | ${it.enText} |") }
            sb.appendLine()
        }

        if (jsonPreserved.isNotEmpty()) {
            sb.appendLine("## JSON Fields Kept From Previous File\n")
            sb.appendLine("The CSV had no translation for these, so the value already in the locale")
            sb.appendLine("file was kept. They are NOT covered by the current spreadsheet.\n")
            sb.appendLine("| File.Field | English Value | Times |")
            sb.appendLine("|---|---|:---:|")
            jsonPreserved
                .groupBy { it.context.replace(Regex("""\[\d+\]"""), "") to it.value }
                .mapValues { it.value.size }
                .entries.sortedBy { it.key.first }
                .forEach { (k, count) -> sb.appendLine("| `${k.first}` | ${k.second} | $count |") }
            sb.appendLine()
        }

        if (jsonUnmatched.isNotEmpty()) {
            sb.appendLine("## JSON Fields Not Matched (kept English)\n")
            sb.appendLine("| File.Field | English Value | Times |")
            sb.appendLine("|---|---|:---:|")
            val grouped = jsonUnmatched
                .groupBy { it.context.replace(Regex("""\[\d+\]"""), "") to it.value }
                .mapValues { it.value.size }
            grouped.entries.sortedBy { it.key.first }.forEach { (k, count) ->
                sb.appendLine("| `${k.first}` | ${k.second} | $count |")
            }
            sb.appendLine()
        }

        if (ntOverridden.isNotEmpty()) {
            sb.appendLine("## Non-Translatable — Overridden\n")
            sb.appendLine("These carry `translatable=\"false\"` in the template but the source supplied a")
            sb.appendLine("translation, so they WERE written. Verify each one is meant to be localized.\n")
            sb.appendLine("| Key | Kind | Source (EN) | Written |")
            sb.appendLine("|---|---|---|---|")
            ntOverridden.forEach { nt ->
                sb.appendLine("| `${nt.key}` | ${nt.kind} | ${nt.enText} | ${nt.translation ?: ""} |")
            }
            sb.appendLine()
        }

        if (ntSkipped.isNotEmpty()) {
            sb.appendLine("## Non-Translatable — Skipped\n")
            sb.appendLine("`translatable=\"false\"` was honoured: these are absent from the locale file.\n")
            sb.appendLine("| Key | Kind | Value (EN) |")
            sb.appendLine("|---|---|---|")
            ntSkipped.forEach { nt -> sb.appendLine("| `${nt.key}` | ${nt.kind} | ${nt.enText} |") }
            sb.appendLine()
        }

        if (skippedArrays.isNotEmpty()) {
            sb.appendLine("## Skipped String-Arrays\n")
            sb.appendLine("| Array | Found / Total | Missing items |")
            sb.appendLine("|---|:---:|---|")
            skippedArrays.forEach { arr ->
                val show = arr.missing.take(3).joinToString(", ") { "`$it`" }
                val more = if (arr.missing.size > 3) ", … and ${arr.missing.size - 3} more" else ""
                sb.appendLine("| `${arr.name}` | ${arr.found} / ${arr.total} | $show$more |")
            }
        }

        return sb.toString()
    }
}
