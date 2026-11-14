// Phản hồi máy thật 2026-11-14 (Android + Chrome: không rung, hiệu chuẩn không lưu, PWM dễ cảm nhận nhất):
// mặc định PWM, ngón tay chưa hiệu chuẩn → kéo dọc, "Không cảm thấy rung" → bật rung bằng loa, bản chẩn đoán.
const { chromium } = require('playwright');
const http = require('http'), fs = require('fs'), path = require('path');
const root = path.join(__dirname, '..', '..');
const types = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.json': 'application/json', '.webmanifest': 'application/manifest+json', '.png': 'image/png', '.svg': 'image/svg+xml' };
const srv = http.createServer((q, s) => { let f = path.join(root, decodeURIComponent(q.url.split('?')[0])); if (f.endsWith('/')) f += 'index.html'; fs.readFile(f, (e, d) => { if (e) { s.writeHead(404); s.end(); } else { s.writeHead(200, { 'content-type': types[path.extname(f)] || 'x' }); s.end(d); } }); });
const ok = (c, m) => { console.log((c ? 'PASS ' : 'FAIL ') + m); if (!c) process.exitCode = 1; };
const T = (ms) => new Promise((r) => setTimeout(r, ms));
(async () => {
  await new Promise((r) => srv.listen(8129, r));
  const b = await chromium.launch({ args: ['--autoplay-policy=no-user-gesture-required'] });
  const ctx = await b.newContext({ viewport: { width: 390, height: 900 }, hasTouch: true });
  await ctx.addInitScript(() => {
    window.__vib = []; navigator.vibrate = (p) => { window.__vib.push(Array.isArray(p) ? p.slice() : [p]); return true; };
    window.__osc = 0; const AC = window.AudioContext; if (AC) { const o = AC.prototype.createOscillator; AC.prototype.createOscillator = function () { window.__osc++; return o.call(this); }; }
  });
  const page = await ctx.newPage(); const errs = [];
  page.on('console', (m) => { if (m.type() === 'error') errs.push(m.text()); }); page.on('pageerror', (e) => errs.push('pageerror: ' + e.message));
  await page.goto('http://localhost:8129/index.html'); await page.waitForFunction(() => document.querySelectorAll('#pulse option').length === 28);
  // ngón tay trên pad: máy "không báo lực" (pressure cố định 0.5, cỡ chạm cố định 1 px) – chỉ vị trí dọc thay đổi
  const touch = (sel, type, frac, pressure = 0.5, w = 1) => page.evaluate(([sel, type, frac, pressure, w]) => {
    const el = document.querySelector(sel), r = el.getBoundingClientRect();
    el.dispatchEvent(new PointerEvent(type, { pointerType: 'touch', pointerId: 5, pressure, width: w, height: w, clientX: r.left + r.width / 2, clientY: r.top + r.height * frac, bubbles: true, cancelable: true }));
  }, [sel, type, frac, pressure, w]);
  const thon = '.pad[data-region="thon"]';
  const depth = () => page.getAttribute(thon, 'data-depth');

  ok((await page.inputValue('#vibmode')) === 'pwm', 'kiểu rung mặc định = PWM');
  ok(await page.evaluate(() => [...document.querySelectorAll('.pad')].every((p) => p.classList.contains('drag'))) && await page.isVisible('#draghelp'), 'chưa hiệu chuẩn → pad ở chế độ kéo dọc + có hướng dẫn');
  await touch(thon, 'pointerdown', 0.8); await T(150); const d1 = await depth();
  for (let i = 0; i < 12; i++) { await touch(thon, 'pointermove', 0.5); await T(20); } const d2 = await depth();
  for (let i = 0; i < 12; i++) { await touch(thon, 'pointermove', 0.1); await T(20); } const d3 = await depth();
  ok(d1 === '1' && d2 === '2' && d3 === '3', `máy không báo lực vẫn đổi tầng bằng kéo dọc: ${d1}/${d2}/${d3}`);
  ok(!(await page.isVisible('#hint')), 'không hiện cảnh báo "máy chưa báo lực" khi đang kéo dọc');
  await T(1200);
  const vib = await page.evaluate(() => window.__vib);
  ok(vib.some((v) => v.length > 3), 'rung theo mẫu PWM (nhiều đoạn bật/tắt): ' + JSON.stringify(vib.find((v) => v.length > 3) || []).slice(0, 60));
  ok((await page.evaluate(() => window.__osc)) === 0, 'loa tắt mặc định khi trình duyệt có rung');
  await touch(thon, 'pointerup', 0.1);

  // Thử rung → "Không cảm thấy gì" → bật rung bằng loa
  await page.click('#t-about'); await page.click('#vibtest');
  ok(await page.isVisible('#vibask'), 'sau Thử rung: hỏi người dùng có cảm thấy không');
  await page.click('#vibno'); await T(500);
  const st = await page.evaluate(() => JSON.parse(localStorage.getItem('pulsemaster.settings_v1')));
  ok(st.sound === true && st.vibFelt === false && await page.isChecked('#sound'), '"Không cảm thấy gì" → bật và lưu kênh rung bằng loa');
  const o1 = await page.evaluate(() => window.__osc);
  ok(o1 >= 1, 'phát thử một nhịp âm ngay: ' + o1);
  ok((await page.textContent('#vibinfo')).includes('Im lặng'), 'có hướng dẫn kiểm tra cài đặt Android');
  await page.click('#t-exam'); await touch(thon, 'pointerdown', 0.5); await T(2200); await touch(thon, 'pointerup', 0.5);
  const o2 = await page.evaluate(() => window.__osc);
  ok(o2 >= o1 + 2, `khi bắt mạch: mỗi nhịp phát một âm (${o2 - o1} nhịp trong 2,2 s)`);

  // Chẩn đoán
  await page.click('#t-about'); await page.click('#diag');
  const dg = await page.textContent('#diagout');
  ok(dg.includes('UA: ') && dg.includes('pressure 0.500–0.500') && dg.includes('drag') && dg.includes('KHÔNG'), 'bản chẩn đoán có UA, khoảng giá trị thô, nguồn lực, trả lời rung');

  // Hiệu chuẩn thất bại → có số đo từng pha; thành công → rời chế độ kéo dọc sang cảm biến
  await page.click('#t-calib');
  const run = async (lv) => { await page.click('#cstart'); await touch('#calpad', 'pointerdown', 0.5, lv[0][0], lv[0][1]); await T(1700); await touch('#calpad', 'pointermove', 0.5, lv[1][0], lv[1][1]); await T(1700); await touch('#calpad', 'pointermove', 0.5, lv[2][0], lv[2][1]); await T(1900); await touch('#calpad', 'pointerup', 0.5, 0, 0); };
  await run([[0.5, 1], [0.5, 1], [0.5, 1]]);
  const cs = await page.textContent('#cstats');
  ok((await page.textContent('#cphase')) === 'Chưa đạt' && cs.includes('nhẹ:') && cs.includes('pressure 0.500'), 'hiệu chuẩn không được → hiện số đo từng pha: ' + cs.split('\n')[0]);
  await run([[0.5, 10], [0.5, 20], [0.5, 34]]);
  ok((await page.textContent('#cphase')) === 'Đã lưu hiệu chuẩn', 'máy có báo diện tích → hiệu chuẩn được');
  await page.click('#t-exam');
  ok(await page.evaluate(() => [...document.querySelectorAll('.pad')].every((p) => !p.classList.contains('drag'))), 'đã hiệu chuẩn → tự chuyển sang cảm biến (hết kéo dọc)');
  await touch(thon, 'pointerdown', 0.9, 0.5, 34); for (let i = 0; i < 12; i++) { await touch(thon, 'pointermove', 0.9, 0.5, 34); await T(20); }
  ok((await depth()) === '3', 'cảm biến: chạm rộng 34 px ở đáy pad = Trầm (không còn phụ thuộc vị trí)');
  await touch(thon, 'pointerup', 0.9);
  ok(errs.length === 0, 'không có lỗi console' + (errs.length ? ': ' + errs.join(' | ') : ''));
  await b.close(); srv.close();
})().catch((e) => { console.error('E2E LỖI', e); process.exit(1); });
