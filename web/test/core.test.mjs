import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import * as C from '../js/core.js';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.join(here, '..', '..');
const read = (p) => fs.readFileSync(path.join(root, p), 'utf8');
const catalog = C.parseCatalog(read('data/tcm_28_pulses_haptic.json'));
const golden = JSON.parse(read('testvectors/golden.json'));
const P = (id) => catalog.require(id);
const scripted = (v) => { let i = 0; return { next: () => v[i++ % v.length] }; };
const near = (a, b, eps = 2e-6, msg = '') => assert.ok(Math.abs(a - b) <= eps, `${msg} ${a} != ${b}`);

test('bản sao dữ liệu trong web/ giống nguồn duy nhất data/', () => {
  assert.equal(fs.readFileSync(path.join(here, '..', 'data', 'tcm_28_pulses_haptic.json'), 'utf8'), read('data/tcm_28_pulses_haptic.json'));
});

test('danh mục: 28 mạch, 5 kiểu nhịp, tham số nhịp đặc biệt', () => {
  assert.equal(catalog.pulses.length, 28);
  assert.equal(new Set(catalog.pulses.map((p) => p.rhythm)).size, 5);
  assert.equal(P('dai').pauseEveryNBeats, 4);
  assert.equal(P('xuc').pauseProbability, 0.18);
  assert.equal(P('phu').nameHan, '浮脉');
});

test('danh mục: từ chối dữ liệu hỏng', () => {
  const base = JSON.parse(read('data/tcm_28_pulses_haptic.json'));
  const mut = (f) => { const c = structuredClone(base); c.pulses = [c.pulses[0]]; f(c.pulses[0], c); return JSON.stringify(c); };
  assert.doesNotThrow(() => C.parseCatalog(mut(() => {})));
  for (const f of [
    (p) => { p.depth_amplitude.phu = 1.5; }, (p) => { p.depth_amplitude.phu = -0.1; }, (p) => { p.rhythm_type = 'weird'; },
    (p) => { p.rhythm_type = 'intermittent_regular'; }, (p) => { p.rhythm_type = 'intermittent_irregular'; },
    (p) => { p.rhythm_type = 'intermittent_regular'; p.pause_every_n_beats = 0; }, (p) => { p.default_bpm = 500; },
    (p) => { p.bpm_range = [80, 60]; }, (p) => { p.upstroke_ms = 1; }, (p) => { p.id = ''; },
    (p, c) => { c.schema_version = '2.0.0'; }, (p, c) => { c.pulses = []; }, (p, c) => { c.pulses = [p, p]; },
  ]) assert.throws(() => C.parseCatalog(mut(f)), C.PulseDataError);
  assert.throws(() => C.parseCatalog('{'), C.PulseDataError);
  assert.throws(() => C.parseCatalog('[]'), C.PulseDataError);
});

test('vector chuẩn Kotlin: đường cong biên độ', () => {
  for (const row of golden.amplitude) {
    row.values.forEach((e, i) => near(C.amplitudeAt(P(row.id).depthAmplitude, i / 20, row.mode), e, 2e-6, `${row.id} ${row.mode} F=${i / 20}`));
  }
});

test('vector chuẩn Kotlin: hiệu chuẩn', () => {
  const g = golden.calibration, cal = new C.ChannelCalibration(g.light, g.medium, g.hard);
  g.raws.forEach((r, i) => near(cal.normalize(r), g.normalized[i], 2e-6, `raw=${r}`));
});

test('vector chuẩn Kotlin: dạng sóng', () => {
  const g = golden.waveform, w = C.synthWaveform(P(g.id), g.intervalMs);
  assert.equal(w.points.length, g.points.length);
  w.points.forEach((p, i) => { near(p.tMs, g.points[i][0]); near(p.level, g.points[i][1]); });
});

test('vector chuẩn Kotlin: nhịp (nguồn ngẫu nhiên có kịch bản)', () => {
  for (const c of golden.rhythm.cases) {
    const s = new C.BeatScheduler(P(c.id), scripted(golden.rhythm.uniform));
    c.beats.forEach((e, i) => {
      const b = s.next(c.force);
      assert.equal(b.silent, e.silent, `${c.id}#${i}`); near(b.intervalMs, e.interval, 2e-6, `${c.id}#${i}`); near(b.amplitudeScale, e.scale, 2e-6, `${c.id}#${i}`);
    });
  }
});

