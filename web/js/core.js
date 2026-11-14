// Lõi PulseMaster cho web – port 1:1 từ core/ (Kotlin). Không dùng DOM nên chạy được trong Node để kiểm thử.
// Mọi số là Number (double) như bản Kotlin; vector chuẩn: ../../testvectors/golden.json

export const Depth = Object.freeze({ NONE: 0, PHU: 1, TRUNG: 2, TRAM: 3 });
export const DEPTH_LABEL = ['Chưa chạm', 'Phù (Ấn nhẹ)', 'Trung (Ấn vừa)', 'Trầm (Ấn mạnh)'];
export const REGIONS = Object.freeze(['thon', 'quan', 'xich']);
export const REGION_LABEL = { thon: 'Thốn', quan: 'Quan', xich: 'Xích' };
export const REGION_CHANNEL = { thon: 0, quan: 1, xich: 2 };
export const HANDS = Object.freeze(['left', 'right']);
export const HAND_LABEL = { left: 'Tay trái', right: 'Tay phải' };
export const RHYTHM_WIRE = { regular: 0, intermittent_irregular: 1, intermittent_regular: 2, irregular_fade: 3, slightly_uneven: 4 };

export class PulseDataError extends Error {}
export class CalibrationError extends Error {}
export class PacketError extends Error {}

const clamp = (x, lo, hi) => Math.min(Math.max(x, lo), hi);
export const smoothstep = (e0, e1, x) => { const t = clamp((x - e0) / (e1 - e0), 0, 1); return t * t * (3 - 2 * t); };
const round = (x) => Math.floor(x + 0.5); // nửa lên như Math.round của Kotlin (giá trị không âm)

// ---------- Danh mục 28 mạch ----------
function num(m, k, id) {
  const v = m[k];
  if (typeof v !== 'number' || !Number.isFinite(v)) throw new PulseDataError(`${id}.${k} thiếu hoặc không phải số`);
  return v;
}
function unit(m, k, ctx) {
  const v = num(m, k, ctx);
  if (v < 0 || v > 1) throw new PulseDataError(`${ctx}.${k} = ${v} ngoài [0,1]`);
  return v;
}
function str(m, k, id) {
  const v = m[k];
  if (typeof v !== 'string' || v.trim() === '') throw new PulseDataError(`${id}.${k} thiếu hoặc rỗng`);
  return v;
}

function parsePulse(m) {
  if (m === null || typeof m !== 'object' || Array.isArray(m)) throw new PulseDataError('Mục mạch không phải object');
  const id = str(m, 'id', '?');
  const r = m.bpm_range;
  if (!Array.isArray(r) || r.length !== 2 || typeof r[0] !== 'number' || typeof r[1] !== 'number') {
    throw new PulseDataError(`${id}.bpm_range phải có 2 số`);
  }
  const [lo, hi] = r;
  if (lo < 20 || hi > 220 || lo > hi) throw new PulseDataError(`${id}.bpm_range ngoài [20,220] hoặc đảo ngược`);
  const def = num(m, 'default_bpm', id);
  if (def < lo || def > hi) throw new PulseDataError(`${id}.default_bpm nằm ngoài bpm_range`);
  const da = m.depth_amplitude, re = m.region_emphasis;
  if (!da || typeof da !== 'object') throw new PulseDataError(`${id}.depth_amplitude thiếu`);
  if (!re || typeof re !== 'object') throw new PulseDataError(`${id}.region_emphasis thiếu`);
  const rhythm = str(m, 'rhythm_type', id);
  if (!(rhythm in RHYTHM_WIRE)) throw new PulseDataError(`${id}.rhythm_type lạ: '${rhythm}'`);
  let pauseProbability = 0, pauseEveryNBeats = 0;
  if (rhythm === 'intermittent_irregular') pauseProbability = unit(m, 'pause_probability', id);
  if (rhythm === 'intermittent_regular') {
    const n = num(m, 'pause_every_n_beats', id);
    if (n < 1 || n > 255 || n !== Math.floor(n)) throw new PulseDataError(`${id}.pause_every_n_beats phải là số nguyên 1..255`);
    pauseEveryNBeats = n;
  }
  const up = num(m, 'upstroke_ms', id);
  if (up < 5 || up > 200) throw new PulseDataError(`${id}.upstroke_ms ngoài [5,200]`);
  return {
    id, nameVi: str(m, 'name_vi', id), nameHan: str(m, 'name_han', id), category: str(m, 'category', id),
    bpmMin: lo, bpmMax: hi, defaultBpm: def,
    depthAmplitude: { phu: unit(da, 'phu', `${id}.depth_amplitude`), trung: unit(da, 'trung', `${id}.depth_amplitude`), tram: unit(da, 'tram', `${id}.depth_amplitude`) },
    sharpness: unit(m, 'sharpness', id), upstrokeMs: up, dicroticRatio: unit(m, 'dicrotic_ratio', id),
    rhythm, pauseProbability, pauseEveryNBeats,
    regionEmphasis: { thon: unit(re, 'thon', `${id}.region_emphasis`), quan: unit(re, 'quan', `${id}.region_emphasis`), xich: unit(re, 'xich', `${id}.region_emphasis`) },
    clinicalFeel: str(m, 'clinical_feel', id),
  };
}

