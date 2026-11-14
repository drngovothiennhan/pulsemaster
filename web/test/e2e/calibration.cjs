// Hiệu chuẩn với ngón tay ĐỨNG YÊN: chỉ phát sự kiện khi đổi mức ấn (như màn hình thật), phải đủ mẫu nhờ lấy mẫu theo đồng hồ.
const { chromium } = require('playwright');
const http = require('http'), fs = require('fs'), path = require('path');
const root = path.join(__dirname, '..', '..');
const types = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.json': 'application/json', '.webmanifest': 'application/manifest+json', '.png': 'image/png', '.svg': 'image/svg+xml' };
const srv = http.createServer((q, s) => { let f = path.join(root, decodeURIComponent(q.url.split('?')[0])); if (f.endsWith('/')) f += 'index.html'; fs.readFile(f, (e, d) => { if (e) { s.writeHead(404); s.end(); } else { s.writeHead(200, { 'content-type': types[path.extname(f)] || 'x' }); s.end(d); } }); });
const ok = (c, m) => { console.log((c ? 'PASS ' : 'FAIL ') + m); if (!c) process.exitCode = 1; };
const T = (ms) => new Promise((r) => setTimeout(r, ms));
(async () => {
  await new Promise((r) => srv.listen(8127, r));
  const b = await chromium.launch(); const ctx = await b.newContext({ viewport: { width: 390, height: 900 } });
  const page = await ctx.newPage(); const errs = [];
  page.on('console', (m) => { if (m.type() === 'error') errs.push(m.text()); }); page.on('pageerror', (e) => errs.push('pageerror: ' + e.message));
  await page.goto('http://localhost:8127/index.html'); await page.waitForFunction(() => document.querySelectorAll('#pulse option').length === 28);
  await page.click('#t-calib');
  const fire = (type, pressure, w) => page.evaluate(([type, pressure, w]) => {
    document.querySelector('#calpad').dispatchEvent(new PointerEvent(type, { pointerType: 'touch', pointerId: 7, pressure, width: w, height: w, bubbles: true }));
  }, [type, pressure, w]);
  const run = async (levels) => {
    await page.click('#cstart'); await fire('pointerdown', levels[0][0], levels[0][1]);
    await T(1700); await fire('pointermove', levels[1][0], levels[1][1]);
    await T(1700); await fire('pointermove', levels[2][0], levels[2][1]);
    await T(1900); await fire('pointerup', 0, 0);
  };
  await run([[0.15, 10], [0.4, 20], [0.8, 34]]);
  ok((await page.textContent('#cphase')) === 'Đã lưu hiệu chuẩn', 'ngón đứng yên (3 sự kiện/5 s) vẫn hiệu chuẩn được: ' + (await page.textContent('#cinfo')).slice(0, 70));
  const saved = await page.evaluate(() => JSON.parse(localStorage.getItem('pulsemaster.finger_cal_v1')));
  ok(saved.pressure && saved.size && saved.size.light < saved.size.medium && saved.size.medium < saved.size.hard, 'đã lưu cả kênh lực và kênh diện tích, tăng dần: ' + JSON.stringify(saved.size));
  // máy chỉ báo pressure cố định 0.5: kênh lực bị loại, vẫn dùng diện tích
  await run([[0.5, 10], [0.5, 20], [0.5, 34]]);
  const fixed = await page.evaluate(() => JSON.parse(localStorage.getItem('pulsemaster.finger_cal_v1')));
  ok(fixed.pressure === null && fixed.size, 'pressure cố định 0.5 → loại kênh lực, giữ diện tích');
  // máy không báo gì thay đổi: thất bại rõ ràng + nút chế độ kéo dọc
  await run([[0.5, 1], [0.5, 1], [0.5, 1]]);
  ok((await page.textContent('#cphase')) === 'Chưa đạt' && (await page.isVisible('#cdrag')), 'không có thay đổi → "Chưa đạt" + đề xuất chế độ kéo dọc');
  await page.click('#cdrag'); ok((await page.evaluate(() => JSON.parse(localStorage.getItem('pulsemaster.settings_v1')).forceSrc)) === 'drag', 'bấm nút → chuyển nguồn lực sang kéo dọc');
  ok(errs.length === 0, 'không có lỗi console' + (errs.length ? ': ' + errs.join(' | ') : ''));
  await b.close(); srv.close();
})().catch((e) => { console.error('E2E LỖI', e); process.exit(1); });
