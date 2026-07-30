package com.paulbaker.localize

import com.paulbaker.localize.config.GenerateMode
import com.paulbaker.localize.core.TranslationDb
import com.paulbaker.localize.core.XmlGenerator
import java.nio.file.Files
import java.nio.file.Path

/** Temporary verification harness — not part of the plugin. */
object DevCheck {

    private var failures = 0

    @JvmStatic
    fun main(args: Array<String>) {
        val root = Files.createTempDirectory("localize-devcheck")
        testOrderPreservation(root.resolve("t1"))
        testOrderFollowTemplate(root.resolve("t2"))
        testArrayItemNameCollision(root.resolve("t3"))
        testArrayPartialMerge(root.resolve("t4"))
        testPluralsMerge(root.resolve("t5"))
        testInvisibleCharMatch(root.resolve("t6"))
        testNonTranslatableSkipped(root.resolve("t7"))
        testDashboardCentering()
        println(if (failures == 0) "\nALL CHECKS PASSED" else "\n$failures CHECK(S) FAILED")
    }

    // ── Scenarios ─────────────────────────────────────────────────────────────

    private fun testOrderPreservation(dir: Path) {
        val (tmpl, out) = scaffold(
            dir,
            template = """
                <resources>
                    <string name="a">Alpha</string>
                    <string name="b">Bravo</string>
                    <string name="c">Charlie</string>
                    <string name="d">Delta</string>
                </resources>
            """,
            existing = """
                <resources>
                    <string name="c">찰리</string>
                    <string name="a">알파</string>
                </resources>
            """
        )
        val db = db("android_key,english,korean\na,Alpha,알파\nb,Bravo,브라보\nc,Charlie,찰리\nd,Delta,델타\n", dir)
        val gen = XmlGenerator(db).apply { setGenerateMode(GenerateMode.MERGE); setPreserveKeyOrder(true) }
        gen.generate(tmpl, out, "ko", "ko")

        val order = keyOrder(out)
        // c and a keep their existing relative position; b/d are new to this locale
        check("order: c stays first", order.first() == "c", order)
        check("order: a before b", order.indexOf("a") < order.indexOf("b"), order)
        check("order: all four present", order.size == 4, order)
    }

    private fun testOrderFollowTemplate(dir: Path) {
        val (tmpl, out) = scaffold(
            dir,
            template = """
                <resources>
                    <string name="a">Alpha</string>
                    <string name="b">Bravo</string>
                </resources>
            """,
            existing = """
                <resources>
                    <string name="b">브라보</string>
                    <string name="a">알파</string>
                </resources>
            """
        )
        val db = db("android_key,english,korean\na,Alpha,알파\nb,Bravo,브라보\n", dir)
        val gen = XmlGenerator(db).apply { setGenerateMode(GenerateMode.MERGE); setPreserveKeyOrder(false) }
        gen.generate(tmpl, out, "ko", "ko")

        val order = keyOrder(out)
        check("no-preserve: follows template order", order == listOf("a", "b"), order)
    }

    /** The reported bug: item name "formal" exists in two arrays AND as a <string> key. */
    private fun testArrayItemNameCollision(dir: Path) {
        val (tmpl, out) = scaffold(
            dir,
            template = """
                <resources>
                    <string name="formal">Formal</string>
                    <string-array name="writing_tag_array">
                        <item name="formal">Formal</item>
                        <item name="friendly">Friendly</item>
                    </string-array>
                    <string-array name="email_writing_tone">
                        <item name="formal">😊 Formal</item>
                        <item name="friendly">😄 Friendly</item>
                    </string-array>
                </resources>
            """,
            existing = null
        )
        val db = TranslationDb()
        val main = dir.resolve("main.csv")
        main.toFile().writeText("android_key,english,korean\nformal,Formal,격식체\n", Charsets.UTF_8)
        db.loadCsv(main, "android_key", mapOf("korean" to "ko"))
        val arrays = dir.resolve("arrays.csv")
        arrays.toFile().writeText(
            "android_key,english,korean\n" +
            "writing_tag_array,Formal,격식체\n" +
            ",Friendly,친근한\n" +
            "email_writing_tone,😊 Formal,😊 격식체\n" +
            ",😄 Friendly,😄 친근한\n",
            Charsets.UTF_8
        )
        db.loadArrayCsv(arrays, mapOf("korean" to "ko"))

        val gen = XmlGenerator(db).apply { setGenerateMode(GenerateMode.MERGE); setPreserveKeyOrder(true) }
        gen.generate(tmpl, out, "ko", "ko")
        val text = out.toFile().readText()

        check("array: email tone keeps 😊", "😊 격식체" in text, text)
        check("array: email tone keeps 😄", "😄 친근한" in text, text)
        check("array: plain tag array still resolves",
            Regex("""<string-array name="writing_tag_array">\s*<item name="formal">격식체</item>""").containsMatchIn(text), text)
    }