export function parseCatalog(text) {
  let root;
  try { root = JSON.parse(text); } catch (e) { throw new PulseDataError(`JSON lỗi: ${e.message}`); }
  if (root === null || typeof root !== 'object' || Array.isArray(root)) throw new PulseDataError('Gốc JSON phải là object');
  const version = root.schema_version;
  if (typeof version !== 'string') throw new PulseDataError('Thiếu schema_version');
  if (Number(version.split('.')[0]) !== 1) throw new PulseDataError(`Không hỗ trợ schema_version ${version}`);
  if (!Array.isArray(root.pulses)) throw new PulseDataError('Thiếu mảng pulses');
  const seen = new Set();
  const pulses = root.pulses.map((p) => {
    const q = parsePulse(p);
    if (seen.has(q.id)) throw new PulseDataError(`Trùng id '${q.id}'`);
    seen.add(q.id);
    return q;
  });
  if (pulses.length === 0) throw new PulseDataError('Danh sách mạch rỗng');
  const byId = new Map(pulses.map((p) => [p.id, p]));
  return { schemaVersion: version, pulses, byId: (id) => byId.get(id), require(id) { const p = byId.get(id); if (!p) throw new PulseDataError(`Không có mạch '${id}'`); return p; } };
}

// ---------- Lực, hiệu chuẩn, phân tầng ----------
export const Zones = Object.freeze({
  NONE_MAX: 0.05, PHU_MAX: 0.35, TRUNG_MAX: 0.70,
  CENTER_PHU: 0.20, CENTER_TRUNG: 0.525, CENTER_TRAM: 0.85, HYSTERESIS: 0.02,
  boundaries: [0.05, 0.35, 0.70],
  classify(f) { return f < 0.05 ? Depth.NONE : f < 0.35 ? Depth.PHU : f <= 0.70 ? Depth.TRUNG : Depth.TRAM; },
  range(d) { return [[0, 0.05], [0.05, 0.35], [0.35, 0.70], [0.70, 1]][d]; },
});

export class ChannelCalibration {
  constructor(light, medium, hard) {
    if (![light, medium, hard].every(Number.isFinite)) throw new CalibrationError('Giá trị hiệu chuẩn không hữu hạn');
    if (hard <= 0) throw new CalibrationError('Mức ấn mạnh phải > 0');
    const minGap = 0.04 * hard;
    if (medium - light < minGap || hard - medium < minGap) {
      throw new CalibrationError('Ba mức ấn chưa đủ khác biệt: hãy chạm nhẹ hơn và nhấn mạnh hơn rõ rệt');
    }
    Object.assign(this, { light, medium, hard });
  }
  normalize(raw) {
    const { light, medium, hard } = this;
    const x = Zones.CENTER_PHU, y = Zones.CENTER_TRUNG, z = Zones.CENTER_TRAM;
    let f;
    if (raw <= light) f = x + (raw - light) * ((y - x) / (medium - light));
    else if (raw <= medium) f = x + (raw - light) / (medium - light) * (y - x);
    else if (raw <= hard) f = y + (raw - medium) / (hard - medium) * (z - y);
    else f = z + (raw - hard) * ((z - y) / (hard - medium));
    return clamp(f, 0, 1);
  }
}

