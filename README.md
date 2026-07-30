# Localize Tool — IntelliJ / Android Studio Plugin

Two-way localization tooling for Android projects:

- **Localize from CSV** — generate localized `strings.xml` and JSON asset files from translation spreadsheets.
- **Export to Excel** — dump the current strings / arrays / plurals / JSON assets of every locale into an `.xlsx` workbook for translators.

Built for the `app/` module layout, but every directory is configurable.

> **Dùng plugin, không sửa plugin?** Xem [Hướng dẫn sử dụng](docs/HUONG-DAN-SU-DUNG.md) — tài liệu cho người dùng, không yêu cầu kiến thức code. This README covers the internals.

---

## Table of Contents

- [Features](#features)
- [Installation](#installation)
- [Quick Start](#quick-start)
- [Dashboard](#dashboard)
- [Tool 1 — Localize from CSV](#tool-1--localize-from-csv)
  - [CSV Format](#csv-format)
  - [CSV Column Mapping](#csv-column-mapping)
  - [Panel Overview](#panel-overview)
  - [Generate Modes](#generate-modes)
  - [JSON Asset Configuration](#json-asset-configuration)
  - [Settings](#settings)
  - [Output Files](#output-files)
  - [Exception Report](#exception-report)
- [Tool 2 — Export to Excel](#tool-2--export-to-excel)
- [Configuration File](#configuration-file)
- [Architecture](#architecture)
- [Building from Source](#building-from-source)
- [Adding a New Language](#adding-a-new-language)

---

## Features

### Localize (CSV → project)

| Feature | Details |
|---------|---------|
| **CSV column mapping** | Pre-process any CSV format — map non-standard headers (`Key`, `Text`, `QC Check`) to the correct roles before localization. Saved per file path. |
| **Smart language detection** | Auto-detects languages from all 3 CSV inputs, merges them, deduplicates by locale code. Supports English names, ISO codes, aliases, native names, Java `Locale` fallback, and manual entry. |
| **XML generation** | Full support for `<string>`, `<string-array>`, `<plurals>`, CDATA, HTML tags, format strings. |
| **`translatable="false"`** | Honoured everywhere — dropped from generated locale files and withheld from the Excel export. Matched case-insensitively. An opt-in setting overrides it when the source supplies a translation; either way the report accounts for every one. |
| **JSON asset generation** | Recursive translation of nested JSON at any depth, including string arrays inside selected fields. |
| **Merge mode** | Preserves existing translations for strings not in the current CSV — safe for incremental updates. Falls back per `<string-array>` item and per `<plurals>` quantity, so one missing translation no longer drops the whole block. |
| **Stable key order** | A key keeps the position it already has in the locale file; new keys are inserted next to their template neighbour. Regenerating produces a reviewable diff instead of delete-here / add-there noise. Toggleable. |
| **Full Replace mode** | Overwrites output entirely from CSV. JSON path strings (`tasks_json_file_path`) are still generated with the correct locale suffix. |
| **Smart asset detection** | Auto-filters Lottie animations and non-translatable config files into a collapsible "Ignored" group. |
| **Field configuration** | Per-asset tick-box field selector with a live side-by-side JSON preview (EN vs translated reference). |
| **Exception report** | Per-locale Markdown report: added / changed keys, missing translations, CSV conflicts, skipped arrays, unmatched JSON fields. |

### Export (project → Excel)

| Feature | Details |
|---------|---------|
| **Round-trip headers** | The `XML Strings` and `String Arrays` sheets use exactly the `android_key, english, <locale>…` header layout the Localize tool consumes. |
| **Plurals export** | Each quantity becomes its own row keyed `plural_name:quantity`. |
| **CDATA-safe** | Values wrapped in `<![CDATA[…]]>` are unwrapped to their inner text; Android escapes are left intact. |
| **Skips non-translatable** | `translatable="false"` strings, arrays and plurals are left out, so translators never see values that must not change. |
| **Locale auto-discovery** | Scans `values-*/` sibling directories and offers each locale as a column toggle. |
| **JSON assets sheet** | `asset | field | english | <locale>…`, matched item-by-item against `*_<locale>.json`. |
| **Independent config** | Export keeps its own selected locales / XML files / asset fields, separate from the Localize tool. |

### Shared

| Feature | Details |
|---------|---------|
| **Dashboard navigation** | Card-based tool picker; each tool has a back arrow. |
| **Settings persistence** | Everything saved to `.idea/localize-plugin.json`. Changing Generate Mode does not reset JSON asset selections. |
| **Background execution** | Both Generate and Export run on a background task with progress indicator + live log pane. |
| **Dynamic plugin** | Reloads without a full IDE restart (`dynamic="true"`). |

---

## Installation

### Build from source

```bash
git clone <repo-url>
cd localize-plugin
./gradlew buildPlugin
# Output: build/distributions/localize-plugin-1.0.0.zip
```

### Install in Android Studio

1. **Settings → Plugins → ⚙️ → Install Plugin from Disk...**
2. Select the `.zip` → **OK** → Restart plugin (not full IDE)

The **Localize** panel appears in the right sidebar (secondary tool window). **Tools → Localize...** also focuses it.

### Development / fast iteration

```bash
./gradlew runIde   # Opens a sandboxed IDE with the plugin pre-installed
```

---

## Quick Start

1. Open your Android project in Android Studio
2. Click **Localize** in the right sidebar → the **dashboard** appears
3. Pick a tool:
   - **Localize from CSV** → select CSV files, languages are auto-detected → **Generate**
   - **Export to Excel** → tick locales / XML files / assets → **Export**

---

## Dashboard

```
┌─ Localize ────────────────────────────────────────────────────┐
│                       Choose a tool                           │
│                                                               │
│  ┌──────────────────────────────────────────────────────────┐ │
│  │ ⟳   Localize from CSV                                    │ │
│  │     Generate localized strings.xml and JSON assets       │ │
│  │     from translation spreadsheets                        │ │
│  └──────────────────────────────────────────────────────────┘ │
│  ┌──────────────────────────────────────────────────────────┐ │
│  │ ↓   Export to Excel                                      │ │
│  │     Export all strings and JSON assets                   │ │
│  │     to Excel format for translation                      │ │
│  └──────────────────────────────────────────────────────────┘ │
└───────────────────────────────────────────────────────────────┘
```

`MainPanel` owns a `CardLayout` with three cards: `dashboard` | `localize` | `export`. Each tool panel has a **←** button in its top bar to return.

---

# Tool 1 — Localize from CSV

## CSV Format

The plugin accepts up to three CSV files:

### 1. `android_only_strings.csv` — main strings

```
android_key,english,korean,arabic,thai
cancel,Cancel,취소,إلغاء,ยกเลิก
ok,OK,확인,موافق,ตกลง
,Insert Web URL,,أدخل عنوان URL للويب,แทรก URL ของเว็บ
```

| Column | Required | Description |
|--------|----------|-------------|
| `android_key` | — | `name` attribute in `strings.xml`. Can be empty if English text is provided. |
| `english` | ✓ | Source English text — used for fallback lookup when key is missing or mismatched |
| *language columns* | ✓ | One column per target language |

> Rows with no key but with English text are valid — they contribute to English-text-based lookup (`byEn`).

### 2. `overlap.csv` — cross-platform shared strings *(optional)*

Same format but uses `unified_id` as key column. When a key appears in both CSVs, `android_only` takes priority and the difference is recorded as a conflict.

### 3. `android_array_strings.csv` — string-array items *(optional)*

Uses **sparse format** — array name appears only on the first row of each group:

```
android_key,english,korean,arabic,thai
label_length_array,Short,짧게,قصير,สั้น
,Medium,보통,متوسط,กลาง
,Long,길게,طويل,ยาว
```

The parser is a full RFC 4180 implementation: quoted fields, escaped `""`, and newlines inside quotes are handled; UTF-8 BOM is stripped from headers.

### Language detection

Headers are resolved using 4 strategies in order:

| Strategy | Examples |
|----------|---------|
| English name | `korean`, `arabic`, `thai`, `japanese` |
| ISO 639-1 code | `ko`, `ar`, `th`, `ja`, `fr` |
| Common alias | `kr` → `ko`, `jp` → `ja`, `cn` → `zh` |
| Native name | `한국어` → `ko`, `العربية` → `ar`, `ภาษาไทย` → `th` |
| Java `Locale` sweep | any language name Java knows about |

Languages from all 3 CSV files are **merged and deduplicated by locale code**. When two files resolve to the same locale, the descriptive column name (`Korean`) wins over the bare code (`ko`) for the checkbox label. Unrecognized columns show a small text input for manual locale entry; those mappings persist in `customLocaleMap`.

---

## CSV Column Mapping

For translation files that don't match the standard format, click the **✏️** icon next to any CSV picker (enabled once a valid file is selected).

```
┌─ Configure CSV: Localization - Missing.csv ──────────────────────────┐
│  33 columns  ·  455 rows  ·  Localization - Missing.csv              │
│  CSV Column             Role             Locale  Sample              │
│  ─────────────────────────────────────────────────────────────────── │
│  (empty)               [Skip        ▼]                               │
│  Key                   [String Key  ▼]          cancel               │
│  Text                  [String Value▼]          Cancel               │
│  Spanish (dịch cơm)    [Language    ▼]  [es]    Cancelar             │
│  QC Check              [Skip        ▼]                               │
│  QC Check (Android)    [Skip        ▼]                               │
│  Japanese (dịch cơm)   [Language    ▼]  [ja]    キャンセル            │
│  ...                                                                  │
│  Filters: ☑ Skip empty rows  ☑ Skip section-only rows               │
│  Preview — first 5 rows after mapping:                               │
│  Key    | Value  | es       | ja       | ko   | th                  │
│  cancel | Cancel | Cancelar | キャンセル| 취소 | ยกเลิก               │
│  ☑ Remember this mapping for this file                              │
│  Reset to auto-detect              [Cancel]  [Apply]                 │
└───────────────────────────────────────────────────────────────────────┘
```

**Role options:**

| Role | Maps to |
|------|---------|
| `String Key` | `android_key` column |
| `String Value` | `english` column |
| `Language` + locale | translation column |
| `Skip` | ignored entirely |

**Auto-detection** pre-fills roles from column names and sample values:

- QC/review columns (`qc`, `check`, `review`, `status`, `note`, `comment`, `verified`) → `Skip`
- Key names (`key`, `android_key`, `id`, `string_key`, `string_id`, `resource_key`) → `String Key`
- Value names (`text`, `english`, `en`, `source`, `value`, `string`, `original`, `default`) → `String Value`
- Translator suffixes are stripped before locale resolution: `(dịch cơm)`, `(machine translate)`, `(MT)`, `(auto)`, `(翻訳)`, `(번역)`, `(译)`
- Columns whose samples are all ≤3 characters → `Skip` (checkmark columns)

The live preview runs the real `applyMapping` on a pooled thread (200 ms debounce). Saved mappings are keyed by absolute file path and are honoured for all 3 CSV inputs, including the arrays CSV.

---

## Panel Overview

```
┌─ Localize ────────────────────────────────────────────────────┐
│  [←] [ Generate ]                              [ ⚙ ]          │
├───────────────────────────────────────────────────────────────┤
│  ── CSV Files ─────────────────────────────────────────────── │
│  android_only_strings.csv  [path ............] [📁] [✏️]      │
│  overlap.csv (optional)    [path ............] [📁] [✏️]      │
│  array_strings.csv (opt.)  [path ............] [📁] [✏️]      │
│                                                               │
│  ── Languages ─────────────────────────────────────────────── │
│  ☑ Korean (ko)   ☑ Thai (th)   ☐ Arabic (ar)               │
│                                                               │
│  ── XML Files ─────────────────────────────────────────────── │
│  ☑ strings.xml   ☑ ds_ob_text.xml                          │
│                                                               │
│  ── JSON Assets ─────────────────────────────────────────── │
│  ☑ whats_new       title, description    [⚙]               │
│  ☑ task            category, title       [⚙]               │
│  ▶ Ignored (3)   benefit_popup · ob_neon · lang             │
├───────────────────────────────────────────────────────────────┤
│  Log                                                          │
│  Phase 1: Loading CSVs...   ✓ android_only: loaded            │
│  ── Locale: th ────────────────────────────────────────────── │
│  Phase 2: Generating XML for th...                            │
│    [th] strings.xml → 1016 written, 2 skipped (+4 new)        │
│  ✅ Done.                                                     │
└───────────────────────────────────────────────────────────────┘
```

**Behaviors:**
- On first open, CSV pickers auto-fill from `*.csv` files in the project root (matched by `android_only` / `overlap` / `array` in the filename)
- ✏️ edit icon is enabled only when a valid CSV file is selected
- Changing Generate Mode does **not** reset JSON asset tick state — only changing a directory triggers a re-scan of that section
- XML file list skips known non-translatable files and any file without a `<string` element
- Ignored group holds Lottie animations and text-free config files; click **[+ Add]** to promote one to the main list
- Deselecting all JSON assets still generates correct JSON path strings in `strings.xml`

---

## Generate Modes

Set in **⚙ Settings → Generate Mode**.

### Merge *(default — recommended)*

For each string:
1. Found in CSV → use CSV translation
2. Not in CSV, existing output has it → **preserve existing translation** (CDATA re-wrapped correctly)
3. Not in CSV, no existing → skip (reported as missing)

For `<string-array>` and `<plurals>`, the fallback is **per item**: an item with no translation keeps the value already in the locale file (arrays match by `name`, unnamed items and plurals by position/quantity). Only when an item can be resolved neither way does the block fall back to preserving the previous version verbatim, and only failing that is it skipped.

For JSON assets: the existing `*_<locale>.json` is used as a recursive per-field fallback tree.

Safe for incremental updates. Run as often as needed.

### Full Replace

Output contains **only** what the CSV provides. The output file is always written, even when the CSV covers fewer strings than the existing file.

### JSON path strings in both modes

Strings whose value matches `dir/file.json` (e.g. `tasks_json_file_path`) are infrastructure, not copy. They are **never** taken from the CSV, and **never** skipped:

1. Asset **selected** → localized path from dynamic asset detection (`task/tasks_th.json`)
2. Asset **not selected**, Merge → preserved from existing `values-th/strings.xml`
3. Asset **not selected**, Full Replace / first run → generated from the base pattern

This guarantees `strings.xml` is never left pointing at a non-existent file.

---

## JSON Asset Configuration

Click **⚙** next to any asset:

```
┌─ Configure: whats_new ─────────────────────────  Preview: [ko ▼] ┐
│  Fields to translate:  ☑ title  ☑ description  ☐ resource  ☐ type │
│                                                                     │
│  ┌── Original (EN) ───────────────┐  ┌── Preview (ko) ───────────┐ │
│  │ "title": "Meet Claude",        │  │ "title": "Claude를 만나…", │ │
│  │ "description": "Experience…",  │  │ "description": "더 자연…", │ │
│  │ "resource": "what_news_claude" │  │ "resource": "what_news_…" │ │
│  └────────────────────────────────┘  └───────────────────────────┘ │
│                                                   [Cancel]  [Save]  │
└─────────────────────────────────────────────────────────────────────┘
```

- Field list is **every string field found in the base JSON**, at any depth (max 8 levels)
- Auto-tick is derived by diffing the base JSON against the `_ko.json` reference, matching items by `id` / `name` / `key` / `type` so a different item order doesn't produce false positives
- Tick/untick to see a live recursive merge preview (debounced 120 ms, parsed on a pooled thread)
- Green = value taken from the translated file · purple = selected field in the EN pane
- Files larger than 300 KB show a placeholder instead of the preview (they are still processed on Generate)
- The locale dropdown lists the locales currently ticked in the panel

---

## Settings

Click **⚙** (top-right of the Localize panel):

| Setting | Default | Purpose |
|---------|---------|---------|
| Generate Mode | Merge | Merge vs Full Replace |
| Keep the position each key already has | on | Key order in the locale files — see below |
| Translate anyway when the source provides a value | off | `translatable="false"` override — see below |
| Values dir | `app/src/main/res/values` | XML template source + locale output parent |
| Assets dir | `app/src/main/assets` | JSON assets scan root |
| Report dir | project root | Where `localize_report_*.md` is written |

A directory left at its default value is stored as empty, so future default changes are picked up automatically. **Reset to defaults** restores everything.

### Key order

A locale file rarely lists its keys in the same order as `values/strings.xml` — a key sitting at line 10 of the template may be at line 6 of `values-ko/`. Rewriting the locale file in template order therefore moves it, and the diff shows a deletion in one place and an unrelated addition far below. Nothing changed, but the change is unreviewable.

**On (default)** — every key that already exists in `values-{locale}/` stays exactly where it is. A key that is new to this locale is inserted directly after its nearest already-placed template neighbour, so related keys stay grouped instead of piling up at the bottom. The diff then contains only the lines whose text actually changed.

**Off** — the locale file is rewritten in template order, the pre-existing behaviour.

The setting applies to `<string>`, `<string-array>` and `<plurals>` alike, and works in both Merge and Full Replace (it only decides ordering, never content).

---

### `translatable="false"`

`translatable="false"` declares that the template owns a value: an API URL, a build flavour, a debug label. By default the plugin honours it — the element never reaches a locale file, and it is never exported for translation.

Sometimes a value marked that way *should* be localized after all, and the translators have supplied one. Tick **Translate anyway when the source provides a value** and each such element is written **if, and only if, the source has a translation for it**. Elements the source doesn't cover stay out.

The override is deliberately source-driven: no Merge fallback, no value carried over from a previous run. `translatable="false"` is an explicit statement, so overriding it has to be an explicit, traceable decision coming from the spreadsheet — silently resurrecting a stale value would defeat the attribute and make the report misleading.

Both outcomes are always reported, whether the setting is on or off:

```markdown
## Non-Translatable — Overridden

These carry `translatable="false"` in the template but the source supplied a
translation, so they WERE written. Verify each one is meant to be localized.

| Key            | Kind         | Source (EN) | Written |
|----------------|--------------|-------------|---------|
| `build_flavor` | string       | prod        | 프로드   |
| `tone_array`   | string-array |             | 10 items |

## Non-Translatable — Skipped

`translatable="false"` was honoured: these are absent from the locale file.

| Key              | Kind    | Value (EN)                |
|------------------|---------|---------------------------|
| `api_base_url`   | string  | https://api.example.com   |
| `internal_count` | plurals |                           |
```

---

## Output Files

| Template | Generated (example: `th`) |
|----------|--------------------------|
| `values/strings.xml` | `values-th/strings.xml` |
| `values/ds_ob_text.xml` | `values-th/ds_ob_text.xml` |
| `assets/whats_new/whats_new.json` | `assets/whats_new/whats_new_th.json` |
| `assets/task/tasks.json` | `assets/task/tasks_th.json` |
| Any selected JSON asset | `assets/{dir}/{base}_{locale}.json` |

---

## Exception Report

`localize_report_{locale}.md` is written to the report dir after each generation:

```markdown
# Localization Report: th

## Summary
| Metric                               | Count |
|--------------------------------------|-------|
| XML strings written (from CSV)       | 1016  |
| XML strings preserved (Merge)        | 0     |
| XML strings skipped (no translation) | 2     |
| XML new keys added                   | 4     |
| XML values changed                   | 37    |
| JSON files written                   | 6     |
| JSON fields unmatched                | 21    |
| CSV conflicts                        | 24    |
| Skipped string-arrays                | 2     |
```

Followed by detail sections (each omitted when empty):

| Section | Contents |
|---------|----------|
| **Non-Translatable — Overridden** | carried `translatable="false"` but the source had a translation, so they *were* written — the list to review |
| **Non-Translatable — Skipped** | `translatable="false"` honoured; absent from the locale file |
| **Changed Values** | keys whose translation differs from the previous run |
| **CSV Conflicts** | same key in both `android_only` and `overlap` with different values; `android_only` always wins |
| **XML Keys Not Found in CSV** | key + the English text that was searched for |
| **JSON Fields Not Matched** | grouped by field path + value with an occurrence count (not per-item) for easy triage |
| **Skipped String-Arrays** | found/total plus the first 3 missing item names — the whole array is skipped to avoid partial localization |

---

# Tool 2 — Export to Excel

Reads what is **already in the project** and writes an `.xlsx` workbook — the inverse of the Localize tool. Use it to hand current translations to a translator, or to snapshot state before a Full Replace.

```
┌─ Localize ────────────────────────────────────────────────────┐
│  [←]                                        [ Export ]        │
├───────────────────────────────────────────────────────────────┤
│  ── Source ──────────────────────────────────────────────────  │
│  Values dir:  [app/src/main/res/values .............] [📁]     │
│  Assets dir:  [app/src/main/assets .................] [📁]     │
│                                                               │
│  ── Languages  (locales to export as columns) ────────────── │
│  ☑ ar  ☑ ja  ☑ ko  ☑ th  ☐ zh                              │
│                                                               │
│  ── XML Files  (from values/ directory) ─────────────────── │
│  ☑ strings.xml   ☑ ds_ob_text.xml                          │
│                                                               │
│  ── JSON Assets ─────────────────────────────────────────── │
│  ☑ whats_new       title, description    [⚙]               │
│  ▶ Ignored (3)                                              │
│                                                               │
│  ── Output ───────────────────────────────────────────────── │
│  Output file: [<project>/localize_export.xlsx ......] [📁]     │
├───────────────────────────────────────────────────────────────┤
│  Log                                                          │
│  Locales to export: ar, ja, ko, th                            │
│  Building XML Strings sheet (including plurals)...            │
│    Scanning strings.xml...                                    │
│  ✅ Saved: localize_export.xlsx                               │
└───────────────────────────────────────────────────────────────┘
```

### Sheets

**1. `XML Strings`** — one row per `<string>`, plus one row per plural quantity.

| android_key | english | ko | th |
|---|---|---|---|
| `cancel` | Cancel | 취소 | ยกเลิก |
| `items_count:one` | %d item | %d개 항목 | %d รายการ |
| `items_count:other` | %d items | %d개 항목 | %d รายการ |

**2. `String Arrays`** — sparse format identical to `android_array_strings.csv`; the array name appears only on the first item of each group.

| android_key | english | ko | th |
|---|---|---|---|
| `label_length_array` | Short | 짧게 | สั้น |
| | Medium | 보통 | กลาง |
| | Long | 길게 | ยาว |

**3. `JSON Assets`** — one row per selected field of each item in the base JSON, matched positionally against `*_<locale>.json`. Nested fields are reported by their dotted path (`header.title`).

| asset | field | english | ko | th |
|---|---|---|---|---|
| `whats_new` | title | Meet Claude | Claude를 만나보세요 | พบกับ Claude |
| `whats_new` | description | Experience… | 더 자연스러운… | สัมผัส… |

### Behaviour notes

- Locale columns are **only** the explicitly ticked ones — there is no fallback to "all detected". Ticking nothing produces an English-only workbook.
- Both English and locale values are read with the same regex parser used for the Merge fallback, so `<![CDATA[…]]>` wrappers are unwrapped while Android escapes (`\'`, `\n`, `&amp;`) are left as-is.
- Each locale file is parsed once and cached for the whole export.
- Sheets are skipped entirely when their source selection is empty (no XML files → no `XML Strings` / `String Arrays` sheet).
- Column widths are auto-sized; the header row is bold on a grey fill.
- The export tool keeps its own persisted selection (`exportLocales`, `exportXmlFiles`, `exportAssets`, `exportAssetFields`, `exportOutputPath`) so it never disturbs the Localize tool's state. Assets promoted out of the Ignored group are remembered too.

---

## Configuration File

Everything lives in `{project}/.idea/localize-plugin.json`, human-readable and safe to commit or delete. The document is shared in memory by every panel, so saving in one tool never reverts what another tool wrote.

```json
{
  "csvAndroidOnly": "/path/android_only_strings.csv",
  "csvOverlap": "/path/overlap.csv",
  "csvArrays": "/path/android_array_strings.csv",
  "checkedLanguages": ["ko", "th"],
  "checkedXmlFiles": ["strings.xml"],
  "checkedAssets": ["whats_new", "task"],
  "assetFields": { "whats_new": ["title", "description"] },
  "generateMode": "MERGE",
  "preserveKeyOrder": true,
  "overrideNonTranslatable": false,
  "valuesDir": "",
  "assetsDir": "",
  "reportDir": "",
  "customLocaleMap": { "spanish (dịch cơm)": "es" },
  "csvMappings": {
    "/path/Localization - Missing.csv": {
      "keyColumn": "Key",
      "englishColumn": "Text",
      "languageColumns": { "Japanese (dịch cơm)": "ja" },
      "skipEmptyRows": true,
      "skipSectionRows": true
    }
  },
  "exportLocales": ["ko", "th"],
  "exportXmlFiles": ["strings.xml"],
  "exportAssets": ["whats_new"],
  "exportAssetFields": { "whats_new": ["title", "description"] },
  "exportOutputPath": "/path/localize_export.xlsx"
}
```

Empty `valuesDir` / `assetsDir` / `reportDir` means "use the default".

---

## Architecture

```
src/main/kotlin/com/paulbaker/localize/
├── action/LocalizeAction.kt           # Tools menu entry → focuses the tool window
├── config/
│   ├── LocalizeConfig.kt              # Data models: LocalizeConfig, AssetConfig, GenerateMode,
│   │                                  #   CsvMapping, LANGUAGE_LOCALE_MAP, DEFAULT_TRANSLATE_FIELDS
│   └── ConfigPersistence.kt           # File-based settings (.idea/localize-plugin.json)
├── core/
│   ├── TranslationDb.kt               # source → byKey / byEn / byArray / byPlural lookup maps
│   │                                  # Full RFC 4180 CSV parser (handles quoted newlines)
│   │                                  # loadCsvFromNormalized / loadArrayCsvFromNormalized for mapped CSVs
│   ├── CsvPreprocessor.kt             # Column mapping: autoDetect + applyMapping
│   │                                  # Rows with empty key but non-empty English are kept
│   ├── XmlGenerator.kt                # strings.xml generation (Merge + Full Replace)
│   │                                  # string / string-array / plurals, CDATA preservation
│   │                                  # per-item Merge fallback; array-scoped item lookup
│   │                                  # resolveOrder() keeps each key where it already is
│   │                                  # JSON path strings always written, never skipped
│   ├── JsonLocalizer.kt               # Recursive JSON translation + field / dataKey detection
│   │                                  # Lottie & text-free config classification (ignoreReason)
│   └── ExcelExporter.kt               # values-*/ + assets/ → .xlsx (Apache POI, 3 sheets)
├── ui/
│   ├── MainPanel.kt                   # CardLayout root: dashboard | localize | export
│   ├── DashboardPanel.kt              # Rounded hover cards for tool selection
│   ├── LocalizePanel.kt               # Localize tool — all 3 CSV mappings respected
│   ├── ExportPanel.kt                 # Export tool — locale / XML / asset pickers
│   ├── CsvMappingDialog.kt            # Column mapping UI (real JComboBox rows, no JTable editor)
│   ├── AssetConfigDialog.kt           # Field picker with live side-by-side JSON preview
│   ├── SettingsDialog.kt              # Mode + custom directory settings
│   └── LocalizeToolWindowFactory.kt   # Right-panel tool window (DumbAware) + LocalizeOutputPanel
├── LocalizeRunner.kt                  # Orchestrates Phase 1–4, writes reports
└── DevCheck.kt                        # Dev-only harness: end-to-end generator checks
```

`DevCheck` is not wired into the plugin; it covers key ordering, array item resolution and per-item Merge fallback. Run it against the compiled classes:

```bash
./gradlew compileKotlin
IJ=$(find ~/.gradle/caches -path '*ideaIC*/lib' -maxdepth 8 -type d | head -1)
CP="build/classes/kotlin/main:$IJ/*"
for j in $(find ~/.gradle/caches/modules-2 -name '*.jar' | grep -vE 'sources|javadoc' \
    | grep -E 'kotlin-stdlib-2.0.0.jar|/poi-5.2.3.jar|poi-ooxml-5.2.3.jar|poi-ooxml-lite|xmlbeans|commons-compress|commons-codec|commons-collections4|SparseBitSet|curvesapi|gson-2.10.1.jar' \
    | sort -u); do CP="$CP:$j"; done
java -cp "$CP" com.paulbaker.localize.DevCheck
```

### Key Design Decisions

**CSV pre-processing** — `CsvPreprocessor` normalizes any CSV to `[key, english, lang1, …]`. Rows with no key but with English text are preserved for English-text-based lookup. All 3 CSV inputs (including arrays) respect saved column mappings.

**Array CSV with mapping** — `loadArrayCsvFromNormalized` applies the mapping's column normalization, then the sparse "carry-forward" array-name logic, populating `byArray`.

**Array item lookup order** — an item's `name` attribute is **array-local**, not a global resource key: `formal` exists in `writing_tag_array` ("Formal") *and* `email_writing_tone` ("😊 Formal"), and often collides with a real `<string name="formal">` too. Resolution therefore starts with the array-scoped English match, and only falls back to the global key map when that entry's own English text corroborates the item. Looking the item name up globally first is what used to strip emoji off tone labels.

**Invisible-character normalization** — `normEn` drops variation selectors, zero-width characters and BOM, and folds NBSP to a plain space, before matching. Spreadsheets routinely emit a stray `U+FE0F` that `strings.xml` doesn't have, which would otherwise leave the item unmatchable and silently skip its whole array. Emoji themselves are kept, so `"💼 Business"` still never matches bare `"Business"`.

**JSON path strings** — values matching `dir/file.json` are never sourced from the CSV (which may carry the non-localized path) and never skipped. See [JSON path strings in both modes](#json-path-strings-in-both-modes).

**Merge mode** — the locale file is parsed once per run and reused for three purposes: the per-string / per-item fallback, key-order preservation, and the added/changed diff in the report. CDATA-wrapped values are re-wrapped on write-back; preserved array items are re-emitted verbatim so their original encoding survives untouched. JSON reads the existing `*_{locale}.json` as a recursive fallback tree. Full Replace always writes the output file; Merge skips writing when nothing was resolved, so a first run can't create empty files.

**`translatable="false"`** — an element carrying it is owned outright by the template: by default it is never written to a locale file and never exported for translation. The generator drops it while walking the template; the exporter filters it out of both the `XML Strings` and `String Arrays` sheets. The attribute is matched case-insensitively and tolerates spacing, since hand-edited XML varies. The opt-in override writes such an element only from a source value — never from the Merge fallback — and every element is accounted for in one of the report's two non-translatable sections.

**Lottie detection** — JSON files with `v` + `fr` + `layers` are classified as animations. Files whose sampled strings contain fewer than 2 "human sentence" values (≥8 chars, contains a space, not a URL/identifier) are classified as text-free config.

**Export parsing** — `ExcelExporter` deliberately reuses the same regex approach as the Merge fallback rather than a DOM parser, so exported text is byte-identical to what `XmlGenerator` would preserve.

**Plurals are quantity-keyed** — any source row whose key matches `name:quantity` goes to `byPlural` instead of `byKey`. Android resource names cannot contain `:`, so the pattern is unambiguous. Resolving `<plurals>` by English text alone collapses `one` and `other` whenever they share a source phrase, which silently breaks every locale that has real plural rules; `byPlural` is consulted first and English remains the fallback. The Export sheet writes exactly this key format, so a workbook converted to CSV keeps its plurals intact.

**Threading** — Generate and Export run in `Task.Backgroundable`; log lines are pushed back through `invokeLater`. Dialog previews parse on `executeOnPooledThread` with a generation counter so stale results can't overwrite newer ones.

---

## Building from Source

```bash
./gradlew buildPlugin           # → build/distributions/localize-plugin-1.0.0.zip
./gradlew runIde                # Sandbox IDE for development
./gradlew clean buildPlugin     # Clean build
```

| Requirement | Version |
|---|---|
| IntelliJ Platform | 2024.1 (`IC`), `sinceBuild = 241`, no upper bound |
| Gradle | 8.8 (wrapper included) |
| Gradle IntelliJ Plugin | 1.17.4 |
| Kotlin | 2.0.0, JVM toolchain 17 |
| Apache POI | 5.2.3 (`poi-ooxml`, Excel export) |

Gson comes from the IntelliJ Platform, so it is not declared as a dependency.

---

## Adding a New Language

1. Add a column to your CSV with any recognizable name:
   ```
   android_key,english,korean,thai,vietnamese
   ```
2. The column appears automatically as a checkbox in the Languages section.
3. If unrecognized, a text input appears — type the Android locale code (e.g. `vi`). It is remembered in `customLocaleMap`.
4. To add it permanently to auto-detection, extend `LANGUAGE_LOCALE_MAP` in `LocalizeConfig.kt`:
   ```kotlin
   "vietnamese" to "vi",
   "tiếng việt" to "vi",
   "viet"       to "vi",
   ```

---

## License

MIT License — Copyright (c) 2026 Paul Baker. See [LICENSE](LICENSE).
