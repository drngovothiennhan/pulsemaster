# Kiến trúc đề xuất: Native thuần Android (Kotlin) + lõi JVM dùng chung

**Chọn Native Kotlin** (không React Native/Flutter) vì độ chân thực phụ thuộc vào `MotionEvent.getPressure/getSize`,
`VibrationEffect.createWaveform` và BLE độ trễ thấp; các cầu nối đa nền tảng thêm trễ và khó kiểm soát nhịp.
iOS **không nằm trong phạm vi** (chủ dự án xác nhận không cần).

```
pulsemaster/
├─ data/tcm_28_pulses_haptic.json      nguồn dữ liệu DUY NHẤT (không đổi khóa); Android đóng gói qua sourceSets
├─ core/                               Kotlin JVM thuần – KHÔNG phụ thuộc Android → test được bằng `gradle test`
│  └─ com.yhct.pulsemaster.core
│     Model, MiniJson, PulseCatalog      nạp + kiểm tra nghiêm ngặt JSON
│     ForceModel                         hiệu chuẩn, hợp nhất pressure+size, lọc EMA, phân tầng có trễ
│     DepthInterpolator                  nội suy liên tục Phù→Trung→Trầm (linear / PCHIP)
│     Rhythm                             5 rhythm_type, jitter ±12 %, bỏ nhịp
│     Waveform, Playback                 chủ ba → khuyết → sóng dội; kế hoạch từng nhịp; region_emphasis
│     Exam                               18 điểm + HoldTracker (≥ 5 nhịp liên tiếp)
│     Ble                                codec uplink/downlink/nhịp, CRC8, PulseHardwareTransport
├─ android/app/                        UI + 2 transport
│  PhoneHapticTransport                 PhoneInternalHaptic (VibrationEffect.createWaveform)
│  BleWristPadTransport                 ExternalWristPadBLE (GATT, hàng đợi thao tác, quyền Android 8–14)
│  CalibrationActivity / ExamActivity   màn hiệu chuẩn 5 s; khám 18 điểm; ForceGauge + PulseWave 60 fps
├─ testvectors/golden.json             vector chuẩn (biên độ, nhịp, gói BLE) – chống hồi quy / đối chiếu firmware
└─ docs/
```

## Luồng dữ liệu
`Transport (lực thô) → FingerCalibration.fuse → ForceFilter → DepthTracker → listener.onForce`
`Beat loop: PulsePlayback.planBeat(forces) → BeatScheduler (nhịp) + DepthInterpolator × region_emphasis → WaveformSynth → VibrationEffect`

## Quy ước nghiệp vụ đã triển khai
| Hạng mục | Quy tắc |
|---|---|
| Tầng | NONE <0.05, Phù <0.35, Trung 0.35–0.70, Trầm >0.70; trễ ±0.02 chống nhấp nháy |
| Hiệu chuẩn | 3 pha 5 s, trung vị; neo nhẹ/vừa/mạnh → F=0.20/0.525/0.85; kênh pressure tự loại nếu máy không có lực thật |
| Fusion | 0.6·pressure + 0.4·size (nếu có pressure thật), ngược lại chỉ size |
| Nội suy | PCHIP qua 3 neo, không vượt biên; dưới F=0.10 giảm mượt về 0 |
| `intermittent_irregular` | bỏ nhịp nếu u < `pause_probability`; không bỏ nhịp đầu, không bỏ 2 nhịp liên tiếp |
| `intermittent_regular` | n nhịp phát rồi nghỉ 1 (`pause_every_n_beats`) |
| `irregular_fade` / `slightly_uneven` | chu kỳ ±12 %, biên độ giảm khi ấn sâu |
| Nhiều ngón (tablet) | 1 động cơ rung → phát bộ có biên độ (đã nhân trọng số) lớn nhất |
| Đạt 1 điểm khám | giữ đúng tầng 5 nhịp liên tiếp; ngoài tầng > 100 ms trong 1 nhịp hoặc nhấc ngón → về 0 |