export class FingerCalibration {
  constructor(pressure, size) {
    if (!pressure && !size) throw new CalibrationError('Không nhận được thay đổi lực nào từ màn hình. Thử chạm lại rõ ràng hơn.');
    this.pressure = pressure || null;
    this.size = size || null;
  }
  static defaultSizeOnly(l, m, h) { return new FingerCalibration(null, new ChannelCalibration(l, m, h)); }
  fuse(raw) {
    const fp = this.pressure ? this.pressure.normalize(raw.pressure) : null;
    const fs = this.size ? this.size.normalize(raw.size) : null;
    if (fp !== null && fs !== null) return clamp(0.6 * fp + 0.4 * fs, 0, 1);
    return fp !== null ? fp : fs;
  }
  toJSON() {
    const ch = (c) => (c ? { light: c.light, medium: c.medium, hard: c.hard } : null);
    return { v: 1, pressure: ch(this.pressure), size: ch(this.size) };
  }
  static fromJSON(o) {
    if (!o || typeof o !== 'object' || o.v !== 1) throw new CalibrationError('Hiệu chuẩn lưu sai định dạng hoặc phiên bản');
    const ch = (c) => (c == null ? null : new ChannelCalibration(c.light, c.medium, c.hard));
    return new FingerCalibration(ch(o.pressure), ch(o.size));
  }
}

export const CALIBRATION_PHASES = ['Chạm nhẹ (Phù)', 'Nhấn vừa (Trung)', 'Nhấn mạnh (Trầm)'];

export class CalibrationRecorder {
  constructor(totalMs = 5000, minSamples = 8) { this.total = totalMs; this.min = minSamples; this.start0 = -1; this.samples = [[], [], []]; }
  start(now) { this.start0 = now; this.samples = [[], [], []]; }
  phaseAt(now) {
    if (this.start0 < 0) return null;
    const e = now - this.start0;
    if (e < 0 || e >= this.total) return null;
    return Math.min(Math.floor(e / Math.floor(this.total / 3)), 2);
  }
  progress(now) { return this.start0 < 0 ? 0 : clamp((now - this.start0) / this.total, 0, 1); }
  addSample(now, raw) {
    if (!Number.isFinite(raw.pressure) || !Number.isFinite(raw.size)) return null;
    const ph = this.phaseAt(now);
    if (ph === null) return null;
    this.samples[ph].push(raw);
    return ph;
  }
  finish() {
    const per = this.samples.map((list, i) => {
      if (list.length < this.min) throw new CalibrationError(`Pha ${CALIBRATION_PHASES[i]}: chỉ có ${list.length} mẫu (cần ≥ ${this.min}). Hãy giữ chạm liên tục trong suốt 5 giây.`);
      return list.slice(Math.floor(list.length * 0.3));
    });
    const med = (v) => { const s = [...v].sort((a, b) => a - b); return s.length % 2 ? s[(s.length - 1) / 2] : (s[s.length / 2 - 1] + s[s.length / 2]) / 2; };
    const channel = (sel) => { try { return new ChannelCalibration(...per.map((p) => med(p.map(sel)))); } catch (e) { if (e instanceof CalibrationError) return null; throw e; } };
    return new FingerCalibration(channel((s) => s.pressure), channel((s) => s.size));
  }
}

export class ForceFilter {
  constructor(alpha = 0.35) { this.alpha = alpha; this.v = null; }
  update(x) { this.v = this.v === null ? x : this.v + this.alpha * (x - this.v); return this.v; }
  reset() { this.v = null; }
}

export class DepthTracker {
  constructor(h = Zones.HYSTERESIS) { this.h = h; this.level = 0; }
  get depth() { return this.level; }
  update(force) {
    const b = Zones.boundaries;
    while (this.level < 3 && force >= b[this.level] + this.h) this.level++;
    while (this.level > 0 && force <= b[this.level - 1] - this.h) this.level--;
    return this.level;
  }
  reset() { this.level = 0; }
}

