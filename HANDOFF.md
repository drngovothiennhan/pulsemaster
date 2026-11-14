# HANDOFF – PulseMaster Pro (chẩn mạch YHCT: lực ấn + haptic)

> Đọc file này đầu tiên khi mở cuộc trò chuyện mới. Cập nhật lần cuối: 2026-11-14 (cuối phiên 1).
> Chủ dự án: `drngovothiennhan` (nói tiếng Việt; muốn trả lời ngắn gọn, có số liệu, nói rõ cái gì đã kiểm / chưa kiểm).

## 1. Mục tiêu sản phẩm
Nâng cấp mô hình web `pulsemaster.netlify.app` (chỉ chọn nút Phù/Trung/Trầm + rung bật/tắt) thành ứng dụng cho người học **đặt ngón tay lên vùng Thốn–Quan–Xích, đo lực ấn thật để chuyển liên tục Phù–Trung–Trầm, và phát rung theo đường cong áp suất mạch** (chủ ba + khuyết trọng bác + sóng dội). Dữ liệu 28 bộ mạch ở `data/tcm_28_pulses_haptic.json` (nguồn duy nhất, KHÔNG đổi tên khóa).
Gói bàn giao gốc (ZIP + prompt) nằm ngoài repo; nội dung đã được hấp thụ vào mã. Yêu cầu gốc: Pha 1 (nhịp đặc biệt, region_emphasis, nội suy mượt) · Pha 2 (hiệu chuẩn + khám 18 điểm + thanh lực + đồ thị 60 fps) · Pha 3 (BLE đệm 3 kênh ESP32-S3).

## 2. Quyết định của chủ dự án (đã chốt)
| Ngày | Quyết định |
|---|---|
| 11-14 | **Không làm iOS** ("Ko cần cho iOS"). Đã xóa mã Swift (chưa từng biên dịch được). |
| 11-14 | **Bật GitHub Pages để dùng PWA; tập trung bản PWA đủ tính năng.** APK để sau. |
| 11-14 | APK hiện **không hiệu chuẩn được và không rung được** (chủ dự án thử trên máy thật) – xem §6. |

## 3. Hiện trạng – nơi mọi thứ nằm
- **Repo:** https://github.com/drngovothiennhan/pulsemaster (riêng tư/không rõ – chủ dự án tạo trống). Nhánh làm việc **duy nhất**: `claude/new-session-hpb4uo` (đang là nhánh mặc định). Chưa có PR/`main`.
- **Cấu trúc:**
```
data/tcm_28_pulses_haptic.json   nguồn dữ liệu duy nhất (web/data/ là bản sao – test kiểm hai file giống nhau)
core/        Kotlin JVM thuần: lõi logic + 68 test  (gradle -p core test)
android/     app Android (Views thuần, không AndroidX) – BIÊN DỊCH ĐƯỢC trên CI nhưng CHƯA hoạt động đúng trên máy thật
web/         PWA (bản chính hiện nay): js/core.js (port 1:1 từ Kotlin), haptic.js, webble.js, app.js, sw.js, manifest…
  web/test/core.test.mjs         25 test Node (gồm đối chiếu từng giá trị với testvectors/golden.json do Kotlin sinh)
  web/test/e2e/*.cjs             Playwright cục bộ: features (29 kiểm tra), pwa-offline (18), calibration (6)
testvectors/golden.json          vector chuẩn (biên độ, nhịp, 28 gói BLE) – sinh bởi `gradle -p core test -PupdateGolden`
docs/        ARCHITECTURE.md, BLE_PROTOCOL.md, BUILD_AND_TEST.md
.github/workflows/android-apk.yml  web-tests + core-tests + build APK debug (artifact `pulsemaster-debug-apk`)
.github/workflows/pages.yml        test + deploy `web/` lên GitHub Pages
```
- **Bản sao máy cục bộ (có thể mất khi phiên kết thúc):** `/home/user/pulsemaster-repo` là git clone; `/home/user/pulsemaster` là bản làm việc ban đầu (trùng nội dung, không phải repo git). Phiên mới: clone lại repo (xem §8).