    /** One item has no translation → Merge should keep just that item, not drop the array. */
    private fun testArrayPartialMerge(dir: Path) {
        val (tmpl, out) = scaffold(
            dir,
            template = """
                <resources>
                    <string-array name="writing_improve_array">
                        <item name="simplify">Simplify</item>
                        <item name="expand">Expand</item>
                        <item name="suggest">Suggest Reliable Source</item>
                    </string-array>
                </resources>
            """,
            existing = """
                <resources>
                    <string-array name="writing_improve_array">
                        <item name="simplify">تبسيط</item>
                        <item name="expand">توسيع</item>
                        <item name="suggest">اقترح مصدرا موثوقا</item>
                    </string-array>
                </resources>
            """
        )
        val db = TranslationDb()
        val arrays = dir.resolve("arrays.csv")
        // "Suggest Reliable Source" has an EMPTY arabic cell — the real CSV case
        arrays.toFile().writeText(
            "android_key,english,arabic\n" +
            "writing_improve_array,Simplify,بسّط\n" +
            ",Expand,وسّع\n" +
            ",Suggest Reliable Source,\n",
            Charsets.UTF_8
        )
        db.loadArrayCsv(arrays, mapOf("arabic" to "ar"))

        val gen = XmlGenerator(db).apply { setGenerateMode(GenerateMode.MERGE); setPreserveKeyOrder(true) }
        gen.generate(tmpl, out, "ar", "ar")
        val text = out.toFile().readText()

        check("partial array: new translation applied", "بسّط" in text, text)
        check("partial array: missing item preserved from existing file", "اقترح مصدرا موثوقا" in text, text)
    }

    private fun testPluralsMerge(dir: Path) {
        val (tmpl, out) = scaffold(
            dir,
            template = """
                <resources>
                    <plurals name="items_count">
                        <item quantity="one">%d item</item>
                        <item quantity="other">%d items</item>
                    </plurals>
                </resources>
            """,
            existing = """
                <resources>
                    <plurals name="items_count">
                        <item quantity="one">%d รายการ</item>
                        <item quantity="other">%d รายการ</item>
                    </plurals>
                </resources>
            """
        )
        // CSV only covers "one" — "other" must survive via the Merge fallback
        val db = db("android_key,english,thai\n,%d item,%d ชิ้น\n", dir)
        val gen = XmlGenerator(db).apply { setGenerateMode(GenerateMode.MERGE); setPreserveKeyOrder(true) }
        gen.generate(tmpl, out, "th", "th")
        val text = out.toFile().readText()

        check("plurals: block not dropped", "<plurals name=\"items_count\">" in text, text)
        check("plurals: new quantity translated", "%d ชิ้น" in text, text)
        check("plurals: untouched quantity preserved", "%d รายการ" in text, text)
    }

    /** CSV carries a stray U+FE0F that strings.xml doesn't have. */
    private fun testInvisibleCharMatch(dir: Path) {
        val (tmpl, out) = scaffold(
            dir,
            template = """
                <resources>
                    <string-array name="use_ai_for">
                        <item name="work_tasks">Work Tasks</item>
                    </string-array>
                </resources>
            """,
            existing = null
        )
        val db = TranslationDb()
        val arrays = dir.resolve("arrays.csv")
        arrays.toFile().writeText(
            "android_key,english,korean\nuse_ai_for,️Work Tasks,업무\n", Charsets.UTF_8
        )
        db.loadArrayCsv(arrays, mapOf("korean" to "ko"))

        val gen = XmlGenerator(db).apply { setGenerateMode(GenerateMode.MERGE); setPreserveKeyOrder(true) }
        gen.generate(tmpl, out, "ko", "ko")
        check("invisible chars: item matched despite U+FE0F", "업무" in out.toFile().readText(),
              out.toFile().readText())
    }