// ---------- Nội suy biên độ theo lực ----------
export function amplitudeAt(a, force, mode = 'MONOTONE_CUBIC') {
  const f = clamp(force, 0, 1);
  const X0 = Zones.CENTER_PHU, X1 = Zones.CENTER_TRUNG, X2 = Zones.CENTER_TRAM;
  let base;
  if (mode === 'LINEAR') {
    base = f <= X0 ? a.phu : f <= X1 ? a.phu + (f - X0) / (X1 - X0) * (a.trung - a.phu) : f <= X2 ? a.trung + (f - X1) / (X2 - X1) * (a.tram - a.trung) : a.tram;
  } else if (f <= X0) base = a.phu;
  else if (f >= X2) base = a.tram;
  else {
    const h0 = X1 - X0, h1 = X2 - X1, d0 = (a.trung - a.phu) / h0, d1 = (a.tram - a.trung) / h1;
    let m1 = 0;
    if (d0 * d1 > 0) { const w1 = 2 * h1 + h0, w2 = h1 + 2 * h0; m1 = (w1 + w2) / (w1 / d0 + w2 / d1); }
    const end = (g0, g1, e0, e1) => {
      const m = ((2 * g0 + g1) * e0 - g0 * e1) / (g0 + g1);
      if (Math.sign(m) !== Math.sign(e0)) return 0;
      if (Math.sign(e0) !== Math.sign(e1) && Math.abs(m) > 3 * Math.abs(e0)) return 3 * e0;
      return m;
    };
    const m0 = end(h0, h1, d0, d1), m2 = end(h1, h0, d1, d0);
    const herm = (xk, h, yk, yk1, mk, mk1) => {
      const t = (f - xk) / h, t2 = t * t, t3 = t2 * t;
      return (2 * t3 - 3 * t2 + 1) * yk + (t3 - 2 * t2 + t) * h * mk + (-2 * t3 + 3 * t2) * yk1 + (t3 - t2) * h * mk1;
    };
    base = f <= X1 ? herm(X0, h0, a.phu, a.trung, m0, m1) : herm(X1, h1, a.trung, a.tram, m1, m2);
  }
  return clamp(base * smoothstep(0.03, 0.10, f), 0, 1);
}

// ---------- Nhịp ----------
export const MIN_BPM = 35, MAX_BPM = 180, JITTER = 0.12;
/** ĐỀ XUẤT (chưa có trong JSON): mức giảm biên độ tối đa khi ấn sâu. */
export const FADE_TAN = 0.35, FADE_SAP = 0.15;

export class BeatScheduler {
  /** uniform: { next(): number } – mạch cần ngẫu nhiên tiêu thụ ĐÚNG MỘT giá trị mỗi nhịp. */
  constructor(profile, uniform, bpm = profile.defaultBpm) {
    this.p = profile; this.u = uniform; this.bpm = clamp(bpm, MIN_BPM, MAX_BPM); this.index = 0; this.lastSilent = false;
  }
  next(force) {
    const base = 60000 / this.bpm;
    let interval = base, silent = false, scale = 1;
    const i = this.index++;
    switch (this.p.rhythm) {
      case 'intermittent_irregular': { const u = this.u.next(); silent = i > 0 && !this.lastSilent && u < this.p.pauseProbability; break; }
      case 'intermittent_regular': { const n = this.p.pauseEveryNBeats; silent = n > 0 && i % (n + 1) === n; break; }
      case 'irregular_fade':
      case 'slightly_uneven': {
        const u = this.u.next();
        interval = base * (1 + (2 * u - 1) * JITTER);
        const fade = this.p.rhythm === 'irregular_fade' ? FADE_TAN : FADE_SAP;
        scale = 1 - fade * smoothstep(0.5, 1.0, force);
        break;
      }
      default: break;
    }
    this.lastSilent = silent;
    return { index: i, intervalMs: interval, silent, amplitudeScale: scale };
  }
}

export const randomUniform = () => ({ next: () => Math.random() });

// ---------- Sóng 1 nhịp ----------
export function synthWaveform(p, intervalMs) {
  const up = p.upstrokeMs, dic = p.dicroticRatio, notch = Math.min(0.25, dic * 0.6);
  let pts = [[0, 0], [up * 0.5, 0.45], [up, 1], [up + 45, notch], [up + 80, dic], [up + 140, dic * 0.2], [up + 200, 0]];
  const total = pts[pts.length - 1][0], limit = intervalMs * 0.85;
  if (total > limit && limit > 0) { const k = limit / total; pts = pts.map(([t, l]) => [t * k, l]); }
  const points = pts.map(([tMs, level]) => ({ tMs, level }));
  return {
    points,
    durationMs: points[points.length - 1].tMs,
    levelAt(t) {
      if (t <= points[0].tMs || t >= points[points.length - 1].tMs) return 0;
      let i = 1; while (points[i].tMs < t) i++;
      const a = points[i - 1], b = points[i], span = b.tMs - a.tMs;
      return span <= 0 ? b.level : a.level + (t - a.tMs) / span * (b.level - a.level);
    },
  };
}

/**
 * navigator.vibrate chỉ nhận chuỗi [bật, tắt, bật, …] (không có biên độ) → mô phỏng biên độ bằng độ rộng xung (PWM):
 * mỗi bước stepMs bật `duty·stepMs`. Giới hạn của web, không phải của lõi.
 */
