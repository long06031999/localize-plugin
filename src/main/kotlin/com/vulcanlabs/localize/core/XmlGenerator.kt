package com.vulcanlabs.localize.core

import com.vulcanlabs.localize.config.AssetConfig
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class XmlGenerator(private val db: TranslationDb) {

    data class XmlResult(
        val written: Int,
        val skipped: Int,
        val addedKeys: List<String>,
        val changedKeys: List<String>,
        val skippedArrays: List<SkippedArray>,
        val notFoundKeys: List<NotFound>
    )

    data class SkippedArray(val name: String, val found: Int, val total: Int, val missing: List<String>)
    data class NotFound(val key: String, val enText: String)

    // Keys whose source values are wrapped in CDATA
    private val cdataKeys = mutableSetOf<String>()

    // Selected assets — used to dynamically detect JSON path strings in strings.xml
    private var selectedAssets: List<AssetConfig> = emptyList()

    fun setSelectedAssets(assets: List<AssetConfig>) {
        selectedAssets = assets
    }

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
        if (!templatePath.exists()) return XmlResult(0, 0, emptyList(), emptyList(), emptyList(), emptyList())

        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }
        val doc = factory.newDocumentBuilder().parse(templatePath.toFile())

        val oldValues = parseExistingXml(outputPath)
        val lines = mutableListOf("<?xml version=\"1.0\" encoding=\"utf-8\"?>", "<resources>")
        val newValues = mutableMapOf<String, String>()
        val skippedArrays = mutableListOf<SkippedArray>()
        val notFound = mutableListOf<NotFound>()
        var written = 0
        var skipped = 0

        val root = doc.documentElement
        var child = root.firstChild
        while (child != null) {
            if (child.nodeType == org.w3c.dom.Node.ELEMENT_NODE) {
                val elem = child as org.w3c.dom.Element
                when (elem.tagName) {
                    "string" -> {
                        val (w, s) = processString(elem, locale, suffix, lines, newValues, notFound)
                        written += w; skipped += s
                    }
                    "string-array" -> {
                        val (w, s, sa) = processStringArray(elem, locale, lines)
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

        if (written == 0) return XmlResult(0, skipped, emptyList(), emptyList(), skippedArrays, notFound)

        lines += "</resources>"
        outputPath.parent.toFile().mkdirs()
        outputPath.writeText(lines.joinToString("\n") + "\n", Charsets.UTF_8)

        val added   = newValues.keys.filter { it !in oldValues }
        val changed = newValues.keys.filter { it in oldValues && oldValues[it] != newValues[it] }
        return XmlResult(written, skipped, added, changed, skippedArrays, notFound)
    }

    private fun processString(
        elem: org.w3c.dom.Element,
        locale: String,
        suffix: String,
        lines: MutableList<String>,
        newValues: MutableMap<String, String>,
        notFound: MutableList<NotFound>
    ): Pair<Int, Int> {
        if (elem.getAttribute("translatable") == "false") return 0 to 0
        val name = elem.getAttribute("name")

        // Dynamic JSON path detection: if this string's value matches a selected asset's base path,
        // generate the localized path instead of looking up in CSV.
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
            return 1 to 0
        }

        val enFromDb = db.byKey[db.normKey(name)]?.english ?: ""
        val enFromElem = elem.textContent?.trim() ?: ""
        val enText = enFromDb.ifEmpty { enFromElem }

        val translation = db.lookup(name, enText, locale)
        if (translation.isNullOrBlank()) {
            notFound += NotFound(name, enText)
            return 0 to 1
        }

        val valStr = if (name in cdataKeys || '<' in translation)
            "<![CDATA[$translation]]>"
        else
            escapeXmlValue(translation)

        lines += """    <string name="$name">$valStr</string>"""
        newValues[name] = translation
        return 1 to 0
    }

    private data class ArrayResult(val written: Int, val skipped: Int, val skippedArray: SkippedArray?)

    private fun processStringArray(
        elem: org.w3c.dom.Element,
        locale: String,
        lines: MutableList<String>
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

    private fun parseExistingXml(path: Path): Map<String, String> {
        if (!path.exists()) return emptyMap()
        val regex = Regex("""<string\s+name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
        val cdataRe = Regex("""^\s*<!\[CDATA\[(.*)\]\]>\s*$""", RegexOption.DOT_MATCHES_ALL)
        return regex.findAll(path.readText()).associate { m ->
            val content = m.groupValues[2].trim()
            val value = cdataRe.find(content)?.groupValues?.get(1)?.trim() ?: content
            m.groupValues[1] to value
        }
    }

    companion object {
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
