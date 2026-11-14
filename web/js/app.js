import * as C from './core.js';
import { WebHapticTransport, AudioPulse } from './haptic.js';
import { WebBleTransport } from './webble.js';

const $ = (s) => document.querySelector(s);
const store = {
  get(k) { try { return localStorage.getItem(k); } catch { return null; } },
  set(k, v) { try { localStorage.setItem(k, v); } catch { /* chế độ riêng tư: bỏ qua */ } },
  remove(k) { try { localStorage.removeItem(k); } catch { /* bỏ qua */ } },
};
const jget = (k, d) => { try { const v = store.get(k); return v ? JSON.parse(v) : d; } catch { return d; } };

// ---------- Cài đặt & hiệu chuẩn (lưu cục bộ) ----------
const CAL_KEY = 'pulsemaster.finger_cal_v1', SET_KEY = 'pulsemaster.settings_v1', HIST_KEY = 'pulsemaster.history_v1', QUIZ_KEY = 'pulsemaster.quiz_v1';
// Mặc định PWM: chủ dự án thử máy thật (Android + Chrome, 2026-11-14) thấy PWM dễ cảm nhận nhất.
// sound = kênh "rung bằng loa"; mặc định bật khi trình duyệt không có navigator.vibrate. vibFelt: null = chưa hỏi.
const settings = Object.assign({ vibMode: 'pwm', vibGain: 1, forceSrc: 'auto', wake: true, sound: !WebHapticTransport.canVibrate, soundFreq: 180, vibFelt: null }, jget(SET_KEY, {}));
if (!(settings.vibMode in C.VIBRATION_MODES)) settings.vibMode = 'pwm';
let calSaved = false; // đã có hiệu chuẩn hợp lệ → mới tin số liệu cảm biến
const saveSettings = () => store.set(SET_KEY, JSON.stringify(settings));
const defaultCal = () => C.FingerCalibration.defaultSizeOnly(8, 18, 32); // px tiếp xúc phổ biến; nên hiệu chuẩn
function loadCal() {
  const raw = store.get(CAL_KEY);
  calSaved = false;
  if (!raw) return defaultCal();
  try { const c = C.FingerCalibration.fromJSON(JSON.parse(raw)); calSaved = true; return c; } catch { return defaultCal(); }
}

// ---------- Dữ liệu mạch ----------
let catalog = null;
try {
  const res = await fetch('data/tcm_28_pulses_haptic.json');
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  catalog = C.parseCatalog(await res.text());
} catch (e) {
  const box = $('#fatal'); box.hidden = false;
  box.textContent = `Không nạp được dữ liệu mạch: ${e.message}`;
}

// ---------- Trạng thái ----------
const phone = new WebHapticTransport(loadCal(), settings);
const ble = new WebBleTransport();
let active = phone;
let mode = 'exam';
let profile = null;
let session = new C.ExamSession();
let quiz = null;
let hold = null;
let held = [];
let gaugeForce = 0, gaugeIn = false, target = null;
let lastSaved = null;
const beats = [];
const regionEls = Object.fromEntries(C.REGIONS.map((r) => [r, document.querySelector(`.pad[data-region="${r}"]`)]));

// ---------- Tab ----------
const tabs = ['exam', 'calib', 'about'];
function showTab(t) {
  for (const u of tabs) { $(`#t-${u}`).setAttribute('aria-selected', String(u === t)); $(`#tab-${u}`).hidden = u !== t; }
  if (t === 'exam') { active.startFeedback(C.REGIONS); drawGauge(); } else active.stopFeedback();
  if (t === 'about') refreshAbout();
}
for (const t of tabs) $(`#t-${t}`).addEventListener('click', () => showTab(t));

