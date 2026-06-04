package com.paulbaker.localize.core

import com.paulbaker.localize.config.AssetConfig
import com.paulbaker.localize.config.GenerateMode
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class XmlGenerator(private val db: TranslationDb) {

    data class XmlResult(
        val written: Int,
        val skipped: Int,
        val preserved: Int,          // Merge mode: keys kept from existing file
        val addedKeys: List<String>,
        val changedKeys: List<String>,
        val skippedArrays: List<SkippedArray>,
        val notFoundKeys: List<NotFound>
    )

    data class SkippedArray(val name: String, val found: Int, val total: Int, val missing: List<String>)
    data class NotFound(val key: String, val enText: String)

    // Keys whose source values are wrapped in CDATA
    private val cdataKeys = mutableSetOf<String>()

    private var selectedAssets: List<AssetConfig> = emptyList()
    private var generateMode: GenerateMode = GenerateMode.MERGE

    fun setSelectedAssets(assets: List<AssetConfig>) { selectedAssets = assets }
    fun setGenerateMode(mode: GenerateMode) { generateMode = mode }

    fun scanCdataKeys(valuesDir: Path) {
        val regex = Regex("""<string\s+name="([^"]+)"[^>]*>\s*<!\[CDATA\[""")
        valuesDir.toFile().listFiles { f -> f.extension == "xml" }?.forEach { f ->
            regex.findAll(f.readText()).forEach { cdataKeys += it.groupValues[1] }
        }
    }

    fun generate(
        templatePath: Path,
        outputPath: Path,
        locale: String,
        suffix: String
    ): XmlResult {
        if (!templatePath.exists()) return XmlResult(0, 0, 0, emptyList(), emptyList(), emptyList(), emptyList())

        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }
        val doc = factory.newDocumentBuilder().parse(templatePath.toFile())

        // Merge mode: read existing output file as fallback for missing translations
        val existingValues = if (generateMode == GenerateMode.MERGE) parseExistingXml(outputPath) else emptyMap()
        val oldValues      = parseExistingXml(outputPath)   // used for diff reporting regardless of mode

        val lines = mutableListOf("<?xml version=\"1.0\" encoding=\"utf-8\"?>", "<resources>")
        val newValues = mutableMapOf<String, String>()
        val skippedArrays = mutableListOf<SkippedArray>()
        val notFound = mutableListOf<NotFound>()
        var written    = 0
        var skipped    = 0
        var preserved  = 0

        val root = doc.documentElement
        var child = root.firstChild
        while (child != null) {
            if (child.nodeType == org.w3c.dom.Node.ELEMENT_NODE) {
                val elem = child as org.w3c.dom.Element
                when (elem.tagName) {
                    "string" -> {
                        val (w, s, p) = processString(elem, locale, suffix, lines, newValues, notFound, existingValues)
                        written += w; skipped += s; preserved += p
                    }
                    "string-array" -> {
                        val (w, s, sa) = processStringArray(elem, locale, lines, existingValues)
                        written += w; skipped += s
                        sa?.let { skippedArrays += it }
                    }
                    "plurals" -> {
                        val (w, s, sa) = processPlurals(elem, locale, lines)
                        written += w; skipped += s
                        sa?.let { skippedArrays += it }
                    }
                }
            }
            child = child.nextSibling
        }

        // Merge: skip writing if nothing was found (avoid creating empty files on first run)
        // Full Replace: always write to overwrite existing file with only what CSV provides
        if (written + preserved == 0 && generateMode == GenerateMode.MERGE) {
            return XmlResult(0, skipped, 0, emptyList(), emptyList(), skippedArrays, notFound)
        }

        lines += "</resources>"
        outputPath.parent.toFile().mkdirs()
        outputPath.writeText(lines.joinToString("\n") + "\n", Charsets.UTF_8)

        val added   = newValues.keys.filter { it !in oldValues }
        val changed = newValues.keys.filter { it in oldValues && oldValues[it] != newValues[it] }
        return XmlResult(written, skipped, preserved, added, changed, skippedArrays, notFound)
    }

    // Returns Triple(written, skipped, preserved)
    private fun processString(
        elem: org.w3c.dom.Element,
        locale: String,
        suffix: String,
        lines: MutableList<String>,
        newValues: MutableMap<String, String>,
        notFound: MutableList<NotFound>,
        existingValues: Map<String, String> = emptyMap()
    ): Triple<Int, Int, Int> {
        if (elem.getAttribute("translatable") == "false") return Triple(0, 0, 0)
        val name = elem.getAttribute("name")

        // Dynamic JSON path detection: asset IS selected → generate localized path
        val elemValue = elem.textContent?.trim() ?: ""
        val matchedAsset = selectedAssets.firstOrNull { asset ->
            val dirName = asset.dir.fileName.toString()
            elemValue == "$dirName/${asset.baseFile}" ||
            elemValue.endsWith("/$dirName/${asset.baseFile}")
        }
        if (matchedAsset != null) {
            val dirName = matchedAsset.dir.fileName.toString()
            val stem    = matchedAsset.baseFile.substringBeforeLast(".")
            val localizedPath = "$dirName/${stem}_$suffix.json"
            lines += """    <string name="$name">$localizedPath</string>"""
            newValues[name] = localizedPath
            return Triple(1, 0, 0)
        }

        val enFromDb   = db.byKey[db.normKey(name)]?.english ?: ""
        val enFromElem = elemValue
        val enText     = enFromDb.ifEmpty { enFromElem }

        // JSON path strings (e.g. "task/tasks.json") must NEVER be taken from the CSV —
        // the CSV may contain the non-localized base path which would override the Merge fallback.
        // Only the dynamic asset detection above (or existing file) should determine their value.
        val isJsonPathString = JSON_PATH_PATTERN.matches(elemValue)

        if (!isJsonPathString) {
            val translation = db.lookup(name, enText, locale)
            if (!translation.isNullOrBlank()) {
                val valStr = if (name in cdataKeys || '<' in translation)
                    "<![CDATA[$translation]]>"
                else
                    escapeXmlValue(translation)
                lines += """    <string name="$name">$valStr</string>"""
                newValues[name] = translation
                return Triple(1, 0, 0)
            }
        }

        // Merge mode: fall back to existing translation from previous run
        val existing = existingValues[name]
        if (!existing.isNullOrBlank()) {
            val valStr = if (name in cdataKeys || '<' in existing)
                "<![CDATA[$existing]]>"
            else
                escapeXmlValue(existing)
            lines += """    <string name="$name">$valStr</string>"""
            return Triple(0, 0, 1)   // preserved
        }

        // JSON path strings must NEVER be skipped — they are infrastructure strings.
        // If no existing value (Full Replace / first run), generate the localized path
        // directly from the base path pattern: "task/tasks.json" → "task/tasks_{suffix}.json"
        if (isJsonPathString) {
            val localizedPath = buildLocalizedJsonPath(elemValue, suffix)
            val target = localizedPath ?: elemValue   // fallback to base path if pattern unrecognized
            lines += """    <string name="$name">$target</string>"""
            newValues[name] = target
            return Triple(1, 0, 0)
        }

        notFound += NotFound(name, enText)
        return Triple(0, 1, 0)
    }

    private data class ArrayResult(val written: Int, val skipped: Int, val skippedArray: SkippedArray?)

    private fun processStringArray(
        elem: org.w3c.dom.Element,
        locale: String,
        lines: MutableList<String>,
        existingValues: Map<String, String> = emptyMap()
    ): ArrayResult {
        if (elem.getAttribute("translatable") == "false") return ArrayResult(0, 0, null)
        val arrayName = elem.getAttribute("name")
        val okItems = mutableListOf<Pair<String?, String>>() // name? to translated value
        val missing = mutableListOf<String>()

        var item = elem.firstChild
        var totalItems = 0
        while (item != null) {
            if (item.nodeType == org.w3c.dom.Node.ELEMENT_NODE) {
                totalItems++
                val itemElem = item as org.w3c.dom.Element
                val itemName = itemElem.getAttribute("name").takeIf { it.isNotEmpty() && it != "null" }
                val enFromElem = itemElem.textContent?.trim() ?: ""
                val enFromDb = itemName?.let { db.byKey[db.normKey(it)]?.english } ?: ""
                val enText = enFromDb.ifEmpty { enFromElem }

                // 1. Key-only lookup (no byEn fallback — avoids false matches from other contexts)
                var tr = itemName?.let { db.byKey[db.normKey(it)]?.tr?.get(locale) }

                // 2. Array-specific CSV by English text from template element
                if (tr == null) {
                    tr = db.lookupArray(arrayName, enFromElem, locale)
                    if (tr == null && enFromDb.isNotEmpty())
                        tr = db.lookupArray(arrayName, enFromDb, locale)
                }

                if (tr != null) {
                    okItems += itemName to formatXmlValue(tr)
                } else {
                    missing += itemName ?: "(unnamed)"
                }
            }
            item = item.nextSibling
        }

        if (missing.isNotEmpty()) {
            // Merge mode: if the existing output file has this full array, preserve it
            val existingBlock = existingValues["__array__$arrayName"]
            if (existingBlock != null) {
                // trimStart() in parseExistingXml stripped the leading 4-space indent — restore it
                lines += "    $existingBlock"
                return ArrayResult(1, 0, null)
            }
            val sa = SkippedArray(arrayName, okItems.size, totalItems, missing)
            return ArrayResult(0, 1, sa)
        }

        lines += """    <string-array name="$arrayName">"""
        okItems.forEach { (name, v) ->
            // trim() removes trailing spaces that may exist in XML attribute values
            lines += if (name != null) """        <item name="${name.trim()}">$v</item>"""
                     else              """        <item>$v</item>"""
        }
        lines += "    </string-array>"
        return ArrayResult(1, 0, null)
    }

    private fun processPlurals(
        elem: org.w3c.dom.Element,
        locale: String,
        lines: MutableList<String>
    ): ArrayResult {
        if (elem.getAttribute("translatable") == "false") return ArrayResult(0, 0, null)
        val name = elem.getAttribute("name")
        val okItems = mutableListOf<Pair<String, String>>() // quantity to value
        val missing = mutableListOf<String>()

        var item = elem.firstChild
        var total = 0
        while (item != null) {
            if (item.nodeType == org.w3c.dom.Node.ELEMENT_NODE) {
                total++
                val itemElem = item as org.w3c.dom.Element
                val quantity = itemElem.getAttribute("quantity")
                val enText = itemElem.textContent?.trim() ?: ""
                if (enText.isEmpty() || enText.startsWith("@string/")) {
                    missing += "quantity=$quantity"
                } else {
                    val tr = db.lookup("", enText, locale)
                    if (tr != null) okItems += quantity to formatXmlValue(tr)
                    else missing += "quantity=$quantity: \"$enText\""
                }
            }
            item = item.nextSibling
        }

        if (missing.isNotEmpty()) return ArrayResult(0, 1, SkippedArray("plurals:$name", okItems.size, total, missing))

        lines += """    <plurals name="$name">"""
        okItems.forEach { (qty, v) -> lines += """        <item quantity="$qty">$v</item>""" }
        lines += "    </plurals>"
        return ArrayResult(1, 0, null)
    }

    /**
     * Parse an existing localized strings.xml into a flat map.
     * Keys:
     *   - Normal string:  name → value
     *   - String-array:   "__array__{name}" → full XML block (for Merge fallback)
     */
    private fun parseExistingXml(path: Path): Map<String, String> {
        if (!path.exists()) return emptyMap()
        val text = path.readText()
        val result = mutableMapOf<String, String>()

        // Parse <string> elements
        val stringRe = Regex("""<string\s+name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
        val cdataRe  = Regex("""^\s*<!\[CDATA\[(.*)\]\]>\s*$""", RegexOption.DOT_MATCHES_ALL)
        stringRe.findAll(text).forEach { m ->
            val content = m.groupValues[2].trim()
            result[m.groupValues[1]] = cdataRe.find(content)?.groupValues?.get(1)?.trim() ?: content
        }

        // Parse <string-array> blocks — stored with "__array__" prefix for Merge fallback
        val arrayRe = Regex("""([ \t]*<string-array\s+name="([^"]+)"[^>]*>.*?</string-array>)""", RegexOption.DOT_MATCHES_ALL)
        arrayRe.findAll(text).forEach { m ->
            result["__array__${m.groupValues[2]}"] = m.groupValues[1].trimStart()
        }

        return result
    }

    companion object {
        // Matches JSON asset path strings like "task/tasks.json", "whats_new/whats_new.json"
        // These must never be sourced from the CSV — only from dynamic asset detection or Merge fallback
        private val JSON_PATH_PATTERN = Regex("""^[\w][\w_-]*/[\w][\w_-]*\.json$""")

        /** "task/tasks.json" + suffix "th" → "task/tasks_th.json". Returns null if pattern not recognized. */
        fun buildLocalizedJsonPath(basePath: String, suffix: String): String? {
            val slash = basePath.indexOf('/')
            if (slash < 0) return null
            val dir  = basePath.substring(0, slash)
            val file = basePath.substring(slash + 1)
            if (!file.endsWith(".json")) return null
            val stem = file.dropLast(5)
            return "$dir/${stem}_$suffix.json"
        }

        fun escapeXmlValue(s: String): String {
            var r = s
            r = r.replace(Regex("&(?!amp;|lt;|gt;|apos;|quot;|#)"), "&amp;")
            r = r.replace(Regex("(?<!\\\\)'"), "\\'")
            r = r.replace("\r\n", "\\n").replace("\r", "").replace("\n", "\\n")
            return r
        }

        fun formatXmlValue(s: String): String =
            if ('<' in s) "<![CDATA[$s]]>" else escapeXmlValue(s)
    }
}
