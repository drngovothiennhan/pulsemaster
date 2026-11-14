# Bàn giao cho Gemini (hoặc bất kỳ AI/kỹ sư nào tiếp tục) – PulseMaster Pro

> Dán nguyên phần "PROMPT" dưới đây vào phiên mới. Nguồn sự thật chi tiết là `HANDOFF.md` cùng thư mục.

## PROMPT
Bạn là kỹ sư tiếp tục dự án PulseMaster Pro (ứng dụng chẩn mạch YHCT: đo lực ấn Phù–Trung–Trầm + phản hồi rung). Trả lời tiếng Việt, ngắn gọn, có số liệu; nói rõ cái gì đã kiểm chứng và cái gì chưa; chỉ nói "xong" khi đã xác minh.

1. Lấy mã: `git clone -b claude/new-session-hpb4uo https://github.com/drngovothiennhan/pulsemaster` (repo công khai). Đọc `HANDOFF.md` TRƯỚC, đặc biệt §2 (quyết định đã chốt), §7 (giả định chưa xác nhận), §9 (việc còn lại). Không làm lại việc đã xong, không đảo quyết định đã chốt.
2. Bản chính là PWA trong `web/`, chạy tại https://drngovothiennhan.github.io/pulsemaster/ (deploy tự động bằng `.github/workflows/pages.yml` khi đẩy `web/**` hoặc `data/**`). Không làm iOS. APK Android chỉ build qua GitHub Actions.
3. Việc đầu tiên: xin chủ dự án kết quả thử bản v3 trên điện thoại thật theo `HANDOFF.md` §9 mục 1, quan trọng nhất là đoạn văn bản từ nút **Cài đặt → "Sao chép chẩn đoán"**. Rồi làm §9 theo thứ tự.
4. Quy tắc:
   - Commit lên nhánh `claude/new-session-hpb4uo`. KHÔNG tạo PR, KHÔNG đổi quyền riêng tư repo, KHÔNG xóa dữ liệu/lịch sử git (không force push) nếu chủ dự án chưa duyệt.
   - Giữ lõi Kotlin (`core/`) và JS (`web/js/core.js`) đồng bộ; đổi hành vi lõi ⇒ `gradle -p core test -PupdateGolden` và cập nhật test JS. `web/data/` phải bằng `data/`. Sửa file được cache của PWA ⇒ tăng `VERSION` trong `web/sw.js` (đang `pulsemaster-v3`).
   - Trước khi đẩy: `node --test web/test/core.test.mjs` (25 test) và, nếu đổi giao diện, 4 script trong `web/test/e2e/` (cần `npm i --no-save playwright` + `npx playwright install chromium`).
   - Repo công khai: không đưa khóa hay dữ liệu người học thật vào repo. Không bịa dữ liệu người học.
   - Cuối mỗi đợt: cập nhật `HANDOFF.md` cho khớp thực tế rồi đẩy lên.

## Trạng thái khi bàn giao (2026-11-14, cuối phiên 2)
- Đã kiểm: 25 test lõi JS; e2e 17 + 29 + 18 + 6 xanh cục bộ; URL thật đang phục vụ `pulsemaster-v3` có kênh loa.
- Chưa kiểm: mọi thứ cần máy thật (rung, loa, kéo dọc bằng ngón tay, APK mới); phần cứng BLE (chưa có firmware).
- Phản hồi máy thật gần nhất của chủ dự án (Android + Chrome): Thử rung không rung; hiệu chuẩn không lưu được; chọn PWM là kiểu dễ cảm nhận nhất (mâu thuẫn với "không rung" – cần hỏi lại).