// ---------- Sự kiện từ transport ----------
const listener = {
  onState(s) {
    const el = $('#transport');
    if (s.name === 'idle') el.textContent = active === ble ? 'Chưa kết nối đệm – bấm “Kết nối đệm”' : '';
    else if (s.name === 'connecting') el.textContent = 'Đang kết nối đệm…';
    else if (s.name === 'ready') {
      el.textContent = s.note || (active === ble ? 'Đã kết nối đệm 3 kênh (BLE)' : WebHapticTransport.canVibrate ? 'Dùng động cơ rung của thiết bị' : '');
      if (!$('#tab-exam').hidden) active.startFeedback(C.REGIONS);
    } else el.textContent = `Lỗi: ${s.reason}`;
    el.style.color = s.name === 'failed' || (s.name === 'ready' && s.note) ? 'var(--accent)' : '';
  },
  onForce(region, force, depth, t) {
    regionEls[region].dataset.depth = depth;
    regionEls[region].querySelector('small').textContent = depth ? C.DEPTH_LABEL[depth].split(' ')[0] : '';
    if (mode === 'free' || mode === 'quiz') {
      if (force > 0) { gaugeForce = force; gaugeIn = false; target = null; drawGauge(); }
      if (mode === 'free' && force > 0) $('#status').textContent = `${C.REGION_LABEL[region]}: ${C.DEPTH_LABEL[depth]} · lực ${Math.round(force * 100)}%`;
      return;
    }
    const pt = session.current;
    if (!pt || region !== pt.region) return;
    gaugeForce = force; gaugeIn = depth === pt.depth; drawGauge();
    hold?.onSample(depth, t);
    if (depth === pt.depth) held.push(force); else held.length = 0;
  },
  onBeat(e) {
    beats.push({ start: e.startMs, peak: e.peak, wave: e.plan.waveform, silent: e.silent });
    requestWave();
    if (mode !== 'exam') return;
    const pt = session.current;
    if (!pt || !hold) return;
    if (hold.onBeat(e.startMs)) capture(pt);
    refreshStatus();
  },
};

function activate(kind) {
  active.stopFeedback(); active.close(); active.listener = null;
  if (kind === 'phone') phone.setCalibration(loadCal());
  active = kind === 'ble' ? ble : phone;
  active.listener = listener;
  $('#connect').hidden = kind !== 'ble';
  if (profile) active.setProfile(profile, currentBpm());
  active.open();
  beats.length = 0;
}
const currentBpm = () => Number($('#bpm').value) || (profile ? profile.defaultBpm : 72);

function applyProfile(p) {
  profile = p;
  const bpm = $('#bpm');
  bpm.min = Math.max(C.MIN_BPM, Math.floor(p.bpmMin)); bpm.max = Math.min(C.MAX_BPM, Math.ceil(p.bpmMax)); bpm.value = p.defaultBpm;
  $('#bpmval').textContent = Math.round(p.defaultBpm);
  $('#feel').textContent = mode === 'quiz' ? 'Mạch được giấu: hãy bắt mạch ở các tầng Phù–Trung–Trầm rồi chọn đáp án.' : `${p.category} · ${p.clinicalFeel}`;
  active.setProfile(p, p.defaultBpm);
  beats.length = 0;
}

// ---------- Khám 18 điểm ----------
function prepare() {
  const pt = mode === 'exam' ? session.current : null;
  hold = pt ? new C.HoldTracker(pt.depth) : null;
  target = pt ? pt.depth : null; gaugeIn = false; held = [];
  for (const r of C.REGIONS) regionEls[r].classList.toggle('target', !!pt && pt.region === r);
  drawGauge(); refreshStatus();
}
function capture(pt) {
  session.record(held.length ? held.reduce((a, b) => a + b, 0) / held.length : 0, true);
  held = []; prepare();
  if (session.isFinished) { saveHistory(); showSummary(); }
}
function refreshStatus() {
  if (mode === 'free') { $('#status').textContent = 'Chế độ tự do: đặt ngón lên Thốn/Quan/Xích và thay đổi lực để cảm nhận Phù–Trung–Trầm.'; return; }
  if (mode === 'quiz') { return; }
  const pt = session.current;
  $('#status').textContent = pt
    ? `Điểm ${session.index + 1}/${session.total}: ${C.pointLabel(pt)}\nGiữ đúng tầng: ${hold?.consecutive ?? 0}/${hold?.required ?? 5} nhịp`
    : `Hoàn tất ${session.total}/${session.total} điểm`;
}
function summaryText() {
  return `Kết quả: ${session.results.filter((r) => r.captured).length}/${session.total} đạt (${profile ? profile.nameVi : ''})\n\n${session.results.length ? session.summary() : 'Chưa có điểm nào.'}`;
}
function currentCsv() { return C.examToCsv(session.results, profile, new Date().toISOString()); }
function showSummary() {
  $('#sumtext').textContent = summaryText();
  const d = $('#summary'); if (!d.open) (d.showModal ? d.showModal() : d.setAttribute('open', ''));
}
function saveHistory() {
  if (!session.results.some((r) => r.captured) || lastSaved === session.results) return; // không lưu phiên không có điểm đạt nào
  lastSaved = session.results;
  const hist = jget(HIST_KEY, []);
  hist.unshift({ date: new Date().toISOString(), pulseId: profile.id, pulseName: profile.nameVi, captured: session.results.filter((r) => r.captured).length, total: session.total, csv: currentCsv() });
  store.set(HIST_KEY, JSON.stringify(hist.slice(0, 50)));
}
function restart() { session = new C.ExamSession(); lastSaved = null; prepare(); }

