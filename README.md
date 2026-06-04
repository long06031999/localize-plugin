# Chatsmith Localize Plugin

An Android Studio plugin that generates localized `strings.xml` and JSON asset files directly from CSV translation spreadsheets. Built for the **Chatsmith** Android project but adaptable to any Android app with the same structure.

---

## Table of Contents

- [Features](#features)
- [Installation](#installation)
- [Quick Start](#quick-start)
- [CSV Format](#csv-format)
- [UI Walkthrough](#ui-walkthrough)
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
| **Smart language detection** | Auto-detects languages from CSV headers using 3 strategies: English names, ISO codes, native names, Java Locale fallback, and manual entry for unknowns |
| **XML generation** | Full support for `<string>`, `<string-array>`, `<plurals>`, CDATA, HTML tags (`<b>`, `<font>`), format strings (`%s`, `%d`, `%1$s`) |
| **JSON asset generation** | Recursive translation of nested JSON at any depth — works with `whats_new`, `highlight_features`, `tasks`, `topic`, `task_assistant`, and any custom JSON asset |
| **Merge mode** | Preserves existing translations for strings not covered by the current CSV — safe for incremental updates |
| **Smart asset filtering** | Automatically detects and hides Lottie animation files and non-translatable config files |
| **Field configuration** | Per-asset tick-box field selector with a live side-by-side JSON preview (EN vs translated) |
| **Exception report** | Per-locale Markdown report of missing translations, CSV conflicts, skipped arrays, and unmatched JSON fields |
| **Dynamic plugin** | Reloads without full IDE restart after installation |

---

## Installation

### Option A — Install pre-built ZIP

1. Download `chatsmith-localize-plugin-*.zip` from Releases
2. **Settings → Plugins → ⚙️ → Install Plugin from Disk...**
3. Select the `.zip` file → **OK** → Restart plugin (not full IDE)

### Option B — Build from source

```bash
git clone <repo-url>
cd chatsmith-localize-plugin
./gradlew buildPlugin
# Output: build/distributions/chatsmith-localize-plugin-1.0.0.zip
```

Then install the ZIP as above.

### Development / fast iteration

```bash
./gradlew runIde   # Opens a sandboxed IDE with the plugin pre-installed
```

---

## Quick Start

1. Open your Android project in Android Studio
2. Click the **Localize** icon in the right sidebar (or **Tools → Localize...**)
3. Browse to your CSV files
4. Languages are auto-detected from CSV headers
5. Click **Generate**

---

## CSV Format

The plugin accepts three CSV files:

### 1. `android_only_strings.csv` — main strings

```
android_key,english,korean,arabic,thai
edit_message_hint,Talk with Chat Smith,Chat Smith와 대화하기,تحدث مع Chat Smith,คุยกับ Chat Smith
cancel,Cancel,취소,إلغاء,ยกเลิก
```

| Column | Required | Description |
|--------|----------|-------------|
| `android_key` | ✓ | Matches `name` attribute in `strings.xml` |
| `english` | ✓ | Used as fallback for English-text matching |
| *language columns* | ✓ | One column per target language |

### 2. `overlap.csv` — cross-platform shared strings *(optional)*

Same format but key column is `unified_id`. When a key appears in both CSVs, `android_only` takes priority.

### 3. `android_array_strings.csv` — string-array items *(optional)*

Uses **sparse format**: the array name appears only on the first row of each group.

```
android_key,english,korean,arabic,thai
label_length_array,Short,짧게,قصير,สั้น
,Medium,보통,متوسط,กลาง
,Long,길게,طويل,ยาว
writing_tag_array,Formal,격식체,رسمي,เป็นทางการ
,Informal,비격식체,غير رسمي,ไม่เป็นทางการ
```

### Language header detection

Headers are matched using 4 strategies in order:

| Strategy | Examples |
|----------|---------|
| English name | `korean`, `arabic`, `thai` |
| ISO 639-1 code | `ko`, `ar`, `th`, `ja`, `fr` |
| Common alias | `kr` → `ko`, `jp` → `ja`, `cn` → `zh` |
| Native name | `한국어` → `ko`, `العربية` → `ar`, `ภาษาไทย` → `th` |

If a column is not recognized, it appears as **"Unrecognized"** with a text input to manually specify the Android locale code (e.g., `vi`). Manual mappings are persisted.

---

## UI Walkthrough

```
┌─ Localize (right sidebar) ────────────────────────────────┐
│  [ Generate ]                            [ ⚙ Settings ]   │
├───────────────────────────────────────────────────────────┤
│  ── CSV Files ───────────────────────────────────────────  │
│  android_only_strings.csv  [/path/to/file.csv] [Browse]   │
│  overlap.csv (optional)    [/path/to/file.csv] [Browse]   │
│  array_strings.csv (opt.)  [/path/to/file.csv] [Browse]   │
│                                                           │
│  ── Languages  (detected from CSV) ─────────────────────  │
│  ☑ Korean (ko)   ☑ Thai (th)   ☐ Arabic (ar)             │
│                                                           │
│  ── XML Files  (from values/) ──────────────────────────  │
│  ☑ strings.xml   ☑ ds_ob_text.xml                        │
│                                                           │
│  ── JSON Assets ─────────────────────────────────────────  │
│  ☑ whats_new       title, description    [⚙]             │
│  ☑ task            category, title       [⚙]             │
│  ▶ Ignored (3)                                           │
│      benefit_popup  ⚠ animation (Lottie)   [+ Add]       │
├───────────────────────────────────────────────────────────┤
│  Log                                                      │
│  Phase 1: Loading CSVs...                                 │
│    ✓ android_only: 1647 rows                              │
│    ✓ overlap: 560 rows                                    │
│    DB: 2111 keys, 1773 English phrases                    │
│  ─── Locale: th ────────────────────────────────────────  │
│  Phase 2: Generating XML for th...                        │
│    [th] strings.xml → 1016 written, 0 skipped            │
│  Phase 3: Generating JSON for th...                       │
│    [th] whats_new_th.json                                 │
│  ✅ Done.                                                 │
└───────────────────────────────────────────────────────────┘
```

### JSON Assets — Ignored group

Assets that are automatically detected as non-localizable are hidden in a collapsible **Ignored** group:
- **Lottie animations** — JSON with `v` + `fr` + `layers` fields, or filenames starting with `anim_`
- **Config files** — JSON with no multi-word user-facing text strings

Click **[+ Add]** to promote an ignored asset to the main list if needed.

---

## Generate Modes

Set in **⚙ Settings → Generate Mode**.

### Merge *(default — recommended)*

For each string/field:
1. Found in CSV → use CSV translation
2. Not in CSV, but existing output file has it → **preserve existing translation**
3. Not in CSV, no existing output → English fallback

Safe for incremental updates. Run as often as needed — only new/changed strings are affected.

### Full Replace

Output contains only what is in the CSV. Strings not in the CSV are lost. Use for initial setup or when you want a clean slate.

---

## JSON Asset Configuration

Click **⚙** next to any asset to open the field configuration dialog:

```
┌─ Configure: whats_new ────────────────────────────────────────────┐
│                                                                    │
│  Fields to translate:  ☑ title  ☑ description  ☐ resource  ...   │
│                                                    Preview: [ko ▼] │
│                                                                    │
│  ┌── Original (EN) ──────────────┐  ┌── Preview (ko) ───────────┐ │
│  │ {                             │  │ {                          │ │
│  │   "id": 22,                   │  │   "id": 22,                │ │
│  │   "title": "Meet Claude",     │  │   "title": "Claude를 만..." │ │
│  │   "description": "Experience..│  │   "description": "더 자연..│ │
│  │   "resource": "what_news_..."  │  │   "resource": "what_news.."│ │
│  │ }                             │  │ }                          │ │
│  └───────────────────────────────┘  └────────────────────────────┘ │
│                                                 [Cancel]  [Save]   │
└────────────────────────────────────────────────────────────────────┘
```

**How it works:**
- Fields are auto-detected by scanning the JSON structure recursively (up to 8 levels deep)
- Fields detected as changed between the English base and `_ko.json` reference are pre-ticked
- The right panel shows a **live merged preview** — tick/untick fields to see the result instantly
- Supports **nested JSON** at any depth (e.g., `question_answer[].question.content`)
- Language selector switches the reference preview between available translated files
- Selections are persisted per-asset between sessions

---

## Settings

Click **⚙ Settings** in the top bar:

```
┌─ Settings ──────────────────────────────────────────────┐
│                                                          │
│  Generate Mode                                          │
│    ⊙ Merge (safe)                                       │
│      Keep existing translations for strings not in CSV  │
│    ○ Full Replace                                       │
│      Overwrite entirely from CSV                        │
│                                                          │
│  ── Directories ─────────────────────────────────────── │
│  Values dir:   [app/src/main/res/values    ] [Browse]   │
│  Assets dir:   [app/src/main/assets        ] [Browse]   │
│  Report dir:   [{project root}             ] [Browse]   │
│                                                          │
│                          [Reset to defaults]  [Save]    │
└──────────────────────────────────────────────────────────┘
```

| Setting | Default | Purpose |
|---------|---------|---------|
| Generate Mode | Merge | Merge vs Full Replace |
| Values dir | `app/src/main/res/values` | XML template source + output parent |
| Assets dir | `app/src/main/assets` | JSON assets scan root |
| Report dir | project root | Where `localize_report_*.md` is written |

**Custom directories** are useful for Android product flavors (e.g., point Assets dir to `app/src/dev/assets` to localize dev-specific assets).

Settings are persisted to `.idea/localize-plugin.json`.

---

## Output Files

| Input template | Generated (example: locale `th`) |
|----------------|----------------------------------|
| `values/strings.xml` | `values-th/strings.xml` |
| `values/ds_ob_text.xml` | `values-th/ds_ob_text.xml` |
| `assets/whats_new/whats_new.json` | `assets/whats_new/whats_new_th.json` |
| `assets/task/tasks.json` | `assets/task/tasks_th.json` |
| `assets/{dir}/{file}.json` | `assets/{dir}/{file}_{locale}.json` |

JSON path strings in `strings.xml` (e.g., `tasks_json_file_path`) are automatically updated to point to the localized file — no hardcoding needed.

---

## Exception Report

After each generation, `localize_report_{locale}.md` is created in the report directory:

```markdown
# Localization Report: th

## Summary
| Metric                              | Count |
|-------------------------------------|-------|
| XML strings written (from CSV)      | 1016  |
| XML strings preserved (Merge)       | 0     |
| XML strings skipped (no translation)| 0     |
| JSON files written                  | 6     |
| JSON fields unmatched (kept English)| 21    |
| CSV conflicts                       | 24    |
| Skipped string-arrays               | 2     |

## CSV Conflicts (android_only used)
| Key | Used | Ignored |
...

## JSON Fields Not Matched (kept English)
| File.Field              | English Value | Times |
|-------------------------|---------------|:-----:|
| task_assistant.category | Study         | 9     |
...

## Skipped String-Arrays
| Array           | Found / Total | Missing items |
|-----------------|:-------------:|---------------|
| use_ai_for      | 1 / 9         | `work_tasks`, ... |
```

**CSV Conflicts** — same key in both `android_only` and `overlap` CSVs with different values. `android_only` always wins.

**JSON Fields Not Matched** — field value not found in CSV translation DB. Grouped and counted for easy triage.

**Skipped String-Arrays** — arrays where at least one item had no translation. The entire array is skipped to avoid partial localization (Android requires complete arrays).

---

## Architecture

```
src/main/kotlin/com/vulcanlabs/localize/
├── action/
│   └── LocalizeAction.kt            # Tools menu entry — opens the tool window
│
├── config/
│   ├── LocalizeConfig.kt            # Data models: LocalizeConfig, AssetConfig, GenerateMode
│   └── ConfigPersistence.kt         # File-based settings (.idea/localize-plugin.json)
│
├── core/
│   ├── TranslationDb.kt             # Loads CSVs → byKey / byEn / byArray lookup maps
│   │                                 # Full CSV parser handling multi-line quoted fields
│   ├── XmlGenerator.kt              # strings.xml generation (Merge + Full Replace)
│   │                                 # Dynamic JSON path detection for path template strings
│   └── JsonLocalizer.kt             # Recursive JSON translation + localizability detection
│                                     # Merge mode: falls back to existing output file
├── ui/
│   ├── LocalizePanel.kt             # Main embedded panel (CSV pickers + checkboxes + log)
│   │                                 # Fixed top bar (Generate + Settings) + scrollable content
│   ├── AssetConfigDialog.kt         # Per-asset field configuration with live JSON preview
│   │                                 # Background loading, generation counter for locale switching
│   ├── SettingsDialog.kt            # Settings dialog (Generate Mode + custom directories)
│   └── LocalizeToolWindowFactory.kt # Registers the right-panel tool window (DumbAware)
│                                     # LocalizeOutputPanel with theme-aware colors
└── LocalizeRunner.kt                # Orchestrates the full pipeline (Phase 1→4)
                                      # Writes localize_report_{locale}.md
```

### Key Design Decisions

**CSV parsing** — `TranslationDb.parseCsvFull()` reads the entire file and handles newlines inside quoted fields (RFC 4180 compliant). This fixes a class of bugs where CSV values containing `\n` (e.g., Arabic with embedded newlines) were silently dropped.

**Translation lookup** — two-level lookup: `byKey` (Android key name) first, then `byEn` (English text) as fallback. For `<string-array>` items, only `byKey` is used to avoid false matches from other contexts (e.g., `"Long"` matching a different string).

**Merge mode** — XML reads the existing `values-{locale}/*.xml` before generating; missing keys fall back to existing values. JSON reads the existing `*_{locale}.json` and passes it recursively through `translateElement()` as a fallback tree.

**Lottie detection** — JSON files with `v` + `fr` + `layers` fields (Lottie format signature) are automatically classified as non-localizable and placed in the Ignored group.

---

## Building from Source

```bash
# Requirements: JDK 17+, no other dependencies

# Build the plugin ZIP
./gradlew buildPlugin
# → build/distributions/chatsmith-localize-plugin-1.0.0.zip

# Run in a sandboxed IDE (for development)
./gradlew runIde

# Clean build
./gradlew clean buildPlugin
```

The plugin targets **IntelliJ Platform 2024.1** (compatible with Android Studio Koala and later).

---

## Adding a New Language

1. Add a column to your CSV files with the language name or ISO code:
   ```
   android_key,english,korean,thai,vietnamese
   ```

2. The column will be auto-detected by the plugin (no code changes needed for most languages).

3. If the column name is not recognized, the plugin shows a text input — type the Android locale code (e.g., `vi`) and press Enter.

4. To add it permanently to the auto-detection list, add an entry to `LANGUAGE_LOCALE_MAP` in `LocalizeConfig.kt`:
   ```kotlin
   "vietnamese" to "vi",
   "tiếng việt" to "vi",   // native name
   "viet" to "vi",          // alias
   ```

---

## Requirements

- **Android Studio** 2024.1 (Koala) or later
- **JDK 17+** for building
- No Python required — all logic is implemented in Kotlin
- No external library dependencies beyond the IntelliJ Platform SDK

---

## License

MIT License — Copyright (c) 2026 Paul Baker

See [LICENSE](LICENSE) for full text.
