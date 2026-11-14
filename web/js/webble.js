// Đệm cổ tay qua Web Bluetooth (Chrome/Edge trên Android & desktop; KHÔNG có trên iOS Safari/Firefox).
// Cùng giao thức với bản Android: xem ../docs/BLE_PROTOCOL.md
import * as C from './core.js';

export class WebBleTransport {
  static get supported() { return typeof navigator !== 'undefined' && !!navigator.bluetooth; }

  constructor() {
    this.kind = 'ble';
    this.listener = null;
    this.state = { name: 'idle' };
    this.mapper = new C.PadChannelMapper();
    this.filters = Object.fromEntries(C.REGIONS.map((r) => [r, new C.ForceFilter(0.4)]));
    this.trackers = Object.fromEntries(C.REGIONS.map((r) => [r, new C.DepthTracker()]));
    this.profile = null; this.bpm = 72; this.config = null; this.device = null; this.chain = Promise.resolve();
  }

  open() { /* kết nối cần thao tác người dùng: gọi connect() từ nút bấm */ this.#set({ name: 'idle' }); }

  /** Phải gọi trong sự kiện bấm nút (yêu cầu của trình duyệt). */
  async connect() {
    if (!WebBleTransport.supported) return this.#set({ name: 'failed', reason: 'Trình duyệt không hỗ trợ Web Bluetooth (dùng Chrome/Edge trên Android hoặc máy tính; iOS không hỗ trợ).' });
    if (!window.isSecureContext) return this.#set({ name: 'failed', reason: 'Web Bluetooth chỉ chạy trên HTTPS hoặc localhost.' });
    this.#set({ name: 'connecting' });
    try {
      this.device = await navigator.bluetooth.requestDevice({ filters: [{ services: [C.PAD_UUID.service] }] });
      this.device.addEventListener('gattserverdisconnected', () => { this.#resetForces(); this.config = null; this.#set({ name: 'failed', reason: 'Đệm đã ngắt kết nối' }); });
      const server = await this.device.gatt.connect();
      const svc = await server.getPrimaryService(C.PAD_UUID.service);
      const force = await svc.getCharacteristic(C.PAD_UUID.forceUplink);
      this.config = await svc.getCharacteristic(C.PAD_UUID.configDownlink);
      force.addEventListener('characteristicvaluechanged', (e) => this.#onForce(this.#bytes(e)));
      await force.startNotifications();
      try {
        const beat = await svc.getCharacteristic(C.PAD_UUID.beatNotify);
        beat.addEventListener('characteristicvaluechanged', (e) => this.#onBeat(this.#bytes(e)));
        await beat.startNotifications();
      } catch (e) { console.warn('Đệm không có BEAT_NOTIFY (tùy chọn)', e); }
      this.#set({ name: 'ready' });
      if (this.profile) this.setProfile(this.profile, this.bpm);
    } catch (e) {
      const cancelled = e && e.name === 'NotFoundError';
      this.#set({ name: 'failed', reason: cancelled ? 'Đã hủy chọn thiết bị' : `Không kết nối được đệm: ${e && e.message ? e.message : e}` });
    }
  }

  close() {
    this.#resetForces();
    try { if (this.device && this.device.gatt.connected) this.device.gatt.disconnect(); } catch (e) { console.warn(e); }
    this.config = null; this.#set({ name: 'idle' });
  }

  setProfile(profile, bpm) { this.profile = profile; this.bpm = bpm; this.#write(C.encodeProfile(C.downlinkProfileFrom(profile, bpm))); }
  startFeedback(regions) { const mask = regions.reduce((m, r) => m | (1 << C.REGION_CHANNEL[r]), 0); if (mask) this.#write(C.encodeStart(mask)); }
  stopFeedback() { this.#write(C.encodeStop()); }
  sendChannelCalibration(region, light, medium, hard) {
    const pkt = C.encodeCalibration(C.REGION_CHANNEL[region], light, medium, hard);
    this.mapper.setCalibration(region, new C.ChannelCalibration(light, medium, hard)); this.#write(pkt);
  }

  #bytes(e) { const v = e.target.value; return new Uint8Array(v.buffer, v.byteOffset, v.byteLength); }
  #set(s) { this.state = s; this.listener?.onState(s); }
  #write(bytes) {
    if (!this.config) return; // chưa kết nối: cấu hình được gửi lại khi sẵn sàng
    const ch = this.config;
    this.chain = this.chain.then(() => (ch.writeValueWithResponse ? ch.writeValueWithResponse(bytes) : ch.writeValue(bytes))).catch((e) => console.warn('Ghi cấu hình lỗi', e));
  }
  #onForce(bytes) {
    let frame; try { frame = C.decodeUplink(bytes); } catch (e) { console.warn('Bỏ gói lực', e.message); return; }
    const now = performance.now();
    for (const r of C.REGIONS) {
      const f = this.filters[r].update(this.mapper.force(r, frame[r]));
      const d = this.trackers[r].update(f);
      this.listener?.onForce(r, d === C.Depth.NONE ? 0 : f, d, now);
    }
  }
  #onBeat(bytes) {
    let n; try { n = C.decodeBeatNotice(bytes); } catch (e) { console.warn('Bỏ gói nhịp', e.message); return; }
    if (!this.profile) return;
    const interval = 60000 / Math.min(Math.max(this.bpm, C.MIN_BPM), C.MAX_BPM);
    const plan = { beat: { index: n.seq, intervalMs: interval, silent: n.silent, amplitudeScale: 1 }, peak: n.peak, waveform: C.synthWaveform(this.profile, interval), dominantRegion: null, intervalMs: interval, silent: n.silent };
    this.listener?.onBeat({ startMs: performance.now(), plan, peak: n.peak, silent: n.silent, intervalMs: interval });
  }
  #resetForces() { for (const r of C.REGIONS) { this.filters[r].reset(); this.trackers[r].reset(); this.listener?.onForce(r, 0, C.Depth.NONE, performance.now()); } }
}
