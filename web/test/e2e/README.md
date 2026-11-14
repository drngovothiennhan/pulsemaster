# Kiểm thử trình duyệt (Playwright, chạy cục bộ – chưa nằm trong CI)
Cần `playwright` + Chromium (`npm i -D playwright`). Mỗi script tự dựng máy chủ tĩnh trên cổng 8123/8125 và in PASS/FAIL.
- `features.cjs` – 29 kiểm tra: khám 18 điểm, rung (mock `navigator.vibrate`), tự do, kéo dọc, kiểu rung/cường độ, cài đặt lưu, lịch sử + CSV, BPM, đố mạch, hiệu chuẩn.
- `pwa-offline.cjs` – 18 kiểm tra: luồng khám, BLE không hỗ trợ, manifest, service worker, tải lại offline.
Mỗi script mất ~60–90 giây (chờ đủ 5 nhịp thật). Chạy: `node web/test/e2e/features.cjs`.
