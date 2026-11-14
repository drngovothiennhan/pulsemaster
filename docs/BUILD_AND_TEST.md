# Build & test

## Lõi (đã chạy được, không cần Android)
```
cd core && gradle test            # 68 test + vector chuẩn
gradle test -PupdateGolden        # chỉ khi cố ý đổi hành vi: ghi lại testvectors/golden.json
```

## Ứng dụng Android (cần Android Studio / SDK 34, JDK 17)
1. Mở thư mục `android/` bằng Android Studio (hoặc `gradle wrapper` rồi `./gradlew :app:assembleDebug`).
2. Bật chế độ nhà phát triển + gỡ lỗi USB trên điện thoại thật (trình giả lập KHÔNG có động cơ rung/lực ấn thật), `Run`.
3. Kiểm thử trên máy thật:
   - Màn chính: kiểm tra "Động cơ rung / Điều khiển biên độ". Không có điều khiển biên độ ⇒ chỉ xung gần đúng.
   - **Hiệu chuẩn**: Bắt đầu → giữ chạm 5 s (nhẹ → vừa → mạnh). Báo "Cảm biến lực thật: CÓ/KHÔNG".
   - **Khám 18 điểm**: chọn mạch (vd. Phù: ấn nhẹ rung rõ, ấn mạnh yếu đi; Trầm ngược lại; Khâu: tầng giữa rỗng; Đại: cứ 4 nhịp nghỉ 1). Giữ đúng tầng 5 nhịp để ghi điểm.
   - **Đệm BLE**: cấp quyền Bluetooth, bấm "Đệm BLE". Cần firmware theo `docs/BLE_PROTOCOL.md`.
4. Nếu tầng lúc nhảy/không bám ngón: chạy lại Hiệu chuẩn; kiểm tra ngón tay thật sự chạm đủ 5 giây.
