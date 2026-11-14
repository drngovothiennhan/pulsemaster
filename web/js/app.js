import * as C from './core.js';
import { WebHapticTransport } from './haptic.js';
import { WebBleTransport } from './webble.js';

const $ = (s) => document.querySelector(s);
const store = {
  get(k) { try { return localStorage.getItem(k); } catch { return null; } },
  set(k, v) { try { localStorage.setItem(k, v); } catch { /* chế độ riêng tư: bỏ qua */ } },
  remove(k) { try { localStorage.removeItem(k); } catch { /* bỏ qua */ } },
};
const CAL_KEY = 'pulsemaster.finger_cal_v1';
const defaultCal = () => C.FingerCalibration.defaultSizeOnly(8, 18, 32); // px tiếp xúc phổ biến; nên hiệu chuẩn
function loadCal() {
  const raw = store.get(CAL_KEY);
  if (!raw) return defaultCal();
  try { return C.FingerCalibration.fromJSON(JSON.parse(raw)); } catch { return defaultCal(); }
}

// ---------- Tab ----------
const tabs = ['exam', 'calib', 'about'];
for (const t of tabs) {
  $(`#t-${t}`).addEventListener('click', () => {
    for (const u of tabs) { $(`#t-${u}`).setAttribute('aria-selected', String(u === t)); $(`#tab-${u}`).hidden = u !== t; }
    if (t !== 'exam') active?.stopFeedback(); else active?.startFeedback(C.REGIONS);
  });
}

// ---------- Khởi tạo dữ liệu ----------
let catalog = null;
try {
  const res = await fetch('data/tcm_28_pulses_haptic.json');
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  catalog = C.parseCatalog(await res.text());
} catch (e) {
  const box = $('#fatal'); box.hidden = false;
  box.textContent = `Không nạp được dữ liệu mạch: ${e.message}`;
}

// ---------- Trạng thái khám ----------
let phone = new WebHapticTransport(loadCal());
let ble = new WebBleTransport();
let active = phone;
let profile = null;
let session = new C.ExamSession();
let hold = null;
let held = [];
const beats = []; // cho đồ thị
const regionEls = Object.fromEntries(C.REGIONS.map((r) => [r, document.querySelector(`.pad[data-region="${r}"]`)]));
let gaugeForce = 0, gaugeIn = false, target = null;

const listener = {
  onState(s) {
    const el = $('#transport');
    if (s.name === 'idle') el.textContent = active === ble ? 'Chưa kết nối đệm – bấm “Kết nối đệm”' : '';
    else if (s.name === 'connecting') el.textContent = 'Đang kết nối đệm…';
    else if (s.name === 'ready') {
      el.textContent = s.note || (active === ble ? 'Đã kết nối đệm 3 kênh (BLE)' : WebHapticTransport.canVibrate ? 'Dùng động cơ rung của thiết bị' : '');
      active.startFeedback(C.REGIONS);
    } else el.textContent = `Lỗi: ${s.reason}`;
    el.style.color = s.name === 'failed' ? 'var(--accent)' : '';
  },
  onForce(region, force, depth, t) {
    regionEls[region].dataset.depth = depth;
    regionEls[region].querySelector('small').textContent = depth ? C.DEPTH_LABEL[depth].split(' ')[0] : '';
    const pt = session.current;
    if (!pt || region !== pt.region) return;
    gaugeForce = force; gaugeIn = depth === pt.depth; drawGauge();
    hold?.onSample(depth, t);
    if (depth === pt.depth) held.push(force); else held.length = 0;
  },
  onBeat(e) {
    beats.push({ start: e.startMs, peak: e.peak, wave: e.plan.waveform, silent: e.silent });
    requestWave();
    const pt = session.current;
    if (!pt || !hold) return;
    if (hold.onBeat(e.startMs)) capture(pt);
    refreshStatus();
  },
};