    /** `translatable="false"` in the template must be invisible to BOTH generate and export. */
    private fun testNonTranslatableSkipped(dir: Path) {
        val template = """
            <resources>
                <string name="cancel">Cancel</string>
                <string name="api_base_url" translatable="false">https://api.example.com</string>
                <string name="build_flavor" translatable="FALSE">prod</string>
                <string-array name="debug_only" translatable="false">
                    <item name="a">A</item>
                </string-array>
                <plurals name="internal_count" translatable="false">
                    <item quantity="one">%d thing</item>
                    <item quantity="other">%d things</item>
                </plurals>
            </resources>
        """
        val (tmpl, out) = scaffold(dir, template = template, existing = null)
        val db = db("android_key,english,korean\ncancel,Cancel,취소\napi_base_url,https://api.example.com,https://api.example.com\nbuild_flavor,prod,프로드\n", dir)
        XmlGenerator(db).apply { setGenerateMode(GenerateMode.MERGE); setPreserveKeyOrder(true) }
            .generate(tmpl, out, "ko", "ko")
        val xml = out.toFile().readText()

        check("translatable: normal string written", "취소" in xml, xml)
        check("translatable: false string omitted", "api_base_url" !in xml, xml)
        check("translatable: FALSE (uppercase) omitted", "build_flavor" !in xml, xml)
        check("translatable: false array omitted", "debug_only" !in xml, xml)
        check("translatable: false plurals omitted", "internal_count" !in xml, xml)

        // ── Override ON: written only where the source actually has a translation ──
        val out2 = dir.resolve("override/values-x/strings.xml")
        Files.createDirectories(out2.parent)
        val gen2 = XmlGenerator(db).apply {
            setGenerateMode(GenerateMode.MERGE); setPreserveKeyOrder(true)
            setOverrideNonTranslatable(true)
        }
        val r2 = gen2.generate(tmpl, out2, "ko", "ko")
        val xml2 = out2.toFile().readText()

        check("override: string with a source value is written", "프로드" in xml2, xml2)
        // api_base_url IS in the CSV but its "translation" equals the URL — still an override
        check("override: reported as overridden",
              r2.ntOverridden.map { it.key }.containsAll(listOf("build_flavor", "api_base_url")), r2.ntOverridden)
        check("override: array with no source stays skipped", "debug_only" !in xml2, xml2)
        check("override: plurals with no source stays skipped", "internal_count" !in xml2, xml2)
        check("override: skipped list holds the unresolved ones",
              r2.ntSkipped.map { it.key }.containsAll(listOf("debug_only", "internal_count")), r2.ntSkipped)
        check("override: normal strings unaffected", "취소" in xml2, xml2)

        // Export must not offer them to translators either
        val values = tmpl.parent
        val xlsx = dir.resolve("out.xlsx")
        com.paulbaker.localize.core.ExcelExporter(
            valuesDir = values, assetsDir = dir,
            selectedXmlFiles = listOf("strings.xml"), selectedLocales = listOf("ko"),
            selectedJsonAssets = emptyList(), logger = {}
        ).export(xlsx)

        val keys = xlsxColumn(xlsx, "XML Strings", 0) + xlsxColumn(xlsx, "String Arrays", 0)
        check("translatable: export skips false string", "api_base_url" !in keys, keys)
        check("translatable: export skips FALSE string", "build_flavor" !in keys, keys)
        check("translatable: export skips false array", "debug_only" !in keys, keys)
        check("translatable: export skips false plurals", keys.none { it.startsWith("internal_count:") }, keys)
        check("translatable: export keeps normal string", "cancel" in keys, keys)
    }