// ---------- Đố mạch ----------
const quizStats = () => jget(QUIZ_KEY, { correct: 0, total: 0 });
function newRound() {
  const p = quiz.newRound();
  $('#quizinfo').textContent = ''; $('#guessbtn').disabled = false; $('#guess').disabled = false;
  applyProfile(p); gaugeForce = 0; drawGauge();
}
function guess() {
  let r;
  try { r = quiz.guess($('#guess').value); } catch (e) { $('#quizinfo').textContent = e.message; return; }
  const st = quizStats(); st.total++; if (r.correct) st.correct++; store.set(QUIZ_KEY, JSON.stringify(st));
  $('#quizinfo').textContent = `${r.correct ? 'Đúng!' : 'Chưa đúng.'} Đáp án: ${r.answer.nameVi} ${r.answer.nameHan} – ${r.answer.clinicalFeel}\nPhiên này: ${quiz.correct}/${quiz.total}`;
  $('#guessbtn').disabled = true;
}

function setMode(m) {
  mode = m;
  $('#quiz').hidden = m !== 'quiz';
  $('#examrow').hidden = m !== 'exam';
  $('#pulselbl').hidden = m === 'quiz';
  if (m === 'quiz') { quiz = quiz || new C.QuizSession(catalog.pulses); newRound(); }
  else applyProfile(catalog.require($('#pulse').value));
  target = null; hold = null;
  for (const r of C.REGIONS) regionEls[r].classList.remove('target');
  if (m === 'exam') prepare(); else { drawGauge(); refreshStatus(); if (m === 'quiz') $('#status').textContent = 'Đố mạch'; }
}

// ---------- Pad: nhận PointerEvent ----------
function simVisible() { return settings.forceSrc === 'slider' || (settings.forceSrc === 'auto' && !(navigator.maxTouchPoints > 0)); }
/** Nguồn lực thực dùng cho một con trỏ: 'slider' | 'drag' | 'sensor'. Tự động: chuột → thanh; ngón tay → cảm biến
 *  nếu ĐÃ hiệu chuẩn thành công, chưa thì kéo dọc (nhiều máy Android không báo lực/diện tích – máy thật đã gặp). */
function sourceFor(pointerType) {
  const mouse = pointerType === 'mouse' || pointerType === '';
  if (settings.forceSrc === 'slider' || (settings.forceSrc === 'auto' && mouse)) return 'slider';
  if (settings.forceSrc === 'drag' || (settings.forceSrc === 'auto' && !calSaved)) return 'drag';
  return 'sensor';
}
const touchSource = () => sourceFor('touch');
function inputFor(e, el) {
  const src = sourceFor(e.pointerType);
  if (src === 'slider') return { manual: Number($('#sim').value) / 100 };
  if (src === 'drag') { const r = el.getBoundingClientRect(); return { manual: C.forceFromDrag(e.clientY - r.top, r.height) }; }
  return { raw: { pressure: e.pressure, size: Math.max(e.width || 0, e.height || 0) } };
}
// Khoảng giá trị thô đã thấy từ ngón tay thật (để chẩn đoán máy có báo lực/diện tích hay không)
const seen = { n: 0, pMin: Infinity, pMax: -Infinity, sMin: Infinity, sMax: -Infinity };
function noteRaw(e) {
  if (e.pointerType !== 'touch' && e.pointerType !== 'pen') return;
  const p = e.pressure, s = Math.max(e.width || 0, e.height || 0);
  if (!Number.isFinite(p) || !Number.isFinite(s)) return;
  seen.n++; seen.pMin = Math.min(seen.pMin, p); seen.pMax = Math.max(seen.pMax, p); seen.sMin = Math.min(seen.sMin, s); seen.sMax = Math.max(seen.sMax, s);
}
function syncPads() {
  const drag = touchSource() === 'drag';
  for (const r of C.REGIONS) regionEls[r].classList.toggle('drag', drag);
  $('#draghelp').hidden = !drag || !(navigator.maxTouchPoints > 0);
}
let rawQueued = null;
function showRaw(e, r) {
  rawQueued = `loại: ${e.pointerType || '?'} | pressure ${e.pressure?.toFixed(2)} | rộng×cao ${e.width?.toFixed?.(1)}×${e.height?.toFixed?.(1)} px\n→ lực chuẩn hóa ${r.force.toFixed(2)} (${C.DEPTH_LABEL[r.depth]})`;
  requestAnimationFrame(() => { if (rawQueued) { $('#raw').textContent = rawQueued; rawQueued = null; } });
}
const down = new Set();
let weak = 0; // số mẫu liên tiếp ở ngón tay thật mà lực < ngưỡng chạm
function hintIfNoForce(e, r) {
  const real = (e.pointerType === 'touch' || e.pointerType === 'pen') && sourceFor(e.pointerType) === 'sensor';
  weak = real && r.force < C.Zones.NONE_MAX ? weak + 1 : 0;
  $('#hint').hidden = weak < 5;
}
let wakeLock = null;
async function keepAwake() {
  if (!settings.wake || !('wakeLock' in navigator) || wakeLock) return;
  try { wakeLock = await navigator.wakeLock.request('screen'); wakeLock.addEventListener('release', () => { wakeLock = null; }); } catch { wakeLock = null; }
}
const sleepOk = () => { try { wakeLock?.release(); } catch { /* bỏ qua */ } wakeLock = null; };

