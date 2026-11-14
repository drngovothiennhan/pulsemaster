# Giao thức BLE đệm cổ tay 3 kênh (ESP32-S3)

Service `7d0a0001-6b1f-4c3e-9a52-5c0de1f0a001`. Mọi số nhiều byte là little-endian. MTU mặc định 23 đủ (≤ 20 byte).

| Đặc tính | UUID | Hướng | Nội dung |
|---|---|---|---|
| FORCE_UPLINK | `…0002…` | Notify, 100 Hz | **6 byte**: 3 × uint16 (Thốn, Quan, Xích), ADC 12-bit 0..4095. Giá trị > 4095 ⇒ app bỏ gói |
| CONFIG_DOWNLINK | `…0003…` | Write | khung `[ver=1][cmd][payload][crc8]` (CRC-8/SMBUS, poly 0x07, init 0, tính trên mọi byte trước) |
| BEAT_NOTIFY (tùy chọn) | `…0004…` | Notify, mỗi nhịp | 3 byte `[seq][flags bit0=nhịp bỏ][peak 0..255]` – để đồ thị trên điện thoại đồng bộ với xung thật |

**Lưu ý:** BEAT_NOTIFY, lệnh START/CALIBRATION và bố cục SET_PROFILE là **đề xuất của bản triển khai này** (bản bàn giao chỉ quy định uplink 6 byte và nội dung cấu hình). Firmware phải theo đúng tài liệu này.

## Lệnh downlink
| cmd | Tên | Payload |
|---|---|---|
| 0x01 | SET_PROFILE (16 B) | `bpm×10 u16`, `phu u8`, `trung u8`, `tram u8`, `sharpness u8`, `upstroke_ms u8`, `dicrotic u8`, `rhythm u8`, `rhythm_param u8`, `w_thon u8`, `w_quan u8`, `w_xich u8` (tỉ lệ ×255) |
| 0x02 | STOP (3 B) | — |
| 0x03 | SET_CALIBRATION (10 B) | `channel u8 (0..2)`, `light u16`, `medium u16`, `hard u16` (ADC, light<medium<hard) |
| 0x04 | START (4 B) | `region_mask u8` (bit0 Thốn, bit1 Quan, bit2 Xích) |

`rhythm`: 0 regular, 1 intermittent_irregular (`param`=pause_probability×255), 2 intermittent_regular (`param`=n), 3 irregular_fade, 4 slightly_uneven.

## Hành vi MCU yêu cầu (để độ trễ < 5 ms)
1. Mỗi kênh tự tính F từ ADC bằng 3 neo hiệu chuẩn (cùng công thức `ChannelCalibration.normalize`; chưa hiệu chuẩn: F = ADC/4095).
2. Một vòng nhịp chung theo `bpm`; mỗi nhịp, với từng kênh trong `region_mask` có F ≥ 0.05: biên độ = PCHIP(phu,trung,tram; F) × w_kênh → DRV2605L/LRA, dạng sóng như `WaveformSynth`.
3. Phải bỏ qua gói có CRC/phiên bản/độ dài sai (không dừng xung đang chạy).
Gói mẫu chuẩn nằm trong `testvectors/golden.json` (28 gói SET_PROFILE + STOP/START/CALIBRATION) để đối chiếu firmware.
