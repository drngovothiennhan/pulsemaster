# PulseMaster – bản web / PWA

Chạy được trên mọi trình duyệt; cài như ứng dụng (PWA) và dùng offline sau lần mở đầu (cần HTTPS hoặc localhost).

```
cd web && python3 -m http.server 8000     # rồi mở http://localhost:8000
node --test web/test/core.test.mjs        # 19 test, gồm đối chiếu từng giá trị với vector chuẩn của lõi Kotlin
```

- `js/core.js` – lõi thuần (port 1:1 từ `core/` Kotlin; không dùng DOM)
- `js/haptic.js` – lực từ PointerEvent + `navigator.vibrate` (biên độ mô phỏng bằng độ rộng xung)
- `js/webble.js` – đệm 3 kênh qua Web Bluetooth (cùng giao thức `docs/BLE_PROTOCOL.md`)
- `js/app.js`, `index.html`, `style.css`, `sw.js`, `manifest.webmanifest`

## Giới hạn của web (không khắc phục được bằng phần mềm)
| Hạng mục | Thực tế |
|---|---|
| Rung | `navigator.vibrate` chỉ có trên Chrome/Edge/Firefox Android; **iOS không hỗ trợ**; không có biên độ thật |
| Lực ấn | `PointerEvent.pressure` thường cố định 0,5 trên di động → app tự bỏ kênh này và dùng diện tích tiếp xúc (`width/height`) sau khi hiệu chuẩn |
| Chuột/desktop | không đo được lực → dùng thanh "Lực mô phỏng" để thử logic |
| BLE | Web Bluetooth: Chrome/Edge (Android, desktop); không có trên iOS |
