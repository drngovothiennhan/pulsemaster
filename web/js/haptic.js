// "PhoneInternalHaptic" cho web: lực từ PointerEvent (pressure + contact geometry), kéo dọc hoặc thanh thủ công;
// phản hồi bằng navigator.vibrate (chỉ Android/Chromium; iOS không hỗ trợ).
import * as C from './core.js';

/**
 * Kênh "rung bằng loa": phát một âm trầm có biên độ bám đúng đường cong áp suất mạch (Web Audio có biên độ thật,
 * khác navigator.vibrate chỉ bật/tắt). Dùng khi máy không rung được (iOS, máy tắt rung hệ thống) hoặc bổ sung cho rung.
 */
export class AudioPulse {
  static get supported() { return typeof (globalThis.AudioContext || globalThis.webkitAudioContext) === 'function'; }
  constructor() { this.ctx = null; this.played = 0; this.lastCurve = null; }
  /** Phải gọi trong một thao tác chạm/bấm (trình duyệt chặn âm thanh khi chưa có tương tác). */
  unlock() {
    if (!AudioPulse.supported) return false;
    try {
      this.ctx ??= new (globalThis.AudioContext || globalThis.webkitAudioContext)();
      if (this.ctx.state === 'suspended') this.ctx.resume().catch(() => {});
      return true;
    } catch { return false; }
  }
  /** Đường bao biên độ 0..1 lấy mẫu từ sóng mạch (điểm cuối = 0 để không lách cách). */
  static envelope(wave, peak, gain = 1) {
    const n = Math.max(8, Math.min(160, Math.round(wave.durationMs / 6)));
    const out = new Float32Array(n + 1);
    const k = Math.min(1, Math.max(0, peak * Math.min(3, Math.max(0.25, gain))));
    for (let i = 0; i < n; i++) out[i] = Math.min(1, Math.max(0, wave.levelAt((i / n) * wave.durationMs) * k));
    out[n] = 0;
    return out;
  }
  play(wave, peak, { freq = 180, gain = 1 } = {}) {
    const ctx = this.ctx;
    if (!ctx || ctx.state !== 'running') return false;
    const curve = AudioPulse.envelope(wave, peak, gain);
    this.lastCurve = curve;
    try {
      const t0 = ctx.currentTime + 0.005, dur = wave.durationMs / 1000;
      const osc = ctx.createOscillator(), g = ctx.createGain();
      osc.type = 'triangle'; // có hài bậc lẻ: loa nhỏ vẫn nghe được dù tần số gốc thấp
      osc.frequency.value = Math.min(600, Math.max(60, freq));
      g.gain.value = 0; g.gain.setValueCurveAtTime(curve, t0, dur);
      osc.connect(g); g.connect(ctx.destination);
      osc.start(t0); osc.stop(t0 + dur + 0.02);
      this.played++;
      return true;
    } catch { return false; }
  }
}

export class WebHapticTransport {
  /** settings: { vibMode, vibGain } – đọc mỗi nhịp nên đổi cài đặt có tác dụng ngay. */
  constructor(calibration, settings = {}) {
    this.kind = 'phone';
    this.listener = null;
    this.state = { name: 'idle' };
    this.cal = calibration;
    this.settings = settings;
    this.profile = null; this.bpm = 72; this.sched = null;
    this.forces = {};
    this.filters = Object.fromEntries(C.REGIONS.map((r) => [r, new C.ForceFilter()]));
    this.trackers = Object.fromEntries(C.REGIONS.map((r) => [r, new C.DepthTracker()]));
    this.enabled = true; this.vibrated = false; this.running = false; this.timer = null; this.nextAt = 0;
    this.lastPattern = [];
    this.audio = new AudioPulse();
  }
  static get canVibrate() { return typeof navigator !== 'undefined' && typeof navigator.vibrate === 'function'; }

  setCalibration(c) { this.cal = c; }
  open() {
    this.#set(WebHapticTransport.canVibrate
      ? { name: 'ready' }
      : { name: 'ready', note: 'Trình duyệt này không hỗ trợ rung (iOS Safari…): dùng kênh “rung bằng loa” (Cài đặt) + đồ thị và thanh lực.' });
  }
  close() { this.stopFeedback(); this.#set({ name: 'idle' }); }
  setProfile(p, bpm) { this.profile = p; this.bpm = bpm; this.sched = new C.BeatScheduler(p, C.randomUniform(), bpm); }
  startFeedback() { this.enabled = true; if (Object.keys(this.forces).length) this.#start(); }
  stopFeedback() { this.enabled = false; this.#stop(); }

  /**
   * input: { manual: 0..1 } (chuột/thanh/kéo dọc) hoặc { raw: {pressure, size} } (cảm biến).
   * Trả về lực đã chuẩn hóa để UI hiển thị thông số thô.
   */
  onForceInput(region, input) {
    const f0 = input.manual !== undefined ? input.manual : this.cal.fuse(input.raw);
    const f = this.filters[region].update(f0);
    const d = this.trackers[region].update(f);
    this.forces[region] = f;
    this.listener?.onForce(region, f, d, performance.now());
    if (d !== C.Depth.NONE) this.#start();
    return { force: f, depth: d };
  }
  onRelease(region) {
    delete this.forces[region];
    this.filters[region].reset(); this.trackers[region].reset();
    this.listener?.onForce(region, 0, C.Depth.NONE, performance.now());
    if (!Object.keys(this.forces).length) this.#stop();
  }

  /** Phát thử một mẫu rung (nút "Thử rung"): trả về false nếu trình duyệt từ chối/không hỗ trợ. */
  static test(pattern) {
    if (!WebHapticTransport.canVibrate) return false;
    try { return navigator.vibrate(pattern) === true; } catch { return false; }
  }

  #start() {
    if (this.running || !this.enabled || !this.sched || this.state.name !== 'ready') return;
    this.running = true; this.nextAt = performance.now(); this.#tick();
  }
  #stop() {
    this.running = false; clearTimeout(this.timer); this.timer = null;
    // chỉ hủy rung nếu đã từng rung (trình duyệt chặn vibrate khi chưa có thao tác chạm)
    if (this.vibrated && WebHapticTransport.canVibrate) navigator.vibrate(0);
    this.vibrated = false;
  }
  #tick() {
    if (!this.running) return;
    const now = performance.now();
    const plan = C.planBeat(this.profile, this.sched, { ...this.forces });
    if (!plan.silent && this.settings.sound) this.audio.play(plan.waveform, plan.peak, { freq: this.settings.soundFreq, gain: this.settings.vibGain ?? 1 });
    if (!plan.silent && WebHapticTransport.canVibrate) {
      const pat = C.vibrationPattern(plan.waveform, plan.peak, { mode: this.settings.vibMode || 'pwm', gain: this.settings.vibGain ?? 1 });
      this.lastPattern = pat;
      if (pat.length) { navigator.vibrate(pat); this.vibrated = true; }
    }
    this.listener?.onBeat({ startMs: now, plan, peak: plan.peak, silent: plan.silent, intervalMs: plan.intervalMs });
    this.nextAt += plan.intervalMs;
    if (this.nextAt < now) this.nextAt = now + plan.intervalMs; // trễ nhiều: bám lại đồng hồ
    this.timer = setTimeout(() => this.#tick(), Math.max(0, this.nextAt - performance.now()));
  }
  #set(s) { this.state = s; this.listener?.onState(s); }
}
