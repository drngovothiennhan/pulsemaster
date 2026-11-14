# Kiểm thử trình duyệt (Playwright; chạy cục bộ và trong CI – job `web-e2e` của android-apk.yml)
Cần `playwright` + Chromium (`npm i -D playwright`). Mỗi script tự dựng máy chủ tĩnh trên cổng 8123/8125 và in PASS/FAIL.
- `features.cjs` – 29 kiểm tra: khám 18 điểm, rung (mock `navigator.vibrate`), tự do, kéo dọc, kiểu rung/cường độ, cài đặt lưu, lịch sử + CSV, BPM, đố mạch, hiệu chuẩn.
- `feedback.cjs` – 17 kiểm tra (cổng 8129): mặc định PWM, kéo dọc khi chưa hiệu chuẩn, rung bằng loa, chẩn đoán, số đo hiệu chuẩn.
- `calibration.cjs` – 6 kiểm tra (cổng 8127): hiệu chuẩn với ngón đứng yên.
- `pwa-offline.cjs` – 18 kiểm tra: luồng khám, BLE không hỗ trợ, manifest, service worker, tải lại offline.
Mỗi script mất ~60–90 giây (chờ đủ 5 nhịp thật). Chạy: `node web/test/e2e/features.cjs`.