function select(kind) {
  active.stopFeedback(); active.close(); active.listener = null;
  if (kind === 'phone') phone.setCalibration(loadCal());
  active = kind === 'ble' ? ble : phone;
  active.listener = listener;
  $('#connect').hidden = kind !== 'ble';
  if (profile) active.setProfile(profile, profile.defaultBpm);
  active.open();
  beats.length = 0;
}

function prepare() {
  const pt = session.current;
  hold = pt ? new C.HoldTracker(pt.depth) : null;
  target = pt ? pt.depth : null; gaugeIn = false;
  for (const r of C.REGIONS) regionEls[r].classList.toggle('target', !!pt && pt.region === r);
  drawGauge(); refreshStatus();
}
function capture(pt) {
  session.record(held.length ? held.reduce((a, b) => a + b, 0) / held.length : 0, true);
  held = []; prepare();
  if (session.isFinished) showSummary();
}
function refreshStatus() {
  const pt = session.current;
  $('#status').textContent = pt
    ? `Điểm ${session.index + 1}/${session.total}: ${C.pointLabel(pt)}\nGiữ đúng tầng: ${hold?.consecutive ?? 0}/${hold?.required ?? 5} nhịp`
    : `Hoàn tất ${session.total}/${session.total} điểm`;
}
function showSummary() {
  $('#sumtext').textContent = `Kết quả: ${session.results.filter((r) => r.captured).length}/${session.total} đạt\n\n${session.results.length ? session.summary() : 'Chưa có điểm nào.'}`;
  const d = $('#summary'); if (!d.open) (d.showModal ? d.showModal() : d.setAttribute('open', ''));
}
function restart() { session = new C.ExamSession(); held = []; prepare(); }

// ---------- Pad: nhận PointerEvent ----------
const sim = $('#sim');
sim.addEventListener('input', () => { $('#simval').textContent = `${sim.value}%`; });
const down = new Set();
for (const r of C.REGIONS) {
  const el = regionEls[r];
  const feed = (e) => { if (active === phone && down.has(e.pointerId)) phone.onPointer(r, e, Number(sim.value) / 100); };
  el.addEventListener('pointerdown', (e) => { e.preventDefault(); down.add(e.pointerId); el.setPointerCapture(e.pointerId); feed(e); });
  el.addEventListener('pointermove', feed);
  const up = (e) => { if (down.delete(e.pointerId) && active === phone) phone.onRelease(r); };
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
  ctx.font = '12px system-ui'; ctx.textAlign = 'center'; ctx.fillStyle = css('--fg');
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
let recording = false, fingerDown = false, ticker = 0;
function ctick() {
  const now = performance.now();
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
    store.set(CAL_KEY, JSON.stringify(cal)); phone.setCalibration(cal);
    $('#cphase').textContent = 'Đã lưu hiệu chuẩn';
    const lines = [cal.pressure ? 'Cảm biến lực thật: CÓ (60% lực + 40% diện tích)' : 'Cảm biến lực thật: KHÔNG – dùng diện tích tiếp xúc'];
    if (cal.size) lines.push(`Diện tích: nhẹ ${cal.size.light.toFixed(1)} · vừa ${cal.size.medium.toFixed(1)} · mạnh ${cal.size.hard.toFixed(1)}`);
    if (cal.pressure) lines.push(`Lực: nhẹ ${cal.pressure.light.toFixed(2)} · vừa ${cal.pressure.medium.toFixed(2)} · mạnh ${cal.pressure.hard.toFixed(2)}`);
    $('#cinfo').textContent = lines.join(' | ');
  } catch (e) {
    $('#cphase').textContent = 'Chưa đạt';
    $('#cinfo').textContent = e instanceof C.CalibrationError ? e.message : `Lỗi: ${e.message}`;
  }
}
$('#cstart').addEventListener('click', () => { recording = true; $('#cinfo').textContent = ''; rec.start(performance.now()); clearTimeout(ticker); ctick(); });
$('#cclear').addEventListener('click', () => { store.remove(CAL_KEY); phone.setCalibration(defaultCal()); $('#cinfo').textContent = 'Đã xóa. Quay về giá trị mặc định.'; });
const cp = $('#calpad');
const csample = (e) => {
  if (e.pointerType === 'mouse') { $('#cinfo').textContent = 'Chuột không đo được lực ấn – hãy mở trang này trên điện thoại/máy tính bảng.'; return; }
  fingerDown = true;
  if (recording) rec.addSample(performance.now(), { pressure: e.pressure, size: Math.max(e.width || 0, e.height || 0) });
};
cp.addEventListener('pointerdown', (e) => { e.preventDefault(); cp.setPointerCapture(e.pointerId); csample(e); });
cp.addEventListener('pointermove', (e) => { if (fingerDown) csample(e); });
const cup = () => { fingerDown = false; };
cp.addEventListener('pointerup', cup); cp.addEventListener('pointercancel', cup);