for (const r of C.REGIONS) {
  const el = regionEls[r];
  const mark = el.querySelector('.mark');
  const feed = (e) => { if (active === phone && down.has(e.pointerId)) { noteRaw(e); const res = phone.onForceInput(r, inputFor(e, el)); mark.style.bottom = `${Math.round(res.force * 100)}%`; showRaw(e, res); hintIfNoForce(e, res); } };
  el.addEventListener('pointerdown', (e) => { e.preventDefault(); down.add(e.pointerId); try { el.setPointerCapture(e.pointerId); } catch { /* con trỏ tổng hợp */ } if (settings.sound) phone.audio.unlock(); keepAwake(); feed(e); });
  el.addEventListener('pointermove', feed);
  const up = (e) => { if (down.delete(e.pointerId) && active === phone) { phone.onRelease(r); weak = 0; $('#hint').hidden = true; } };
  el.addEventListener('pointerup', up); el.addEventListener('pointercancel', up);
  el.addEventListener('contextmenu', (e) => e.preventDefault());
}

// ---------- Vẽ: thanh lực + sóng 60 fps ----------
const css = (n) => getComputedStyle(document.documentElement).getPropertyValue(n).trim();
function fit(canvas) {
  const dpr = window.devicePixelRatio || 1, r = canvas.getBoundingClientRect();
  const w = Math.max(1, Math.round(r.width * dpr)), h = Math.max(1, Math.round(r.height * dpr));
  if (canvas.width !== w || canvas.height !== h) { canvas.width = w; canvas.height = h; }
  const ctx = canvas.getContext('2d'); ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  return { ctx, w: r.width, h: r.height };
}
function drawGauge() {
  const { ctx, w, h } = fit($('#gauge')), pad = 8, top = pad, H = h - 2 * pad;
  const y = (f) => top + H * (1 - f);
  ctx.clearRect(0, 0, w, h);
  const colors = [css('--none'), css('--phu'), css('--trung'), css('--tram')];
  ctx.font = '12px system-ui'; ctx.textAlign = 'center';
  for (let d = 0; d < 4; d++) {
    const [lo, hi] = C.Zones.range(d);
    ctx.fillStyle = colors[d]; ctx.fillRect(pad, y(hi), w - 2 * pad, y(lo) - y(hi));
    if (d) { ctx.fillStyle = css('--fg'); ctx.fillText(C.DEPTH_LABEL[d].split(' ')[0], w / 2, (y(lo) + y(hi)) / 2 + 4); }
  }
  if (target) {
    const [lo, hi] = C.Zones.range(target);
    ctx.lineWidth = 4; ctx.strokeStyle = gaugeIn ? css('--ok') : css('--info');
    ctx.strokeRect(pad, y(hi), w - 2 * pad, y(lo) - y(hi));
  }
  ctx.fillStyle = '#d32f2f'; ctx.fillRect(pad - 4, y(gaugeForce) - 2, w - 2 * pad + 8, 4);
}
let waveRaf = 0;
const WINDOW = 4000;
function requestWave() { if (!waveRaf) waveRaf = requestAnimationFrame(drawWave); }
function drawWave() {
  waveRaf = 0;
  const { ctx, w, h } = fit($('#wave')), now = performance.now(), pad = 6;
  while (beats.length && beats[0].start + beats[0].wave.durationMs < now - WINDOW) beats.shift();
  ctx.clearRect(0, 0, w, h);
  ctx.strokeStyle = css('--line'); ctx.lineWidth = 1;
  for (let i = 1; i <= 3; i++) { ctx.beginPath(); ctx.moveTo(0, h * i / 4); ctx.lineTo(w, h * i / 4); ctx.stroke(); }
  ctx.beginPath(); ctx.strokeStyle = css('--accent'); ctx.lineWidth = 3; ctx.lineJoin = 'round';
  for (let x = 0; x <= w; x += 3) {
    const t = now - WINDOW + (x / w) * WINDOW; let v = 0;
    for (const b of beats) { const dt = t - b.start; if (dt >= 0 && dt < b.wave.durationMs) v = Math.max(v, b.wave.levelAt(dt) * b.peak); }
    const yy = h - pad - (h - 2 * pad) * v;
    x === 0 ? ctx.moveTo(x, yy) : ctx.lineTo(x, yy);
  }
  ctx.stroke();
  ctx.fillStyle = css('--muted'); ctx.font = '11px system-ui'; ctx.textAlign = 'center';
  for (const b of beats) if (b.silent) { const bx = ((b.start - (now - WINDOW)) / WINDOW) * w; if (bx >= 0 && bx <= w) ctx.fillText('nghỉ', bx, 14); }
  if (beats.length) requestWave(); // chỉ vẽ liên tục khi còn nhịp
}
window.addEventListener('resize', () => { drawGauge(); requestWave(); });