test('vector chuẩn Kotlin: gói BLE giống từng byte', () => {
  const b = golden.ble;
  assert.equal(C.toHex([C.crc8(Uint8Array.from(Buffer.from('123456789')))]), b.crc8_check);
  const up = C.decodeUplink(Uint8Array.from(Buffer.from(b.uplink[0].hex, 'hex')));
  assert.deepEqual(up, { thon: b.uplink[0].thon, quan: b.uplink[0].quan, xich: b.uplink[0].xich });
  assert.equal(C.toHex(C.encodeCalibration(b.calibration.channel, b.calibration.light, b.calibration.medium, b.calibration.hard)), b.calibration.hex);
  assert.equal(C.toHex(C.encodeStop()), b.stop);
  assert.equal(C.toHex(C.encodeStart(b.start.mask)), b.start.hex);
  assert.equal(b.profiles.length, 28);
  for (const e of b.profiles) assert.equal(C.toHex(C.encodeProfile(C.downlinkProfileFrom(P(e.id)))), e.hex, e.id);
});

test('vector chuẩn Kotlin: trọng số bộ (Đoản mạnh nhất ở Quan)', () => {
  const g = golden.playback;
  for (const r of C.REGIONS) {
    const plan = C.planBeat(P(g.id), new C.BeatScheduler(P(g.id), scripted([0.5])), { [r]: g.force });
    near(plan.peak, g.peaks[r], 2e-6, r);
  }
});

test('phân tầng + trễ, mốc tầng đúng đặc tả', () => {
  assert.equal(C.Zones.classify(0.049), 0); assert.equal(C.Zones.classify(0.05), 1);
  assert.equal(C.Zones.classify(0.35), 2); assert.equal(C.Zones.classify(0.7), 2); assert.equal(C.Zones.classify(0.701), 3);
  const t = new C.DepthTracker();
  assert.deepEqual([0.03, 0.2, 0.36, 0.371, 0.34, 0.329, 0.95, 0].map((f) => t.update(f)), [0, 1, 1, 2, 2, 1, 3, 0]);
});

test('hiệu chuẩn: neo đúng, từ chối mức không phân biệt, hợp nhất 0.6/0.4', () => {
  const c = new C.ChannelCalibration(0.04, 0.09, 0.15);
  near(c.normalize(0.04), 0.2, 1e-9); near(c.normalize(0.09), 0.525, 1e-9); near(c.normalize(0.15), 0.85, 1e-9);
  assert.equal(c.normalize(-5), 0); assert.equal(c.normalize(5), 1);
  assert.throws(() => new C.ChannelCalibration(0.5, 0.5, 0.5), C.CalibrationError);
  assert.throws(() => new C.ChannelCalibration(0.09, 0.04, 0.15), C.CalibrationError);
  assert.throws(() => new C.ChannelCalibration(NaN, 1, 2), C.CalibrationError);
  assert.throws(() => new C.FingerCalibration(null, null), C.CalibrationError);
  const both = new C.FingerCalibration(new C.ChannelCalibration(0.2, 0.5, 0.9), c);
  near(both.fuse({ pressure: 0.5, size: 0.15 }), 0.6 * 0.525 + 0.4 * 0.85, 1e-9);
  const r = C.FingerCalibration.fromJSON(JSON.parse(JSON.stringify(both)));
  assert.equal(r.pressure.medium, 0.5);
  assert.throws(() => C.FingerCalibration.fromJSON({ v: 2 }), C.CalibrationError);
  assert.throws(() => C.FingerCalibration.fromJSON(null), C.CalibrationError);
});

test('máy ghi hiệu chuẩn: có lực thật / pressure cố định 0.5 / ít mẫu / phẳng', () => {
  const run = (level) => { const r = new C.CalibrationRecorder(); r.start(0); for (let i = 0; i < 100; i++) { const t = i * 50, ph = r.phaseAt(t); if (ph === null) break; r.addSample(t, level(ph)); } return r; };
  const real = run((ph) => [{ pressure: 0.15, size: 0.04 }, { pressure: 0.4, size: 0.09 }, { pressure: 0.8, size: 0.15 }][ph]).finish();
  assert.ok(real.pressure && real.size);
  assert.equal(C.Zones.classify(real.fuse({ pressure: 0.4, size: 0.09 })), C.Depth.TRUNG);
  const fixed = run((ph) => ({ pressure: 0.5, size: [0.04, 0.09, 0.15][ph] })).finish();
  assert.equal(fixed.pressure, null); assert.ok(fixed.size);
  const few = new C.CalibrationRecorder(); few.start(0); for (let i = 0; i < 3; i++) few.addSample(i * 50, { pressure: 0.5, size: 0.05 });
  assert.throws(() => few.finish(), C.CalibrationError);
  assert.throws(() => run(() => ({ pressure: 0.5, size: 0.08 })).finish(), C.CalibrationError);
  const r = new C.CalibrationRecorder(); assert.equal(r.phaseAt(0), null); r.start(1000);
  assert.deepEqual([1000, 2700, 4400, 5999].map((t) => r.phaseAt(t)), [0, 1, 2, 2]); assert.equal(r.phaseAt(6000), null);
  assert.equal(r.addSample(1100, { pressure: NaN, size: 1 }), null);
});

