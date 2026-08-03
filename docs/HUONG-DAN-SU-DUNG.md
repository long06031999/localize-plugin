# Localize Tool — Hướng dẫn sử dụng

Hướng dẫn dành cho người dùng plugin.

---

## Mục lục

- [1. Plugin này làm gì](#1-plugin-này-làm-gì)
- [2. Yêu cầu](#2-yêu-cầu)
- [3. Cài đặt](#3-cài-đặt)
- [4. Mở plugin](#4-mở-plugin)
- [5. Màn hình chính](#5-màn-hình-chính)
- [6. Chuẩn bị file CSV](#6-chuẩn-bị-file-csv)
- [7. Tool 1 — Localize from CSV](#7-tool-1--localize-from-csv)
- [8. Khi CSV không đúng format: Column Mapping](#8-khi-csv-không-đúng-format-column-mapping)
- [9. Cấu hình JSON asset](#9-cấu-hình-json-asset)
- [10. Settings](#10-settings)
- [11. Đọc report](#11-đọc-report)
- [12. Tool 2 — Export to Excel](#12-tool-2--export-to-excel)
- [13. Các quy trình thường dùng](#13-các-quy-trình-thường-dùng)
- [14. Xử lý sự cố](#14-xử-lý-sự-cố)
- [15. Câu hỏi thường gặp](#15-câu-hỏi-thường-gặp)

---

## 1. Plugin này làm gì

Plugin có hai chiều làm việc:

**Localize from CSV** — Bạn có file dịch (CSV) từ team dịch. Plugin đọc file đó rồi tự sinh ra:
- `values-ko/strings.xml`, `values-th/strings.xml`… cho từng ngôn ngữ
- `whats_new_ko.json`, `tasks_th.json`… cho các file JSON trong `assets/`
- Một file report cho từng ngôn ngữ, liệt kê những gì thiếu / sai / bị bỏ qua

**Export to Excel** — Ngược lại: đọc những gì **đang có** trong project rồi xuất ra một file Excel `.xlsx` để gửi cho team dịch. File này có đủ tiếng Anh gốc và các bản dịch hiện tại, nên translator thấy ngay ô nào còn trống.

Điểm quan trọng: plugin **không bao giờ dịch tự động**. Nó chỉ chuyển bản dịch do con người cung cấp vào đúng chỗ trong project, và báo lại những gì nó không tìm được.

---

## 2. Yêu cầu

| | |
|---|---|
| IDE | Android Studio (bản Koala trở lên) hoặc IntelliJ IDEA 2024.1+ |
| Project | Project Android có thư mục `res/values/` |
| File dịch | CSV (cho Localize) |

Không cần cài thêm gì khác.

---

## 3. Cài đặt

### Cài lần đầu

1. Lấy file `localize-plugin-1.0.0.zip`
2. Trong Android Studio mở **Settings** (`⌘,` trên macOS / `Ctrl+Alt+S` trên Windows)
3. Vào **Plugins**
4. Bấm icon **⚙** ở cạnh ô tìm kiếm → chọn **Install Plugin from Disk...**
5. Chọn file `.zip` vừa tải → **OK**
6. Bấm **Restart IDE** nếu IDE yêu cầu

> Plugin hỗ trợ nạp động (dynamic), nên thường chỉ cần **Restart plugin** chứ không phải khởi động lại cả Android Studio.

### Kiểm tra đã cài thành công

Nhìn thanh dọc bên **phải** cửa sổ Android Studio, phải thấy một tab tên **Localize**. Nếu không thấy, xem [mục 4](#4-mở-plugin).

### Cập nhật lên bản mới

Làm lại đúng các bước trên với file `.zip` mới. Không cần gỡ bản cũ — IDE tự ghi đè.

Cấu hình của bạn (đường dẫn file CSV, ngôn ngữ đã tick, mapping cột…) **không bị mất** khi cập nhật, vì nó được lưu trong project chứ không trong plugin.

### Gỡ cài đặt

**Settings → Plugins** → tìm **Localize Tool** → bấm mũi tên cạnh nút → **Uninstall**.

---

## 4. Mở plugin

Có 2 cách:

- Bấm tab **Localize** ở thanh dọc bên phải
- Hoặc menu **Tools → Localize...**

Nếu không thấy tab đâu cả: menu **View → Tool Windows → Localize**.

Panel này khá nhiều thông tin, nên bạn có thể kéo rộng ra hoặc bấm đúp vào tên tab để mở toàn màn hình cho dễ làm việc.

---

## 5. Màn hình chính

Khi mở lên bạn thấy màn hình chọn tool:

```
                    Choose a tool

  ┌────────────────────────────────────────────────┐
  │ ⟳   Localize from CSV                          │
  │     Generate localized strings.xml and         │
  │     JSON assets from translation spreadsheets  │
  └────────────────────────────────────────────────┘
  ┌────────────────────────────────────────────────┐
  │ ↓   Export to Excel                            │
  │     Export all strings and JSON assets         │
  │     to Excel format for translation            │
  └────────────────────────────────────────────────┘
```

Bấm vào thẻ để vào tool. Trong mỗi tool có nút **←** ở góc trên trái để quay lại màn hình này.

---

## 6. Chuẩn bị file CSV

Plugin nhận **tối đa 3 file CSV**. Chỉ file đầu tiên là bắt buộc.

### File 1 — String thường (bắt buộc)

Thường tên là `android_only_strings.csv`.

```
android_key,english,korean,thai,arabic
cancel,Cancel,취소,ยกเลิก,إلغاء
ok,OK,확인,ตกลง,موافق
```

| Cột | Bắt buộc | Ý nghĩa |
|---|---|---|
| `android_key` | Không | Tên key trong `strings.xml`. Để trống được nếu có cột `english`. |
| `english` | **Có** | Câu tiếng Anh gốc. Plugin dùng cột này để dò khi key trống hoặc không khớp. |
| Các cột ngôn ngữ | **Có** | Mỗi ngôn ngữ một cột |

> **Dòng không có key nhưng có tiếng Anh vẫn dùng được.** Plugin sẽ dò theo nội dung tiếng Anh. Rất tiện khi team dịch không biết key.

### File 2 — String dùng chung nhiều nền tảng (tuỳ chọn)

Thường tên `overlap.csv`. Giống file 1 nhưng cột key tên là `unified_id`.

Nếu một key có ở **cả hai file** mà giá trị khác nhau → plugin lấy theo file 1, và ghi lại vào report ở mục **CSV Conflicts** để bạn kiểm tra.

### File 3 — String array (tuỳ chọn)

Thường tên `android_array_strings.csv`. File này dùng **format thưa**: tên array chỉ ghi ở dòng đầu của nhóm, các dòng sau để trống.

```
android_key,english,korean,thai
label_length_array,Short,짧게,สั้น
,Medium,보통,กลาง
,Long,길게,ยาว
writing_tag_array,Formal,격식체,เป็นทางการ
,Informal,비격식체,ไม่เป็นทางการ
```

### Đặt tên cột ngôn ngữ thế nào

Plugin tự hiểu được khá nhiều dạng:

| Kiểu | Ví dụ |
|---|---|
| Tên tiếng Anh | `korean`, `thai`, `arabic`, `japanese`, `vietnamese` |
| Mã ISO | `ko`, `th`, `ar`, `ja`, `vi`, `fr` |
| Viết tắt quen dùng | `kr` → `ko`, `jp` → `ja`, `cn` → `zh` |
| Tên bản địa | `한국어`, `ภาษาไทย`, `العربية`, `Tiếng Việt` |

Nếu tên cột lạ quá (ví dụ `Spanish (dịch cơm)`), plugin sẽ không tự hiểu — lúc đó dùng [Column Mapping](#8-khi-csv-không-đúng-format-column-mapping).

### Vài lưu ý về file CSV

- Lưu file với encoding **UTF-8**, nếu không tiếng Hàn / Thái / Ả Rập sẽ thành ký tự lạ
- Ô có dấu phẩy hoặc xuống dòng phải nằm trong dấu ngoặc kép — Excel và Google Sheets tự làm việc này khi bạn xuất CSV
- Ô để trống nghĩa là "chưa có bản dịch". Plugin sẽ bỏ qua và báo trong report, chứ không ghi tiếng Anh vào file ngôn ngữ khác
- **Emoji phải giữ nguyên.** Nếu trong `strings.xml` là `😊 Formal` thì trong CSV cũng phải là `😊 Formal`, không được bỏ emoji đi

---

## 7. Tool 1 — Localize from CSV

### Toàn cảnh panel

```
┌────────────────────────────────────────────────────────┐
│ [←] [ Generate ]                              [ ⚙ ]    │  ← thanh trên
├────────────────────────────────────────────────────────┤
│ ── CSV Files ───────────────────────────────────────── │
│ android_only_strings.csv   [.........] [📁] [✏️]        │
│ overlap.csv (optional)     [.........] [📁] [✏️]        │
│ array_strings.csv (opt.)   [.........] [📁] [✏️]        │
│                                                        │
│ ── Languages ───────────────────────────────────────── │
│ ☑ Korean (ko)   ☑ Thai (th)   ☐ Arabic (ar)          │
│                                                        │
│ ── XML Files ───────────────────────────────────────── │
│ ☑ strings.xml   ☑ ds_ob_text.xml                     │
│                                                        │
│ ── JSON Assets ─────────────────────────────────────── │
│ ☑ whats_new      title, description          [⚙]      │
│ ☑ task           category, title             [⚙]      │
│ ▶ Ignored (3)                                          │
├────────────────────────────────────────────────────────┤
│ Log                                                    │
│ Phase 1: Loading CSVs...                               │
│ ...                                                    │
└────────────────────────────────────────────────────────┘
```

### Các bước làm

**Bước 1 — Chọn file CSV**

Bấm icon **📁** ở dòng tương ứng để chọn file. Nếu bạn để file CSV ngay trong thư mục gốc project và tên file có chứa `android_only`, `overlap`, hoặc `array`, plugin sẽ **tự điền sẵn** lần đầu mở.

Icon **✏️** bên cạnh chỉ sáng lên sau khi đã chọn được file hợp lệ.

**Bước 2 — Kiểm tra mục Languages**

Ngay khi chọn file, plugin đọc dòng tiêu đề và hiện ra các ngôn ngữ nó nhận ra, dạng `Korean (ko)`.

- Ngôn ngữ được gộp từ **cả 3 file CSV**, và lọc trùng theo mã ngôn ngữ — không bao giờ hiện `ko` hai lần
- Tick những ngôn ngữ bạn muốn sinh lần này. Không cần làm hết một lượt
- Nếu có cột plugin không hiểu, nó hiện một ô nhập nhỏ: gõ mã ngôn ngữ Android (ví dụ `vi`) rồi Enter. Plugin sẽ **nhớ** mã đó cho lần sau
- Nếu có quá nhiều cột lạ, plugin không hiện ô nhập mà báo `N unrecognized columns — click ✏ to map`. Lúc này dùng [Column Mapping](#8-khi-csv-không-đúng-format-column-mapping)

**Bước 3 — Chọn file XML cần sinh**

Danh sách này lấy từ thư mục `values/`. Plugin tự loại ra:
- Các file không chứa string (`colors.xml`, `dimens.xml`, `styles.xml`, `themes.xml`…)
- File nào không có thẻ `<string` nào bên trong

**Bước 4 — Chọn JSON asset cần sinh**

Xem [mục 9](#9-cấu-hình-json-asset).

Nếu project bạn không dùng JSON asset thì bỏ tick hết cũng được — plugin vẫn ghi đúng đường dẫn JSON trong `strings.xml`, không làm hỏng gì.

**Bước 5 — Kiểm tra Settings**

Bấm **⚙** ở góc trên phải. Lần đầu dùng nên xem qua [mục 10](#10-settings) một lượt, nhất là **Generate Mode**.

**Bước 6 — Bấm Generate**

Quá trình chạy ở chế độ nền, bạn vẫn dùng IDE bình thường được. Khung **Log** phía dưới hiện tiến trình theo 4 giai đoạn:

```
Phase 1: Loading CSVs...
  ✓ android_only: loaded
  DB: 1647 unique keys, 1602 English phrases
  Mode: merge
  Key order: preserve existing positions

─── Locale: th ───────────────────────────
Phase 2: Generating XML for th...
  [th] strings.xml → 1016 written, 2 skipped (+4 new)
Phase 3: Generating JSON for th...
  [th] tasks_th.json
Phase 4: Writing reports...
  [th] Report → localize_report_th.md

✅ Done.
```

Ý nghĩa các số ở dòng kết quả:

| | |
|---|---|
| `written` | Số string lấy được bản dịch từ CSV |
| `skipped` | Số string không có bản dịch → **không** ghi vào file |
| `preserved` | (chế độ Merge) Số string giữ lại bản dịch cũ đang có |
| `+N new` | Số key mới xuất hiện trong ngôn ngữ này |

**Bước 7 — Đọc report và kiểm tra kết quả**

Xem [mục 11](#11-đọc-report). Sau đó dùng `git diff` để xem plugin đã sửa đúng những gì bạn mong đợi chưa.

### File được sinh ra ở đâu

| File gốc | File được sinh (ví dụ tiếng Thái) |
|---|---|
| `values/strings.xml` | `values-th/strings.xml` |
| `values/ds_ob_text.xml` | `values-th/ds_ob_text.xml` |
| `assets/whats_new/whats_new.json` | `assets/whats_new/whats_new_th.json` |
| `assets/task/tasks.json` | `assets/task/tasks_th.json` |

Report: `localize_report_th.md` ở thư mục gốc project (đổi được trong Settings).

---

## 8. Khi CSV không đúng format: Column Mapping

Dùng khi file dịch có tiêu đề cột không theo chuẩn — ví dụ cột key tên là `Key`, cột tiếng Anh tên là `Text`, và có thêm mấy cột `QC Check` không liên quan.

Bấm icon **✏️** cạnh file CSV đó.

```
┌─ Configure CSV: Localization - Missing.csv ──────────────────┐
│ 33 columns · 455 rows                                        │
│ CSV Column            Role            Locale  Sample         │
│ ──────────────────────────────────────────────────────────── │
│ Key                  [String Key  ▼]         cancel          │
│ Text                 [String Value▼]         Cancel          │
│ Spanish (dịch cơm)   [Language    ▼]  [es]   Cancelar        │
│ QC Check             [Skip        ▼]                         │
│ Japanese (dịch cơm)  [Language    ▼]  [ja]   キャンセル        │
│ ...                                                          │
│ Filters: ☑ Skip empty rows  ☑ Skip section-only rows        │
│                                                              │
│ Preview — first 5 rows after mapping:                        │
│ Key    | Value  | es       | ja                              │
│ cancel | Cancel | Cancelar | キャンセル                        │
│                                                              │
│ ☑ Remember this mapping for this file                        │
│ Reset to auto-detect          [Cancel]  [Apply]              │
└──────────────────────────────────────────────────────────────┘
```

### Bốn lựa chọn Role

| Role | Nghĩa |
|---|---|
| **String Key** | Cột này là tên key. Chọn đúng **một** cột. |
| **String Value** | Cột này là câu tiếng Anh gốc. Chọn đúng **một** cột. |
| **Language** | Cột này là một bản dịch. Phải gõ mã ngôn ngữ vào ô **Locale** bên cạnh (`ko`, `th`, `vi`…). |
| **Skip** | Bỏ qua hoàn toàn cột này. |

Plugin đoán trước giúp bạn: cột nào tên có `qc`, `check`, `review`, `status`, `note`, `comment` → tự đặt `Skip`. Cột nào có toàn giá trị ngắn (dưới 3 ký tự, kiểu dấu tick) → cũng `Skip`. Đuôi kiểu `(dịch cơm)`, `(MT)`, `(auto)` được bỏ đi trước khi đoán ngôn ngữ.

Đoán sai thì bạn sửa lại — plugin luôn ưu tiên lựa chọn của bạn.

### Hai filter phía dưới

| Filter | Nên bật khi |
|---|---|
| **Skip empty rows** | Luôn nên bật. Bỏ các dòng trống hoàn toàn. |
| **Skip section-only rows** | File có dòng phân mục (chỉ có 1 ô có chữ, ví dụ `=== ONBOARDING ===`). Bật để bỏ chúng. |

### Preview

Bảng **Preview** hiện 5 dòng đầu **sau khi áp mapping**. Đây là cách nhanh nhất để biết mapping đúng chưa — nếu cột `Value` hiện ra tiếng Hàn thì rõ ràng bạn chọn sai cột.

### Ghi nhớ mapping

Để nguyên tick **Remember this mapping for this file** thì lần sau mở lại file đó plugin tự áp mapping cũ, không phải làm lại. Mapping được lưu theo **đường dẫn file**, nên nếu bạn đổi tên hoặc di chuyển file thì phải map lại.

---

## 9. Cấu hình JSON asset

Plugin quét thư mục `assets/`, mỗi thư mục con có file `.json` được coi là một asset.

### Nhóm Ignored

Những file JSON không phải để dịch sẽ bị gom vào nhóm **▶ Ignored** cho gọn. Bấm vào để mở ra, mỗi dòng có ghi lý do:

| Lý do | Nghĩa |
|---|---|
| `animation (Lottie)` | File animation Lottie, không có chữ cho người đọc |
| `animation file` | Tên file bắt đầu bằng `anim_`, `animation_`, `lottie_` |
| `config (no user text)` | File cấu hình, không tìm thấy câu chữ nào đáng dịch |

Nếu plugin đoán sai và bạn thật sự muốn dịch file đó → bấm **[+ Add]** để đưa nó lên danh sách chính.

### Chọn field cần dịch

Bấm **⚙** cạnh tên asset:

```
┌─ Configure: whats_new ─────────────────  Preview: [ko ▼] ┐
│ Fields to translate: ☑ title ☑ description ☐ resource    │
│                                                          │
│ ┌── Original (EN) ─────┐  ┌── Preview (ko) ──────────┐  │
│ │ "title": "Meet…",    │  │ "title": "Claude를 만…", │  │
│ │ "description": "…",  │  │ "description": "더 자…", │  │
│ │ "resource": "img_…"  │  │ "resource": "img_…"      │  │
│ └──────────────────────┘  └──────────────────────────┘  │
│                                    [Cancel]  [Save]      │
└──────────────────────────────────────────────────────────┘
```

- Danh sách field là **toàn bộ** field dạng chữ trong file JSON, kể cả field lồng sâu bên trong
- Plugin tick sẵn những field mà nó đoán là cần dịch, bằng cách so file gốc với file `_ko.json` đang có
- Ô chọn ngôn ngữ ở góc trên phải để xem thử với ngôn ngữ khác

**Khung phải cho biết chính xác Generate sẽ ghi ra gì**, phân giải theo đúng thứ tự lúc chạy thật: CSV → file locale đang có → tiếng Anh.

| Màu ở khung phải | Nghĩa |
|---|---|
| **Xanh** | Giá trị **đã được dịch** (khác tiếng Anh) |
| **Cam** | Field được tick nhưng **vẫn còn tiếng Anh** — chưa có bản dịch |

Dòng trạng thái ngay dưới danh sách field đếm rõ từng nguồn, ví dụ:

```
12 from CSV  ·  3 kept from features_ja.json  ·  5 still English
```

Nếu file `features_{ngôn_ngữ}.json` chưa tồn tại, quá lớn, hay lỗi syntax, dòng này sẽ ghi rõ lý do (`⚠ features_ja.json not found`) chứ không im lặng hiện tiếng Anh.

> Chỉ tick những field là **câu chữ cho người dùng đọc**. Đừng tick các field kiểu `resource`, `type`, `id`, `icon` — đó là tên file hoặc mã, dịch vào là app lỗi.

File lớn hơn 300 KB sẽ không hiện preview (để panel không bị treo), nhưng vẫn được xử lý bình thường khi Generate.

---

## 10. Settings

Bấm **⚙** ở góc trên phải panel Localize.

### Generate Mode

Đây là setting quan trọng nhất. Hiểu sai có thể mất bản dịch.

| | **Merge** (mặc định) | **Full Replace** |
|---|---|---|
| String có trong CSV | Ghi bản dịch mới | Ghi bản dịch mới |
| String **không** có trong CSV, file cũ đã có bản dịch | **Giữ lại bản dịch cũ** | **Mất** |
| String không có ở đâu cả | Bỏ qua, báo trong report | Bỏ qua |

**Nên dùng Merge** cho gần như mọi trường hợp. Đặc biệt khi file CSV chỉ chứa vài string mới cần cập nhật — Merge giữ nguyên toàn bộ phần còn lại.

**Chỉ dùng Full Replace** khi CSV của bạn là nguồn đầy đủ và bạn muốn dọn sạch những string cũ không còn dùng.

Với `<string-array>` và `<plurals>`, chế độ Merge còn thông minh hơn: nếu một array có 10 item mà CSV chỉ có 9, plugin giữ lại đúng item thứ 10 từ file cũ thay vì bỏ cả array.

#### Merge áp dụng cho file JSON thế nào

Có, chế độ này áp cho cả file JSON:

- **Merge** — plugin đọc file `*_{ngôn_ngữ}.json` đang có làm nguồn dự phòng. Field nào CSV có thì lấy từ CSV; field nào CSV không có mà file cũ đã có bản dịch thì **giữ lại bản dịch cũ**.
- **Full Replace** — không dùng file cũ. Field nào CSV không có sẽ **giữ nguyên tiếng Anh**, và được liệt kê trong report ở mục *JSON Fields Not Matched*.

Khi lấy lại bản dịch cũ, plugin ghép từng item theo **id riêng của nó** (`id`, hoặc `name` / `key` / `type` nếu không có `id`) — **không** ghép theo vị trí trong danh sách. Điều này quan trọng: nếu file cũ có thứ tự khác, hoặc bạn vừa thêm/xoá một item, thì ghép theo vị trí sẽ khiến mỗi item nhận bản dịch của item khác. Item nào không tìm được id tương ứng trong file cũ thì giữ nguyên tiếng Anh — thà thấy tiếng Anh còn hơn thấy nội dung của tính năng khác.

> Nếu item trong JSON của bạn **không có** field nào kiểu `id` / `name` / `key` / `type`, plugin buộc phải ghép theo vị trí. Trường hợp này nên tránh thêm / xoá / đảo item giữa các lần chạy.

### JSON Assets — "Keep translations the CSV doesn't cover"

**Mặc định: tắt. Chỉ có tác dụng ở chế độ Full Replace** (Merge vốn đã luôn giữ).

| | Hành vi ở Full Replace |
|---|---|
| **Tắt** | Field nào CSV không có → **về tiếng Anh**, và được liệt kê trong report ở mục *JSON Fields Not Matched* |
| **Bật** | Field nào CSV không có → **giữ bản dịch đang có** trong file locale (ghép theo `id`) |

Dùng khi CSV chỉ cover một phần asset: bạn muốn Full Replace để dọn sạch XML, nhưng không muốn mất bản dịch JSON cũ.

> **Đánh đổi:** khi bật, Full Replace sẽ **không bao giờ dọn được** nội dung JSON cũ. Một câu đã bị xoá khỏi CSV vẫn tồn tại mãi trong file locale. Report có mục *JSON Fields Kept From Previous File* đếm và liệt kê chính xác những field này — nên xem qua sau mỗi lần chạy.

Muốn dọn sạch hoàn toàn: **tắt** setting này, chạy Full Replace một lần (mọi thứ CSV không cover sẽ về tiếng Anh), rồi bật lại nếu cần.

### Key Order — "Keep the position each key already has"

**Mặc định: bật. Nên để bật.**

Vấn đề nếu tắt: thứ tự key trong `values-ko/strings.xml` thường không giống `values/strings.xml`. Một key nằm ở dòng 10 của file gốc có thể đang ở dòng 6 của file tiếng Hàn. Nếu ghi lại theo thứ tự file gốc thì key đó bị **dời chỗ**, và khi review `git diff` bạn sẽ thấy nó bị xoá ở một chỗ rồi thêm lại ở chỗ khác cách đó vài trăm dòng — dù nội dung không hề đổi. Review kiểu đó rất mệt và dễ bỏ sót.

Khi bật:
- Key đã có sẵn trong file ngôn ngữ → **giữ nguyên vị trí cũ**
- Key mới → chèn ngay cạnh key liền trước nó trong file gốc, để các key liên quan vẫn nằm gần nhau
- Kết quả: `git diff` chỉ hiện những dòng thật sự đổi nội dung

### Non-Translatable Strings — "Translate anyway when the source provides a value"

**Mặc định: tắt.**

Trong `strings.xml` có thể có những string được đánh dấu `translatable="false"` — nghĩa là "string này không được dịch". Thường là URL API, tên build flavor, nhãn debug.

| | Hành vi |
|---|---|
| **Tắt** (mặc định) | Luôn tôn trọng dấu đó. String không bao giờ được ghi vào file ngôn ngữ, và cũng không xuất ra file Excel cho translator. |
| **Bật** | String đó **vẫn được dịch, nếu file CSV có bản dịch cho nó**. Key nào CSV không có thì vẫn bị bỏ qua như bình thường. |

Khi bật, plugin **chỉ** lấy giá trị từ CSV — không lấy lại từ file ngôn ngữ của lần chạy trước. Lý do: `translatable="false"` là một tuyên bố rõ ràng, nên việc phá vỡ nó phải luôn truy được về file dịch cụ thể. Nếu cho phép lấy lại giá trị cũ, một lần ghi đè nhầm sẽ tự tồn tại mãi mà không ai biết nó đến từ đâu.

Dù bật hay tắt, report luôn có 2 mục cho bạn kiểm tra — xem [mục 11](#11-đọc-report).

### Directories

| Setting | Mặc định | Dùng khi |
|---|---|---|
| **Values dir** | `app/src/main/res/values` | Module của bạn không tên `app`, hoặc project multi-module |
| **Assets dir** | `app/src/main/assets` | Tương tự |
| **Report dir** | Thư mục gốc project | Muốn gom report vào một chỗ riêng |

Để trống hoặc để nguyên giá trị mặc định đều được.

Nút **Reset to defaults** ở góc dưới phải đưa mọi setting về ban đầu.

---

## 11. Đọc report

Sau mỗi lần Generate, plugin ghi một file `localize_report_{ngôn_ngữ}.md` (ví dụ `localize_report_th.md`). Mở bằng Android Studio để xem dạng bảng cho dễ đọc.

**Hãy đọc file này mỗi lần chạy.** Đây là chỗ duy nhất plugin nói cho bạn biết nó *không* làm được gì.

### Phần Summary

```markdown
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
| Non-translatable overridden          | 0     |
| Non-translatable skipped             | 3     |
```

### Các mục chi tiết và việc cần làm

| Mục | Nghĩa | Bạn nên làm gì |
|---|---|---|
| **Non-Translatable — Overridden** | String có `translatable="false"` nhưng CSV có bản dịch nên **đã được ghi** | **Kiểm tra từng dòng.** Đây là nơi dễ sai nhất: một URL bị dịch thành chữ là app hỏng. |
| **Non-Translatable — Skipped** | String có `translatable="false"` và đã được bỏ qua đúng như mong đợi | Xem qua để chắc không có string nào bị đánh dấu nhầm |
| **Changed Values** | Những key có bản dịch **khác** lần chạy trước | Đối chiếu nhanh xem thay đổi có hợp lý |
| **CSV Conflicts** | Cùng một key nhưng 2 file CSV ghi khác nhau. Plugin lấy theo `android_only` | Nhắc team dịch sửa cho khớp |
| **XML Keys Not Found in CSV** | Key có trong `strings.xml` nhưng CSV không có | Gửi danh sách này cho team dịch |
| **JSON Fields Not Matched** | Field JSON không tìm được bản dịch, đang giữ nguyên tiếng Anh | Bổ sung câu đó vào CSV |
| **JSON Fields Kept From Previous File** | Field lấy lại bản dịch cũ vì CSV không có. **Không** nằm trong CSV hiện tại | Xem qua để biết phần nào của asset chưa được cover; bổ sung vào CSV nếu cần quản lý tập trung |
| **Skipped String-Arrays** | Array bị bỏ vì có item không có bản dịch | Xem cột `Missing items` để biết thiếu item nào |

> **Vì sao array thiếu 1 item lại bỏ cả array?** Vì nếu ghi ra một array nửa Hàn nửa Anh thì trên app sẽ hiện lộn xộn, khó phát hiện. Bỏ hẳn thì app dùng lại array tiếng Anh — nhìn là biết ngay còn thiếu. Ở chế độ Merge, nếu file cũ đã có item đó thì plugin giữ lại và không bỏ array.

---

## 12. Tool 2 — Export to Excel

Dùng khi bạn muốn gửi nội dung hiện tại cho team dịch.

### Điều kiện

Cả **Values dir** và **Assets dir** đều phải là thư mục tồn tại thật, kể cả khi bạn chỉ muốn xuất XML. Nếu project không có thư mục `assets`, hãy trỏ **Assets dir** sang một thư mục nào đó đang tồn tại.

### Các bước

```
┌────────────────────────────────────────────────────────┐
│ [←]                                    [ Export ]      │
├────────────────────────────────────────────────────────┤
│ ── Source ───────────────────────────────────────────  │
│ Values dir: [app/src/main/res/values .....] [📁]       │
│ Assets dir: [app/src/main/assets .........] [📁]       │
│                                                        │
│ ── Languages (locales to export as columns) ─────────  │
│ ☑ ar  ☑ ja  ☑ ko  ☑ th                               │
│                                                        │
│ ── XML Files ────────────────────────────────────────  │
│ ☑ strings.xml   ☑ ds_ob_text.xml                     │
│                                                        │
│ ── JSON Assets ──────────────────────────────────────  │
│ ☑ whats_new      title, description        [⚙]        │
│                                                        │
│ ── Output ───────────────────────────────────────────  │
│ Output file: [<project>/localize_export.xlsx] [📁]      │
└────────────────────────────────────────────────────────┘
```

1. Kiểm tra **Values dir** và **Assets dir**
2. **Languages** — danh sách này lấy từ các thư mục `values-*` đang có trong project. Tick ngôn ngữ nào thì ngôn ngữ đó thành một cột trong Excel
3. Chọn **XML Files** và **JSON Assets** muốn xuất
4. Đặt đường dẫn file kết quả ở **Output file**
5. Bấm **Export**

> Chỉ những ngôn ngữ **được tick** mới có cột. Không tick gì thì file Excel chỉ có tiếng Anh — vẫn hữu ích nếu bạn muốn gửi cho ngôn ngữ hoàn toàn mới.

### File Excel có gì

**Sheet `XML Strings`** — mỗi string một dòng. `<plurals>` được tách thành nhiều dòng, key ghi dạng `tên:số_lượng`.

| android_key | english | ko | th |
|---|---|---|---|
| `cancel` | Cancel | 취소 | ยกเลิก |
| `items_count:one` | %d item | %d개 | %d รายการ |
| `items_count:other` | %d items | %d개 | %d รายการ |

**Sheet `String Arrays`** — tên array chỉ ghi ở item đầu tiên, giống format file CSV array.

| android_key | english | ko | th |
|---|---|---|---|
| `label_length_array` | Short | 짧게 | สั้น |
| | Medium | 보통 | กลาง |
| | Long | 길게 | ยาว |

**Sheet `JSON Assets`** — mỗi field của mỗi item một dòng.

| asset | field | english | ko | th |
|---|---|---|---|---|
| `whats_new` | title | Meet Claude | Claude를 만나보세요 | พบกับ Claude |

### Lưu ý

- String có `translatable="false"` **không** xuất ra. Translator sẽ không thấy chúng, tránh dịch phí công
- Tiêu đề cột dùng đúng format mà tool Localize đọc được (`android_key`, `english`, mã ngôn ngữ), nên khi nhận file về bạn có thể lưu lại thành CSV rồi nạp vào tool Localize
- Ô trống trong cột ngôn ngữ nghĩa là chưa có bản dịch — đây chính là chỗ translator cần điền

### Gửi cho team dịch

Nên nói rõ với họ:
- Chỉ điền vào các cột ngôn ngữ, **không sửa** cột `android_key` và `english`
- **Giữ nguyên emoji** ở đầu câu nếu câu tiếng Anh có emoji
- **Giữ nguyên** các ký hiệu như `%s`, `%d`, `%1$s` — đó là chỗ app chèn số/chữ vào
- Ô nào chưa dịch thì để trống, đừng ghi `-` hay `N/A`

---

## 13. Các quy trình thường dùng

### Cập nhật vài string mới

1. Team dịch gửi CSV chỉ chứa các string mới
2. Mở **Localize from CSV**, chọn file đó
3. Settings → đảm bảo **Merge**
4. Tick ngôn ngữ liên quan → **Generate**
5. Đọc report, xem `git diff`

Merge sẽ giữ nguyên toàn bộ string cũ, chỉ thêm/sửa những gì có trong file mới.

### Thêm một ngôn ngữ hoàn toàn mới

1. Trong CSV thêm một cột mới, ví dụ `vietnamese` (hoặc `vi`)
2. Mở lại panel Localize — cột đó tự xuất hiện ở mục **Languages**
3. Nếu plugin không hiểu tên cột, gõ mã `vi` vào ô nhập bên cạnh
4. Tick nó → **Generate**

Plugin tự tạo thư mục `values-vi/`.

### Gửi đi dịch rồi nhận về

1. **Export to Excel** → gửi file `.xlsx` cho team dịch
2. Nhận file đã dịch về
3. Mở bằng Excel / Google Sheets → **Save As / Download as → CSV (UTF-8)**
   - Sheet `XML Strings` lưu thành file CSV cho ô chọn đầu tiên
   - Sheet `String Arrays` lưu thành file CSV riêng cho ô chọn thứ ba
4. Mở **Localize from CSV**, chọn 2 file CSV vừa lưu → **Generate**

### Kiểm tra trước khi commit

Sau mỗi lần Generate, luôn nên:
1. Đọc file `localize_report_*.md`
2. Chạy `git diff` xem thay đổi
3. Chú ý riêng mục **Non-Translatable — Overridden** trong report nếu bạn có bật setting đó
4. Build app và xem thử vài màn hình bằng ngôn ngữ mới

---

## 14. Xử lý sự cố

### Không thấy tab Localize

| Thử |
|---|
| **View → Tool Windows → Localize** |
| **Tools → Localize...** |
| **Settings → Plugins** kiểm tra **Localize Tool** đang được bật |
| Khởi động lại Android Studio |

### Mục Languages trống hoặc thiếu ngôn ngữ

| Nguyên nhân | Cách sửa |
|---|---|
| File CSV chưa được chọn | Chọn file ở mục CSV Files |
| Tên cột plugin không hiểu | Gõ mã ngôn ngữ vào ô nhập, hoặc dùng **✏️** Column Mapping |
| Báo `N unrecognized columns` | Bấm **✏️** và map thủ công |
| File CSV sai encoding | Lưu lại file với UTF-8 |

### Mục XML Files trống

- Kiểm tra **Values dir** trong Settings có trỏ đúng thư mục `values` chưa
- File XML phải có ít nhất một thẻ `<string` mới hiện ra

### Generate xong nhưng file không đổi

| Nguyên nhân | Cách kiểm tra |
|---|---|
| Không tick ngôn ngữ nào | Log sẽ ghi `⚠ No languages selected.` |
| CSV không có bản dịch nào khớp | Log ghi `0 written`; xem mục **XML Keys Not Found in CSV** trong report |
| Nội dung không thật sự thay đổi | Bình thường — Merge giữ nguyên bản dịch cũ nếu CSV không có gì mới |
| IDE chưa nhận file mới | Bấm chuột phải thư mục project → **Reload from Disk** |

### Có string không được dịch

Mở report, tìm theo thứ tự:
1. **XML Keys Not Found in CSV** — CSV chưa có key này
2. **Non-Translatable — Skipped** — string bị đánh dấu `translatable="false"`
3. **Skipped String-Arrays** — nằm trong array bị bỏ vì thiếu item khác

### Preview JSON hiện tiếng Anh dù CSV đã có bản dịch

Bản cũ chỉ đọc file `features_{ngôn_ngữ}.json` đã tồn tại, **không đọc CSV**. Nên ngôn ngữ nào chưa generate lần nào là preview luôn ra tiếng Anh, và nhãn locale ở khung phải cũng không đổi theo dropdown nên không ai biết đang xem ngôn ngữ nào. Bản hiện tại đọc CSV trước, và nhãn luôn khớp dropdown.

Nếu vẫn thấy tiếng Anh, đọc dòng trạng thái dưới danh sách field — nó nói rõ nguyên nhân:

| Dòng trạng thái | Nghĩa |
|---|---|
| `N still English` (không có `from CSV`) | CSV chưa được chọn ở panel, hoặc câu tiếng Anh trong JSON không khớp câu trong CSV |
| `⚠ ... not found` | Chưa có file locale — bình thường nếu chưa generate; CSV vẫn được dùng |
| `⚠ ... too large to preview` | File > 300 KB. Không preview được nhưng Generate vẫn xử lý đủ |
| `⚠ ... could not be parsed` | File locale bị lỗi syntax JSON, cần sửa tay |

### File JSON dịch ra bị lẫn nội dung của item khác

Ví dụ item `id: 12` (đúng ra là *Sign in to sync*) lại mang title của *Deep Research*.

Bản plugin cũ ghép item với file dịch cũ **theo vị trí**, nên khi thứ tự khác nhau là nội dung bị lẫn sang nhau. Bản hiện tại ghép theo `id`, không còn lỗi này.

Nếu vẫn thấy nội dung lệch, kiểm tra theo thứ tự:

1. Item đó trong JSON có field `id` (hoặc `name` / `key` / `type`) không? Nếu không có, plugin phải ghép theo vị trí — thêm `id` vào là hết lỗi.
2. Câu tiếng Anh trong CSV có khớp **chính xác** với trong file JSON không? Rất hay lệch ở **dấu câu cuối**: JSON ghi `Write professional emails in seconds` mà CSV ghi `Write professional emails in seconds.` (có dấu chấm) là không khớp.
3. Xem mục *JSON Fields Not Matched* trong report — những câu nằm ở đó là những câu CSV chưa có.

### Array bị mất emoji hoặc lấy sai bản dịch

Nguyên nhân gần như luôn là **câu tiếng Anh trong CSV không khớp chính xác** với trong `strings.xml`. Plugin so khớp item của array theo nội dung tiếng Anh trong đúng array đó.

Kiểm tra:
- Emoji có bị bỏ đi khi copy vào CSV không (`😊 Formal` vs `Formal`)
- Có khoảng trắng thừa hoặc thiếu không
- Chữ hoa chữ thường không ảnh hưởng, plugin bỏ qua

### File tiếng Hàn / Thái / Ả Rập hiện ký tự lạ

File CSV không phải UTF-8. Mở lại bằng Excel hoặc Google Sheets rồi lưu với **CSV UTF-8**.

### `git diff` toàn bộ file bị xoá rồi thêm lại

Kiểm tra Settings → **Key Order** → phải đang **bật**.

### Muốn xoá hết cấu hình để làm lại từ đầu

Cấu hình nằm ở file `.idea/localize-plugin.json` trong project. Xoá file đó rồi mở lại panel là về trạng thái ban đầu. Việc này không ảnh hưởng gì đến code hay bản dịch đã sinh.

---

## 15. Câu hỏi thường gặp

**Plugin có tự dịch không?**
Không. Plugin chỉ chuyển bản dịch do người cung cấp vào đúng chỗ. Ô nào trống trong CSV thì để trống, không tự sinh nội dung.

**Chạy nhiều lần có bị trùng lặp không?**
Không. Cứ chạy bao nhiêu lần cũng được, miễn là đang ở chế độ **Merge**.

**Bản dịch cũ có bị mất không?**
Ở **Merge**: không. Ở **Full Replace**: có, những string không nằm trong CSV sẽ bị mất. Nếu chưa chắc thì dùng Merge.

**Có cần chọn hết ngôn ngữ trong một lần chạy không?**
Không. Chạy từng ngôn ngữ một cũng được, kết quả như nhau.

**Có làm hỏng `values/strings.xml` gốc không?**
Không. Plugin chỉ **đọc** file gốc và **ghi** vào các thư mục `values-{ngôn_ngữ}/`.

**Tại sao đường dẫn file JSON trong `strings.xml` tự đổi?**
Đó là chủ ý. Những string kiểu `task/tasks.json` phải trỏ tới file JSON của đúng ngôn ngữ, nên plugin tự sửa thành `task/tasks_th.json`. Kể cả khi bạn không tick asset nào, plugin vẫn ghi đúng để app không bị lỗi thiếu file.

**Có thể để CSV ở ngoài project không?**
Được. Chọn file ở đâu cũng được, plugin lưu đường dẫn tuyệt đối.

**Nhiều người cùng dùng trên một project thì sao?**
Cấu hình nằm trong `.idea/localize-plugin.json`. Nếu file này được commit thì cả team dùng chung cấu hình. Nếu không muốn, thêm nó vào `.gitignore`.

**Tôi sửa tay file `values-ko/strings.xml` rồi chạy Generate, có bị mất không?**
Ở chế độ **Merge**: những string bạn sửa tay mà CSV không có sẽ được giữ lại. Nhưng string nào CSV có thì bản trong CSV sẽ thắng. Sửa tay không phải cách tốt — nên sửa trong CSV.

---

*Có vấn đề không nằm trong tài liệu này, hoặc plugin làm gì đó khó hiểu — hãy chụp lại khung **Log** và file `localize_report_*.md` khi báo lỗi.*