// ---------- Hiệu chuẩn ----------
const rec = new C.CalibrationRecorder(5000);
let recording = false, fingerDown = false, ticker = 0, calLast = null;
function ctick() {
  const now = performance.now();
  // Lấy mẫu theo đồng hồ (20 lần/giây) từ giá trị chạm mới nhất: ngón tay đứng yên chỉ sinh rất ít pointermove,
  // nếu chỉ lấy mẫu theo sự kiện sẽ không đủ mẫu mỗi pha.
  if (recording && fingerDown && calLast) rec.addSample(now, calLast);
  $('#cprog').value = Math.round(rec.progress(now) * 1000);
  const ph = rec.phaseAt(now);
  if (ph === null) { cfinish(); return; }
  $('#cphase').textContent = C.CALIBRATION_PHASES[ph] + (fingerDown ? '' : '\n(đặt ngón tay lên vùng bên dưới)');
  ticker = setTimeout(ctick, 50);
}
function cfinish() {
  recording = false; clearTimeout(ticker); $('#cprog').value = 1000;
  try {
    const cal = rec.finish();
    store.set(CAL_KEY, JSON.stringify(cal)); phone.setCalibration(cal); calSaved = true; syncPads();
    $('#cphase').textContent = 'Đã lưu hiệu chuẩn'; $('#cdrag').hidden = true;
    const lines = [cal.pressure ? 'Cảm biến lực thật: CÓ (60% lực + 40% diện tích)' : 'Cảm biến lực thật: KHÔNG – dùng diện tích tiếp xúc'];
    if (cal.size) lines.push(`Diện tích: nhẹ ${cal.size.light.toFixed(1)} · vừa ${cal.size.medium.toFixed(1)} · mạnh ${cal.size.hard.toFixed(1)}`);
    if (cal.pressure) lines.push(`Lực: nhẹ ${cal.pressure.light.toFixed(2)} · vừa ${cal.pressure.medium.toFixed(2)} · mạnh ${cal.pressure.hard.toFixed(2)}`);
    $('#cinfo').textContent = lines.join(' | ');
  } catch (e) {
    $('#cphase').textContent = 'Chưa đạt';
    $('#cinfo').textContent = (e instanceof C.CalibrationError ? e.message : `Lỗi: ${e.message}`) +
      ' Nếu số đo bên dưới gần như giống nhau ở cả ba pha thì máy này không báo lực/diện tích: hãy dùng chế độ kéo dọc (đang là mặc định khi chưa hiệu chuẩn).';
    $('#cdrag').hidden = false;
  }
  lastCalStats = calStats(); $('#cstats').textContent = lastCalStats; $('#cstats').hidden = false;
}
let lastCalStats = '';
/** Số đo từng pha (trung vị, min–max) để người dùng báo lại khi hiệu chuẩn không được. */
function calStats() {
  const med = (v) => { const s = [...v].sort((a, b) => a - b); return s.length ? s[Math.floor(s.length / 2)] : NaN; };
  const f = (v, d) => (v.length ? `${med(v).toFixed(d)} (${Math.min(...v).toFixed(d)}–${Math.max(...v).toFixed(d)})` : '—');
  return rec.samples.map((list, i) => `${['nhẹ', 'vừa', 'mạnh'][i]}: ${list.length} mẫu | pressure ${f(list.map((s) => s.pressure), 3)} | cỡ chạm ${f(list.map((s) => s.size), 1)} px`).join('\n');
}
$('#cstart').addEventListener('click', () => { recording = true; calLast = fingerDown ? calLast : null; $('#cinfo').textContent = ''; rec.start(performance.now()); clearTimeout(ticker); ctick(); });
$('#cclear').addEventListener('click', () => { store.remove(CAL_KEY); phone.setCalibration(defaultCal()); calSaved = false; syncPads(); $('#cinfo').textContent = 'Đã xóa. Quay về giá trị mặc định.'; });
$('#cdrag').addEventListener('click', () => { settings.forceSrc = 'drag'; saveSettings(); $('#forcesrc').value = 'drag'; syncSim(); syncPads(); $('#cinfo').textContent = 'Đã chuyển sang chế độ kéo dọc: ở màn Bắt mạch, kéo ngón lên = ấn mạnh hơn, kéo xuống = nhẹ hơn.'; });
const cp = $('#calpad');
const csample = (e) => {
  $('#craw').textContent = `loại: ${e.pointerType || '?'} | pressure ${e.pressure?.toFixed(3)} | rộng×cao ${e.width?.toFixed?.(1)}×${e.height?.toFixed?.(1)} px`;
  if (e.pointerType === 'mouse') { $('#cinfo').textContent = 'Chuột không đo được lực ấn – hãy mở trang này trên điện thoại/máy tính bảng.'; return; }
  fingerDown = true;
  calLast = { pressure: e.pressure, size: Math.max(e.width || 0, e.height || 0) };
};
cp.addEventListener('pointerdown', (e) => { e.preventDefault(); try { cp.setPointerCapture(e.pointerId); } catch { /* con trỏ tổng hợp: bỏ qua */ } csample(e); });
cp.addEventListener('pointermove', (e) => { if (fingerDown) csample(e); });
const cup = () => { fingerDown = false; calLast = null; };
cp.addEventListener('pointerup', cup); cp.addEventListener('pointercancel', cup);

