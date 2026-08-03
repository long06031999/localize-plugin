package com.paulbaker.localize.core

import com.paulbaker.localize.config.AssetConfig
import org.apache.poi.ss.usermodel.*
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

class ExcelExporter(
    private val valuesDir: Path,
    private val assetsDir: Path,
    /** XML file names to export (e.g. ["strings.xml", "ds_ob_text.xml"]). */
    private val selectedXmlFiles: List<String>,
    /** Locale codes to export as columns. Empty = use all detected. */
    private val selectedLocales: List<String> = emptyList(),
    private val selectedJsonAssets: List<AssetConfig>,
    private val logger: (String) -> Unit
) {

    fun export(outputPath: Path) {
        val wb = XSSFWorkbook()
        val allLocales = collectLocales()
        // Use only the explicitly selected locales — no fallback to all
        val locales = allLocales.filter { it in selectedLocales }
        logger("Locales to export: ${if (locales.isEmpty()) "(none — english only)" else locales.joinToString()}")

        if (selectedXmlFiles.isNotEmpty()) {
            logger("Building XML Strings sheet (including plurals)...")
            buildXmlStringsSheet(wb, locales)
            logger("Building String Arrays sheet...")
            buildArraysSheet(wb, locales)
        }
        if (selectedJsonAssets.isNotEmpty()) {
            logger("Building JSON Assets sheet...")
            buildJsonSheet(wb, locales)
        }

        outputPath.parent.toFile().mkdirs()
        outputPath.toFile().outputStream().use { wb.write(it) }
        wb.close()
        logger("✅ Saved: ${outputPath.fileName}")
    }

    // ── Locale detection ──────────────────────────────────────────────────────

    private fun collectLocales(): List<String> {
        val parent = valuesDir.parent ?: return emptyList()
        return parent.toFile().listFiles { f ->
            f.isDirectory && f.name.startsWith("values-")
        }?.map { it.name.removePrefix("values-") }
            ?.filter { it.isNotEmpty() }
            ?.sorted()
            ?: emptyList()
    }

    // ── Sheet 1: XML Strings ──────────────────────────────────────────────────

    private fun buildXmlStringsSheet(wb: Workbook, locales: List<String>) {
        val sheet  = wb.createSheet("XML Strings")
        val hStyle = headerStyle(wb)
        val hr     = sheet.createRow(0)
        // Header: android_key, english, locale1, locale2, ...
        writeCell(hr, 0, "android_key", hStyle)
        writeCell(hr, 1, "english",     hStyle)
        locales.forEachIndexed { i, loc -> writeCell(hr, i + 2, loc, hStyle) }

        var rowIdx = 1
        selectedXmlFiles.forEach { fileName ->
            if (!valuesDir.resolve(fileName).exists()) return@forEach
            logger("  Scanning $fileName...")
            val tmpl = getTemplateData(fileName)

            // Strings (preserving CDATA format via cache)
            tmpl.strings.forEach { (key, english) ->
                val row = sheet.createRow(rowIdx++)
                writeCell(row, 0, key)
                writeCell(row, 1, english)
                locales.forEachIndexed { i, loc ->
                    writeCell(row, i + 2, readStringFromLocale(loc, fileName, key))
                }
            }

            // Plurals — key format: "plural_name:quantity"
            tmpl.plurals.forEach { (pluralName, quantities) ->
                quantities.forEach { (quantity, english) ->
                    val key = "$pluralName:$quantity"
                    val row = sheet.createRow(rowIdx++)
                    writeCell(row, 0, key)
                    writeCell(row, 1, english)
                    locales.forEachIndexed { i, loc ->
                        writeCell(row, i + 2, readPluralsFromLocale(loc, fileName, pluralName)[quantity] ?: "")
                    }
                }
            }
        }
        autoSizeColumns(sheet, locales.size + 2)
    }

    // ── Sheet 2: String Arrays ────────────────────────────────────────────────

    /**
     * String Arrays sheet — sparse format matching android_array_strings.csv:
     * android_key | english | locale1 | locale2 | ...
     * The array name appears only on the FIRST item of each group (sparse), subsequent items have empty android_key.
     */
    private fun buildArraysSheet(wb: Workbook, locales: List<String>) {
        val sheet  = wb.createSheet("String Arrays")
        val hStyle = headerStyle(wb)
        val hr     = sheet.createRow(0)
        // Header matches android_array_strings.csv format exactly
        writeCell(hr, 0, "android_key", hStyle)
        writeCell(hr, 1, "english",     hStyle)
        locales.forEachIndexed { i, loc -> writeCell(hr, i + 2, loc, hStyle) }

        var rowIdx = 1
        selectedXmlFiles.forEach { fileName ->
            if (!valuesDir.resolve(fileName).exists()) return@forEach
            val tmpl = getTemplateData(fileName)
            tmpl.arrays.forEach { (arrayName, items) ->
                val locArrays = locales.associateWith { readArrayFromLocale(it, fileName, arrayName) }
                items.forEachIndexed { itemIdx, english ->
                    val row = sheet.createRow(rowIdx++)
                    // Sparse format: android_key only on first item of each group
                    writeCell(row, 0, if (itemIdx == 0) arrayName else "")
                    writeCell(row, 1, english)
                    locales.forEachIndexed { i, loc ->
                        writeCell(row, i + 2, locArrays[loc]?.getOrNull(itemIdx) ?: "")
                    }
                }
            }
        }
        autoSizeColumns(sheet, locales.size + 2)
    }

    // ── Sheet 3: JSON Assets ──────────────────────────────────────────────────

    private fun buildJsonSheet(wb: Workbook, locales: List<String>) {
        val sheet  = wb.createSheet("JSON Assets")
        val header = headerStyle(wb)
        val hr     = sheet.createRow(0)
        writeCell(hr, 0, "asset",       header)
        writeCell(hr, 1, "field",       header)
        writeCell(hr, 2, "english",     header)
        locales.forEachIndexed { i, loc -> writeCell(hr, i + 3, loc, header) }

        var rowIdx = 1
        selectedJsonAssets.forEach { asset ->
            logger("  Scanning ${asset.name}...")
            val basePath = asset.dir.resolve(asset.baseFile)
            if (!basePath.exists()) return@forEach

            val baseRoot = com.google.gson.JsonParser.parseString(basePath.readText(Charsets.UTF_8))
            val fields   = asset.translateFields.toSet()

            // Collect all items from base JSON
            val baseItems = collectJsonItems(baseRoot, asset.dataKey)
            // Collect locale translations keyed by item ID
            val localeRoots = locales.associateWith { loc ->
                val locPath = asset.dir.resolve("${basePath.toFile().nameWithoutExtension}_$loc.json")
                if (locPath.exists())
                    runCatching { com.google.gson.JsonParser.parseString(locPath.readText(Charsets.UTF_8)) }.getOrNull()
                else null
            }
            val localeItems = localeRoots.mapValues { (_, root) ->
                if (root != null) collectJsonItems(root, asset.dataKey) else emptyList()
            }
            // Match locale items by their own identity, not by position — a locale file whose
            // items are ordered differently would otherwise put a neighbour's translation next
            // to the English text, and the translator would "correct" the wrong row.
            val localeByIdentity = localeItems.mapValues { (_, items) ->
                items.mapNotNull { e -> JsonLocalizer.itemIdentity(e)?.let { it to e } }.toMap()
            }

            baseItems.forEachIndexed { itemIdx, baseItem ->
                if (!baseItem.isJsonObject) return@forEachIndexed
                val baseObj  = baseItem.asJsonObject
                val identity = JsonLocalizer.itemIdentity(baseItem)
                // For each selected field
                collectFieldsRecursively(baseObj, fields, "", { fieldPath, englishVal ->
                    val row = sheet.createRow(rowIdx++)
                    writeCell(row, 0, asset.name)
                    writeCell(row, 1, fieldPath)
                    writeCell(row, 2, englishVal)
                    locales.forEachIndexed { i, loc ->
                        val locItem = if (identity != null) localeByIdentity[loc]?.get(identity)
                                      else localeItems[loc]?.getOrNull(itemIdx)
                        val locVal  = if (locItem != null && locItem.isJsonObject)
                            getNestedFieldValue(locItem.asJsonObject, fieldPath) else ""
                        writeCell(row, i + 3, locVal)
                    }
                })
            }
        }
        autoSizeColumns(sheet, locales.size + 3)
    }

    // ── Locale XML cache (pre-parse each file once, preserve CDATA format) ──────

    /** Parsed locale XML: cache[locale][xmlFile] = LocaleXmlData */
    private val xmlCache = mutableMapOf<String, MutableMap<String, LocaleXmlData>>()

    private data class LocaleXmlData(
        val strings: Map<String, String>,          // name → value
        val arrays:  Map<String, List<String>>,    // name → item list
        val plurals: Map<String, Map<String, String>> // name → {quantity → value}
    )

    /**
     * Parse a locale XML file once using regex (same approach as XmlGenerator.parseExistingXml).
     * Preserves CDATA content exactly: `<![CDATA[<b>text</b>]]>` → `<b>text</b>`
     * Plain text is returned with Android escapes intact (e.g. `\'`, `\n`, `&amp;`).
     */
    private fun getLocaleData(locale: String, xmlFileName: String): LocaleXmlData {
        return xmlCache.getOrPut(locale) { mutableMapOf() }.getOrPut(xmlFileName) {
            val locFile = valuesDir.parent?.resolve("values-$locale/$xmlFileName") ?: return@getOrPut emptyLocaleData()
            if (!locFile.exists()) return@getOrPut emptyLocaleData()
            parseLocaleXml(locFile)
        }
    }

    private fun parseLocaleXml(path: java.nio.file.Path): LocaleXmlData {
        return runCatching {
            val text     = path.readText(Charsets.UTF_8)
            val strings  = mutableMapOf<String, String>()
            val arrays   = mutableMapOf<String, List<String>>()
            val plurals  = mutableMapOf<String, Map<String, String>>()
            val cdataRe  = Regex("""^\s*<!\[CDATA\[(.*?)]\]>\s*$""", RegexOption.DOT_MATCHES_ALL)

            // Parse <string> elements. translatable="false" is skipped: those values are
            // infrastructure, not copy — putting them in the workbook only invites a
            // translator to spend effort on strings that must never change.
            Regex("""<string\s+name="([^"]+)"([^>]*)>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
                .findAll(text).forEach { m ->
                    if (NON_TRANSLATABLE.containsMatchIn(m.groupValues[2])) return@forEach
                    val content = m.groupValues[3]
                    strings[m.groupValues[1]] = cdataRe.find(content)?.groupValues?.get(1) ?: content
                }

            // Parse <string-array> elements
            Regex("""<string-array\s+name="([^"]+)"([^>]*)>(.*?)</string-array>""", RegexOption.DOT_MATCHES_ALL)
                .findAll(text).forEach { m ->
                    if (NON_TRANSLATABLE.containsMatchIn(m.groupValues[2])) return@forEach
                    val items = mutableListOf<String>()
                    Regex("""<item[^>]*>(.*?)</item>""", RegexOption.DOT_MATCHES_ALL)
                        .findAll(m.groupValues[3]).forEach { im ->
                            val c = im.groupValues[1]
                            items += cdataRe.find(c)?.groupValues?.get(1) ?: c
                        }
                    arrays[m.groupValues[1]] = items
                }

            // Parse <plurals> elements
            Regex("""<plurals\s+name="([^"]+)"([^>]*)>(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL)
                .findAll(text).forEach { m ->
                    if (NON_TRANSLATABLE.containsMatchIn(m.groupValues[2])) return@forEach
                    val qMap = mutableMapOf<String, String>()
                    Regex("""<item\s+quantity="([^"]+)"[^>]*>(.*?)</item>""", RegexOption.DOT_MATCHES_ALL)
                        .findAll(m.groupValues[3]).forEach { im ->
                            val c = im.groupValues[2]
                            qMap[im.groupValues[1]] = cdataRe.find(c)?.groupValues?.get(1) ?: c
                        }
                    plurals[m.groupValues[1]] = qMap
                }

            LocaleXmlData(strings, arrays, plurals)
        }.getOrElse { emptyLocaleData() }
    }

    /** Also parse the ENGLISH template using the same method (preserves CDATA). */
    private val templateCache = mutableMapOf<String, LocaleXmlData>()

    private fun getTemplateData(xmlFileName: String): LocaleXmlData {
        return templateCache.getOrPut(xmlFileName) {
            val file = valuesDir.resolve(xmlFileName)
            if (!file.exists()) emptyLocaleData() else parseLocaleXml(file)
        }
    }

    private fun emptyLocaleData() = LocaleXmlData(emptyMap(), emptyMap(), emptyMap())

    private fun readStringFromLocale(locale: String, xmlFileName: String, key: String) =
        getLocaleData(locale, xmlFileName).strings[key] ?: ""

    private fun readArrayFromLocale(locale: String, xmlFileName: String, arrayName: String) =
        getLocaleData(locale, xmlFileName).arrays[arrayName] ?: emptyList()

    private fun readPluralsFromLocale(locale: String, xmlFileName: String, pluralName: String) =
        getLocaleData(locale, xmlFileName).plurals[pluralName] ?: emptyMap()

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun collectJsonItems(root: com.google.gson.JsonElement, dataKey: String?): List<com.google.gson.JsonElement> {
        return when {
            root.isJsonArray -> root.asJsonArray.toList()
            root.isJsonObject && dataKey != null ->
                root.asJsonObject.getAsJsonArray(dataKey)?.toList() ?: emptyList()
            else -> emptyList()
        }
    }

    /**
     * Emits the DOTTED path of every selected field, not the bare key name — [getNestedFieldValue]
     * resolves the locale value by walking that path, so a nested field emitted as `"title"`
     * instead of `"header.title"` would always come back empty.
     */
    private fun collectFieldsRecursively(
        obj: com.google.gson.JsonObject,
        fields: Set<String>,
        prefix: String,
        emit: (fieldPath: String, value: String) -> Unit
    ) {
        obj.keySet().forEach { key ->
            val child = obj.get(key)
            val path  = if (prefix.isEmpty()) key else "$prefix.$key"
            when {
                key in fields && child.isJsonPrimitive && child.asJsonPrimitive.isString ->
                    emit(path, child.asString)
                child.isJsonObject ->
                    collectFieldsRecursively(child.asJsonObject, fields, path, emit)
            }
        }
    }

    private fun getNestedFieldValue(obj: com.google.gson.JsonObject, fieldPath: String): String {
        val parts   = fieldPath.split(".")
        var current: com.google.gson.JsonElement = obj
        for (part in parts) {
            if (!current.isJsonObject) return ""
            current = current.asJsonObject.get(part) ?: return ""
        }
        return if (current.isJsonPrimitive && current.asJsonPrimitive.isString) current.asString else ""
    }

    // ── Styling ───────────────────────────────────────────────────────────────

    private fun headerStyle(wb: Workbook): CellStyle = wb.createCellStyle().apply {
        val font = wb.createFont().also { it.bold = true }
        setFont(font)
        fillForegroundColor = IndexedColors.GREY_25_PERCENT.index
        fillPattern         = FillPatternType.SOLID_FOREGROUND
        borderBottom        = BorderStyle.THIN
    }

    private fun writeCell(row: Row, col: Int, value: String, style: CellStyle? = null) {
        val cell = row.createCell(col)
        cell.setCellValue(value)
        if (style != null) cell.cellStyle = style
    }

    private fun autoSizeColumns(sheet: Sheet, count: Int) {
        for (i in 0 until count) {
            try { sheet.autoSizeColumn(i) } catch (_: Exception) {}
        }
    }

    private companion object {
        /** Matches `translatable="false"` on any resource element, however it is spaced or cased. */
        val NON_TRANSLATABLE = Regex("""translatable\s*=\s*"\s*false\s*"""", RegexOption.IGNORE_CASE)
    }
}