test('nội suy: không vượt biên, liên tục, im lặng khi không chạm', () => {
  for (const p of catalog.pulses) {
    const d = p.depthAmplitude, lo = Math.min(d.phu, d.trung, d.tram), hi = Math.max(d.phu, d.trung, d.tram);
    for (let i = 20; i <= 100; i++) { const v = C.amplitudeAt(d, i / 100); assert.ok(v >= lo - 1e-9 && v <= hi + 1e-9, `${p.id} ${i}`); }
    for (const m of ['LINEAR', 'MONOTONE_CUBIC']) { let prev = C.amplitudeAt(d, 0, m); for (let i = 1; i <= 1000; i++) { const v = C.amplitudeAt(d, i / 1000, m); assert.ok(Math.abs(v - prev) < 0.03, `${p.id} ${m} ${i}`); prev = v; } }
  }
  assert.equal(C.amplitudeAt(P('phu').depthAmplitude, 0), 0);
});

test('nhịp: Xúc/Kết ngẫu nhiên, Đại 4 nghỉ 1, Tán/Sáp ±12 %', () => {
  const run = (id, u, n, f = 0.3) => { const s = new C.BeatScheduler(P(id), scripted(u)); return Array.from({ length: n }, () => s.next(f)); };
  assert.deepEqual(run('xuc', [0.5, 0.1, 0.05, 0.9, 0.1, 0.3, 0.17, 0.19], 8).filter((b) => b.silent).map((b) => b.index), [1, 4, 6]);
  const ket = run('ket', [0], 40); assert.ok(!ket[0].silent); assert.ok(ket.every((b, i) => i === 0 || !(b.silent && ket[i - 1].silent)));
  assert.deepEqual(run('dai', [0.5], 15).filter((b) => b.silent).map((b) => b.index), [4, 9, 14]);
  assert.ok(run('phu', [0], 30).every((b) => !b.silent));
  for (const id of ['tan', 'sap']) {
    const base = 60000 / P(id).defaultBpm;
    near(run(id, [0], 1)[0].intervalMs, base * 0.88, 1e-6); near(run(id, [0.999999999], 1)[0].intervalMs, base * 1.12, 1e-3);
    assert.equal(run(id, [0.5], 1, 0.2)[0].amplitudeScale, 1); assert.ok(run(id, [0.5], 1, 1)[0].amplitudeScale < 1);
  }
  assert.equal(run('hoat', [0.5], 1, 1)[0].amplitudeScale, 1);
  assert.equal(new C.BeatScheduler(P('tat'), scripted([0.5]), 999).bpm, 180);
});

test('sóng: mọi mạch nằm trong chu kỳ ở mọi bpm; có khuyết trước sóng dội', () => {
  for (const p of catalog.pulses) for (const bpm of [35, p.defaultBpm, 150, 180]) {
    const iv = 60000 / bpm, w = C.synthWaveform(p, iv);
    assert.ok(w.durationMs <= iv * 0.85 + 1e-9, `${p.id}@${bpm}`);
    assert.ok(w.points.every((q, i) => i === 0 || q.tMs > w.points[i - 1].tMs));
  }
  const h = P('hoat'), w = C.synthWaveform(h, 750);
  near(w.levelAt(h.upstrokeMs), 1, 1e-12); assert.ok(w.levelAt(h.upstrokeMs + 45) < w.levelAt(h.upstrokeMs + 80));
});

test('mẫu rung web: hợp lệ cho navigator.vibrate và tổng thời gian đúng', () => {
  for (const p of catalog.pulses) {
    const w = C.synthWaveform(p, 60000 / p.defaultBpm), pat = C.toVibratePattern(w, 0.9);
    assert.ok(pat.length > 0 && pat.length % 2 === 1 || pat.length % 2 === 0);
    assert.ok(pat.every((d) => Number.isInteger(d) && d >= 0), p.id);
    assert.ok(pat.reduce((a, b) => a + b, 0) <= Math.ceil(w.durationMs / 20) * 20 + 1e-9);
  }
  assert.deepEqual(C.toVibratePattern(C.synthWaveform(P('phu'), 800), 0), []);
});

