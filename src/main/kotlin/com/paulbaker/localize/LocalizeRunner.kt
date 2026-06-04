package com.paulbaker.localize

import com.paulbaker.localize.config.LocalizeConfig
import com.paulbaker.localize.core.CsvPreprocessor
import com.paulbaker.localize.core.JsonLocalizer
import com.paulbaker.localize.core.TranslationDb
import com.paulbaker.localize.core.XmlGenerator
import com.paulbaker.localize.ui.OutputLevel
import java.nio.file.Path
import kotlin.io.path.writeText

class LocalizeRunner {

    fun run(config: LocalizeConfig, logger: (String, OutputLevel) -> Unit) {
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
        config.csvArrays?.let {
            db.loadArrayCsv(it, langMap)
            logger("  ✓ arrays: loaded (${db.byArray.size} arrays)", OutputLevel.INFO)
        }
        logger("  DB: ${db.byKey.size} unique keys, ${db.byEn.size} English phrases", OutputLevel.INFO)
        if (db.conflicts.isNotEmpty())
            logger("  ⚠ ${db.conflicts.size} CSV conflicts (android_only takes priority)", OutputLevel.WARN)

        val valuesDir = config.resolvedValuesDir()
        val xmlGen = XmlGenerator(db)
        xmlGen.scanCdataKeys(valuesDir)
        // Pass selected assets so XmlGenerator can dynamically detect JSON path strings
        xmlGen.setSelectedAssets(config.selectedAssets)
        xmlGen.setGenerateMode(config.generateMode)
        val jsonLoc = JsonLocalizer(db)
        logger("  Mode: ${config.generateMode.name.lowercase().replace('_', ' ')}", OutputLevel.INFO)

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
                }
            }

            // JSON assets
            if (config.selectedAssets.isNotEmpty()) {
                logger("Phase 3: Generating JSON for $locale...", OutputLevel.INFO)
                config.selectedAssets.forEach { asset ->
                    val result = jsonLoc.localize(asset, locale, locale, config.generateMode)
                    logger("  [$locale] ${asset.baseFile.substringBeforeLast(".")}_$locale.json", OutputLevel.INFO)
                    report.jsonWritten++
                    report.jsonUnmatched += result.unmatched
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
    var jsonWritten  = 0
    val jsonUnmatched = mutableListOf<JsonLocalizer.UnmatchedField>()

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
        sb.appendLine("| CSV conflicts | ${conflicts.size} |")
        sb.appendLine("| Skipped string-arrays | ${skippedArrays.size} |")
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
