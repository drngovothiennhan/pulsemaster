// "PhoneInternalHaptic" cho web: lực từ PointerEvent (pressure + contact geometry) hoặc thanh mô phỏng (chuột),
// phản hồi bằng navigator.vibrate (chỉ Android/Chromium; iOS không hỗ trợ).
import * as C from './core.js';

export class WebHapticTransport {
  constructor(calibration) {
    this.kind = 'phone';
    this.listener = null;
    this.state = { name: 'idle' };
    this.cal = calibration;
    this.playback = null; this.profile = null; this.bpm = 72;
    this.forces = {};
    this.filters = Object.fromEntries(C.REGIONS.map((r) => [r, new C.ForceFilter()]));
    this.trackers = Object.fromEntries(C.REGIONS.map((r) => [r, new C.DepthTracker()]));
    this.enabled = true; this.vibrated = false; this.running = false; this.timer = null; this.nextAt = 0; this.sched = null;
  }
  static get canVibrate() { return typeof navigator !== 'undefined' && typeof navigator.vibrate === 'function'; }

  setCalibration(c) { this.cal = c; }
  open() {
    this.#set(WebHapticTransport.canVibrate
      ? { name: 'ready' }
      : { name: 'ready', note: 'Trình duyệt này không hỗ trợ rung (iOS Safari…): chỉ có đồ thị và thanh lực, không có xúc giác.' });
  }
  close() { this.stopFeedback(); this.#set({ name: 'idle' }); }
  setProfile(p, bpm) { this.profile = p; this.bpm = bpm; this.sched = new C.BeatScheduler(p, C.randomUniform(), bpm); }
  startFeedback() { this.enabled = true; if (Object.keys(this.forces).length) this.#start(); }
  stopFeedback() { this.enabled = false; this.#stop(); }

  /** simForce: lực (0..1) từ thanh mô phỏng, dùng khi thiết bị trỏ là chuột (không đo được lực). */
  onPointer(region, e, simForce) {
    let f;
    if (e.pointerType === 'mouse' || e.pointerType === '') f = simForce;
    else f = this.cal.fuse({ pressure: e.pressure, size: Math.max(e.width || 0, e.height || 0) });
    f = this.filters[region].update(f);
    const d = this.trackers[region].update(f);
    this.forces[region] = f;
    this.listener?.onForce(region, f, d, performance.now());
    if (d !== C.Depth.NONE) this.#start();
  }
  onRelease(region) {
    delete this.forces[region];
    this.filters[region].reset(); this.trackers[region].reset();
    this.listener?.onForce(region, 0, C.Depth.NONE, performance.now());
    if (!Object.keys(this.forces).length) this.#stop();
  }

  #start() {
    if (this.running || !this.enabled || !this.sched || this.state.name !== 'ready') return;
    this.running = true; this.nextAt = performance.now(); this.#tick();
  }
  #stop() {
    this.running = false; clearTimeout(this.timer); this.timer = null;
    if (this.vibrated && WebHapticTransport.canVibrate) navigator.vibrate(0); // chưa từng rung thì không gọi (trình duyệt chặn khi chưa có thao tác chạm)
    this.vibrated = false;
  }
  #tick() {
    if (!this.running) return;
    const now = performance.now();
    const plan = C.planBeat(this.profile, this.sched, { ...this.forces });
    if (!plan.silent && WebHapticTransport.canVibrate) {
      const pat = C.toVibratePattern(plan.waveform, plan.peak);
      if (pat.length) { navigator.vibrate(pat); this.vibrated = true; }
    }
    this.listener?.onBeat({ startMs: now, plan, peak: plan.peak, silent: plan.silent, intervalMs: plan.intervalMs });
    this.nextAt += plan.intervalMs;
    if (this.nextAt < now) this.nextAt = now + plan.intervalMs; // trễ nhiều: bám lại đồng hồ
    this.timer = setTimeout(() => this.#tick(), Math.max(0, this.nextAt - performance.now()));
  }
  #set(s) { this.state = s; this.listener?.onState(s); }
}