test('trọng số bộ + nhiều ngón + nhịp bỏ', () => {
  const doan = P('doan'), plan = (r) => C.planBeat(doan, new C.BeatScheduler(doan, scripted([0.5])), { [r]: 0.525 });
  near(plan('quan').peak, 0.65, 1e-9); near(plan('thon').peak, 0.65 * 0.35, 1e-9); near(plan('xich').peak, 0.65 * 0.3, 1e-9);
  const dong = P('dong'), three = C.planBeat(dong, new C.BeatScheduler(dong, scripted([0.5])), { thon: 0.525, quan: 0.525, xich: 0.525 });
  assert.equal(three.dominantRegion, 'quan'); near(three.peak, 0.9, 1e-9);
  assert.ok(C.planBeat(doan, new C.BeatScheduler(doan, scripted([0.5])), {}).silent);
  const dai = P('dai'), s = new C.BeatScheduler(dai, scripted([0.5])), plans = Array.from({ length: 5 }, () => C.planBeat(dai, s, { quan: 0.5 }));
  assert.ok(plans[4].silent && plans[4].peak === 0 && plans.slice(0, 4).every((p) => !p.silent));
});

test('khám 18 điểm: kế hoạch + giữ đúng tầng 5 nhịp liên tiếp', () => {
  assert.equal(C.EXAM_PLAN.length, 18);
  assert.deepEqual(C.EXAM_PLAN[0], { hand: 'left', region: 'thon', depth: 1 });
  assert.deepEqual(C.EXAM_PLAN[17], { hand: 'right', region: 'xich', depth: 3 });
  const sim = (h, until, depthAt) => { for (let t = 0; t <= until; t += 20) { h.onSample(depthAt(t), t); if (t > 0 && t % 800 === 0 && h.onBeat(t)) return t; } return null; };
  assert.equal(sim(new C.HoldTracker(2), 10000, () => 2), 4000);
  assert.equal(sim(new C.HoldTracker(2), 12000, (t) => (t >= 1700 && t <= 2000 ? 3 : 2)), 6400);
  assert.equal(sim(new C.HoldTracker(1), 10000, (t) => (t >= 1700 && t <= 1740 ? 2 : 1)), 4000);
  assert.equal(sim(new C.HoldTracker(3), 20000, () => 2), null);
  const g = new C.HoldTracker(2); for (let t = 0; t <= 2400; t += 20) { g.onSample(2, t); if (t > 0 && t % 800 === 0) g.onBeat(t); }
  assert.equal(g.consecutive, 3); g.onLift(); assert.equal(g.consecutive, 0); assert.equal(g.onBeat(3200), false);
  assert.throws(() => new C.HoldTracker(0));
  const s = new C.ExamSession(); for (let i = 0; i < 17; i++) s.record(0.5); s.skip();
  assert.ok(s.isFinished && s.current === null); assert.throws(() => s.record(0.5));
  assert.equal(s.results.filter((r) => r.captured).length, 17);
});

test('BLE: uplink, hỏng gói, khung điều khiển', () => {
  assert.deepEqual(C.decodeUplink(Uint8Array.from([0x34, 0x02, 0xff, 0x0f, 0, 0])), { thon: 0x234, quan: 4095, xich: 0 });
  for (const n of [0, 5, 7, 20]) assert.throws(() => C.decodeUplink(new Uint8Array(n)), C.PacketError);
  assert.throws(() => C.decodeUplink(Uint8Array.from([0, 0x10, 0, 0, 0, 0])), C.PacketError);
  assert.equal(C.encodeProfile(C.downlinkProfileFrom(P('hoat'))).length, 16);
  assert.throws(() => C.encodeProfile({ ...C.downlinkProfileFrom(P('phu')), bpmTenths: 10 }), C.PacketError);
  assert.throws(() => C.encodeStart(0), C.PacketError); assert.throws(() => C.encodeStart(8), C.PacketError);
  assert.equal(C.encodeCalibration(2, 300, 1200, 3000).length, 10);
  assert.throws(() => C.encodeCalibration(3, 1, 2, 3), C.PacketError); assert.throws(() => C.encodeCalibration(0, 5, 5, 6), C.PacketError);
  assert.deepEqual(C.decodeBeatNotice(Uint8Array.from([7, 1, 255])), { seq: 7, silent: true, peak: 1 });
  assert.throws(() => C.decodeBeatNotice(new Uint8Array(2)), C.PacketError);
  const m = new C.PadChannelMapper(); near(m.force('quan', 2048), 0.5, 1e-3);
  m.setCalibration('quan', new C.ChannelCalibration(400, 1200, 2400)); near(m.force('quan', 1200), 0.525, 1e-9);
});
