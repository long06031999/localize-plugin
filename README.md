# Localize Tool — IntelliJ / Android Studio Plugin

Generates localized `strings.xml` and JSON asset files from CSV translation spreadsheets. Built for Android projects with the app structure, but adaptable to any standard Android project.

---

## Table of Contents

- [Features](#features)
- [Installation](#installation)
- [Quick Start](#quick-start)
- [CSV Format](#csv-format)
- [CSV Column Mapping](#csv-column-mapping)
- [UI Overview](#ui-overview)
- [Generate Modes](#generate-modes)
- [JSON Asset Configuration](#json-asset-configuration)
- [Settings](#settings)
- [Output Files](#output-files)
- [Exception Report](#exception-report)
- [Architecture](#architecture)
- [Building from Source](#building-from-source)
- [Adding a New Language](#adding-a-new-language)

---

## Features

| Feature | Details |
|---------|---------|
| **CSV column mapping** | Pre-process any CSV format — map non-standard headers (`Key`, `Text`, `QC Check`) to the correct roles before localization. Saved per file. |
| **Smart language detection** | Auto-detects languages from all 3 CSV inputs, merges them, deduplicates by locale code. Supports English names, ISO codes, aliases, native names, Java Locale fallback, and manual entry. |
| **XML generation** | Full support for `<string>`, `<string-array>`, `<plurals>`, CDATA, HTML tags, format strings. CDATA content is preserved in Merge mode. |
| **JSON asset generation** | Recursive translation of nested JSON at any depth. Works with `whats_new`, `task`, `topic`, and any custom asset. |
| **Merge mode** | Preserves existing translations for strings not in the current CSV — safe for incremental updates. |
| **Full Replace mode** | Overwrites output entirely from CSV. JSON path strings (`tasks_json_file_path`) are always generated with the correct locale suffix, even when no JSON assets are selected. |
| **Smart asset detection** | Auto-filters Lottie animations and non-translatable config files into a collapsible "Ignored" group. |
| **Field configuration** | Per-asset tick-box field selector with a live side-by-side JSON preview (EN vs translated reference). |
| **Exception report** | Per-locale Markdown report: missing translations, CSV conflicts, skipped arrays, unmatched JSON fields. |
| **Settings persistence** | Config saved to `.idea/localize-plugin.json`. Changing Generate Mode does not reset JSON asset selections. |
| **Dynamic plugin** | Reloads without full IDE restart (`dynamic="true"`). |

---

## Installation

### Build from source

```bash
git clone <repo-url>
cd localize-plugin
./gradlew buildPlugin
# Output: build/distributions/chatsmith-localize-plugin-1.0.0.zip
```

### Install in Android Studio

1. **Settings → Plugins → ⚙️ → Install Plugin from Disk...**
2. Select the `.zip` → **OK** → Restart plugin (not full IDE)

The **Localize** panel appears in the right sidebar.

### Development / fast iteration

```bash
./gradlew runIde   # Opens a sandboxed IDE with the plugin pre-installed
```

---

## Quick Start

1. Open your Android project in Android Studio
2. Click **Localize** in the right sidebar
3. Select your CSV files (edit icon appears once a file is chosen)
4. Languages are auto-detected and merged from all CSV files
5. Click **Generate**

---

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

> Rows with no key but with English text are valid — they contribute to English-text-based lookup.

### 2. `overlap.csv` — cross-platform shared strings *(optional)*

Same format but uses `unified_id` as key column. When a key appears in both CSVs, `android_only` takes priority.

### 3. `android_array_strings.csv` — string-array items *(optional)*

Uses **sparse format** — array name appears only on the first row of each group:

```
android_key,english,korean,arabic,thai
label_length_array,Short,짧게,قصير,สั้น
,Medium,보통,متوسط,กลาง
,Long,길게,طويل,ยาว
```

### Language detection

Headers are resolved using 4 strategies in order:

| Strategy | Examples |
|----------|---------|
| English name | `korean`, `arabic`, `thai`, `japanese` |
| ISO 639-1 code | `ko`, `ar`, `th`, `ja`, `fr` |
| Common alias | `kr` → `ko`, `jp` → `ja`, `cn` → `zh` |
| Native name | `한국어` → `ko`, `العربية` → `ar`, `ภาษาไทย` → `th` |

Languages from all 3 CSV files are **merged and deduplicated by locale code** — no duplicates even when mixing mapped and standard CSVs. Unrecognized columns show a text input for manual locale entry; mappings are persisted.

---

## CSV Column Mapping

For translation files that don't match the standard format, click the **✏️** icon next to any CSV picker (icon appears once a valid file is selected).

```
┌─ Configure CSV: Chat GPT Localization - Missing.csv ─────────────────┐
│  33 columns  ·  455 rows                                              │
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
│                           [Reset]  [Cancel]  [Apply]                 │
└───────────────────────────────────────────────────────────────────────┘
```

**Role options:**

| Role | Maps to |
|------|---------|
| `String Key` | `android_key` column |
| `String Value` | `english` column |
| `Language` + locale | translation column |
| `Skip` | ignored entirely |

Auto-detection pre-fills roles based on column names and sample values (all overridable). Saved mappings are remembered per file path and respected for all 3 CSV inputs, including the arrays CSV.

---

## UI Overview

```
┌─ Localize ────────────────────────────────────────────────────┐
│  [ Generate ]                            [ ⚙ Settings ]       │
├───────────────────────────────────────────────────────────────┤
│  ── CSV Files ─────────────────────────────────────────────── │
│  android_only_strings.csv  [path ............] [📁] [✏️]      │
│  overlap.csv (optional)    [path ............] [📁] [✏️]      │
│  array_strings.csv (opt.)  [path ............] [📁] [✏️]      │
│                                                               │
│  ── Languages  (merged from all CSV files) ──────────────── │
│  ☑ Korean (ko)   ☑ Thai (th)   ☐ Arabic (ar)               │
│                                                               │
│  ── XML Files  (from values/ directory) ─────────────────── │
│  ☑ strings.xml   ☑ ds_ob_text.xml                          │
│                                                               │
│  ── JSON Assets ─────────────────────────────────────────── │
│  ☑ whats_new       title, description    [⚙]               │
│  ☑ task            category, title       [⚙]               │
│  ▶ Ignored (3)   benefit_popup · ob_neon · lang             │
├───────────────────────────────────────────────────────────────┤
│  Log                                                          │
│  Phase 1: Loading CSVs...   ✓ android_only: 1647 rows        │
│  ── Locale: th ────────────────────────────────────────────── │
│  Phase 2: Generating XML for th...  [th] strings.xml ✓       │
│  ✅ Done.                                                     │
└───────────────────────────────────────────────────────────────┘
```

**Behaviors:**
- ✏️ edit icon only appears when a valid CSV file is selected
- Changing Generate Mode does **not** reset JSON asset tick state
- Ignored group contains Lottie animations and non-translatable config files; click **[+ Add]** to promote any to the main list
- Deselecting all JSON assets still generates correct JSON path strings in `strings.xml`

---

## Generate Modes

Set in **⚙ Settings → Generate Mode**.

### Merge *(default — recommended)*

For each string:
1. Found in CSV → use CSV translation
2. Not in CSV, existing output has it → **preserve existing translation**
3. Not in CSV, no existing → skip (reported as missing)

For JSON path strings (`tasks_json_file_path` etc.): always preserved from existing file.

Safe for incremental updates. Run as often as needed.

### Full Replace

Output contains **only** what the CSV provides. The output file is always written (even if CSV covers fewer strings than the existing file).

For JSON path strings: always generated with the correct locale suffix (e.g., `task/tasks_th.json`), even when no JSON assets are selected. This ensures `strings.xml` is never broken.

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

- Fields auto-detected by comparing base JSON with `_ko.json` reference
- Tick/untick fields to see live merged preview (background thread — no lag)
- Supports **nested JSON** at any depth
- Language dropdown switches the reference preview

---

## Settings

Click **⚙** (top-right of the panel):

| Setting | Default | Purpose |
|---------|---------|---------|
| Generate Mode | Merge | Merge vs Full Replace |
| Values dir | `app/src/main/res/values` | XML template source + locale output parent |
| Assets dir | `app/src/main/assets` | JSON assets scan root |
| Report dir | project root | Where `localize_report_*.md` is written |

Changing the mode **does not** reset JSON asset selections. Custom directories only trigger a re-scan of the affected section (XML or JSON assets).

---

## Output Files

| Template | Generated (example: `th`) |
|----------|--------------------------|
| `values/strings.xml` | `values-th/strings.xml` |
| `values/ds_ob_text.xml` | `values-th/ds_ob_text.xml` |
| `assets/whats_new/whats_new.json` | `assets/whats_new/whats_new_th.json` |
| `assets/task/tasks.json` | `assets/task/tasks_th.json` |
| Any selected JSON asset | `assets/{dir}/{base}_{locale}.json` |

**JSON path strings** in `strings.xml` (e.g., `tasks_json_file_path`):
- Asset **selected** → localized path generated by dynamic detection
- Asset **not selected**, Merge → preserved from existing `values-th/strings.xml`
- Asset **not selected**, Full Replace → localized path generated from pattern (always correct)

---

## Exception Report

`localize_report_{locale}.md` written after each generation:

```markdown
## Summary
| Metric                               | Count |
|--------------------------------------|-------|
| XML strings written (from CSV)       | 1016  |
| XML strings preserved (Merge)        | 0     |
| XML strings skipped (no translation) | 2     |
| JSON files written                   | 6     |
| JSON fields unmatched (kept English) | 21    |
| CSV conflicts                        | 24    |
| Skipped string-arrays                | 2     |
```

**CSV conflicts** — same key in both `android_only` and `overlap` with different values; `android_only` always wins.

**JSON fields not matched** — grouped by field name and count (not per-item) for easy triage.

**Skipped string-arrays** — arrays where at least one item had no translation; the whole array is skipped to avoid partial localization.

---

## Architecture

```
src/main/kotlin/com/paulbaker/localize/
├── action/LocalizeAction.kt           # Tools menu entry
├── config/
│   ├── LocalizeConfig.kt              # Data models: LocalizeConfig, AssetConfig,
│   │                                   #   GenerateMode, CsvMapping
│   └── ConfigPersistence.kt           # File-based settings (.idea/localize-plugin.json)
├── core/
│   ├── TranslationDb.kt               # CSV → byKey / byEn / byArray lookup maps
│   │                                   # Full RFC 4180 CSV parser (handles quoted newlines)
│   │                                   # loadArrayCsvFromNormalized for mapped sparse CSVs
│   ├── CsvPreprocessor.kt             # Column mapping: auto-detect + applyMapping
│   │                                   # Rows with empty key but non-empty English are kept
│   ├── XmlGenerator.kt                # strings.xml generation (Merge + Full Replace)
│   │                                   # JSON path strings always written, never skipped
│   │                                   # CDATA preserved in Merge fallback
│   └── JsonLocalizer.kt               # Recursive JSON translation + localizability check
│                                       # Merge mode falls back to existing output file
├── ui/
│   ├── LocalizePanel.kt               # Main panel — all 3 CSV mappings respected
│   ├── CsvMappingDialog.kt            # Column mapping UI (real JComboBox rows, no JTable)
│   ├── AssetConfigDialog.kt           # Field picker with live JSON preview
│   ├── SettingsDialog.kt              # Mode + custom directory settings
│   └── LocalizeToolWindowFactory.kt   # Right-panel tool window (DumbAware)
└── LocalizeRunner.kt                  # Orchestrates Phase 1–4, writes reports
```

### Key Design Decisions

**CSV pre-processing** — `CsvPreprocessor` normalizes any CSV to `[key, english, lang1, ...]`. Rows with no key but with English text are preserved for English-text-based lookup. All 3 CSV inputs (including arrays) respect saved column mappings.

**Array CSV with mapping** — `loadArrayCsvFromNormalized` applies the mapping's column normalization then processes with the sparse "carry-forward" array name logic, populating `byArray`.

**JSON path strings** — strings like `tasks_json_file_path` whose value matches `dir/file.json` are never sourced from the CSV (which may contain non-localized paths). They are handled by: (1) dynamic asset detection if asset is selected, (2) Merge fallback from existing file, or (3) auto-generated localized path from pattern — never skipped.

**Merge mode** — XML reads `values-{locale}/*.xml` before generating. CDATA-wrapped values are re-wrapped correctly on write-back. JSON reads the existing `*_{locale}.json` as a recursive fallback tree. Full Replace always writes the output file.

**Lottie detection** — JSON files with `v` + `fr` + `layers` (Lottie signature) are auto-classified as non-localizable.

---

## Building from Source

```bash
./gradlew buildPlugin           # → build/distributions/*.zip
./gradlew runIde                # Sandbox IDE for development
./gradlew clean buildPlugin     # Clean build
```

Targets **IntelliJ Platform 2024.1** (Android Studio Koala+). Requires JDK 17+.

---

## Adding a New Language

1. Add a column to your CSV with any recognizable name:
   ```
   android_key,english,korean,thai,vietnamese
   ```
2. The column appears automatically as a checkbox in the Languages section.
3. If unrecognized, a text input appears — type the Android locale code (e.g., `vi`).
4. To add permanently to auto-detection, add to `LANGUAGE_LOCALE_MAP` in `LocalizeConfig.kt`:
   ```kotlin
   "vietnamese" to "vi",
   "tiếng việt" to "vi",
   "viet"       to "vi",
   ```

---

## License

MIT License — Copyright (c) 2026 Paul Baker. See [LICENSE](LICENSE).