## 4. Trạng thái từng hạng mục
| Hạng mục | Trạng thái | Bằng chứng |
|---|---|---|
| Lõi Kotlin (catalog, hiệu chuẩn, nội suy PCHIP, 5 rhythm_type, sóng, 18 điểm, codec BLE) | **Xong, kiểm thử** | 68 test xanh cục bộ + trên GitHub Actions |
| Lõi JS (port) | **Xong, kiểm thử** | 25 test Node, trùng golden Kotlin ≤ 2e-6 |
| PWA – Khám 18 điểm (giữ đúng tầng ≥5 nhịp), Tự do, Đố mạch, BPM theo dải mạch, thanh lực + đồ thị 60 fps, 3 ngón cùng lúc | **Xong, kiểm thử trình duyệt** | Playwright 29+18+6 kiểm tra (mock `navigator.vibrate`) |
| PWA – hiệu chuẩn 5 s (lấy mẫu theo đồng hồ, loại kênh `pressure` cố định 0.5), thông số thô, chế độ kéo dọc/thủ công | Xong, kiểm thử bằng sự kiện tổng hợp | `calibration.cjs`. **Chưa thử trên điện thoại thật** |
| PWA – kiểu rung: xung kép (mặc định) / xung đơn / PWM, cường độ 0,25–3×, nút Thử rung | Xong về mã | Mẫu rung kiểm bằng test; **cảm giác thật chưa ai kiểm** |
| PWA – lịch sử khám + xuất CSV, giữ màn hình sáng, offline (service worker v2), cài PWA | Xong | e2e offline PASS |
| PWA – Web Bluetooth đệm 3 kênh | Mã xong; **chưa thử với phần cứng** (không có firmware/đệm) | chỉ kiểm nhánh "không hỗ trợ" |
| **GitHub Pages** | **CHƯA BẬT – cần chủ dự án bấm 1 lần** (xem §5) | run Pages #1 lỗi `Resource not accessible by integration` |
| Android app | Biên dịch OK (CI #2, #3), **chạy thật có lỗi** (§6) | APK artifact trên run `43601910143` (908 KB) |
| iOS | **Bỏ** | — |
| Firmware ESP32-S3 | **Chưa viết** (ngoài phạm vi); giao thức đã đặc tả | `docs/BLE_PROTOCOL.md` |

## 5. VIỆC CẦN LÀM NGAY – bật GitHub Pages (thủ công, GITHUB_TOKEN không có quyền)
1. Mở https://github.com/drngovothiennhan/pulsemaster/settings/pages
2. **Build and deployment → Source: chọn `GitHub Actions`** (không cần chọn gì thêm).
3. Quay lại phiên và nhờ chạy lại workflow "Deploy PWA to GitHub Pages" (hoặc Actions → workflow đó → *Run workflow*; hoặc đẩy 1 commit đụng `web/`).
4. URL dự kiến: `https://drngovothiennhan.github.io/pulsemaster/` → trên điện thoại mở bằng **Chrome Android** → menu ⋮ → *Cài đặt ứng dụng / Thêm vào màn hình chính*.
Lưu ý: repo **riêng tư** cần gói GitHub trả phí (Pro/Team) mới dùng được Pages; nếu không, đổi repo sang public (**việc cần chủ dự án duyệt**) hoặc host nơi khác (Netlify/Cloudflare Pages: chỉ cần thư mục `web/`). Môi trường `github-pages` mặc định chỉ cho deploy từ nhánh mặc định = `claude/new-session-hpb4uo` (đã đúng).
Sau khi Pages chạy: **kiểm tra thật** URL tải được, manifest/service worker đăng ký, mở offline được. Nếu đường dẫn con (`/pulsemaster/`) gây lỗi: mọi đường dẫn trong `web/` đều tương đối nên không nên lỗi – nhưng phải kiểm.

## 6. Lỗi đã biết – Android app (chưa sửa; chủ dự án bảo tập trung PWA)
Triệu chứng chủ dự án báo: hiệu chuẩn không được, không rung. **Chưa có thiết bị/log để chẩn đoán**, giả thuyết xếp theo khả năng:
1. Máy báo `size = 0` và/hoặc `pressure` cố định (1.0/0.5) ⇒ lực chuẩn hóa ≈ 0 (< 0.05 = NONE) ⇒ **vòng rung không bao giờ khởi động** và hiệu chuẩn không có thay đổi để học. Android app **chưa có chế độ kéo dọc/thủ công** như PWA. Cách sửa: thêm nguồn lực thủ công + hiển thị thông số thô (đã làm ở PWA).
2. `CalibrationActivity` chỉ lấy mẫu theo `ACTION_MOVE`; ngón đứng yên sinh ít sự kiện ⇒ < 8 mẫu/pha ⇒ "Chưa đạt". Cách sửa giống PWA: lấy mẫu theo timer 50 ms từ giá trị chạm mới nhất.
3. `hasAmplitudeControl()` = false ⇒ chỉ phát `createOneShot` (xấp xỉ) – cảm nhận yếu; hoặc rung bị tắt bởi chế độ yên lặng/tiết kiệm pin.
Việc nên làm khi quay lại: thêm màn "thông số thô" + log, dùng cùng cơ chế như PWA, rồi build lại qua Actions.

## 7. Giả định/đề xuất CHƯA được chủ dự án xác nhận (phải nói rõ khi báo cáo)
- Mức giảm biên độ khi ấn sâu cho mạch Tán/Sáp: **35% / 15%** (`FADE_TAN/FADE_SAP`) – JSON không có; tự đặt.
- Giao thức BLE ngoài phần bàn giao gốc (uplink 6 byte): bố cục **SET_PROFILE 16 B, START, SET_CALIBRATION, kênh BEAT_NOTIFY, CRC-8/SMBUS** là đề xuất của tôi (`docs/BLE_PROTOCOL.md`). Firmware phải theo đó.
- Công thức: tầng NONE <0.05 / Phù <0.35 / Trung 0.35–0.70 / Trầm >0.70 (từ gói gốc); neo nội suy 0.20/0.525/0.85, fusion 0.6·pressure+0.4·size, trễ ±0.02, "đạt điểm" = 5 nhịp liên tiếp, dung sai 100 ms ngoài tầng/nhịp – các giá trị nội suy/dung sai là lựa chọn của tôi.
- Rung web: xung kép (chủ ba 12–60 ms theo biên độ + sóng dội cách ~80 ms, bỏ nếu < 10 ms) là **gần đúng** – `navigator.vibrate` không có biên độ thật. Con số "75–85% / ≥95% chân thực" trong gói gốc là ước lượng chưa có phép đo.
- Mạch Xúc/Kết/Đại: nhịp "nghỉ" vẫn tính là một nhịp thời gian cho bộ đếm 5 nhịp; Xúc/Kết không bao giờ nghỉ 2 nhịp liên tiếp và không nghỉ nhịp đầu.

## 8. Cách làm việc / lệnh hữu ích
- **Clone:** dùng công cụ `mcp__claude-code-remote__add_repo` (owner `drngovothiennhan`, repo `pulsemaster`, access `push`) rồi `git clone --depth 1 https://github.com/drngovothiennhan/pulsemaster <thư-mục-MỚI>` (thư mục `/home/user/pulsemaster` có thể đã tồn tại – không xóa bừa). Nhánh: `claude/new-session-hpb4uo`. Đẩy: `git -c push.negotiate=true push -u origin claude/new-session-hpb4uo`. Không tạo PR nếu chủ dự án chưa yêu cầu. Commit kết thúc bằng dòng `Co-Authored-By` + `Claude-Session` theo chỉ dẫn phiên.
- **Môi trường phiên cũ:** không có Android SDK, không có Swift; build APK chỉ qua GitHub Actions. Không có công cụ bật Pages / tạo repo. `sleep` dài ở foreground bị chặn → chạy nền (`run_in_background`) rồi đọc kết quả. Cổng e2e: 8123/8125/8127 (dọn `pkill -f chrome-headless` nếu kẹt).
- **Chạy test:**
  - Kotlin: `cd core && gradle test` (cần `gradle.properties` có `kotlin.compiler.execution.strategy=in-process`; JDK 21 OK, target 17). Đổi hành vi có chủ ý ⇒ `gradle test -PupdateGolden` rồi cập nhật JS + `web/test`.
  - JS: `node --test web/test/core.test.mjs` (25 test).
  - Trình duyệt: `node web/test/e2e/features.cjs`, `pwa-offline.cjs`, `calibration.cjs` (cần Playwright; mỗi cái ~1–2 phút).
  - Chạy PWA cục bộ: `cd web && python3 -m http.server 8000`.
- **Quy tắc khi đổi lõi:** giữ Kotlin ↔ JS đồng bộ (cùng quy ước: mạch cần ngẫu nhiên tiêu thụ ĐÚNG MỘT giá trị `u` mỗi nhịp); `web/data/…json` phải bằng `data/…json`; mỗi lần sửa file cache của PWA nhớ **tăng `VERSION` trong `web/sw.js`**.
- Quy tắc dự án khác (CLAUDE.md của các repo hiệu `hiutmc-ecosystem`…) **không áp dụng** cho repo này.

## 9. Việc còn lại (theo thứ tự đề xuất)
1. **Bật Pages (§5) và kiểm URL thật**, cài PWA trên điện thoại chủ dự án.
2. Chủ dự án thử PWA trên điện thoại thật và báo: (a) tab Cài đặt → "Thử rung" có rung không; (b) Hiệu chuẩn có "Đã lưu" không / thông số thô mà máy báo (pressure, rộng×cao) đổi thế nào khi ấn nhẹ–mạnh; (c) kiểu rung nào dễ cảm nhận nhất. Chỉnh `vibrationPattern` theo phản hồi (đây là phần rủi ro nhất, chỉ máy thật mới trả lời được).
3. Sửa Android theo §6 (nếu chủ dự án vẫn muốn APK).
4. Firmware ESP32-S3 theo `docs/BLE_PROTOCOL.md` + thử Web Bluetooth/Android BLE với đệm thật.
5. Tùy chọn: thêm e2e Playwright vào CI; màn hiệu chuẩn ADC từng kênh cho đệm BLE (hiện chỉ có API `sendChannelCalibration`).