export function toVibratePattern(wave, peak, stepMs = 20) {
  const n = Math.max(1, Math.ceil(wave.durationMs / stepMs));
  const segs = []; // [bật?, độ dài ms], đã gộp các đoạn liền kề cùng trạng thái
  const push = (on, d) => {
    if (d <= 0) return;
    const last = segs[segs.length - 1];
    if (last && last[0] === on) last[1] += d; else segs.push([on, d]);
  };
  for (let i = 0; i < n; i++) {
    const duty = clamp(wave.levelAt((i + 0.5) * stepMs) * peak, 0, 1);
    const on = Math.round(duty * stepMs);
    push(true, on);
    push(false, stepMs - on);
  }
  while (segs.length && !segs[segs.length - 1][0]) segs.pop(); // không kết thúc bằng nghỉ
  const out = [];
  if (segs.length && !segs[0][0]) out.push(0); // chuỗi phải bắt đầu bằng "bật"
  segs.forEach(([, d]) => out.push(d));
  return out;
}

export function planBeat(profile, scheduler, forces, mode = 'MONOTONE_CUBIC') {
  const touching = Object.entries(forces).filter(([, f]) => f >= Zones.NONE_MAX);
  const maxForce = touching.reduce((m, [, f]) => Math.max(m, f), 0);
  const beat = scheduler.next(maxForce);
  let best = 0, bestRegion = null;
  for (const r of REGIONS) {
    const e = touching.find(([k]) => k === r);
    if (!e) continue;
    const amp = amplitudeAt(profile.depthAmplitude, e[1], mode) * profile.regionEmphasis[r];
    if (amp > best) { best = amp; bestRegion = r; }
  }
  const peak = beat.silent ? 0 : clamp(best * beat.amplitudeScale, 0, 1);
  return { beat, peak, waveform: synthWaveform(profile, beat.intervalMs), dominantRegion: bestRegion, intervalMs: beat.intervalMs, silent: beat.silent || peak <= 0 };
}

// ---------- Khám 18 điểm ----------
export const EXAM_PLAN = HANDS.flatMap((hand) => REGIONS.flatMap((region) => [Depth.PHU, Depth.TRUNG, Depth.TRAM].map((depth) => ({ hand, region, depth }))));
export const pointLabel = (p) => `${HAND_LABEL[p.hand]} · ${REGION_LABEL[p.region]} · ${DEPTH_LABEL[p.depth]}`;

export class HoldTracker {
  constructor(target, required = 5, graceMs = 100, maxGapMs = 100) {
    if (target === Depth.NONE) throw new Error('Tầng mục tiêu không thể là NONE');
    Object.assign(this, { target, required, graceMs, maxGapMs, consecutive: 0, completed: false, outMs: 0, lastT: -1, lastIn: false });
  }
  gap(t) { return clamp(t - this.lastT, 0, this.maxGapMs); }
  onSample(depth, t) { if (this.lastT >= 0 && !this.lastIn) this.outMs += this.gap(t); this.lastIn = depth === this.target; this.lastT = t; }
  onBeat(t) {
    if (this.completed) return false;
    if (this.lastT >= 0 && !this.lastIn) this.outMs += this.gap(t);
    this.lastT = t;
    if (this.outMs <= this.graceMs && this.lastIn) this.consecutive++; else this.consecutive = 0;
    this.outMs = 0;
    if (this.consecutive >= this.required) { this.completed = true; return true; }
    return false;
  }
  onLift() { Object.assign(this, { consecutive: 0, outMs: 0, lastT: -1, lastIn: false }); }
}

export class ExamSession {
  constructor(plan = EXAM_PLAN) { this.plan = plan; this.results = []; }
  get index() { return this.results.length; }
  get total() { return this.plan.length; }
  get isFinished() { return this.results.length >= this.plan.length; }
  get current() { return this.plan[this.results.length] ?? null; }
  record(meanForce, captured = true) {
    const p = this.current;
    if (!p) throw new Error('Phiên khám đã kết thúc');
    this.results.push({ point: p, captured, meanForce });
  }
  skip() { this.record(0, false); }
  summary() { return this.results.map((r) => `${pointLabel(r.point)}: ${r.captured ? `đạt (F≈${r.meanForce.toFixed(2)})` : 'bỏ qua'}`).join('\n'); }
}