// ---------- Thiết bị & PWA ----------
const caps = [
  ['Rung (navigator.vibrate)', WebHapticTransport.canVibrate ? 'có' : 'KHÔNG (iOS Safari/Firefox…)'],
  ['Web Bluetooth (đệm 3 kênh)', WebBleTransport.supported ? 'có' : 'KHÔNG'],
  ['Ngữ cảnh an toàn (HTTPS)', window.isSecureContext ? 'có' : 'KHÔNG – cài đặt PWA/BLE cần HTTPS'],
  ['Màn hình cảm ứng', navigator.maxTouchPoints > 0 ? `có (${navigator.maxTouchPoints} điểm)` : 'không'],
  ['Hiệu chuẩn ngón tay', store.get(CAL_KEY) ? 'đã lưu' : 'chưa – đang dùng mặc định'],
];
$('#caps').replaceChildren(...caps.map(([k, v]) => { const li = document.createElement('li'); li.textContent = `${k}: ${v}`; return li; }));

let deferred = null;
window.addEventListener('beforeinstallprompt', (e) => { e.preventDefault(); deferred = e; $('#install').hidden = false; });
$('#install').addEventListener('click', async () => { if (!deferred) return; deferred.prompt(); await deferred.userChoice; deferred = null; $('#install').hidden = true; });

if ('serviceWorker' in navigator && (location.protocol === 'https:' || location.hostname === 'localhost' || location.hostname === '127.0.0.1')) {
  navigator.serviceWorker.register('sw.js').catch((e) => console.warn('Không đăng ký được service worker', e));
}
window.addEventListener('pagehide', () => active?.stopFeedback());
document.addEventListener('visibilitychange', () => { if (document.hidden) active?.stopFeedback(); else if (!$('#tab-exam').hidden) active?.startFeedback(C.REGIONS); });

// ---------- Khởi động (sau khi mọi hàm vẽ/biến đã được khai báo) ----------
function init() {
  if (catalog) {
    const sel = $('#pulse');
    for (const p of catalog.pulses) sel.add(new Option(`${p.nameVi}  ${p.nameHan}`, p.id));
    profile = catalog.pulses[0];
    const setFeel = () => { $('#feel').textContent = `${profile.category} · ${profile.clinicalFeel}`; };
    setFeel();
    sel.addEventListener('change', () => { profile = catalog.require(sel.value); setFeel(); active.setProfile(profile, profile.defaultBpm); beats.length = 0; });
    $('#source').addEventListener('change', (e) => select(e.target.value));
    $('#connect').addEventListener('click', () => ble.connect());
    $('#skip').addEventListener('click', () => { if (!session.isFinished) { session.skip(); held = []; prepare(); } });
    $('#restart').addEventListener('click', restart);
    $('#results').addEventListener('click', showSummary);
    select('phone'); restart();
  } else {
    for (const id of ['pulse', 'source', 'skip', 'restart', 'results']) $(`#${id}`).disabled = true;
  }
}
init();