// ---------- Cài đặt, thiết bị, lịch sử, PWA ----------
function syncSim() { $('#simrow').hidden = !simVisible(); }
function diagnostics() {
  const r = (a, b, d) => (seen.n ? `${a.toFixed(d)}–${b.toFixed(d)}` : 'chưa chạm');
  return [
    `PulseMaster chẩn đoán ${new Date().toISOString()}`,
    `UA: ${navigator.userAgent}`,
    `Màn hình: ${screen.width}×${screen.height} @${devicePixelRatio} · điểm chạm tối đa ${navigator.maxTouchPoints}`,
    `vibrate: ${WebHapticTransport.canVibrate ? 'có' : 'không'} · người dùng cảm thấy rung: ${settings.vibFelt === null ? 'chưa trả lời' : settings.vibFelt ? 'có' : 'KHÔNG'}`,
    `AudioContext: ${AudioPulse.supported ? (phone.audio.ctx ? phone.audio.ctx.state : 'chưa mở') : 'không'} · số nhịp đã phát bằng loa: ${phone.audio.played}`,
    `Cài đặt: ${JSON.stringify(settings)}`,
    `Nguồn lực đang dùng cho ngón tay: ${touchSource()} · hiệu chuẩn đã lưu: ${calSaved ? store.get(CAL_KEY) : 'chưa'}`,
    `Giá trị thô đã thấy ở màn Bắt mạch (${seen.n} mẫu): pressure ${r(seen.pMin, seen.pMax, 3)} · cỡ chạm ${r(seen.sMin, seen.sMax, 1)} px`,
    `Lần hiệu chuẩn gần nhất:\n${lastCalStats || '(chưa chạy trong lần mở này)'}`,
  ].join('\n');
}
function refreshAbout() {
  const caps = [
    ['Rung (navigator.vibrate)', WebHapticTransport.canVibrate ? 'có' : 'KHÔNG (iOS Safari…)'],
    ['Web Bluetooth (đệm 3 kênh)', WebBleTransport.supported ? 'có' : 'KHÔNG'],
    ['Ngữ cảnh an toàn (HTTPS)', window.isSecureContext ? 'có' : 'KHÔNG – cài PWA/BLE cần HTTPS'],
    ['Màn hình cảm ứng', navigator.maxTouchPoints > 0 ? `có (${navigator.maxTouchPoints} điểm)` : 'không'],
    ['Giữ màn hình sáng', 'wakeLock' in navigator ? 'có' : 'không'],
    ['Hiệu chuẩn ngón tay', store.get(CAL_KEY) ? 'đã lưu' : 'chưa – đang dùng mặc định'],
  ];
  $('#caps').replaceChildren(...caps.map(([k, v]) => { const li = document.createElement('li'); li.textContent = `${k}: ${v}`; return li; }));
  renderHistory();
  const q = quizStats(); $('#quizscore').textContent = q.total ? `Đố mạch (tổng): ${q.correct}/${q.total} đúng` : '';
}
function download(name, text) {
  const url = URL.createObjectURL(new Blob([text], { type: 'text/csv;charset=utf-8' }));
  const a = document.createElement('a'); a.href = url; a.download = name; document.body.append(a); a.click(); a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
function renderHistory() {
  const hist = jget(HIST_KEY, []), ul = $('#history');
  if (!hist.length) { const li = document.createElement('li'); li.textContent = 'Chưa có phiên khám nào hoàn tất.'; ul.replaceChildren(li); return; }
  ul.replaceChildren(...hist.map((h, i) => {
    const li = document.createElement('li'), span = document.createElement('span'), b = document.createElement('button');
    span.textContent = `${new Date(h.date).toLocaleString('vi-VN')} · ${h.pulseName} · ${h.captured}/${h.total}`;
    b.textContent = 'Tải CSV'; b.addEventListener('click', () => download(`pulsemaster-${h.pulseId}-${h.date.slice(0, 10)}-${i + 1}.csv`, h.csv));
    li.append(span, b); return li;
  }));
}
function soundTest() {
  if (!profile) return false;
  phone.audio.unlock();
  const w = C.synthWaveform(profile, 60000 / currentBpm());
  // AudioContext vừa resume có thể chưa 'running' ngay: thử lại sau một nhịp ngắn
  const go = () => phone.audio.play(w, 0.9, { freq: settings.soundFreq, gain: settings.vibGain });
  if (!go()) setTimeout(go, 150);
  return true;
}
function setSound(on) { settings.sound = on; saveSettings(); $('#sound').checked = on; if (on) phone.audio.unlock(); }
function vibInfo(ok) {
  $('#vibask').hidden = !(ok && WebHapticTransport.canVibrate);
  $('#vibinfo').textContent = !WebHapticTransport.canVibrate ? 'Trình duyệt này không hỗ trợ rung (iOS Safari không có navigator.vibrate). Hãy dùng Chrome/Edge trên Android.'
    : ok ? 'Đã gửi lệnh rung. Bạn có cảm thấy máy rung không? (trình duyệt không tự biết được – hãy trả lời bên dưới)'
      : 'Trình duyệt từ chối lệnh rung (cần chạm vào trang trước, và không ở chế độ yên lặng).';
}

function initSettingsUi() {
  const vm = $('#vibmode');
  for (const [k, label] of Object.entries(C.VIBRATION_MODES)) vm.add(new Option(label, k));
  vm.value = settings.vibMode; $('#forcesrc').value = settings.forceSrc; $('#wake').checked = settings.wake;
  $('#vibgain').value = Math.round(settings.vibGain * 100); $('#vibgainval').textContent = `${settings.vibGain.toFixed(1).replace('.', ',')}×`;
  vm.addEventListener('change', () => { settings.vibMode = vm.value; saveSettings(); });
  $('#vibgain').addEventListener('input', (e) => { settings.vibGain = Number(e.target.value) / 100; $('#vibgainval').textContent = `${settings.vibGain.toFixed(1).replace('.', ',')}×`; saveSettings(); });
  $('#forcesrc').addEventListener('change', (e) => { settings.forceSrc = e.target.value; saveSettings(); syncSim(); syncPads(); });
  $('#wake').addEventListener('change', (e) => { settings.wake = e.target.checked; saveSettings(); if (!settings.wake) sleepOk(); });
  $('#vibtest').addEventListener('click', () => vibInfo(WebHapticTransport.test([300])));
  $('#sound').checked = !!settings.sound; $('#soundfreq').value = settings.soundFreq; $('#soundfreqval').textContent = `${settings.soundFreq} Hz`;
  $('#sound').addEventListener('change', (e) => setSound(e.target.checked));
  $('#soundfreq').addEventListener('input', (e) => { settings.soundFreq = Number(e.target.value); $('#soundfreqval').textContent = `${settings.soundFreq} Hz`; saveSettings(); });
  $('#soundtest').addEventListener('click', soundTest);
  $('#vibyes').addEventListener('click', () => { settings.vibFelt = true; saveSettings(); $('#vibask').hidden = true; $('#vibinfo').textContent = 'Tốt: động cơ rung hoạt động. Chọn kiểu rung và cường độ dễ cảm nhận nhất bằng “Thử mẫu mạch hiện tại”.'; });
  $('#vibno').addEventListener('click', () => {
    settings.vibFelt = false; setSound(true); $('#vibask').hidden = true; soundTest();
    $('#vibinfo').textContent = 'Đã bật kênh “rung bằng loa” thay thế (tăng âm lượng media; đặt máy trong lòng bàn tay để cảm nhận qua thân máy).\n'
      + 'Để động cơ rung chạy lại, kiểm tra trên Android: (1) tắt chế độ Im lặng/Không làm phiền – Chrome không rung khi máy ở chế độ im lặng; '
      + '(2) Cài đặt → Âm thanh và rung → Rung và phản hồi xúc giác: bật “Phản hồi khi chạm” và kéo cường độ lên; (3) tắt Tiết kiệm pin; rồi bấm Thử rung lại.';
  });
  $('#diag').addEventListener('click', async () => {
    const t = diagnostics(); $('#diagout').textContent = t; $('#diagout').hidden = false;
    try { await navigator.clipboard.writeText(t); $('#diag').textContent = 'Đã sao chép chẩn đoán'; } catch { $('#diag').textContent = 'Chọn và sao chép đoạn bên dưới'; }
  });
  $('#vibpulse').addEventListener('click', () => {
    if (!profile) return;
    const pat = C.vibrationPattern(C.synthWaveform(profile, 60000 / currentBpm()), 0.9, { mode: settings.vibMode, gain: settings.vibGain });
    vibInfo(pat.length ? WebHapticTransport.test(pat) : false);
  });
  $('#exportall').addEventListener('click', () => {
    const hist = jget(HIST_KEY, []);
    if (!hist.length) return;
    const header = 'ngay,mach,tay,bo,tang,dat,luc_tb\n';
    download(`pulsemaster-tat-ca-${new Date().toISOString().slice(0, 10)}.csv`, header + hist.map((h) => h.csv.split('\n').slice(1).join('\n')).join(''));
  });
  $('#clearhist').addEventListener('click', () => { store.remove(HIST_KEY); renderHistory(); });
  $('#bpm').addEventListener('input', (e) => { $('#bpmval').textContent = e.target.value; });
  $('#bpm').addEventListener('change', () => { if (profile) active.setProfile(profile, currentBpm()); });
  $('#sim').addEventListener('input', () => { $('#simval').textContent = `${$('#sim').value}%`; });
}

let deferred = null;
window.addEventListener('beforeinstallprompt', (e) => { e.preventDefault(); deferred = e; $('#install').hidden = false; });
$('#install').addEventListener('click', async () => { if (!deferred) return; deferred.prompt(); await deferred.userChoice; deferred = null; $('#install').hidden = true; });
$('#reload').addEventListener('click', () => location.reload());

if ('serviceWorker' in navigator && (location.protocol === 'https:' || location.hostname === 'localhost' || location.hostname === '127.0.0.1')) {
  navigator.serviceWorker.register('sw.js').then((reg) => {
    reg.addEventListener('updatefound', () => {
      const w = reg.installing;
      w?.addEventListener('statechange', () => { if (w.state === 'installed' && navigator.serviceWorker.controller) $('#update').hidden = false; });
    });
  }).catch((e) => console.warn('Không đăng ký được service worker', e));
}
window.addEventListener('pagehide', () => active?.stopFeedback());
document.addEventListener('visibilitychange', () => {
  if (document.hidden) { active?.stopFeedback(); sleepOk(); } else if (!$('#tab-exam').hidden) { active?.startFeedback(C.REGIONS); if (down.size) keepAwake(); }
});

// ---------- Khởi động (sau khi mọi hàm/biến đã được khai báo) ----------
function init() {
  initSettingsUi(); syncSim(); syncPads();
  if (!catalog) { for (const id of ['mode', 'pulse', 'source', 'skip', 'restart', 'results', 'bpm']) $(`#${id}`).disabled = true; return; }
  const sel = $('#pulse'), gs = $('#guess');
  for (const p of catalog.pulses) { sel.add(new Option(`${p.nameVi}  ${p.nameHan}`, p.id)); gs.add(new Option(`${p.nameVi}  ${p.nameHan}`, p.id)); }
  sel.addEventListener('change', () => applyProfile(catalog.require(sel.value)));
  $('#mode').addEventListener('change', (e) => setMode(e.target.value));
  $('#source').addEventListener('change', (e) => activate(e.target.value));
  $('#connect').addEventListener('click', () => ble.connect());
  $('#skip').addEventListener('click', () => { if (session.isFinished) return; session.skip(); prepare(); if (session.isFinished) { saveHistory(); showSummary(); } });
  $('#restart').addEventListener('click', restart);
  $('#results').addEventListener('click', showSummary);
  $('#guessbtn').addEventListener('click', guess);
  $('#nextround').addEventListener('click', newRound);
  $('#copysum').addEventListener('click', async () => { try { await navigator.clipboard.writeText(summaryText()); $('#copysum').textContent = 'Đã sao chép'; } catch { $('#copysum').textContent = 'Không sao chép được'; } });
  $('#dlsum').addEventListener('click', () => download(`pulsemaster-${profile.id}-${new Date().toISOString().slice(0, 10)}.csv`, currentCsv()));
  profile = catalog.pulses[0]; activate('phone'); applyProfile(profile); restart();
}
init();