    private fun xlsxColumn(path: Path, sheetName: String, col: Int): List<String> =
        org.apache.poi.ss.usermodel.WorkbookFactory.create(path.toFile(), null, true).use { wb ->
            val sheet = wb.getSheet(sheetName) ?: return emptyList()
            (1..sheet.lastRowNum).mapNotNull { r ->
                sheet.getRow(r)?.getCell(col)?.let { org.apache.poi.ss.usermodel.DataFormatter().formatCellValue(it) }
            }
        }

    /**
     * Lays the dashboard out at a few sizes and asserts the cards really are centred.
     * Guards the BoxLayout alignmentX trap: one child with a different alignmentX silently
     * offsets every sibling instead of failing.
     */
    private fun testDashboardCentering() {
        val panel = com.paulbaker.localize.ui.DashboardPanel(onLocalize = {}, onExport = {})

        listOf(1500 to 780, 900 to 600, 320 to 500).forEach { (w, h) ->
            panel.setSize(w, h)
            panel.doLayout()
            layoutTree(panel)

            val cards = mutableListOf<javax.swing.JComponent>()
            collectCards(panel, cards)
            if (cards.size != 2) { check("dashboard ${w}x$h: found 2 cards", false, cards.size); return@forEach }

            cards.forEach { card ->
                val x = absoluteX(card, panel)
                val leftGap  = x
                val rightGap = w - (x + card.width)
                // BoxLayout's tiled distribution can leave a 1-2px remainder unallocated at the
                // trailing edge. Anything beyond that means the centring is actually broken.
                check("dashboard ${w}x$h: card centred (left=$leftGap right=$rightGap)",
                      Math.abs(leftGap - rightGap) <= 2, "w=$w cardX=$x cardW=${card.width}")
            }
            val widths = cards.map { it.width }.distinct()
            check("dashboard ${w}x$h: cards share one width", widths.size == 1, widths)
            check("dashboard ${w}x$h: card width capped", cards[0].width <= 520, cards[0].width)
        }
    }

    private fun layoutTree(c: java.awt.Container) {
        c.doLayout()
        c.components.forEach { if (it is java.awt.Container) layoutTree(it) }
    }

    private fun collectCards(c: java.awt.Container, out: MutableList<javax.swing.JComponent>) {
        c.components.forEach { comp ->
            // The cards are the only components carrying a HAND cursor
            if (comp is javax.swing.JPanel && comp.isCursorSet &&
                comp.cursor.type == java.awt.Cursor.HAND_CURSOR) out += comp
            if (comp is java.awt.Container) collectCards(comp, out)
        }
    }

    private fun absoluteX(c: java.awt.Component, root: java.awt.Component): Int {
        var x = 0; var cur: java.awt.Component? = c
        while (cur != null && cur !== root) { x += cur.x; cur = cur.parent }
        return x
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun scaffold(dir: Path, template: String, existing: String?): Pair<Path, Path> {
        val values = dir.resolve("values")
        Files.createDirectories(values)
        val tmpl = values.resolve("strings.xml")
        tmpl.toFile().writeText("""<?xml version="1.0" encoding="utf-8"?>""" + "\n" + template.trimIndent(), Charsets.UTF_8)

        val out = dir.resolve("values-x").resolve("strings.xml")
        if (existing != null) {
            Files.createDirectories(out.parent)
            out.toFile().writeText("""<?xml version="1.0" encoding="utf-8"?>""" + "\n" + existing.trimIndent(), Charsets.UTF_8)
        }
        return tmpl to out
    }

    private fun db(csv: String, dir: Path): TranslationDb {
        val path = dir.resolve("main.csv")
        path.toFile().writeText(csv, Charsets.UTF_8)
        val header = csv.lineSequence().first().split(",")
        val langMap = header.drop(2).associateWith { col ->
            when (col.trim()) {
                "korean" -> "ko"; "thai" -> "th"; "arabic" -> "ar"; else -> col.trim()
            }
        }
        return TranslationDb().also { it.loadCsv(path, "android_key", langMap) }
    }

    private fun keyOrder(out: Path): List<String> =
        Regex("""<string\s+name="([^"]+)"""").findAll(out.toFile().readText()).map { it.groupValues[1] }.toList()

    private fun check(label: String, ok: Boolean, detail: Any) {
        if (ok) println("  PASS  $label")
        else { failures++; println("  FAIL  $label\n        ---\n$detail\n        ---") }
    }
}
