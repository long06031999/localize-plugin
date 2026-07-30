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
        val preserved: Int,          // Merge mode: values kept from the existing locale file
        val addedKeys: List<String>,
        val changedKeys: List<String>,
        val skippedArrays: List<SkippedArray>,
        val notFoundKeys: List<NotFound>,
        /** `translatable="false"` elements that the source overrode and that were written. */
        val ntOverridden: List<NonTranslatable> = emptyList(),
        /** `translatable="false"` elements left out of the locale file. */
        val ntSkipped: List<NonTranslatable> = emptyList()
    )

    data class SkippedArray(val name: String, val found: Int, val total: Int, val missing: List<String>)
    data class NotFound(val key: String, val enText: String)

    /** A `translatable="false"` element and what happened to it. [kind] is the tag name. */
    data class NonTranslatable(
        val key: String,
        val kind: String,
        val enText: String,
        val translation: String? = null
    )

    // Keys whose source values are wrapped in CDATA
    private val cdataKeys = mutableSetOf<String>()

    private var selectedAssets: List<AssetConfig> = emptyList()
    private var generateMode: GenerateMode = GenerateMode.MERGE
    private var preserveKeyOrder: Boolean = true
    private var overrideNonTranslatable: Boolean = false

    // Per-generate() accumulators for the two non-translatable report sections
    private val ntOverridden = mutableListOf<NonTranslatable>()
    private val ntSkipped    = mutableListOf<NonTranslatable>()

    fun setSelectedAssets(assets: List<AssetConfig>) { selectedAssets = assets }
    fun setGenerateMode(mode: GenerateMode) { generateMode = mode }

    /**
     * true  → every key that already exists in the locale file keeps its current position,
     *         new keys are inserted next to their template neighbour. Produces reviewable diffs.
     * false → output always follows the template (`values/`) order, as before.
     */
    fun setPreserveKeyOrder(enabled: Boolean) { preserveKeyOrder = enabled }

    /**
     * false → `translatable="false"` elements are always left out of the locale file.
     * true  → they are written **when the source supplies a translation**, and only then.
     *
     * The override is deliberately source-driven only: no Merge fallback, no value carried over
     * from a previous run. `translatable="false"` states that the template owns the value, so
     * overriding it has to be an explicit, traceable decision coming from the spreadsheet —
     * resurrecting a stale value would defeat the attribute and make the report lie.
     */
    fun setOverrideNonTranslatable(enabled: Boolean) { overrideNonTranslatable = enabled }

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

        ntOverridden.clear(); ntSkipped.clear()

        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }
        val doc = factory.newDocumentBuilder().parse(templatePath.toFile())

        // Parsed once, used for three things: the Merge fallback, order preservation, diff reporting.
        val existing = parseExistingXml(outputPath)
        val fallback = if (generateMode == GenerateMode.MERGE) existing else ExistingXml.EMPTY

        // Rendered output per element, keyed by "<type>:<name>". Insertion order = template order.
        val blocks = LinkedHashMap<String, MutableList<String>>()
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
                val name = elem.getAttribute("name")
                val buf  = mutableListOf<String>()
                var blockKey: String? = null
                when (elem.tagName) {
                    "string" -> {
                        blockKey = "string:$name"
                        val (w, s, p) = processString(elem, locale, suffix, buf, newValues, notFound, fallback.strings)
                        written += w; skipped += s; preserved += p
                    }
                    "string-array" -> {
                        blockKey = "array:$name"
                        val r = processStringArray(elem, locale, buf, fallback)
                        written += r.written; skipped += r.skipped; preserved += r.preserved
                        r.skippedArray?.let { skippedArrays += it }
                    }
                    "plurals" -> {
                        blockKey = "plurals:$name"
                        val r = processPlurals(elem, locale, buf, fallback)
                        written += r.written; skipped += r.skipped; preserved += r.preserved
                        r.skippedArray?.let { skippedArrays += it }
                    }
                }
                if (blockKey != null && buf.isNotEmpty())
                    blocks.getOrPut(blockKey) { mutableListOf() } += buf
            }
            child = child.nextSibling
        }

        // Merge: skip writing if nothing was found (avoid creating empty files on first run)
        // Full Replace: always write to overwrite existing file with only what CSV provides
        if (written + preserved == 0 && generateMode == GenerateMode.MERGE) {
            return XmlResult(0, skipped, 0, emptyList(), emptyList(), skippedArrays, notFound,
                             ntOverridden.toList(), ntSkipped.toList())
        }

        val templateOrder = blocks.keys.toList()
        val orderedKeys   = if (preserveKeyOrder) resolveOrder(existing.order, templateOrder) else templateOrder

        val lines = mutableListOf("<?xml version=\"1.0\" encoding=\"utf-8\"?>", "<resources>")
        orderedKeys.forEach { key -> blocks[key]?.let { lines += it } }
        lines += "</resources>"

        outputPath.parent.toFile().mkdirs()
        outputPath.writeText(lines.joinToString("\n") + "\n", Charsets.UTF_8)

        val oldValues = existing.strings
        val added   = newValues.keys.filter { it !in oldValues }
        val changed = newValues.keys.filter { it in oldValues && oldValues[it] != newValues[it] }
        return XmlResult(written, skipped, preserved, added, changed, skippedArrays, notFound,
                         ntOverridden.toList(), ntSkipped.toList())
    }

    /**
     * Merge the order of the existing locale file with the template order.
     *
     * Keys already present in the locale file stay exactly where they are — regenerating a
     * locale therefore produces no move-noise in the diff. A key that is new to this locale
     * is inserted directly after its nearest already-placed predecessor in the template, so
     * related keys stay grouped instead of piling up at the bottom of the file.
     */
    private fun resolveOrder(existingOrder: List<String>, templateOrder: List<String>): List<String> {
        if (existingOrder.isEmpty()) return templateOrder

        val templateSet = templateOrder.toHashSet()
        val placed      = existingOrder.filter { it in templateSet }.distinct().toMutableList()
        val placedSet   = placed.toHashSet()

        templateOrder.forEachIndexed { i, key ->
            if (key in placedSet) return@forEachIndexed
            // Nearest preceding template sibling that already has a position
            var anchorIdx = -1
            for (j in i - 1 downTo 0) {
                val idx = placed.indexOf(templateOrder[j])
                if (idx >= 0) { anchorIdx = idx; break }
            }
            placed.add(anchorIdx + 1, key)   // no anchor → head of file
            placedSet += key
        }
        return placed
    }

    /**
     * `translatable="false"` marks a value the template owns outright — it is never localized
     * and never written to a locale file, so the element is dropped from the output entirely.
     */
    private fun isNonTranslatable(elem: org.w3c.dom.Element): Boolean =
        elem.getAttribute("translatable").trim().equals("false", ignoreCase = true)

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
        val name = elem.getAttribute("name")
        val elemValue = elem.textContent?.trim() ?: ""

        if (isNonTranslatable(elem)) {
            if (!overrideNonTranslatable) {
                ntSkipped += NonTranslatable(name, "string", elemValue)
                return Triple(0, 0, 0)
            }
            // Source value only — no asset path rewriting, no Merge fallback.
            val enText = db.byKey[db.normKey(name)]?.english?.ifEmpty { elemValue } ?: elemValue
            val translation = db.lookup(name, enText, locale)
            if (translation.isNullOrBlank()) {
                ntSkipped += NonTranslatable(name, "string", elemValue)
                return Triple(0, 0, 0)
            }
            val valStr = if (name in cdataKeys || '<' in translation)
                "<![CDATA[$translation]]>" else escapeXmlValue(translation)
            lines += """    <string name="$name">$valStr</string>"""
            newValues[name] = translation
            ntOverridden += NonTranslatable(name, "string", elemValue, translation)
            return Triple(1, 0, 0)
        }

        // Dynamic JSON path detection: asset IS selected → generate localized path
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

    private data class ArrayResult(
        val written: Int,
        val skipped: Int,
        val skippedArray: SkippedArray?,
        val preserved: Int = 0
    )

    private fun processStringArray(
        elem: org.w3c.dom.Element,
        locale: String,
        lines: MutableList<String>,
        fallback: ExistingXml
    ): ArrayResult {
        val arrayName = elem.getAttribute("name")
        val nonTranslatable = isNonTranslatable(elem)
        if (nonTranslatable && !overrideNonTranslatable) {
            ntSkipped += NonTranslatable(arrayName, "string-array", "")
            return ArrayResult(0, 0, null)
        }
        // Overriding is source-driven: drop the Merge fallback so no item can be carried over.
        @Suppress("NAME_SHADOWING")
        val fallback = if (nonTranslatable) ExistingXml.EMPTY else fallback

        val existingItems  = fallback.arrayItems[arrayName] ?: emptyList()
        val existingByName = existingItems.mapNotNull { item -> item.name?.let { it to item } }.toMap()

        val okItems = mutableListOf<Pair<String?, String>>() // name? to encoded value
        val missing = mutableListOf<String>()
        var translatedCount = 0
        var preservedCount  = 0

        var item = elem.firstChild
        var totalItems = 0
        var itemIdx    = -1
        while (item != null) {
            if (item.nodeType == org.w3c.dom.Node.ELEMENT_NODE) {
                totalItems++; itemIdx++
                val itemElem   = item as org.w3c.dom.Element
                val itemName   = itemElem.getAttribute("name").takeIf { it.isNotEmpty() && it != "null" }
                val enFromElem = itemElem.textContent?.trim() ?: ""

                val tr = lookupArrayItem(arrayName, itemName, enFromElem, locale)

                when {
                    tr != null -> { okItems += itemName to formatXmlValue(tr); translatedCount++ }
                    else -> {
                        // Merge: keep the item that is already in the locale file instead of
                        // dropping the whole array. Named items are matched by name; unnamed
                        // ones by position (matching a named item by position could pick the
                        // wrong translation once the template gains or loses an entry).
                        val prev = if (itemName != null) existingByName[itemName]
                                   else existingItems.getOrNull(itemIdx)
                        if (generateMode == GenerateMode.MERGE && prev != null && prev.raw.isNotBlank()) {
                            okItems += itemName to prev.raw
                            preservedCount++
                        } else {
                            missing += itemName ?: "(unnamed)"
                        }
                    }
                }
            }
            item = item.nextSibling
        }

        if (missing.isNotEmpty()) {
            // Merge mode: if the existing output file has this full array, preserve it verbatim
            val existingBlock = fallback.arrayBlocks[arrayName]
            if (existingBlock != null) {
                // trimStart() in parseExistingXml stripped the leading 4-space indent — restore it
                lines += "    $existingBlock"
                return ArrayResult(1, 0, null)
            }
            if (nonTranslatable) {
                ntSkipped += NonTranslatable(arrayName, "string-array", "")
                return ArrayResult(0, 0, null)
            }
            return ArrayResult(0, 1, SkippedArray(arrayName, translatedCount, totalItems, missing))
        }

        lines += """    <string-array name="$arrayName">"""
        okItems.forEach { (name, v) ->
            // trim() removes trailing spaces that may exist in XML attribute values
            lines += if (name != null) """        <item name="${name.trim()}">$v</item>"""
                     else              """        <item>$v</item>"""
        }
        lines += "    </string-array>"
        if (nonTranslatable)
            ntOverridden += NonTranslatable(arrayName, "string-array", "", "$totalItems items")
        return ArrayResult(1, 0, null, preservedCount)
    }

    /**
     * Resolve one `<item>` of a `<string-array>`.
     *
     * An item's `name` attribute is **array-local**: `formal` exists in both `writing_tag_array`
     * ("Formal") and `email_writing_tone` ("😊 Formal"), and often collides with a real
     * `<string>` key too. Looking it up in the global key map first therefore hands back the
     * translation of an unrelated label — which is how emoji-prefixed items used to lose their
     * icon. So the array-scoped English match runs first, and the global key map is only
     * trusted when its own English text corroborates the item.
     */
    private fun lookupArrayItem(
        arrayName: String,
        itemName: String?,
        enFromElem: String,
        locale: String
    ): String? {
        // 1. Item is an @string/ reference → resolve through the referenced key
        if (enFromElem.startsWith("@string/")) {
            db.byKey[db.normKey(enFromElem.removePrefix("@string/"))]?.tr?.get(locale)
                ?.let { return it }
        }

        // 2. Array-scoped English match — same array, same source text
        if (enFromElem.isNotEmpty())
            db.lookupArray(arrayName, enFromElem, locale)?.let { return it }

        // 3. Global <string> key sharing the item's name — only when its English agrees
        val entry = itemName?.let { db.byKey[db.normKey(it)] }
        if (entry != null) {
            val corroborated = enFromElem.isEmpty() || entry.english.isEmpty() ||
                               db.normEn(entry.english) == db.normEn(enFromElem)
            if (corroborated) entry.tr[locale]?.let { return it }

            // 4. Array-scoped match using the CSV's English for that key
            if (entry.english.isNotEmpty() && entry.english != enFromElem)
                db.lookupArray(arrayName, entry.english, locale)?.let { return it }
        }
        return null
    }

    private fun processPlurals(
        elem: org.w3c.dom.Element,
        locale: String,
        lines: MutableList<String>,
        fallback: ExistingXml
    ): ArrayResult {
        val name = elem.getAttribute("name")
        val nonTranslatable = isNonTranslatable(elem)
        if (nonTranslatable && !overrideNonTranslatable) {
            ntSkipped += NonTranslatable(name, "plurals", "")
            return ArrayResult(0, 0, null)
        }
        val existingQuantities = if (nonTranslatable) emptyMap() else fallback.pluralItems[name] ?: emptyMap()

        val okItems = mutableListOf<Pair<String, String>>() // quantity to encoded value
        val missing = mutableListOf<String>()
        var translatedCount = 0
        var preservedCount  = 0

        var item = elem.firstChild
        var total = 0
        while (item != null) {
            if (item.nodeType == org.w3c.dom.Node.ELEMENT_NODE) {
                total++
                val itemElem = item as org.w3c.dom.Element
                val quantity = itemElem.getAttribute("quantity")
                val enText   = itemElem.textContent?.trim() ?: ""

                // Quantity-keyed lookup first: an Excel round-trip carries a value per quantity,
                // and locales with real plural rules need them kept apart even when `one` and
                // `other` share the same English source.
                val tr = db.lookupPlural(name, quantity, locale) ?: when {
                    enText.isEmpty() -> null
                    enText.startsWith("@string/") ->
                        db.byKey[db.normKey(enText.removePrefix("@string/"))]?.tr?.get(locale)
                    else -> db.lookup("", enText, locale)
                }

                when {
                    tr != null -> { okItems += quantity to formatXmlValue(tr); translatedCount++ }
                    // Merge: keep the quantity already present in the locale file rather than
                    // dropping the whole <plurals> block on every run.
                    generateMode == GenerateMode.MERGE && existingQuantities[quantity]?.isNotBlank() == true -> {
                        okItems += quantity to existingQuantities.getValue(quantity)
                        preservedCount++
                    }
                    enText.isEmpty() -> missing += "quantity=$quantity"
                    else             -> missing += "quantity=$quantity: \"$enText\""
                }
            }
            item = item.nextSibling
        }

        if (missing.isNotEmpty()) {
            val existingBlock = if (nonTranslatable) null else fallback.pluralBlocks[name]
            if (existingBlock != null) {
                lines += "    $existingBlock"
                return ArrayResult(1, 0, null)
            }
            if (nonTranslatable) {
                ntSkipped += NonTranslatable(name, "plurals", "")
                return ArrayResult(0, 0, null)
            }
            return ArrayResult(0, 1, SkippedArray("plurals:$name", translatedCount, total, missing))
        }

        lines += """    <plurals name="$name">"""
        okItems.forEach { (qty, v) -> lines += """        <item quantity="$qty">$v</item>""" }
        lines += "    </plurals>"
        if (nonTranslatable)
            ntOverridden += NonTranslatable(name, "plurals", "", "$total quantities")
        return ArrayResult(1, 0, null, preservedCount)
    }

    // ── Existing locale file ───────────────────────────────────────────────────

    /** One `<item>` of an existing `<string-array>`. [raw] is kept encoded (CDATA intact). */
    private data class ArrayItem(val name: String?, val raw: String)

    /**
     * Snapshot of a locale file that already exists on disk.
     * [order] holds `"<type>:<name>"` keys in document order — the basis for order preservation.
     */
    private data class ExistingXml(
        val strings: Map<String, String>,
        val arrayBlocks: Map<String, String>,
        val arrayItems: Map<String, List<ArrayItem>>,
        val pluralBlocks: Map<String, String>,
        val pluralItems: Map<String, Map<String, String>>,
        val order: List<String>
    ) {
        companion object {
            val EMPTY = ExistingXml(emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyList())
        }
    }

    private fun parseExistingXml(path: Path): ExistingXml {
        if (!path.exists()) return ExistingXml.EMPTY
        val text = runCatching { path.readText() }.getOrElse { return ExistingXml.EMPTY }

        val strings      = mutableMapOf<String, String>()
        val arrayBlocks  = mutableMapOf<String, String>()
        val arrayItems   = mutableMapOf<String, List<ArrayItem>>()
        val pluralBlocks = mutableMapOf<String, String>()
        val pluralItems  = mutableMapOf<String, Map<String, String>>()

        STRING_RE.findAll(text).forEach { m ->
            val content = m.groupValues[2].trim()
            strings[m.groupValues[1]] = CDATA_RE.find(content)?.groupValues?.get(1)?.trim() ?: content
        }

        ARRAY_RE.findAll(text).forEach { m ->
            val name = m.groupValues[2]
            arrayBlocks[name] = m.groupValues[1].trimStart()
            arrayItems[name]  = ITEM_RE.findAll(m.groupValues[3]).map { im ->
                ArrayItem(
                    name = ITEM_NAME_RE.find(im.groupValues[1])?.groupValues?.get(1),
                    raw  = im.groupValues[2].trim()
                )
            }.toList()
        }

        PLURALS_RE.findAll(text).forEach { m ->
            val name = m.groupValues[2]
            pluralBlocks[name] = m.groupValues[1].trimStart()
            pluralItems[name]  = ITEM_RE.findAll(m.groupValues[3]).mapNotNull { im ->
                val qty = QUANTITY_RE.find(im.groupValues[1])?.groupValues?.get(1) ?: return@mapNotNull null
                qty to im.groupValues[2].trim()
            }.toMap()
        }

        val order = ORDER_RE.findAll(text).map { m ->
            when (m.groupValues[1]) {
                "string-array" -> "array:${m.groupValues[2]}"
                "plurals"      -> "plurals:${m.groupValues[2]}"
                else           -> "string:${m.groupValues[2]}"
            }
        }.toList()

        return ExistingXml(strings, arrayBlocks, arrayItems, pluralBlocks, pluralItems, order)
    }

    companion object {
        // Matches JSON asset path strings like "task/tasks.json", "whats_new/whats_new.json"
        // These must never be sourced from the CSV — only from dynamic asset detection or Merge fallback
        private val JSON_PATH_PATTERN = Regex("""^[\w][\w_-]*/[\w][\w_-]*\.json$""")

        private val STRING_RE    = Regex("""<string\s+name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
        private val CDATA_RE     = Regex("""^\s*<!\[CDATA\[(.*)\]\]>\s*$""", RegexOption.DOT_MATCHES_ALL)
        private val ARRAY_RE     = Regex("""([ \t]*<string-array\s+name="([^"]+)"[^>]*>(.*?)</string-array>)""", RegexOption.DOT_MATCHES_ALL)
        private val PLURALS_RE   = Regex("""([ \t]*<plurals\s+name="([^"]+)"[^>]*>(.*?)</plurals>)""", RegexOption.DOT_MATCHES_ALL)
        private val ITEM_RE      = Regex("""<item([^>]*)>(.*?)</item>""", RegexOption.DOT_MATCHES_ALL)
        private val ITEM_NAME_RE = Regex("""name="([^"]+)"""")
        private val QUANTITY_RE  = Regex("""quantity="([^"]+)"""")
        // string-array / plurals listed first: alternation is leftmost-first, so "string"
        // must not win against "<string-array".
        private val ORDER_RE     = Regex("""<(string-array|plurals|string)\s+name="([^"]+)"""")

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