// ---------- BLE (cùng giao thức với Android) ----------
export const PAD_UUID = Object.freeze({
  service: '7d0a0001-6b1f-4c3e-9a52-5c0de1f0a001', forceUplink: '7d0a0002-6b1f-4c3e-9a52-5c0de1f0a001',
  configDownlink: '7d0a0003-6b1f-4c3e-9a52-5c0de1f0a001', beatNotify: '7d0a0004-6b1f-4c3e-9a52-5c0de1f0a001',
});

export function crc8(bytes, len = bytes.length) {
  let crc = 0;
  for (let i = 0; i < len; i++) { crc ^= bytes[i]; for (let k = 0; k < 8; k++) crc = (crc & 0x80) ? ((crc << 1) ^ 0x07) & 0xff : (crc << 1) & 0xff; }
  return crc;
}
const withCrc = (body) => Uint8Array.from([...body, crc8(body)]);
export const toHex = (b) => Array.from(b, (x) => x.toString(16).padStart(2, '0')).join('');

export function decodeUplink(b) {
  if (b.length !== 6) throw new PacketError(`Gói lực phải dài 6 byte, nhận ${b.length}`);
  const u16 = (i) => b[i] | (b[i + 1] << 8);
  const v = [u16(0), u16(2), u16(4)];
  if (v.some((x) => x > 4095)) throw new PacketError(`Giá trị ADC vượt 12-bit: ${v.join(',')}`);
  return { thon: v[0], quan: v[1], xich: v[2] };
}

export function downlinkProfileFrom(p, bpm = p.defaultBpm) {
  const q = (x) => round(clamp(x, 0, 1) * 255);
  const b = clamp(bpm, MIN_BPM, MAX_BPM);
  const param = p.rhythm === 'intermittent_irregular' ? q(p.pauseProbability) : p.rhythm === 'intermittent_regular' ? p.pauseEveryNBeats : 0;
  return {
    bpmTenths: round(b * 10), phu: q(p.depthAmplitude.phu), trung: q(p.depthAmplitude.trung), tram: q(p.depthAmplitude.tram),
    sharpness: q(p.sharpness), upstrokeMs: clamp(round(p.upstrokeMs), 0, 255), dicrotic: q(p.dicroticRatio),
    rhythm: p.rhythm, rhythmParam: param, thon: q(p.regionEmphasis.thon), quan: q(p.regionEmphasis.quan), xich: q(p.regionEmphasis.xich),
  };
}

export function encodeProfile(d) {
  if (d.bpmTenths < 350 || d.bpmTenths > 1800) throw new PacketError('bpmTenths ngoài 35.0..180.0');
  for (const k of ['phu', 'trung', 'tram', 'sharpness', 'upstrokeMs', 'dicrotic', 'rhythmParam', 'thon', 'quan', 'xich']) {
    if (!Number.isInteger(d[k]) || d[k] < 0 || d[k] > 255) throw new PacketError(`Trường ${k} vượt byte: ${d[k]}`);
  }
  return withCrc([1, 0x01, d.bpmTenths & 0xff, d.bpmTenths >> 8, d.phu, d.trung, d.tram, d.sharpness, d.upstrokeMs, d.dicrotic,
    RHYTHM_WIRE[d.rhythm], d.rhythmParam, d.thon, d.quan, d.xich]);
}
export const encodeStop = () => withCrc([1, 0x02]);
export function encodeStart(mask) { if (!(mask >= 1 && mask <= 7)) throw new PacketError('regionMask phải trong 1..7'); return withCrc([1, 0x04, mask]); }
export function encodeCalibration(channel, light, medium, hard) {
  if (![0, 1, 2].includes(channel)) throw new PacketError('channel 0..2');
  if (![light, medium, hard].every((v) => Number.isInteger(v) && v >= 0 && v <= 4095)) throw new PacketError('ADC ngoài 12-bit');
  if (!(light < medium && medium < hard)) throw new PacketError('Cần light < medium < hard');
  return withCrc([1, 0x03, channel, light & 0xff, light >> 8, medium & 0xff, medium >> 8, hard & 0xff, hard >> 8]);
}
export function decodeBeatNotice(b) {
  if (b.length !== 3) throw new PacketError(`Gói nhịp phải dài 3 byte, nhận ${b.length}`);
  return { seq: b[0], silent: (b[1] & 1) !== 0, peak: b[2] / 255 };
}
export class PadChannelMapper {
  constructor() { this.cal = { thon: null, quan: null, xich: null }; }
  setCalibration(r, c) { this.cal[r] = c; }
  force(r, adc) { return this.cal[r] ? this.cal[r].normalize(adc) : clamp(adc / 4095, 0, 1); }
}
